package Handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import config.DbConnection;
import auth.SessionUtil;
import Services.GeminiService;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class AiExplanationHandler extends AbstractHttpHandler {

    private final Services.AiExplanationFacade facade = new Services.AiExplanationFacade();

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod().toUpperCase();

        if (method.equals("GET")) {
            handleGet(exchange);
        } else if (method.equals("POST")) {
            handlePost(exchange);
        } else {
            sendError(exchange, 405, "Method not allowed");
        }
    }


    private void handleGet(HttpExchange exchange) throws Exception {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);

        String type = params.get("type");
        String idStr = params.get("id");

        if (type == null || idStr == null) {
            sendError(exchange, 400, "Missing required parameters: type and id");
            return;
        }

        // For solutions, check authentication even for GET requests
        if ("solution".equals(type)) {
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String token = SessionUtil.extractTokenFromCookies(cookieHeader);
            Integer userId = SessionUtil.getUserIdFromToken(token);
            if (userId == null) {
                sendError(exchange, 401, "Unauthorized: Logged in session required.");
                return;
            }
        }

        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            sendError(exchange, 400, "Invalid id parameter");
            return;
        }

        String content = facade.getCachedExplanation(type, id);
        if (content != null) {
            sendJSON(exchange, 200, Map.of("cached", true, "content", content));
        } else {
            sendJSON(exchange, 200, Map.of("cached", false, "can_generate", true));
        }
    }

    private void handlePost(HttpExchange exchange) throws Exception {
        // Parse Request Body first to check resource type
        String body = readBody(exchange);
        JsonNode json = mapper.readTree(body);
        String type = json.path("type").asText("");
        int id = json.path("id").asInt(0);
        boolean regenerate = json.path("regenerate").asBoolean(false);

        if (type.isEmpty() || id == 0) {
            sendError(exchange, 400, "Missing required fields: type and id");
            return;
        }

        if (!type.equals("solution") && !type.equals("topic") && !type.equals("subtopic")) {
            sendError(exchange, 400, "Invalid resource type. Allowed values: solution, topic, subtopic");
            return;
        }

        // Verification of session / user
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String token = SessionUtil.extractTokenFromCookies(cookieHeader);
        Integer userId = SessionUtil.getUserIdFromToken(token);
        String role = SessionUtil.getRoleFromToken(token);

        // Check cache via Facade
        String existingContent = facade.getCachedExplanation(type, id);

        // If explanation is cached and they do not request regeneration, return cached
        if (existingContent != null && !regenerate) {
            sendJSON(exchange, 200, Map.of("cached", true, "content", existingContent));
            return;
        }

        // If user is not logged in
        if (userId == null) {
            if ("solution".equals(type)) {
                sendError(exchange, 401, "Unauthorized: Logged in session required.");
                return;
            }
            if (existingContent != null && regenerate) {
                sendError(exchange, 401, "Unauthorized: Logged in session required.");
                return;
            }
        } else {
            // Overwrite or regenerate requires admin role for logged in users
            if (existingContent != null && regenerate && !"admin".equalsIgnoreCase(role)) {
                sendError(exchange, 403, "Forbidden: Only admin users can regenerate existing cached explanations.");
                return;
            }
        }

        // Generate explanation via Facade
        try {
            String explanation = facade.generateAndCacheExplanation(type, id, userId);
            sendJSON(exchange, 200, Map.of("cached", true, "content", explanation));
        } catch (Services.AiExplanationFacade.ResourceNotFoundException e) {
            sendError(exchange, 404, e.getMessage());
        } catch (Services.AiExplanationFacade.InvalidResourceDataException e) {
            sendError(exchange, 400, e.getMessage());
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                params.put(pair[0], java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            } else if (pair.length == 1) {
                params.put(pair[0], "");
            }
        }
        return params;
    }
}

