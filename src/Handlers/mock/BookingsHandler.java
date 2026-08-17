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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Student-facing booking lifecycle. Registered behind AuthDecorator, so every
 * request here already carries a valid session.
 *
 * Routes:
 *   POST /bookings         claim a slot and open a payment
 *   GET  /my-bookings      the student's bookings, newest first
 *   POST /bookings/cancel  release a slot the student no longer wants
 *   POST /feedback         rate the mentor after a completed session
 */
public class BookingsHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        Integer userId = MockInterviewSupport.userId(exchange);
        if (userId == null) {
            sendError(exchange, 401, "Unauthorized: Please log in to proceed.");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        if (path.equals("/bookings") && method.equals("POST")) {
            handleCreate(exchange, userId);
        } else if (path.equals("/my-bookings") && method.equals("GET")) {
            handleMyBookings(exchange, userId);
        } else if (path.equals("/bookings/cancel") && method.equals("POST")) {
            handleCancel(exchange, userId);
        } else if (path.equals("/feedback") && method.equals("POST")) {
            handleFeedback(exchange, userId);
        } else if (path.equals("/bookings") || path.equals("/my-bookings")
                || path.equals("/bookings/cancel") || path.equals("/feedback")) {
            sendError(exchange, 405, "Method Not Allowed");
        } else {
            sendError(exchange, 404, "Not Found");
        }
    }

    // ── POST /bookings ─────────────────────────────────────────────────────

    /**
     * Claims a slot for the caller.
     *
     * The whole thing runs in one transaction, and the slot is claimed with a
     * conditional UPDATE (... WHERE status = 'open') rather than a read followed
     * by a write. If two students press Book in the same instant, exactly one
     * UPDATE reports a changed row; the loser gets a 409 and the slot is never
     * sold twice.
     */
    private void handleCreate(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));

        Integer slotId = json.has("slot_id") && !json.get("slot_id").isNull() ? json.get("slot_id").asInt() : null;
        String stack = json.has("stack") ? json.get("stack").asText("").trim() : "";
        String notes = json.has("notes") ? json.get("notes").asText("").trim() : "";

        if (slotId == null) {
            sendError(exchange, 400, "Missing required field: slot_id");
            return;
        }
        if (stack.isEmpty()) {
            sendError(exchange, 400, "Missing required field: stack (the topic you want to be interviewed on)");
            return;
        }
        if (notes.length() > 2000) {
            notes = notes.substring(0, 2000);
        }

        Connection conn = DbConnection.getConnection();
        boolean committed = false;
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);
            conn.setAutoCommit(false);

            // Resolve slot + mentor + price in one read.
            int mentorId;
            int mentorUserId;
            int rate;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT s.mentor_id, s.status AS slot_status, s.start_at, " +
                    "       m.user_id, m.hourly_rate_bdt, m.status AS mentor_status " +
                    "  FROM mentor_slots s JOIN mentors m ON m.id = s.mentor_id " +
                    " WHERE s.id = ?")) {
                stmt.setInt(1, slotId);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    conn.rollback();
                    sendError(exchange, 404, "Slot not found");
                    return;
                }
                mentorId = rs.getInt("mentor_id");
                mentorUserId = rs.getInt("user_id");
                rate = rs.getInt("hourly_rate_bdt");

                if (!"approved".equalsIgnoreCase(rs.getString("mentor_status"))) {
                    conn.rollback();
                    sendError(exchange, 409, "This mentor is not currently accepting bookings.");
                    return;
                }
                if (rs.getTimestamp("start_at").toInstant().isBefore(java.time.Instant.now())) {
                    conn.rollback();
                    sendError(exchange, 409, "That slot is already in the past.");
                    return;
                }
            }

            if (mentorUserId == userId) {
                conn.rollback();
                sendError(exchange, 400, "You cannot book an interview with yourself.");
                return;
            }

            // Atomic claim. Zero rows updated means somebody else won the race.
            int claimed;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE mentor_slots SET status = 'held' WHERE id = ? AND status = 'open'")) {
                stmt.setInt(1, slotId);
                claimed = stmt.executeUpdate();
            }
            if (claimed == 0) {
                conn.rollback();
                sendError(exchange, 409, "That slot was just taken. Please pick another one.");
                return;
            }

            int fee = MockInterviewSupport.platformFee(rate);

            int bookingId;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO bookings (slot_id, mentor_id, student_id, stack, notes, amount_bdt, " +
                    "                      platform_fee_bdt, status, hold_expires_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'pending_payment', NOW() + (? || ' minutes')::interval) " +
                    "RETURNING id")) {
                stmt.setInt(1, slotId);
                stmt.setInt(2, mentorId);
                stmt.setInt(3, userId);
                stmt.setString(4, stack);
                stmt.setString(5, notes.isEmpty() ? null : notes);
                stmt.setInt(6, rate);
                stmt.setInt(7, fee);
                stmt.setString(8, String.valueOf(MockInterviewSupport.HOLD_MINUTES));
                ResultSet rs = stmt.executeQuery();
                rs.next();
                bookingId = rs.getInt("id");
            }

            PaymentGateway gateway = PaymentGatewayFactory.getActiveGateway();
            String idempotencyKey = gateway.name() + ":booking:" + bookingId + ":1";

            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO payments (booking_id, provider, amount_bdt, status, idempotency_key) " +
                    "VALUES (?, ?, ?, 'initiated', ?)")) {
                stmt.setInt(1, bookingId);
                stmt.setString(2, gateway.name());
                stmt.setInt(3, rate);
                stmt.setString(4, idempotencyKey);
                stmt.executeUpdate();
            }

            conn.commit();
            committed = true;

            PaymentIntent intent = gateway.initiate(
                    MockInterviewSupport.bookingReference(bookingId), rate, idempotencyKey);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("booking_id", bookingId);
            response.put("status", "pending_payment");
            response.put("amount_bdt", rate);
            response.put("hold_minutes", MockInterviewSupport.HOLD_MINUTES);
            response.put("payment", intent.toMap());
            sendJSON(exchange, 201, response);

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

    // ── GET /my-bookings ───────────────────────────────────────────────────

    private void handleMyBookings(HttpExchange exchange, int userId) throws Exception {
        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT b.id, b.stack, b.notes, b.amount_bdt, b.status, b.meeting_link, " +
                    "       b.hold_expires_at, b.created_at, " +
                    "       s.start_at, s.end_at, " +
                    "       m.id AS mentor_id, m.display_name, m.headline, m.company, m.photo_url, " +
                    "       p.status AS payment_status, p.provider_txn_id, " +
                    "       f.student_rating, f.mentor_verdict, f.mentor_notes " +
                    "  FROM bookings b " +
                    "  JOIN mentor_slots s ON s.id = b.slot_id " +
                    "  JOIN mentors m ON m.id = b.mentor_id " +
                    "  LEFT JOIN LATERAL (SELECT status, provider_txn_id FROM payments " +
                    "                      WHERE booking_id = b.id ORDER BY id DESC LIMIT 1) p ON TRUE " +
                    "  LEFT JOIN interview_feedback f ON f.booking_id = b.id " +
                    " WHERE b.student_id = ? " +
                    " ORDER BY s.start_at DESC")) {
                stmt.setInt(1, userId);
                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> bookings = new ArrayList<>();
                while (rs.next()) {
                    String status = rs.getString("status");
                    Map<String, Object> b = new HashMap<>();
                    b.put("id", rs.getInt("id"));
                    b.put("stack", rs.getString("stack"));
                    b.put("notes", rs.getString("notes"));
                    b.put("amount_bdt", rs.getInt("amount_bdt"));
                    b.put("status", status);
                    b.put("start_at", MockInterviewSupport.iso(rs.getTimestamp("start_at")));
                    b.put("end_at", MockInterviewSupport.iso(rs.getTimestamp("end_at")));
                    b.put("hold_expires_at", MockInterviewSupport.iso(rs.getTimestamp("hold_expires_at")));
                    b.put("created_at", MockInterviewSupport.iso(rs.getTimestamp("created_at")));
                    b.put("mentor_id", rs.getInt("mentor_id"));
                    b.put("mentor_name", rs.getString("display_name"));
                    b.put("mentor_headline", rs.getString("headline"));
                    b.put("mentor_company", rs.getString("company"));
                    b.put("mentor_photo", rs.getString("photo_url"));
                    b.put("payment_status", rs.getString("payment_status"));
                    b.put("trx_id", rs.getString("provider_txn_id"));

                    // The meeting link is the paid-for good: only release it once
                    // the money has actually been verified.
                    boolean paidFor = "confirmed".equals(status) || "completed".equals(status);
                    b.put("meeting_link", paidFor ? rs.getString("meeting_link") : null);

                    int rating = rs.getInt("student_rating");
                    b.put("my_rating", rs.wasNull() ? null : rating);
                    b.put("mentor_verdict", rs.getString("mentor_verdict"));
                    b.put("mentor_notes", rs.getString("mentor_notes"));

                    bookings.add(b);
                }
                sendJSON(exchange, 200, bookings);
            }
        } finally {
            conn.close();
        }
    }

    // ── POST /bookings/cancel ──────────────────────────────────────────────

    private void handleCancel(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));
        if (!json.has("booking_id")) {
            sendError(exchange, 400, "Missing required field: booking_id");
            return;
        }
        int bookingId = json.get("booking_id").asInt();

        Connection conn = DbConnection.getConnection();
        boolean committed = false;
        try {
            conn.setAutoCommit(false);

            int slotId;
            String status;
            boolean alreadyPaid;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT b.slot_id, b.status, s.start_at, " +
                    "       EXISTS (SELECT 1 FROM payments p WHERE p.booking_id = b.id AND p.status = 'paid') AS paid " +
                    "  FROM bookings b JOIN mentor_slots s ON s.id = b.slot_id " +
                    " WHERE b.id = ? AND b.student_id = ?")) {
                stmt.setInt(1, bookingId);
                stmt.setInt(2, userId);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    conn.rollback();
                    sendError(exchange, 404, "Booking not found");
                    return;
                }
                slotId = rs.getInt("slot_id");
                status = rs.getString("status");
                alreadyPaid = rs.getBoolean("paid");
            }

            if (!status.equals("pending_payment") && !status.equals("payment_review") && !status.equals("confirmed")) {
                conn.rollback();
                sendError(exchange, 409, "A booking with status '" + status + "' can no longer be cancelled.");
                return;
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE bookings SET status = 'cancelled', hold_expires_at = NULL WHERE id = ?")) {
                stmt.setInt(1, bookingId);
                stmt.executeUpdate();
            }

            // Put the slot back on sale unless it has already started.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE mentor_slots SET status = 'open' WHERE id = ? AND start_at > NOW()")) {
                stmt.setInt(1, slotId);
                stmt.executeUpdate();
            }

            // Money that never arrived is simply dropped; money that did arrive
            // goes into the admin refund queue rather than being auto-reversed.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE payments SET status = CASE WHEN status = 'paid' THEN 'refund_pending' ELSE 'failed' END, " +
                    "       updated_at = NOW() " +
                    " WHERE booking_id = ? AND status IN ('initiated', 'submitted', 'paid')")) {
                stmt.setInt(1, bookingId);
                stmt.executeUpdate();
            }

            conn.commit();
            committed = true;

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("status", "cancelled");
            response.put("refund_pending", alreadyPaid);
            response.put("message", alreadyPaid
                    ? "Booking cancelled. Your refund has been queued for review."
                    : "Booking cancelled and the slot has been released.");
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

    // ── POST /feedback ─────────────────────────────────────────────────────

    private void handleFeedback(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));

        if (!json.has("booking_id") || !json.has("rating")) {
            sendError(exchange, 400, "Missing required fields: booking_id and rating");
            return;
        }
        int bookingId = json.get("booking_id").asInt();
        int rating = json.get("rating").asInt();
        String comment = json.has("comment") ? json.get("comment").asText("").trim() : "";

        if (rating < 1 || rating > 5) {
            sendError(exchange, 400, "Rating must be between 1 and 5.");
            return;
        }

        Connection conn = DbConnection.getConnection();
        try {
            int mentorId;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT mentor_id, status FROM bookings WHERE id = ? AND student_id = ?")) {
                stmt.setInt(1, bookingId);
                stmt.setInt(2, userId);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    sendError(exchange, 404, "Booking not found");
                    return;
                }
                mentorId = rs.getInt("mentor_id");
                if (!"completed".equals(rs.getString("status"))) {
                    sendError(exchange, 409, "You can only review an interview after it has been completed.");
                    return;
                }
            }

            // The mentor may already have written their evaluation into this row,
            // so upsert the student's half rather than inserting a second row.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO interview_feedback (booking_id, student_rating, student_comment) " +
                    "VALUES (?, ?, ?) " +
                    "ON CONFLICT (booking_id) DO UPDATE SET " +
                    "  student_rating = EXCLUDED.student_rating, student_comment = EXCLUDED.student_comment")) {
                stmt.setInt(1, bookingId);
                stmt.setInt(2, rating);
                stmt.setString(3, comment.isEmpty() ? null : comment);
                stmt.executeUpdate();
            }

            MockInterviewSupport.recomputeRating(conn, mentorId);

            sendJSON(exchange, 200, Map.of("success", true, "message", "Thanks for reviewing your interview."));
        } finally {
            conn.close();
        }
    }
}
