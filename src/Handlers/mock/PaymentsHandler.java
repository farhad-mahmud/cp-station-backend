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
 * Student-facing payment endpoints for the manual bKash flow. Registered behind
 * AuthDecorator.
 *
 * Routes:
 *   GET  /payments/instructions?bookingId=   where to send the money
 *   POST /payments/submit                    declare the bKash Transaction ID
 *
 * Submitting a transaction id does NOT confirm the booking. It moves the
 * booking to 'payment_review'; only an admin verifying the transaction in the
 * console confirms it. Nothing a student can send is ever treated as proof of
 * payment on its own.
 */
public class PaymentsHandler extends AbstractHttpHandler {

    /** PostgreSQL unique-violation SQLSTATE. */
    private static final String UNIQUE_VIOLATION = "23505";

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

    // ── GET /payments/instructions?bookingId= ──────────────────────────────

    private void handleInstructions(HttpExchange exchange, int userId) throws Exception {
        Integer bookingId = MockInterviewSupport.intParam(
                MockInterviewSupport.queryParams(exchange), "bookingId");
        if (bookingId == null) {
            sendError(exchange, 400, "Missing or invalid query parameter: bookingId");
            return;
        }

        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT b.amount_bdt, b.status, b.hold_expires_at, " +
                    "       p.idempotency_key, p.status AS payment_status, p.provider " +
                    "  FROM bookings b " +
                    "  LEFT JOIN LATERAL (SELECT idempotency_key, status, provider FROM payments " +
                    "                      WHERE booking_id = b.id ORDER BY id DESC LIMIT 1) p ON TRUE " +
                    " WHERE b.id = ? AND b.student_id = ?")) {
                stmt.setInt(1, bookingId);
                stmt.setInt(2, userId);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    sendError(exchange, 404, "Booking not found");
                    return;
                }

                int amount = rs.getInt("amount_bdt");
                String bookingStatus = rs.getString("status");
                String key = rs.getString("idempotency_key");

                PaymentGateway gateway = PaymentGatewayFactory.forProvider(rs.getString("provider"));
                PaymentIntent intent = gateway.initiate(bookingId, amount, key);

                Map<String, Object> response = new HashMap<>();
                response.put("booking_id", bookingId);
                response.put("booking_status", bookingStatus);
                response.put("payment_status", rs.getString("payment_status"));
                response.put("hold_expires_at", MockInterviewSupport.iso(rs.getTimestamp("hold_expires_at")));
                response.put("requires_manual_verification", gateway.requiresManualVerification());
                response.put("payment", intent.toMap());
                sendJSON(exchange, 200, response);
            }
        } finally {
            conn.close();
        }
    }

    // ── POST /payments/submit ──────────────────────────────────────────────

    private void handleSubmit(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));

        if (!json.has("booking_id")) {
            sendError(exchange, 400, "Missing required field: booking_id");
            return;
        }
        int bookingId = json.get("booking_id").asInt();
        String trxId = json.has("trx_id") ? json.get("trx_id").asText("").trim() : "";
        String msisdn = json.has("payer_msisdn") ? json.get("payer_msisdn").asText("").trim() : "";

        Connection conn = DbConnection.getConnection();
        boolean committed = false;
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);
            conn.setAutoCommit(false);

            int paymentId;
            String provider;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT b.status AS booking_status, p.id AS payment_id, p.provider, p.status AS payment_status " +
                    "  FROM bookings b " +
                    "  LEFT JOIN LATERAL (SELECT id, provider, status FROM payments " +
                    "                      WHERE booking_id = b.id ORDER BY id DESC LIMIT 1) p ON TRUE " +
                    " WHERE b.id = ? AND b.student_id = ?")) {
                stmt.setInt(1, bookingId);
                stmt.setInt(2, userId);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    conn.rollback();
                    sendError(exchange, 404, "Booking not found");
                    return;
                }

                String bookingStatus = rs.getString("booking_status");
                if ("expired".equals(bookingStatus)) {
                    conn.rollback();
                    sendError(exchange, 409, "Your payment hold expired and the slot was released. Please book again.");
                    return;
                }
                if (!"pending_payment".equals(bookingStatus)) {
                    conn.rollback();
                    sendError(exchange, 409, "This booking is not awaiting payment (current status: " + bookingStatus + ").");
                    return;
                }

                paymentId = rs.getInt("payment_id");
                if (rs.wasNull()) {
                    conn.rollback();
                    sendError(exchange, 500, "No payment record exists for this booking.");
                    return;
                }
                provider = rs.getString("provider");
            }

            PaymentGateway gateway = PaymentGatewayFactory.forProvider(provider);
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
                stmt.setInt(3, paymentId);
                stmt.executeUpdate();
            } catch (SQLException e) {
                if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    conn.rollback();
                    sendError(exchange, 409, "That Transaction ID has already been submitted for another booking.");
                    return;
                }
                throw e;
            }

            // Clearing the hold stops the sweeper from releasing a slot whose
            // payment is genuinely sitting in the admin review queue.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE bookings SET status = 'payment_review', hold_expires_at = NULL WHERE id = ?")) {
                stmt.setInt(1, bookingId);
                stmt.executeUpdate();
            }

            conn.commit();
            committed = true;

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("status", "payment_review");
            response.put("message", "Transaction submitted. Your slot is reserved while we verify the payment — "
                    + "you will get the meeting link as soon as it is confirmed.");
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
}
