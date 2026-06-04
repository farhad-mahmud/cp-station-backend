package Repository;

import config.DbConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class GetTopicRepository {

    public List<String> getAllTopics() throws Exception {

        Connection conn =
                DbConnection.getConnection();

        String sql =
                "SELECT name FROM topics";

        PreparedStatement stmt =
                conn.prepareStatement(sql);

        ResultSet rs =
                stmt.executeQuery();

        List<String> topics =
                new ArrayList<>();

        while(rs.next()) {
            topics.add(
                    rs.getString("name")
            );
        }

        conn.close();

        return topics;
    }
}