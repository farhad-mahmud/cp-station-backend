package Services;

import config.DbConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Facade Pattern Implementation: AiExplanationFacade
 * 
 * Provides a unified, high-level facade interface for AI explanation
 * operations,
 * encapsulating DB caching, resource/topic metadata queries, Gemini service
 * strategy calls,
 * and explanation persistence.
 */
public class AiExplanationFacade {

    public static class ResourceNotFoundException extends Exception {
        public ResourceNotFoundException(String message) {
            super(message);
        }
    }

    public static class InvalidResourceDataException extends Exception {
        public InvalidResourceDataException(String message) {
            super(message);
        }
    }

    /**
     * Checks database cache for an existing AI explanation.
     *
     * @param type Resource type ("solution", "topic", "subtopic")
     * @param id   Resource ID
     * @return Cached content string, or null if not cached
     */
    public String getCachedExplanation(String type, int id) throws Exception {
        try (Connection conn = DbConnection.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT content FROM ai_explanations WHERE resource_type = ? AND resource_id = ?")) {
            stmt.setString(1, type);
            stmt.setInt(2, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("content");
                }
            }
        }
        return null;
    }

    /**
     * Generates a new explanation via GeminiService and updates the database cache.
     *
     * @param type   Resource type ("solution", "topic", "subtopic")
     * @param id     Resource ID
     * @param userId Requesting user ID (or null)
     * @return Generated explanation content string
     */
    public String generateAndCacheExplanation(String type, int id, Integer userId) throws Exception {
        String explanation;

        try (Connection conn = DbConnection.getConnection()) {
            if ("solution".equals(type)) {
                String title = "";
                String code = "";
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT title, solution_code FROM resources WHERE id = ?")) {
                    stmt.setInt(1, id);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            title = rs.getString("title");
                            code = rs.getString("solution_code");
                        } else {
                            throw new ResourceNotFoundException("Resource not found");
                        }
                    }
                }

                if (code == null || code.trim().isEmpty()) {
                    throw new InvalidResourceDataException("No solution code is submitted for this problem yet.");
                }

                explanation = GeminiService.generateSolutionExplanation(title, code, userId, id);

            } else if ("topic".equals(type)) {
                String topicName = "";
                String categoryName = "";
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT t.name as topic_name, c.category_name as category_name " +
                                "FROM topics t " +
                                "LEFT JOIN categories c ON t.category_id = c.id " +
                                "WHERE t.id = ?")) {
                    stmt.setInt(1, id);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            topicName = rs.getString("topic_name");
                            categoryName = rs.getString("category_name");
                        } else {
                            throw new ResourceNotFoundException("Topic not found");
                        }
                    }
                }

                explanation = GeminiService.generateTopicExplanation(topicName, categoryName, "topic", userId, id);

            } else if ("subtopic".equals(type)) {
                String subtopicName = "";
                String topicName = "";
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT s.name as subtopic_name, t.name as topic_name " +
                                "FROM subtopics s " +
                                "LEFT JOIN topics t ON s.topic_id = t.id " +
                                "WHERE s.id = ?")) {
                    stmt.setInt(1, id);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            subtopicName = rs.getString("subtopic_name");
                            topicName = rs.getString("topic_name");
                        } else {
                            throw new ResourceNotFoundException("Subtopic not found");
                        }
                    }
                }

                explanation = GeminiService.generateTopicExplanation(subtopicName, topicName, "subtopic", userId, id);
            } else {
                throw new IllegalArgumentException("Invalid resource type: " + type);
            }
        }

        // Save generated explanation to DB cache
        saveCachedExplanation(type, id, explanation);

        return explanation;
    }

    /**
     * Helper method to upsert explanation into ai_explanations database table.
     */
    public void saveCachedExplanation(String type, int id, String content) throws Exception {
        try (Connection conn = DbConnection.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO ai_explanations (resource_type, resource_id, content, updated_at) " +
                                "VALUES (?, ?, ?, now()) " +
                                "ON CONFLICT (resource_type, resource_id) " +
                                "DO UPDATE SET content = EXCLUDED.content, updated_at = now()")) {
            stmt.setString(1, type);
            stmt.setInt(2, id);
            stmt.setString(3, content);
            stmt.executeUpdate();
        }
    }
}
