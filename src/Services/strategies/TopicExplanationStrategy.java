package Services.strategies;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Strategy Pattern Implementation: TopicExplanationStrategy
 * Encapsulates prompt generation for topics and subtopics.
 */
public class TopicExplanationStrategy implements AiPromptStrategy {

    private final String name;
    private final String categoryOrParentName;
    private final String resourceType;

    public TopicExplanationStrategy(String name, String categoryOrParentName, String resourceType) {
        this.name = name;
        this.categoryOrParentName = categoryOrParentName;
        this.resourceType = resourceType;
    }

    @Override
    public ObjectNode buildRequestBody(ObjectMapper mapper) {
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
        return requestBody;
    }

    @Override
    public String getResourceType() {
        return resourceType;
    }
}
