package Handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import config.DbConnection;
import auth.SessionUtil;
import Services.GeminiService;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AiFollowupHandler implements HttpHandler {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) {
        try {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin == null || origin.isEmpty()) {
                origin = "https://cp-station.vercel.app";
            }
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            String method = exchange.getRequestMethod().toUpperCase();

            if (method.equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // Require logged in user for all operations
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String token = SessionUtil.extractTokenFromCookies(cookieHeader);
            Integer userId = SessionUtil.getUserIdFromToken(token);

            if (userId == null) {
                sendError(exchange, 401, "Unauthorized: Logged in session required.");
                return;
            }

            if (method.equals("GET")) {
                handleGet(exchange, userId);
            } else if (method.equals("POST")) {
                handlePost(exchange, userId);
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Internal server error");
        }
    }

    private void handleGet(HttpExchange exchange, int userId) throws Exception {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);

        String type = params.get("type");
        String idStr = params.get("id");

        if (type == null || idStr == null) {
            sendError(exchange, 400, "Missing required parameters: type and id");
            return;
        }

        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            sendError(exchange, 400, "Invalid id parameter");
            return;
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        try (Connection conn = DbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT role, content, created_at FROM ai_followup_messages " +
                 "WHERE user_id = ? AND resource_type = ? AND resource_id = ? " +
                 "ORDER BY id ASC"
             )) {
            stmt.setInt(1, userId);
            stmt.setString(2, type);
            stmt.setInt(3, id);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(Map.of(
                        "role", rs.getString("role"),
                        "content", rs.getString("content"),
                        "created_at", rs.getTimestamp("created_at").toString()
                    ));
                }
            }
        }

        sendJSON(exchange, 200, messages);
    }

    private void handlePost(HttpExchange exchange, int userId) throws Exception {
        String body = readBody(exchange);
        JsonNode json = mapper.readTree(body);

        String type = json.path("type").asText("");
        int id = json.path("id").asInt(0);
        String message = json.path("message").asText("");

        if (type.isEmpty() || id == 0 || message.trim().isEmpty()) {
            sendError(exchange, 400, "Missing required fields: type, id, or message");
            return;
        }

        if (!type.equals("solution") && !type.equals("topic") && !type.equals("subtopic")) {
            sendError(exchange, 400, "Invalid resource type. Allowed values: solution, topic, subtopic");
            return;
        }

        // 1. Enforce Daily limits
        int dailyLimit = getDailyLimit();
        int userSentCount = 0;

        try (Connection conn = DbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM ai_followup_messages " +
                 "WHERE user_id = ? AND role = 'user' AND created_at >= CURRENT_DATE"
             )) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    userSentCount = rs.getInt(1);
                }
            }
        }

        if (userSentCount >= dailyLimit) {
            sendError(exchange, 429, "Daily limit of " + dailyLimit + " follow-up messages reached.");
            return;
        }

        // 2. Fetch Cached Explanation
        String explanationContent = null;
        try (Connection conn = DbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT content FROM ai_explanations WHERE resource_type = ? AND resource_id = ?"
             )) {
            stmt.setString(1, type);
            stmt.setInt(2, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    explanationContent = rs.getString("content");
                }
            }
        }

        if (explanationContent == null) {
            sendError(exchange, 400, "No base explanation found. Please generate the AI explanation first.");
            return;
        }

        // 3. Fetch past history
        List<Map<String, String>> chatHistory = new ArrayList<>();
        try (Connection conn = DbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT role, content FROM ai_followup_messages " +
                 "WHERE user_id = ? AND resource_type = ? AND resource_id = ? " +
                 "ORDER BY id ASC"
             )) {
            stmt.setInt(1, userId);
            stmt.setString(2, type);
            stmt.setInt(3, id);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    chatHistory.add(Map.of(
                        "role", rs.getString("role"),
                        "content", rs.getString("content")
                    ));
                }
            }
        }

        // 4. Generate Response via Gemini
        String promptMessage = message + " - simply and shortly";
        String aiResponse = GeminiService.generateFollowupResponse(
            explanationContent,
            chatHistory,
            promptMessage,
            userId,
            type,
            id
        );

        // 5. Store user question and ai response
        try (Connection conn = DbConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // User Message
                try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO ai_followup_messages (user_id, resource_type, resource_id, role, content, created_at) VALUES (?, ?, ?, 'user', ?, now())"
                )) {
                    stmt.setInt(1, userId);
                    stmt.setString(2, type);
                    stmt.setInt(3, id);
                    stmt.setString(4, message);
                    stmt.executeUpdate();
                }

                // Model Message
                try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO ai_followup_messages (user_id, resource_type, resource_id, role, content, created_at) VALUES (?, ?, ?, 'model', ?, now())"
                )) {
                    stmt.setInt(1, userId);
                    stmt.setString(2, type);
                    stmt.setInt(3, id);
                    stmt.setString(4, aiResponse);
                    stmt.executeUpdate();
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }

        sendJSON(exchange, 200, Map.of("role", "model", "content", aiResponse));
    }

    private int getDailyLimit() {
        try (Connection conn = DbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT value FROM ai_settings WHERE key = 'daily_message_limit'"
             )) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Integer.parseInt(rs.getString("value"));
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: failed to read daily limit from settings: " + e.getMessage());
        }
        return 10; // default backup limit
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                params.put(pair[0], java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            } else if (pair.length == 1) {
                params.put(pair[0], "");
            }
        }
        return params;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void sendJSON(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(data);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String msg) {
        try {
            sendJSON(exchange, status, Map.of("error", msg));
        } catch (Exception ignored) {}
    }
}
