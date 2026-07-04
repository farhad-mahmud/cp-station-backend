package auth;

import config.DbConnection;
import java.sql.*;
import java.time.LocalDateTime;

public class SessionUtil {

    // Looks up a token in the sessions table, returns the role if valid, or null if invalid/expired
    public static String getRoleFromToken(String token) {
        if (token == null) return null;

        try {
            Connection conn = DbConnection.getConnection();

            PreparedStatement stmt = conn.prepareStatement(
                "SELECT role, expires_at FROM sessions WHERE token = ?"
            );
            stmt.setString(1, token);
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                conn.close();
                return null; // token doesn't exist
            }

            Timestamp expiresAt = rs.getTimestamp("expires_at");
            if (expiresAt.toLocalDateTime().isBefore(LocalDateTime.now())) {
                conn.close();
                return null; // token expired
            }

            String role = rs.getString("role");
            conn.close();
            return role;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Pulls the session_token value out of a raw Cookie header string
    public static String extractTokenFromCookies(String cookieHeader) {
        if (cookieHeader == null) return null;

        String[] cookies = cookieHeader.split(";");
        for (String c : cookies) {
            String[] parts = c.trim().split("=", 2);
            if (parts.length == 2 && parts[0].equals("session_token")) {
                return parts[1];
            }
        }
        return null;
    }

    // Resolves userId from session token
    public static Integer getUserIdFromToken(String token) {
        if (token == null) return null;

        try {
            Connection conn = DbConnection.getConnection();

            PreparedStatement stmt = conn.prepareStatement(
                "SELECT user_id, expires_at FROM sessions WHERE token = ?"
            );
            stmt.setString(1, token);
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                conn.close();
                return null;
            }

            Timestamp expiresAt = rs.getTimestamp("expires_at");
            if (expiresAt.toLocalDateTime().isBefore(LocalDateTime.now())) {
                conn.close();
                return null; // session expired
            }

            int userId = rs.getInt("user_id");
            conn.close();
            return userId;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}