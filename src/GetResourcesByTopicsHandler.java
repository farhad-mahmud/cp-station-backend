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
            System.out.println("Driver loaded successfully");

            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            String query = exchange.getRequestURI().getQuery();
            System.out.println("📥 Raw query: " + query);

            if (query == null) {
                sendError(exchange, 400, "Missing query parameter");
                return;
            }

            Connection conn = DriverManager.getConnection(dburl, user, password);

            PreparedStatement stmt;

            if (query.startsWith("subtopicId=")) {
                // ── Has subtopic — fetch resources where subtopic_id matches ──
                String subtopicId = query.split("=")[1];
                System.out.println("🔍 Fetching by subtopicId: " + subtopicId);

                String sql =
                    "SELECT id, title, url, type " +
                    "FROM resources " +
                    "WHERE subtopic_id = ?";

                stmt = conn.prepareStatement(sql);
                stmt.setInt(1, Integer.parseInt(subtopicId));

            } else if (query.startsWith("topicId=")) {
                // ── No subtopic — fetch resources where topic_id matches and subtopic_id is null ──
                String topicId = query.split("=")[1];
                System.out.println("🔍 Fetching by topicId: " + topicId);

                String sql =
                    "SELECT id, title, url, type " +
                    "FROM resources " +
                    "WHERE topic_id = ? AND subtopic_id IS NULL";

                stmt = conn.prepareStatement(sql);
                stmt.setInt(1, Integer.parseInt(topicId));
            }
            else{
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
                        .append("\"title\":\"").append(title).append("\",")
                        .append("\"url\":\"").append(url).append("\",")
                        .append("\"type\":\"").append(type).append("\"")
                        .append("}");
            }

            response.append("]");
            System.out.println(" Total resources found: " + count);

            exchange.sendResponseHeaders(200, response.toString().getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.toString().getBytes());
            os.close();
            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Server error: " + e.getMessage());
        }
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