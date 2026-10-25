import java.util.regex.Pattern;

/**
 * The chess logic behind the HTTP API, kept apart from the HTTP plumbing so it can be tested.
 *
 * The service is stateless: every request carries the whole game as a list of moves from the
 * starting position, which the service replays. That keeps simultaneous visitors from sharing a
 * board and lets a game survive the host restarting or sleeping.
 *
 * Request body:   MODE|MOVES|COLOR
 *   MODE   CPU (the engine replies when it is its turn), PVP (two humans) or EVAL (only analyse)
 *   MOVES  space separated coordinate moves from the start, such as "e2e4 e7e5 g1f3"; a
 *          promotion carries the piece letter, such as "e7e8q" (no letter means queen)
 *   COLOR  WHITE or BLACK, the side the human plays against the engine (ignored outside CPU mode)
 *
 * Response body: FEN|CPU_MOVE|LEGAL_MOVES|TURN|EVAL|STATUS|FEN_BEFORE_CPU
 *   FEN             position after the engine has replied
 *   CPU_MOVE        the engine reply, or empty
 *   LEGAL_MOVES     comma separated legal moves for the side to move; empty once the game is over
 *   TURN            White or Black, the side to move
 *   EVAL            engine score from White's point of view: centipawns ("35") or mate ("#3", "#-2"),
 *                   empty when no search was run
 *   STATUS          ONGOING, CHECKMATE, STALEMATE, DRAW_REPETITION or DRAW_50
 *   FEN_BEFORE_CPU  position before the engine reply, empty if the engine did not move
 */
final class GameService {

    static final class BadRequest extends Exception {
        BadRequest(String message) {
            super(message);
        }
    }

    static final String ONGOING = "ONGOING";
    static final String CHECKMATE = "CHECKMATE";
    static final String STALEMATE = "STALEMATE";
    static final String DRAW_REPETITION = "DRAW_REPETITION";
    static final String DRAW_50 = "DRAW_50";

    /** Thinking time per engine move; override with the ENGINE_TIME_MS environment variable. */
    static final long ENGINE_TIME_MS = readMillis("ENGINE_TIME_MS", 2000);
    /** Thinking time for an EVAL request; override with ANALYSIS_TIME_MS. */
    static final long ANALYSIS_TIME_MS = readMillis("ANALYSIS_TIME_MS", 400);

    private static final int MAX_PLIES = 1000;
    private static final Pattern MOVE_FORMAT = Pattern.compile("[a-h][1-8][a-h][1-8][qrbnQRBN]?");

    private static final MoveGen generator = new MoveGen();
    private static final Search search = new Search();

    static {
        search.setThreads(Search.defaultThreads()); // ENGINE_THREADS, else up to 4 cores
    }

    private GameService() {
    }

    private static long readMillis(String name, long fallback) {
        String env = System.getenv(name);
        if (env != null) {
            try {
                return Math.max(50, Long.parseLong(env.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return fallback;
    }

    /** Handles one request. Synchronized because the engine search keeps shared scratch space. */
    static synchronized String respond(String body) throws BadRequest {
        String[] parts = body.trim().split("\\|", -1);
        if (parts.length != 3)
            throw new BadRequest("expected MODE|MOVES|COLOR");

        String mode = parts[0].trim().toUpperCase();
        if (!mode.equals("CPU") && !mode.equals("PVP") && !mode.equals("EVAL"))
            throw new BadRequest("unknown mode " + parts[0]);
        String color = parts[2].trim().toUpperCase();
        if (!color.equals("WHITE") && !color.equals("BLACK"))
            throw new BadRequest("unknown color " + parts[2]);
        boolean humanIsWhite = color.equals("WHITE");

        String movesText = parts[1].trim();
        String[] tokens = movesText.isEmpty() ? new String[0] : movesText.split("\\s+");
        if (tokens.length > MAX_PLIES)
            throw new BadRequest("too many moves");

        // Replay the game from the start so the board knows the history (repetitions, clock).
        Board board = new Board();
        boolean white = true;
        int[] moves = new int[512];
        for (int i = 0; i < tokens.length; i++) {
            if (!MOVE_FORMAT.matcher(tokens[i]).matches())
                throw new BadRequest("bad move " + tokens[i]);
            int m = findLegalMove(tokens[i], board, white, moves);
            if (m == 0)
                throw new BadRequest("illegal move " + tokens[i] + " at ply " + (i + 1));
            board.makeMove(m, white);
            white = !white;
        }
        int plies = tokens.length;

        int count = generator.genLegal(board, white, moves, 0, false);
        String status = statusOf(board, white, count);
        String cpuMove = "";
        String eval = "";
        String fenBeforeCpu = "";

        if (status.equals(ONGOING)) {
            if (mode.equals("CPU") && white != humanIsWhite) {
                fenBeforeCpu = board.toFEN(white, 1 + plies / 2);
                Move reply = search.getBestMoveTimed(board, ENGINE_TIME_MS, white);
                eval = search.lastEval();
                int m = board.encode(reply, white);
                board.makeMove(m, white);
                cpuMove = Move.toUci(m);
                white = !white;
                plies++;
                count = generator.genLegal(board, white, moves, 0, false);
                status = statusOf(board, white, count);
            } else if (mode.equals("EVAL")) {
                search.getBestMoveTimed(board, ANALYSIS_TIME_MS, white);
                eval = search.lastEval();
            }
        }

        StringBuilder legal = new StringBuilder();
        if (status.equals(ONGOING)) {
            for (int i = 0; i < count; i++) {
                if (i > 0)
                    legal.append(',');
                legal.append(Move.toUci(moves[i]));
            }
        }

        return board.toFEN(white, 1 + plies / 2) + "|" + cpuMove + "|" + legal + "|" + (white ? "White" : "Black")
                + "|" + eval + "|" + status + "|" + fenBeforeCpu;
    }

    /** Game state for the side to move, given how many legal moves it has. */
    static String statusOf(Board board, boolean white, int legalMoveCount) {
        if (legalMoveCount == 0)
            return generator.inCheck(board, white) ? CHECKMATE : STALEMATE;
        if (board.repetitionCount() >= 3)
            return DRAW_REPETITION;
        if (board.halfmoveClock >= 100)
            return DRAW_50;
        return ONGOING;
    }

    /** Finds the legal move matching a coordinate move such as e2e4 or e7e8q; 0 if there is none. */
    private static int findLegalMove(String token, Board board, boolean white, int[] buffer) {
        int from = (token.charAt(1) - '1') * 8 + (token.charAt(0) - 'a');
        int to = (token.charAt(3) - '1') * 8 + (token.charAt(2) - 'a');
        int promotion = token.length() == 5 ? "nbrq".indexOf(Character.toLowerCase(token.charAt(4))) + 2 : 0;

        int count = generator.genLegal(board, white, buffer, 0, false);
        for (int i = 0; i < count; i++) {
            int m = buffer[i];
            if (Move.from(m) != from || Move.to(m) != to)
                continue;
            int p = Move.promotion(m);
            if (p == promotion || (promotion == 0 && p == Move.QUEEN))
                return m;
        }
        return 0;
    }
}
