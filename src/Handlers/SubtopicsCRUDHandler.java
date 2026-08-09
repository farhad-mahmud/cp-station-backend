package Handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import config.DbConnection;
import java.sql.*;
import java.util.Map;

public class SubtopicsCRUDHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod().toUpperCase();
        Connection conn = DbConnection.getConnection();

        try {
            if (method.equals("POST")) {
                String body = readBody(exchange);
                JsonNode json = mapper.readTree(body);
                String name = json.has("name") ? json.get("name").asText() : "";
                int topicId = json.has("topic_id") ? json.get("topic_id").asInt() : 0;
                int sortOrder = json.has("sort_order") ? json.get("sort_order").asInt() : 0;

                if (name.isEmpty() || topicId == 0) {
                    sendError(exchange, 400, "Missing name or topic_id");
                    return;
                }

                String sql = "INSERT INTO subtopics (name, topic_id, sort_order) VALUES (?, ?, ?) RETURNING id";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setString(1, name);
                stmt.setInt(2, topicId);
                stmt.setInt(3, sortOrder);
                ResultSet rs = stmt.executeQuery();
                rs.next();
                int id = rs.getInt("id");

                sendJSON(exchange, 200, Map.of("success", true, "id", id));
            } else if (method.equals("PUT")) {
                int id = getParamId(exchange);
                if (id == -1) {
                    sendError(exchange, 400, "Missing subtopic id parameter");
                    return;
                }

                String body = readBody(exchange);
                JsonNode json = mapper.readTree(body);
                String name = json.has("name") ? json.get("name").asText() : "";
                int topicId = json.has("topic_id") ? json.get("topic_id").asInt() : 0;
                int sortOrder = json.has("sort_order") ? json.get("sort_order").asInt() : 0;

                if (name.isEmpty() || topicId == 0) {
                    sendError(exchange, 400, "Missing name or topic_id");
                    return;
                }

                String sql = "UPDATE subtopics SET name = ?, topic_id = ?, sort_order = ? WHERE id = ?";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setString(1, name);
                stmt.setInt(2, topicId);
                stmt.setInt(3, sortOrder);
                stmt.setInt(4, id);
                stmt.executeUpdate();

                sendJSON(exchange, 200, Map.of("success", true));
            } else if (method.equals("DELETE")) {
                int id = getParamId(exchange);
                if (id == -1) {
                    sendError(exchange, 400, "Missing subtopic id parameter");
                    return;
                }

                String sql = "DELETE FROM subtopics WHERE id = ?";
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

