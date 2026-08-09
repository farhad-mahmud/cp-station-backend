package Handlers;

import Services.TopicByCatService;
import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import models.Topic;

public class GetTopicsByCategoryHandler extends AbstractHttpHandler {

    private TopicByCatService topicService = new TopicByCatService();

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        String query = exchange.getRequestURI().getQuery();
        String categoryId = null;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2 && pair[0].equals("categoryId")) {
                    categoryId = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
        }

        if (categoryId == null) {
            sendError(exchange, 400, "Missing categoryId");
            return;
        }

        List<Topic> topics = topicService.getTopicsByCategoryId(Integer.parseInt(categoryId));

        StringBuilder response = new StringBuilder("[");
        boolean first = true;

        for (Topic topic : topics) {
            if (!first) response.append(",");
            first = false;

            response.append("{")
                    .append("\"id\":").append(topic.id).append(",")
                    .append("\"name\":\"").append(topic.name).append("\",")
                    .append("\"sort_order\":").append(topic.sort_order).append(",")
                    .append("\"is_interview\":").append(topic.is_interview)
                    .append("}");
        }

        response.append("]");
        sendJSON(exchange, 200, response.toString());
    }
}