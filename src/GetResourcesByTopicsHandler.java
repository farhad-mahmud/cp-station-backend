import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;

// getrequest 
// backend api to get resources according to topics from database ..
public class GetResourcesByTopicsHandler implements HttpHandler {

    public void handle(HttpExchange exchange) {

        try {

            String dburl = "jdbc:postgresql://localhost:5432/postgres";
            String user = "farhadmahmud";
            String password = "1234";

            // extract query from uri .. 
            Class.forName("org.postgresql.Driver");
            System.out.println("Driver loaded successfully");

            String query = exchange.getRequestURI().getQuery();
            
            String topic = URLDecoder.decode(query.split("=")[1], StandardCharsets.UTF_8);

            Connection conn = DriverManager.getConnection(dburl, user, password);

            String sql =               
                "SELECT id, title, url, type " +
                "FROM resources " +
                "WHERE topic_id = (SELECT id FROM topics WHERE name = ?)";

            // preparedstatement is sequred this is how java sends sql query ..

            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, topic);
            ResultSet rs = stmt.executeQuery();

            String response = "[";

            boolean first = true;


            while (rs.next()) {

                if (!first) response += ",";
                 first = false;

                int id = rs.getInt("id");
                String title = rs.getString("title");
                String url = rs.getString("url");
                String type = rs.getString("type");

                response += "{";
                response += "\"id\":" + id + ",";
                response += "\"title\":\"" + title + "\",";
                response += "\"url\":\"" + url + "\",";
                response += "\"type\":\"" + type + "\"";
                    response += "}";
                 }

            response += "]";

            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes().length);

            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();

            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
              String error = "Server error: " + e.getMessage();

              
        try {
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(500, error.getBytes().length);
        exchange.getResponseBody().write(error.getBytes());
        exchange.getResponseBody().close();
         } catch (Exception ignored) {}
        }
    }
}