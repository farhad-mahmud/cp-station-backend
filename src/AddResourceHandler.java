import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import Handlers.AbstractHttpHandler;
import config.DbConnection;

import java.sql.*;

public class AddResourceHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJSON(exchange, 405, "{\"status\":\"error\",\"message\":\"Only POST allowed\"}");
            return;
        }

        String body = readBody(exchange);
        JsonNode json = mapper.readTree(body);

        String title = getText(json, "title");
        String url = getText(json, "url");
        String type = getText(json, "type");
        int topicId = json.has("topicId") ? json.get("topicId").asInt() : 0;
        int subtopicId = json.has("subtopicId") ? json.get("subtopicId").asInt() : 0;

        if (isEmpty(title) || isEmpty(url) || isEmpty(type) || topicId == 0) {
            sendJSON(exchange, 400, "{\"status\":\"error\",\"message\":\"Missing fields\"}");
            return;
        }

        Connection conn = DbConnection.getConnection();

        String sql = """
            INSERT INTO resources(title, url, type, topic_id, subtopic_id)
            VALUES (?, ?, ?, ?, ?)
            RETURNING id
        """;

        PreparedStatement stmt = conn.prepareStatement(sql);

        stmt.setString(1, title);
        stmt.setString(2, url);
        stmt.setString(3, type);
        stmt.setInt(4, topicId);

        if (subtopicId == 0) {
            stmt.setInt(5, topicId);
        } else {
            stmt.setNull(5, Types.INTEGER);
        }

        ResultSet rs = stmt.executeQuery();
        rs.next();

        int id = rs.getInt("id");
        conn.close();

        sendJSON(exchange, 200, "{\"status\":\"success\",\"resourceId\":" + id + "}");
    }

    private String getText(JsonNode json, String key) {
        JsonNode node = json.get(key);
        return (node == null || node.isNull()) ? "" : node.asText();
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}