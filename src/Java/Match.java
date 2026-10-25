import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays two builds of the engine against each other to check that a change really makes it
 * stronger (or at least not weaker).
 *
 * Usage:
 *   java -cp NEW Match <games> <millisPerMove> <baselineClasspath> [concurrentGames] [newThreads]
 *
 * NEW is the build being tested (the classpath this program runs from); the baseline is an older
 * compiled copy of the engine, for example a folder of .class files. Each engine runs in its own
 * process ("java Match player"), single-threaded, and both get the same time per move. Every
 * opening (a few random moves) is played twice with colours swapped. The result is reported from
 * the point of view of the new build, with an Elo estimate and its 95% error margin.
 *
 * The player protocol (one line in, one line out) only uses engine methods that every build has,
 * so old builds can be used as a baseline as long as Match.class is compiled into them.
 */
public class Match {

    private static final int OPENING_PLIES = 6;
    private static final int MAX_PLIES = 400;

    // ------------------------------------------------------------------ player side

    /** Engine subprocess: "new" starts a new game, "go <ms> <moves...>" answers with a move. */
    static void runPlayer() throws Exception {
        PrintStream out = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); // silence the engine log
        MoveGen gen = new MoveGen();
        Search search = new Search();
        String threads = System.getenv("ENGINE_THREADS");
        if (threads != null)
            search.setThreads(Integer.parseInt(threads.trim()));
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            String[] parts = line.trim().split(" ");
            if (parts[0].equals("new")) {
                search.clear();
                out.println("ok");
            } else if (parts[0].equals("go")) {
                long ms = Long.parseLong(parts[1]);
                Board board = new Board();
                boolean white = true;
                for (int i = 2; i < parts.length; i++) {
                    Move found = null;
                    for (Move m : gen.getLegalMoves(board, white)) {
                        if (uci(m).equals(parts[i])) {
                            found = m;
                            break;
                        }
                    }
                    if (found == null)
                        throw new IllegalStateException("cannot replay " + parts[i]);
                    board.makeMove(found, white);
                    white = !white;
                }
                Move best = search.getBestMoveTimed(board, ms, white);
                out.println(best == null ? "none" : uci(best));
            } else if (parts[0].equals("quit")) {
                break;
            }
            out.flush();
        }
    }

    private static String uci(Move m) {
        String s = "" + (char) ('a' + m.startSquare % 8) + (char) ('1' + m.startSquare / 8)
                + (char) ('a' + m.targetSquare % 8) + (char) ('1' + m.targetSquare / 8);
        return m.promotedPiece != 0 ? s + "nbrq".charAt(m.promotedPiece - 2) : s;
    }

    // ------------------------------------------------------------------ match side

    static final class Player implements AutoCloseable {
        final Process process;
        final PrintWriter in;
        final BufferedReader out;

        Player(String classpath, int threads) throws Exception {
            ProcessBuilder pb = new ProcessBuilder("java", "-cp", classpath, "Match", "player");
            pb.environment().put("ENGINE_THREADS", Integer.toString(threads));
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = pb.start();
            in = new PrintWriter(process.getOutputStream(), true);
            out = new BufferedReader(new InputStreamReader(process.getInputStream()));
        }

        String ask(String command) throws Exception {
            in.println(command);
            String reply = out.readLine();
            if (reply == null)
                throw new IllegalStateException("engine process ended");
            return reply;
        }

        @Override
        public void close() {
            in.println("quit");
            process.destroy();
        }
    }

    /** Plays one game; returns 1 if the new build wins, 0.5 for a draw, 0 for a loss. */
    static double playGame(Player newer, Player older, boolean newIsWhite, long seed, long ms, StringBuilder log)
            throws Exception {
        newer.ask("new");
        older.ask("new");
        MoveGen gen = new MoveGen();
        Random rng = new Random(seed);
        Board board = new Board();
        boolean white = true;
        List<String> history = new ArrayList<>();
        int[] buf = new int[512];

        for (int ply = 0; ply < MAX_PLIES; ply++) {
            int n = gen.genLegal(board, white, buf, 0, false);
            String status = GameService.statusOf(board, white, n);
            if (!status.equals(GameService.ONGOING)) {
                log.append(status).append(" after ").append(history.size()).append(" plies");
                if (status.equals(GameService.CHECKMATE))
                    return (white == newIsWhite) ? 0.0 : 1.0; // the side to move is mated
                return 0.5;
            }

            int chosen = 0;
            if (ply < OPENING_PLIES) {
                chosen = buf[rng.nextInt(n)];
            } else {
                Player toMove = (white == newIsWhite) ? newer : older;
                String reply = toMove.ask("go " + ms + (history.isEmpty() ? "" : " " + String.join(" ", history)));
                for (int i = 0; i < n; i++)
                    if (Move.toUci(buf[i]).equals(reply))
                        chosen = buf[i];
                if (chosen == 0) {
                    log.append("illegal move ").append(reply).append(" by ").append(toMove == newer ? "new" : "base");
                    return toMove == newer ? 0.0 : 1.0;
                }
            }
            history.add(Move.toUci(chosen));
            board.makeMove(chosen, white);
            white = !white;
        }
        log.append("move limit");
        return 0.5;
    }

    static double elo(double score) {
        score = Math.min(Math.max(score, 0.001), 0.999);
        return -400.0 * Math.log10(1.0 / score - 1.0);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("player")) {
            runPlayer();
            return;
        }
        if (args.length < 3) {
            System.out.println("usage: java -cp NEW Match <games> <millisPerMove> <baselineClasspath> [concurrentGames] [newThreads]");
            return;
        }
        int games = Integer.parseInt(args[0]);
        long ms = Long.parseLong(args[1]);
        String baseClasspath = args[2];
        int concurrency = args.length > 3 ? Integer.parseInt(args[3])
                : Math.max(1, Runtime.getRuntime().availableProcessors() / 2 - 1);
        int newThreads = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        String newClasspath = System.getProperty("java.class.path");

        double[] results = new double[games];
        String[] logs = new String[games];
        AtomicInteger next = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        long start = System.currentTimeMillis();

        for (int w = 0; w < concurrency; w++) {
            Thread t = new Thread(() -> {
                try (Player newer = new Player(newClasspath, newThreads); Player older = new Player(baseClasspath, 1)) {
                    int g;
                    while ((g = next.getAndIncrement()) < games) {
                        StringBuilder log = new StringBuilder();
                        boolean newIsWhite = g % 2 == 0;
                        results[g] = playGame(newer, older, newIsWhite, 1000 + g / 2, ms, log);
                        logs[g] = log.toString();
                        int finished = done.incrementAndGet();
                        synchronized (Match.class) {
                            System.out.printf("game %3d/%d  new as %s: %s  (%s)%n", finished, games,
                                    newIsWhite ? "white" : "black",
                                    results[g] == 1.0 ? "win" : results[g] == 0.0 ? "loss" : "draw", logs[g]);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            t.start();
            workers.add(t);
        }
        for (Thread t : workers)
            t.join();

        int wins = 0, draws = 0, losses = 0;
        for (double r : results) {
            if (r == 1.0)
                wins++;
            else if (r == 0.0)
                losses++;
            else
                draws++;
        }
        int n = wins + draws + losses;
        double score = (wins + 0.5 * draws) / n;
        double variance = (wins * Math.pow(1 - score, 2) + draws * Math.pow(0.5 - score, 2)
                + losses * Math.pow(score, 2)) / n;
        double margin = 1.96 * Math.sqrt(variance / n);
        System.out.printf(Locale.ROOT, "%nRESULT new vs baseline at %d ms/move: +%d =%d -%d  score %.1f%%  "
                + "Elo %+.0f (95%%: %+.0f .. %+.0f)  [%d s]%n", ms, wins, draws, losses, 100 * score, elo(score),
                elo(score - margin), elo(score + margin), (System.currentTimeMillis() - start) / 1000);
    }
}
