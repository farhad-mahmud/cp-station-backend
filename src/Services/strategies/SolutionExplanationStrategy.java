package Services.strategies;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Strategy Pattern Implementation: SolutionExplanationStrategy
 * Encapsulates the prompt construction strategy for explaining competitive
 * programming problem solution code.
 */
public class SolutionExplanationStrategy implements AiPromptStrategy {

    private final String problemTitle;
    private final String code;

    // constructor..

    public SolutionExplanationStrategy(String problemTitle, String code) {
        this.problemTitle = problemTitle;
        this.code = code;
    }

    @Override
    public ObjectNode buildRequestBody(ObjectMapper mapper) {
        ObjectNode requestBody = mapper.createObjectNode();
        ArrayNode contents = requestBody.putArray("contents");

        ObjectNode userTurn = contents.addObject();
        userTurn.put("role", "user");
        ArrayNode parts = userTurn.putArray("parts");

        String prompt = "You are an expert competitive programmer and computer science teacher. Explain the following competitive programming problem solution. Keep it clear, concise, and structured in clean Markdown.\n\n"
                +
                "Problem Title: " + problemTitle + "\n" +
                "Solution Code:\n" + code + "\n\n" +
                "Provide a detailed explanation of:\n" +
                "1. The approach used.\n" +
                "2. Why it works.\n" +
                "3. The time and space complexity.\n" +
                "4. The key insights or techniques.\n";

        parts.addObject().put("text", prompt);
        return requestBody;
    }

    @Override
    public String getResourceType() {
        return "solution";
    }
}
