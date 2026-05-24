import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.OutputStream;
import java.sql.*;
//getrequest ..

// backend api to retrieve topics from .. database..
public class GetTopicsHandler implements HttpHandler {

    public void handle(HttpExchange exchange) {
        try {

            String url = "jdbc:postgresql://localhost:5432/postgres";
            String user = "farhadmahmud";
            String password = "1234";

            Connection conn = DriverManager.getConnection(url, user, password);

            String sql = "SELECT name FROM topics";
            PreparedStatement stmt = conn.prepareStatement(sql);
            ResultSet rs = stmt.executeQuery();

            String response = "[";

            boolean first = true;

            while (rs.next()) {
                if (!first) response += ",";
                first = false;

                String name = rs.getString("name");

                response += "\"" + name + "\"";
            }

            response += "]";

            // CORS (IMPORTANT)
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            exchange.sendResponseHeaders(200, response.getBytes().length);

            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();

            conn.close();

        } catch (Exception e) {
            e.printStackTrace();

            try {
                String error = "Server error: " + e.getMessage();
                exchange.sendResponseHeaders(500, error.getBytes().length);
                exchange.getResponseBody().write(error.getBytes());
                exchange.getResponseBody().close();
            } catch (Exception ignored) {}
        }
    }
}