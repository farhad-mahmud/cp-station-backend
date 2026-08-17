package Handlers.mock;

import auth.SessionUtil;
import com.sun.net.httpserver.HttpExchange;
import config.Env;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared helpers for the mock interview handlers: session resolution, hold
 * expiry sweeping, commission maths and row mapping.
 */
public final class MockInterviewSupport {

    /** How long a slot stays reserved while the student pays. */
    public static final int HOLD_MINUTES = 30;

    private MockInterviewSupport() {
    }

    // ── Session ────────────────────────────────────────────────────────────

    /**
     * Resolves the caller's user id. Prefers the attribute injected by
     * AuthDecorator, falling back to the raw cookie so a handler still works if
     * it is ever registered without the decorator.
     */
    public static Integer userId(HttpExchange exchange) {
        Object attr = exchange.getAttribute("userId");
        if (attr instanceof Integer) {
            return (Integer) attr;
        }
        String token = SessionUtil.extractTokenFromCookies(exchange.getRequestHeaders().getFirst("Cookie"));
        return SessionUtil.getUserIdFromToken(token);
    }

    public static String role(HttpExchange exchange) {
        Object attr = exchange.getAttribute("role");
        if (attr instanceof String) {
            return (String) attr;
        }
        String token = SessionUtil.extractTokenFromCookies(exchange.getRequestHeaders().getFirst("Cookie"));
        return SessionUtil.getRoleFromToken(token);
    }

    public static boolean isAdmin(HttpExchange exchange) {
        String role = role(exchange);
        return role != null && role.equalsIgnoreCase("admin");
    }

    // ── Query strings ──────────────────────────────────────────────────────

    /** Parses a raw query string into a map, URL-decoding both keys and values. */
    public static Map<String, String> queryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String key = java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String value = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                params.put(key, value);
            } catch (Exception ignored) {
                // Skip malformed pairs rather than failing the whole request.
            }
        }
        return params;
    }

    /** Parses an integer query parameter, returning null when absent or unparseable. */
    public static Integer intParam(Map<String, String> params, String key) {
        String raw = params.get(key);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Money ──────────────────────────────────────────────────────────────

    /**
     * Platform commission in whole BDT, rounded down so the mentor is never
     * short-changed by rounding. Configure with PLATFORM_FEE_PERCENT.
     */
    public static int platformFee(int amountBdt) {
        int percent;
        try {
            percent = Integer.parseInt(Env.get("PLATFORM_FEE_PERCENT", "20"));
        } catch (NumberFormatException e) {
            percent = 20;
        }
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        return (amountBdt * percent) / 100;
    }

    // ── Slot hold expiry ───────────────────────────────────────────────────

    /**
     * Lazy sweep: releases slots whose payment hold ran out, and expires the
     * matching bookings. Called at the top of any read that exposes slot
     * availability, which removes the need for a scheduler on a single-process
     * server.
     */
    public static void releaseExpiredHolds(Connection conn) throws Exception {
        try (PreparedStatement slots = conn.prepareStatement(
                "UPDATE mentor_slots SET status = 'open' WHERE status = 'held' AND id IN (" +
                "  SELECT slot_id FROM bookings" +
                "   WHERE status = 'pending_payment' AND hold_expires_at IS NOT NULL AND hold_expires_at < NOW())")) {
            slots.executeUpdate();
        }
        try (PreparedStatement books = conn.prepareStatement(
                "UPDATE bookings SET status = 'expired'" +
                " WHERE status = 'pending_payment' AND hold_expires_at IS NOT NULL AND hold_expires_at < NOW()")) {
            books.executeUpdate();
        }
        try (PreparedStatement pays = conn.prepareStatement(
                "UPDATE payments SET status = 'failed', updated_at = NOW()" +
                " WHERE status = 'initiated' AND booking_id IN (SELECT id FROM bookings WHERE status = 'expired')")) {
            pays.executeUpdate();
        }
    }

    /**
     * Same lazy sweep for course enrollments. Releases the seat before marking
     * the enrollment expired, because once the status changes the row can no
     * longer be identified as one that was holding a seat.
     */
    public static void releaseExpiredEnrollments(Connection conn) throws Exception {
        try (PreparedStatement seats = conn.prepareStatement(
                "UPDATE courses c SET enrolled_count = GREATEST(c.enrolled_count - sub.n, 0)" +
                "  FROM (SELECT course_id, COUNT(*) AS n FROM enrollments" +
                "         WHERE status = 'pending_payment'" +
                "           AND hold_expires_at IS NOT NULL AND hold_expires_at < NOW()" +
                "         GROUP BY course_id) sub" +
                " WHERE c.id = sub.course_id")) {
            seats.executeUpdate();
        }
        try (PreparedStatement rows = conn.prepareStatement(
                "UPDATE enrollments SET status = 'expired'" +
                " WHERE status = 'pending_payment'" +
                "   AND hold_expires_at IS NOT NULL AND hold_expires_at < NOW()")) {
            rows.executeUpdate();
        }
        try (PreparedStatement pays = conn.prepareStatement(
                "UPDATE payments SET status = 'failed', updated_at = NOW()" +
                " WHERE status = 'initiated' AND enrollment_id IN" +
                "       (SELECT id FROM enrollments WHERE status = 'expired')")) {
            pays.executeUpdate();
        }
    }

    // ── Payment references ─────────────────────────────────────────────────

    /**
     * Reference the student types into the bKash reference field. Prefixed per
     * product so a mock interview and a course enrollment can never be confused
     * for each other while reading a bKash statement.
     */
    public static String bookingReference(int bookingId) {
        return "CPS-B" + bookingId;
    }

    public static String enrollmentReference(int enrollmentId) {
        return "CPS-C" + enrollmentId;
    }

    // ── Lookups ────────────────────────────────────────────────────────────

    /** Mentor id for a user, or null when that user is not a mentor. */
    public static Integer mentorIdForUser(Connection conn, int userId) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM mentors WHERE user_id = ?")) {
            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt("id") : null;
        }
    }

    /** Mentor id for a user, only when the profile is approved. */
    public static Integer approvedMentorIdForUser(Connection conn, int userId) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT id FROM mentors WHERE user_id = ? AND status = 'approved'")) {
            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt("id") : null;
        }
    }

    /** Recomputes a mentor's cached rating from the feedback table. */
    public static void recomputeRating(Connection conn, int mentorId) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE mentors m SET" +
                "  rating_avg = COALESCE(agg.avg_rating, 0)," +
                "  rating_count = COALESCE(agg.n, 0)" +
                " FROM (SELECT AVG(f.student_rating) AS avg_rating, COUNT(f.student_rating) AS n" +
                "         FROM interview_feedback f" +
                "         JOIN bookings b ON b.id = f.booking_id" +
                "        WHERE b.mentor_id = ? AND f.student_rating IS NOT NULL) agg" +
                " WHERE m.id = ?")) {
            stmt.setInt(1, mentorId);
            stmt.setInt(2, mentorId);
            stmt.executeUpdate();
        }
    }

    // ── Row mapping ────────────────────────────────────────────────────────

    /** ISO-8601 instant, or null. Keeps timestamps unambiguous for the browser. */
    public static String iso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    public static Map<String, Object> mapMentorRow(ResultSet rs) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("display_name", rs.getString("display_name"));
        m.put("headline", rs.getString("headline"));
        m.put("company", rs.getString("company"));
        m.put("years_experience", rs.getInt("years_experience"));
        m.put("hourly_rate_bdt", rs.getInt("hourly_rate_bdt"));
        m.put("photo_url", rs.getString("photo_url"));
        m.put("status", rs.getString("status"));
        m.put("rating_avg", rs.getBigDecimal("rating_avg"));
        m.put("rating_count", rs.getInt("rating_count"));
        m.put("sessions_completed", rs.getInt("sessions_completed"));
        return m;
    }

    public static Map<String, Object> mapSlotRow(ResultSet rs) throws Exception {
        Map<String, Object> s = new HashMap<>();
        s.put("id", rs.getInt("id"));
        s.put("mentor_id", rs.getInt("mentor_id"));
        s.put("start_at", iso(rs.getTimestamp("start_at")));
        s.put("end_at", iso(rs.getTimestamp("end_at")));
        s.put("status", rs.getString("status"));
        return s;
    }
}
