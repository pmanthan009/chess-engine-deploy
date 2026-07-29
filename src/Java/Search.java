import java.util.List;

public class Search {

    private MoveGen generator = new MoveGen();
    private Evaluator evaluator = new Evaluator();

    /**
     * The root of the search tree. Evaluates all legal moves and returns the best
     * one.
     */
    public Move getBestMove(Board board, int depth, boolean isWhite) {
        List<Move> legalMoves = generator.getLegalMoves(board, isWhite);
        orderMoves(legalMoves, board);

        Move bestMove = null;
        // White wants positive infinity, Black wants negative infinity
        int bestScore = isWhite ? Integer.MIN_VALUE : Integer.MAX_VALUE;

        // Initial absolute worst-case scenarios
        int alpha = -1000000;
        int beta = 1000000;

        for (Move move : legalMoves) {
            // 1. Play the move
            board.makeMove(move, isWhite);

            // 2. Look into the future (Switch turns!)
            int score = minimax(board, depth - 1, alpha, beta, !isWhite);

            // 3. Undo the move to restore the board
            board.undoMove(move, isWhite);

            // 4. Did we find a better score?
            if (isWhite) {
                if (score > bestScore) {
                    bestScore = score;
                    bestMove = move;
                }
                alpha = Math.max(alpha, bestScore); // Update root Alpha
            } else {
                if (score < bestScore) {
                    bestScore = score;
                    bestMove = move;
                }
                beta = Math.min(beta, bestScore); // Update root Beta
            }
        }

        // --- FORMAT SCORE OUTPUT ---
        String displayScore;

        // If the score is astronomical, it's a forced Checkmate!
        if (Math.abs(bestScore) > 90000) {
            // 1. Find out how much depth was left when the mate was found
            int depthFound = Math.abs(bestScore) - 99999;

            // 2. Calculate the total plies (half-moves) it took to reach that mate
            int pliesToMate = depth - depthFound + 1;

            // 3. Convert half-moves to full moves (e.g., 3 plies = Mate in 2)
            int mateDistance = (pliesToMate + 1) / 2;

            // If positive, White is delivering mate. If negative, Black is delivering mate.
            displayScore = (bestScore > 0 ? "+M" : "-M") + mateDistance;
        } else {
            // Standard evaluation: Convert centipawns to decimal (e.g., 320 -> 3.20)
            displayScore = String.format("%.2f", bestScore / 100.0);

            // Add a plus sign for White's advantage
            if (bestScore > 0)
                displayScore = "+" + displayScore;
        }

        System.out.println("Evaluation: " + displayScore);

        return bestMove;
    }

    /**
     * The recursive time machine.
     */
    private int minimax(Board board, int depth, int alpha, int beta, boolean isWhite) {
        // 1. Generate STRICTLY LEGAL moves
        List<Move> legalMoves = generator.getLegalMoves(board, isWhite);
        orderMoves(legalMoves, board);

        // 2. CHECKMATE / STALEMATE DETECTION
        if (legalMoves.isEmpty()) {
            // Find the king

            long kingBoard = isWhite ? board.whiteKing : board.blackKing;
            int kingSquare = Long.numberOfTrailingZeros(kingBoard);

            // Are we in check?
            boolean inCheck = generator.isSquareAttacked(kingSquare, !isWhite, board);

            if (inCheck) {
                // Checkmate! Returning a massive score.
                // We add/subtract 'depth' so the engine prefers faster checkmates!
                return isWhite ? -99999 - depth : 99999 + depth;
            } else {
                // Stalemate! It's a draw.
                return 0;
            }
        }

        // Base Case: We hit the depth limit! Evaluate the board.
        if (depth == 0) {
            return evaluator.evaluate(board);
        }

        if (isWhite) {
            int maxScore = Integer.MIN_VALUE;
            for (Move move : legalMoves) {
                board.makeMove(move, true);
                int score = minimax(board, depth - 1, alpha, beta, false);
                board.undoMove(move, true);
                maxScore = Math.max(maxScore, score);
                alpha = Math.max(alpha, score); // Update Alpha (White's guaranteed minimum)

                // --- ALPHA-BETA PRUNING ---
                if (beta <= alpha) {
                    break; // Black had a better option earlier in the tree. Prune this branch!
                }
            }
            return maxScore;
        } else {
            int minScore = Integer.MAX_VALUE;
            for (Move move : legalMoves) {
                board.makeMove(move, false);
                int score = minimax(board, depth - 1, alpha, beta, true);
                board.undoMove(move, false);
                minScore = Math.min(minScore, score);
                beta = Math.min(beta, score); // Update Beta (Black's guaranteed maximum)

                // --- ALPHA-BETA PRUNING ---
                if (beta <= alpha) {
                    break; // White had a better option earlier in the tree. Prune this branch!
                }
            }
            return minScore;
        }
    }

    /**
     * Calculates a heuristic "guess" of a move's quality to prioritize it in the search tree.
     * Implements MVV-LVA (Most Valuable Victim - Least Valuable Attacker).
     */
    private int guessMoveScore(Move move, Board board) {
        int scoreGuess = 0;

        // Figure out what piece is moving (The Attacker)
        int attackerValue = getPieceValueAt(board, move.startSquare);

        // 1. PROMOTIONS (Massive priority, often better than captures)
        if (move.promotedPiece != 0) {
            scoreGuess += move.promotedPiece * 1000; // e.g., Queen = 5000
        }

        // 2. CAPTURES (MVV-LVA)
        if (move.capturedPiece != 0 || move.isEnPassant) {
            // Base value of the victim (En Passant captures a pawn, which is ID 1)
            int victimValue = move.isEnPassant ? 1 : move.capturedPiece;

            // MVV-LVA Formula: 
            // e.g., Pawn takes Queen = (5 * 100) - 1 = 499
            // Queen takes Queen = (5 * 100) - 5 = 495
            // The AI will mathematically search the Pawn capture first!
            scoreGuess += (victimValue * 100) - attackerValue;
        } 
        // 3. CASTLING
        else {
            // If the King (ID 6) moves exactly 2 squares, it's a Castle!
            // Castling is generally a fantastic quiet move, so we evaluate it before random pawn pushes.
            if (attackerValue == 6 && Math.abs(move.startSquare - move.targetSquare) == 2) {
                scoreGuess += 50;
            }
        }

        return scoreGuess;
    }

    /**
     * Helper method to identify the moving piece's value for the MVV-LVA calculation.
     */
    private int getPieceValueAt(Board board, int square) {
        long mask = 1L << square;
        if ((board.whitePawns & mask) != 0 || (board.blackPawns & mask) != 0) return 1;
        if ((board.whiteKnights & mask) != 0 || (board.blackKnights & mask) != 0) return 2;
        if ((board.whiteBishops & mask) != 0 || (board.blackBishops & mask) != 0) return 3;
        if ((board.whiteRooks & mask) != 0 || (board.blackRooks & mask) != 0) return 4;
        if ((board.whiteQueens & mask) != 0 || (board.blackQueens & mask) != 0) return 5;
        if ((board.whiteKing & mask) != 0 || (board.blackKing & mask) != 0) return 6; 
        return 0; // Should never happen
    }

    /**
     * Sorts the move list. (Make sure you update your method calls to pass the Board!)
     */
    private void orderMoves(List<Move> moves, Board board) {
        moves.sort((m1, m2) -> Integer.compare(guessMoveScore(m2, board), guessMoveScore(m1, board)));
    }
}