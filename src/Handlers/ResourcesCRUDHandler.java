package Handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import config.DbConnection;
import java.sql.*;
import java.util.Map;

public class ResourcesCRUDHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod().toUpperCase();
        Connection conn = DbConnection.getConnection();

        try {
            if (method.equals("POST")) {
                String body = readBody(exchange);
                JsonNode json = mapper.readTree(body);

                String title = json.has("title") ? json.get("title").asText() : "";
                String url = json.has("url") ? json.get("url").asText() : "";
                String type = json.has("type") ? json.get("type").asText() : "";
                boolean isInterview = json.has("is_interview") ? json.get("is_interview").asBoolean() : false;
                int sortOrder = json.has("sort_order") ? json.get("sort_order").asInt() : 0;

                String solutionCode = "";
                if (json.has("solution_code")) {
                    solutionCode = json.get("solution_code").asText();
                } else if (json.has("solutionCode")) {
                    solutionCode = json.get("solutionCode").asText();
                }

                String solutionGithubUrl = "";
                if (json.has("solution_github_url")) {
                    solutionGithubUrl = json.get("solution_github_url").asText();
                } else if (json.has("solutionGithubUrl")) {
                    solutionGithubUrl = json.get("solutionGithubUrl").asText();
                }

                int topicId = 0;
                if (json.has("topic_id")) {
                    topicId = json.get("topic_id").asInt();
                } else if (json.has("topicId")) {
                    topicId = json.get("topicId").asInt();
                }

                Integer subtopicId = null;
                if (json.has("subtopic_id") && !json.get("subtopic_id").isNull()) {
                    subtopicId = json.get("subtopic_id").asInt();
                } else if (json.has("subtopicId") && !json.get("subtopicId").isNull()) {
                    subtopicId = json.get("subtopicId").asInt();
                }

                if (title.isEmpty() || url.isEmpty() || type.isEmpty() || topicId == 0) {
                    sendError(exchange, 400, "Missing required fields");
                    return;
                }

                String sql = "INSERT INTO resources (title, url, type, topic_id, subtopic_id, is_interview, sort_order, solution_code, solution_github_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setString(1, title);
                stmt.setString(2, url);
                stmt.setString(3, type);
                stmt.setInt(4, topicId);
                if (subtopicId == null || subtopicId == 0) {
                    stmt.setNull(5, Types.INTEGER);
                } else {
                    stmt.setInt(5, subtopicId);
                }
                stmt.setBoolean(6, isInterview);
                stmt.setInt(7, sortOrder);
                stmt.setString(8, solutionCode);
                stmt.setString(9, solutionGithubUrl);

                ResultSet rs = stmt.executeQuery();
                rs.next();
                int id = rs.getInt("id");

                sendJSON(exchange, 200, Map.of("success", true, "id", id));
            } else if (method.equals("PUT")) {
                int id = getParamId(exchange);
                if (id == -1) {
                    sendError(exchange, 400, "Missing resource id parameter");
                    return;
                }

                String body = readBody(exchange);
                JsonNode json = mapper.readTree(body);

                String title = json.has("title") ? json.get("title").asText() : "";
                String url = json.has("url") ? json.get("url").asText() : "";
                String type = json.has("type") ? json.get("type").asText() : "";
                boolean isInterview = json.has("is_interview") ? json.get("is_interview").asBoolean() : false;
                int sortOrder = json.has("sort_order") ? json.get("sort_order").asInt() : 0;

                String solutionCode = "";
                if (json.has("solution_code")) {
                    solutionCode = json.get("solution_code").asText();
                } else if (json.has("solutionCode")) {
                    solutionCode = json.get("solutionCode").asText();
                }

                String solutionGithubUrl = "";
                if (json.has("solution_github_url")) {
                    solutionGithubUrl = json.get("solution_github_url").asText();
                } else if (json.has("solutionGithubUrl")) {
                    solutionGithubUrl = json.get("solutionGithubUrl").asText();
                }

                int topicId = 0;
                if (json.has("topic_id")) {
                    topicId = json.get("topic_id").asInt();
                } else if (json.has("topicId")) {
                    topicId = json.get("topicId").asInt();
                }

                Integer subtopicId = null;
                if (json.has("subtopic_id") && !json.get("subtopic_id").isNull()) {
                    subtopicId = json.get("subtopic_id").asInt();
                } else if (json.has("subtopicId") && !json.get("subtopicId").isNull()) {
                    subtopicId = json.get("subtopicId").asInt();
                }

                if (title.isEmpty() || url.isEmpty() || type.isEmpty() || topicId == 0) {
                    sendError(exchange, 400, "Missing required fields");
                    return;
                }

                String sql = "UPDATE resources SET title = ?, url = ?, type = ?, topic_id = ?, subtopic_id = ?, is_interview = ?, sort_order = ?, solution_code = ?, solution_github_url = ? WHERE id = ?";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setString(1, title);
                stmt.setString(2, url);
                stmt.setString(3, type);
                stmt.setInt(4, topicId);
                if (subtopicId == null || subtopicId == 0) {
                    stmt.setNull(5, Types.INTEGER);
                } else {
                    stmt.setInt(5, subtopicId);
                }
                stmt.setBoolean(6, isInterview);
                stmt.setInt(7, sortOrder);
                stmt.setString(8, solutionCode);
                stmt.setString(9, solutionGithubUrl);
                stmt.setInt(10, id);
                stmt.executeUpdate();

                sendJSON(exchange, 200, Map.of("success", true));
            } else if (method.equals("DELETE")) {
                int id = getParamId(exchange);
                if (id == -1) {
                    sendError(exchange, 400, "Missing resource id parameter");
                    return;
                }

                String sql = "DELETE FROM resources WHERE id = ?";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setInt(1, id);
                stmt.executeUpdate();

                sendJSON(exchange, 200, Map.of("success", true));
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        } finally {
            conn.close();
        }
    }

    private int getParamId(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2 && pair[0].equals("id")) {
                    return Integer.parseInt(pair[1]);
                }
            }
        }
        return -1;
    }
}

