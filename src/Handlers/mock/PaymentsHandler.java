package Handlers.mock;

import Handlers.AbstractHttpHandler;
import Services.payment.PaymentGateway;
import Services.payment.PaymentGatewayFactory;
import Services.payment.PaymentIntent;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import config.DbConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Student-facing payment endpoints for the manual bKash flow, shared by both
 * products: a mock interview booking and a virtual classroom enrollment.
 * Registered behind AuthDecorator.
 *
 * Routes:
 *   GET  /payments/instructions?bookingId= | ?enrollmentId=
 *   POST /payments/submit  { booking_id | enrollment_id, trx_id, payer_msisdn }
 *
 * Submitting a transaction id does NOT confirm anything. It moves the purchase
 * to 'payment_review'; only an admin verifying the transaction releases the
 * goods. Nothing a student can send is ever treated as proof of payment.
 */
public class PaymentsHandler extends AbstractHttpHandler {

    /** PostgreSQL unique-violation SQLSTATE. */
    private static final String UNIQUE_VIOLATION = "23505";

    /** What a payment is for. */
    private enum Kind { BOOKING, ENROLLMENT }

    /** Resolved purchase being paid for. */
    private static class Target {
        Kind kind;
        int id;
        int paymentId;
        int amountBdt;
        String purchaseStatus;
        String paymentStatus;
        String provider;
        String reference;

        String table() {
            return kind == Kind.BOOKING ? "bookings" : "enrollments";
        }

        String noun() {
            return kind == Kind.BOOKING ? "booking" : "enrollment";
        }
    }

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        Integer userId = MockInterviewSupport.userId(exchange);
        if (userId == null) {
            sendError(exchange, 401, "Unauthorized: Please log in to proceed.");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        if (path.equals("/payments/instructions") && method.equals("GET")) {
            handleInstructions(exchange, userId);
        } else if (path.equals("/payments/submit") && method.equals("POST")) {
            handleSubmit(exchange, userId);
        } else if (path.startsWith("/payments/")) {
            sendError(exchange, 405, "Method Not Allowed");
        } else {
            sendError(exchange, 404, "Not Found");
        }
    }

    // ── GET /payments/instructions ─────────────────────────────────────────

    private void handleInstructions(HttpExchange exchange, int userId) throws Exception {
        Map<String, String> params = MockInterviewSupport.queryParams(exchange);
        Integer bookingId = MockInterviewSupport.intParam(params, "bookingId");
        Integer enrollmentId = MockInterviewSupport.intParam(params, "enrollmentId");

        if ((bookingId == null) == (enrollmentId == null)) {
            sendError(exchange, 400, "Provide exactly one of bookingId or enrollmentId.");
            return;
        }

        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);
            MockInterviewSupport.releaseExpiredEnrollments(conn);

            Target target = bookingId != null
                    ? loadTarget(conn, Kind.BOOKING, bookingId, userId)
                    : loadTarget(conn, Kind.ENROLLMENT, enrollmentId, userId);

            if (target == null) {
                sendError(exchange, 404, (bookingId != null ? "Booking" : "Enrollment") + " not found");
                return;
            }

            String holdExpiry;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT hold_expires_at FROM " + target.table() + " WHERE id = ?")) {
                stmt.setInt(1, target.id);
                ResultSet rs = stmt.executeQuery();
                rs.next();
                holdExpiry = MockInterviewSupport.iso(rs.getTimestamp("hold_expires_at"));
            }

            PaymentGateway gateway = PaymentGatewayFactory.forProvider(target.provider);
            PaymentIntent intent = gateway.initiate(target.reference, target.amountBdt, null);

            Map<String, Object> response = new HashMap<>();
            response.put("kind", target.noun());
            response.put(target.noun() + "_id", target.id);
            response.put("purchase_status", target.purchaseStatus);
            response.put("payment_status", target.paymentStatus);
            response.put("hold_expires_at", holdExpiry);
            response.put("requires_manual_verification", gateway.requiresManualVerification());
            response.put("payment", intent.toMap());
            sendJSON(exchange, 200, response);
        } finally {
            conn.close();
        }
    }

    // ── POST /payments/submit ──────────────────────────────────────────────

    private void handleSubmit(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));

        boolean hasBooking = json.has("booking_id") && !json.get("booking_id").isNull();
        boolean hasEnrollment = json.has("enrollment_id") && !json.get("enrollment_id").isNull();
        if (hasBooking == hasEnrollment) {
            sendError(exchange, 400, "Provide exactly one of booking_id or enrollment_id.");
            return;
        }

        Kind kind = hasBooking ? Kind.BOOKING : Kind.ENROLLMENT;
        int purchaseId = hasBooking ? json.get("booking_id").asInt() : json.get("enrollment_id").asInt();
        String trxId = json.has("trx_id") ? json.get("trx_id").asText("").trim() : "";
        String msisdn = json.has("payer_msisdn") ? json.get("payer_msisdn").asText("").trim() : "";

        Connection conn = DbConnection.getConnection();
        boolean committed = false;
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);
            MockInterviewSupport.releaseExpiredEnrollments(conn);
            conn.setAutoCommit(false);

            Target target = loadTarget(conn, kind, purchaseId, userId);
            if (target == null) {
                conn.rollback();
                sendError(exchange, 404, (kind == Kind.BOOKING ? "Booking" : "Enrollment") + " not found");
                return;
            }

            if ("expired".equals(target.purchaseStatus)) {
                conn.rollback();
                sendError(exchange, 409, kind == Kind.BOOKING
                        ? "Your payment hold expired and the slot was released. Please book again."
                        : "Your payment hold expired and the seat was released. Please enroll again.");
                return;
            }
            if (!"pending_payment".equals(target.purchaseStatus)) {
                conn.rollback();
                sendError(exchange, 409, "This " + target.noun()
                        + " is not awaiting payment (current status: " + target.purchaseStatus + ").");
                return;
            }
            if (target.paymentId == 0) {
                conn.rollback();
                sendError(exchange, 500, "No payment record exists for this " + target.noun() + ".");
                return;
            }

            PaymentGateway gateway = PaymentGatewayFactory.forProvider(target.provider);
            Map<String, String> submission = new HashMap<>();
            submission.put("trx_id", trxId);
            submission.put("payer_msisdn", msisdn);

            String rejection = gateway.validateSubmission(submission);
            if (rejection != null) {
                conn.rollback();
                sendError(exchange, 400, rejection);
                return;
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE payments SET status = 'submitted', provider_txn_id = ?, payer_msisdn = ?, " +
                    "       updated_at = NOW() WHERE id = ?")) {
                stmt.setString(1, trxId.toUpperCase());
                stmt.setString(2, msisdn.isEmpty() ? null : msisdn);
                stmt.setInt(3, target.paymentId);
                stmt.executeUpdate();
            } catch (SQLException e) {
                if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    conn.rollback();
                    sendError(exchange, 409, "That Transaction ID has already been submitted for another purchase.");
                    return;
                }
                throw e;
            }

            // Clearing the hold stops the sweeper from releasing a slot or seat
            // whose payment is genuinely sitting in the admin review queue.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE " + target.table() +
                    "   SET status = 'payment_review', hold_expires_at = NULL WHERE id = ?")) {
                stmt.setInt(1, target.id);
                stmt.executeUpdate();
            }

            conn.commit();
            committed = true;

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("status", "payment_review");
            response.put("message", "Transaction submitted. Your "
                    + (kind == Kind.BOOKING ? "slot" : "seat")
                    + " is reserved while we verify the payment — you will get access as soon as it is confirmed.");
            sendJSON(exchange, 200, response);

        } catch (Exception e) {
            try {
                if (!committed) conn.rollback();
            } catch (Exception ignored) {
            }
            throw e;
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (Exception ignored) {
            }
            conn.close();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Loads a purchase and its latest payment, scoped to the calling student so
     * one student can never touch another's payment. Returns null when there is
     * no such purchase for this user.
     */
    private Target loadTarget(Connection conn, Kind kind, int id, int userId) throws Exception {
        String table = kind == Kind.BOOKING ? "bookings" : "enrollments";
        String fk = kind == Kind.BOOKING ? "booking_id" : "enrollment_id";

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT t.status AS purchase_status, t.amount_bdt, " +
                "       p.id AS payment_id, p.provider, p.status AS payment_status " +
                "  FROM " + table + " t " +
                "  LEFT JOIN LATERAL (SELECT id, provider, status FROM payments " +
                "                      WHERE " + fk + " = t.id ORDER BY id DESC LIMIT 1) p ON TRUE " +
                " WHERE t.id = ? AND t.student_id = ?")) {
            stmt.setInt(1, id);
            stmt.setInt(2, userId);
            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) {
                return null;
            }

            Target target = new Target();
            target.kind = kind;
            target.id = id;
            target.amountBdt = rs.getInt("amount_bdt");
            target.purchaseStatus = rs.getString("purchase_status");
            target.paymentId = rs.getInt("payment_id");
            if (rs.wasNull()) {
                target.paymentId = 0;
            }
            target.provider = rs.getString("provider");
            target.paymentStatus = rs.getString("payment_status");
            target.reference = kind == Kind.BOOKING
                    ? MockInterviewSupport.bookingReference(id)
                    : MockInterviewSupport.enrollmentReference(id);
            return target;
        }
    }
}
