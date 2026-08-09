package auth;

import Handlers.AbstractHttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.util.Map;

public class MeHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        System.out.println("Cookie header received: " + cookieHeader);

        String token = SessionUtil.extractTokenFromCookies(cookieHeader);
        String role = SessionUtil.getRoleFromToken(token);

        if (role == null) {
            sendJSON(exchange, 200, Map.of("loggedIn", false));
        } else {
            sendJSON(exchange, 200, Map.of("loggedIn", true, "role", role));
        }
    }
}