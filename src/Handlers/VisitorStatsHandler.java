package Handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import config.DbConnection;
import com.fasterxml.jackson.databind.ObjectMapper;

public class VisitorStatsHandler implements HttpHandler {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) {
        try {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin == null || origin.isEmpty()) {
                origin = "https://cp-station.vercel.app";
            }
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();

            if (path.contains("/track-visit") && exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                handleTrackVisit(exchange);
            } else if (path.contains("/visitor-stats") && exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                handleGetVisitorStats(exchange);
            } else {
                sendError(exchange, 405, "Method Not Allowed or Invalid Path");
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Server Error: " + e.getMessage());
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

        Map<String, Object> responseBody = Map.of("success", true);
        byte[] bytes = mapper.writeValueAsBytes(responseBody);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
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

        Map<String, Object> responseBody = Map.of(
            "totalVisits", totalVisits,
            "uniqueVisits", uniqueVisits
        );
        byte[] bytes = mapper.writeValueAsBytes(responseBody);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) {
        try {
            Map<String, String> error = Map.of("error", message);
            byte[] bytes = mapper.writeValueAsBytes(error);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception ignored) {}
    }
}
