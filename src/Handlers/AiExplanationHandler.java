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
import java.util.HashMap;
import java.util.Map;

public class AiExplanationHandler implements HttpHandler {
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

            if (method.equals("GET")) {
                handleGet(exchange);
            } else if (method.equals("POST")) {
                handlePost(exchange);
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Internal server error");
        }
    }

    private void handleGet(HttpExchange exchange) throws Exception {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);

        String type = params.get("type");
        String idStr = params.get("id");

        if (type == null || idStr == null) {
            sendError(exchange, 400, "Missing required parameters: type and id");
            return;
        }

        // For solutions, check authentication even for GET requests
        if ("solution".equals(type)) {
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String token = SessionUtil.extractTokenFromCookies(cookieHeader);
            Integer userId = SessionUtil.getUserIdFromToken(token);
            if (userId == null) {
                sendError(exchange, 401, "Unauthorized: Logged in session required.");
                return;
            }
        }

        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            sendError(exchange, 400, "Invalid id parameter");
            return;
        }

        try (Connection conn = DbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT content FROM ai_explanations WHERE resource_type = ? AND resource_id = ?"
             )) {
            stmt.setString(1, type);
            stmt.setInt(2, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String content = rs.getString("content");
                    sendJSON(exchange, 200, Map.of("cached", true, "content", content));
                } else {
                    sendJSON(exchange, 200, Map.of("cached", false, "can_generate", true));
                }
            }
        }
    }

    private void handlePost(HttpExchange exchange) throws Exception {
        // Parse Request Body first to check resource type
        String body = readBody(exchange);
        JsonNode json = mapper.readTree(body);
        String type = json.path("type").asText("");
        int id = json.path("id").asInt(0);
        boolean regenerate = json.path("regenerate").asBoolean(false);

        if (type.isEmpty() || id == 0) {
            sendError(exchange, 400, "Missing required fields: type and id");
            return;
        }

        if (!type.equals("solution") && !type.equals("topic") && !type.equals("subtopic")) {
            sendError(exchange, 400, "Invalid resource type. Allowed values: solution, topic, subtopic");
            return;
        }

        // Verification of session / user
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String token = SessionUtil.extractTokenFromCookies(cookieHeader);
        Integer userId = SessionUtil.getUserIdFromToken(token);
        String role = SessionUtil.getRoleFromToken(token);

        // Check cache
        String existingContent = null;
        try (Connection conn = DbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT content FROM ai_explanations WHERE resource_type = ? AND resource_id = ?"
             )) {
            stmt.setString(1, type);
            stmt.setInt(2, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    existingContent = rs.getString("content");
                }
            }
        }

        // If explanation is cached and they do not request regeneration, return cached
        if (existingContent != null && !regenerate) {
            sendJSON(exchange, 200, Map.of("cached", true, "content", existingContent));
            return;
        }

        // If user is not logged in
        if (userId == null) {
            if ("solution".equals(type)) {
                sendError(exchange, 401, "Unauthorized: Logged in session required.");
                return;
            }
            if (existingContent != null && regenerate) {
                sendError(exchange, 401, "Unauthorized: Logged in session required.");
                return;
            }
        } else {
            // Overwrite or regenerate requires admin role for logged in users
            if (existingContent != null && regenerate && !"admin".equalsIgnoreCase(role)) {
                sendError(exchange, 403, "Forbidden: Only admin users can regenerate existing cached explanations.");
                return;
            }
        }

        // Generate explanation via Gemini
        String explanation;
        try (Connection conn = DbConnection.getConnection()) {
            if (type.equals("solution")) {
                String title = "";
                String code = "";
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT title, solution_code FROM resources WHERE id = ?"
                )) {
                    stmt.setInt(1, id);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            title = rs.getString("title");
                            code = rs.getString("solution_code");
                        } else {
                            sendError(exchange, 404, "Resource not found");
                            return;
                        }
                    }
                }

                if (code == null || code.trim().isEmpty()) {
                    sendError(exchange, 400, "No solution code is submitted for this problem yet.");
                    return;
                }

                explanation = GeminiService.generateSolutionExplanation(title, code, userId, id);

            } else if (type.equals("topic")) {
                String topicName = "";
                String categoryName = "";
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT t.name as topic_name, c.category_name as category_name " +
                    "FROM topics t " +
                    "LEFT JOIN categories c ON t.category_id = c.id " +
                    "WHERE t.id = ?"
                )) {
                    stmt.setInt(1, id);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            topicName = rs.getString("topic_name");
                            categoryName = rs.getString("category_name");
                        } else {
                            sendError(exchange, 404, "Topic not found");
                            return;
                        }
                    }
                }

                explanation = GeminiService.generateTopicExplanation(topicName, categoryName, "topic", userId, id);

            } else { // subtopic
                String subtopicName = "";
                String topicName = "";
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT s.name as subtopic_name, t.name as topic_name " +
                    "FROM subtopics s " +
                    "LEFT JOIN topics t ON s.topic_id = t.id " +
                    "WHERE s.id = ?"
                )) {
                    stmt.setInt(1, id);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            subtopicName = rs.getString("subtopic_name");
                            topicName = rs.getString("topic_name");
                        } else {
                            sendError(exchange, 404, "Subtopic not found");
                            return;
                        }
                    }
                }

                explanation = GeminiService.generateTopicExplanation(subtopicName, topicName, "subtopic", userId, id);
            }
        }

        // Cache explanation
        try (Connection conn = DbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO ai_explanations (resource_type, resource_id, content, updated_at) " +
                 "VALUES (?, ?, ?, now()) " +
                 "ON CONFLICT (resource_type, resource_id) " +
                 "DO UPDATE SET content = EXCLUDED.content, updated_at = now()"
             )) {
            stmt.setString(1, type);
            stmt.setInt(2, id);
            stmt.setString(3, explanation);
            stmt.executeUpdate();
        }

        sendJSON(exchange, 200, Map.of("cached", true, "content", explanation));
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
