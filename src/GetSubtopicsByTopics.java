import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class GetSubtopicsByTopics implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) {

        String dburl = "jdbc:postgresql://localhost:5432/postgres";
        String user = "farhadmahmud";
        String password = "1234";

        Connection conn = null;

        try {
            // Load driver
            Class.forName("org.postgresql.Driver");
            System.out.println("Driver loaded successfully");

            // Get query param: ?topic=DP
            String query = exchange.getRequestURI().getQuery();

            if (query == null || !query.contains("=")) {
                sendError(exchange, 400, "Missing topic parameter");
                return;
            }

            String topicName = URLDecoder.decode(
                    query.split("=")[1],
                    StandardCharsets.UTF_8
            );

            conn = DriverManager.getConnection(dburl, user, password);

            String sql =
                    "SELECT id, name " +
                    "FROM subtopics " +
                    "WHERE topic_id = (SELECT id FROM topics WHERE name = ?)";

            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, topicName);

            ResultSet rs = stmt.executeQuery();

            StringBuilder response = new StringBuilder();
            response.append("[");

            boolean first = true;

            while (rs.next()) {

                if (!first) response.append(",");
                first = false;

                int id = rs.getInt("id");
                String name = rs.getString("name");

                response.append("{")
                        .append("\"id\":").append(id).append(",")
                        .append("\"name\":\"").append(name).append("\"")
                        .append("}");
            }

            response.append("]");

            // Headers
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

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
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            exchange.sendResponseHeaders(statusCode, error.getBytes().length);

            OutputStream os = exchange.getResponseBody();
            os.write(error.getBytes());
            os.close();

        } catch (Exception ignored) {}
    }
}