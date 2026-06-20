package auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Base64;

public class LoginHandler implements HttpHandler {

    public void handle(HttpExchange exchange) {
        try {
            
            // CORS setup — must allow credentials since we're using cookies
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "http://localhost:3000");
            exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            // Browsers send a preflight OPTIONS request before POST — must handle it
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

            // Step 1: Read the request body (explained above)
            InputStream is = exchange.getRequestBody();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("Login body: " + body);

            String email = extract(body, "email");
            String password = extract(body, "password");

            if (email == null || password == null) {
                sendError(exchange, 400, "Missing email or password");
                return;
            }

            // Step 2: Connect to DB
            String dburl = "jdbc:postgresql://localhost:5432/postgres";
            String dbuser = "farhadmahmud";
            String dbpassword = "1234";
            Class.forName("org.postgresql.Driver");
            Connection conn = DriverManager.getConnection(dburl, dbuser, dbpassword);

            // Step 3: Look up user by email
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT id, password_hash, salt, role FROM users WHERE email = ?"
            );
            stmt.setString(1, email);
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                // Step 4: No such user — generic error, don't reveal why
                sendError(exchange, 401, "Invalid email or password");
                conn.close();
                return;
            }

            int userId = rs.getInt("id");
            String storedHash = rs.getString("password_hash");
            String salt = rs.getString("salt");
            String role = rs.getString("role");

            // Step 5: Check password
            boolean valid = PasswordUtil.verify(password, salt, storedHash);

            if (!valid) {
                // Step 6: Wrong password — same generic error
                sendError(exchange, 401, "Invalid email or password");
                conn.close();
                return;
            }

            // Step 7: Correct password — create a session
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

            // Set the HTTP-only cookie holding the token
            String cookie = String.format(
                "session_token=%s; HttpOnly; Path=/; Max-Age=%d; SameSite=Lax",
                token, 7 * 24 * 60 * 60
            );
            exchange.getResponseHeaders().add("Set-Cookie", cookie);

            // Step 8: Respond with just the role
            String response = "{\"role\":\"" + role + "\",\"success\":true}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
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

    // Very simple JSON value extractor for flat {"key":"value"} bodies
    private String extract(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) {
        try {
            String error = "{\"error\":\"" + message + "\"}";
            byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        } catch (Exception ignored) {}
    }
}