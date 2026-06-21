package auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.time.LocalDateTime;
import java.util.Map;

public class LoginHandler implements HttpHandler {

    private static final ObjectMapper mapper = new ObjectMapper();

    public void handle(HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "http://localhost:3000");
            exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            // Step 1: Parse JSON body directly into a LoginRequest object
            
            LoginRequest req = mapper.readValue(exchange.getRequestBody(), LoginRequest.class);

            if (req.email == null || req.password == null) {
                sendError(exchange, 400, "Missing email or password");
                return;
            }

            // Step 2: Connect to DB
            String dburl = "jdbc:postgresql://localhost:5432/postgres";
            String dbuser = "farhadmahmud";
            String dbpassword = "1234";
            Class.forName("org.postgresql.Driver");
            Connection conn = DriverManager.getConnection(dburl, dbuser, dbpassword);

            PreparedStatement stmt = conn.prepareStatement(
                "SELECT id, password_hash, salt, role FROM users WHERE email = ?"
            );

            stmt.setString(1, req.email);
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                sendError(exchange, 401, "Invalid email or password");
                conn.close();
                return;
            }

            int userId = rs.getInt("id");
            String storedHash = rs.getString("password_hash");
            String salt = rs.getString("salt");
            String role = rs.getString("role");


        // ── TEMPORARY DEBUG LOGGING ──
            System.out.println("Email received: [" + req.email + "]");
System.out.println("Password received: [" + req.password + "]");
System.out.println("Salt from DB: [" + salt + "]");
System.out.println("Stored hash from DB: [" + storedHash + "]");

boolean valid = PasswordUtil.verify(req.password, salt, storedHash);
System.out.println("Verify result: " + valid);
// ── END DEBUG ──


            if (!valid) {
                sendError(exchange, 401, "Invalid email or password");
                conn.close();
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

            conn.close();

            String cookie = String.format(
                "session_token=%s; HttpOnly; Path=/; Max-Age=%d; SameSite=Lax",
                token, 7 * 24 * 60 * 60
            );
            exchange.getResponseHeaders().add("Set-Cookie", cookie);

            // Step 3: Build response with Jackson instead of string concatenation
            Map<String, Object> responseBody = Map.of("role", role, "success", true);
            byte[] bytes = mapper.writeValueAsBytes(responseBody);
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();

        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Server error: " + e.getMessage());
        }
    }

    private String generateToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) {
        try {
            Map<String, String> error = Map.of("error", message);
            byte[] bytes = mapper.writeValueAsBytes(error);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        } catch (Exception ignored) {}
    }
}