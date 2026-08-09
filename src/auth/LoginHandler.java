package auth;

import Handlers.AbstractHttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.sql.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.time.LocalDateTime;
import java.util.Map;
import config.DbConnection;

public class LoginHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        LoginRequest req = mapper.readValue(exchange.getRequestBody(), LoginRequest.class);

        if (req.email == null || req.password == null) {
            sendError(exchange, 400, "Missing email or password");
            return;
        }

        Class.forName("org.postgresql.Driver");
        Connection conn = DbConnection.getConnection();

        try {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT id, password_hash, salt, role FROM users WHERE email = ?"
            );
            stmt.setString(1, req.email);
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                sendError(exchange, 401, "Invalid email or password");
                return;
            }

            int userId = rs.getInt("id");
            String storedHash = rs.getString("password_hash");
            String salt = rs.getString("salt");
            String role = rs.getString("role");

            boolean valid = PasswordUtil.verify(req.password, salt, storedHash);

            if (!valid) {
                sendError(exchange, 401, "Invalid email or password");
                return;
            }

            String token = generateToken();
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(7);

            PreparedStatement sessionStmt = conn.prepareStatement(
                "INSERT INTO sessions (token, user_id, role, expires_at) VALUES (?, ?, ?, ?)"
            );
            sessionStmt.setString(1, token);
            sessionStmt.setInt(2, userId);
            sessionStmt.setString(3, role);
            sessionStmt.setTimestamp(4, Timestamp.valueOf(expiresAt));
            sessionStmt.executeUpdate();

            String cookie = String.format(
                "session_token=%s; HttpOnly; Path=/; Max-Age=%d; SameSite=None; Secure",
                token, 7 * 24 * 60 * 60
            );
            exchange.getResponseHeaders().add("Set-Cookie", cookie);

            sendJSON(exchange, 200, Map.of("role", role, "success", true));
        } finally {
            conn.close();
        }
    }

    private String generateToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}