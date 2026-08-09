import com.sun.net.httpserver.HttpExchange;
import Handlers.AbstractHttpHandler;
import config.DbConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class GetSubtopicsByTopics extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        Class.forName("org.postgresql.Driver");

        String query = exchange.getRequestURI().getQuery();

        if (query == null || !query.contains("=")) {
            sendError(exchange, 400, "Missing topicId parameter");
            return;
        }

        int topicId = Integer.parseInt(
                URLDecoder.decode(query.split("=")[1], StandardCharsets.UTF_8)
        );

        Connection conn = DbConnection.getConnection();

        String sql =
                "SELECT id, name, sort_order " +
                "FROM subtopics " +
                "WHERE topic_id = ? " +
                "ORDER BY CASE WHEN sort_order = 0 THEN 999999 ELSE sort_order END ASC, id ASC";

        PreparedStatement stmt = conn.prepareStatement(sql);
        stmt.setInt(1, topicId);

        ResultSet rs = stmt.executeQuery();

        StringBuilder response = new StringBuilder();
        response.append("[");

        boolean first = true;

        while (rs.next()) {
            if (!first) response.append(",");
            first = false;

            response.append("{")
                    .append("\"id\":").append(rs.getInt("id")).append(",")
                    .append("\"name\":\"").append(rs.getString("name")).append("\",")
                    .append("\"sort_order\":").append(rs.getInt("sort_order"))
                    .append("}");
        }

        response.append("]");

        conn.close();
        sendJSON(exchange, 200, response.toString());
    }
}