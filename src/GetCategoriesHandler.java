import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.OutputStream;
import java.sql.*;

public class GetCategoriesHandler implements HttpHandler {

    public void handle(HttpExchange exchange) {

        try {

            Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/postgres",
                "farhadmahmud",
                "1234"
            );

            String sql = "SELECT id, name FROM categories";
            PreparedStatement stmt = conn.prepareStatement(sql);
            ResultSet rs = stmt.executeQuery();

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

            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            exchange.sendResponseHeaders(200, response.length());

            OutputStream os = exchange.getResponseBody();
            os.write(response.toString().getBytes());
            os.close();

            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}