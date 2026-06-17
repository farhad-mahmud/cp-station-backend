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

             // read requestttt.
            InputStreamReader isr =
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);

            BufferedReader br = new BufferedReader(isr);

            StringBuilder sb = new StringBuilder();
            String line;

            while ((line = br.readLine()) != null) {
                sb.append(line);
            }

            String body = sb.toString();

            // manual json parsing..

            String title = extract(body, "title");
            String url = extract(body, "url");
            String type = extract(body, "type");
            String topic = extract(body, "topic");
        
             // validation check if title , empty or not

            if (isEmpty(title) || isEmpty(url) || isEmpty(type) || isEmpty(topic)) {
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

            // Class.forName("org.postgresql.Driver");

            Connection conn = DriverManager.getConnection(dbUrl, user, password);

            // insert resourcess..

            String sql =
                    "INSERT INTO resources(title, url, type) VALUES (?, ?, ?) RETURNING id";

            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, title);
            stmt.setString(2, url);
            stmt.setString(3, type);

            ResultSet rs = stmt.executeQuery();
            rs.next();

            int resourceId = rs.getInt("id");

            // get topic id..by name..of the topic..

            PreparedStatement tstmt =
                    conn.prepareStatement("SELECT id FROM topics WHERE name = ?");

            tstmt.setString(1, topic);

            ResultSet get_topic_id = tstmt.executeQuery();
            get_topic_id.next();

            int topicId = get_topic_id.getInt("id");

             // insert relation 
            PreparedStatement rstmt =
                    conn.prepareStatement(
                            "INSERT INTO resource_topics(resource_id, topic_id) VALUES (?, ?)");

            rstmt.setInt(1, resourceId);
            rstmt.setInt(2, topicId);

            rstmt.executeUpdate();

           // response / results

            System.out.println("TYPE='" + type + "'");

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


   //  extract json ..
    // by json perser..

    private String extract(String body, String key) {
        try {
            String pattern = "\"" + key + "\":\"";
            int start = body.indexOf(pattern) + pattern.length();
            int end = body.indexOf("\"", start);
            return body.substring(start, end);
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}