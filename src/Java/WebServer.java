import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;

public class WebServer {

    public static void main(String[] args) throws Exception {
        String portEnv = System.getenv("PORT");
        int port = (portEnv != null) ? Integer.parseInt(portEnv) : 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/move", new MoveHandler());
        server.setExecutor(null); 
        server.start();
        System.out.println("Chess Engine API running on port " + port);
    }

    static class MoveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 1. CORS Headers
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("POST".equals(exchange.getRequestMethod())) {
                InputStream is = exchange.getRequestBody();
                String requestBody = new String(is.readAllBytes()); // e.g., "CPU|RESET|WHITE"

                // ==============================================================
                // TODO: Pass 'requestBody' to your Bitboard Engine
                // Parse mode, move, and player color (e.g. split by "|")
                // ==============================================================

                // Dummy response matching the format expected by Board.html:
                // Format: FEN|LAST_MOVE|LEGAL_MOVES|TURN
                String fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";
                String lastMove = "e2e4"; 
                String legalMoves = "e7e5,e7e6,g8f6,b8c6"; // Comma-separated valid moves
                String turn = "White";

                String responseText = fen + "|" + lastMove + "|" + legalMoves + "|" + turn;

                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, responseText.getBytes().length);
                
                OutputStream os = exchange.getResponseBody();
                os.write(responseText.getBytes());
                os.close();
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }
}