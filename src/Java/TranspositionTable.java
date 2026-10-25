/**
 * Transposition table: remembers the result of earlier searches of a position (keyed by its
 * Zobrist hash) so it is not searched twice, and so its best move can be tried first.
 *
 * One entry per slot, two longs each: a check word and a data word.
 *   data bits  0-23  best move (packed move, see Move)
 *   data bits 24-39  score (signed 16 bit)
 *   data bits 40-55  static evaluation (signed 16 bit, EVAL_NONE when in check)
 *   data bits 56-61  search depth (0..63)
 *   data bits 62-63  bound type
 *   check word       key XOR data
 * Storing key XOR data lets several search threads share the table without locks: if two
 * threads write the same slot at once, the check word no longer matches and the entry is simply
 * treated as a miss. A separate byte per slot records which search wrote it.
 *
 * (A 4-entry bucket version was tried and lost about 30 Elo in engine matches: the extra
 * probing cost more time than the better replacement saved.)
 *
 * probe() copies a hit into the fields move / score / eval / depth / flag.
 */
final class TranspositionTable {

    /** The stored score is a lower bound (the search failed high). */
    static final int LOWER = 1;
    /** The stored score is an upper bound (the search failed low). */
    static final int UPPER = 2;
    /** The stored score is exact. */
    static final int EXACT = 3;

    private final long[] table; // [check, data] pairs
    private final byte[] ages;  // per slot
    private final int mask;
    private final TranspositionTable parent; // the table that owns the storage, or null if this one does
    private volatile int age;               // used on the owning table only

    // Results of the last successful probe()
    int move;
    int score;
    int eval;
    int depth;
    int flag;

    /** Size from the TT_MB environment variable (megabytes, default 32). */
    static int defaultMegabytes() {
        String env = System.getenv("TT_MB");
        if (env != null) {
            try {
                return Math.max(1, Math.min(4096, Integer.parseInt(env.trim())));
            } catch (NumberFormatException ignored) {
                // use the default
            }
        }
        return 32;
    }

    /** Creates a table of about the given size (rounded down to a power-of-two number of slots). */
    TranspositionTable(int megabytes) {
        long slots = Math.max(1, (long) megabytes * 1024 * 1024 / 17);
        int bits = Math.min(63 - Long.numberOfLeadingZeros(slots), 28);
        table = new long[2 << bits];
        ages = new byte[1 << bits];
        mask = (1 << bits) - 1;
        parent = null;
    }

    /** A separate copy of the probe fields, for another search thread that shares this table. */
    TranspositionTable(TranspositionTable shared) {
        this.table = shared.table;
        this.ages = shared.ages;
        this.mask = shared.mask;
        this.parent = shared.root();
    }

    private TranspositionTable root() {
        return parent == null ? this : parent;
    }

    /** Marks the start of a new search so that stale entries may be replaced. */
    void newSearch() {
        TranspositionTable r = root();
        r.age = (r.age + 1) & 127;
    }

    void clear() {
        java.util.Arrays.fill(table, 0L);
        java.util.Arrays.fill(ages, (byte) 0);
        root().age = 0;
    }

    private int slot(long key) {
        return (int) (key ^ (key >>> 32)) & mask;
    }

    /** Looks the position up; on a hit fills move / score / eval / depth / flag and returns true. */
    boolean probe(long key) {
        int i = slot(key);
        long data = table[2 * i + 1];
        if (data == 0 || (table[2 * i] ^ data) != key)
            return false;
        move = (int) (data & 0xFFFFFF);
        score = (short) ((data >>> 24) & 0xFFFF);
        eval = (short) ((data >>> 40) & 0xFFFF);
        depth = (int) ((data >>> 56) & 63);
        flag = (int) ((data >>> 62) & 3);
        return true;
    }

    void store(long key, int move, int score, int eval, int depth, int flag) {
        int i = slot(key);
        long old = table[2 * i + 1];
        int currentAge = root().age;

        if (old != 0) {
            int oldDepth = (int) ((old >>> 56) & 63);
            // Keep a deeper result from this same search, otherwise replace.
            if (ages[i] == currentAge && oldDepth > depth)
                return;
            // A search that failed low has no best move; keep the one we already know.
            if (move == 0 && (table[2 * i] ^ old) == key)
                move = (int) (old & 0xFFFFFF);
        }

        long data = (move & 0xFFFFFFL)
                | ((long) (score & 0xFFFF) << 24)
                | ((long) (eval & 0xFFFF) << 40)
                | ((long) Math.min(Math.max(depth, 0), 63) << 56)
                | ((long) (flag & 3) << 62);
        table[2 * i + 1] = data;
        table[2 * i] = key ^ data;
        ages[i] = (byte) currentAge;
    }
}
