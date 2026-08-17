package Handlers.decorators;

import Handlers.AbstractHttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * Decorator Pattern Implementation: HttpHandlerDecorator
 * 
 * Abstract decorator class that wraps an HttpHandler (or AbstractHttpHandler)
 * to attach dynamic behaviors (e.g. authentication, logging) without altering base logic.
 */
public abstract class HttpHandlerDecorator implements HttpHandler {

    protected final HttpHandler wrappedHandler;

    public HttpHandlerDecorator(HttpHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("Wrapped HttpHandler cannot be null.");
        }
        this.wrappedHandler = handler;
    }

    @Override
    public void handle(HttpExchange exchange) throws java.io.IOException {
        wrappedHandler.handle(exchange);
    }
}
