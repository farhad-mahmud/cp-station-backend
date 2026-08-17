package Handlers.mock;

import Handlers.AbstractHttpHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import config.DbConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The expert's own workspace. Registered behind AuthDecorator.
 *
 * A mentor is an ordinary user with an approved row in `mentors`; users.role is
 * never changed, so nothing in the existing admin/user auth logic shifts.
 *
 * Routes:
 *   GET    /mentor/me         mentor profile for the logged-in user (or not_mentor)
 *   POST   /mentor/apply      apply, or update an existing profile
 *   GET    /mentor/slots      own availability, including booked slots
 *   POST   /mentor/slots      publish a one-hour slot
 *   DELETE /mentor/slots      withdraw an unbooked slot
 *   GET    /mentor/bookings   interviews to run, with student notes
 *   PUT    /mentor/bookings   set or change the meeting link for a booking
 *   POST   /mentor/complete   mark done and write the student's evaluation
 */
public class MentorPortalHandler extends AbstractHttpHandler {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final long SLOT_MINUTES = 60;

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        Integer userId = MockInterviewSupport.userId(exchange);
        if (userId == null) {
            sendError(exchange, 401, "Unauthorized: Please log in to proceed.");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        switch (path) {
            case "/mentor/me":
                if (method.equals("GET")) handleMe(exchange, userId);
                else sendError(exchange, 405, "Method Not Allowed");
                break;
            case "/mentor/apply":
                if (method.equals("POST")) handleApply(exchange, userId);
                else sendError(exchange, 405, "Method Not Allowed");
                break;
            case "/mentor/slots":
                if (method.equals("GET")) handleListSlots(exchange, userId);
                else if (method.equals("POST")) handleCreateSlot(exchange, userId);
                else if (method.equals("DELETE")) handleDeleteSlot(exchange, userId);
                else sendError(exchange, 405, "Method Not Allowed");
                break;
            case "/mentor/bookings":
                if (method.equals("GET")) handleMentorBookings(exchange, userId);
                else if (method.equals("PUT")) handleSetMeetingLink(exchange, userId);
                else sendError(exchange, 405, "Method Not Allowed");
                break;
            case "/mentor/complete":
                if (method.equals("POST")) handleComplete(exchange, userId);
                else sendError(exchange, 405, "Method Not Allowed");
                break;
            default:
                sendError(exchange, 404, "Not Found");
        }
    }

    // ── GET /mentor/me ─────────────────────────────────────────────────────

    private void handleMe(HttpExchange exchange, int userId) throws Exception {
        Connection conn = DbConnection.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT id, display_name, headline, bio, company, years_experience, hourly_rate_bdt, " +
                "       photo_url, status, rating_avg, rating_count, sessions_completed " +
                "  FROM mentors WHERE user_id = ?")) {
            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                sendJSON(exchange, 200, Map.of("is_mentor", false, "status", "none"));
                return;
            }

            int mentorId = rs.getInt("id");
            Map<String, Object> profile = MockInterviewSupport.mapMentorRow(rs);
            profile.put("bio", rs.getString("bio"));
            profile.put("is_mentor", true);

            List<String> stacks = new ArrayList<>();
            try (PreparedStatement s = conn.prepareStatement(
                    "SELECT stack FROM mentor_stacks WHERE mentor_id = ? ORDER BY stack")) {
                s.setInt(1, mentorId);
                ResultSet srs = s.executeQuery();
                while (srs.next()) {
                    stacks.add(srs.getString("stack"));
                }
            }
            profile.put("stacks", stacks);

            // Earnings, net of the platform commission, on sessions actually delivered.
            try (PreparedStatement s = conn.prepareStatement(
                    "SELECT COALESCE(SUM(amount_bdt - platform_fee_bdt), 0) AS net, COUNT(*) AS n " +
                    "  FROM bookings WHERE mentor_id = ? AND status = 'completed'")) {
                s.setInt(1, mentorId);
                ResultSet ers = s.executeQuery();
                ers.next();
                profile.put("earnings_bdt", ers.getInt("net"));
                profile.put("completed_count", ers.getInt("n"));
            }

            sendJSON(exchange, 200, profile);
        } finally {
            conn.close();
        }
    }

    // ── POST /mentor/apply ─────────────────────────────────────────────────

    private void handleApply(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));

        String displayName = text(json, "display_name");
        String headline = text(json, "headline");
        String bio = text(json, "bio");
        String company = text(json, "company");
        String photoUrl = text(json, "photo_url");
        int years = json.has("years_experience") ? json.get("years_experience").asInt(0) : 0;
        int rate = json.has("hourly_rate_bdt") ? json.get("hourly_rate_bdt").asInt(500) : 500;

        if (displayName.isEmpty()) {
            sendError(exchange, 400, "Missing required field: display_name");
            return;
        }
        if (rate < 100 || rate > 20000) {
            sendError(exchange, 400, "Hourly rate must be between BDT 100 and BDT 20000.");
            return;
        }
        if (years < 0 || years > 60) {
            sendError(exchange, 400, "Years of experience looks invalid.");
            return;
        }

        List<String> stacks = new ArrayList<>();
        if (json.has("stacks") && json.get("stacks").isArray()) {
            for (JsonNode node : json.get("stacks")) {
                String stack = node.asText("").trim();
                if (!stack.isEmpty() && !stacks.contains(stack)) {
                    stacks.add(stack);
                }
            }
        }
        if (stacks.isEmpty()) {
            sendError(exchange, 400, "Pick at least one stack or topic you can interview on.");
            return;
        }

        Connection conn = DbConnection.getConnection();
        boolean committed = false;
        try {
            conn.setAutoCommit(false);

            Integer existingId = MockInterviewSupport.mentorIdForUser(conn, userId);
            int mentorId;
            boolean isNew = existingId == null;

            if (isNew) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO mentors (user_id, display_name, headline, bio, company, " +
                        "                     years_experience, hourly_rate_bdt, photo_url, status) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending') RETURNING id")) {
                    stmt.setInt(1, userId);
                    stmt.setString(2, displayName);
                    stmt.setString(3, nullable(headline));
                    stmt.setString(4, nullable(bio));
                    stmt.setString(5, nullable(company));
                    stmt.setInt(6, years);
                    stmt.setInt(7, rate);
                    stmt.setString(8, nullable(photoUrl));
                    ResultSet rs = stmt.executeQuery();
                    rs.next();
                    mentorId = rs.getInt("id");
                }
            } else {
                mentorId = existingId;
                // An edit never re-opens approval status; only an admin moves that.
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE mentors SET display_name = ?, headline = ?, bio = ?, company = ?, " +
                        "       years_experience = ?, hourly_rate_bdt = ?, photo_url = ? WHERE id = ?")) {
                    stmt.setString(1, displayName);
                    stmt.setString(2, nullable(headline));
                    stmt.setString(3, nullable(bio));
                    stmt.setString(4, nullable(company));
                    stmt.setInt(5, years);
                    stmt.setInt(6, rate);
                    stmt.setString(7, nullable(photoUrl));
                    stmt.setInt(8, mentorId);
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM mentor_stacks WHERE mentor_id = ?")) {
                    stmt.setInt(1, mentorId);
                    stmt.executeUpdate();
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO mentor_stacks (mentor_id, stack) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                for (String stack : stacks) {
                    stmt.setInt(1, mentorId);
                    stmt.setString(2, stack);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            conn.commit();
            committed = true;

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("mentor_id", mentorId);
            response.put("message", isNew
                    ? "Application submitted. An admin will review your profile before it goes live."
                    : "Profile updated.");
            sendJSON(exchange, isNew ? 201 : 200, response);

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

    // ── /mentor/slots ──────────────────────────────────────────────────────

    private void handleListSlots(HttpExchange exchange, int userId) throws Exception {
        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);

            Integer mentorId = MockInterviewSupport.mentorIdForUser(conn, userId);
            if (mentorId == null) {
                sendError(exchange, 403, "You do not have a mentor profile.");
                return;
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT s.id, s.mentor_id, s.start_at, s.end_at, s.status, s.meeting_link, " +
                    "       b.id AS booking_id, b.stack, b.status AS booking_status " +
                    "  FROM mentor_slots s " +
                    "  LEFT JOIN bookings b ON b.slot_id = s.id " +
                    "       AND b.status IN ('pending_payment', 'payment_review', 'confirmed', 'completed') " +
                    " WHERE s.mentor_id = ? AND s.status <> 'cancelled' " +
                    " ORDER BY s.start_at")) {
                stmt.setInt(1, mentorId);
                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> slots = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> slot = MockInterviewSupport.mapSlotRow(rs);
                    slot.put("meeting_link", rs.getString("meeting_link"));
                    int bookingId = rs.getInt("booking_id");
                    slot.put("booking_id", rs.wasNull() ? null : bookingId);
                    slot.put("booking_status", rs.getString("booking_status"));
                    slot.put("stack", rs.getString("stack"));
                    slots.add(slot);
                }
                sendJSON(exchange, 200, slots);
            }
        } finally {
            conn.close();
        }
    }

    private void handleCreateSlot(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));

        String startRaw = text(json, "start_at");
        String meetingLink = text(json, "meeting_link");
        if (startRaw.isEmpty()) {
            sendError(exchange, 400, "Missing required field: start_at (ISO-8601 timestamp)");
            return;
        }

        Instant start;
        try {
            start = Instant.parse(startRaw);
        } catch (Exception e) {
            sendError(exchange, 400, "start_at must be an ISO-8601 UTC timestamp, e.g. 2026-09-01T15:00:00Z");
            return;
        }
        if (start.isBefore(Instant.now())) {
            sendError(exchange, 400, "You cannot publish a slot in the past.");
            return;
        }
        if (!meetingLink.isEmpty() && !meetingLink.startsWith("https://")) {
            sendError(exchange, 400, "Meeting link must be an https:// URL.");
            return;
        }

        Instant end = start.plusSeconds(SLOT_MINUTES * 60);

        Connection conn = DbConnection.getConnection();
        try {
            Integer mentorId = MockInterviewSupport.approvedMentorIdForUser(conn, userId);
            if (mentorId == null) {
                sendError(exchange, 403, "Only an approved mentor can publish availability.");
                return;
            }

            // Reject a slot that overlaps one already published, not just an exact
            // duplicate start time; a mentor cannot run two interviews at once.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT 1 FROM mentor_slots WHERE mentor_id = ? AND status <> 'cancelled' " +
                    "   AND start_at < ? AND end_at > ?")) {
                stmt.setInt(1, mentorId);
                stmt.setTimestamp(2, Timestamp.from(end));
                stmt.setTimestamp(3, Timestamp.from(start));
                if (stmt.executeQuery().next()) {
                    sendError(exchange, 409, "That time overlaps a slot you have already published.");
                    return;
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO mentor_slots (mentor_id, start_at, end_at, status, meeting_link) " +
                    "VALUES (?, ?, ?, 'open', ?) RETURNING id")) {
                stmt.setInt(1, mentorId);
                stmt.setTimestamp(2, Timestamp.from(start));
                stmt.setTimestamp(3, Timestamp.from(end));
                stmt.setString(4, nullable(meetingLink));
                ResultSet rs = stmt.executeQuery();
                rs.next();

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("id", rs.getInt("id"));
                response.put("start_at", start.toString());
                response.put("end_at", end.toString());
                sendJSON(exchange, 201, response);
            } catch (SQLException e) {
                if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    sendError(exchange, 409, "You have already published a slot at that time.");
                    return;
                }
                throw e;
            }
        } finally {
            conn.close();
        }
    }

    private void handleDeleteSlot(HttpExchange exchange, int userId) throws Exception {
        Integer slotId = MockInterviewSupport.intParam(MockInterviewSupport.queryParams(exchange), "id");
        if (slotId == null) {
            sendError(exchange, 400, "Missing or invalid query parameter: id");
            return;
        }

        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);

            Integer mentorId = MockInterviewSupport.mentorIdForUser(conn, userId);
            if (mentorId == null) {
                sendError(exchange, 403, "You do not have a mentor profile.");
                return;
            }

            // Only an untouched slot may be withdrawn. A held or booked slot has a
            // student attached to it and must be cancelled through support.
            int updated;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE mentor_slots SET status = 'cancelled' " +
                    " WHERE id = ? AND mentor_id = ? AND status = 'open'")) {
                stmt.setInt(1, slotId);
                stmt.setInt(2, mentorId);
                updated = stmt.executeUpdate();
            }

            if (updated == 0) {
                sendError(exchange, 409, "That slot cannot be withdrawn — it is already booked or does not exist.");
                return;
            }
            sendJSON(exchange, 200, Map.of("success", true, "message", "Slot withdrawn."));
        } finally {
            conn.close();
        }
    }

    // ── GET/PUT /mentor/bookings ───────────────────────────────────────────

    private void handleMentorBookings(HttpExchange exchange, int userId) throws Exception {
        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);

            Integer mentorId = MockInterviewSupport.mentorIdForUser(conn, userId);
            if (mentorId == null) {
                sendError(exchange, 403, "You do not have a mentor profile.");
                return;
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT b.id, b.stack, b.notes, b.amount_bdt, b.platform_fee_bdt, b.status, " +
                    "       b.meeting_link, b.created_at, s.start_at, s.end_at, " +
                    "       u.name AS student_name, u.email AS student_email, u.institute, " +
                    "       f.mentor_verdict, f.mentor_notes, f.student_rating " +
                    "  FROM bookings b " +
                    "  JOIN mentor_slots s ON s.id = b.slot_id " +
                    "  LEFT JOIN users u ON u.id = b.student_id " +
                    "  LEFT JOIN interview_feedback f ON f.booking_id = b.id " +
                    " WHERE b.mentor_id = ? AND b.status IN ('confirmed', 'completed', 'no_show') " +
                    " ORDER BY s.start_at DESC")) {
                stmt.setInt(1, mentorId);
                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> bookings = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> b = new HashMap<>();
                    b.put("id", rs.getInt("id"));
                    b.put("stack", rs.getString("stack"));
                    b.put("notes", rs.getString("notes"));
                    b.put("amount_bdt", rs.getInt("amount_bdt"));
                    b.put("payout_bdt", rs.getInt("amount_bdt") - rs.getInt("platform_fee_bdt"));
                    b.put("status", rs.getString("status"));
                    b.put("meeting_link", rs.getString("meeting_link"));
                    b.put("start_at", MockInterviewSupport.iso(rs.getTimestamp("start_at")));
                    b.put("end_at", MockInterviewSupport.iso(rs.getTimestamp("end_at")));
                    b.put("student_name", rs.getString("student_name"));
                    b.put("student_email", rs.getString("student_email"));
                    b.put("student_institute", rs.getString("institute"));
                    b.put("mentor_verdict", rs.getString("mentor_verdict"));
                    b.put("mentor_notes", rs.getString("mentor_notes"));
                    int rating = rs.getInt("student_rating");
                    b.put("student_rating", rs.wasNull() ? null : rating);
                    bookings.add(b);
                }
                sendJSON(exchange, 200, bookings);
            }
        } finally {
            conn.close();
        }
    }

    private void handleSetMeetingLink(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));
        if (!json.has("booking_id")) {
            sendError(exchange, 400, "Missing required field: booking_id");
            return;
        }
        int bookingId = json.get("booking_id").asInt();
        String link = text(json, "meeting_link");

        if (link.isEmpty() || !link.startsWith("https://")) {
            sendError(exchange, 400, "meeting_link must be an https:// URL (your Google Meet or Zoom room).");
            return;
        }

        Connection conn = DbConnection.getConnection();
        try {
            Integer mentorId = MockInterviewSupport.mentorIdForUser(conn, userId);
            if (mentorId == null) {
                sendError(exchange, 403, "You do not have a mentor profile.");
                return;
            }

            int updated;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE bookings SET meeting_link = ? " +
                    " WHERE id = ? AND mentor_id = ? AND status IN ('confirmed', 'completed')")) {
                stmt.setString(1, link);
                stmt.setInt(2, bookingId);
                stmt.setInt(3, mentorId);
                updated = stmt.executeUpdate();
            }

            if (updated == 0) {
                sendError(exchange, 404, "No confirmed booking of yours with that id.");
                return;
            }
            sendJSON(exchange, 200, Map.of("success", true, "message", "Meeting link updated."));
        } finally {
            conn.close();
        }
    }

    // ── POST /mentor/complete ──────────────────────────────────────────────

    private void handleComplete(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));
        if (!json.has("booking_id")) {
            sendError(exchange, 400, "Missing required field: booking_id");
            return;
        }
        int bookingId = json.get("booking_id").asInt();
        String verdict = text(json, "verdict").toLowerCase();
        String notes = text(json, "notes");
        boolean noShow = json.has("no_show") && json.get("no_show").asBoolean(false);

        if (!noShow && !verdict.isEmpty()
                && !verdict.equals("hire") && !verdict.equals("lean_hire") && !verdict.equals("no_hire")) {
            sendError(exchange, 400, "verdict must be one of: hire, lean_hire, no_hire");
            return;
        }

        Connection conn = DbConnection.getConnection();
        boolean committed = false;
        try {
            conn.setAutoCommit(false);

            Integer mentorId = MockInterviewSupport.mentorIdForUser(conn, userId);
            if (mentorId == null) {
                conn.rollback();
                sendError(exchange, 403, "You do not have a mentor profile.");
                return;
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT b.status FROM bookings b WHERE b.id = ? AND b.mentor_id = ?")) {
                stmt.setInt(1, bookingId);
                stmt.setInt(2, mentorId);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    conn.rollback();
                    sendError(exchange, 404, "Booking not found");
                    return;
                }
                if (!"confirmed".equals(rs.getString("status"))) {
                    conn.rollback();
                    sendError(exchange, 409, "Only a confirmed booking can be closed out.");
                    return;
                }
            }

            String finalStatus = noShow ? "no_show" : "completed";
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE bookings SET status = ? WHERE id = ?")) {
                stmt.setString(1, finalStatus);
                stmt.setInt(2, bookingId);
                stmt.executeUpdate();
            }

            if (!noShow) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO interview_feedback (booking_id, mentor_verdict, mentor_notes) " +
                        "VALUES (?, ?, ?) " +
                        "ON CONFLICT (booking_id) DO UPDATE SET " +
                        "  mentor_verdict = EXCLUDED.mentor_verdict, mentor_notes = EXCLUDED.mentor_notes")) {
                    stmt.setInt(1, bookingId);
                    stmt.setString(2, nullable(verdict));
                    stmt.setString(3, nullable(notes));
                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE mentors SET sessions_completed = sessions_completed + 1 WHERE id = ?")) {
                    stmt.setInt(1, mentorId);
                    stmt.executeUpdate();
                }
            }

            conn.commit();
            committed = true;

            sendJSON(exchange, 200, Map.of(
                    "success", true,
                    "status", finalStatus,
                    "message", noShow ? "Marked as a no-show." : "Session closed and evaluation sent to the student."));

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

    private String text(JsonNode json, String field) {
        return json.has(field) && !json.get(field).isNull() ? json.get(field).asText("").trim() : "";
    }

    private String nullable(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
