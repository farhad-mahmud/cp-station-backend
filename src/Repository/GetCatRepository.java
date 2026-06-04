package Repository;

import config.DbConnection;
import models.Category;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class GetCatRepository {

    public List<Category> getAllCategories() throws Exception {

        Connection conn = DbConnection.getConnection();

        String sql =
                "SELECT id, category_name FROM categories";

        PreparedStatement stmt =
                conn.prepareStatement(sql);

        ResultSet rs =
                stmt.executeQuery();

        List<Category> list = new ArrayList<>();

        while (rs.next()) {
            list.add(
                new Category(
                    rs.getInt("id"),
                    rs.getString("category_name")
                )
            );
        }

        conn.close();

        return list;
    }
}