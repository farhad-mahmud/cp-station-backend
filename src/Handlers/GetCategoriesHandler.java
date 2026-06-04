package Handlers;

import Services.CategoryService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.OutputStream;
import java.util.List;
import models.Category;

public class GetCategoriesHandler implements HttpHandler {

    private CategoryService service =
            new CategoryService();

    @Override
    public void handle(HttpExchange exchange) {

        try {

            List<Category> categories =
                    service.getAllCategories();

            StringBuilder response =
                    new StringBuilder("[");

            boolean first = true;

            for (Category c : categories) {

                if (!first) response.append(",");
                first = false;

                response.append("{")
                        .append("\"id\":").append(c.id).append(",")
                        .append("\"category_name\":\"")
                        .append(c.categoryName)
                        .append("\"")
                        .append("}");
            }

            response.append("]");

            exchange.getResponseHeaders()
                    .add("Access-Control-Allow-Origin", "*");

            exchange.getResponseHeaders()
                    .set("Content-Type", "application/json");

            exchange.sendResponseHeaders(
                    200,
                    response.toString().getBytes().length
            );

            OutputStream os =
                    exchange.getResponseBody();

            os.write(response.toString().getBytes());
            os.close();

        } catch (Exception e) {
            e.printStackTrace();

            try {
                String error =
                        "Server error: " + e.getMessage();

                exchange.sendResponseHeaders(
                        500,
                        error.getBytes().length
                );

                exchange.getResponseBody()
                        .write(error.getBytes());

                exchange.getResponseBody().close();

            } catch (Exception ignored) {}
        }
    }
}