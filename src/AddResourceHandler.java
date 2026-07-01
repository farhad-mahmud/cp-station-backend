import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import config.DbConnection;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class AddResourceHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) {

        try {

            // CORS .. response headers.. 
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            // only POST
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                send(exchange, 405, "{\"status\":\"error\",\"message\":\"Only POST allowed\"}");
                return;
            }

            // read body
            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
          
            // JSON parsing (SAFE)
            JsonNode json = mapper.readTree(body);

            String title = getText(json, "title");
            String url = getText(json, "url");
            String type = getText(json, "type");
            int topicId = json.get("topicId").asInt();
            int subtopicId = json.get("subtopicId").asInt();


            // validation
            if (isEmpty(title) || isEmpty(url) || isEmpty(type) || topicId == 0 ) {
                send(exchange, 400, "{\"status\":\"error\",\"message\":\"Missing fields\"}");
                return;
            }

            // DB
            Connection conn =
            DbConnection.getConnection();
            
            String sql = """
                INSERT INTO resources(title, url, type, topic_id, subtopic_id)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
            """;

            PreparedStatement stmt = conn.prepareStatement(sql);

            stmt.setString(1, title);
            stmt.setString(2, url);
            stmt.setString(3, type);
            stmt.setInt(4,topicId);
    
            if (subtopicId == 0) {
                stmt.setInt(5, topicId);
            } else {
                stmt.setNull(5, Types.INTEGER);
            }

            ResultSet rs = stmt.executeQuery();
            rs.next();

            int id = rs.getInt("id");

            send(exchange, 200,
                    "{\"status\":\"success\",\"resourceId\":" + id + "}");

            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
            try {
                send(exchange, 500,
                        "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
            } catch (Exception ignored) {}
        }
    }

    //helper: safe JSON field read
    
    private String getText(JsonNode json, String key) {
        JsonNode node = json.get(key);
        return (node == null || node.isNull()) ? "" : node.asText();
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void send(HttpExchange exchange, int code, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}