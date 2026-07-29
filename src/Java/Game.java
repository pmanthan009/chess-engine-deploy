import java.util.List;
import java.util.Scanner;

public class Game {

    private Board board;
    private Search ai;
    private MoveGen generator;

    public Game() {
        this.board = new Board();
        this.ai = new Search();
        this.generator = new MoveGen();
    }

    /**
     * Play a game against your engine!
     * 
     * @param aiDepth      How many moves ahead the AI should look (3 or 4 is
     *                     recommended)
     * @param humanIsWhite True if you want to play as White
     */
    public void playHumanVsAI(int aiDepth, boolean humanIsWhite) {
        Scanner scanner = new Scanner(System.in);
        boolean isWhiteTurn = true;
        int moveNumber = 1;

        System.out.println("--- STARTING HUMAN VS ENGINE (Engine Depth " + aiDepth + ") ---");
        System.out.println("Type your moves in standard format: 'e2e4', 'g1f3', etc.");
        if (humanIsWhite)
            System.out.println("You are playing White.");
        else
            System.out.println("You are playing Black.");

        while (true) {
            System.out.println("\nMove " + moveNumber + "(Depth:" + aiDepth + ")" + " - " + (isWhiteTurn ? "White" : "Black") + " to play:");
            printBoard(board);

            // 1. Generate Legal Moves & Check for Game Over
            List<Move> legalMoves = generator.getLegalMoves(board, isWhiteTurn);
            if (legalMoves.isEmpty()) {
                long kingBoard = isWhiteTurn ? board.whiteKing : board.blackKing;
                int kingSquare = Long.numberOfTrailingZeros(kingBoard);

                if (generator.isSquareAttacked(kingSquare, !isWhiteTurn, board)) {
                    System.out.println("\nCHECKMATE! " + (isWhiteTurn ? "Black" : "White") + " wins!");
                } else {
                    System.out.println("\nSTALEMATE! It's a draw.");
                }
                break;
            }

            if (isWhiteTurn == humanIsWhite) {
                // --- HUMAN TURN ---
                Move chosenMove = null;
                while (chosenMove == null) {
                    System.out.print("Enter move: ");
                    String input = scanner.nextLine().trim().toLowerCase();

                    // Parse the input (e.g., "e2e4")
                    if (input.length() >= 4) {
                        int startSquare = parseSquare(input.substring(0, 2));
                        int targetSquare = parseSquare(input.substring(2, 4));

                        // Find the matching move in the strictly legal list
                        for (Move m : legalMoves) {
                            if (m.startSquare == startSquare && m.targetSquare == targetSquare) {
                                // Basic promotion handling (defaults to Queen if you don't type 'q')
                                if (m.promotedPiece != 0) {
                                    int requestedPiece = 5; // Queen default
                                    if (input.length() == 5) {
                                        char p = input.charAt(4);
                                        if (p == 'r')
                                            requestedPiece = 4;
                                        else if (p == 'b')
                                            requestedPiece = 3;
                                        else if (p == 'n')
                                            requestedPiece = 2;
                                    }
                                    if (m.promotedPiece == requestedPiece) {
                                        chosenMove = m;
                                        break;
                                    }
                                } else {
                                    chosenMove = m;
                                    break;
                                }
                            }
                        }
                    }

                    if (chosenMove == null) {
                        System.out.println("Invalid or illegal move! Please try again.");
                    }
                }

                board.makeMove(chosenMove, isWhiteTurn);

            } else {
                // --- AI TURN ---
                System.out.println("Engine running...");
                long startTime = System.currentTimeMillis();
                Move bestMove = ai.getBestMove(board, aiDepth, isWhiteTurn);
                long endTime = System.currentTimeMillis();

                if (bestMove == null) {
                    System.out.println("ERROR: Engine returned null move.");
                    break;
                }

                System.out.println(
                        "Engine played: " + squareToString(bestMove.startSquare) + squareToString(bestMove.targetSquare) +
                                " (Took " + (endTime - startTime) + "ms)");
                board.makeMove(bestMove, isWhiteTurn);
            }

            // Flip turns
            isWhiteTurn = !isWhiteTurn;
            if (isWhiteTurn)
                moveNumber++;
        }
        scanner.close();
    }

    // --- HELPER METHODS ---

    /**
     * Converts a string like "e2" into a board index (12).
     */
    private int parseSquare(String sq) {
        int file = sq.charAt(0) - 'a';
        int rank = sq.charAt(1) - '1';
        return rank * 8 + file;
    }

    /**
     * Converts a board index (12) into a string like "e2" for AI output.
     */
    private String squareToString(int square) {
        char file = (char) ('a' + (square % 8));
        char rank = (char) ('1' + (square / 8));
        return "" + file + rank;
    }

    public void printBoard(Board b) {
        System.out.println("  +-----------------+");
        for (int rank = 7; rank >= 0; rank--) {
            System.out.print((rank + 1) + " | ");
            for (int file = 0; file < 8; file++) {
                int square = rank * 8 + file;
                long mask = 1L << square;

                char piece = '.';
                if ((b.whitePawns & mask) != 0)
                    piece = 'P';
                else if ((b.whiteKnights & mask) != 0)
                    piece = 'N';
                else if ((b.whiteBishops & mask) != 0)
                    piece = 'B';
                else if ((b.whiteRooks & mask) != 0)
                    piece = 'R';
                else if ((b.whiteQueens & mask) != 0)
                    piece = 'Q';
                else if ((b.whiteKing & mask) != 0)
                    piece = 'K';
                else if ((b.blackPawns & mask) != 0)
                    piece = 'p';
                else if ((b.blackKnights & mask) != 0)
                    piece = 'n';
                else if ((b.blackBishops & mask) != 0)
                    piece = 'b';
                else if ((b.blackRooks & mask) != 0)
                    piece = 'r';
                else if ((b.blackQueens & mask) != 0)
                    piece = 'q';
                else if ((b.blackKing & mask) != 0)
                    piece = 'k';

                System.out.print(piece + " ");
            }
            System.out.println("|");
        }
        System.out.println("  +-----------------+");
        System.out.println("    a b c d e f g h\n");
    }

    public static void main(String[] args) {
        Game game = new Game();
        // Play against the AI! Depth 4, and you are White (true).
        game.playHumanVsAI(7, true);
    }
}