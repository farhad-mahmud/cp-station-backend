package auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import config.DbConnection;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Map;

public class RegisterHandler implements HttpHandler {
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
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

            byte[] requestBodyBytes = exchange.getRequestBody().readAllBytes();
            String body = new String(requestBodyBytes, StandardCharsets.UTF_8);
            
            @SuppressWarnings("unchecked")
            Map<String, String> req = mapper.readValue(body, Map.class);

            String email = req.get("email");
            String password = req.get("password");

            if (email == null || email.trim().isEmpty() || password == null || password.trim().isEmpty()) {
                sendError(exchange, 400, "Missing email or password");
                return;
            }

            Connection conn = DbConnection.getConnection();

            // Check if email already exists
            PreparedStatement checkStmt = conn.prepareStatement("SELECT id FROM users WHERE email = ?");
            checkStmt.setString(1, email);
            ResultSet rs = checkStmt.executeQuery();
            if (rs.next()) {
                sendError(exchange, 409, "Email already registered");
                conn.close();
                return;
            }

            // Create user
            String salt = PasswordUtil.generateSalt();
            String passwordHash = PasswordUtil.hash(password, salt);
            String role = "user"; // default role

            PreparedStatement insertStmt = conn.prepareStatement(
                "INSERT INTO users (email, password_hash, salt, role) VALUES (?, ?, ?, ?) RETURNING id"
            );
            insertStmt.setString(1, email);
            insertStmt.setString(2, passwordHash);
            insertStmt.setString(3, salt);
            insertStmt.setString(4, role);
            ResultSet insertRs = insertStmt.executeQuery();
            insertRs.next();
            int userId = insertRs.getInt("id");

            conn.close();

            sendJSON(exchange, 200, Map.of("success", true, "userId", userId));

        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Server error: " + e.getMessage());
        }
    }

    private void sendJSON(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(data);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) {
        try {
            Map<String, String> error = Map.of("error", message);
            sendJSON(exchange, statusCode, error);
        } catch (Exception ignored) {}
    }
}
