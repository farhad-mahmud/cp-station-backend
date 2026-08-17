package Handlers.mock;

import Handlers.AbstractHttpHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import config.DbConnection;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The instructor's course workspace. Registered behind AuthDecorator.
 *
 * Identity is the existing `mentors` row — a trainer approved for mock
 * interviews can open a classroom with no second profile and no second vetting.
 *
 * Routes:
 *   GET    /mentor/courses          own courses in every status
 *   POST   /mentor/courses          create a draft
 *   PUT    /mentor/courses          edit a course, or submit/withdraw it
 *   POST   /mentor/courses/syllabus replace the whole syllabus for a course
 *   PUT    /mentor/courses/syllabus mark an item covered / not covered
 *   GET    /mentor/course-students  roster for one course
 */
public class MentorCoursesHandler extends AbstractHttpHandler {

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
            case "/mentor/courses":
                if (method.equals("GET")) handleList(exchange, userId);
                else if (method.equals("POST")) handleCreate(exchange, userId);
                else if (method.equals("PUT")) handleUpdate(exchange, userId);
                else sendError(exchange, 405, "Method Not Allowed");
                break;
            case "/mentor/courses/syllabus":
                if (method.equals("POST")) handleReplaceSyllabus(exchange, userId);
                else if (method.equals("PUT")) handleCoverItem(exchange, userId);
                else sendError(exchange, 405, "Method Not Allowed");
                break;
            case "/mentor/course-students":
                if (method.equals("GET")) handleRoster(exchange, userId);
                else sendError(exchange, 405, "Method Not Allowed");
                break;
            default:
                sendError(exchange, 404, "Not Found");
        }
    }

    // ── GET /mentor/courses ────────────────────────────────────────────────

    private void handleList(HttpExchange exchange, int userId) throws Exception {
        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredEnrollments(conn);

            Integer mentorId = MockInterviewSupport.mentorIdForUser(conn, userId);
            if (mentorId == null) {
                sendError(exchange, 403, "You do not have a mentor profile.");
                return;
            }

            List<Map<String, Object>> courses = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT c.id, c.title, c.summary, c.description, c.stack, c.level, c.price_bdt, " +
                    "       c.duration_weeks, c.total_hours, c.seat_limit, c.enrolled_count, " +
                    "       c.start_date, c.schedule_note, c.meeting_link, c.cover_url, c.status, " +
                    "       c.created_at, " +
                    "       (SELECT COUNT(*) FROM enrollments e WHERE e.course_id = c.id " +
                    "          AND e.status IN ('active', 'completed')) AS paid_students, " +
                    "       (SELECT COALESCE(SUM(e.amount_bdt - e.platform_fee_bdt), 0) FROM enrollments e " +
                    "         WHERE e.course_id = c.id AND e.status IN ('active', 'completed')) AS net_earnings " +
                    "  FROM courses c WHERE c.mentor_id = ? ORDER BY c.created_at DESC")) {
                stmt.setInt(1, mentorId);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    Map<String, Object> c = new HashMap<>();
                    c.put("id", rs.getInt("id"));
                    c.put("title", rs.getString("title"));
                    c.put("summary", rs.getString("summary"));
                    c.put("description", rs.getString("description"));
                    c.put("stack", rs.getString("stack"));
                    c.put("level", rs.getString("level"));
                    c.put("price_bdt", rs.getInt("price_bdt"));
                    c.put("duration_weeks", rs.getInt("duration_weeks"));
                    int totalHours = rs.getInt("total_hours");
                    c.put("total_hours", rs.wasNull() ? null : totalHours);
                    c.put("seat_limit", rs.getInt("seat_limit"));
                    c.put("enrolled_count", rs.getInt("enrolled_count"));
                    c.put("seats_left", Math.max(0, rs.getInt("seat_limit") - rs.getInt("enrolled_count")));
                    Date startDate = rs.getDate("start_date");
                    c.put("start_date", startDate == null ? null : startDate.toString());
                    c.put("schedule_note", rs.getString("schedule_note"));
                    c.put("meeting_link", rs.getString("meeting_link"));
                    c.put("cover_url", rs.getString("cover_url"));
                    c.put("status", rs.getString("status"));
                    c.put("created_at", MockInterviewSupport.iso(rs.getTimestamp("created_at")));
                    c.put("paid_students", rs.getInt("paid_students"));
                    c.put("net_earnings_bdt", rs.getInt("net_earnings"));
                    courses.add(c);
                }
            }

            for (Map<String, Object> course : courses) {
                course.put("syllabus", loadSyllabus(conn, (Integer) course.get("id")));
            }

            sendJSON(exchange, 200, courses);
        } finally {
            conn.close();
        }
    }

    // ── POST /mentor/courses ───────────────────────────────────────────────

    private void handleCreate(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));

        String title = text(json, "title");
        String stack = text(json, "stack");
        int price = json.has("price_bdt") ? json.get("price_bdt").asInt(0) : 0;

        String invalid = validate(title, stack, price, json);
        if (invalid != null) {
            sendError(exchange, 400, invalid);
            return;
        }

        Connection conn = DbConnection.getConnection();
        try {
            Integer mentorId = MockInterviewSupport.approvedMentorIdForUser(conn, userId);
            if (mentorId == null) {
                sendError(exchange, 403, "Only an approved mentor can create a course.");
                return;
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO courses (mentor_id, title, summary, description, stack, level, " +
                    "                     price_bdt, duration_weeks, total_hours, seat_limit, " +
                    "                     start_date, schedule_note, meeting_link, cover_url, status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'draft') RETURNING id")) {
                stmt.setInt(1, mentorId);
                bindCourseFields(stmt, json, title, stack, price, 1);
                ResultSet rs = stmt.executeQuery();
                rs.next();

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("course_id", rs.getInt("id"));
                response.put("status", "draft");
                response.put("message", "Draft created. Add your syllabus, then submit it for review.");
                sendJSON(exchange, 201, response);
            }
        } finally {
            conn.close();
        }
    }

    // ── PUT /mentor/courses ────────────────────────────────────────────────

    /**
     * Edits a course, and optionally moves it between draft and pending.
     *
     * A mentor may submit (draft → pending) or withdraw (pending → draft), but
     * never publish: only an admin does that. A published course can still be
     * edited but cannot be silently unpublished while students are enrolled.
     */
    private void handleUpdate(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));
        if (!json.has("course_id")) {
            sendError(exchange, 400, "Missing required field: course_id");
            return;
        }
        int courseId = json.get("course_id").asInt();
        String action = text(json, "action").toLowerCase();

        Connection conn = DbConnection.getConnection();
        try {
            Integer mentorId = MockInterviewSupport.mentorIdForUser(conn, userId);
            if (mentorId == null) {
                sendError(exchange, 403, "You do not have a mentor profile.");
                return;
            }

            String currentStatus;
            int syllabusCount;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT c.status, (SELECT COUNT(*) FROM course_syllabus s WHERE s.course_id = c.id) AS n " +
                    "  FROM courses c WHERE c.id = ? AND c.mentor_id = ?")) {
                stmt.setInt(1, courseId);
                stmt.setInt(2, mentorId);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    sendError(exchange, 404, "Course not found");
                    return;
                }
                currentStatus = rs.getString("status");
                syllabusCount = rs.getInt("n");
            }

            // Status transition, if one was asked for.
            String newStatus = null;
            if (action.equals("submit")) {
                if (!currentStatus.equals("draft")) {
                    sendError(exchange, 409, "Only a draft can be submitted for review.");
                    return;
                }
                if (syllabusCount == 0) {
                    sendError(exchange, 400, "Add at least one syllabus item before submitting.");
                    return;
                }
                newStatus = "pending";
            } else if (action.equals("withdraw")) {
                if (!currentStatus.equals("pending")) {
                    sendError(exchange, 409, "Only a course awaiting review can be withdrawn.");
                    return;
                }
                newStatus = "draft";
            } else if (action.equals("archive")) {
                newStatus = "archived";
            } else if (!action.isEmpty()) {
                sendError(exchange, 400, "action must be one of: submit, withdraw, archive");
                return;
            }

            // Field edits are optional — a bare action call just moves status.
            if (json.has("title")) {
                String title = text(json, "title");
                String stack = text(json, "stack");
                int price = json.has("price_bdt") ? json.get("price_bdt").asInt(0) : 0;
                String invalid = validate(title, stack, price, json);
                if (invalid != null) {
                    sendError(exchange, 400, invalid);
                    return;
                }

                int seatLimit = json.has("seat_limit") ? json.get("seat_limit").asInt(20) : 20;
                // Never let the cap drop below the seats already sold, or the
                // courses_seats_chk constraint would reject the update.
                try (PreparedStatement check = conn.prepareStatement(
                        "SELECT enrolled_count FROM courses WHERE id = ?")) {
                    check.setInt(1, courseId);
                    ResultSet rs = check.executeQuery();
                    rs.next();
                    int enrolled = rs.getInt("enrolled_count");
                    if (seatLimit < enrolled) {
                        sendError(exchange, 400, "Seat limit cannot be lower than the "
                                + enrolled + " seat(s) already taken.");
                        return;
                    }
                }

                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE courses SET title = ?, summary = ?, description = ?, stack = ?, " +
                        "       level = ?, price_bdt = ?, duration_weeks = ?, total_hours = ?, " +
                        "       seat_limit = ?, start_date = ?, schedule_note = ?, meeting_link = ?, " +
                        "       cover_url = ?, updated_at = NOW() " +
                        " WHERE id = ? AND mentor_id = ?")) {
                    bindCourseFields(stmt, json, title, stack, price, 0);
                    stmt.setInt(14, courseId);
                    stmt.setInt(15, mentorId);
                    stmt.executeUpdate();
                }
            }

            if (newStatus != null) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE courses SET status = ?, updated_at = NOW() WHERE id = ? AND mentor_id = ?")) {
                    stmt.setString(1, newStatus);
                    stmt.setInt(2, courseId);
                    stmt.setInt(3, mentorId);
                    stmt.executeUpdate();
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("status", newStatus != null ? newStatus : currentStatus);
            response.put("message", newStatus == null ? "Course updated."
                    : newStatus.equals("pending") ? "Submitted for review. An admin will publish it."
                    : newStatus.equals("draft") ? "Withdrawn back to draft."
                    : "Course archived.");
            sendJSON(exchange, 200, response);
        } finally {
            conn.close();
        }
    }

    // ── POST /mentor/courses/syllabus ──────────────────────────────────────

    /**
     * Replaces the syllabus wholesale, which is what a drag-to-reorder editor
     * needs. covered_at is preserved by title so marking progress is not lost
     * when the mentor edits wording or reorders items.
     */
    private void handleReplaceSyllabus(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));
        if (!json.has("course_id") || !json.has("items") || !json.get("items").isArray()) {
            sendError(exchange, 400, "Missing required fields: course_id and items[]");
            return;
        }
        int courseId = json.get("course_id").asInt();

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
                    "SELECT 1 FROM courses WHERE id = ? AND mentor_id = ?")) {
                stmt.setInt(1, courseId);
                stmt.setInt(2, mentorId);
                if (!stmt.executeQuery().next()) {
                    conn.rollback();
                    sendError(exchange, 404, "Course not found");
                    return;
                }
            }

            Map<String, java.sql.Timestamp> coveredByTitle = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT title, covered_at FROM course_syllabus WHERE course_id = ? AND covered_at IS NOT NULL")) {
                stmt.setInt(1, courseId);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    coveredByTitle.put(rs.getString("title"), rs.getTimestamp("covered_at"));
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM course_syllabus WHERE course_id = ?")) {
                stmt.setInt(1, courseId);
                stmt.executeUpdate();
            }

            int position = 0;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO course_syllabus (course_id, position, title, detail, est_hours, covered_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)")) {
                for (JsonNode item : json.get("items")) {
                    String itemTitle = item.has("title") ? item.get("title").asText("").trim() : "";
                    if (itemTitle.isEmpty()) continue;

                    position++;
                    stmt.setInt(1, courseId);
                    stmt.setInt(2, position);
                    stmt.setString(3, itemTitle);
                    String detail = item.has("detail") ? item.get("detail").asText("").trim() : "";
                    stmt.setString(4, detail.isEmpty() ? null : detail);
                    if (item.has("est_hours") && !item.get("est_hours").isNull()
                            && item.get("est_hours").asDouble(0) > 0) {
                        stmt.setBigDecimal(5, new java.math.BigDecimal(
                                String.valueOf(item.get("est_hours").asDouble())));
                    } else {
                        stmt.setNull(5, Types.NUMERIC);
                    }
                    java.sql.Timestamp covered = coveredByTitle.get(itemTitle);
                    if (covered != null) {
                        stmt.setTimestamp(6, covered);
                    } else {
                        stmt.setNull(6, Types.TIMESTAMP);
                    }
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            conn.commit();
            committed = true;
            sendJSON(exchange, 200, Map.of(
                    "success", true, "items", position, "message", "Syllabus saved."));

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

    // ── PUT /mentor/courses/syllabus ───────────────────────────────────────

    private void handleCoverItem(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));
        if (!json.has("item_id")) {
            sendError(exchange, 400, "Missing required field: item_id");
            return;
        }
        int itemId = json.get("item_id").asInt();
        boolean covered = !json.has("covered") || json.get("covered").asBoolean(true);

        Connection conn = DbConnection.getConnection();
        try {
            Integer mentorId = MockInterviewSupport.mentorIdForUser(conn, userId);
            if (mentorId == null) {
                sendError(exchange, 403, "You do not have a mentor profile.");
                return;
            }

            int updated;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE course_syllabus s SET covered_at = " + (covered ? "NOW()" : "NULL") +
                    " WHERE s.id = ? AND EXISTS (SELECT 1 FROM courses c " +
                    "        WHERE c.id = s.course_id AND c.mentor_id = ?)")) {
                stmt.setInt(1, itemId);
                stmt.setInt(2, mentorId);
                updated = stmt.executeUpdate();
            }

            if (updated == 0) {
                sendError(exchange, 404, "No syllabus item of yours with that id.");
                return;
            }
            sendJSON(exchange, 200, Map.of("success", true,
                    "message", covered ? "Marked as covered." : "Marked as not covered yet."));
        } finally {
            conn.close();
        }
    }

    // ── GET /mentor/course-students?courseId= ──────────────────────────────

    private void handleRoster(HttpExchange exchange, int userId) throws Exception {
        Integer courseId = MockInterviewSupport.intParam(
                MockInterviewSupport.queryParams(exchange), "courseId");
        if (courseId == null) {
            sendError(exchange, 400, "Missing or invalid query parameter: courseId");
            return;
        }

        Connection conn = DbConnection.getConnection();
        try {
            Integer mentorId = MockInterviewSupport.mentorIdForUser(conn, userId);
            if (mentorId == null) {
                sendError(exchange, 403, "You do not have a mentor profile.");
                return;
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT e.id, e.status, e.goal, e.amount_bdt, e.platform_fee_bdt, e.created_at, " +
                    "       u.name AS student_name, u.email AS student_email, u.institute " +
                    "  FROM enrollments e " +
                    "  JOIN courses c ON c.id = e.course_id " +
                    "  LEFT JOIN users u ON u.id = e.student_id " +
                    " WHERE e.course_id = ? AND c.mentor_id = ? " +
                    "   AND e.status IN ('active', 'completed') " +
                    " ORDER BY e.created_at")) {
                stmt.setInt(1, courseId);
                stmt.setInt(2, mentorId);
                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> roster = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> s = new HashMap<>();
                    s.put("enrollment_id", rs.getInt("id"));
                    s.put("status", rs.getString("status"));
                    s.put("goal", rs.getString("goal"));
                    s.put("payout_bdt", rs.getInt("amount_bdt") - rs.getInt("platform_fee_bdt"));
                    s.put("joined_at", MockInterviewSupport.iso(rs.getTimestamp("created_at")));
                    s.put("student_name", rs.getString("student_name"));
                    s.put("student_email", rs.getString("student_email"));
                    s.put("student_institute", rs.getString("institute"));
                    roster.add(s);
                }
                sendJSON(exchange, 200, roster);
            }
        } finally {
            conn.close();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    static List<Map<String, Object>> loadSyllabus(Connection conn, int courseId) throws Exception {
        List<Map<String, Object>> syllabus = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT id, position, title, detail, est_hours, covered_at " +
                "  FROM course_syllabus WHERE course_id = ? ORDER BY position")) {
            stmt.setInt(1, courseId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", rs.getInt("id"));
                item.put("position", rs.getInt("position"));
                item.put("title", rs.getString("title"));
                item.put("detail", rs.getString("detail"));
                item.put("est_hours", rs.getBigDecimal("est_hours"));
                item.put("covered_at", MockInterviewSupport.iso(rs.getTimestamp("covered_at")));
                syllabus.add(item);
            }
        }
        return syllabus;
    }

    private String validate(String title, String stack, int price, JsonNode json) {
        if (title.isEmpty()) return "Course title is required.";
        if (stack.isEmpty()) return "Stack or topic is required.";
        if (price < 100 || price > 100000) return "Course fee must be between BDT 100 and BDT 100000.";

        int weeks = json.has("duration_weeks") ? json.get("duration_weeks").asInt(4) : 4;
        if (weeks < 1 || weeks > 104) return "Duration must be between 1 and 104 weeks.";

        int seats = json.has("seat_limit") ? json.get("seat_limit").asInt(20) : 20;
        if (seats < 1 || seats > 1000) return "Seat limit must be between 1 and 1000.";

        String level = text(json, "level").toLowerCase();
        if (!level.isEmpty() && !level.equals("beginner") && !level.equals("intermediate")
                && !level.equals("advanced")) {
            return "level must be one of: beginner, intermediate, advanced";
        }

        String link = text(json, "meeting_link");
        if (!link.isEmpty() && !link.startsWith("https://")) {
            return "Class link must be an https:// URL.";
        }

        String start = text(json, "start_date");
        if (!start.isEmpty()) {
            try {
                Date.valueOf(start);
            } catch (Exception e) {
                return "start_date must be formatted as YYYY-MM-DD.";
            }
        }
        return null;
    }

    /**
     * Binds the 13 course fields in a fixed order, starting at {@code offset + 1}.
     *
     * The INSERT puts mentor_id first, so it passes offset = 1; the UPDATE starts
     * with the fields themselves, so it passes offset = 0. Keeping the order in
     * one place means the two statements cannot drift apart.
     */
    private void bindCourseFields(PreparedStatement stmt, JsonNode json, String title,
                                  String stack, int price, int offset) throws Exception {
        stmt.setString(offset + 1, title);
        setNullable(stmt, offset + 2, text(json, "summary"));
        setNullable(stmt, offset + 3, text(json, "description"));
        stmt.setString(offset + 4, stack);
        String level = text(json, "level").toLowerCase();
        stmt.setString(offset + 5, level.isEmpty() ? "beginner" : level);
        stmt.setInt(offset + 6, price);
        stmt.setInt(offset + 7, json.has("duration_weeks") ? json.get("duration_weeks").asInt(4) : 4);
        if (json.has("total_hours") && !json.get("total_hours").isNull()
                && json.get("total_hours").asInt(0) > 0) {
            stmt.setInt(offset + 8, json.get("total_hours").asInt());
        } else {
            stmt.setNull(offset + 8, Types.INTEGER);
        }
        stmt.setInt(offset + 9, json.has("seat_limit") ? json.get("seat_limit").asInt(20) : 20);
        String start = text(json, "start_date");
        if (start.isEmpty()) {
            stmt.setNull(offset + 10, Types.DATE);
        } else {
            stmt.setDate(offset + 10, Date.valueOf(start));
        }
        setNullable(stmt, offset + 11, text(json, "schedule_note"));
        setNullable(stmt, offset + 12, text(json, "meeting_link"));
        setNullable(stmt, offset + 13, text(json, "cover_url"));
    }

    private void setNullable(PreparedStatement stmt, int index, String value) throws Exception {
        if (value == null || value.isEmpty()) {
            stmt.setNull(index, Types.VARCHAR);
        } else {
            stmt.setString(index, value);
        }
    }

    private String text(JsonNode json, String field) {
        return json.has(field) && !json.get(field).isNull() ? json.get(field).asText("").trim() : "";
    }
}
