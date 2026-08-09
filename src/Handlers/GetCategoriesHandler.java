package Handlers;

import Services.CategoryService;
import com.sun.net.httpserver.HttpExchange;
import java.util.List;
import models.Category;

public class GetCategoriesHandler extends AbstractHttpHandler {

    private CategoryService service = new CategoryService();

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        List<Category> categories = service.getAllCategories();

        StringBuilder response = new StringBuilder("[");
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
        sendJSON(exchange, 200, response.toString());
    }
}