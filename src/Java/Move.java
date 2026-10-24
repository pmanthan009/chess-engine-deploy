/**
 * A move, in two forms.
 *
 * The engine core uses a packed int (all static helpers below):
 *   bits  0-5   start square
 *   bits  6-11  target square
 *   bits 12-14  moving piece type   (1 = Pawn, 2 = Knight, 3 = Bishop, 4 = Rook, 5 = Queen, 6 = King)
 *   bits 15-17  captured piece type (0 = none)
 *   bits 18-20  promotion piece type (0 = none, else 2..5)
 *   bit  21     En Passant capture
 *   bit  22     castling
 *   bit  23     pawn double push
 * A packed move of 0 never occurs for a real move.
 *
 * The object form is what the web server, console game and tests use.
 */
class Move {

    // --- PIECE TYPES ---
    static final int PAWN = 1;
    static final int KNIGHT = 2;
    static final int BISHOP = 3;
    static final int ROOK = 4;
    static final int QUEEN = 5;
    static final int KING = 6;

    // --- PACKED MOVE FLAGS ---
    static final int EN_PASSANT = 1 << 21;
    static final int CASTLE = 1 << 22;
    static final int DOUBLE_PUSH = 1 << 23;

    /** Mask that is zero for quiet moves (no capture and no promotion). */
    static final int CAPTURE_OR_PROMO = (7 << 15) | (7 << 18);

    static int from(int m) {
        return m & 63;
    }

    static int to(int m) {
        return (m >>> 6) & 63;
    }

    static int piece(int m) {
        return (m >>> 12) & 7;
    }

    static int captured(int m) {
        return (m >>> 15) & 7;
    }

    static int promotion(int m) {
        return (m >>> 18) & 7;
    }

    static boolean isQuiet(int m) {
        return (m & CAPTURE_OR_PROMO) == 0;
    }

    /** Builds the object form of a packed move. */
    static Move fromInt(int m) {
        Move move = new Move(from(m), to(m));
        move.capturedPiece = captured(m);
        move.promotedPiece = promotion(m);
        move.isEnPassant = (m & EN_PASSANT) != 0;
        move.isCastle = (m & CASTLE) != 0;
        return move;
    }

    /** Coordinate notation such as e2e4 or e7e8q. */
    static String toUci(int m) {
        String s = "" + (char) ('a' + (from(m) & 7)) + (char) ('1' + (from(m) >> 3))
                + (char) ('a' + (to(m) & 7)) + (char) ('1' + (to(m) >> 3));
        return promotion(m) != 0 ? s + "nbrq".charAt(promotion(m) - 2) : s;
    }

    // --- OBJECT FORM ---

    public int startSquare;
    public int targetSquare;
    public int capturedPiece; // 0 = None, 1 = Pawn, 2 = Knight, 3 = Bishop, 4 = Rook, 5 = Queen

    // --- SPECIAL MOVE FLAGS ---
    public int promotedPiece; // 0 = None, otherwise Piece ID (2=Knight, 3=Bishop, 4=Rook, 5=Queen)
    public boolean isEnPassant;
    public boolean isCastle;

    public Move(int startSquare, int targetSquare) {
        this.startSquare = startSquare;
        this.targetSquare = targetSquare;
        this.capturedPiece = 0;
        this.promotedPiece = 0;
        this.isEnPassant = false;
        this.isCastle = false;
    }

    @Override
    public String toString() {
        String str = "Move from " + startSquare + " to " + targetSquare;
        if (capturedPiece != 0) str += " (Capture)";
        if (promotedPiece != 0) str += " (Promotion)";
        if (isEnPassant) str += " (En Passant)";
        if (isCastle) str += " (Castle)";
        return str;
    }
}
