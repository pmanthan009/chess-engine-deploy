/**
 * Fixed-size transposition table: remembers the result of earlier searches of a position
 * (keyed by its Zobrist hash) so it is never searched twice and so its best move can be tried
 * first.
 *
 * Each slot packs into two longs: the full hash key (to detect collisions) and one data word:
 *   bits  0-23  best move (packed move, see Move)
 *   bits 24-39  score (signed 16 bit)
 *   bits 40-47  search depth
 *   bits 48-49  bound type
 *   bits 50-55  age (which search wrote it)
 *
 * probe() copies a hit into the public fields move / score / depth / flag.
 */
final class TranspositionTable {

    /** The stored score is a lower bound (the search failed high). */
    static final int LOWER = 1;
    /** The stored score is an upper bound (the search failed low). */
    static final int UPPER = 2;
    /** The stored score is exact. */
    static final int EXACT = 3;

    private final long[] keys;
    private final long[] data;
    private final int mask;
    private int age;

    // Results of the last successful probe()
    int move;
    int score;
    int depth;
    int flag;

    /** Creates a table with 2^bits slots (16 bytes each). */
    TranspositionTable(int bits) {
        keys = new long[1 << bits];
        data = new long[1 << bits];
        mask = (1 << bits) - 1;
    }

    /** Marks the start of a new search so that stale entries may be replaced. */
    void newSearch() {
        age = (age + 1) & 63;
    }

    void clear() {
        java.util.Arrays.fill(keys, 0L);
        java.util.Arrays.fill(data, 0L);
        age = 0;
    }

    private int index(long key) {
        return (int) (key ^ (key >>> 32)) & mask;
    }

    /** Looks the position up; on a hit fills move / score / depth / flag and returns true. */
    boolean probe(long key) {
        int i = index(key);
        if (keys[i] != key)
            return false;
        long d = data[i];
        move = (int) (d & 0xFFFFFF);
        score = (short) ((d >>> 24) & 0xFFFF);
        depth = (int) ((d >>> 40) & 0xFF);
        flag = (int) ((d >>> 48) & 3);
        return true;
    }

    void store(long key, int move, int score, int depth, int flag) {
        int i = index(key);
        long old = data[i];
        boolean sameKey = keys[i] == key;

        if (keys[i] != 0L) {
            int oldDepth = (int) ((old >>> 40) & 0xFF);
            int oldAge = (int) ((old >>> 50) & 63);
            // Keep a deeper result from this same search, otherwise replace.
            if (oldAge == age && oldDepth > depth)
                return;
        }

        // A search that failed low has no best move; keep the one we already know.
        if (move == 0 && sameKey)
            move = (int) (old & 0xFFFFFF);

        keys[i] = key;
        data[i] = (move & 0xFFFFFFL)
                | ((long) (score & 0xFFFF) << 24)
                | ((long) (depth & 0xFF) << 40)
                | ((long) (flag & 3) << 48)
                | ((long) age << 50);
    }
}
