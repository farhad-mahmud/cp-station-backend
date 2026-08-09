package auth;

import Handlers.AbstractHttpHandler;
import com.sun.net.httpserver.HttpExchange;
import config.DbConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;

public class LogoutHandler extends AbstractHttpHandler {

    @Override
    protected void processRequest(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String token = SessionUtil.extractTokenFromCookies(cookieHeader);

        if (token != null) {
            Connection conn = DbConnection.getConnection();
            PreparedStatement stmt = conn.prepareStatement("DELETE FROM sessions WHERE token = ?");
            stmt.setString(1, token);
            stmt.executeUpdate();
            conn.close();
        }

        String expiredCookie = "session_token=; HttpOnly; Path=/; Max-Age=0; SameSite=None; Secure";
        exchange.getResponseHeaders().add("Set-Cookie", expiredCookie);

        sendJSON(exchange, 200, Map.of("success", true));
    }
}

