package Handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import config.DbConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;
import java.util.Map;
import Services.TopicService;

public class TopicsCRUDHandler implements HttpHandler {
    private static final String ALLOWED_ORIGIN = "https://cp-station.vercel.app";
    private final ObjectMapper mapper = new ObjectMapper();
    private final TopicService topicService = new TopicService();

    @Override
    public void handle(HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
            exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            String method = exchange.getRequestMethod().toUpperCase();

            if (method.equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (method.equals("GET")) {
                List<String> topics = topicService.getAllTopics();
                byte[] bytes = mapper.writeValueAsBytes(topics);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }

            Connection conn = DbConnection.getConnection();

            if (method.equals("POST")) {
                String body = readBody(exchange);
                JsonNode json = mapper.readTree(body);
                String name = json.has("name") ? json.get("name").asText() : "";
                int categoryId = json.has("category_id") ? json.get("category_id").asInt() : 0;
                int sortOrder = json.has("sort_order") ? json.get("sort_order").asInt() : 0;
                boolean isInterview = json.has("is_interview") ? json.get("is_interview").asBoolean() : false;

                if (name.isEmpty() || categoryId == 0) {
                    sendError(exchange, 400, "Missing name or category_id");
                    conn.close();
                    return;
                }

                String sql = "INSERT INTO topics (name, category_id, sort_order, is_interview) VALUES (?, ?, ?, ?) RETURNING id";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setString(1, name);
                stmt.setInt(2, categoryId);
                stmt.setInt(3, sortOrder);
                stmt.setBoolean(4, isInterview);
                ResultSet rs = stmt.executeQuery();
                rs.next();
                int id = rs.getInt("id");

                sendJSON(exchange, 200, Map.of("success", true, "id", id));
            } else if (method.equals("PUT")) {
                int id = getParamId(exchange);
                if (id == -1) {
                    sendError(exchange, 400, "Missing topic id parameter");
                    conn.close();
                    return;
                }

                String body = readBody(exchange);
                JsonNode json = mapper.readTree(body);
                String name = json.has("name") ? json.get("name").asText() : "";
                int categoryId = json.has("category_id") ? json.get("category_id").asInt() : 0;
                int sortOrder = json.has("sort_order") ? json.get("sort_order").asInt() : 0;
                boolean isInterview = json.has("is_interview") ? json.get("is_interview").asBoolean() : false;

                if (name.isEmpty() || categoryId == 0) {
                    sendError(exchange, 400, "Missing name or category_id");
                    conn.close();
                    return;
                }

                String sql = "UPDATE topics SET name = ?, category_id = ?, sort_order = ?, is_interview = ? WHERE id = ?";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setString(1, name);
                stmt.setInt(2, categoryId);
                stmt.setInt(3, sortOrder);
                stmt.setBoolean(4, isInterview);
                stmt.setInt(5, id);
                stmt.executeUpdate();

                sendJSON(exchange, 200, Map.of("success", true));
            } else if (method.equals("DELETE")) {
                int id = getParamId(exchange);
                if (id == -1) {
                    sendError(exchange, 400, "Missing topic id parameter");
                    conn.close();
                    return;
                }

                String sql = "DELETE FROM topics WHERE id = ?";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setInt(1, id);
                stmt.executeUpdate();

                sendJSON(exchange, 200, Map.of("success", true));
            } else {
                sendError(exchange, 405, "Method not allowed");
            }

            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Internal server error");
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int getParamId(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2 && pair[0].equals("id")) {
                    return Integer.parseInt(pair[1]);
                }
            }
        }
        return -1;
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
