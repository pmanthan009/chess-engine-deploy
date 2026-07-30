import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.List;

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
        
        private static Board board = new Board();
        private static MoveGen moveGen = new MoveGen();
        private static boolean isWhiteTurn = true;

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
                String requestBody = new String(is.readAllBytes());

                // Payload structure: "CPU|RESET|BLACK" or "CPU|e2e4|WHITE"
                String[] parts = requestBody.split("\\|");
                String mode = parts[0];
                String moveStr = parts[1];
                String humanColor = parts[2];

                String responseText = "";

                if ("RESET".equals(moveStr)) {
                    board = new Board(); // Reset board state
                    isWhiteTurn = true;

                    List<Move> legalMoves = moveGen.getLegalMoves(board, true);
                    String legalStr = formatLegalMoves(legalMoves);
                    String fen = board.toFEN();

                    responseText = fen + "||" + legalStr + "|White";
                } else {
                    // Parse human move (e.g. "e2e4")
                    Move playedMove = parseMoveString(moveStr, moveGen.getLegalMoves(board, isWhiteTurn));

                    if (playedMove != null) {
                        board.makeMove(playedMove, isWhiteTurn);
                        isWhiteTurn = !isWhiteTurn; // Switch turn
                    }

                    List<Move> legalMoves = moveGen.getLegalMoves(board, isWhiteTurn);
                    String legalStr = formatLegalMoves(legalMoves);
                    String fen = board.toFEN();
                    String turnStr = isWhiteTurn ? "White" : "Black";

                    responseText = fen + "|" + moveStr + "|" + legalStr + "|" + turnStr;
                }

                // Send Response
                byte[] responseBytes = responseText.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, responseBytes.length);

                OutputStream os = exchange.getResponseBody();
                os.write(responseBytes);
                os.close();
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }

        // --- HELPER METHODS ---

        // Parses a 4-character move string like "e2e4" into a Move object
        private static Move parseMoveString(String moveStr, List<Move> legalMoves) {
            if (moveStr == null || moveStr.length() < 4)
                return null;

            int startSq = (moveStr.charAt(1) - '1') * 8 + (moveStr.charAt(0) - 'a');
            int targetSq = (moveStr.charAt(3) - '1') * 8 + (moveStr.charAt(2) - 'a');

            for (Move m : legalMoves) {
                if (m.startSquare == startSq && m.targetSquare == targetSq) {
                    return m;
                }
            }
            return new Move(startSq, targetSq);
        }

        // Converts list of Move objects to comma-separated algebraic strings
        // ("e2e4,d2d4")
        private static String formatLegalMoves(List<Move> moves) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < moves.size(); i++) {
                Move m = moves.get(i);
                sb.append((char) ('a' + (m.startSquare % 8)))
                        .append((char) ('1' + (m.startSquare / 8)))
                        .append((char) ('a' + (m.targetSquare % 8)))
                        .append((char) ('1' + (m.targetSquare / 8)));
                if (i < moves.size() - 1)
                    sb.append(",");
            }
            return sb.toString();
        }
    }
}