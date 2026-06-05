package Repository;

import config.DbConnection;
import models.Topic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class GetTopicByCatRepository {
    public List<Topic> getTopicsByCategoryId(int categoryId)
        throws Exception {

    Connection conn =
            DbConnection.getConnection();

    String sql =
            "SELECT id, name FROM topics WHERE category_id = ?";

    PreparedStatement stmt =
            conn.prepareStatement(sql);

    stmt.setInt(1, categoryId);

    ResultSet rs =
            stmt.executeQuery();

    List<Topic> topics =
            new ArrayList<>();

    while (rs.next()) {

        topics.add(
            new Topic(
                rs.getInt("id"),
                rs.getString("name")
            )
        );
    }

    conn.close();

    return topics;
}
}
