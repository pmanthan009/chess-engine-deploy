import java.util.Arrays;
import java.util.Random;

/**
 * Bitboard chess position.
 *
 * Moves are made and unmade as packed ints (see Move); the undo information lives in a
 * stack inside the board, so searching allocates nothing. A mailbox array (pieceAt) is kept in
 * sync with the bitboards for O(1) "what is on this square" lookups, and a Zobrist hash is
 * maintained incrementally for the transposition table.
 *
 * Call updateOccupancies() after editing bitboards by hand, and resetHash(boolean) before
 * relying on the hash field.
 */
public class Board {

    long whitePawns, whiteKnights, whiteBishops, whiteRooks, whiteQueens, whiteKing;
    long blackPawns, blackKnights, blackBishops, blackRooks, blackQueens, blackKing;

    long whitePieces, blackPieces, allPieces;

    /** Mailbox: 0 = empty, otherwise pieceType (1..6) plus 8 for Black pieces. */
    final int[] pieceAt = new int[64];

    /** Zobrist hash of the position, including the side to move (see resetHash). */
    long hash;

    // --- SPECIAL MOVE STATE ---
    // Tracks if the King and Rooks have moved yet
    public boolean whiteCanCastleKingside;
    public boolean whiteCanCastleQueenside;
    public boolean blackCanCastleKingside;
    public boolean blackCanCastleQueenside;

    // Tracks the target square index for an En Passant capture (-1 if none is
    // available)
    public int enPassantTarget;

    /** Side to move as read by fromFEN (the Board itself does not track the turn). */
    public boolean fenWhiteToMove = true;

    // --- ZOBRIST KEYS ---
    private static final long[][] Z_PIECE = new long[12][64]; // [colour * 6 + type - 1][square]
    private static final long Z_SIDE;
    private static final long[] Z_CASTLE = new long[16];
    private static final long[] Z_EP = new long[8];

    static {
        Random rng = new Random(0x2545F4914F6CDD1DL);
        for (int p = 0; p < 12; p++)
            for (int sq = 0; sq < 64; sq++)
                Z_PIECE[p][sq] = rng.nextLong();
        Z_SIDE = rng.nextLong();
        for (int i = 0; i < 16; i++)
            Z_CASTLE[i] = rng.nextLong();
        for (int i = 0; i < 8; i++)
            Z_EP[i] = rng.nextLong();
    }

    // Castling rights (bit 1 = white O-O, 2 = white O-O-O, 4 = black O-O, 8 = black O-O-O)
    // that are lost when a piece moves from or to a given square.
    private static final int[] CASTLE_LOST = new int[64];

    static {
        CASTLE_LOST[4] = 1 | 2;
        CASTLE_LOST[0] = 2;
        CASTLE_LOST[7] = 1;
        CASTLE_LOST[60] = 4 | 8;
        CASTLE_LOST[56] = 8;
        CASTLE_LOST[63] = 4;
    }

    // --- UNDO STACK ---
    private int[] stMove = new int[256];
    private int[] stCastle = new int[256];
    private int[] stEp = new int[256];
    private long[] stHash = new long[256];
    private int sp = 0;

    public Board() {
        setupStandardPosition();
        // At the start of a standard game, everyone can castle!
        whiteCanCastleKingside = true;
        whiteCanCastleQueenside = true;
        blackCanCastleKingside = true;
        blackCanCastleQueenside = true;

        // No En Passant target on turn 1
        enPassantTarget = -1;
        resetHash(true);
    }

    private void setupStandardPosition() {
        // WHITE PIECES (Ranks 1 and 2)
        whitePawns = 0x000000000000FF00L; // Rank 2
        whiteRooks = 0x0000000000000081L; // A1 (bit 0) and H1 (bit 7)
        whiteKnights = 0x0000000000000042L; // B1 (bit 1) and G1 (bit 6)
        whiteBishops = 0x0000000000000024L; // C1 (bit 2) and F1 (bit 5)
        whiteQueens = 0x0000000000000008L; // D1 (bit 3)
        whiteKing = 0x0000000000000010L; // E1 (bit 4)

        // BLACK PIECES (Ranks 7 and 8)
        blackPawns = 0x00FF000000000000L; // Rank 7
        blackRooks = 0x8100000000000000L; // A8 (bit 56) and H8 (bit 63)
        blackKnights = 0x4200000000000000L; // B8 and G8
        blackBishops = 0x2400000000000000L; // C8 and F8
        blackQueens = 0x0800000000000000L; // D8
        blackKing = 0x1000000000000000L; // E8

        updateOccupancies();
    }

    // ------------------------------------------------------------------
    // Fast int-move interface
    // ------------------------------------------------------------------

    /**
     * Executes a packed move. The move must come from the move generator (or from encode),
     * which fills in the moving and captured piece types.
     */
    public void makeMove(int m, boolean white) {
        if (sp == stMove.length)
            growStack();
        stMove[sp] = m;
        stCastle[sp] = castleBits();
        stEp[sp] = enPassantTarget;
        stHash[sp] = hash;
        sp++;

        int from = m & 63;
        int to = (m >>> 6) & 63;
        int pt = (m >>> 12) & 7;
        int cap = (m >>> 15) & 7;
        int promo = (m >>> 18) & 7;

        long h = hash ^ Z_SIDE;
        if (enPassantTarget != -1) {
            h ^= Z_EP[enPassantTarget & 7];
            enPassantTarget = -1;
        }

        long fromBB = 1L << from;
        long toBB = 1L << to;
        int ownOffset = white ? 0 : 6;

        // Lift the moving piece off its start square
        xorPiece(white, pt, fromBB);
        pieceAt[from] = 0;
        h ^= Z_PIECE[ownOffset + pt - 1][from];

        // Remove the captured piece (for En Passant it sits behind the target square)
        if (cap != 0) {
            int capSq = ((m & Move.EN_PASSANT) != 0) ? (white ? to - 8 : to + 8) : to;
            long capBB = 1L << capSq;
            xorPiece(!white, cap, capBB);
            pieceAt[capSq] = 0;
            h ^= Z_PIECE[(white ? 6 : 0) + cap - 1][capSq];
            if (white)
                blackPieces ^= capBB;
            else
                whitePieces ^= capBB;
        }

        // Put the piece (or its promotion) on the target square
        int placed = promo != 0 ? promo : pt;
        xorPiece(white, placed, toBB);
        pieceAt[to] = white ? placed : (placed | 8);
        h ^= Z_PIECE[ownOffset + placed - 1][to];
        if (white)
            whitePieces ^= fromBB | toBB;
        else
            blackPieces ^= fromBB | toBB;

        // Castling also moves the rook
        if ((m & Move.CASTLE) != 0) {
            int rookFrom, rookTo;
            if (to == 6) {
                rookFrom = 7;
                rookTo = 5;
            } else if (to == 2) {
                rookFrom = 0;
                rookTo = 3;
            } else if (to == 62) {
                rookFrom = 63;
                rookTo = 61;
            } else {
                rookFrom = 56;
                rookTo = 59;
            }
            long rookMask = (1L << rookFrom) | (1L << rookTo);
            if (white) {
                whiteRooks ^= rookMask;
                whitePieces ^= rookMask;
            } else {
                blackRooks ^= rookMask;
                blackPieces ^= rookMask;
            }
            pieceAt[rookFrom] = 0;
            pieceAt[rookTo] = white ? Move.ROOK : (Move.ROOK | 8);
            h ^= Z_PIECE[ownOffset + Move.ROOK - 1][rookFrom] ^ Z_PIECE[ownOffset + Move.ROOK - 1][rookTo];
        }

        // A double pawn push opens an En Passant target on the skipped square
        if ((m & Move.DOUBLE_PUSH) != 0) {
            enPassantTarget = (from + to) >> 1;
            h ^= Z_EP[enPassantTarget & 7];
        }

        // Moving a king or rook, or capturing a rook on its home square, costs castling rights
        int lost = CASTLE_LOST[from] | CASTLE_LOST[to];
        if (lost != 0) {
            int before = castleBits();
            if ((lost & 1) != 0)
                whiteCanCastleKingside = false;
            if ((lost & 2) != 0)
                whiteCanCastleQueenside = false;
            if ((lost & 4) != 0)
                blackCanCastleKingside = false;
            if ((lost & 8) != 0)
                blackCanCastleQueenside = false;
            h ^= Z_CASTLE[before] ^ Z_CASTLE[castleBits()];
        }

        allPieces = whitePieces | blackPieces;
        hash = h;
    }

    /** Reverts the most recent makeMove or makeNullMove. */
    public void undoMove(boolean white) {
        sp--;
        int m = stMove[sp];

        if (m != 0) {
            int from = m & 63;
            int to = (m >>> 6) & 63;
            int pt = (m >>> 12) & 7;
            int cap = (m >>> 15) & 7;
            int promo = (m >>> 18) & 7;

            long fromBB = 1L << from;
            long toBB = 1L << to;
            int placed = promo != 0 ? promo : pt;

            // Take the piece (or promoted piece) off the target and put the mover back
            xorPiece(white, placed, toBB);
            pieceAt[to] = 0;
            xorPiece(white, pt, fromBB);
            pieceAt[from] = white ? pt : (pt | 8);
            if (white)
                whitePieces ^= fromBB | toBB;
            else
                blackPieces ^= fromBB | toBB;

            // Resurrect the captured piece
            if (cap != 0) {
                int capSq = ((m & Move.EN_PASSANT) != 0) ? (white ? to - 8 : to + 8) : to;
                long capBB = 1L << capSq;
                xorPiece(!white, cap, capBB);
                pieceAt[capSq] = white ? (cap | 8) : cap;
                if (white)
                    blackPieces ^= capBB;
                else
                    whitePieces ^= capBB;
            }

            // Put the castling rook back
            if ((m & Move.CASTLE) != 0) {
                int rookFrom, rookTo;
                if (to == 6) {
                    rookFrom = 7;
                    rookTo = 5;
                } else if (to == 2) {
                    rookFrom = 0;
                    rookTo = 3;
                } else if (to == 62) {
                    rookFrom = 63;
                    rookTo = 61;
                } else {
                    rookFrom = 56;
                    rookTo = 59;
                }
                long rookMask = (1L << rookFrom) | (1L << rookTo);
                if (white) {
                    whiteRooks ^= rookMask;
                    whitePieces ^= rookMask;
                } else {
                    blackRooks ^= rookMask;
                    blackPieces ^= rookMask;
                }
                pieceAt[rookTo] = 0;
                pieceAt[rookFrom] = white ? Move.ROOK : (Move.ROOK | 8);
            }

            allPieces = whitePieces | blackPieces;
        }

        int castle = stCastle[sp];
        whiteCanCastleKingside = (castle & 1) != 0;
        whiteCanCastleQueenside = (castle & 2) != 0;
        blackCanCastleKingside = (castle & 4) != 0;
        blackCanCastleQueenside = (castle & 8) != 0;
        enPassantTarget = stEp[sp];
        hash = stHash[sp];
    }

    /** Passes the turn without moving (used by null-move pruning). */
    public void makeNullMove() {
        if (sp == stMove.length)
            growStack();
        stMove[sp] = 0;
        stCastle[sp] = castleBits();
        stEp[sp] = enPassantTarget;
        stHash[sp] = hash;
        sp++;

        long h = hash ^ Z_SIDE;
        if (enPassantTarget != -1) {
            h ^= Z_EP[enPassantTarget & 7];
            enPassantTarget = -1;
        }
        hash = h;
    }

    public void undoNullMove() {
        undoMove(true);
    }

    private int castleBits() {
        return (whiteCanCastleKingside ? 1 : 0) | (whiteCanCastleQueenside ? 2 : 0)
                | (blackCanCastleKingside ? 4 : 0) | (blackCanCastleQueenside ? 8 : 0);
    }

    private void growStack() {
        int size = stMove.length * 2;
        stMove = Arrays.copyOf(stMove, size);
        stCastle = Arrays.copyOf(stCastle, size);
        stEp = Arrays.copyOf(stEp, size);
        stHash = Arrays.copyOf(stHash, size);
    }

    /** Toggles the bitboard of one piece type for the given colour. */
    private void xorPiece(boolean white, int type, long mask) {
        if (white) {
            switch (type) {
                case 1: whitePawns ^= mask; break;
                case 2: whiteKnights ^= mask; break;
                case 3: whiteBishops ^= mask; break;
                case 4: whiteRooks ^= mask; break;
                case 5: whiteQueens ^= mask; break;
                default: whiteKing ^= mask; break;
            }
        } else {
            switch (type) {
                case 1: blackPawns ^= mask; break;
                case 2: blackKnights ^= mask; break;
                case 3: blackBishops ^= mask; break;
                case 4: blackRooks ^= mask; break;
                case 5: blackQueens ^= mask; break;
                default: blackKing ^= mask; break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Move-object interface (used by the web server, the console game and the tests)
    // ------------------------------------------------------------------

    /**
     * Executes a move on the board.
     *
     * @param move    The move to execute (its captured piece is filled in)
     * @param isWhite True if it is White to move, false if Black
     */
    public void makeMove(Move move, boolean isWhite) {
        int m = encode(move, isWhite);
        if (m == 0) {
            // Not a playable move (no piece to move). Leave the pieces alone, but keep the undo
            // stack balanced so a later undoMove still pairs up correctly.
            move.capturedPiece = 0;
            makeNullMove();
            return;
        }
        move.capturedPiece = (m >>> 15) & 7;
        makeMove(m, isWhite);
    }

    /** Reverts the most recent move made with makeMove (moves must be undone in reverse order). */
    public void undoMove(Move move, boolean isWhite) {
        undoMove(isWhite);
    }

    /**
     * Converts a Move object into the packed form using the current contents of the board.
     * Returns 0 (a harmless no-op move) if there is no piece of the moving colour on the
     * start square, or the target holds a piece of the moving colour.
     */
    int encode(Move move, boolean white) {
        int from = move.startSquare;
        int to = move.targetSquare;
        int code = pieceAt[from];
        int pt = code & 7;
        if (pt == 0 || (code >> 3) != (white ? 0 : 1))
            return 0;
        int target = pieceAt[to];
        if (target != 0 && (target >> 3) == (white ? 0 : 1))
            return 0;

        int cap = move.isEnPassant ? Move.PAWN : (target & 7);
        int m = from | (to << 6) | (pt << 12) | (cap << 15) | (move.promotedPiece << 18);
        if (move.isEnPassant)
            m |= Move.EN_PASSANT;
        if (move.isCastle)
            m |= Move.CASTLE;
        if (pt == Move.PAWN && Math.abs(to - from) == 16)
            m |= Move.DOUBLE_PUSH;
        return m;
    }

    /**
     * Refreshes the summary bitboards and the mailbox after the piece bitboards were edited.
     */
    public void updateOccupancies() {
        whitePieces = whitePawns | whiteKnights | whiteBishops | whiteRooks | whiteQueens | whiteKing;
        blackPieces = blackPawns | blackKnights | blackBishops | blackRooks | blackQueens | blackKing;
        allPieces = whitePieces | blackPieces;

        Arrays.fill(pieceAt, 0);
        fillMailbox(whitePawns, 1);
        fillMailbox(whiteKnights, 2);
        fillMailbox(whiteBishops, 3);
        fillMailbox(whiteRooks, 4);
        fillMailbox(whiteQueens, 5);
        fillMailbox(whiteKing, 6);
        fillMailbox(blackPawns, 1 | 8);
        fillMailbox(blackKnights, 2 | 8);
        fillMailbox(blackBishops, 3 | 8);
        fillMailbox(blackRooks, 4 | 8);
        fillMailbox(blackQueens, 5 | 8);
        fillMailbox(blackKing, 6 | 8);
        sp = 0;
    }

    private void fillMailbox(long bitboard, int code) {
        while (bitboard != 0) {
            pieceAt[Long.numberOfTrailingZeros(bitboard)] = code;
            bitboard &= bitboard - 1;
        }
    }

    /** Recomputes the Zobrist hash from scratch and stores it in the hash field. */
    public long resetHash(boolean whiteToMove) {
        hash = computeHash(whiteToMove);
        return hash;
    }

    /** Zobrist hash computed from scratch (used to seed and to verify the incremental hash). */
    public long computeHash(boolean whiteToMove) {
        long h = 0L;
        for (int sq = 0; sq < 64; sq++) {
            int code = pieceAt[sq];
            if (code != 0)
                h ^= Z_PIECE[((code >> 3) * 6) + (code & 7) - 1][sq];
        }
        if (!whiteToMove)
            h ^= Z_SIDE;
        h ^= Z_CASTLE[castleBits()];
        if (enPassantTarget != -1)
            h ^= Z_EP[enPassantTarget & 7];
        return h;
    }

    /**
     * Builds a Board from a FEN string (piece placement, side to move, castling, en passant).
     * The half-move and full-move counters are ignored.
     */
    public static Board fromFEN(String fen) {
        Board b = new Board();
        b.whitePawns = b.whiteKnights = b.whiteBishops = b.whiteRooks = b.whiteQueens = b.whiteKing = 0L;
        b.blackPawns = b.blackKnights = b.blackBishops = b.blackRooks = b.blackQueens = b.blackKing = 0L;

        String[] parts = fen.trim().split("\\s+");
        String[] ranks = parts[0].split("/");
        for (int r = 0; r < 8; r++) {
            int rank = 7 - r;
            int file = 0;
            for (char c : ranks[r].toCharArray()) {
                if (Character.isDigit(c)) {
                    file += c - '0';
                    continue;
                }
                long mask = 1L << (rank * 8 + file);
                switch (c) {
                    case 'P': b.whitePawns |= mask; break;
                    case 'N': b.whiteKnights |= mask; break;
                    case 'B': b.whiteBishops |= mask; break;
                    case 'R': b.whiteRooks |= mask; break;
                    case 'Q': b.whiteQueens |= mask; break;
                    case 'K': b.whiteKing |= mask; break;
                    case 'p': b.blackPawns |= mask; break;
                    case 'n': b.blackKnights |= mask; break;
                    case 'b': b.blackBishops |= mask; break;
                    case 'r': b.blackRooks |= mask; break;
                    case 'q': b.blackQueens |= mask; break;
                    case 'k': b.blackKing |= mask; break;
                    default: throw new IllegalArgumentException("Bad FEN piece: " + c);
                }
                file++;
            }
        }

        b.fenWhiteToMove = parts.length < 2 || parts[1].equals("w");
        String castling = parts.length > 2 ? parts[2] : "-";
        b.whiteCanCastleKingside = castling.indexOf('K') >= 0;
        b.whiteCanCastleQueenside = castling.indexOf('Q') >= 0;
        b.blackCanCastleKingside = castling.indexOf('k') >= 0;
        b.blackCanCastleQueenside = castling.indexOf('q') >= 0;

        b.enPassantTarget = -1;
        if (parts.length > 3 && parts[3].length() == 2 && !parts[3].equals("-")) {
            b.enPassantTarget = (parts[3].charAt(1) - '1') * 8 + (parts[3].charAt(0) - 'a');
        }

        b.updateOccupancies();
        b.resetHash(b.fenWhiteToMove);
        return b;
    }

    /**
     * Translates the current Bitboard state into a FEN string for the web frontend.
     */
    public String toFEN() {
        StringBuilder fen = new StringBuilder();
        String letters = ".PNBRQK..pnbrqk";

        // FEN starts at Rank 8 (Index 7) and goes down to Rank 1 (Index 0)
        for (int rank = 7; rank >= 0; rank--) {
            int emptyCount = 0;
            for (int file = 0; file < 8; file++) {
                int code = pieceAt[rank * 8 + file];
                if (code == 0) {
                    emptyCount++;
                } else {
                    if (emptyCount > 0) {
                        fen.append(emptyCount);
                        emptyCount = 0;
                    }
                    fen.append(letters.charAt(code));
                }
            }
            if (emptyCount > 0)
                fen.append(emptyCount);
            if (rank > 0)
                fen.append('/'); // New line for the next rank
        }

        // Return the board state layout
        return fen.toString() + " w KQkq - 0 1";
    }
}
