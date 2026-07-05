package Handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import auth.SessionUtil;
import config.DbConnection;

public class UserSuggestionsHandler implements HttpHandler {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String ALLOWED_ORIGIN = "https://cp-station.vercel.app";

    @Override
    public void handle(HttpExchange exchange) {
        try {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin == null || origin.isEmpty()) {
                origin = ALLOWED_ORIGIN;
            }
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            String method = exchange.getRequestMethod().toUpperCase();

            if (method.equals("OPTIONS")) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // Extract session authentication
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String token = SessionUtil.extractTokenFromCookies(cookieHeader);
            Integer userId = SessionUtil.getUserIdFromToken(token);
            String role = SessionUtil.getRoleFromToken(token);

            if (userId == null) {
                sendError(exchange, 401, "Unauthorized: Please log in to proceed.");
                return;
            }

            String path = exchange.getRequestURI().getPath();

            if (path.equals("/my-suggestions")) {
                if (method.equals("GET")) {
                    handleGetMySuggestions(exchange, userId);
                } else {
                    sendError(exchange, 405, "Method Not Allowed");
                }
            } else if (path.equals("/user-suggestions")) {
                if (method.equals("GET")) {
                    // Admin only
                    if (role == null || !role.equalsIgnoreCase("admin")) {
                        sendError(exchange, 403, "Forbidden: Admin access required.");
                        return;
                    }
                    handleGetAllSuggestions(exchange);
                } else if (method.equals("POST")) {
                    handleCreateSuggestion(exchange, userId);
                } else if (method.equals("PUT")) {
                    // Admin only
                    if (role == null || !role.equalsIgnoreCase("admin")) {
                        sendError(exchange, 403, "Forbidden: Admin access required.");
                        return;
                    }
                    handleUpdateSuggestionStatus(exchange);
                } else {
                    sendError(exchange, 405, "Method Not Allowed");
                }
            } else {
                sendError(exchange, 404, "Not Found");
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Server Error: " + e.getMessage());
        }
    }

    private void handleGetMySuggestions(HttpExchange exchange, int userId) throws Exception {
        Connection conn = DbConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(
            "SELECT id, type, title, url, topic_id, status, created_at FROM user_suggestions WHERE user_id = ? ORDER BY created_at DESC"
        );
        stmt.setInt(1, userId);
        ResultSet rs = stmt.executeQuery();

        List<Map<String, Object>> suggestions = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", rs.getInt("id"));
            item.put("type", rs.getString("type"));
            item.put("title", rs.getString("title"));
            item.put("url", rs.getString("url"));
            int topicId = rs.getInt("topic_id");
            item.put("topic_id", rs.wasNull() ? null : topicId);
            item.put("status", rs.getString("status"));
            item.put("created_at", rs.getTimestamp("created_at").toString());
            suggestions.add(item);
        }
        conn.close();

        sendJSON(exchange, 200, suggestions);
    }

    private void handleGetAllSuggestions(HttpExchange exchange) throws Exception {
        Connection conn = DbConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(
            "SELECT s.id, s.type, s.title, s.url, s.topic_id, s.status, s.created_at, s.user_id, u.email as user_email, u.name as user_name " +
            "FROM user_suggestions s " +
            "LEFT JOIN users u ON s.user_id = u.id " +
            "ORDER BY s.created_at DESC"
        );
        ResultSet rs = stmt.executeQuery();

        List<Map<String, Object>> suggestions = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", rs.getInt("id"));
            item.put("type", rs.getString("type"));
            item.put("title", rs.getString("title"));
            item.put("url", rs.getString("url"));
            int topicId = rs.getInt("topic_id");
            item.put("topic_id", rs.wasNull() ? null : topicId);
            item.put("status", rs.getString("status"));
            item.put("created_at", rs.getTimestamp("created_at").toString());
            item.put("user_id", rs.getInt("user_id"));
            item.put("user_email", rs.getString("user_email"));
            item.put("user_name", rs.getString("user_name"));
            suggestions.add(item);
        }
        conn.close();

        sendJSON(exchange, 200, suggestions);
    }

    private void handleCreateSuggestion(HttpExchange exchange, int userId) throws Exception {
        String body = readBody(exchange);
        JsonNode json = mapper.readTree(body);

        String type = json.has("type") ? json.get("type").asText() : "";
        String title = json.has("title") ? json.get("title").asText() : "";
        String url = json.has("url") ? json.get("url").asText() : "";
        Integer topicId = null;
        if (json.has("topic_id") && !json.get("topic_id").isNull()) {
            topicId = json.get("topic_id").asInt();
        }

        if (type.isEmpty() || title.isEmpty() || url.isEmpty()) {
            sendError(exchange, 400, "Missing required fields: type, title, or url");
            return;
        }

        Connection conn = DbConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO user_suggestions (user_id, type, title, url, topic_id, status) VALUES (?, ?, ?, ?, ?, 'pending') RETURNING id"
        );
        stmt.setInt(1, userId);
        stmt.setString(2, type);
        stmt.setString(3, title);
        stmt.setString(4, url);
        if (topicId == null) {
            stmt.setNull(5, Types.INTEGER);
        } else {
            stmt.setInt(5, topicId);
        }

        ResultSet rs = stmt.executeQuery();
        rs.next();
        int generatedId = rs.getInt("id");
        conn.close();

        sendJSON(exchange, 201, Map.of("success", true, "id", generatedId, "message", "Suggestion submitted successfully!"));
    }

    private void handleUpdateSuggestionStatus(HttpExchange exchange) throws Exception {
        String body = readBody(exchange);
        JsonNode json = mapper.readTree(body);

        int id = json.has("id") ? json.get("id").asInt() : 0;
        String status = json.has("status") ? json.get("status").asText() : "";

        if (id == 0 || status.isEmpty()) {
            sendError(exchange, 400, "Missing required fields: id or status");
            return;
        }

        if (!status.equalsIgnoreCase("approved") && !status.equalsIgnoreCase("rejected")) {
            sendError(exchange, 400, "Invalid status. Must be 'approved' or 'rejected'.");
            return;
        }

        Connection conn = DbConnection.getConnection();
        
        // Fetch existing status and user_id first
        PreparedStatement checkStmt = conn.prepareStatement(
            "SELECT user_id, status FROM user_suggestions WHERE id = ?"
        );
        checkStmt.setInt(1, id);
        ResultSet checkRs = checkStmt.executeQuery();
        
        if (!checkRs.next()) {
            conn.close();
            sendError(exchange, 404, "Suggestion not found.");
            return;
        }

        int userId = checkRs.getInt("user_id");
        String currentStatus = checkRs.getString("status");

        if (currentStatus.equalsIgnoreCase("approved")) {
            conn.close();
            sendError(exchange, 400, "Suggestion has already been approved.");
            return;
        }

        // Update suggestions status
        PreparedStatement updateStmt = conn.prepareStatement(
            "UPDATE user_suggestions SET status = ? WHERE id = ?"
        );
        updateStmt.setString(1, status.toLowerCase());
        updateStmt.setInt(2, id);
        updateStmt.executeUpdate();

        // If approved, increment solutions_contributed for user
        if (status.equalsIgnoreCase("approved")) {
            PreparedStatement userStmt = conn.prepareStatement(
                "UPDATE users SET solutions_contributed = solutions_contributed + 1 WHERE id = ?"
            );
            userStmt.setInt(1, userId);
            userStmt.executeUpdate();
        }

        conn.close();
        sendJSON(exchange, 200, Map.of("success", true, "message", "Suggestion status updated to " + status));
    }

    private String readBody(HttpExchange exchange) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
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
