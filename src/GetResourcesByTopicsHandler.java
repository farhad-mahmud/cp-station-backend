import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.OutputStream;
import java.sql.*;

public class GetResourcesByTopicsHandler implements HttpHandler {

    public void handle(HttpExchange exchange) {

        try {
            String dburl = "jdbc:postgresql://localhost:5432/postgres";
            String user = "farhadmahmud";
            String password = "1234";

            Class.forName("org.postgresql.Driver");

            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            String query = exchange.getRequestURI().getQuery();
            System.out.println("Raw query: " + query);

            if (query == null) {
                sendError(exchange, 400, "Missing query parameter");
                return;
            }

            
            String key = query.split("=")[0];
            String value = query.split("=")[1];

            Connection conn = DriverManager.getConnection(dburl, user, password);
            PreparedStatement stmt;

            if (key.equals("subtopicId")) {
                System.out.println("Fetching by subtopicId: " + value);

                String sql = "SELECT id, title, url, type FROM resources WHERE subtopic_id = ?";
                stmt = conn.prepareStatement(sql);
                stmt.setInt(1, Integer.parseInt(value));

            } else if (key.equals("topicId")) {
                System.out.println("Fetching by topicId: " + value);

                
                String sql = "SELECT id, title, url, type FROM resources WHERE topic_id = ?";
                stmt = conn.prepareStatement(sql);
                stmt.setInt(1, Integer.parseInt(value));

            } else {
                sendError(exchange, 400, "Invalid parameter — use subtopicId or topicId");
                return;
            }

            ResultSet rs = stmt.executeQuery();

            StringBuilder response = new StringBuilder("[");
            boolean first = true;
            int count = 0;

            while (rs.next()) {
                if (!first) response.append(",");
                first = false;
                count++;

                int id = rs.getInt("id");
                String title = rs.getString("title");
                String url = rs.getString("url");
                String type = rs.getString("type");

                response.append("{")
                        .append("\"id\":").append(id).append(",")
                        .append("\"title\":\"").append(escapeJson(title)).append("\",")
                        .append("\"url\":\"").append(escapeJson(url)).append("\",")
                        .append("\"type\":\"").append(escapeJson(type)).append("\"")
                        .append("}");
            }

            response.append("]");
            System.out.println("Total resources found: " + count);

            byte[] bytes = response.toString().getBytes("UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Server error: " + e.getMessage());
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) {
        try {
            String error = "{\"error\":\"" + message + "\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(statusCode, error.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(error.getBytes());
            os.close();
        } catch (Exception ignored) {}
    }
}