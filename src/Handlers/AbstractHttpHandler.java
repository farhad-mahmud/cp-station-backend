package Handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Template Method Pattern Implementation: AbstractHttpHandler
 * 
 * Defines the standard skeleton execution algorithm for handling HTTP requests:
 * 1. Configures CORS headers
 * 2. Handles preflight OPTIONS requests automatically
 * 3. Delegates execution to processRequest(exchange) [Template Step]
 * 4. Provides centralized 500 error catch-all handling
 */
public abstract class AbstractHttpHandler implements HttpHandler {

    protected static final String DEFAULT_ALLOWED_ORIGIN = "https://cp-station.vercel.app";
    protected static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public final void handle(HttpExchange exchange) {
        try {
            setCorsHeaders(exchange);

            String method = exchange.getRequestMethod().toUpperCase();
            if ("OPTIONS".equals(method)) {
                handleOptions(exchange);
                return;
            }

            processRequest(exchange);

        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Internal server error");
        }
    }

    /**
     * Primitive / Hook method to be implemented by concrete HTTP Handler subclasses.
     * Contains the core endpoint logic.
     *
     * @param exchange The HttpExchange object representing the request/response lifecycle.
     * @throws Exception Any unhandled exception will be caught by the template algorithm and responded with HTTP 500.
     */
    
    protected abstract void processRequest(HttpExchange exchange) throws Exception;

    /**
     * Template Step 1: Configures default CORS and Content-Type headers.
     */
    protected void setCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.trim().isEmpty()) {
            origin = DEFAULT_ALLOWED_ORIGIN;
        }

        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization, Cookie");
        exchange.getResponseHeaders().set("Content-Type", "application/json");
    }

    /**
     * Template Step 2: Responds to CORS preflight OPTIONS requests.
     */
    protected void handleOptions(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization, Cookie");
        exchange.sendResponseHeaders(204, -1);
    }

    /**
     * Helper method to read request body UTF-8 String.
     */
    protected String readBody(HttpExchange exchange) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * Helper method to write JSON responses.
     */
    protected void sendJSON(HttpExchange exchange, int statusCode, Object data) throws IOException {
        byte[] bytes;
        if (data instanceof String) {
            bytes = ((String) data).getBytes(StandardCharsets.UTF_8);
        } else {
            bytes = mapper.writeValueAsBytes(data);
        }
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Helper method to return JSON error response.
     */
    protected void sendError(HttpExchange exchange, int statusCode, String message) {
        try {
            Map<String, String> error = Map.of("error", message);
            sendJSON(exchange, statusCode, error);
        } catch (Exception ignored) {}
    }
}
