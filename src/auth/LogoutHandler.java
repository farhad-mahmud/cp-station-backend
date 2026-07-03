package auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import config.DbConnection;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;

public class LogoutHandler implements HttpHandler {
    private static final String ALLOWED_ORIGIN = "https://cp-station.vercel.app";
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
            exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

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

            String expiredCookie = "session_token=; HttpOnly; Path=/; Max-Age=0; SameSite=Lax";
            exchange.getResponseHeaders().add("Set-Cookie", expiredCookie);

            sendJSON(exchange, 200, Map.of("success", true));

        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Server error: " + e.getMessage());
        }
    }

    private void sendJSON(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(data);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) {
        try {
            Map<String, String> error = Map.of("error", message);
            sendJSON(exchange, statusCode, error);
        } catch (Exception ignored) {}
    }
}
