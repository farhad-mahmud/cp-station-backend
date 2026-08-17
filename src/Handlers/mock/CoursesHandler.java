package Handlers.mock;

import Handlers.AbstractHttpHandler;
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
 * Public catalogue for the Virtual Classroom. No authentication: visitors can
 * browse courses and syllabi, and only meet the login gate when enrolling.
 *
 * Routes:
 *   GET /courses?stack=&level=&q=   published courses
 *   GET /course-detail?id=          one course with its syllabus and instructor
 *   GET /course-stacks              distinct stacks, for the filter UI
 */
public class CoursesHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        switch (exchange.getRequestURI().getPath()) {
            case "/courses":
                handleList(exchange);
                break;
            case "/course-detail":
                handleDetail(exchange);
                break;
            case "/course-stacks":
                handleStacks(exchange);
                break;
            default:
                sendError(exchange, 404, "Not Found");
        }
    }

    // ── GET /courses ───────────────────────────────────────────────────────

    private void handleList(HttpExchange exchange) throws Exception {
        Map<String, String> params = MockInterviewSupport.queryParams(exchange);
        String stack = params.get("stack");
        String level = params.get("level");
        String search = params.get("q");

        StringBuilder sql = new StringBuilder(
            "SELECT c.id, c.title, c.summary, c.stack, c.level, c.price_bdt, c.duration_weeks, " +
            "       c.total_hours, c.seat_limit, c.enrolled_count, c.start_date, c.schedule_note, " +
            "       c.cover_url, c.status, " +
            "       m.id AS mentor_id, m.display_name, m.headline, m.company, m.photo_url, " +
            "       m.rating_avg, m.rating_count, " +
            "       (SELECT COUNT(*) FROM course_syllabus s WHERE s.course_id = c.id) AS syllabus_count " +
            "  FROM courses c " +
            "  JOIN mentors m ON m.id = c.mentor_id " +
            " WHERE c.status = 'published' AND m.status = 'approved'"
        );

        List<Object> args = new ArrayList<>();
        if (stack != null && !stack.trim().isEmpty()) {
            sql.append(" AND LOWER(c.stack) = LOWER(?)");
            args.add(stack.trim());
        }
        if (level != null && !level.trim().isEmpty()) {
            sql.append(" AND LOWER(c.level) = LOWER(?)");
            args.add(level.trim());
        }
        if (search != null && !search.trim().isEmpty()) {
            sql.append(" AND (c.title ILIKE ? OR c.summary ILIKE ? OR m.display_name ILIKE ?)");
            String like = "%" + search.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        // Courses that have not started yet come first, then by soonest start.
        sql.append(" ORDER BY (c.start_date IS NULL), c.start_date, c.id DESC");

        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredEnrollments(conn);

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) {
                    stmt.setString(i + 1, String.valueOf(args.get(i)));
                }
                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> courses = new ArrayList<>();
                while (rs.next()) {
                    courses.add(mapCourseRow(rs, false));
                }
                sendJSON(exchange, 200, courses);
            }
        } finally {
            conn.close();
        }
    }

    // ── GET /course-detail?id= ─────────────────────────────────────────────

    private void handleDetail(HttpExchange exchange) throws Exception {
        Integer courseId = MockInterviewSupport.intParam(
                MockInterviewSupport.queryParams(exchange), "id");
        if (courseId == null) {
            sendError(exchange, 400, "Missing or invalid query parameter: id");
            return;
        }

        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredEnrollments(conn);

            Map<String, Object> course;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT c.id, c.title, c.summary, c.description, c.stack, c.level, c.price_bdt, " +
                    "       c.duration_weeks, c.total_hours, c.seat_limit, c.enrolled_count, " +
                    "       c.start_date, c.schedule_note, c.cover_url, c.status, " +
                    "       m.id AS mentor_id, m.display_name, m.headline, m.company, m.photo_url, " +
                    "       m.bio AS mentor_bio, m.years_experience, m.rating_avg, m.rating_count, " +
                    "       m.sessions_completed, " +
                    "       (SELECT COUNT(*) FROM course_syllabus s WHERE s.course_id = c.id) AS syllabus_count " +
                    "  FROM courses c " +
                    "  JOIN mentors m ON m.id = c.mentor_id " +
                    " WHERE c.id = ? AND c.status = 'published' AND m.status = 'approved'")) {
                stmt.setInt(1, courseId);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    sendError(exchange, 404, "Course not found");
                    return;
                }
                course = mapCourseRow(rs, true);
            }

            // The syllabus is a selling point, so it is public. covered_at lets a
            // visitor see how far an in-progress cohort has actually got.
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
            course.put("syllabus", syllabus);

            sendJSON(exchange, 200, course);
        } finally {
            conn.close();
        }
    }

    // ── GET /course-stacks ─────────────────────────────────────────────────

    private void handleStacks(HttpExchange exchange) throws Exception {
        Connection conn = DbConnection.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT DISTINCT c.stack FROM courses c JOIN mentors m ON m.id = c.mentor_id " +
                " WHERE c.status = 'published' AND m.status = 'approved' ORDER BY c.stack")) {
            ResultSet rs = stmt.executeQuery();
            List<String> stacks = new ArrayList<>();
            while (rs.next()) {
                stacks.add(rs.getString("stack"));
            }
            sendJSON(exchange, 200, stacks);
        } finally {
            conn.close();
        }
    }

    // ── mapping ────────────────────────────────────────────────────────────

    static Map<String, Object> mapCourseRow(ResultSet rs, boolean full) throws Exception {
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
        c.put("seats_left", Math.max(0, rs.getInt("seat_limit") - rs.getInt("enrolled_count")));
        java.sql.Date startDate = rs.getDate("start_date");
        c.put("start_date", startDate == null ? null : startDate.toString());
        c.put("schedule_note", rs.getString("schedule_note"));
        c.put("cover_url", rs.getString("cover_url"));
        c.put("status", rs.getString("status"));
        c.put("syllabus_count", rs.getInt("syllabus_count"));

        c.put("mentor_id", rs.getInt("mentor_id"));
        c.put("mentor_name", rs.getString("display_name"));
        c.put("mentor_headline", rs.getString("headline"));
        c.put("mentor_company", rs.getString("company"));
        c.put("mentor_photo", rs.getString("photo_url"));
        c.put("mentor_rating", rs.getBigDecimal("rating_avg"));
        c.put("mentor_rating_count", rs.getInt("rating_count"));

        if (full) {
            c.put("description", rs.getString("description"));
            c.put("mentor_bio", rs.getString("mentor_bio"));
            c.put("mentor_years", rs.getInt("years_experience"));
            c.put("mentor_sessions", rs.getInt("sessions_completed"));
        }
        return c;
    }
}
