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
     * Explains the solution code for a problem.
     */
    public static String generateSolutionExplanation(
        String problemTitle, 
        String code, 
        Integer userId, 
        int resourceId
    ) throws Exception {
        ObjectNode requestBody = mapper.createObjectNode();
        ArrayNode contents = requestBody.putArray("contents");
        
        ObjectNode userTurn = contents.addObject();
        userTurn.put("role", "user");
        ArrayNode parts = userTurn.putArray("parts");
        
        String prompt = "You are an expert competitive programmer and computer science teacher. Explain the following competitive programming problem solution. Keep it clear, concise, and structured in clean Markdown.\n\n" +
            "Problem Title: " + problemTitle + "\n" +
            "Solution Code:\n" + code + "\n\n" +
            "Provide a detailed explanation of:\n" +
            "1. The approach used.\n" +
            "2. Why it works.\n" +
            "3. The time and space complexity.\n" +
            "4. The key insights or techniques.\n";
            
        parts.addObject().put("text", prompt);

        return generateContent(requestBody, userId, "solution", resourceId);
    }

    /**
     * Explains a CP topic or subtopic from general knowledge.
     */
    public static String generateTopicExplanation(
        String name, 
        String categoryOrParentName, 
        String resourceType, 
        Integer userId, 
        int resourceId
    ) throws Exception {
        ObjectNode requestBody = mapper.createObjectNode();
        ArrayNode contents = requestBody.putArray("contents");
        
        ObjectNode userTurn = contents.addObject();
        userTurn.put("role", "user");
        ArrayNode parts = userTurn.putArray("parts");

        String prompt = "You are an expert competitive programmer and computer science teacher. Explain the competitive programming topic: " + name + 
            " (Context: " + (categoryOrParentName != null ? categoryOrParentName : "General Competitive Programming") + ").\n\n" +
            "Provide:\n" +
            "1. The basic concepts and definitions.\n" +
            "2. How and why it is used in competitive programming.\n" +
            "3. Standard examples or use cases.\n" +
            "4. Complexity analysis of standard operations.\n\n" +
            "Keep it structured in beautiful Markdown with code examples where appropriate.";

        parts.addObject().put("text", prompt);

        return generateContent(requestBody, userId, resourceType, resourceId);
    }

    /**
     * Generate multi-turn followup Q&A response.
     */
    public static String generateFollowupResponse(
        String explanationContent, 
        List<Map<String, String>> chatHistory, 
        String newQuestion, 
        Integer userId, 
        String resourceType, 
        int resourceId
    ) throws Exception {
        ObjectNode requestBody = mapper.createObjectNode();
        ArrayNode contents = requestBody.putArray("contents");

        // We build the multi-turn contents.
        // First message acts as user introducing the cached explanation context and the first user question or starts the chat.
        // To be extremely clean and robust, we can prepend the explanation as system instruction context or inside the first user turn.
        // Let's prepend it to the systemInstruction if possible, and fallback to user turn prepending if not.
        // But systemInstruction is extremely clean:
        ObjectNode systemInstruction = requestBody.putObject("systemInstruction");
        ArrayNode systemParts = systemInstruction.putArray("parts");
        systemParts.addObject().put("text", 
            "You are an expert competitive programmer and AI assistant. The user is reading a cached explanation they have requested. " +
            "Here is the cached explanation for context:\n\n" +
            explanationContent + "\n\n" +
            "Help the user by answering clarifying questions about this explanation, their code, or the concepts. " +
            "Keep answers concise, direct, helpful, and formatted in Markdown."
        );

        // Populate contents from chat history
        for (int i = 0; i < chatHistory.size(); i++) {
            Map<String, String> msg = chatHistory.get(i);
            String dbRole = msg.get("role");
            String content = msg.get("content");

            ObjectNode turn = contents.addObject();
            // Gemini roles must be 'user' or 'model'
            turn.put("role", dbRole.equalsIgnoreCase("model") || dbRole.equalsIgnoreCase("assistant") ? "model" : "user");
            ArrayNode parts = turn.putArray("parts");
            parts.addObject().put("text", content);
        }

        // Add current question
        ObjectNode currentTurn = contents.addObject();
        currentTurn.put("role", "user");
        ArrayNode currentParts = currentTurn.putArray("parts");
        currentParts.addObject().put("text", newQuestion);

        return generateContent(requestBody, userId, resourceType, resourceId);
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
