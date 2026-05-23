import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

public class LinkResTop {

    public static void main(String[] args) {

        String url = "jdbc:postgresql://localhost:5432/postgres";
        String user = "farhadmahmud";
        String password = "1234";

        try {

            
            Connection conn = DriverManager.getConnection(url, user, password);

            String sql = "INSERT INTO resource_topics (resource_id, topic_id) VALUES (?, ?)";

            // this is how java sends sql query safely ,, 
            
            PreparedStatement stmt = conn.prepareStatement(sql);

           // 1 = resource_id .
           // 2 = topic_id ..
           
            stmt.setInt(1, 14);
            stmt.setInt(2,8 ); 
            stmt.executeUpdate();

            System.out.println("Resource linked to topic successfully!");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}