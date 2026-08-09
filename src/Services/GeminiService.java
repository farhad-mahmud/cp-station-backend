package Services;

import config.DbConnection;
import config.Env;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

public class GeminiService {
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Call the Gemini API to generate content.
     * Logs the usage details to database.
     */
    public static String generateContent(
        ObjectNode requestBody, 
        Integer userId, 
        String resourceType, 
        int resourceId
    ) throws Exception {
        String apiKey = Env.get("GEMINI_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("GEMINI_API_KEY environment variable is not set.");
        }

        String model = Env.get("GEMINI_MODEL");
        if (model == null || model.trim().isEmpty()) {
            model = "gemini-2.5-flash"; // Fallback default
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
        String requestJson = mapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Gemini API returned error code " + response.statusCode() + ": " + response.body());
        }

        JsonNode rootNode = mapper.readTree(response.body());
        
        // Extract text response
        JsonNode candidate = rootNode.path("candidates").path(0);
        JsonNode parts = candidate.path("content").path("parts");
        if (parts.isMissingNode() || parts.isEmpty()) {
            throw new RuntimeException("No text generated in response. Full body: " + response.body());
        }
        
        String responseText = parts.get(0).path("text").asText("");

        // Extract and log usage tokens
        JsonNode usageNode = rootNode.path("usageMetadata");
        int totalTokens = usageNode.path("totalTokenCount").asInt(0);
        int thoughtsTokens = usageNode.path("thoughtsTokenCount").asInt(0);

        logUsage(userId, resourceType, resourceId, totalTokens, thoughtsTokens);

        return responseText;
    }

    /**
     * Strategy Pattern Context Executor:
     * Executes Gemini API content generation using an injected AiPromptStrategy.
     */
    public static String generateContentWithStrategy(
        Services.strategies.AiPromptStrategy strategy,
        Integer userId,
        int resourceId
    ) throws Exception {
        ObjectNode requestBody = strategy.buildRequestBody(mapper);
        return generateContent(requestBody, userId, strategy.getResourceType(), resourceId);
    }

    /**
     * Explains the solution code for a problem using SolutionExplanationStrategy.
     */
    public static String generateSolutionExplanation(
        String problemTitle, 
        String code, 
        Integer userId, 
        int resourceId
    ) throws Exception {
        Services.strategies.AiPromptStrategy strategy = 
            new Services.strategies.SolutionExplanationStrategy(problemTitle, code);
        return generateContentWithStrategy(strategy, userId, resourceId);
    }

    /**
     * Explains a CP topic or subtopic using TopicExplanationStrategy.
     */
    public static String generateTopicExplanation(
        String name, 
        String categoryOrParentName, 
        String resourceType, 
        Integer userId, 
        int resourceId
    ) throws Exception {
        Services.strategies.AiPromptStrategy strategy = 
            new Services.strategies.TopicExplanationStrategy(name, categoryOrParentName, resourceType);
        return generateContentWithStrategy(strategy, userId, resourceId);
    }

    /**
     * Generate multi-turn followup Q&A response using FollowupPromptStrategy.
     */
    public static String generateFollowupResponse(
        String explanationContent, 
        List<Map<String, String>> chatHistory, 
        String newQuestion, 
        Integer userId, 
        String resourceType, 
        int resourceId
    ) throws Exception {
        Services.strategies.AiPromptStrategy strategy = 
            new Services.strategies.FollowupPromptStrategy(explanationContent, chatHistory, newQuestion, resourceType);
        return generateContentWithStrategy(strategy, userId, resourceId);
    }


    /**
     * Write token usage to database log.
     */
    private static void logUsage(
        Integer userId, 
        String resourceType, 
        int resourceId, 
        int totalTokens, 
        int thoughtsTokens
    ) {
        try (Connection conn = DbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO ai_usage_log (user_id, resource_type, resource_id, total_tokens, thoughts_tokens) VALUES (?, ?, ?, ?, ?)"
             )) {
            if (userId == null) {
                stmt.setNull(1, java.sql.Types.INTEGER);
            } else {
                stmt.setInt(1, userId);
            }
            stmt.setString(2, resourceType);
            stmt.setInt(3, resourceId);
            stmt.setInt(4, totalTokens);
            stmt.setInt(5, thoughtsTokens);
            stmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("Warning: Failed to log AI token usage: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
