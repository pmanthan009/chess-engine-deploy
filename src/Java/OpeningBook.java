import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Opening book: well-known opening lines, answered instantly instead of searching.
 *
 * The book is a text file (book.txt) with one opening line per row, as coordinate moves from the
 * start position. Each line is replayed on a board at startup; every position along it (up to
 * BOOK_MAX_PLIES) is stored under its Zobrist hash with the move that was played there. A move
 * that several lines agree on is simply stored several times, so it is chosen more often.
 *
 * Each row starts with the opening's name ("Sicilian Defence: Najdorf Variation: e2e4 ..."); a row
 * starting with "=" only names positions and adds no moves. The name is shown to the player.
 *
 * Settings (environment variables): BOOK=off disables it, BOOK_PATH is the file (default
 * book.txt, also tried under src/Java), BOOK_MAX_PLIES is how deep into the game it is used
 * (default 20).
 */
final class OpeningBook {

    private final Map<Long, List<Integer>> positions = new HashMap<>();
    private final Map<Long, String> names = new HashMap<>();
    private final MoveGen generator = new MoveGen();
    private final Random random = new Random();
    private final int maxPlies;
    private int lines;

    /** The book configured by the environment; empty if disabled or the file is missing. */
    static OpeningBook fromEnvironment() {
        OpeningBook book = new OpeningBook(readInt("BOOK_MAX_PLIES", 20));
        if ("off".equalsIgnoreCase(System.getenv("BOOK")))
            return book;
        String env = System.getenv("BOOK_PATH");
        for (String candidate : new String[] { env, "book.txt", "src/Java/book.txt" }) {
            if (candidate == null || !Files.isRegularFile(Paths.get(candidate)))
                continue;
            try {
                book.load(Paths.get(candidate));
                System.out.println("Opening book: " + book.lines + " lines, " + book.positions.size()
                        + " positions from " + candidate);
            } catch (IOException e) {
                System.out.println("Opening book not loaded: " + e.getMessage());
            }
            break;
        }
        return book;
    }

    OpeningBook(int maxPlies) {
        this.maxPlies = maxPlies;
    }

    private static int readInt(String name, int fallback) {
        String env = System.getenv(name);
        if (env != null) {
            try {
                return Math.max(0, Integer.parseInt(env.trim()));
            } catch (NumberFormatException ignored) {
                // use the default
            }
        }
        return fallback;
    }

    void load(Path file) throws IOException {
        int row = 0;
        for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            row++;
            text = text.trim();
            if (text.isEmpty() || text.charAt(0) == '#')
                continue;
            boolean namesOnly = text.charAt(0) == '=';
            if (namesOnly)
                text = text.substring(1).trim();
            int colon = text.lastIndexOf(':');
            if (colon < 0) {
                System.out.println("Opening book: line " + row + " has no name, line skipped");
                continue;
            }
            addLine(text.substring(0, colon).trim(), text.substring(colon + 1).trim().split("\\s+"), namesOnly, row);
        }
    }

    /** Adds one opening line; a line with an illegal move is reported and ignored completely. */
    void addLine(String name, String[] tokens, boolean namesOnly, int row) {
        Board board = new Board();
        boolean white = true;
        int[] buffer = new int[256];
        List<Long> keys = new ArrayList<>();
        List<Integer> chosen = new ArrayList<>();
        for (int i = 0; i < tokens.length && i < maxPlies; i++) {
            int m = findMove(tokens[i], board, white, buffer);
            if (m == 0) {
                System.out.println("Opening book: illegal move " + tokens[i] + " at ply " + (i + 1) + " of line " + row
                        + ", line skipped");
                return;
            }
            keys.add(board.computeHash(white));
            chosen.add(m);
            board.makeMove(m, white);
            white = !white;
        }
        if (!namesOnly)
            for (int i = 0; i < keys.size(); i++)
                positions.computeIfAbsent(keys.get(i), k -> new ArrayList<>()).add(chosen.get(i));
        // Positions along the line get its name unless a more general line named them first;
        // the position the line ends in always takes the line's own (most specific) name.
        for (int i = 0; i < keys.size(); i++)
            names.putIfAbsent(keys.get(i), name);
        if (tokens.length <= maxPlies)
            names.put(board.computeHash(white), name);
        if (!namesOnly)
            lines++;
    }

    /** A legal book move, chosen at random in proportion to how many lines use it; 0 if the position is not in the book. */
    int probe(Board board, boolean white, int plies) {
        if (plies >= maxPlies)
            return 0;
        List<Integer> options = positions.get(board.computeHash(white));
        if (options == null)
            return 0;
        int[] buffer = new int[256];
        int count = generator.genLegal(board, white, buffer, 0, false);
        int m = options.get(random.nextInt(options.size()));
        for (int i = 0; i < count; i++)
            if (buffer[i] == m)
                return m;
        return 0; // hash collision with a position where the move is illegal
    }

    /** Name of the opening the position belongs to, or an empty string if it is not in the book. */
    String nameOf(Board board, boolean white) {
        String name = names.get(board.computeHash(white));
        return name == null ? "" : name;
    }

    int lineCount() {
        return lines;
    }

    private int findMove(String token, Board board, boolean white, int[] buffer) {
        if (token.length() != 4 && token.length() != 5)
            return 0;
        int from = (token.charAt(1) - '1') * 8 + (token.charAt(0) - 'a');
        int to = (token.charAt(3) - '1') * 8 + (token.charAt(2) - 'a');
        int promotion = token.length() == 5 ? "nbrq".indexOf(token.charAt(4)) + 2 : 0;
        int count = generator.genLegal(board, white, buffer, 0, false);
        for (int i = 0; i < count; i++) {
            int m = buffer[i];
            if (Move.from(m) == from && Move.to(m) == to
                    && (Move.promotion(m) == promotion || (promotion == 0 && Move.promotion(m) == Move.QUEEN)))
                return m;
        }
        return 0;
    }
}
