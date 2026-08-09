package Handlers;

import com.sun.net.httpserver.HttpExchange;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import config.DbConnection;

public class VisitorStatsHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getPath();

        if (path.contains("/track-visit") && exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            handleTrackVisit(exchange);
        } else if (path.contains("/visitor-stats") && exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            handleGetVisitorStats(exchange);
        } else {
            sendError(exchange, 405, "Method Not Allowed or Invalid Path");
        }
    }

    private void handleTrackVisit(HttpExchange exchange) throws Exception {
        String query = exchange.getRequestURI().getQuery();
        boolean isUnique = false;
        if (query != null && query.contains("unique=true")) {
            isUnique = true;
        }

        Connection conn = DbConnection.getConnection();
        String sql;
        if (isUnique) {
            sql = "UPDATE visitor_stats SET total_visits = total_visits + 1, unique_visits = unique_visits + 1 WHERE id = 1";
        } else {
            sql = "UPDATE visitor_stats SET total_visits = total_visits + 1 WHERE id = 1";
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        }
        conn.close();

        sendJSON(exchange, 200, Map.of("success", true));
    }

    private void handleGetVisitorStats(HttpExchange exchange) throws Exception {
        int totalVisits = 0;
        int uniqueVisits = 0;

        Connection conn = DbConnection.getConnection();
        String sql = "SELECT total_visits, unique_visits FROM visitor_stats WHERE id = 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                totalVisits = rs.getInt("total_visits");
                uniqueVisits = rs.getInt("unique_visits");
            }
        }
        conn.close();

        sendJSON(exchange, 200, Map.of(
            "totalVisits", totalVisits,
            "uniqueVisits", uniqueVisits
        ));
    }
}

