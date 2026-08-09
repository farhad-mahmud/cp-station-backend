package Services.strategies;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Strategy Pattern Interface: AiPromptStrategy
 * Defines the contract for dynamic AI prompt generation algorithms.
 */
public interface AiPromptStrategy {

    /**
     * Constructs the specific JSON request body for the Gemini API call.
     *
     * @param mapper Jackson ObjectMapper instance
     * @return ObjectNode containing formatted request payload
     */
    ObjectNode buildRequestBody(ObjectMapper mapper);

    /**
     * Gets the associated resource type identifier (e.g. 'solution', 'topic',
     * 'subtopic').
     *
     * @return String resource type
     */
    String getResourceType();
}
