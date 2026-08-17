package Services.strategies;

import java.util.List;
import java.util.Map;

/**
 * Factory Method Pattern Implementation: PromptStrategyFactory
 * 
 * Centralizes and encapsulates the creation logic of concrete AiPromptStrategy instances
 * for different AI generation modes (solutions, topics/subtopics, followups).
 */
public class PromptStrategyFactory {

    /**
     * Factory method for creating a SolutionExplanationStrategy.
     */
    public static AiPromptStrategy createSolutionStrategy(String problemTitle, String code) {
        return new SolutionExplanationStrategy(problemTitle, code);
    }

    /**
     * Factory method for creating a TopicExplanationStrategy.
     */
    public static AiPromptStrategy createTopicStrategy(String name, String categoryOrParentName, String resourceType) {
        return new TopicExplanationStrategy(name, categoryOrParentName, resourceType);
    }

    /**
     * Factory method for creating a FollowupPromptStrategy.
     */
    public static AiPromptStrategy createFollowupStrategy(
            String explanationContent,
            List<Map<String, String>> chatHistory,
            String newQuestion,
            String resourceType) {
        return new FollowupPromptStrategy(explanationContent, chatHistory, newQuestion, resourceType);
    }

    /**
     * Dynamic Factory Method: Instantiates an AiPromptStrategy based on the resourceType identifier.
     * Useful for dynamic strategy creation from request payloads.
     */
    @SuppressWarnings("unchecked")
    public static AiPromptStrategy createStrategy(String resourceType, Map<String, Object> params) {
        if (resourceType == null) {
            throw new IllegalArgumentException("resourceType cannot be null for PromptStrategyFactory.");
        }

        switch (resourceType.toLowerCase()) {
            case "solution":
                return createSolutionStrategy(
                    (String) params.get("problemTitle"), 
                    (String) params.get("code")
                );
            case "topic":
            case "subtopic":
                return createTopicStrategy(
                    (String) params.get("name"), 
                    (String) params.get("categoryOrParentName"), 
                    resourceType
                );
            case "followup":
                return createFollowupStrategy(
                    (String) params.get("explanationContent"), 
                    (List<Map<String, String>>) params.get("chatHistory"), 
                    (String) params.get("newQuestion"), 
                    resourceType
                );
            default:
                throw new IllegalArgumentException("Unsupported AI strategy resourceType: " + resourceType);
        }
    }
}
