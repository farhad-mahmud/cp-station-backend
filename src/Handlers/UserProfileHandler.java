package Handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import auth.SessionUtil;
import config.DbConnection;

public class UserProfileHandler implements HttpHandler {

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

            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String token = SessionUtil.extractTokenFromCookies(cookieHeader);
            Integer userId = SessionUtil.getUserIdFromToken(token);

            if (userId == null) {
                sendError(exchange, 401, "Unauthorized");
                return;
            }

            if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                handleGetProfile(exchange, userId);
            } else if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                handleUpdateProfile(exchange, userId);
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Server Error: " + e.getMessage());
        }
    }

    private void handleGetProfile(HttpExchange exchange, int userId) throws Exception {
        Connection conn = DbConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(
            "SELECT name, institute, solutions_contributed, completed_topics, completed_subtopics, solved_resources FROM users WHERE id = ?"
        );
        stmt.setInt(1, userId);
        ResultSet rs = stmt.executeQuery();

        if (!rs.next()) {
            conn.close();
            sendError(exchange, 404, "User not found");
            return;
        }

        String name = rs.getString("name");
        String institute = rs.getString("institute");
        int solutionsContributed = rs.getInt("solutions_contributed");
        
        String completedTopics = rs.getString("completed_topics");
        if (completedTopics == null || completedTopics.trim().isEmpty()) {
            completedTopics = "[]";
        }
        
        String completedSubtopics = rs.getString("completed_subtopics");
        if (completedSubtopics == null || completedSubtopics.trim().isEmpty()) {
            completedSubtopics = "[]";
        }
        
        String solvedResources = rs.getString("solved_resources");
        if (solvedResources == null || solvedResources.trim().isEmpty()) {
            solvedResources = "[]";
        }

        conn.close();

        StringBuilder response = new StringBuilder("{");
        response.append("\"loggedIn\":true,")
                .append("\"name\":\"").append(escapeJson(name)).append("\",")
                .append("\"institute\":\"").append(escapeJson(institute)).append("\",")
                .append("\"solutionsContributed\":").append(solutionsContributed).append(",")
                .append("\"completedTopics\":").append(completedTopics).append(",")
                .append("\"completedSubtopics\":").append(completedSubtopics).append(",")
                .append("\"solvedResources\":").append(solvedResources)
                .append("}");

        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleUpdateProfile(HttpExchange exchange, int userId) throws Exception {
        JsonNode json = mapper.readTree(readBody(exchange));
        
        Connection conn = DbConnection.getConnection();
        StringBuilder sql = new StringBuilder("UPDATE users SET ");
        List<Object> params = new ArrayList<>();
        boolean first = true;

        if (json.has("name")) {
            sql.append("name = ?");
            params.add(json.get("name").asText());
            first = false;
        }
        if (json.has("institute")) {
            if (!first) sql.append(", ");
            sql.append("institute = ?");
            params.add(json.get("institute").asText());
            first = false;
        }
        if (json.has("solutionsContributed")) {
            if (!first) sql.append(", ");
            sql.append("solutions_contributed = ?");
            params.add(json.get("solutionsContributed").asInt());
            first = false;
        }
        if (json.has("completedTopics")) {
            if (!first) sql.append(", ");
            sql.append("completed_topics = ?");
            params.add(json.get("completedTopics").toString());
            first = false;
        }
        if (json.has("completedSubtopics")) {
            if (!first) sql.append(", ");
            sql.append("completed_subtopics = ?");
            params.add(json.get("completedSubtopics").toString());
            first = false;
        }
        if (json.has("solvedResources")) {
            if (!first) sql.append(", ");
            sql.append("solved_resources = ?");
            params.add(json.get("solvedResources").toString());
            first = false;
        }

        if (params.isEmpty()) {
            conn.close();
            sendJSON(exchange, 200, Map.of("success", true, "message", "No fields to update"));
            return;
        }

        sql.append(" WHERE id = ?");
        params.add(userId);

        PreparedStatement stmt = conn.prepareStatement(sql.toString());
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Integer) {
                stmt.setInt(i + 1, (Integer) p);
            } else {
                stmt.setString(i + 1, (String) p);
            }
        }
        stmt.executeUpdate();
        conn.close();

        sendJSON(exchange, 200, Map.of("success", true));
    }

    private String readBody(HttpExchange exchange) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private void sendJSON(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(data);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) {
        try {
            Map<String, String> error = Map.of("error", message);
            sendJSON(exchange, statusCode, error);
        } catch (Exception ignored) {}
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
