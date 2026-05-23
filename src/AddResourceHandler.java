
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
// post request ..

public class AddResourceHandler implements HttpHandler {

    public void handle (HttpExchange exchange){
           try {
               
            //cors..
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin" , "*") ;

            // db connection

            String dburl = "jdbc:postgresql://localhost:5432/postgres";
            String user = "farhadmahmud";
            String password = "1234";

            Connection conn = DriverManager.getConnection(dburl, user, password);

            // query parameters ..
            String query = exchange.getRequestURI().getQuery() ;

            String[] params = query.split("&") ;

            String title = "";
            String url = "";
            String type = "";
            String topic = "";

            for (String p : params) {

                String[] pair = p.split("=");

                // pair[0] is key.. like title , url, type,topic,,
                // pair[1] is value.. like title = binarysearchguide , type = article..

                String key = pair[0];
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);

                if (key.equals("title")) title = value;
                if (key.equals("url")) url = value;
                if (key.equals("type")) type = value;
                if (key.equals("topic")) topic = value;
            }

            String insert = "INSERT INTO resources(title, url, type) " +
                "VALUES (?, ?, ?) RETURNING id";

            PreparedStatement insertstmt = conn.prepareStatement(insert);

            insertstmt.setString(1 , title) ;
            insertstmt.setString(2, url);
            insertstmt.setString(3, type);


            ResultSet insertedResource = insertstmt.executeQuery();

            insertedResource.next();

            int resourceId = insertedResource.getInt("id");

            // FIND TOPIC ID
            String topicSql =
                "SELECT id FROM topics WHERE name = ?";

            PreparedStatement topicStmt =
                conn.prepareStatement(topicSql);

            topicStmt.setString(1, topic);

            ResultSet topicResult = topicStmt.executeQuery();

            topicResult.next();

            int topicId = topicResult.getInt("id");


            String relationSql =
                "INSERT INTO resource_topics(resource_id, topic_id) " +
                "VALUES (?, ?)";

            PreparedStatement relationStmt =
                conn.prepareStatement(relationSql);

            relationStmt.setInt(1, resourceId);
            relationStmt.setInt(2, topicId);

            relationStmt.executeUpdate();

            // SUCCESS RESPONSE

            String response = "Resource added successfully";

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

               } catch (Exception ignored) {
                    
               }
               
           }
    }
}
