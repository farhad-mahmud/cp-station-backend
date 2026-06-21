package auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.OutputStream;
import java.util.Map;

public class MeHandler implements HttpHandler {

    private static final ObjectMapper mapper = new ObjectMapper();

    public void handle(HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "http://localhost:3000");
            exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // Read the raw Cookie header sent by the browser
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            System.out.println("Cookie header received: " + cookieHeader);

            String token = SessionUtil.extractTokenFromCookies(cookieHeader);
            String role = SessionUtil.getRoleFromToken(token);

            byte[] bytes;
            if (role == null) {
                Map<String, Object> response = Map.of("loggedIn", false);
                bytes = mapper.writeValueAsBytes(response);
            } else {
                Map<String, Object> response = Map.of("loggedIn", true, "role", role);
                bytes = mapper.writeValueAsBytes(response);
            }

            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();

        } catch (Exception e) {
            e.printStackTrace();
            try {
                String error = "{\"error\":\"Server error\"}";
                exchange.sendResponseHeaders(500, error.length());
                OutputStream os = exchange.getResponseBody();
                os.write(error.getBytes());
                os.close();
            } catch (Exception ignored) {}
        }
    }
}