package Handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import config.DbConnection;
import auth.SessionUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class AdminAiSettingsHandler implements HttpHandler {
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
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            String method = exchange.getRequestMethod().toUpperCase();

            if (method.equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // Require admin role
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String token = SessionUtil.extractTokenFromCookies(cookieHeader);
            String role = SessionUtil.getRoleFromToken(token);

            if (role == null || !role.equalsIgnoreCase("admin")) {
                sendError(exchange, 403, "Forbidden: Admin access required.");
                return;
            }

            if (method.equals("GET")) {
                handleGet(exchange);
            } else if (method.equals("POST") || method.equals("PUT")) {
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
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/usage")) {
            Map<String, Object> response = new HashMap<>();
            try (Connection conn = DbConnection.getConnection()) {
                // 1. Fetch Global Summary stats
                Map<String, Long> summary = new HashMap<>();
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COALESCE(SUM(total_tokens), 0) as total, COALESCE(SUM(thoughts_tokens), 0) as thoughts FROM ai_usage_log"
                );
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        summary.put("total_tokens", rs.getLong("total"));
                        summary.put("thoughts_tokens", rs.getLong("thoughts"));
                    }
                }
                response.put("summary", summary);

                // 2. Fetch User Breakdown stats
                List<Map<String, Object>> usersList = new ArrayList<>();
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT u.id, u.email, u.name, " +
                    "COALESCE(SUM(log.total_tokens), 0) as total_tokens, " +
                    "COALESCE(SUM(log.thoughts_tokens), 0) as thoughts_tokens " +
                    "FROM users u " +
                    "LEFT JOIN ai_usage_log log ON u.id = log.user_id " +
                    "GROUP BY u.id, u.email, u.name " +
                    "ORDER BY total_tokens DESC"
                );
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> userData = new HashMap<>();
                        userData.put("id", rs.getInt("id"));
                        userData.put("email", rs.getString("email"));
                        userData.put("name", rs.getString("name"));
                        userData.put("total_tokens", rs.getLong("total_tokens"));
                        userData.put("thoughts_tokens", rs.getLong("thoughts_tokens"));
                        usersList.add(userData);
                    }
                }
                response.put("users", usersList);
            }
            sendJSON(exchange, 200, response);
        } else {
            Map<String, String> settings = new HashMap<>();
            try (Connection conn = DbConnection.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT key, value FROM ai_settings");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    settings.put(rs.getString("key"), rs.getString("value"));
                }
            }
            sendJSON(exchange, 200, settings);
        }
    }

    private void handlePost(HttpExchange exchange) throws Exception {
        String body = readBody(exchange);
        JsonNode json = mapper.readTree(body);

        try (Connection conn = DbConnection.getConnection()) {
            Iterator<Map.Entry<String, JsonNode>> fields = json.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                String val = entry.getValue().asText();

                try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO ai_settings (key, value) VALUES (?, ?) " +
                    "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value"
                )) {
                    stmt.setString(1, key);
                    stmt.setString(2, val);
                    stmt.executeUpdate();
                }
            }
        }

        sendJSON(exchange, 200, Map.of("success", true));
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
