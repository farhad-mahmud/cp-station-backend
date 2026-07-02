package Handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import config.DbConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Map;

public class ResourcesCRUDHandler implements HttpHandler {
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
            exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            String method = exchange.getRequestMethod().toUpperCase();

            if (method.equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            Connection conn = DbConnection.getConnection();

            if (method.equals("POST")) {
                String body = readBody(exchange);
                JsonNode json = mapper.readTree(body);

                String title = json.has("title") ? json.get("title").asText() : "";
                String url = json.has("url") ? json.get("url").asText() : "";
                String type = json.has("type") ? json.get("type").asText() : "";
                
                int topicId = 0;
                if (json.has("topic_id")) {
                    topicId = json.get("topic_id").asInt();
                } else if (json.has("topicId")) {
                    topicId = json.get("topicId").asInt();
                }

                Integer subtopicId = null;
                if (json.has("subtopic_id") && !json.get("subtopic_id").isNull()) {
                    subtopicId = json.get("subtopic_id").asInt();
                } else if (json.has("subtopicId") && !json.get("subtopicId").isNull()) {
                    subtopicId = json.get("subtopicId").asInt();
                }

                if (title.isEmpty() || url.isEmpty() || type.isEmpty() || topicId == 0) {
                    sendError(exchange, 400, "Missing required fields");
                    conn.close();
                    return;
                }

                String sql = "INSERT INTO resources (title, url, type, topic_id, subtopic_id) VALUES (?, ?, ?, ?, ?) RETURNING id";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setString(1, title);
                stmt.setString(2, url);
                stmt.setString(3, type);
                stmt.setInt(4, topicId);
                if (subtopicId == null || subtopicId == 0) {
                    stmt.setNull(5, Types.INTEGER);
                } else {
                    stmt.setInt(5, subtopicId);
                }

                ResultSet rs = stmt.executeQuery();
                rs.next();
                int id = rs.getInt("id");

                sendJSON(exchange, 200, Map.of("success", true, "id", id));
            } else if (method.equals("PUT")) {
                int id = getParamId(exchange);
                if (id == -1) {
                    sendError(exchange, 400, "Missing resource id parameter");
                    conn.close();
                    return;
                }

                String body = readBody(exchange);
                JsonNode json = mapper.readTree(body);

                String title = json.has("title") ? json.get("title").asText() : "";
                String url = json.has("url") ? json.get("url").asText() : "";
                String type = json.has("type") ? json.get("type").asText() : "";
                
                int topicId = 0;
                if (json.has("topic_id")) {
                    topicId = json.get("topic_id").asInt();
                } else if (json.has("topicId")) {
                    topicId = json.get("topicId").asInt();
                }

                Integer subtopicId = null;
                if (json.has("subtopic_id") && !json.get("subtopic_id").isNull()) {
                    subtopicId = json.get("subtopic_id").asInt();
                } else if (json.has("subtopicId") && !json.get("subtopicId").isNull()) {
                    subtopicId = json.get("subtopicId").asInt();
                }

                if (title.isEmpty() || url.isEmpty() || type.isEmpty() || topicId == 0) {
                    sendError(exchange, 400, "Missing required fields");
                    conn.close();
                    return;
                }

                String sql = "UPDATE resources SET title = ?, url = ?, type = ?, topic_id = ?, subtopic_id = ? WHERE id = ?";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setString(1, title);
                stmt.setString(2, url);
                stmt.setString(3, type);
                stmt.setInt(4, topicId);
                if (subtopicId == null || subtopicId == 0) {
                    stmt.setNull(5, Types.INTEGER);
                } else {
                    stmt.setInt(5, subtopicId);
                }
                stmt.setInt(6, id);
                stmt.executeUpdate();

                sendJSON(exchange, 200, Map.of("success", true));
            } else if (method.equals("DELETE")) {
                int id = getParamId(exchange);
                if (id == -1) {
                    sendError(exchange, 400, "Missing resource id parameter");
                    conn.close();
                    return;
                }

                String sql = "DELETE FROM resources WHERE id = ?";
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
            sendError(exchange, 500, "Server error: " + e.getMessage());
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
