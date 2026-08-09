package Handlers;

import Services.TopicService;
import com.sun.net.httpserver.HttpExchange;
import java.util.List;

public class GetTopicsHandler extends AbstractHttpHandler {

    private TopicService topic_service = new TopicService();

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        List<String> topics = topic_service.getAllTopics();

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < topics.size(); i++) {
            json.append("\"").append(topics.get(i)).append("\"");
            if (i < topics.size() - 1) {
                json.append(",");
            }
        }
        json.append("]");

        sendJSON(exchange, 200, json.toString());
    }
}