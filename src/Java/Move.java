/**
 * A Move structure that acts as a historical snapshot.
 * It holds the move coordinates, flags for special rules, 
 * and a perfect memory of the board's castling/en passant state 
 * BEFORE the move was executed.
 */
class Move {
    public int startSquare;
    public int targetSquare;
    public int capturedPiece; // 0 = None, 1 = Pawn, 2 = Knight, 3 = Bishop, 4 = Rook, 5 = Queen

    // --- SPECIAL MOVE FLAGS ---
    public int promotedPiece; // 0 = None, otherwise Piece ID (2=Knight, 3=Bishop, 4=Rook, 5=Queen)
    public boolean isEnPassant;
    public boolean isCastle;

    // --- BOARD STATE MEMORY ---
    public boolean prevWhiteCanCastleKingside;
    public boolean prevWhiteCanCastleQueenside;
    public boolean prevBlackCanCastleKingside;
    public boolean prevBlackCanCastleQueenside;
    public int prevEnPassantTarget;

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