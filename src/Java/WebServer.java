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

        // Keep persistent instances of your board and engine here
        // (or manage state per game if you have a session manager)
        private static Bitboard board = new Bitboard();
        private static ChessEngine engine = new ChessEngine();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 1. Handle CORS (Allow frontend to communicate with Render backend)
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("POST".equals(exchange.getRequestMethod())) {
                // 2. Read the raw request body string (e.g. "CPU|RESET|BLACK" or
                // "CPU|e2e4|WHITE")
                InputStream is = exchange.getRequestBody();
                String requestBody = new String(is.readAllBytes());

                // 3. Parse the pipe-delimited payload
                String[] parts = requestBody.split("\\|");
                String mode = parts[0]; // "CPU" or "PVP"
                String move = parts[1]; // "e2e4" or "RESET"
                String humanColor = parts[2]; // "WHITE" or "BLACK"

                String responseText = "";

                // 4. Game Logic Processing
                if ("RESET".equals(move)) {
                    // Reset internal board state
                    board.resetToStartingPosition();

                    if ("CPU".equals(mode) && "BLACK".equals(humanColor)) {
                        // Human is Black -> CPU moves 1st as White
                        String engineMove = engine.getBestMove(board);
                        board.makeMove(engineMove);

                        String fen = board.toFen();
                        String legalMoves = board.getLegalMoves(); // Black's legal responses
                        String turn = "Black";

                        responseText = fen + "|" + engineMove + "|" + legalMoves + "|" + turn;
                    } else {
                        // Human is White or PVP -> Human goes 1st
                        String fen = board.toFen();
                        String legalMoves = board.getLegalMoves(); // White's starting legal moves
                        String turn = "White";

                        responseText = fen + "||" + legalMoves + "|" + turn;
                    }
                } else {
                    // Apply human's move
                    board.makeMove(move);

                    if ("CPU".equals(mode) && !board.isGameOver()) {
                        // Engine counter-move
                        String engineMove = engine.getBestMove(board);
                        board.makeMove(engineMove);

                        String fen = board.toFen();
                        String legalMoves = board.getLegalMoves(); // Human's legal moves for next turn
                        String activeTurn = board.getTurn(); // "White" or "Black"

                        responseText = fen + "|" + engineMove + "|" + legalMoves + "|" + activeTurn;
                    } else {
                        // PVP Mode or Game Over
                        String fen = board.toFen();
                        String legalMoves = board.getLegalMoves();
                        String activeTurn = board.getTurn();

                        responseText = fen + "|" + move + "|" + legalMoves + "|" + activeTurn;
                    }
                }

                // 5. Send response back to frontend
                byte[] responseBytes = responseText.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, responseBytes.length);

                OutputStream os = exchange.getResponseBody();
                os.write(responseBytes);
                os.close();
            } else {
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            }
        }
    }
}