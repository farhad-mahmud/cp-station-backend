import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import config.DbConnection;
import java.io.OutputStream;
import java.sql.*;

public class GetResourcesByTopicsHandler implements HttpHandler {

    private static final String ALLOWED_ORIGIN = "https://cp-station.vercel.app";

    public void handle(HttpExchange exchange) {

        try {
            

            Class.forName("org.postgresql.Driver");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
            exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            String query = exchange.getRequestURI().getQuery();
            System.out.println("Raw query: " + query);

            if (query == null) {
                sendError(exchange, 400, "Missing query parameter");
                return;
            }

            
            String key = query.split("=")[0];
            String value = query.split("=")[1];

             Connection conn =
             DbConnection.getConnection();
                
            PreparedStatement stmt;

            if (key.equals("subtopicId")) {
                System.out.println("Fetching by subtopicId: " + value);

                String sql = "SELECT id, title, url, type, is_interview, sort_order, solution_code, solution_github_url FROM resources WHERE subtopic_id = ? ORDER BY CASE WHEN sort_order = 0 THEN 999999 ELSE sort_order END ASC, id ASC";
                stmt = conn.prepareStatement(sql);
                stmt.setInt(1, Integer.parseInt(value));

            } else if (key.equals("topicId")) {
                System.out.println("Fetching by topicId: " + value);

                
                String sql = "SELECT id, title, url, type, is_interview, sort_order, solution_code, solution_github_url FROM resources WHERE topic_id = ? ORDER BY CASE WHEN sort_order = 0 THEN 999999 ELSE sort_order END ASC, id ASC";
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
                boolean isInterview = rs.getBoolean("is_interview");
                int sortOrder = rs.getInt("sort_order");
                String solutionCode = rs.getString("solution_code");
                String solutionGithubUrl = rs.getString("solution_github_url");
                if (solutionCode == null) solutionCode = "";
                if (solutionGithubUrl == null) solutionGithubUrl = "";

                response.append("{")
                        .append("\"id\":").append(id).append(",")
                        .append("\"title\":\"").append(escapeJson(title)).append("\",")
                        .append("\"url\":\"").append(escapeJson(url)).append("\",")
                        .append("\"type\":\"").append(escapeJson(type)).append("\",")
                        .append("\"is_interview\":").append(isInterview).append(",")
                        .append("\"sort_order\":").append(sortOrder).append(",")
                        .append("\"solution_code\":\"").append(escapeJson(solutionCode)).append("\",")
                        .append("\"solution_github_url\":\"").append(escapeJson(solutionGithubUrl)).append("\"")
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
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
            exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
            exchange.sendResponseHeaders(statusCode, error.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(error.getBytes());
            os.close();
        } catch (Exception ignored) {}
    }
}