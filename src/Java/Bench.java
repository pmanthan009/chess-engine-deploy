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
 *   java Bench rules            game rules and the API: mate, stalemate, repetition, 50 moves, promotion
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
            case "rules": System.exit(runRules() ? 0 : 1); break;
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
            int clockBefore = b.halfmoveClock;
            b.makeMove(buf[i], white);
            if (verify) {
                boolean irreversible = Move.piece(buf[i]) == Move.PAWN || Move.captured(buf[i]) != 0;
                if (b.halfmoveClock != (irreversible ? 0 : clockBefore + 1))
                    throw new IllegalStateException("halfmove clock wrong after " + Move.toUci(buf[i]));
            }
            total += perftInt(gen, b, depth - 1, !white, bufs, ply + 1, verify);
            b.undoMove(white);
            if (verify && b.halfmoveClock != clockBefore)
                throw new IllegalStateException("halfmove clock not restored after " + Move.toUci(buf[i]));
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

    // ------------------------------------------------------- rules and API tests

    static int ruleFailures;

    static void expect(String name, boolean ok, String detail) {
        if (!ok)
            ruleFailures++;
        System.out.println((ok ? "OK   " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    static String[] respond(String request) {
        try {
            return GameService.respond(request).split("[|]", -1);
        } catch (GameService.BadRequest e) {
            return new String[] { "ERROR", e.getMessage() };
        }
    }

    static boolean runRules() {
        ruleFailures = 0;
        String shuffle = "g1f3 g8f6 f3g1 f6g8";

        String[] r = respond("PVP|" + shuffle + "|WHITE");
        expect("one cycle of knight shuffling is not a draw", r.length == 7 && r[5].equals("ONGOING"), String.join("|", r));
        r = respond("PVP|" + shuffle + " g1f3 g8f6 f3g1|WHITE");
        expect("second occurrence is not yet a draw", r[5].equals("ONGOING"), String.join("|", r));
        r = respond("PVP|" + shuffle + " " + shuffle + "|WHITE");
        expect("threefold repetition is a draw", r[5].equals("DRAW_REPETITION") && r[2].isEmpty(), String.join("|", r));

        r = respond("PVP|f2f3 e7e5 g2g4 d8h4|WHITE");
        expect("fool's mate is checkmate with White to move and no legal moves",
                r[5].equals("CHECKMATE") && r[3].equals("White") && r[2].isEmpty(), String.join("|", r));

        r = respond("PVP|e2e3 a7a5 d1h5 a8a6 h5a5 h7h5 h2h4 a6h6 a5c7 f7f6 c7d7 e8f7 d7b7 d8d3 b7b8 d3h7 b8c8 f7g6 c8e6|WHITE");
        expect("Loyd's 19 move game ends in stalemate", r[5].equals("STALEMATE") && r[3].equals("Black"), String.join("|", r));

        // Fifty-move rule: clock at 98, two quiet moves reach 100.
        MoveGen gen = new MoveGen();
        Board b = Board.fromFEN("8/8/8/4k3/8/8/4K3/R7 w - - 98 80");
        int[] buf = new int[512];
        int n = gen.genLegal(b, true, buf, 0, false);
        b.makeMove(buf[0], true);
        n = gen.genLegal(b, false, buf, 0, false);
        expect("clock 99 is not yet a draw", GameService.statusOf(b, false, n).equals("ONGOING") && b.halfmoveClock == 99,
                "clock " + b.halfmoveClock);
        b.makeMove(buf[0], false);
        n = gen.genLegal(b, true, buf, 0, false);
        expect("clock 100 is a draw", GameService.statusOf(b, true, n).equals("DRAW_50") && b.halfmoveClock == 100,
                "clock " + b.halfmoveClock);

        // A capture resets the clock.
        b = Board.fromFEN("8/8/8/4k3/8/8/r3K3/R7 w - - 99 80");
        n = gen.genLegal(b, true, buf, 0, false);
        int capture = 0;
        for (int i = 0; i < n; i++)
            if (Move.captured(buf[i]) != 0)
                capture = buf[i];
        b.makeMove(capture, true);
        expect("capture resets the fifty-move clock", capture != 0 && b.halfmoveClock == 0, "clock " + b.halfmoveClock);
        b.undoMove(true);
        expect("undo restores the clock", b.halfmoveClock == 99, "clock " + b.halfmoveClock);

        // The search treats the fifty-move limit as a draw even when it is totally winning.
        Search search = new Search();
        Board winning = Board.fromFEN("8/8/8/4k3/8/8/4K3/R7 w - - 99 80");
        quiet(() -> search.getBestMove(winning, 4, true));
        expect("search scores the move that hits 100 plies as a draw", search.lastEval().equals("0"), search.lastEval());
        Board normal = Board.fromFEN("8/8/8/4k3/8/8/4K3/R7 w - - 0 80");
        quiet(() -> search.getBestMove(normal, 4, true));
        expect("same position with a fresh clock is winning", Integer.parseInt(search.lastEval()) > 300, search.lastEval());

        // Promotion: no letter means queen, a letter picks the piece.
        String setup = "a2a4 b7b5 a4b5 a7a6 b5a6 c8b7 a6b7 b8c6";
        r = respond("PVP|" + setup + " b7b8n|WHITE");
        expect("underpromotion to a knight", r[0].startsWith("rN"), r[0]);
        r = respond("PVP|" + setup + " b7b8|WHITE");
        expect("promotion without a letter is a queen", r[0].startsWith("rQ"), r[0]);
        r = respond("PVP|" + setup + "|WHITE");
        expect("promotion moves are listed with their letters",
                r[2].contains("b7b8q") && r[2].contains("b7b8n") && r[2].contains("b7a8r"), r[2]);

        // Protocol
        r = respond("CPU||BLACK");
        expect("engine opens when the human plays Black",
                r.length == 7 && r[1].length() == 4 && r[3].equals("Black") && !r[4].isEmpty() && !r[6].isEmpty(),
                String.join("|", r));
        r = respond("CPU|e2e4|WHITE");
        expect("engine answers e2e4", r.length == 7 && !r[1].isEmpty() && r[3].equals("White") && !r[4].isEmpty()
                && r[5].equals("ONGOING") && !r[6].isEmpty(), String.join("|", r));
        r = respond("EVAL|e2e4|WHITE");
        expect("analysis returns a score and no move", r[1].isEmpty() && !r[4].isEmpty() && r[3].equals("Black"),
                String.join("|", r));
        r = respond("PVP|e2e4|WHITE");
        expect("PvP mode never moves for anyone", r[1].isEmpty() && r[4].isEmpty() && r[6].isEmpty(), String.join("|", r));
        r = respond("CPU|f2f3 e7e5 g2g4 d8h4|WHITE");
        expect("no engine move after the game is over", r[1].isEmpty() && r[5].equals("CHECKMATE"), String.join("|", r));
        expect("illegal move is rejected", respond("PVP|e2e5|WHITE")[0].equals("ERROR"), "");
        expect("garbage move is rejected", respond("PVP|hello|WHITE")[0].equals("ERROR"), "");
        expect("bad mode is rejected", respond("XYZ||WHITE")[0].equals("ERROR"), "");
        expect("old single-move request is rejected", respond("CPU|RESET|WHITE")[0].equals("ERROR"), "");
        r = respond("PVP|e2e4 e7e5|WHITE");
        expect("full FEN carries turn, castling and move number",
                r[0].equals("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"), r[0]);

        System.out.println(ruleFailures == 0 ? "RULES PASSED" : "RULES FAILED (" + ruleFailures + ")");
        return ruleFailures == 0;
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
