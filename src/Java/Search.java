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
    private static final int EVAL_NONE = -INF - 1;


    private final MoveGen generator = new MoveGen();
    private final Evaluator evaluator = new Evaluator();
    private final TranspositionTable tt;

    // Per-ply scratch space, allocated once
    private final int[][] moves = new int[MAX_PLY + 2][BUFFER];
    private final int[][] scores = new int[MAX_PLY + 2][BUFFER];
    private final int[][] quietsTried = new int[MAX_PLY + 2][64];
    private final int[][] killers = new int[MAX_PLY + 2][2];
    private final int[] history = new int[2 * 4096]; // [colour][from + 64 * to]
    private final int[] staticEvals = new int[MAX_PLY + 2];

    /** Positions visited by the most recent search (read by the benchmark). */
    public long nodes;
    /** Deepest fully completed iteration of the most recent search. */
    public int completedDepth;
    /** Deepest ply reached by the most recent search, quiescence included. */
    public int selDepth;
    /** Total nodes when each iteration finished (index = depth), for branching-factor statistics. */
    public final long[] iterationNodes = new long[MAX_DEPTH + 1];

    // Score of the most recent search, from the point of view of the side that was searched
    private int lastScore;
    private boolean lastRootWhite = true;

    private boolean timed;
    private boolean canStop;
    private long deadline;
    private int rootBest;
    private int rootDepth;

    /** State shared by every thread of one search. */
    private static final class Shared {
        volatile boolean stop;
    }

    private final Shared shared;
    private final int id;                       // 0 for the main search, 1.. for helper threads
    private Search[] helpers = new Search[0];

    /** A single-threaded search with a transposition table of TT_MB megabytes (default 32). */
    public Search() {
        this(TranspositionTable.defaultMegabytes());
    }

    /** A single-threaded search with a transposition table of about the given size. */
    public Search(int ttMegabytes) {
        tt = new TranspositionTable(ttMegabytes);
        shared = new Shared();
        id = 0;
    }

    /** A helper thread of the given main search: it shares the table and the stop signal. */
    private Search(Search main, int id) {
        tt = new TranspositionTable(main.tt);
        shared = main.shared;
        this.id = id;
    }

    /**
     * Number of threads to search with (Lazy SMP: helpers search the same position and share
     * what they learn through the transposition table). 1 means no helpers.
     */
    public void setThreads(int threads) {
        int count = Math.max(1, Math.min(threads, 64)) - 1;
        helpers = new Search[count];
        for (int i = 0; i < count; i++)
            helpers[i] = new Search(this, i + 1);
    }

    /** Threads from the ENGINE_THREADS environment variable, else the CPU count capped at 4. */
    public static int defaultThreads() {
        String env = System.getenv("ENGINE_THREADS");
        if (env != null) {
            try {
                return Math.max(1, Integer.parseInt(env.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
    }

    /** Forgets everything learned so far (call when a new game starts). */
    public void clear() {
        tt.clear();
        java.util.Arrays.fill(history, 0);
        for (Search helper : helpers)
            java.util.Arrays.fill(helper.history, 0);
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
        completedDepth = 0;
        selDepth = 0;
        java.util.Arrays.fill(iterationNodes, 0);
        shared.stop = false;
        canStop = false;
        timed = timeMs > 0;
        long start = System.nanoTime();
        deadline = start + timeMs * 1_000_000L;
        long softLimit = start + (long) (timeMs * 0.45 * 1_000_000L);

        board.resetHash(white);
        tt.newSearch();
        prepareTables();

        lastRootWhite = white;
        lastScore = 0;
        int count = generator.genLegal(board, white, moves[0], 0, false);
        if (count == 0)
            return null;
        if (count == 1) {
            lastScore = evaluate(board, white);
            System.out.println("Evaluation: forced move");
            return Move.fromInt(moves[0][0]);
        }

        rootBest = moves[0][0];
        int best = rootBest;
        int bestScore = 0;
        boolean aborted = false;

        Thread[] threads = startHelpers(board, white, maxDepth);
        try {
            for (int depth = 1; depth <= maxDepth; depth++) {
                canStop = depth > 1; // a depth-1 search is trivial, always let it finish
                int score = rootSearch(board, white, depth, bestScore);
                if (shared.stop) {
                    aborted = true;
                    break;
                }

                best = rootBest;
                bestScore = score;
                completedDepth = depth;
                iterationNodes[depth] = nodes;
                long ms = (System.nanoTime() - start) / 1_000_000L;
                System.out.println("depth " + depth + "/" + selDepth + "  " + formatScore(score, white) + "  nodes "
                        + nodes + "  " + ms + " ms  best " + Move.toUci(best));

                if (timed && System.nanoTime() >= softLimit)
                    break; // the next iteration would very likely not finish
                if (Math.abs(score) >= MATE_BOUND && MATE - Math.abs(score) <= depth)
                    break; // a forced mate inside the horizon has been proven
            }
        } finally {
            shared.stop = true; // tell the helpers to finish
            for (Thread t : threads) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        for (Search helper : helpers)
            nodes += helper.nodes;

        // The last, unfinished iteration may have found an improvement; rootBest is always valid.
        if (aborted)
            best = rootBest;

        lastScore = bestScore;
        System.out.println("Evaluation: " + formatScore(bestScore, white));
        return Move.fromInt(best);
    }

    /** Clears the killers and ages the history before a new search. */
    private void prepareTables() {
        for (int[] k : killers) {
            k[0] = 0;
            k[1] = 0;
        }
        for (int i = 0; i < history.length; i++)
            history[i] >>= 1;
    }

    /**
     * One iteration at the root. From depth 5 it uses an aspiration window: it expects a score
     * close to the previous one and widens the window only if the result falls outside.
     */
    private int rootSearch(Board board, boolean white, int depth, int previousScore) {
        rootDepth = depth;
        if (depth < 5 || Math.abs(previousScore) >= MATE_BOUND)
            return search(board, white, depth, 0, -INF, INF, false);

        int window = 25;
        int alpha = Math.max(previousScore - window, -INF);
        int beta = Math.min(previousScore + window, INF);
        while (true) {
            int score = search(board, white, depth, 0, alpha, beta, false);
            if (shared.stop)
                return score;
            if (score <= alpha) {
                beta = (alpha + beta) / 2;
                alpha = Math.max(score - window, -INF);
            } else if (score >= beta) {
                beta = Math.min(score + window, INF);
            } else {
                return score;
            }
            window *= 2;
            if (window > 800) {
                alpha = -INF;
                beta = INF;
            }
        }
    }

    /** Starts the helper threads, each on its own copy of the board. */
    private Thread[] startHelpers(Board board, boolean white, int maxDepth) {
        Thread[] threads = new Thread[helpers.length];
        for (int i = 0; i < helpers.length; i++) {
            Search helper = helpers[i];
            Board copy = new Board(board);
            threads[i] = new Thread(() -> helper.helperLoop(copy, white, maxDepth), "search-helper-" + (i + 1));
            threads[i].setDaemon(true);
            threads[i].start();
        }
        return threads;
    }

    /**
     * A helper thread: iterative deepening on the same position until the main search stops it.
     * Odd helpers start one ply deeper so the threads do not all search the same depth at once.
     */
    private void helperLoop(Board board, boolean white, int maxDepth) {
        nodes = 0;
        selDepth = 0;
        timed = false; // only the main thread watches the clock
        canStop = true;
        prepareTables();
        int count = generator.genLegal(board, white, moves[0], 0, false);
        if (count == 0)
            return;
        rootBest = moves[0][0];
        int bestScore = 0;
        for (int depth = 1 + (id & 1); depth <= maxDepth && !shared.stop; depth++) {
            int score = rootSearch(board, white, depth, bestScore);
            if (shared.stop)
                break;
            bestScore = score;
        }
    }

    /**
     * Score of the most recent search from White's point of view, as text: centipawns such as
     * "35" or "-120", or a forced mate such as "#3" (White mates in 3) or "#-2" (Black mates in 2).
     */
    public String lastEval() {
        int whiteScore = lastRootWhite ? lastScore : -lastScore;
        if (Math.abs(lastScore) >= MATE_BOUND) {
            int moves = (MATE - Math.abs(lastScore) + 1) / 2;
            return (whiteScore > 0 ? "#" : "#-") + moves;
        }
        return Integer.toString(whiteScore);
    }

    // ------------------------------------------------------------------
    // Main search
    // ------------------------------------------------------------------

    private int search(Board b, boolean white, int depth, int ply, int alpha, int beta, boolean allowNull) {
        if (depth <= 0)
            return quiescence(b, white, ply, alpha, beta);

        nodes++;
        if (ply > selDepth)
            selDepth = ply;
        if ((nodes & 2047) == 0 && canStop && timed && System.nanoTime() >= deadline)
            shared.stop = true;
        if (shared.stop)
            return 0;
        if (ply >= MAX_PLY)
            return evaluate(b, white);

        boolean root = ply == 0;
        // A position that already occurred in this game or line, or the fifty-move limit, is a draw.
        if (!root && (b.halfmoveClock >= 100 || b.hasRepeated()))
            return 0;

        boolean pv = beta - alpha > 1;
        boolean inCheck = generator.inCheck(b, white);

        // --- TRANSPOSITION TABLE ---
        long key = b.hash;
        int ttMove = 0;
        int ttEval = EVAL_NONE;
        if (tt.probe(key)) {
            ttMove = tt.move;
            ttEval = tt.eval;
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

        // --- STATIC EVALUATION, AND IS IT IMPROVING ON OUR LAST TURN? ---
        int staticEval = inCheck ? EVAL_NONE : (ttEval != EVAL_NONE ? ttEval : evaluate(b, white));
        staticEvals[ply] = staticEval;
        boolean improving = false;
        if (!inCheck) {
            int earlier = ply >= 2 ? staticEvals[ply - 2] : EVAL_NONE;
            if (earlier == EVAL_NONE && ply >= 4)
                earlier = staticEvals[ply - 4];
            improving = earlier == EVAL_NONE || staticEval > earlier;
        }

        // --- REVERSE FUTILITY PRUNING ---
        // So far above beta that, near the leaves, no reply will bring it back down.
        if (!pv && !inCheck && depth <= 8 && Math.abs(beta) < MATE_BOUND
                && staticEval - 80 * (depth - (improving ? 1 : 0)) >= beta)
            return staticEval;

        // --- NULL-MOVE PRUNING ---
        // If passing the turn still leaves us above beta, a real move will too.
        if (!pv && allowNull && !inCheck && depth >= 3 && hasPieces(b, white) && staticEval >= beta) {
            int reduction = depth > 6 ? 3 : 2;
            b.makeNullMove();
            int s = -search(b, !white, depth - 1 - reduction, ply + 1, -beta, -beta + 1, false);
            b.undoNullMove();
            if (shared.stop)
                return 0;
            if (s >= beta)
                return s >= MATE_BOUND ? beta : s;
        }

        // --- MOVE GENERATION IN TWO STAGES ---
        // Stage 1: captures and queen promotions, plus the table move and the killers when they are
        // quiet moves that can be played here. Stage 2 (quiet moves) is generated only if no move
        // of stage 1 caused a cutoff. Moves are pseudo-legal; legality is checked after making them.
        int[] mv = moves[ply];
        int[] sc = scores[ply];
        int killer1 = killers[ply][0];
        int killer2 = killers[ply][1];
        int count = generator.genMoves(b, white, mv, 0, MoveGen.NOISY);
        if (ttMove != 0 && !MoveGen.isNoisy(ttMove) && generator.isPseudoLegal(b, white, ttMove))
            mv[count++] = ttMove;
        if (killer1 != 0 && killer1 != ttMove && generator.isPseudoLegal(b, white, killer1))
            mv[count++] = killer1;
        if (killer2 != 0 && killer2 != ttMove && killer2 != killer1 && generator.isPseudoLegal(b, white, killer2))
            mv[count++] = killer2;
        scoreMoves(mv, sc, 0, count, ttMove, ply, white);
        boolean quietsGenerated = false;

        long ownKing = white ? b.whiteKing : b.blackKing;
        int kingSquare = ownKing == 0 ? -1 : Long.numberOfTrailingZeros(ownKing);

        // Pruning of quiet moves near the leaves (never in PV nodes or when in check)
        boolean pruneQuiets = !pv && !inCheck && depth <= 6;
        boolean futile = pruneQuiets && Math.abs(alpha) < MATE_BOUND && staticEval + 100 + 120 * depth <= alpha;
        int lateMoveLimit = pruneQuiets ? (3 + depth * depth) / (improving ? 1 : 2) : Integer.MAX_VALUE;

        int[] quiets = quietsTried[ply];
        int quietCount = 0;
        int bestScore = -INF;
        int bestMove = 0;
        int alphaStart = alpha;
        int searched = 0;

        for (int i = 0;; i++) {
            if (i == count) {
                if (quietsGenerated)
                    break;
                quietsGenerated = true;
                int end = generator.genMoves(b, white, mv, count, MoveGen.QUIET);
                int kept = count;
                for (int k = count; k < end; k++) {
                    int q = mv[k];
                    if (q != ttMove && q != killer1 && q != killer2) // already tried in stage 1
                        mv[kept++] = q;
                }
                scoreMoves(mv, sc, count, kept, ttMove, ply, white);
                count = kept;
                if (i == count)
                    break;
            }

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

            // --- LATE MOVE PRUNING ---
            // Once enough quiet moves have failed, the remaining (worse ordered) ones are skipped.
            if (quiet && searched > 0 && bestScore > -MATE_BOUND && quietCount >= lateMoveLimit)
                continue;

            b.makeMove(m, white);
            if (MoveGen.needsLegalityCheck(m, kingSquare, inCheck) && generator.inCheck(b, white)) {
                b.undoMove(white); // illegal: it leaves our own king in check
                continue;
            }
            boolean givesCheck = generator.givesCheck(b, white, m);

            // --- FUTILITY PRUNING ---
            // Too far below alpha for a quiet move to catch up near the leaves (unless it gives check).
            if (futile && quiet && searched > 0 && !givesCheck && bestScore > -MATE_BOUND) {
                b.undoMove(white);
                continue;
            }

            // --- CHECK EXTENSION ---
            int extension = (givesCheck && ply < 2 * rootDepth) ? 1 : 0;
            int newDepth = depth - 1 + extension;
            int s;
            if (searched == 0) {
                s = -search(b, !white, newDepth, ply + 1, -beta, -alpha, true);
            } else {
                // --- LATE MOVE REDUCTIONS ---
                int reduction = 0;
                if (depth >= 3 && searched >= 4 && quiet && !inCheck && m != killer1 && m != killer2
                        && !givesCheck) {
                    reduction = Math.min(searched >= 8 ? 2 : 1, newDepth - 1);
                }
                // --- PRINCIPAL VARIATION SEARCH: null window first, full window only if it beats alpha ---
                s = -search(b, !white, newDepth - reduction, ply + 1, -alpha - 1, -alpha, true);
                if (s > alpha && reduction > 0)
                    s = -search(b, !white, newDepth, ply + 1, -alpha - 1, -alpha, true);
                if (s > alpha && s < beta)
                    s = -search(b, !white, newDepth, ply + 1, -beta, -alpha, true);
            }
            b.undoMove(white);

            if (shared.stop)
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

        if (searched == 0)
            return inCheck ? -MATE + ply : 0; // checkmate (prefer the faster one) or stalemate

        int flag = bestScore >= beta ? TranspositionTable.LOWER
                : (bestScore > alphaStart ? TranspositionTable.EXACT : TranspositionTable.UPPER);
        tt.store(key, bestMove, toTT(bestScore, ply), staticEval, depth, flag);
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
        if (ply > selDepth)
            selDepth = ply;
        if ((nodes & 2047) == 0 && canStop && timed && System.nanoTime() >= deadline)
            shared.stop = true;
        if (shared.stop)
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
            count = generator.genMoves(b, white, mv, 0, MoveGen.ALL);
            best = -INF;
        } else {
            stand = evaluate(b, white);
            if (stand >= beta)
                return stand;
            if (stand > alpha)
                alpha = stand;
            best = stand;
            count = generator.genMoves(b, white, mv, 0, MoveGen.NOISY);
        }

        // MVV-LVA: most valuable victim first, then least valuable attacker
        for (int i = 0; i < count; i++) {
            int m = mv[i];
            int cap = Move.captured(m);
            int promo = Move.promotion(m);
            sc[i] = ORDER_VALUE[cap] * 16 - ORDER_VALUE[Move.piece(m)] + (promo != 0 ? 1000 : 0) + (cap != 0 ? 100 : 0);
        }

        long ownKing = white ? b.whiteKing : b.blackKing;
        int kingSquare = ownKing == 0 ? -1 : Long.numberOfTrailingZeros(ownKing);
        int legal = 0;

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
            if (MoveGen.needsLegalityCheck(m, kingSquare, inCheck) && generator.inCheck(b, white)) {
                b.undoMove(white);
                continue;
            }
            legal++;
            int s = -quiescence(b, !white, ply + 1, -beta, -alpha);
            b.undoMove(white);
            if (shared.stop)
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
        if (inCheck && legal == 0)
            return -MATE + ply; // checkmated
        return best;
    }

    // ------------------------------------------------------------------
    // Move ordering
    // ------------------------------------------------------------------

    /**
     * Gives every move a score once, so the search can simply pick the highest. Order: the table
     * move, queen promotions, good captures, killers, bad captures, quiet moves by history.
     */
    private void scoreMoves(int[] mv, int[] sc, int from, int to, int ttMove, int ply, boolean white) {
        int historyBase = white ? 0 : 4096;
        for (int i = from; i < to; i++) {
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
        int score = evaluator.evaluateFast(b);
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
