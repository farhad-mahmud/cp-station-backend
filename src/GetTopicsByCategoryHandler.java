import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class GetTopicsByCategoryHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) {

        try {
            // CORS
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            // only GET allowed
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                String res = "{\"error\":\"Only GET allowed\"}";
                exchange.sendResponseHeaders(405, res.getBytes().length);
                exchange.getResponseBody().write(res.getBytes());
                exchange.getResponseBody().close();
                return;
            }

            // -----------------------------
            // STEP 1: Parse query safely
            // -----------------------------
            String query = exchange.getRequestURI().getQuery();

            String categoryId = null;

            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");

                    if (pair.length == 2 && pair[0].equals("categoryId")) {
                        categoryId = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    }
                }
            }

            System.out.println("CATEGORY ID = " + categoryId);

            if (categoryId == null) {
                String res = "{\"error\":\"Missing categoryId\"}";
                exchange.sendResponseHeaders(400, res.getBytes().length);
                exchange.getResponseBody().write(res.getBytes());
                exchange.getResponseBody().close();
                return;
            }

            // -----------------------------
            // STEP 2: DB Connection
            // -----------------------------
            Connection conn = DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/postgres",
                    "farhadmahmud",
                    "1234"
            );

            // -----------------------------
            // STEP 3: Query topics
            // -----------------------------
            String sql = "SELECT id, name FROM topics WHERE category_id = ?";

            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setInt(1, Integer.parseInt(categoryId));

            ResultSet rs = stmt.executeQuery();

            // -----------------------------
            // STEP 4: Build JSON response
            // -----------------------------
            StringBuilder response = new StringBuilder("[");
            boolean first = true;

            while (rs.next()) {

                if (!first) response.append(",");
                first = false;

                response.append("{")
                        .append("\"id\":").append(rs.getInt("id")).append(",")
                        .append("\"name\":\"").append(rs.getString("name")).append("\"")
                        .append("}");
            }

            response.append("]");

            // -----------------------------
            // STEP 5: Send response
            // -----------------------------
            exchange.sendResponseHeaders(200, response.length());

            OutputStream os = exchange.getResponseBody();
            os.write(response.toString().getBytes());
            os.close();

            conn.close();

        } catch (Exception e) {
            e.printStackTrace();

            try {
                String error = "{\"error\":\"" + e.getMessage() + "\"}";
                exchange.sendResponseHeaders(500, error.getBytes().length);
                exchange.getResponseBody().write(error.getBytes());
                exchange.getResponseBody().close();
            } catch (Exception ignored) {}
        }
    }
}