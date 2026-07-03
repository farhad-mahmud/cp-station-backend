import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import config.DbConnection;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class GetSubtopicsByTopics implements HttpHandler {

    private static final String ALLOWED_ORIGIN = "https://cp-station.vercel.app";

    @Override
    public void handle(HttpExchange exchange) {

        Connection conn = null;

        try {
            Class.forName("org.postgresql.Driver");

            // -----------------------------
            // STEP 1: read topicId
            // -----------------------------
            String query = exchange.getRequestURI().getQuery();

            if (query == null || !query.contains("=")) {
                sendError(exchange, 400, "Missing topicId parameter");
                return;
            }

            int topicId = Integer.parseInt(
                    URLDecoder.decode(query.split("=")[1], StandardCharsets.UTF_8)
            );

                 conn =
                DbConnection.getConnection();

            // -----------------------------
            // STEP 2: UPDATED SQL (IMPORTANT CHANGE ONLY HERE)
            // -----------------------------
            String sql =
                    "SELECT id, name, sort_order " +
                    "FROM subtopics " +
                    "WHERE topic_id = ? " +
                    "ORDER BY CASE WHEN sort_order = 0 THEN 999999 ELSE sort_order END ASC, id ASC";

            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setInt(1, topicId);

            ResultSet rs = stmt.executeQuery();

            StringBuilder response = new StringBuilder();
            response.append("[");

            boolean first = true;

            while (rs.next()) {

                if (!first) response.append(",");
                first = false;

                response.append("{")
                        .append("\"id\":").append(rs.getInt("id")).append(",")
                        .append("\"name\":\"").append(rs.getString("name")).append("\",")
                        .append("\"sort_order\":").append(rs.getInt("sort_order"))
                        .append("}");
            }

            response.append("]");

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
            exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");

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
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
            exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");

            exchange.sendResponseHeaders(statusCode, error.getBytes().length);

            OutputStream os = exchange.getResponseBody();
            os.write(error.getBytes());
            os.close();

        } catch (Exception ignored) {}
    }
}