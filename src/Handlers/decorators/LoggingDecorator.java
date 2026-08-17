package Handlers.decorators;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/**
 * Decorator Pattern Implementation: LoggingDecorator
 * 
 * Intercepts incoming HTTP requests to log request metadata (method, URI, execution duration).
 */
public class LoggingDecorator extends HttpHandlerDecorator {

    public LoggingDecorator(HttpHandler handler) {
        super(handler);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long startTime = System.currentTimeMillis();
        String method = exchange.getRequestMethod();
        String uri = exchange.getRequestURI().toString();

        try {
            wrappedHandler.handle(exchange);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("[HTTP LOG] " + method + " " + uri + " - Completed in " + duration + " ms");
        }
    }
}
