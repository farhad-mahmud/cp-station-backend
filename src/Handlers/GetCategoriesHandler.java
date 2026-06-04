package Handlers;

import Services.CategoryService;
import Services.CategoryService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.OutputStream;
import java.util.List;

public class GetCategoriesHandler
        implements HttpHandler {

    private CategoryService cat_service =
            new CategoryService();

    @Override
    public void handle(HttpExchange exchange) {

        try {

            List<String> topics =
                    cat_service.getAllTopics();

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
        
             // Cors important.. 
            exchange.getResponseHeaders()
                    .add(
                        "Access-Control-Allow-Origin",
                        "*"
                    );

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