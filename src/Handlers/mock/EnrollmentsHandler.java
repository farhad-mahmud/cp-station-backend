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
 * Student side of the Virtual Classroom. Registered behind AuthDecorator.
 *
 * Routes:
 *   POST /enrollments         claim a seat and open a payment
 *   GET  /my-enrollments      courses the student has joined, with syllabus progress
 *   POST /enrollments/cancel  leave a course and free the seat
 */
public class EnrollmentsHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        Integer userId = MockInterviewSupport.userId(exchange);
        if (userId == null) {
            sendError(exchange, 401, "Unauthorized: Please log in to proceed.");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        if (path.equals("/enrollments") && method.equals("POST")) {
            handleEnroll(exchange, userId);
        } else if (path.equals("/my-enrollments") && method.equals("GET")) {
            handleMyEnrollments(exchange, userId);
        } else if (path.equals("/enrollments/cancel") && method.equals("POST")) {
            handleCancel(exchange, userId);
        } else if (path.startsWith("/enrollments") || path.equals("/my-enrollments")) {
            sendError(exchange, 405, "Method Not Allowed");
        } else {
            sendError(exchange, 404, "Not Found");
        }
    }

    // ── POST /enrollments ──────────────────────────────────────────────────

    /**
     * Claims one seat on a course.
     *
     * The seat is taken with a conditional UPDATE against the counter
     * (... WHERE enrolled_count < seat_limit), which is the same trick the slot
     * booking uses: if two students claim the last seat simultaneously exactly
     * one UPDATE reports a changed row, and the course can never oversell.
     */
    private void handleEnroll(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));

        Integer courseId = json.has("course_id") && !json.get("course_id").isNull()
                ? json.get("course_id").asInt() : null;
        String goal = json.has("goal") ? json.get("goal").asText("").trim() : "";

        if (courseId == null) {
            sendError(exchange, 400, "Missing required field: course_id");
            return;
        }
        if (goal.length() > 2000) {
            goal = goal.substring(0, 2000);
        }

        Connection conn = DbConnection.getConnection();
        boolean committed = false;
        try {
            MockInterviewSupport.releaseExpiredEnrollments(conn);
            conn.setAutoCommit(false);

            int price;
            int mentorUserId;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT c.price_bdt, c.status AS course_status, m.user_id, m.status AS mentor_status " +
                    "  FROM courses c JOIN mentors m ON m.id = c.mentor_id WHERE c.id = ?")) {
                stmt.setInt(1, courseId);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    conn.rollback();
                    sendError(exchange, 404, "Course not found");
                    return;
                }
                price = rs.getInt("price_bdt");
                mentorUserId = rs.getInt("user_id");

                if (!"published".equals(rs.getString("course_status"))) {
                    conn.rollback();
                    sendError(exchange, 409, "This course is not open for enrollment.");
                    return;
                }
                if (!"approved".equalsIgnoreCase(rs.getString("mentor_status"))) {
                    conn.rollback();
                    sendError(exchange, 409, "This instructor is not currently accepting students.");
                    return;
                }
            }

            if (mentorUserId == userId) {
                conn.rollback();
                sendError(exchange, 400, "You cannot enroll in your own course.");
                return;
            }

            // Already in? The partial unique index would reject the insert anyway,
            // but a clear message beats a constraint violation.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, status FROM enrollments WHERE course_id = ? AND student_id = ? " +
                    "   AND status IN ('pending_payment', 'payment_review', 'active', 'completed')")) {
                stmt.setInt(1, courseId);
                stmt.setInt(2, userId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    int existingId = rs.getInt("id");
                    String existingStatus = rs.getString("status");
                    conn.rollback();
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", false);
                    response.put("enrollment_id", existingId);
                    response.put("status", existingStatus);
                    response.put("error", "pending_payment".equals(existingStatus)
                            ? "You already have an unpaid enrollment for this course."
                            : "You are already enrolled in this course.");
                    sendJSON(exchange, 409, response);
                    return;
                }
            }

            // Atomic seat claim.
            int claimed;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE courses SET enrolled_count = enrolled_count + 1, updated_at = NOW()" +
                    " WHERE id = ? AND enrolled_count < seat_limit")) {
                stmt.setInt(1, courseId);
                claimed = stmt.executeUpdate();
            }
            if (claimed == 0) {
                conn.rollback();
                sendError(exchange, 409, "This course is full. No seats left.");
                return;
            }

            int fee = MockInterviewSupport.platformFee(price);

            int enrollmentId;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO enrollments (course_id, student_id, amount_bdt, platform_fee_bdt, " +
                    "                         status, goal, hold_expires_at) " +
                    "VALUES (?, ?, ?, ?, 'pending_payment', ?, NOW() + (? || ' minutes')::interval) " +
                    "RETURNING id")) {
                stmt.setInt(1, courseId);
                stmt.setInt(2, userId);
                stmt.setInt(3, price);
                stmt.setInt(4, fee);
                stmt.setString(5, goal.isEmpty() ? null : goal);
                stmt.setString(6, String.valueOf(MockInterviewSupport.HOLD_MINUTES));
                ResultSet rs = stmt.executeQuery();
                rs.next();
                enrollmentId = rs.getInt("id");
            }

            PaymentGateway gateway = PaymentGatewayFactory.getActiveGateway();
            String idempotencyKey = gateway.name() + ":enrollment:" + enrollmentId + ":1";

            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO payments (enrollment_id, provider, amount_bdt, status, idempotency_key) " +
                    "VALUES (?, ?, ?, 'initiated', ?)")) {
                stmt.setInt(1, enrollmentId);
                stmt.setString(2, gateway.name());
                stmt.setInt(3, price);
                stmt.setString(4, idempotencyKey);
                stmt.executeUpdate();
            }

            conn.commit();
            committed = true;

            PaymentIntent intent = gateway.initiate(
                    MockInterviewSupport.enrollmentReference(enrollmentId), price, idempotencyKey);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("enrollment_id", enrollmentId);
            response.put("status", "pending_payment");
            response.put("amount_bdt", price);
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

    // ── GET /my-enrollments ────────────────────────────────────────────────

    private void handleMyEnrollments(HttpExchange exchange, int userId) throws Exception {
        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredEnrollments(conn);

            List<Map<String, Object>> enrollments = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT e.id, e.amount_bdt, e.status, e.goal, e.hold_expires_at, e.created_at, " +
                    "       c.id AS course_id, c.title, c.summary, c.stack, c.level, c.duration_weeks, " +
                    "       c.start_date, c.schedule_note, c.meeting_link, c.cover_url, " +
                    "       m.display_name AS mentor_name, m.headline AS mentor_headline, " +
                    "       m.photo_url AS mentor_photo, " +
                    "       p.status AS payment_status, p.provider_txn_id, " +
                    "       (SELECT COUNT(*) FROM course_syllabus s WHERE s.course_id = c.id) AS syllabus_count, " +
                    "       (SELECT COUNT(*) FROM course_syllabus s WHERE s.course_id = c.id " +
                    "          AND s.covered_at IS NOT NULL) AS covered_count " +
                    "  FROM enrollments e " +
                    "  JOIN courses c ON c.id = e.course_id " +
                    "  JOIN mentors m ON m.id = c.mentor_id " +
                    "  LEFT JOIN LATERAL (SELECT status, provider_txn_id FROM payments " +
                    "                      WHERE enrollment_id = e.id ORDER BY id DESC LIMIT 1) p ON TRUE " +
                    " WHERE e.student_id = ? " +
                    " ORDER BY e.created_at DESC")) {
                stmt.setInt(1, userId);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    String status = rs.getString("status");
                    Map<String, Object> e = new HashMap<>();
                    e.put("id", rs.getInt("id"));
                    e.put("amount_bdt", rs.getInt("amount_bdt"));
                    e.put("status", status);
                    e.put("goal", rs.getString("goal"));
                    e.put("hold_expires_at", MockInterviewSupport.iso(rs.getTimestamp("hold_expires_at")));
                    e.put("created_at", MockInterviewSupport.iso(rs.getTimestamp("created_at")));
                    e.put("course_id", rs.getInt("course_id"));
                    e.put("title", rs.getString("title"));
                    e.put("summary", rs.getString("summary"));
                    e.put("stack", rs.getString("stack"));
                    e.put("level", rs.getString("level"));
                    e.put("duration_weeks", rs.getInt("duration_weeks"));
                    java.sql.Date startDate = rs.getDate("start_date");
                    e.put("start_date", startDate == null ? null : startDate.toString());
                    e.put("schedule_note", rs.getString("schedule_note"));
                    e.put("cover_url", rs.getString("cover_url"));
                    e.put("mentor_name", rs.getString("mentor_name"));
                    e.put("mentor_headline", rs.getString("mentor_headline"));
                    e.put("mentor_photo", rs.getString("mentor_photo"));
                    e.put("payment_status", rs.getString("payment_status"));
                    e.put("trx_id", rs.getString("provider_txn_id"));
                    e.put("syllabus_count", rs.getInt("syllabus_count"));
                    e.put("covered_count", rs.getInt("covered_count"));

                    // The classroom link is the paid-for good: released only once
                    // the money has actually been verified.
                    boolean paidFor = "active".equals(status) || "completed".equals(status);
                    e.put("meeting_link", paidFor ? rs.getString("meeting_link") : null);

                    enrollments.add(e);
                }
            }

            // Syllabus for each joined course, so the student sees the plan and
            // how far the cohort has got.
            for (Map<String, Object> enrollment : enrollments) {
                List<Map<String, Object>> syllabus = new ArrayList<>();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT position, title, detail, est_hours, covered_at " +
                        "  FROM course_syllabus WHERE course_id = ? ORDER BY position")) {
                    stmt.setInt(1, (Integer) enrollment.get("course_id"));
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("position", rs.getInt("position"));
                        item.put("title", rs.getString("title"));
                        item.put("detail", rs.getString("detail"));
                        item.put("est_hours", rs.getBigDecimal("est_hours"));
                        item.put("covered_at", MockInterviewSupport.iso(rs.getTimestamp("covered_at")));
                        syllabus.add(item);
                    }
                }
                enrollment.put("syllabus", syllabus);
            }

            sendJSON(exchange, 200, enrollments);
        } finally {
            conn.close();
        }
    }

    // ── POST /enrollments/cancel ───────────────────────────────────────────

    private void handleCancel(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));
        if (!json.has("enrollment_id")) {
            sendError(exchange, 400, "Missing required field: enrollment_id");
            return;
        }
        int enrollmentId = json.get("enrollment_id").asInt();

        Connection conn = DbConnection.getConnection();
        boolean committed = false;
        try {
            conn.setAutoCommit(false);

            int courseId;
            String status;
            boolean alreadyPaid;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT e.course_id, e.status, " +
                    "       EXISTS (SELECT 1 FROM payments p WHERE p.enrollment_id = e.id " +
                    "                 AND p.status = 'paid') AS paid " +
                    "  FROM enrollments e WHERE e.id = ? AND e.student_id = ?")) {
                stmt.setInt(1, enrollmentId);
                stmt.setInt(2, userId);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    conn.rollback();
                    sendError(exchange, 404, "Enrollment not found");
                    return;
                }
                courseId = rs.getInt("course_id");
                status = rs.getString("status");
                alreadyPaid = rs.getBoolean("paid");
            }

            if (!status.equals("pending_payment") && !status.equals("payment_review")
                    && !status.equals("active")) {
                conn.rollback();
                sendError(exchange, 409, "An enrollment with status '" + status + "' can no longer be cancelled.");
                return;
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE enrollments SET status = 'cancelled', hold_expires_at = NULL WHERE id = ?")) {
                stmt.setInt(1, enrollmentId);
                stmt.executeUpdate();
            }

            // Free the seat.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE courses SET enrolled_count = GREATEST(enrolled_count - 1, 0), " +
                    "       updated_at = NOW() WHERE id = ?")) {
                stmt.setInt(1, courseId);
                stmt.executeUpdate();
            }

            // Money that never arrived is dropped; money that did arrive goes to
            // the admin refund queue rather than being auto-reversed.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE payments SET status = CASE WHEN status = 'paid' THEN 'refund_pending' ELSE 'failed' END, " +
                    "       updated_at = NOW() " +
                    " WHERE enrollment_id = ? AND status IN ('initiated', 'submitted', 'paid')")) {
                stmt.setInt(1, enrollmentId);
                stmt.executeUpdate();
            }

            conn.commit();
            committed = true;

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("status", "cancelled");
            response.put("refund_pending", alreadyPaid);
            response.put("message", alreadyPaid
                    ? "Enrollment cancelled. Your refund has been queued for review."
                    : "Enrollment cancelled and the seat has been released.");
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
