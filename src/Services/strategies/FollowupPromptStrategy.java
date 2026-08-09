package Services.strategies;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Strategy Pattern Implementation: FollowupPromptStrategy
 * Encapsulates multi-turn AI followup chat prompt generation.
 */
public class FollowupPromptStrategy implements AiPromptStrategy {

    private final String explanationContent;
    private final List<Map<String, String>> chatHistory;
    private final String newQuestion;
    private final String resourceType;

    public FollowupPromptStrategy(
        String explanationContent, 
        List<Map<String, String>> chatHistory, 
        String newQuestion, 
        String resourceType
    ) {
        this.explanationContent = explanationContent;
        this.chatHistory = chatHistory;
        this.newQuestion = newQuestion;
        this.resourceType = resourceType;
    }

    @Override
    public ObjectNode buildRequestBody(ObjectMapper mapper) {
        ObjectNode requestBody = mapper.createObjectNode();
        ArrayNode contents = requestBody.putArray("contents");

        ObjectNode systemInstruction = requestBody.putObject("systemInstruction");
        ArrayNode systemParts = systemInstruction.putArray("parts");
        systemParts.addObject().put("text", 
            "You are an expert competitive programmer and AI assistant. The user is reading a cached explanation they have requested. " +
            "Here is the cached explanation for context:\n\n" +
            explanationContent + "\n\n" +
            "Help the user by answering clarifying questions about this explanation, their code, or the concepts. " +
            "Keep answers concise, direct, helpful, and formatted in Markdown."
        );

        for (Map<String, String> msg : chatHistory) {
            String dbRole = msg.get("role");
            String content = msg.get("content");

            ObjectNode turn = contents.addObject();
            turn.put("role", dbRole.equalsIgnoreCase("model") || dbRole.equalsIgnoreCase("assistant") ? "model" : "user");
            ArrayNode parts = turn.putArray("parts");
            parts.addObject().put("text", content);
        }

        ObjectNode currentTurn = contents.addObject();
        currentTurn.put("role", "user");
        ArrayNode currentParts = currentTurn.putArray("parts");
        currentParts.addObject().put("text", newQuestion);

        return requestBody;
    }

    @Override
    public String getResourceType() {
        return resourceType;
    }
}
