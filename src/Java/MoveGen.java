import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Move generation.
 *
 * The engine core uses the int-based gen* methods, which write packed moves (see Move) into a
 * caller-supplied buffer and return the new end index, so no objects are allocated. The
 * List<Move> methods further down are thin wrappers kept for the web server, console game and
 * tests.
 *
 * All lookup tables (knight/king attacks and the magic bitboards) are built once per JVM and
 * shared by every MoveGen instance.
 */
public class MoveGen {

    // --- BITMASKS ---
    // Masks to prevent the "Wrap-Around Bug"
    private static final long NOT_A_FILE = ~0x0101010101010101L;
    private static final long NOT_H_FILE = ~0x8080808080808080L;

    // Masks for pieces that move two squares horizontally (Knights)
    private static final long NOT_AB_FILE = ~0x0303030303030303L;
    private static final long NOT_GH_FILE = ~0xC0C0C0C0C0C0C0C0L;

    private static final long RANK_1 = 0x00000000000000FFL;
    private static final long RANK_3 = 0x0000000000FF0000L;
    private static final long RANK_6 = 0x0000FF0000000000L;
    private static final long RANK_8 = 0xFF00000000000000L;

    // --- PRE-CALCULATED TABLES (shared) ---
    private static final long[] KNIGHT_ATTACKS = new long[64];
    private static final long[] KING_ATTACKS = new long[64];

    // Magic bitboards: for every square, the relevant blocker mask, the magic multiplier, the
    // shift that turns the product into an index, and where that square's block of the
    // flattened attack table starts.
    private static final long[] ROOK_MASKS = new long[64];
    private static final long[] BISHOP_MASKS = new long[64];
    private static final long[] ROOK_MAGICS = new long[64];
    private static final long[] BISHOP_MAGICS = new long[64];
    private static final int[] ROOK_SHIFTS = new int[64];
    private static final int[] BISHOP_SHIFTS = new int[64];
    private static final int[] ROOK_OFFSETS = new int[64];
    private static final int[] BISHOP_OFFSETS = new int[64];
    private static final long[] ROOK_TABLE;
    private static final long[] BISHOP_TABLE;

    static {
        initKnightAttacks();
        initKingAttacks();
        initSlidingMasks();

        int rookTotal = 0;
        int bishopTotal = 0;
        for (int square = 0; square < 64; square++) {
            int rookBits = Long.bitCount(ROOK_MASKS[square]);
            int bishopBits = Long.bitCount(BISHOP_MASKS[square]);
            ROOK_SHIFTS[square] = 64 - rookBits;
            BISHOP_SHIFTS[square] = 64 - bishopBits;
            ROOK_OFFSETS[square] = rookTotal;
            BISHOP_OFFSETS[square] = bishopTotal;
            rookTotal += 1 << rookBits;
            bishopTotal += 1 << bishopBits;
        }
        ROOK_TABLE = new long[rookTotal];
        BISHOP_TABLE = new long[bishopTotal];
        initMagicBitboards();
    }

    public MoveGen() {
        // All tables are static; nothing to build per instance.
    }

    // ------------------------------------------------------------------
    // Slider attack lookups
    // ------------------------------------------------------------------

    private static long rookAttacks(int square, long occupancy) {
        return ROOK_TABLE[ROOK_OFFSETS[square]
                + (int) (((occupancy & ROOK_MASKS[square]) * ROOK_MAGICS[square]) >>> ROOK_SHIFTS[square])];
    }

    private static long bishopAttacks(int square, long occupancy) {
        return BISHOP_TABLE[BISHOP_OFFSETS[square]
                + (int) (((occupancy & BISHOP_MASKS[square]) * BISHOP_MAGICS[square]) >>> BISHOP_SHIFTS[square])];
    }

    // ------------------------------------------------------------------
    // Int-based generation
    // ------------------------------------------------------------------

    /** Is the given side's king attacked? (False if that side has no king.) */
    public boolean inCheck(Board b, boolean white) {
        long king = white ? b.whiteKing : b.blackKing;
        return king != 0 && isSquareAttacked(Long.numberOfTrailingZeros(king), !white, b);
    }

    /**
     * Writes every STRICTLY LEGAL move for the side to move into buf starting at index start
     * and returns the index one past the last move. With capsOnly, only captures and queen
     * promotions are produced (for quiescence search).
     */
    public int genLegal(Board b, boolean white, int[] buf, int start, boolean capsOnly) {
        int end = genPseudo(b, white, buf, start, capsOnly);
        int out = start;
        for (int i = start; i < end; i++) {
            int m = buf[i];
            b.makeMove(m, white);
            long king = white ? b.whiteKing : b.blackKing;
            // If the king was captured in a pseudo-legal timeline there is nothing to protect.
            boolean legal = king == 0 || !isSquareAttacked(Long.numberOfTrailingZeros(king), !white, b);
            b.undoMove(white);
            if (legal)
                buf[out++] = m;
        }
        return out;
    }

    /** Pseudo-legal moves: may leave the mover's own king in check. */
    public int genPseudo(Board b, boolean white, int[] buf, int n, boolean capsOnly) {
        n = genPawns(b, white, buf, n, capsOnly);
        n = genKnights(b, white, buf, n, capsOnly);
        n = genSliders(b, white, Move.BISHOP, buf, n, capsOnly);
        n = genSliders(b, white, Move.ROOK, buf, n, capsOnly);
        n = genSliders(b, white, Move.QUEEN, buf, n, capsOnly);
        n = genKing(b, white, buf, n, capsOnly);
        return n;
    }

    private static int emit(Board b, long targets, int from, int pieceType, int[] buf, int n) {
        int base = from | (pieceType << 12);
        while (targets != 0) {
            int to = Long.numberOfTrailingZeros(targets);
            targets &= targets - 1;
            buf[n++] = base | (to << 6) | ((b.pieceAt[to] & 7) << 15);
        }
        return n;
    }

    private int genPawns(Board b, boolean white, int[] buf, int n, boolean capsOnly) {
        long empty = ~b.allPieces;

        if (white) {
            long pawns = b.whitePawns;
            long enemy = b.blackPieces;

            long single = (pawns << 8) & empty;
            if (capsOnly) {
                n = pawnMoves(b, single & RANK_8, -8, false, false, buf, n, true);
            } else {
                n = pawnMoves(b, single, -8, false, false, buf, n, false);
                long doublePush = ((single & RANK_3) << 8) & empty;
                n = pawnMoves(b, doublePush, -16, false, true, buf, n, false);
            }
            n = pawnMoves(b, ((pawns & NOT_H_FILE) << 9) & enemy, -9, true, false, buf, n, capsOnly);
            n = pawnMoves(b, ((pawns & NOT_A_FILE) << 7) & enemy, -7, true, false, buf, n, capsOnly);

            // --- EN PASSANT (WHITE) ---
            int ep = b.enPassantTarget;
            if (ep >= 40) {
                long epMask = 1L << ep;
                int base = (Move.PAWN << 12) | (Move.PAWN << 15) | Move.EN_PASSANT | (ep << 6);
                if ((((pawns & NOT_H_FILE) << 9) & epMask) != 0)
                    buf[n++] = base | (ep - 9);
                if ((((pawns & NOT_A_FILE) << 7) & epMask) != 0)
                    buf[n++] = base | (ep - 7);
            }
        } else {
            long pawns = b.blackPawns;
            long enemy = b.whitePieces;

            long single = (pawns >>> 8) & empty;
            if (capsOnly) {
                n = pawnMoves(b, single & RANK_1, 8, false, false, buf, n, true);
            } else {
                n = pawnMoves(b, single, 8, false, false, buf, n, false);
                long doublePush = ((single & RANK_6) >>> 8) & empty;
                n = pawnMoves(b, doublePush, 16, false, true, buf, n, false);
            }
            n = pawnMoves(b, ((pawns & NOT_A_FILE) >>> 9) & enemy, 9, true, false, buf, n, capsOnly);
            n = pawnMoves(b, ((pawns & NOT_H_FILE) >>> 7) & enemy, 7, true, false, buf, n, capsOnly);

            // --- EN PASSANT (BLACK) ---
            int ep = b.enPassantTarget;
            if (ep != -1 && ep < 24) {
                long epMask = 1L << ep;
                int base = (Move.PAWN << 12) | (Move.PAWN << 15) | Move.EN_PASSANT | (ep << 6);
                if ((((pawns & NOT_A_FILE) >>> 9) & epMask) != 0)
                    buf[n++] = base | (ep + 9);
                if ((((pawns & NOT_H_FILE) >>> 7) & epMask) != 0)
                    buf[n++] = base | (ep + 7);
            }
        }
        return n;
    }

    /**
     * Turns a bitboard of pawn target squares into moves, expanding promotions. The start
     * square is the target square plus delta.
     */
    private static int pawnMoves(Board b, long targets, int delta, boolean capture, boolean doublePush,
            int[] buf, int n, boolean queenOnly) {
        while (targets != 0) {
            int to = Long.numberOfTrailingZeros(targets);
            targets &= targets - 1;
            int base = (to + delta) | (to << 6) | (Move.PAWN << 12);
            if (capture)
                base |= (b.pieceAt[to] & 7) << 15;
            if (to >= 56 || to <= 7) {
                if (queenOnly) {
                    buf[n++] = base | (Move.QUEEN << 18);
                } else {
                    for (int piece = Move.QUEEN; piece >= Move.KNIGHT; piece--)
                        buf[n++] = base | (piece << 18);
                }
            } else {
                buf[n++] = doublePush ? (base | Move.DOUBLE_PUSH) : base;
            }
        }
        return n;
    }

    private int genKnights(Board b, boolean white, int[] buf, int n, boolean capsOnly) {
        long knights = white ? b.whiteKnights : b.blackKnights;
        long own = white ? b.whitePieces : b.blackPieces;
        long enemy = white ? b.blackPieces : b.whitePieces;
        long valid = capsOnly ? enemy : ~own;

        while (knights != 0) {
            int from = Long.numberOfTrailingZeros(knights);
            knights &= knights - 1;
            n = emit(b, KNIGHT_ATTACKS[from] & valid, from, Move.KNIGHT, buf, n);
        }
        return n;
    }

    private int genSliders(Board b, boolean white, int pieceType, int[] buf, int n, boolean capsOnly) {
        long pieces;
        if (pieceType == Move.BISHOP)
            pieces = white ? b.whiteBishops : b.blackBishops;
        else if (pieceType == Move.ROOK)
            pieces = white ? b.whiteRooks : b.blackRooks;
        else
            pieces = white ? b.whiteQueens : b.blackQueens;

        long own = white ? b.whitePieces : b.blackPieces;
        long enemy = white ? b.blackPieces : b.whitePieces;
        long valid = capsOnly ? enemy : ~own;
        long occupancy = b.allPieces;

        while (pieces != 0) {
            int from = Long.numberOfTrailingZeros(pieces);
            pieces &= pieces - 1;
            long attacks;
            if (pieceType == Move.BISHOP)
                attacks = bishopAttacks(from, occupancy);
            else if (pieceType == Move.ROOK)
                attacks = rookAttacks(from, occupancy);
            else
                attacks = rookAttacks(from, occupancy) | bishopAttacks(from, occupancy);
            n = emit(b, attacks & valid, from, pieceType, buf, n);
        }
        return n;
    }

    private int genKing(Board b, boolean white, int[] buf, int n, boolean capsOnly) {
        long kingBoard = white ? b.whiteKing : b.blackKing;
        if (kingBoard == 0)
            return n;

        long own = white ? b.whitePieces : b.blackPieces;
        long enemy = white ? b.blackPieces : b.whitePieces;
        long valid = capsOnly ? enemy : ~own;
        int from = Long.numberOfTrailingZeros(kingBoard);
        n = emit(b, KING_ATTACKS[from] & valid, from, Move.KING, buf, n);

        if (capsOnly)
            return n;

        // --- CASTLING ---
        // The king must be on its home square; the squares it crosses must be empty and safe.
        // Only the king squares need to be unattacked: the rook may pass through attacked squares.
        if (white && from == 4) {
            if (b.whiteCanCastleKingside && (b.whiteRooks & (1L << 7)) != 0
                    && (b.allPieces & ((1L << 5) | (1L << 6))) == 0
                    && !isSquareAttacked(4, false, b) && !isSquareAttacked(5, false, b)
                    && !isSquareAttacked(6, false, b)) {
                buf[n++] = 4 | (6 << 6) | (Move.KING << 12) | Move.CASTLE;
            }
            if (b.whiteCanCastleQueenside && (b.whiteRooks & 1L) != 0
                    && (b.allPieces & ((1L << 1) | (1L << 2) | (1L << 3))) == 0
                    && !isSquareAttacked(4, false, b) && !isSquareAttacked(3, false, b)
                    && !isSquareAttacked(2, false, b)) {
                buf[n++] = 4 | (2 << 6) | (Move.KING << 12) | Move.CASTLE;
            }
        } else if (!white && from == 60) {
            if (b.blackCanCastleKingside && (b.blackRooks & (1L << 63)) != 0
                    && (b.allPieces & ((1L << 61) | (1L << 62))) == 0
                    && !isSquareAttacked(60, true, b) && !isSquareAttacked(61, true, b)
                    && !isSquareAttacked(62, true, b)) {
                buf[n++] = 60 | (62 << 6) | (Move.KING << 12) | Move.CASTLE;
            }
            if (b.blackCanCastleQueenside && (b.blackRooks & (1L << 56)) != 0
                    && (b.allPieces & ((1L << 57) | (1L << 58) | (1L << 59))) == 0
                    && !isSquareAttacked(60, true, b) && !isSquareAttacked(59, true, b)
                    && !isSquareAttacked(58, true, b)) {
                buf[n++] = 60 | (58 << 6) | (Move.KING << 12) | Move.CASTLE;
            }
        }
        return n;
    }

    /**
     * Checks if a specific square is attacked by the given colour.
     *
     * @param square  The square index to check (0-63)
     * @param byWhite True if we are checking whether WHITE attacks the square.
     * @param board   The current board state
     * @return True if the square is under attack, false if it is safe.
     */
    public boolean isSquareAttacked(int square, boolean byWhite, Board board) {
        long squareBit = 1L << square;
        long occupancy = board.allPieces;

        if (byWhite) {
            // Pawns: a white pawn attacks up-left/up-right, so look down-left/down-right of the square.
            if ((((squareBit & NOT_A_FILE) >>> 9) & board.whitePawns) != 0) return true;
            if ((((squareBit & NOT_H_FILE) >>> 7) & board.whitePawns) != 0) return true;
            if ((KNIGHT_ATTACKS[square] & board.whiteKnights) != 0) return true;
            if ((KING_ATTACKS[square] & board.whiteKing) != 0) return true;
            if ((bishopAttacks(square, occupancy) & (board.whiteBishops | board.whiteQueens)) != 0) return true;
            if ((rookAttacks(square, occupancy) & (board.whiteRooks | board.whiteQueens)) != 0) return true;
        } else {
            if ((((squareBit & NOT_H_FILE) << 9) & board.blackPawns) != 0) return true;
            if ((((squareBit & NOT_A_FILE) << 7) & board.blackPawns) != 0) return true;
            if ((KNIGHT_ATTACKS[square] & board.blackKnights) != 0) return true;
            if ((KING_ATTACKS[square] & board.blackKing) != 0) return true;
            if ((bishopAttacks(square, occupancy) & (board.blackBishops | board.blackQueens)) != 0) return true;
            if ((rookAttacks(square, occupancy) & (board.blackRooks | board.blackQueens)) != 0) return true;
        }

        return false; // If we survive all checks, the square is perfectly safe!
    }

    // ------------------------------------------------------------------
    // Move-object wrappers (web server, console game, tests)
    // ------------------------------------------------------------------

    private static List<Move> toMoves(int[] buf, int count) {
        List<Move> moves = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            moves.add(Move.fromInt(buf[i]));
        return moves;
    }

    public List<Move> generateWhitePawnMoves(Board board) {
        int[] buf = new int[128];
        return toMoves(buf, genPawns(board, true, buf, 0, false));
    }

    public List<Move> generateBlackPawnMoves(Board board) {
        int[] buf = new int[128];
        return toMoves(buf, genPawns(board, false, buf, 0, false));
    }

    public List<Move> generateWhiteKnightMoves(Board board) {
        int[] buf = new int[128];
        return toMoves(buf, genKnights(board, true, buf, 0, false));
    }

    public List<Move> generateBlackKnightMoves(Board board) {
        int[] buf = new int[128];
        return toMoves(buf, genKnights(board, false, buf, 0, false));
    }

    public List<Move> generateWhiteKingMoves(Board board) {
        int[] buf = new int[16];
        return toMoves(buf, genKing(board, true, buf, 0, false));
    }

    public List<Move> generateBlackKingMoves(Board board) {
        int[] buf = new int[16];
        return toMoves(buf, genKing(board, false, buf, 0, false));
    }

    public List<Move> generateWhiteRookMoves(Board board) {
        int[] buf = new int[256];
        return toMoves(buf, genSliders(board, true, Move.ROOK, buf, 0, false));
    }

    public List<Move> generateBlackRookMoves(Board board) {
        int[] buf = new int[256];
        return toMoves(buf, genSliders(board, false, Move.ROOK, buf, 0, false));
    }

    public List<Move> generateWhiteBishopMoves(Board board) {
        int[] buf = new int[256];
        return toMoves(buf, genSliders(board, true, Move.BISHOP, buf, 0, false));
    }

    public List<Move> generateBlackBishopMoves(Board board) {
        int[] buf = new int[256];
        return toMoves(buf, genSliders(board, false, Move.BISHOP, buf, 0, false));
    }

    public List<Move> generateWhiteQueenMoves(Board board) {
        int[] buf = new int[256];
        return toMoves(buf, genSliders(board, true, Move.QUEEN, buf, 0, false));
    }

    public List<Move> generateBlackQueenMoves(Board board) {
        int[] buf = new int[256];
        return toMoves(buf, genSliders(board, false, Move.QUEEN, buf, 0, false));
    }

    /** Gathers every pseudo-legal move for White into one master list. */
    public List<Move> generateAllWhiteMoves(Board board) {
        int[] buf = new int[512];
        return toMoves(buf, genPseudo(board, true, buf, 0, false));
    }

    /** Gathers every pseudo-legal move for Black into one master list. */
    public List<Move> generateAllBlackMoves(Board board) {
        int[] buf = new int[512];
        return toMoves(buf, genPseudo(board, false, buf, 0, false));
    }

    /**
     * Generates only STRICTLY LEGAL moves by simulating each pseudo-legal move
     * and ensuring it does not leave the King in check.
     */
    public List<Move> getLegalMoves(Board board, boolean isWhite) {
        int[] buf = new int[512];
        return toMoves(buf, genLegal(board, isWhite, buf, 0, false));
    }

    // --- GETTERS ---
    public long getRookMask(int square) {
        return ROOK_MASKS[square];
    }

    public long getBishopMask(int square) {
        return BISHOP_MASKS[square];
    }

    // ------------------------------------------------------------------
    // Table construction (runs once per JVM)
    // ------------------------------------------------------------------

    private static void initKnightAttacks() {
        for (int square = 0; square < 64; square++) {
            long knight = 1L << square;
            long attacks = 0L;

            // Generate the 8 possible L-shapes
            attacks |= (knight << 17) & NOT_A_FILE;
            attacks |= (knight << 15) & NOT_H_FILE;
            attacks |= (knight << 10) & NOT_AB_FILE;
            attacks |= (knight << 6) & NOT_GH_FILE;

            attacks |= (knight >>> 17) & NOT_H_FILE;
            attacks |= (knight >>> 15) & NOT_A_FILE;
            attacks |= (knight >>> 10) & NOT_GH_FILE;
            attacks |= (knight >>> 6) & NOT_AB_FILE;

            KNIGHT_ATTACKS[square] = attacks;
        }
    }

    private static void initKingAttacks() {
        for (int square = 0; square < 64; square++) {
            long king = 1L << square;
            long attacks = 0L;

            // Horizontal and Vertical
            attacks |= (king << 8); // Up
            attacks |= (king >>> 8); // Down
            attacks |= (king & NOT_H_FILE) << 1; // Right (+1 index)
            attacks |= (king & NOT_A_FILE) >>> 1; // Left (-1 index)

            // Diagonals
            attacks |= (king & NOT_H_FILE) << 9; // Up-Right
            attacks |= (king & NOT_A_FILE) << 7; // Up-Left
            attacks |= (king & NOT_H_FILE) >>> 7; // Down-Right
            attacks |= (king & NOT_A_FILE) >>> 9; // Down-Left

            KING_ATTACKS[square] = attacks;
        }
    }

    private static void initSlidingMasks() {
        for (int square = 0; square < 64; square++) {
            ROOK_MASKS[square] = maskRookAttacks(square);
            BISHOP_MASKS[square] = maskBishopAttacks(square);
        }
    }

    private static void initMagicBitboards() {
        for (int square = 0; square < 64; square++) {
            // --- ROOKS ---
            long rookMask = ROOK_MASKS[square];
            int rookBits = Long.bitCount(rookMask);
            ROOK_MAGICS[square] = findMagic(square, rookBits, false);
            for (int i = 0; i < (1 << rookBits); i++) {
                long blockers = setOccupancy(i, rookBits, rookMask);
                int index = (int) ((blockers * ROOK_MAGICS[square]) >>> ROOK_SHIFTS[square]);
                ROOK_TABLE[ROOK_OFFSETS[square] + index] = rookAttacksOnTheFly(square, blockers);
            }

            // --- BISHOPS ---
            long bishopMask = BISHOP_MASKS[square];
            int bishopBits = Long.bitCount(bishopMask);
            BISHOP_MAGICS[square] = findMagic(square, bishopBits, true);
            for (int i = 0; i < (1 << bishopBits); i++) {
                long blockers = setOccupancy(i, bishopBits, bishopMask);
                int index = (int) ((blockers * BISHOP_MAGICS[square]) >>> BISHOP_SHIFTS[square]);
                BISHOP_TABLE[BISHOP_OFFSETS[square] + index] = bishopAttacksOnTheFly(square, blockers);
            }
        }
    }

    private static long randomSparse(Random random) {
        // ANDing three random longs drastically reduces the number of '1' bits,
        // creating the sparse numbers needed for perfect hashing.
        return random.nextLong() & random.nextLong() & random.nextLong();
    }

    private static long findMagic(int square, int bits, boolean isBishop) {
        long mask = isBishop ? BISHOP_MASKS[square] : ROOK_MASKS[square];
        int numCombinations = 1 << bits;
        long[] occupancies = new long[numCombinations];
        long[] attacks = new long[numCombinations];
        long[] used = new long[numCombinations];

        // Pre-calculate all occupancies and their true ray-cast attacks
        for (int i = 0; i < numCombinations; i++) {
            occupancies[i] = setOccupancy(i, bits, mask);
            attacks[i] = isBishop ? bishopAttacksOnTheFly(square, occupancies[i])
                    : rookAttacksOnTheFly(square, occupancies[i]);
        }

        Random random = new Random(100); // Fixed seed so the engine boots up consistently

        // Brute-force a Magic Number
        for (int k = 0; k < 100000000; k++) {
            long magic = randomSparse(random);
            Arrays.fill(used, 0L);
            boolean fail = false;

            for (int i = 0; !fail && i < numCombinations; i++) {
                int magicIndex = (int) ((occupancies[i] * magic) >>> (64 - bits));

                if (used[magicIndex] == 0L) {
                    used[magicIndex] = attacks[i]; // Slot is empty, save the attack
                } else if (used[magicIndex] != attacks[i]) {
                    fail = true; // Hash collision! This magic number is bad.
                }
            }

            if (!fail) {
                return magic; // We found a perfect hash!
            }
        }
        throw new IllegalStateException("Failed to find magic for square " + square);
    }

    /**
     * Generates a mask for a Rook on a specific square, EXCLUDING the outer edges.
     */
    private static long maskRookAttacks(int square) {
        long mask = 0L;
        int targetRank = square / 8;
        int targetFile = square % 8;

        for (int r = targetRank + 1; r <= 6; r++) {
            mask |= (1L << (r * 8 + targetFile));
        }
        for (int r = targetRank - 1; r >= 1; r--) {
            mask |= (1L << (r * 8 + targetFile));
        }
        for (int f = targetFile + 1; f <= 6; f++) {
            mask |= (1L << (targetRank * 8 + f));
        }
        for (int f = targetFile - 1; f >= 1; f--) {
            mask |= (1L << (targetRank * 8 + f));
        }
        return mask;
    }

    /**
     * Generates a mask for a Bishop on a specific square, EXCLUDING the outer edges.
     */
    private static long maskBishopAttacks(int square) {
        long mask = 0L;
        int targetRank = square / 8;
        int targetFile = square % 8;

        for (int r = targetRank + 1, f = targetFile + 1; r <= 6 && f <= 6; r++, f++) {
            mask |= (1L << (r * 8 + f));
        }
        for (int r = targetRank + 1, f = targetFile - 1; r <= 6 && f >= 1; r++, f--) {
            mask |= (1L << (r * 8 + f));
        }
        for (int r = targetRank - 1, f = targetFile + 1; r >= 1 && f <= 6; r--, f++) {
            mask |= (1L << (r * 8 + f));
        }
        for (int r = targetRank - 1, f = targetFile - 1; r >= 1 && f >= 1; r--, f--) {
            mask |= (1L << (r * 8 + f));
        }
        return mask;
    }

    /**
     * Maps an integer index to a specific combination of blockers on a mask.
     */
    private static long setOccupancy(int index, int bitsInMask, long attackMask) {
        long occupancy = 0L;

        for (int count = 0; count < bitsInMask; count++) {
            int square = Long.numberOfTrailingZeros(attackMask);
            attackMask &= (attackMask - 1);
            if ((index & (1 << count)) != 0) {
                occupancy |= (1L << square);
            }
        }
        return occupancy;
    }

    /**
     * Calculates true Rook attacks using slow ray casting.
     * ONLY USED DURING INITIALIZATION to populate the Magic arrays.
     */
    private static long rookAttacksOnTheFly(int square, long block) {
        long attacks = 0L;
        int targetRank = square / 8;
        int targetFile = square % 8;

        for (int r = targetRank + 1; r <= 7; r++) {
            attacks |= (1L << (r * 8 + targetFile));
            if ((block & (1L << (r * 8 + targetFile))) != 0) break;
        }
        for (int r = targetRank - 1; r >= 0; r--) {
            attacks |= (1L << (r * 8 + targetFile));
            if ((block & (1L << (r * 8 + targetFile))) != 0) break;
        }
        for (int f = targetFile + 1; f <= 7; f++) {
            attacks |= (1L << (targetRank * 8 + f));
            if ((block & (1L << (targetRank * 8 + f))) != 0) break;
        }
        for (int f = targetFile - 1; f >= 0; f--) {
            attacks |= (1L << (targetRank * 8 + f));
            if ((block & (1L << (targetRank * 8 + f))) != 0) break;
        }
        return attacks;
    }

    /**
     * Calculates true Bishop attacks using slow ray casting.
     * ONLY USED DURING INITIALIZATION to populate the Magic arrays.
     */
    private static long bishopAttacksOnTheFly(int square, long block) {
        long attacks = 0L;
        int targetRank = square / 8;
        int targetFile = square % 8;

        for (int r = targetRank + 1, f = targetFile + 1; r <= 7 && f <= 7; r++, f++) {
            attacks |= (1L << (r * 8 + f));
            if ((block & (1L << (r * 8 + f))) != 0) break;
        }
        for (int r = targetRank + 1, f = targetFile - 1; r <= 7 && f >= 0; r++, f--) {
            attacks |= (1L << (r * 8 + f));
            if ((block & (1L << (r * 8 + f))) != 0) break;
        }
        for (int r = targetRank - 1, f = targetFile + 1; r >= 0 && f <= 7; r--, f++) {
            attacks |= (1L << (r * 8 + f));
            if ((block & (1L << (r * 8 + f))) != 0) break;
        }
        for (int r = targetRank - 1, f = targetFile - 1; r >= 0 && f >= 0; r--, f--) {
            attacks |= (1L << (r * 8 + f));
            if ((block & (1L << (r * 8 + f))) != 0) break;
        }
        return attacks;
    }
}
