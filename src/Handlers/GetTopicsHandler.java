package Handlers;

import Services.TopicService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.OutputStream;
import java.util.List;

public class GetTopicsHandler
        implements HttpHandler {

        private static final String ALLOWED_ORIGIN = "https://cp-station.vercel.app";

    private TopicService topic_service =
            new TopicService();

    @Override
    public void handle(HttpExchange exchange) {

        try {

            List<String> topics =
                    topic_service.getAllTopics();

            String json = "[";

            for(int i=0;i<topics.size();i++) {

                json += "\"" +
                        topics.get(i) +
                        "\"";

                if(i < topics.size()-1) {
                    json += ",";
                }
            }

            json += "]";
        
             // cors important.. 
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
         // http status check 200 = ok..
         
            exchange.sendResponseHeaders(
                    200,
                    json.getBytes().length
            );

            OutputStream os =
                    exchange.getResponseBody();

            os.write(json.getBytes());

            os.close();

        } catch(Exception e) {

            try {

                String error =
                        "Server error: "
                        + e.getMessage();

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