import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * HTTP front for the engine. See GameService for the request and response format.
 *
 *   POST /api/move   play, analyse or replay a game (stateless: the request holds the whole game)
 *   GET  /           health check, also used by the web page to wake the host up
 */
public class WebServer {

    private static final int MAX_BODY_BYTES = 20_000;

    public static void main(String[] args) throws Exception {
        String portEnv = System.getenv("PORT");
        int port = (portEnv != null) ? Integer.parseInt(portEnv) : 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/move", new MoveHandler());
        server.createContext("/", new HealthHandler());
        server.setExecutor(null); // requests are handled one at a time
        server.start();
        System.out.println("Chess Engine API running on port " + port);
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void send(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String method = exchange.getRequestMethod();
            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(204, -1);
            } else if ("GET".equals(method) || "HEAD".equals(method)) {
                send(exchange, 200, "Chess engine API is running");
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        }
    }

    static class MoveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String method = exchange.getRequestMethod();

            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"POST".equals(method)) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            String requestBody = new String(exchange.getRequestBody().readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
            long start = System.currentTimeMillis();
            try {
                String response = GameService.respond(requestBody);
                System.out.println("request " + summarize(requestBody) + " -> "
                        + (System.currentTimeMillis() - start) + " ms");
                send(exchange, 200, response);
            } catch (GameService.BadRequest e) {
                send(exchange, 400, "ERROR|" + e.getMessage());
            } catch (RuntimeException e) {
                e.printStackTrace();
                send(exchange, 500, "ERROR|internal error");
            } finally {
                exchange.close();
            }
        }

        private static String summarize(String body) {
            String[] parts = body.trim().split("\\|", -1);
            int plies = parts.length > 1 && !parts[1].trim().isEmpty() ? parts[1].trim().split("\\s+").length : 0;
            return (parts.length > 0 ? parts[0] : "?") + " after " + plies + " plies";
        }
    }
}
