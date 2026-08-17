package Handlers.mock;

import Handlers.AbstractHttpHandler;
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
 * Admin console endpoints for the Mock Interview feature. Registered behind
 * AuthDecorator, with an additional admin role check on every route.
 *
 * Routes:
 *   GET /admin/mentors?status=      applications and live mentors
 *   PUT /admin/mentors              approve / reject / suspend a mentor
 *   GET /admin/bookings?status=     every booking, with revenue split
 *   GET /admin/payments?status=     the verification queue (default: submitted)
 *   PUT /admin/payments             verify / reject / mark-refunded a payment
 *
 * Verifying a payment here is the single point in the system that confirms a
 * booking. Nothing the student sends can do it on its own.
 */
public class AdminMockInterviewHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        Integer userId = MockInterviewSupport.userId(exchange);
        if (userId == null) {
            sendError(exchange, 401, "Unauthorized: Please log in to proceed.");
            return;
        }
        if (!MockInterviewSupport.isAdmin(exchange)) {
            sendError(exchange, 403, "Forbidden: Admin access required.");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        switch (path) {
            case "/admin/mentors":
                if (method.equals("GET")) handleListMentors(exchange);
                else if (method.equals("PUT")) handleUpdateMentorStatus(exchange);
                else sendError(exchange, 405, "Method Not Allowed");
                break;
            case "/admin/bookings":
                if (method.equals("GET")) handleListBookings(exchange);
                else sendError(exchange, 405, "Method Not Allowed");
                break;
            case "/admin/payments":
                if (method.equals("GET")) handleListPayments(exchange);
                else if (method.equals("PUT")) handleReviewPayment(exchange, userId);
                else sendError(exchange, 405, "Method Not Allowed");
                break;
            case "/admin/courses":
                if (method.equals("GET")) handleListCourses(exchange);
                else if (method.equals("PUT")) handleUpdateCourseStatus(exchange);
                else sendError(exchange, 405, "Method Not Allowed");
                break;
            default:
                sendError(exchange, 404, "Not Found");
        }
    }

    // ── GET /admin/mentors ─────────────────────────────────────────────────

    private void handleListMentors(HttpExchange exchange) throws Exception {
        String status = MockInterviewSupport.queryParams(exchange).get("status");

        String sql =
            "SELECT m.id, m.display_name, m.headline, m.bio, m.company, m.years_experience, " +
            "       m.hourly_rate_bdt, m.photo_url, m.status, m.rating_avg, m.rating_count, " +
            "       m.sessions_completed, m.created_at, u.email, u.name AS user_name, " +
            "       COALESCE(string_agg(DISTINCT ms.stack, ',' ORDER BY ms.stack), '') AS stacks " +
            "  FROM mentors m " +
            "  LEFT JOIN users u ON u.id = m.user_id " +
            "  LEFT JOIN mentor_stacks ms ON ms.mentor_id = m.id " +
            (status != null && !status.trim().isEmpty() ? " WHERE m.status = ? " : "") +
            " GROUP BY m.id, u.email, u.name ORDER BY m.created_at DESC";

        Connection conn = DbConnection.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (status != null && !status.trim().isEmpty()) {
                stmt.setString(1, status.trim());
            }
            ResultSet rs = stmt.executeQuery();

            List<Map<String, Object>> mentors = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> m = MockInterviewSupport.mapMentorRow(rs);
                m.put("bio", rs.getString("bio"));
                m.put("email", rs.getString("email"));
                m.put("user_name", rs.getString("user_name"));
                m.put("created_at", MockInterviewSupport.iso(rs.getTimestamp("created_at")));
                String stacks = rs.getString("stacks");
                m.put("stacks", stacks == null || stacks.isEmpty()
                        ? new ArrayList<String>()
                        : new ArrayList<>(java.util.Arrays.asList(stacks.split(","))));
                mentors.add(m);
            }
            sendJSON(exchange, 200, mentors);
        } finally {
            conn.close();
        }
    }

    // ── PUT /admin/mentors ─────────────────────────────────────────────────

    private void handleUpdateMentorStatus(HttpExchange exchange) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));

        if (!json.has("mentor_id") || !json.has("status")) {
            sendError(exchange, 400, "Missing required fields: mentor_id and status");
            return;
        }
        int mentorId = json.get("mentor_id").asInt();
        String status = json.get("status").asText("").trim().toLowerCase();

        if (!status.equals("approved") && !status.equals("rejected")
                && !status.equals("suspended") && !status.equals("pending")) {
            sendError(exchange, 400, "status must be one of: pending, approved, rejected, suspended");
            return;
        }

        Connection conn = DbConnection.getConnection();
        boolean committed = false;
        try {
            conn.setAutoCommit(false);

            int updated;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE mentors SET status = ? WHERE id = ?")) {
                stmt.setString(1, status);
                stmt.setInt(2, mentorId);
                updated = stmt.executeUpdate();
            }
            if (updated == 0) {
                conn.rollback();
                sendError(exchange, 404, "Mentor not found");
                return;
            }

            // A suspended or rejected mentor must stop appearing as bookable, but
            // interviews students already paid for are left alone.
            if (status.equals("suspended") || status.equals("rejected")) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE mentor_slots SET status = 'cancelled' " +
                        " WHERE mentor_id = ? AND status = 'open' AND start_at > NOW()")) {
                    stmt.setInt(1, mentorId);
                    stmt.executeUpdate();
                }
            }

            conn.commit();
            committed = true;
            sendJSON(exchange, 200, Map.of("success", true, "message", "Mentor status set to " + status + "."));

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

    // ── GET /admin/bookings ────────────────────────────────────────────────

    private void handleListBookings(HttpExchange exchange) throws Exception {
        String status = MockInterviewSupport.queryParams(exchange).get("status");

        String sql =
            "SELECT b.id, b.stack, b.amount_bdt, b.platform_fee_bdt, b.status, b.created_at, " +
            "       s.start_at, m.display_name AS mentor_name, " +
            "       u.email AS student_email, u.name AS student_name, " +
            "       p.status AS payment_status, p.provider_txn_id " +
            "  FROM bookings b " +
            "  JOIN mentor_slots s ON s.id = b.slot_id " +
            "  JOIN mentors m ON m.id = b.mentor_id " +
            "  LEFT JOIN users u ON u.id = b.student_id " +
            "  LEFT JOIN LATERAL (SELECT status, provider_txn_id FROM payments " +
            "                      WHERE booking_id = b.id ORDER BY id DESC LIMIT 1) p ON TRUE " +
            (status != null && !status.trim().isEmpty() ? " WHERE b.status = ? " : "") +
            " ORDER BY b.created_at DESC LIMIT 500";

        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);

            List<Map<String, Object>> bookings = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (status != null && !status.trim().isEmpty()) {
                    stmt.setString(1, status.trim());
                }
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    Map<String, Object> b = new HashMap<>();
                    b.put("id", rs.getInt("id"));
                    b.put("stack", rs.getString("stack"));
                    b.put("amount_bdt", rs.getInt("amount_bdt"));
                    b.put("platform_fee_bdt", rs.getInt("platform_fee_bdt"));
                    b.put("mentor_payout_bdt", rs.getInt("amount_bdt") - rs.getInt("platform_fee_bdt"));
                    b.put("status", rs.getString("status"));
                    b.put("created_at", MockInterviewSupport.iso(rs.getTimestamp("created_at")));
                    b.put("start_at", MockInterviewSupport.iso(rs.getTimestamp("start_at")));
                    b.put("mentor_name", rs.getString("mentor_name"));
                    b.put("student_name", rs.getString("student_name"));
                    b.put("student_email", rs.getString("student_email"));
                    b.put("payment_status", rs.getString("payment_status"));
                    b.put("trx_id", rs.getString("provider_txn_id"));
                    bookings.add(b);
                }
            }

            // Revenue only counts sessions that were actually delivered.
            Map<String, Object> totals = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) AS n, COALESCE(SUM(amount_bdt), 0) AS gross, " +
                    "       COALESCE(SUM(platform_fee_bdt), 0) AS fees " +
                    "  FROM bookings WHERE status = 'completed'")) {
                ResultSet rs = stmt.executeQuery();
                rs.next();
                totals.put("completed_sessions", rs.getInt("n"));
                totals.put("gross_bdt", rs.getInt("gross"));
                totals.put("platform_revenue_bdt", rs.getInt("fees"));
                totals.put("mentor_payouts_bdt", rs.getInt("gross") - rs.getInt("fees"));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("bookings", bookings);
            response.put("totals", totals);
            sendJSON(exchange, 200, response);
        } finally {
            conn.close();
        }
    }

    // ── GET /admin/payments ────────────────────────────────────────────────

    /**
     * One queue for both products. A payment row points at either a booking or
     * an enrollment, so both are LEFT JOINed and the response carries a `kind`
     * telling the console which it is looking at.
     */
    private void handleListPayments(HttpExchange exchange) throws Exception {
        String status = MockInterviewSupport.queryParams(exchange).getOrDefault("status", "submitted");

        Connection conn = DbConnection.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT p.id, p.booking_id, p.enrollment_id, p.provider, p.amount_bdt, p.status, " +
                "       p.provider_txn_id, p.payer_msisdn, p.created_at, " +
                "       b.status AS booking_status, b.stack AS booking_stack, s.start_at, " +
                "       bm.display_name AS booking_mentor, " +
                "       bu.email AS booking_student_email, bu.name AS booking_student_name, " +
                "       e.status AS enrollment_status, c.title AS course_title, c.stack AS course_stack, " +
                "       c.start_date, cm.display_name AS course_mentor, " +
                "       eu.email AS course_student_email, eu.name AS course_student_name " +
                "  FROM payments p " +
                "  LEFT JOIN bookings b ON b.id = p.booking_id " +
                "  LEFT JOIN mentor_slots s ON s.id = b.slot_id " +
                "  LEFT JOIN mentors bm ON bm.id = b.mentor_id " +
                "  LEFT JOIN users bu ON bu.id = b.student_id " +
                "  LEFT JOIN enrollments e ON e.id = p.enrollment_id " +
                "  LEFT JOIN courses c ON c.id = e.course_id " +
                "  LEFT JOIN mentors cm ON cm.id = c.mentor_id " +
                "  LEFT JOIN users eu ON eu.id = e.student_id " +
                " WHERE p.status = ? ORDER BY p.created_at ASC")) {
            stmt.setString(1, status);
            ResultSet rs = stmt.executeQuery();

            List<Map<String, Object>> payments = new ArrayList<>();
            while (rs.next()) {
                boolean isBooking = rs.getObject("booking_id") != null;

                Map<String, Object> p = new HashMap<>();
                p.put("id", rs.getInt("id"));
                p.put("kind", isBooking ? "booking" : "enrollment");
                p.put("provider", rs.getString("provider"));
                p.put("amount_bdt", rs.getInt("amount_bdt"));
                p.put("status", rs.getString("status"));
                p.put("trx_id", rs.getString("provider_txn_id"));
                p.put("payer_msisdn", rs.getString("payer_msisdn"));
                p.put("created_at", MockInterviewSupport.iso(rs.getTimestamp("created_at")));

                if (isBooking) {
                    p.put("booking_id", rs.getInt("booking_id"));
                    p.put("purchase_status", rs.getString("booking_status"));
                    p.put("item", "Mock interview · " + rs.getString("booking_stack"));
                    p.put("stack", rs.getString("booking_stack"));
                    p.put("when", MockInterviewSupport.iso(rs.getTimestamp("start_at")));
                    p.put("mentor_name", rs.getString("booking_mentor"));
                    p.put("student_name", rs.getString("booking_student_name"));
                    p.put("student_email", rs.getString("booking_student_email"));
                } else {
                    p.put("enrollment_id", rs.getInt("enrollment_id"));
                    p.put("purchase_status", rs.getString("enrollment_status"));
                    p.put("item", "Course · " + rs.getString("course_title"));
                    p.put("stack", rs.getString("course_stack"));
                    java.sql.Date startDate = rs.getDate("start_date");
                    p.put("when", startDate == null ? null : startDate.toString());
                    p.put("mentor_name", rs.getString("course_mentor"));
                    p.put("student_name", rs.getString("course_student_name"));
                    p.put("student_email", rs.getString("course_student_email"));
                }
                payments.add(p);
            }
            sendJSON(exchange, 200, payments);
        } finally {
            conn.close();
        }
    }

    // ── GET /admin/courses ─────────────────────────────────────────────────

    private void handleListCourses(HttpExchange exchange) throws Exception {
        String status = MockInterviewSupport.queryParams(exchange).get("status");

        String sql =
            "SELECT c.id, c.title, c.summary, c.stack, c.level, c.price_bdt, c.duration_weeks, " +
            "       c.total_hours, c.seat_limit, c.enrolled_count, c.start_date, c.schedule_note, " +
            "       c.meeting_link, c.status, c.created_at, " +
            "       m.id AS mentor_id, m.display_name AS mentor_name, m.status AS mentor_status, " +
            "       u.email AS mentor_email, " +
            "       (SELECT COUNT(*) FROM course_syllabus s WHERE s.course_id = c.id) AS syllabus_count, " +
            "       (SELECT COUNT(*) FROM enrollments e WHERE e.course_id = c.id " +
            "          AND e.status IN ('active','completed')) AS paid_students, " +
            "       (SELECT COALESCE(SUM(e.platform_fee_bdt), 0) FROM enrollments e " +
            "         WHERE e.course_id = c.id AND e.status IN ('active','completed')) AS platform_revenue " +
            "  FROM courses c " +
            "  JOIN mentors m ON m.id = c.mentor_id " +
            "  LEFT JOIN users u ON u.id = m.user_id " +
            (status != null && !status.trim().isEmpty() ? " WHERE c.status = ? " : "") +
            " ORDER BY CASE c.status WHEN 'pending' THEN 0 ELSE 1 END, c.created_at DESC";

        Connection conn = DbConnection.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (status != null && !status.trim().isEmpty()) {
                stmt.setString(1, status.trim());
            }
            ResultSet rs = stmt.executeQuery();

            List<Map<String, Object>> courses = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> c = new HashMap<>();
                c.put("id", rs.getInt("id"));
                c.put("title", rs.getString("title"));
                c.put("summary", rs.getString("summary"));
                c.put("stack", rs.getString("stack"));
                c.put("level", rs.getString("level"));
                c.put("price_bdt", rs.getInt("price_bdt"));
                c.put("duration_weeks", rs.getInt("duration_weeks"));
                int totalHours = rs.getInt("total_hours");
                c.put("total_hours", rs.wasNull() ? null : totalHours);
                c.put("seat_limit", rs.getInt("seat_limit"));
                c.put("enrolled_count", rs.getInt("enrolled_count"));
                java.sql.Date startDate = rs.getDate("start_date");
                c.put("start_date", startDate == null ? null : startDate.toString());
                c.put("schedule_note", rs.getString("schedule_note"));
                c.put("has_meeting_link", rs.getString("meeting_link") != null);
                c.put("status", rs.getString("status"));
                c.put("created_at", MockInterviewSupport.iso(rs.getTimestamp("created_at")));
                c.put("mentor_id", rs.getInt("mentor_id"));
                c.put("mentor_name", rs.getString("mentor_name"));
                c.put("mentor_status", rs.getString("mentor_status"));
                c.put("mentor_email", rs.getString("mentor_email"));
                c.put("syllabus_count", rs.getInt("syllabus_count"));
                c.put("paid_students", rs.getInt("paid_students"));
                c.put("platform_revenue_bdt", rs.getInt("platform_revenue"));
                courses.add(c);
            }
            sendJSON(exchange, 200, courses);
        } finally {
            conn.close();
        }
    }

    // ── PUT /admin/courses ─────────────────────────────────────────────────

    private void handleUpdateCourseStatus(HttpExchange exchange) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));

        if (!json.has("course_id") || !json.has("status")) {
            sendError(exchange, 400, "Missing required fields: course_id and status");
            return;
        }
        int courseId = json.get("course_id").asInt();
        String status = json.get("status").asText("").trim().toLowerCase();

        if (!status.equals("published") && !status.equals("draft")
                && !status.equals("archived") && !status.equals("pending")) {
            sendError(exchange, 400, "status must be one of: draft, pending, published, archived");
            return;
        }

        Connection conn = DbConnection.getConnection();
        try {
            // Publishing a course with no syllabus would put an empty product on
            // sale, so refuse it here rather than trusting the console.
            if (status.equals("published")) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT (SELECT COUNT(*) FROM course_syllabus s WHERE s.course_id = c.id) AS n, " +
                        "       m.status AS mentor_status " +
                        "  FROM courses c JOIN mentors m ON m.id = c.mentor_id WHERE c.id = ?")) {
                    stmt.setInt(1, courseId);
                    ResultSet rs = stmt.executeQuery();
                    if (!rs.next()) {
                        sendError(exchange, 404, "Course not found");
                        return;
                    }
                    if (rs.getInt("n") == 0) {
                        sendError(exchange, 400, "This course has no syllabus items — it cannot be published.");
                        return;
                    }
                    if (!"approved".equalsIgnoreCase(rs.getString("mentor_status"))) {
                        sendError(exchange, 409, "Approve the instructor's mentor profile before publishing their course.");
                        return;
                    }
                }
            }

            int updated;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE courses SET status = ?, updated_at = NOW() WHERE id = ?")) {
                stmt.setString(1, status);
                stmt.setInt(2, courseId);
                updated = stmt.executeUpdate();
            }
            if (updated == 0) {
                sendError(exchange, 404, "Course not found");
                return;
            }

            sendJSON(exchange, 200, Map.of("success", true,
                    "message", "Course status set to " + status + "."));
        } finally {
            conn.close();
        }
    }

    // ── PUT /admin/payments ────────────────────────────────────────────────

    /**
     * verify  — money confirmed in the bKash statement: booking becomes confirmed,
     *           the slot is finally marked booked, and the meeting link is released.
     * reject  — no matching transaction: booking cancelled, slot back on sale.
     * refunded— an already-refunded booking is closed off in the ledger.
     */
    private void handleReviewPayment(HttpExchange exchange, int adminUserId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));

        if (!json.has("payment_id") || !json.has("action")) {
            sendError(exchange, 400, "Missing required fields: payment_id and action");
            return;
        }
        int paymentId = json.get("payment_id").asInt();
        String action = json.get("action").asText("").trim().toLowerCase();

        if (!action.equals("verify") && !action.equals("reject") && !action.equals("refunded")) {
            sendError(exchange, 400, "action must be one of: verify, reject, refunded");
            return;
        }

        Connection conn = DbConnection.getConnection();
        boolean committed = false;
        try {
            conn.setAutoCommit(false);

            boolean isBooking;
            int bookingId = 0;
            int slotId = 0;
            int enrollmentId = 0;
            int courseId = 0;
            String paymentStatus;
            String purchaseStatus;
            String meetingLink;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT p.status AS payment_status, p.booking_id, p.enrollment_id, " +
                    "       b.status AS booking_status, b.slot_id, s.meeting_link AS slot_link, " +
                    "       e.status AS enrollment_status, e.course_id, c.meeting_link AS course_link " +
                    "  FROM payments p " +
                    "  LEFT JOIN bookings b ON b.id = p.booking_id " +
                    "  LEFT JOIN mentor_slots s ON s.id = b.slot_id " +
                    "  LEFT JOIN enrollments e ON e.id = p.enrollment_id " +
                    "  LEFT JOIN courses c ON c.id = e.course_id " +
                    " WHERE p.id = ? FOR UPDATE OF p")) {
                stmt.setInt(1, paymentId);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    conn.rollback();
                    sendError(exchange, 404, "Payment not found");
                    return;
                }
                paymentStatus = rs.getString("payment_status");
                isBooking = rs.getObject("booking_id") != null;

                if (isBooking) {
                    bookingId = rs.getInt("booking_id");
                    purchaseStatus = rs.getString("booking_status");
                    slotId = rs.getInt("slot_id");
                    meetingLink = rs.getString("slot_link");
                } else {
                    enrollmentId = rs.getInt("enrollment_id");
                    purchaseStatus = rs.getString("enrollment_status");
                    courseId = rs.getInt("course_id");
                    meetingLink = rs.getString("course_link");
                }
            }

            if (action.equals("verify")) {
                if ("paid".equals(paymentStatus)) {
                    conn.rollback();
                    sendJSON(exchange, 200, Map.of("success", true, "message", "Payment was already verified."));
                    return;
                }
                if (!"submitted".equals(paymentStatus)) {
                    conn.rollback();
                    sendError(exchange, 409, "Only a submitted payment can be verified (current: " + paymentStatus + ").");
                    return;
                }
                if ("expired".equals(purchaseStatus) || "cancelled".equals(purchaseStatus)) {
                    conn.rollback();
                    sendError(exchange, 409, "This purchase is " + purchaseStatus
                            + " — refund the student instead of verifying.");
                    return;
                }

                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE payments SET status = 'paid', verified_by = ?, updated_at = NOW() WHERE id = ?")) {
                    stmt.setInt(1, adminUserId);
                    stmt.setInt(2, paymentId);
                    stmt.executeUpdate();
                }

                if (isBooking) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE bookings SET status = 'confirmed', hold_expires_at = NULL, " +
                            "       meeting_link = COALESCE(meeting_link, ?) WHERE id = ?")) {
                        stmt.setString(1, meetingLink);
                        stmt.setInt(2, bookingId);
                        stmt.executeUpdate();
                    }
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE mentor_slots SET status = 'booked' WHERE id = ?")) {
                        stmt.setInt(1, slotId);
                        stmt.executeUpdate();
                    }
                } else {
                    // The seat was already claimed at enrollment time, so activating
                    // must not touch enrolled_count again.
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE enrollments SET status = 'active', hold_expires_at = NULL WHERE id = ?")) {
                        stmt.setInt(1, enrollmentId);
                        stmt.executeUpdate();
                    }
                }

                conn.commit();
                committed = true;
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("kind", isBooking ? "booking" : "enrollment");
                response.put("purchase_status", isBooking ? "confirmed" : "active");
                response.put("meeting_link_set", meetingLink != null);
                response.put("message", meetingLink != null
                        ? (isBooking ? "Payment verified and the booking is confirmed."
                                     : "Payment verified — the student now has access to the classroom.")
                        : (isBooking ? "Payment verified. The mentor still needs to add a meeting link for this booking."
                                     : "Payment verified. The instructor has not set a class link for this course yet."));
                sendJSON(exchange, 200, response);

            } else if (action.equals("reject")) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE payments SET status = 'failed', verified_by = ?, updated_at = NOW() WHERE id = ?")) {
                    stmt.setInt(1, adminUserId);
                    stmt.setInt(2, paymentId);
                    stmt.executeUpdate();
                }

                if (isBooking) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE bookings SET status = 'cancelled', hold_expires_at = NULL " +
                            " WHERE id = ? AND status IN ('pending_payment', 'payment_review')")) {
                        stmt.setInt(1, bookingId);
                        stmt.executeUpdate();
                    }
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE mentor_slots SET status = 'open' WHERE id = ? AND start_at > NOW() AND status = 'held'")) {
                        stmt.setInt(1, slotId);
                        stmt.executeUpdate();
                    }
                } else {
                    // Free the seat, but only if this enrollment was still holding
                    // one — a second reject must not decrement twice.
                    int released;
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE enrollments SET status = 'cancelled', hold_expires_at = NULL " +
                            " WHERE id = ? AND status IN ('pending_payment', 'payment_review')")) {
                        stmt.setInt(1, enrollmentId);
                        released = stmt.executeUpdate();
                    }
                    if (released > 0) {
                        try (PreparedStatement stmt = conn.prepareStatement(
                                "UPDATE courses SET enrolled_count = GREATEST(enrolled_count - 1, 0), " +
                                "       updated_at = NOW() WHERE id = ?")) {
                            stmt.setInt(1, courseId);
                            stmt.executeUpdate();
                        }
                    }
                }

                conn.commit();
                committed = true;
                sendJSON(exchange, 200, Map.of(
                        "success", true,
                        "message", isBooking
                                ? "Payment rejected. The booking was cancelled and the slot released."
                                : "Payment rejected. The enrollment was cancelled and the seat released."));

            } else {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE payments SET status = 'refunded', verified_by = ?, updated_at = NOW() WHERE id = ?")) {
                    stmt.setInt(1, adminUserId);
                    stmt.setInt(2, paymentId);
                    stmt.executeUpdate();
                }
                conn.commit();
                committed = true;
                sendJSON(exchange, 200, Map.of("success", true, "message", "Payment marked as refunded."));
            }

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
