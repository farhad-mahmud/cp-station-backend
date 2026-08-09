package auth;

import Handlers.AbstractHttpHandler;
import com.sun.net.httpserver.HttpExchange;
import config.DbConnection;
import java.sql.*;
import java.util.Map;

public class RegisterHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        String body = readBody(exchange);
        
        @SuppressWarnings("unchecked")
        Map<String, String> req = mapper.readValue(body, Map.class);

        String email = req.get("email");
        String password = req.get("password");

        if (email == null || email.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            sendError(exchange, 400, "Missing email or password");
            return;
        }

        Connection conn = DbConnection.getConnection();

        try {
            // Check if email already exists
            PreparedStatement checkStmt = conn.prepareStatement("SELECT id FROM users WHERE email = ?");
            checkStmt.setString(1, email);
            ResultSet rs = checkStmt.executeQuery();
            if (rs.next()) {
                sendError(exchange, 409, "Email already registered");
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

            sendJSON(exchange, 200, Map.of("success", true, "userId", userId));
        } finally {
            conn.close();
        }
    }
}

