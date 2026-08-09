package Handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import config.DbConnection;
import java.sql.*;
import java.util.List;
import java.util.Map;
import Services.TopicService;

public class TopicsCRUDHandler extends AbstractHttpHandler {
    private final TopicService topicService = new TopicService();

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod().toUpperCase();

        if (method.equals("GET")) {
            List<String> topics = topicService.getAllTopics();
            sendJSON(exchange, 200, topics);
            return;
        }

        Connection conn = DbConnection.getConnection();

        try {
            if (method.equals("POST")) {
                String body = readBody(exchange);
                JsonNode json = mapper.readTree(body);
                String name = json.has("name") ? json.get("name").asText() : "";
                int categoryId = json.has("category_id") ? json.get("category_id").asInt() : 0;
                int sortOrder = json.has("sort_order") ? json.get("sort_order").asInt() : 0;
                boolean isInterview = json.has("is_interview") ? json.get("is_interview").asBoolean() : false;

                if (name.isEmpty() || categoryId == 0) {
                    sendError(exchange, 400, "Missing name or category_id");
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
        } finally {
            conn.close();
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
}

