package Handlers.decorators;

import auth.SessionUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Decorator Pattern Implementation: AuthDecorator
 * 
 * Intercepts incoming HTTP requests, validates session cookies/JWT tokens using SessionUtil,
 * injects user session details into exchange attributes, and enforces authentication.
 */
public class AuthDecorator extends HttpHandlerDecorator {

    private final boolean required;

    public AuthDecorator(HttpHandler handler) {
        this(handler, true);
    }

    public AuthDecorator(HttpHandler handler, boolean required) {
        super(handler);
        this.required = required;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        if ("OPTIONS".equals(method)) {
            wrappedHandler.handle(exchange);
            return;
        }

        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String token = SessionUtil.extractTokenFromCookies(cookieHeader);
        Integer userId = SessionUtil.getUserIdFromToken(token);
        String role = SessionUtil.getRoleFromToken(token);

        if (userId != null) {
            exchange.setAttribute("userId", userId);
            if (role != null) {
                exchange.setAttribute("role", role);
            }
        } else if (required) {
            sendUnauthorizedResponse(exchange, "Unauthorized: Logged-in session required.");
            return;
        }

        wrappedHandler.handle(exchange);
    }

    private void sendUnauthorizedResponse(HttpExchange exchange, String message) throws IOException {
        String jsonError = "{\"error\":\"" + message + "\"}";
        byte[] bytes = jsonError.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.trim().isEmpty()) {
            origin = "https://cp-station.vercel.app";
        }
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");

        exchange.sendResponseHeaders(401, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
