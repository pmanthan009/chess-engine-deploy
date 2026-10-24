import java.util.List;

/**
 * Perft correctness checks and a fixed-depth search benchmark.
 *
 * Usage:
 *   java Bench perft            node counts vs known-good reference values
 *   java Bench search [depth]   fixed-depth search on a set of positions (default depth 5)
 *   java Bench time [millis]    timed iterative-deepening search on the same positions
 *   java Bench tactics [depth]  checks that known mates and tactics are found
 *   java Bench fast             perft through the allocation-free int move path (speed)
 *   java Bench verify           perft that also checks hash, mailbox and captures-only generation
 */
public class Bench {

    static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    static final String KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
    static final String ENDGAME = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1";
    static final String PROMO = "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1";
    static final String POS5 = "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8";
    static final String ITALIAN = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4";
    static final String MIDDLEGAME = "r2q1rk1/ppp2ppp/2np1n2/2b1p1B1/2B1P1b1/2NP1N2/PPP2PPP/R2Q1RK1 w - - 0 8";

    // { fen, expected node counts for depth 1, 2, 3, ... }
    static final Object[][] PERFT = {
        { START, 20L, 400L, 8902L, 197281L, 4865609L },
        { KIWIPETE, 48L, 2039L, 97862L, 4085603L },
        { ENDGAME, 14L, 191L, 2812L, 43238L, 674624L },
        { PROMO, 6L, 264L, 9467L, 422333L },
        { POS5, 44L, 1486L, 62379L, 2103487L },
    };

    static final String[] SEARCH_POSITIONS = { START, KIWIPETE, ITALIAN, MIDDLEGAME, ENDGAME, POS5 };

    // { fen, expected best move or null when any legal move is acceptable }
    static final String[][] TACTICS = {
        { "6k1/5ppp/8/8/8/8/8/R3K3 w - - 0 1", "a1a8" },
        { "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4", "h5f7" },
        { "k7/8/1K6/8/8/8/8/7R w - - 0 1", "h1h8" },
        { "3r2k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1", "d1d8" },
        { "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 6 5", null },
        { "7k/8/5K2/8/8/8/8/6R1 w - - 0 1", null },
        { "4k3/8/8/8/8/8/4q3/4K1R1 w - - 0 1", null },
    };

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "perft";
        switch (mode) {
            case "perft": System.exit(runPerft() ? 0 : 1); break;
            case "search": runSearch(args.length > 1 ? Integer.parseInt(args[1]) : 5, 0); break;
            case "time": runSearch(0, args.length > 1 ? Long.parseLong(args[1]) : 1000); break;
            case "fast": System.exit(runPerftInt(false) ? 0 : 1); break;
            case "verify": System.exit(runPerftInt(true) ? 0 : 1); break;
            case "tactics": System.exit(runTactics(args.length > 1 ? Integer.parseInt(args[1]) : 5) ? 0 : 1); break;
            default: System.out.println("Unknown mode: " + mode);
        }
    }

    // ------------------------------------------------------------------ perft

    static long perft(MoveGen gen, Board board, int depth, boolean white) {
        List<Move> moves = gen.getLegalMoves(board, white);
        if (depth == 1) return moves.size();
        long total = 0;
        for (Move m : moves) {
            board.makeMove(m, white);
            total += perft(gen, board, depth - 1, !white);
            board.undoMove(m, white);
        }
        return total;
    }

    static boolean runPerft() {
        MoveGen gen = new MoveGen();
        boolean allOk = true;
        for (Object[] row : PERFT) {
            String fen = (String) row[0];
            System.out.println("Perft: " + fen);
            for (int d = 1; d < row.length; d++) {
                Board b = Board.fromFEN(fen);
                long t0 = System.nanoTime();
                long got = perft(gen, b, d, b.fenWhiteToMove);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                long want = (Long) row[d];
                boolean ok = got == want;
                allOk &= ok;
                System.out.printf("  depth %d: %,d (expected %,d) %s  [%d ms]%n", d, got, want, ok ? "OK" : "MISMATCH", ms);
            }
        }
        System.out.println(allOk ? "PERFT PASSED" : "PERFT FAILED");
        return allOk;
    }

    // ------------------------------------------------- int-move perft / verification

    static long perftInt(MoveGen gen, Board b, int depth, boolean white, int[][] bufs, int ply, boolean verify) {
        int[] buf = bufs[ply];
        int n = gen.genLegal(b, white, buf, 0, false);
        if (verify)
            verifyNode(gen, b, white, buf, n, bufs[ply + 1]);
        if (depth == 1)
            return n;
        long total = 0;
        for (int i = 0; i < n; i++) {
            b.makeMove(buf[i], white);
            total += perftInt(gen, b, depth - 1, !white, bufs, ply + 1, verify);
            b.undoMove(white);
        }
        return total;
    }

    static boolean runPerftInt(boolean verify) {
        MoveGen gen = new MoveGen();
        int[][] bufs = new int[16][512];
        boolean allOk = true;
        long totalNodes = 0, totalMs = 0;
        for (Object[] row : PERFT) {
            String fen = (String) row[0];
            int maxDepth = verify ? Math.min(row.length - 1, 4) : row.length - 1;
            for (int d = 1; d <= maxDepth; d++) {
                Board b = Board.fromFEN(fen);
                long t0 = System.nanoTime();
                long got;
                try {
                    got = perftInt(gen, b, d, b.fenWhiteToMove, bufs, 0, verify);
                } catch (IllegalStateException e) {
                    System.out.println("VERIFY FAILED: " + e.getMessage() + " in " + fen + " depth " + d);
                    return false;
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                long want = (Long) row[d];
                boolean ok = got == want;
                allOk &= ok;
                totalNodes += got;
                totalMs += ms;
                if (!ok || d == maxDepth)
                    System.out.printf("  %s depth %d: %,d (expected %,d) %s [%d ms]%n", fen.substring(0, Math.min(24, fen.length())), d, got, want,
                            ok ? "OK" : "MISMATCH", ms);
            }
        }
        System.out.printf("%,d leaf nodes in %d ms%n", totalNodes, totalMs);
        System.out.println(allOk ? (verify ? "VERIFY PASSED" : "FAST PERFT PASSED") : "FAILED");
        return allOk;
    }

    /** Consistency checks run at every node of the verification perft. */
    static void verifyNode(MoveGen gen, Board b, boolean white, int[] legal, int legalCount, int[] scratch) {
        if (b.computeHash(white) != b.hash)
            throw new IllegalStateException("incremental hash differs from recomputed hash");

        long wp = b.whitePawns | b.whiteKnights | b.whiteBishops | b.whiteRooks | b.whiteQueens | b.whiteKing;
        long bp = b.blackPawns | b.blackKnights | b.blackBishops | b.blackRooks | b.blackQueens | b.blackKing;
        if (wp != b.whitePieces || bp != b.blackPieces || (wp | bp) != b.allPieces)
            throw new IllegalStateException("occupancy bitboards out of sync");
        for (int sq = 0; sq < 64; sq++)
            if (b.pieceAt[sq] != codeFromBitboards(b, sq))
                throw new IllegalStateException("mailbox out of sync at square " + sq);

        // Captures-only generation must equal the capture / queen-promotion subset of the full list.
        int capCount = gen.genLegal(b, white, scratch, 0, true);
        int expected = 0;
        for (int i = 0; i < legalCount; i++) {
            if (isCapsOnlyMove(legal[i])) {
                expected++;
                boolean found = false;
                for (int j = 0; j < capCount; j++)
                    found |= scratch[j] == legal[i];
                if (!found)
                    throw new IllegalStateException("captures-only list is missing " + Move.toUci(legal[i]));
            }
        }
        if (expected != capCount)
            throw new IllegalStateException("captures-only list has " + capCount + " moves, expected " + expected);
    }

    static boolean isCapsOnlyMove(int m) {
        int promo = Move.promotion(m);
        return promo == Move.QUEEN || (Move.captured(m) != 0 && promo == 0);
    }

    static int codeFromBitboards(Board b, int sq) {
        long mask = 1L << sq;
        if ((b.whitePawns & mask) != 0) return 1;
        if ((b.whiteKnights & mask) != 0) return 2;
        if ((b.whiteBishops & mask) != 0) return 3;
        if ((b.whiteRooks & mask) != 0) return 4;
        if ((b.whiteQueens & mask) != 0) return 5;
        if ((b.whiteKing & mask) != 0) return 6;
        if ((b.blackPawns & mask) != 0) return 9;
        if ((b.blackKnights & mask) != 0) return 10;
        if ((b.blackBishops & mask) != 0) return 11;
        if ((b.blackRooks & mask) != 0) return 12;
        if ((b.blackQueens & mask) != 0) return 13;
        if ((b.blackKing & mask) != 0) return 14;
        return 0;
    }

    // ----------------------------------------------------------------- search

    static void runSearch(int depth, long millis) {
        // Warm-up so the JIT is not charged to the first position.
        for (String fen : SEARCH_POSITIONS) {
            Board b = Board.fromFEN(fen);
            quiet(() -> new Search().getBestMove(b, 3, b.fenWhiteToMove));
        }

        long totalNodes = 0, totalMs = 0;
        for (String fen : SEARCH_POSITIONS) {
            Board b = Board.fromFEN(fen);
            Search s = new Search();
            long t0 = System.nanoTime();
            Move[] best = new Move[1];
            quiet(() -> best[0] = depth > 0 ? s.getBestMove(b, depth, b.fenWhiteToMove)
                                            : timed(s, b, millis));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            totalNodes += s.nodes;
            totalMs += ms;
            System.out.printf("%-72s best %-5s nodes %,12d  %6d ms  %,10d nps%n", fen, name(best[0]), s.nodes, ms,
                    ms == 0 ? 0 : s.nodes * 1000 / ms);
        }
        System.out.printf("TOTAL nodes %,d  %d ms  %,d nps%n", totalNodes, totalMs,
                totalMs == 0 ? 0 : totalNodes * 1000 / totalMs);
    }

    static Move timed(Search s, Board b, long millis) {
        return s.getBestMoveTimed(b, millis, b.fenWhiteToMove);
    }

    static boolean runTactics(int depth) {
        boolean ok = true;
        for (String[] t : TACTICS) {
            Board b = Board.fromFEN(t[0]);
            Search s = new Search();
            Move[] best = new Move[1];
            quiet(() -> best[0] = s.getBestMove(b, depth, b.fenWhiteToMove));
            String got = name(best[0]);
            boolean pass = t[1] == null ? best[0] != null : t[1].equals(got);
            ok &= pass;
            System.out.printf("%-4s %-70s got %-5s expected %s%n", pass ? "OK" : "FAIL", t[0], got, t[1] == null ? "(any)" : t[1]);
        }
        System.out.println(ok ? "TACTICS PASSED" : "TACTICS FAILED");
        return ok;
    }

    // ---------------------------------------------------------------- helpers

    static String name(Move m) {
        if (m == null) return "none";
        String s = "" + (char) ('a' + m.startSquare % 8) + (char) ('1' + m.startSquare / 8)
                + (char) ('a' + m.targetSquare % 8) + (char) ('1' + m.targetSquare / 8);
        return m.promotedPiece != 0 ? s + "nbrq".charAt(m.promotedPiece - 2) : s;
    }

    /** Runs a task with System.out muted, because the engine prints a line per search. */
    static void quiet(Runnable r) {
        java.io.PrintStream real = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            r.run();
        } finally {
            System.setOut(real);
        }
    }
}
