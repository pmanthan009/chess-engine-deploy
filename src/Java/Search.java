import java.util.Locale;

/**
 * The engine search: iterative-deepening negamax with alpha-beta pruning.
 *
 * Features: transposition table, principal variation search, quiescence search, null-move
 * pruning, late move reductions, and killer / history / MVV-LVA move ordering. Everything on the
 * hot path works on packed int moves in preallocated per-ply buffers, so the search itself does
 * not allocate.
 */
public class Search {

    private static final int MAX_PLY = 128;
    private static final int MAX_DEPTH = 64;
    private static final int BUFFER = 512;

    private static final int INF = 32000;
    private static final int MATE = 30000;
    private static final int MATE_BOUND = MATE - MAX_PLY;

    // Piece values used only to order moves (index = piece type 1..6)
    private static final int[] ORDER_VALUE = { 0, 1, 3, 3, 5, 9, 10 };
    // Centipawn values used for delta pruning in quiescence search
    private static final int[] CENTIPAWNS = { 0, 100, 300, 300, 500, 900, 0 };

    private static final int SCORE_TT_MOVE = 1_000_000;
    private static final int SCORE_QUEEN_PROMO = 600_000;
    private static final int SCORE_GOOD_CAPTURE = 500_000;
    private static final int SCORE_KILLER_1 = 400_000;
    private static final int SCORE_KILLER_2 = 390_000;
    private static final int SCORE_BAD_CAPTURE = 300_000;
    private static final int SCORE_UNDERPROMO = 100_000;
    private static final int HISTORY_LIMIT = 16384;

    private final MoveGen generator = new MoveGen();
    private final Evaluator evaluator = new Evaluator();
    private final TranspositionTable tt;

    // Per-ply scratch space, allocated once
    private final int[][] moves = new int[MAX_PLY + 2][BUFFER];
    private final int[][] scores = new int[MAX_PLY + 2][BUFFER];
    private final int[][] quietsTried = new int[MAX_PLY + 2][64];
    private final int[][] killers = new int[MAX_PLY + 2][2];
    private final int[] history = new int[2 * 4096]; // [colour][from + 64 * to]

    /** Positions visited by the most recent search (read by the benchmark). */
    public long nodes;

    private boolean timed;
    private boolean canStop;
    private boolean stop;
    private long deadline;
    private int rootBest;

    public Search() {
        this(20);
    }

    /** @param ttBits the transposition table holds 2^ttBits entries (16 bytes each) */
    public Search(int ttBits) {
        tt = new TranspositionTable(ttBits);
    }

    /** Forgets everything learned so far (call when a new game starts). */
    public void clear() {
        tt.clear();
        java.util.Arrays.fill(history, 0);
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** Searches to a fixed depth (in plies) and returns the best move, or null if there is none. */
    public Move getBestMove(Board board, int depth, boolean isWhite) {
        return iterate(board, isWhite, Math.min(depth, MAX_DEPTH), 0);
    }

    /**
     * Searches for about the given number of milliseconds, going as deep as time allows, and
     * returns the best move, or null if there is none.
     */
    public Move getBestMoveTimed(Board board, long timeMs, boolean isWhite) {
        return iterate(board, isWhite, MAX_DEPTH, Math.max(1, timeMs));
    }

    private Move iterate(Board board, boolean white, int maxDepth, long timeMs) {
        nodes = 0;
        stop = false;
        canStop = false;
        timed = timeMs > 0;
        long start = System.nanoTime();
        deadline = start + timeMs * 1_000_000L;
        long softLimit = start + (long) (timeMs * 0.45 * 1_000_000L);

        board.resetHash(white);
        tt.newSearch();
        for (int[] k : killers) {
            k[0] = 0;
            k[1] = 0;
        }
        for (int i = 0; i < history.length; i++)
            history[i] >>= 1;

        int count = generator.genLegal(board, white, moves[0], 0, false);
        if (count == 0)
            return null;
        if (count == 1) {
            System.out.println("Evaluation: forced move");
            return Move.fromInt(moves[0][0]);
        }

        rootBest = moves[0][0];
        int best = rootBest;
        int bestScore = 0;

        for (int depth = 1; depth <= maxDepth; depth++) {
            canStop = depth > 1; // a depth-1 search is trivial, always let it finish
            int score = search(board, white, depth, 0, -INF, INF, false);
            if (stop)
                break;

            best = rootBest;
            bestScore = score;
            long ms = (System.nanoTime() - start) / 1_000_000L;
            System.out.println("depth " + depth + "  " + formatScore(score, white) + "  nodes " + nodes
                    + "  " + ms + " ms  best " + Move.toUci(best));

            if (timed && System.nanoTime() >= softLimit)
                break; // the next iteration would very likely not finish
            if (Math.abs(score) >= MATE_BOUND && MATE - Math.abs(score) <= depth)
                break; // a forced mate inside the horizon has been proven
        }

        // The last, unfinished iteration may have found an improvement; rootBest is always valid.
        if (stop)
            best = rootBest;

        System.out.println("Evaluation: " + formatScore(bestScore, white));
        return Move.fromInt(best);
    }

    // ------------------------------------------------------------------
    // Main search
    // ------------------------------------------------------------------

    private int search(Board b, boolean white, int depth, int ply, int alpha, int beta, boolean allowNull) {
        if (depth <= 0)
            return quiescence(b, white, ply, alpha, beta);

        nodes++;
        if ((nodes & 2047) == 0 && canStop && timed && System.nanoTime() >= deadline)
            stop = true;
        if (stop)
            return 0;
        if (ply >= MAX_PLY)
            return evaluate(b, white);

        boolean root = ply == 0;
        boolean pv = beta - alpha > 1;
        boolean inCheck = generator.inCheck(b, white);

        // --- TRANSPOSITION TABLE ---
        long key = b.hash;
        int ttMove = 0;
        if (tt.probe(key)) {
            ttMove = tt.move;
            if (!root && !pv && tt.depth >= depth) {
                int s = fromTT(tt.score, ply);
                int flag = tt.flag;
                if (flag == TranspositionTable.EXACT
                        || (flag == TranspositionTable.LOWER && s >= beta)
                        || (flag == TranspositionTable.UPPER && s <= alpha))
                    return s;
            }
        }
        if (root)
            ttMove = rootBest; // the best move of the previous iteration goes first

        // --- NULL-MOVE PRUNING ---
        // If passing the turn still leaves us above beta, a real move will too.
        if (!pv && allowNull && !inCheck && depth >= 3 && hasPieces(b, white) && evaluate(b, white) >= beta) {
            int reduction = depth > 6 ? 3 : 2;
            b.makeNullMove();
            int s = -search(b, !white, depth - 1 - reduction, ply + 1, -beta, -beta + 1, false);
            b.undoNullMove();
            if (stop)
                return 0;
            if (s >= beta)
                return s >= MATE_BOUND ? beta : s;
        }

        int[] mv = moves[ply];
        int[] sc = scores[ply];
        int count = generator.genLegal(b, white, mv, 0, false);
        if (count == 0)
            return inCheck ? -MATE + ply : 0; // checkmate (prefer the faster one) or stalemate

        scoreMoves(mv, sc, count, ttMove, ply, white);

        int[] quiets = quietsTried[ply];
        int quietCount = 0;
        int bestScore = -INF;
        int bestMove = 0;
        int alphaStart = alpha;
        int searched = 0;

        for (int i = 0; i < count; i++) {
            // Selection sort step: bring the best remaining move to position i.
            int bi = i;
            for (int j = i + 1; j < count; j++)
                if (sc[j] > sc[bi])
                    bi = j;
            int m = mv[bi];
            mv[bi] = mv[i];
            sc[bi] = sc[i];
            mv[i] = m;

            boolean quiet = Move.isQuiet(m);
            int newDepth = depth - 1;
            int s;

            b.makeMove(m, white);
            if (searched == 0) {
                s = -search(b, !white, newDepth, ply + 1, -beta, -alpha, true);
            } else {
                // --- LATE MOVE REDUCTIONS ---
                int reduction = 0;
                if (depth >= 3 && searched >= 4 && quiet && !inCheck
                        && m != killers[ply][0] && m != killers[ply][1]
                        && !generator.inCheck(b, !white)) {
                    reduction = searched >= 8 ? 2 : 1;
                    reduction = Math.min(reduction, newDepth - 1);
                }
                // --- PRINCIPAL VARIATION SEARCH: null window first, full window only if it beats alpha ---
                s = -search(b, !white, newDepth - reduction, ply + 1, -alpha - 1, -alpha, true);
                if (s > alpha && reduction > 0)
                    s = -search(b, !white, newDepth, ply + 1, -alpha - 1, -alpha, true);
                if (s > alpha && s < beta)
                    s = -search(b, !white, newDepth, ply + 1, -beta, -alpha, true);
            }
            b.undoMove(white);

            if (stop)
                return 0;
            searched++;

            if (s > bestScore) {
                bestScore = s;
                bestMove = m;
                if (s > alpha) {
                    alpha = s;
                    if (root)
                        rootBest = m;
                    if (alpha >= beta) {
                        if (quiet)
                            rewardQuiet(m, quiets, quietCount, depth, ply, white);
                        break;
                    }
                }
            }
            if (quiet && quietCount < quiets.length)
                quiets[quietCount++] = m;
        }

        int flag = bestScore >= beta ? TranspositionTable.LOWER
                : (bestScore > alphaStart ? TranspositionTable.EXACT : TranspositionTable.UPPER);
        tt.store(key, bestMove, toTT(bestScore, ply), depth, flag);
        return bestScore;
    }

    // ------------------------------------------------------------------
    // Quiescence search
    // ------------------------------------------------------------------

    /**
     * Keeps resolving captures after the depth limit so the evaluation is not taken in the
     * middle of an exchange. When in check, every evasion is searched.
     */
    private int quiescence(Board b, boolean white, int ply, int alpha, int beta) {
        nodes++;
        if ((nodes & 2047) == 0 && canStop && timed && System.nanoTime() >= deadline)
            stop = true;
        if (stop)
            return 0;
        if (ply >= MAX_PLY)
            return evaluate(b, white);

        int[] mv = moves[ply];
        int[] sc = scores[ply];
        boolean inCheck = generator.inCheck(b, white);
        int stand = -INF;
        int best;
        int count;

        if (inCheck) {
            count = generator.genLegal(b, white, mv, 0, false);
            if (count == 0)
                return -MATE + ply;
            best = -INF;
        } else {
            stand = evaluate(b, white);
            if (stand >= beta)
                return stand;
            if (stand > alpha)
                alpha = stand;
            best = stand;
            count = generator.genLegal(b, white, mv, 0, true);
        }

        // MVV-LVA: most valuable victim first, then least valuable attacker
        for (int i = 0; i < count; i++) {
            int m = mv[i];
            int cap = Move.captured(m);
            int promo = Move.promotion(m);
            sc[i] = ORDER_VALUE[cap] * 16 - ORDER_VALUE[Move.piece(m)] + (promo != 0 ? 1000 : 0) + (cap != 0 ? 100 : 0);
        }

        for (int i = 0; i < count; i++) {
            int bi = i;
            for (int j = i + 1; j < count; j++)
                if (sc[j] > sc[bi])
                    bi = j;
            int m = mv[bi];
            mv[bi] = mv[i];
            sc[bi] = sc[i];
            mv[i] = m;

            // Delta pruning: even winning this piece cannot lift the score up to alpha.
            if (!inCheck && Move.promotion(m) == 0 && stand + CENTIPAWNS[Move.captured(m)] + 200 <= alpha)
                continue;

            b.makeMove(m, white);
            int s = -quiescence(b, !white, ply + 1, -beta, -alpha);
            b.undoMove(white);
            if (stop)
                return 0;

            if (s > best) {
                best = s;
                if (s > alpha) {
                    alpha = s;
                    if (alpha >= beta)
                        break;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Move ordering
    // ------------------------------------------------------------------

    /**
     * Gives every move a score once, so the search can simply pick the highest. Order: the table
     * move, queen promotions, good captures, killers, bad captures, quiet moves by history.
     */
    private void scoreMoves(int[] mv, int[] sc, int count, int ttMove, int ply, boolean white) {
        int historyBase = white ? 0 : 4096;
        for (int i = 0; i < count; i++) {
            int m = mv[i];
            int s;
            if (m == ttMove) {
                s = SCORE_TT_MOVE;
            } else {
                int cap = Move.captured(m);
                int promo = Move.promotion(m);
                if (cap != 0 || promo != 0) {
                    int attacker = ORDER_VALUE[Move.piece(m)];
                    int victim = ORDER_VALUE[cap];
                    int mvvLva = victim * 16 - attacker;
                    if (promo == Move.QUEEN)
                        s = SCORE_QUEEN_PROMO + mvvLva;
                    else if (promo != 0)
                        s = SCORE_UNDERPROMO + mvvLva;
                    else
                        s = (victim >= attacker ? SCORE_GOOD_CAPTURE : SCORE_BAD_CAPTURE) + mvvLva;
                } else if (m == killers[ply][0]) {
                    s = SCORE_KILLER_1;
                } else if (m == killers[ply][1]) {
                    s = SCORE_KILLER_2;
                } else {
                    s = history[historyBase + (m & 4095)];
                }
            }
            sc[i] = s;
        }
    }

    /** A quiet move caused a beta cutoff: remember it as a killer and reward it in the history. */
    private void rewardQuiet(int m, int[] quiets, int quietCount, int depth, int ply, boolean white) {
        if (killers[ply][0] != m) {
            killers[ply][1] = killers[ply][0];
            killers[ply][0] = m;
        }
        int bonus = Math.min(depth * depth, 1600);
        updateHistory(white, m, bonus);
        for (int i = 0; i < quietCount; i++)
            updateHistory(white, quiets[i], -bonus); // the quiet moves tried before it failed
    }

    private void updateHistory(boolean white, int m, int bonus) {
        int index = (white ? 0 : 4096) + (m & 4095);
        int h = history[index];
        history[index] = h + bonus - h * Math.abs(bonus) / HISTORY_LIMIT;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Static evaluation from the point of view of the side to move. */
    private int evaluate(Board b, boolean white) {
        int score = evaluator.evaluate(b);
        return white ? score : -score;
    }

    /** Does the side still have something other than king and pawns? (Null-move safety.) */
    private static boolean hasPieces(Board b, boolean white) {
        return white ? (b.whiteKnights | b.whiteBishops | b.whiteRooks | b.whiteQueens) != 0
                : (b.blackKnights | b.blackBishops | b.blackRooks | b.blackQueens) != 0;
    }

    // Mate scores are relative to the root; in the table they are stored relative to the node.
    private static int toTT(int score, int ply) {
        if (score >= MATE_BOUND)
            return score + ply;
        if (score <= -MATE_BOUND)
            return score - ply;
        return score;
    }

    private static int fromTT(int score, int ply) {
        if (score >= MATE_BOUND)
            return score - ply;
        if (score <= -MATE_BOUND)
            return score + ply;
        return score;
    }

    /** Formats a score (side to move perspective) from White's point of view, e.g. +0.35 or -M3. */
    private static String formatScore(int score, boolean white) {
        int whiteScore = white ? score : -score;
        if (Math.abs(score) >= MATE_BOUND) {
            int plies = MATE - Math.abs(score);
            return (whiteScore > 0 ? "+M" : "-M") + ((plies + 1) / 2);
        }
        String text = String.format(Locale.ROOT, "%.2f", whiteScore / 100.0);
        return whiteScore > 0 ? "+" + text : text;
    }
}
