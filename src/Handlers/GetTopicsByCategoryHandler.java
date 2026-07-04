package Handlers;

import Services.TopicByCatService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import models.Topic;

public class GetTopicsByCategoryHandler
        implements HttpHandler {

        private static final String ALLOWED_ORIGIN = "https://cp-station.vercel.app";

    private TopicByCatService topicService =
            new TopicByCatService ();

    @Override
    public void handle(HttpExchange exchange) {

        try {

            exchange.getResponseHeaders()
                    .add(
                        "Access-Control-Allow-Origin",
                        ALLOWED_ORIGIN
                    );

            exchange.getResponseHeaders()
                    .add("Access-Control-Allow-Credentials", "true");

            exchange.getResponseHeaders()
                    .set(
                        "Content-Type",
                        "application/json"
                    );

                   
            String query =
                    exchange.getRequestURI()
                            .getQuery();

            String categoryId = null;

            if(query != null) {

                for(String param :
                        query.split("&")) {

                    String[] pair =
                            param.split("=");

                    if(pair.length == 2 &&
                       pair[0].equals("categoryId")) {

                        categoryId =
                                URLDecoder.decode(
                                        pair[1],
                                        StandardCharsets.UTF_8
                                );
                    }
                }
            }

            if(categoryId == null) {

                String error =
                        "{\"error\":\"Missing categoryId\"}";

                exchange.getResponseHeaders()
                        .add("Access-Control-Allow-Origin", ALLOWED_ORIGIN);

                exchange.getResponseHeaders()
                        .add("Access-Control-Allow-Credentials", "true");

                exchange.sendResponseHeaders(
                        400,
                        error.getBytes().length
                );

                exchange.getResponseBody()
                        .write(error.getBytes());

                exchange.getResponseBody()
                        .close();

                return;
            }

            List<Topic> topics =
                    topicService.getTopicsByCategoryId(
                            Integer.parseInt(categoryId)
                    );

            StringBuilder response =
                    new StringBuilder("[");

            boolean first = true;

            for(Topic topic : topics) {

                if(!first)
                    response.append(",");

                first = false;

                response.append("{")
                        .append("\"id\":")
                        .append(topic.id)
                        .append(",")

                        .append("\"name\":\"")
                        .append(topic.name)
                        .append("\",")

                        .append("\"sort_order\":")
                        .append(topic.sort_order)
                        .append(",")

                        .append("\"is_interview\":")
                        .append(topic.is_interview)

                        .append("}");
            }

            response.append("]");

            exchange.sendResponseHeaders(
                    200,
                    response.toString()
                            .getBytes()
                            .length
            );

            OutputStream os =
                    exchange.getResponseBody();

            os.write(
                    response.toString().getBytes()
            );

            os.close();

        } catch(Exception e) {

            e.printStackTrace();

            try {

                String error =
                        "{\"error\":\""
                        + e.getMessage()
                        + "\"}";

                exchange.getResponseHeaders()
                        .add("Access-Control-Allow-Origin", ALLOWED_ORIGIN);

                exchange.getResponseHeaders()
                        .add("Access-Control-Allow-Credentials", "true");

                exchange.sendResponseHeaders(
                        500,
                        error.getBytes().length
                );

                exchange.getResponseBody()
                        .write(error.getBytes());

                exchange.getResponseBody()
                        .close();

            } catch(Exception ignored) {}
        }
    }
}