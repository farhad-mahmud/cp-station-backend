package Handlers.mock;

import Handlers.AbstractHttpHandler;
import com.sun.net.httpserver.HttpExchange;
import config.DbConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Public catalogue for the Mock Interview feature. No authentication: a logged
 * out visitor can browse experts and only meets the login gate when booking.
 *
 * Routes:
 *   GET /mentors?stack=&q=&maxPrice=   approved mentors, filtered
 *   GET /mentor-detail?id=             one mentor with stacks and reviews
 *   GET /mentor-slots?mentorId=        that mentor's open, future slots
 *   GET /mentor-stacks                 distinct stacks, for the filter UI
 */
public class MentorsHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/mentors":
                handleList(exchange);
                break;
            case "/mentor-detail":
                handleDetail(exchange);
                break;
            case "/mentor-slots":
                handleSlots(exchange);
                break;
            case "/mentor-stacks":
                handleStacks(exchange);
                break;
            default:
                sendError(exchange, 404, "Not Found");
        }
    }

    // ── GET /mentors ───────────────────────────────────────────────────────

    private void handleList(HttpExchange exchange) throws Exception {
        Map<String, String> params = MockInterviewSupport.queryParams(exchange);
        String stack = params.get("stack");
        String search = params.get("q");
        Integer maxPrice = MockInterviewSupport.intParam(params, "maxPrice");

        StringBuilder sql = new StringBuilder(
            "SELECT m.id, m.display_name, m.headline, m.company, m.years_experience, " +
            "       m.hourly_rate_bdt, m.photo_url, m.status, m.rating_avg, m.rating_count, " +
            "       m.sessions_completed, " +
            "       COALESCE(string_agg(DISTINCT ms.stack, ',' ORDER BY ms.stack), '') AS stacks, " +
            "       (SELECT COUNT(*) FROM mentor_slots sl " +
            "         WHERE sl.mentor_id = m.id AND sl.status = 'open' AND sl.start_at > NOW()) AS open_slots " +
            "  FROM mentors m " +
            "  LEFT JOIN mentor_stacks ms ON ms.mentor_id = m.id " +
            " WHERE m.status = 'approved'"
        );

        List<Object> args = new ArrayList<>();

        if (stack != null && !stack.trim().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM mentor_stacks f WHERE f.mentor_id = m.id AND LOWER(f.stack) = LOWER(?))");
            args.add(stack.trim());
        }
        if (search != null && !search.trim().isEmpty()) {
            sql.append(" AND (m.display_name ILIKE ? OR m.headline ILIKE ? OR m.company ILIKE ?)");
            String like = "%" + search.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (maxPrice != null) {
            sql.append(" AND m.hourly_rate_bdt <= ?");
            args.add(maxPrice);
        }

        sql.append(" GROUP BY m.id ORDER BY m.rating_avg DESC, m.sessions_completed DESC, m.id");

        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                bind(stmt, args);
                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> mentors = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> mentor = MockInterviewSupport.mapMentorRow(rs);
                    mentor.put("stacks", splitStacks(rs.getString("stacks")));
                    mentor.put("open_slots", rs.getInt("open_slots"));
                    mentors.add(mentor);
                }
                sendJSON(exchange, 200, mentors);
            }
        } finally {
            conn.close();
        }
    }

    // ── GET /mentor-detail?id= ─────────────────────────────────────────────

    private void handleDetail(HttpExchange exchange) throws Exception {
        Integer mentorId = MockInterviewSupport.intParam(MockInterviewSupport.queryParams(exchange), "id");
        if (mentorId == null) {
            sendError(exchange, 400, "Missing or invalid query parameter: id");
            return;
        }

        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);

            Map<String, Object> mentor;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT m.id, m.display_name, m.headline, m.bio, m.company, m.years_experience, " +
                    "       m.hourly_rate_bdt, m.photo_url, m.status, m.rating_avg, m.rating_count, " +
                    "       m.sessions_completed, m.created_at " +
                    "  FROM mentors m WHERE m.id = ? AND m.status = 'approved'")) {
                stmt.setInt(1, mentorId);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    sendError(exchange, 404, "Mentor not found");
                    return;
                }
                mentor = MockInterviewSupport.mapMentorRow(rs);
                mentor.put("bio", rs.getString("bio"));
                mentor.put("member_since", MockInterviewSupport.iso(rs.getTimestamp("created_at")));
            }

            // Expertise tags
            List<String> stacks = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT stack FROM mentor_stacks WHERE mentor_id = ? ORDER BY stack")) {
                stmt.setInt(1, mentorId);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    stacks.add(rs.getString("stack"));
                }
            }
            mentor.put("stacks", stacks);

            // Public reviews left by students
            List<Map<String, Object>> reviews = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT f.student_rating, f.student_comment, f.created_at, b.stack, u.name AS student_name " +
                    "  FROM interview_feedback f " +
                    "  JOIN bookings b ON b.id = f.booking_id " +
                    "  LEFT JOIN users u ON u.id = b.student_id " +
                    " WHERE b.mentor_id = ? AND f.student_rating IS NOT NULL " +
                    " ORDER BY f.created_at DESC LIMIT 20")) {
                stmt.setInt(1, mentorId);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    Map<String, Object> review = new HashMap<>();
                    review.put("rating", rs.getInt("student_rating"));
                    review.put("comment", rs.getString("student_comment"));
                    review.put("stack", rs.getString("stack"));
                    review.put("student_name", rs.getString("student_name"));
                    review.put("created_at", MockInterviewSupport.iso(rs.getTimestamp("created_at")));
                    reviews.add(review);
                }
            }
            mentor.put("reviews", reviews);

            sendJSON(exchange, 200, mentor);
        } finally {
            conn.close();
        }
    }

    // ── GET /mentor-slots?mentorId= ────────────────────────────────────────

    private void handleSlots(HttpExchange exchange) throws Exception {
        Integer mentorId = MockInterviewSupport.intParam(MockInterviewSupport.queryParams(exchange), "mentorId");
        if (mentorId == null) {
            sendError(exchange, 400, "Missing or invalid query parameter: mentorId");
            return;
        }

        Connection conn = DbConnection.getConnection();
        try {
            MockInterviewSupport.releaseExpiredHolds(conn);

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, mentor_id, start_at, end_at, status FROM mentor_slots " +
                    " WHERE mentor_id = ? AND status = 'open' AND start_at > NOW() " +
                    " ORDER BY start_at")) {
                stmt.setInt(1, mentorId);
                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> slots = new ArrayList<>();
                while (rs.next()) {
                    slots.add(MockInterviewSupport.mapSlotRow(rs));
                }
                sendJSON(exchange, 200, slots);
            }
        } finally {
            conn.close();
        }
    }

    // ── GET /mentor-stacks ─────────────────────────────────────────────────

    private void handleStacks(HttpExchange exchange) throws Exception {
        Connection conn = DbConnection.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT DISTINCT s.stack FROM mentor_stacks s " +
                "  JOIN mentors m ON m.id = s.mentor_id " +
                " WHERE m.status = 'approved' ORDER BY s.stack")) {
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

    // ── helpers ────────────────────────────────────────────────────────────

    private void bind(PreparedStatement stmt, List<Object> args) throws Exception {
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg instanceof Integer) {
                stmt.setInt(i + 1, (Integer) arg);
            } else {
                stmt.setString(i + 1, String.valueOf(arg));
            }
        }
    }

    private List<String> splitStacks(String aggregated) {
        if (aggregated == null || aggregated.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(aggregated.split(",")));
    }
}
