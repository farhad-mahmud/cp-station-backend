import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class AddResourceHandler implements HttpHandler {

    public void handle(HttpExchange exchange) {

        try {

            // CORS
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            // only allow POST
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                String res = "{\"status\":\"error\",\"message\":\"Only POST allowed\"}";
                exchange.sendResponseHeaders(405, res.getBytes().length);
                exchange.getResponseBody().write(res.getBytes());
                exchange.getResponseBody().close();
                return;
            }

            // read request
            InputStreamReader isr =
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);

            BufferedReader br = new BufferedReader(isr);

            StringBuilder sb = new StringBuilder();
            String line;

            while ((line = br.readLine()) != null) {
                sb.append(line);
            }

            String body = sb.toString();

            // manual json parsing
            String title = extract(body, "title");
            String url = extract(body, "url");
            String type = extract(body, "type");
            String topicId = extract(body, "topicId");       // now an id, not a name
            String subtopicId = extract(body, "subtopicId"); // may be empty if not sent

            // validation — title, url, type, topicId are required
            if (isEmpty(title) || isEmpty(url) || isEmpty(type) || isEmpty(topicId)) {
                String res = "{\"status\":\"error\",\"message\":\"Missing fields\"}";
                exchange.sendResponseHeaders(400, res.getBytes().length);
                exchange.getResponseBody().write(res.getBytes());
                exchange.getResponseBody().close();
                return;
            }

            // db connection
            String dbUrl = "jdbc:postgresql://localhost:5432/postgres";
            String user = "farhadmahmud";
            String password = "1234";

            Connection conn = DriverManager.getConnection(dbUrl, user, password);

            // insert resource directly with topic_id and subtopic_id
            String sql =
                    "INSERT INTO resources(title, url, type, topic_id, subtopic_id) " +
                    "VALUES (?, ?, ?, ?, ?) RETURNING id";

            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, title);
            stmt.setString(2, url);
            stmt.setString(3, type);
            stmt.setInt(4, Integer.parseInt(topicId));

            if (!isEmpty(subtopicId)) {
                stmt.setInt(5, Integer.parseInt(subtopicId));
            } else {
                stmt.setNull(5, Types.INTEGER);
            }

            ResultSet rs = stmt.executeQuery();
            rs.next();

            int resourceId = rs.getInt("id");

            // response
            String response =
                    "{\"status\":\"success\",\"resourceId\":" + resourceId + "}";

            exchange.sendResponseHeaders(200, response.getBytes().length);
            exchange.getResponseBody().write(response.getBytes());
            exchange.getResponseBody().close();

            conn.close();

        } catch (Exception e) {
            e.printStackTrace();

            try {
                String error =
                        "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";

                exchange.sendResponseHeaders(500, error.getBytes().length);
                exchange.getResponseBody().write(error.getBytes());
                exchange.getResponseBody().close();

            } catch (Exception ignored) {}
        }
    }

    // extract json by simple parsing
    
   private String extract(String body, String key) {
    try {
        String searchKey = "\"" + key + "\"";
        int keyIndex = body.indexOf(searchKey);
        if (keyIndex == -1) return "";

        int colon = body.indexOf(":", keyIndex + searchKey.length());
        if (colon == -1) return "";

        int i = colon + 1;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;

        if (i >= body.length() || body.charAt(i) != '"') return "";

        int start = i + 1;
        int end = body.indexOf("\"", start);
        if (end == -1) return "";

        return body.substring(start, end);
    } catch (Exception e) {
        return "";
    }
}

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}