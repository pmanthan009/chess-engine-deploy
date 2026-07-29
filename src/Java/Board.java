public class Board {

    long whitePawns, whiteKnights, whiteBishops, whiteRooks, whiteQueens, whiteKing;
    long blackPawns, blackKnights, blackBishops, blackRooks, blackQueens, blackKing;

    long whitePieces, blackPieces, allPieces;

    // --- SPECIAL MOVE STATE ---
    // Tracks if the King and Rooks have moved yet
    public boolean whiteCanCastleKingside;
    public boolean whiteCanCastleQueenside;
    public boolean blackCanCastleKingside;
    public boolean blackCanCastleQueenside;

    // Tracks the target square index for an En Passant capture (-1 if none is
    // available)
    public int enPassantTarget;

    public Board() {
        setupStandardPosition();
        // At the start of a standard game, everyone can castle!
        whiteCanCastleKingside = true;
        whiteCanCastleQueenside = true;
        blackCanCastleKingside = true;
        blackCanCastleQueenside = true;

        // No En Passant target on turn 1
        enPassantTarget = -1;
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

    /**
     * Executes a move on the board using bitwise operations.
     * 
     * @param move    The move to execute
     * @param isWhite True if it is White's turn, false if Black's
     */
    public void makeMove(Move move, boolean isWhite) {
        // --- 0. SAVE STATE MEMORY BEFORE DOING ANYTHING ---
        move.prevWhiteCanCastleKingside = this.whiteCanCastleKingside;
        move.prevWhiteCanCastleQueenside = this.whiteCanCastleQueenside;
        move.prevBlackCanCastleKingside = this.blackCanCastleKingside;
        move.prevBlackCanCastleQueenside = this.blackCanCastleQueenside;
        move.prevEnPassantTarget = this.enPassantTarget;

        long startMask = 1L << move.startSquare;
        long targetMask = 1L << move.targetSquare;
        long moveMask = startMask | targetMask;

        // Reset En Passant for the next turn
        this.enPassantTarget = -1;

        // --- EN PASSANT TARGET SETUP ---
        // If a piece moved exactly 16 squares (2 ranks), it might be a pawn double-push
        if (Math.abs(move.targetSquare - move.startSquare) == 16) {
            if (isWhite && ((whitePawns & startMask) != 0)) {
                this.enPassantTarget = move.startSquare + 8; // The skipped square behind the White pawn
            } else if (!isWhite && ((blackPawns & startMask) != 0)) {
                this.enPassantTarget = move.startSquare - 8; // The skipped square behind the Black pawn
            }
        }

        if (isWhite) {
            // 1. Find which White piece is moving and toggle it (Remove from start, add to
            // target)
            if ((whitePawns & startMask) != 0)
                whitePawns ^= moveMask;
            else if ((whiteKnights & startMask) != 0)
                whiteKnights ^= moveMask;
            else if ((whiteBishops & startMask) != 0)
                whiteBishops ^= moveMask;
            else if ((whiteQueens & startMask) != 0)
                whiteQueens ^= moveMask;
            // Update Castling Rights
            else if ((whiteRooks & startMask) != 0) {
                whiteRooks ^= moveMask;
                if (move.startSquare == 0)
                    whiteCanCastleQueenside = false; // A1 Rook moved
                if (move.startSquare == 7)
                    whiteCanCastleKingside = false; // H1 Rook moved
            } else if ((whiteKing & startMask) != 0) {
                whiteKing ^= moveMask;
                whiteCanCastleKingside = false;
                whiteCanCastleQueenside = false; // King moved, lost all rights
            }

            // --- CASTLING ROOK TELEPORT ---
            if (move.isCastle) {
                if (move.targetSquare == 6) {
                    whiteRooks ^= (1L << 7) | (1L << 5); // Kingside: H1 to F1
                } else if (move.targetSquare == 2) {
                    whiteRooks ^= (1L << 0) | (1L << 3); // Queenside: A1 to D1
                }
            }

            // --- EN PASSANT CAPTURE DELETION ---
            if (move.isEnPassant) {
                // White captures UP, so the Black pawn is 8 squares BELOW the target
                int capturedPawnSquare = move.targetSquare - 8; 
                blackPawns &= ~(1L << capturedPawnSquare);
                move.capturedPiece = 1; // Memorize that we killed a pawn!
            }

            // --- PAWN PROMOTION (WHITE) ---
            if (move.promotedPiece != 0) {
                whitePawns ^= targetMask; // Delete the pawn from the target square
                
                // Spawn the new piece
                if (move.promotedPiece == 2) whiteKnights ^= targetMask;
                else if (move.promotedPiece == 3) whiteBishops ^= targetMask;
                else if (move.promotedPiece == 4) whiteRooks ^= targetMask;
                else if (move.promotedPiece == 5) whiteQueens ^= targetMask;
            }

            // 2. Handle Captures: If Black has a piece on the target square, remove it
            if ((blackPieces & targetMask) != 0) {
                long captureMask = ~targetMask;
                if ((blackPawns & targetMask) != 0) {
                    move.capturedPiece = 1;
                    blackPawns &= captureMask;
                } else if ((blackKnights & targetMask) != 0) {
                    move.capturedPiece = 2;
                    blackKnights &= captureMask;
                } else if ((blackBishops & targetMask) != 0) {
                    move.capturedPiece = 3;
                    blackBishops &= captureMask;
                } else if ((blackQueens & targetMask) != 0) {
                    move.capturedPiece = 5;
                    blackQueens &= captureMask;
                } else if ((blackRooks & targetMask) != 0) {
                    move.capturedPiece = 4;
                    blackRooks &= captureMask;
                } else if ((blackRooks & targetMask) != 0) {
                    move.capturedPiece = 4;
                    blackRooks &= captureMask;
                    // If we captured their Rook on its starting square, they lose castling rights!
                    if (move.targetSquare == 56)
                        blackCanCastleQueenside = false; // A8
                    if (move.targetSquare == 63)
                        blackCanCastleKingside = false; // H8
                }
                // Don't need to check the King, as Kings cannot be captured in chess
            }
        } else {
            // 1. Find which Black piece is moving and toggle it
            if ((blackPawns & startMask) != 0)
                blackPawns ^= moveMask;
            else if ((blackKnights & startMask) != 0)
                blackKnights ^= moveMask;
            else if ((blackBishops & startMask) != 0)
                blackBishops ^= moveMask;
            else if ((blackQueens & startMask) != 0)
                blackQueens ^= moveMask;
            // Update Castling Rights
            else if ((blackRooks & startMask) != 0) {
                blackRooks ^= moveMask;
                if (move.startSquare == 56)
                    blackCanCastleQueenside = false; // A8 Rook moved
                if (move.startSquare == 63)
                    blackCanCastleKingside = false; // H8 Rook moved
            } else if ((blackKing & startMask) != 0) {
                blackKing ^= moveMask;
                blackCanCastleKingside = false;
                blackCanCastleQueenside = false; // King moved, lost all rights
            }

            // --- CASTLING ROOK TELEPORT ---
            if (move.isCastle) {
                if (move.targetSquare == 62) {
                    blackRooks ^= (1L << 63) | (1L << 61); // Kingside: H8 to F8
                } else if (move.targetSquare == 58) {
                    blackRooks ^= (1L << 56) | (1L << 59); // Queenside: A8 to D8
                }
            }

            // --- EN PASSANT CAPTURE DELETION ---
            if (move.isEnPassant) {
                // Black captures DOWN, so the White pawn is 8 squares ABOVE the target
                int capturedPawnSquare = move.targetSquare + 8; 
                whitePawns &= ~(1L << capturedPawnSquare);
                move.capturedPiece = 1; // Memorize that we killed a pawn!
            }

            // --- PAWN PROMOTION (BLACK) ---
            if (move.promotedPiece != 0) {
                blackPawns ^= targetMask; // Delete the pawn from the target square
                
                if (move.promotedPiece == 2) blackKnights ^= targetMask;
                else if (move.promotedPiece == 3) blackBishops ^= targetMask;
                else if (move.promotedPiece == 4) blackRooks ^= targetMask;
                else if (move.promotedPiece == 5) blackQueens ^= targetMask;
            }

            // 2. Handle Captures: If White has a piece on the target square, remove it
            if ((whitePieces & targetMask) != 0) {
                long captureMask = ~targetMask;
                if ((whitePawns & targetMask) != 0) {
                    move.capturedPiece = 1;
                    whitePawns &= captureMask;
                } else if ((whiteKnights & targetMask) != 0) {
                    move.capturedPiece = 2;
                    whiteKnights &= captureMask;
                } else if ((whiteBishops & targetMask) != 0) {
                    move.capturedPiece = 3;
                    whiteBishops &= captureMask;
                } else if ((whiteQueens & targetMask) != 0) {
                    move.capturedPiece = 5;
                    whiteQueens &= captureMask;
                } else if ((whiteRooks & targetMask) != 0) {
                    move.capturedPiece = 4;
                    whiteRooks &= captureMask;
                    // If we captured their Rook on its starting square, they lose castling rights!
                    if (move.targetSquare == 0)
                        whiteCanCastleQueenside = false; // A1
                    if (move.targetSquare == 7)
                        whiteCanCastleKingside = false; // H1
                }
            }
        }

        // 3. Update the occupancy summary boards
        updateOccupancies();
    }

    /**
     * Reverts a move on the board using bitwise operations.
     * 
     * @param move    The move to undo (must contain captured piece data)
     * @param isWhite True if it was White's turn when the move was MADE
     */
    public void undoMove(Move move, boolean isWhite) {
        long startMask = 1L << move.startSquare;
        long targetMask = 1L << move.targetSquare;
        long moveMask = startMask | targetMask;

        if (isWhite) {
            // --- REVERSE PAWN PROMOTION (WHITE) ---
            if (move.promotedPiece != 0) {
                // Delete the promoted piece
                if (move.promotedPiece == 2) whiteKnights ^= targetMask;
                else if (move.promotedPiece == 3) whiteBishops ^= targetMask;
                else if (move.promotedPiece == 4) whiteRooks ^= targetMask;
                else if (move.promotedPiece == 5) whiteQueens ^= targetMask;
                
                whitePawns ^= targetMask; // Respawn the pawn on the target square so the logic below catches it!
            }
            
            // 1. Move the White piece back
            if ((whitePawns & targetMask) != 0) {
                whitePawns ^= moveMask;
            } else if ((whiteKnights & targetMask) != 0) {
                whiteKnights ^= moveMask;
            } else if ((whiteBishops & targetMask) != 0) {
                whiteBishops ^= moveMask;
            } else if ((whiteRooks & targetMask) != 0) {
                whiteRooks ^= moveMask;
            } else if ((whiteQueens & targetMask) != 0) {
                whiteQueens ^= moveMask;
            } else if ((whiteKing & targetMask) != 0) {
                whiteKing ^= moveMask;
            }
            // --- REVERSE CASTLING ROOK TELEPORT ---
            if (move.isCastle) {
                if (move.targetSquare == 6) {
                    whiteRooks ^= (1L << 7) | (1L << 5); // Kingside: F1 back to H1
                } else if (move.targetSquare == 2) {
                    whiteRooks ^= (1L << 0) | (1L << 3); // Queenside: D1 back to A1
                }
            }

            // 2. Resurrect the captured Black piece (if any)
            if (move.capturedPiece != 0) {
                // If En Passant, resurrect on the square behind the target. Otherwise, use targetMask.
                long resurrectMask = move.isEnPassant ? (1L << (move.targetSquare - 8)) : targetMask;
                
                if (move.capturedPiece == 1) {
                    blackPawns |= resurrectMask;
                } else if (move.capturedPiece == 2) {
                    blackKnights |= resurrectMask;
                } else if (move.capturedPiece == 3) {
                    blackBishops |= resurrectMask;
                } else if (move.capturedPiece == 4) {
                    blackRooks |= resurrectMask;
                } else if (move.capturedPiece == 5) {
                    blackQueens |= resurrectMask;
                }
            }
        } else {
            // --- REVERSE PAWN PROMOTION (BLACK) ---
            if (move.promotedPiece != 0) {
                if (move.promotedPiece == 2) blackKnights ^= targetMask;
                else if (move.promotedPiece == 3) blackBishops ^= targetMask;
                else if (move.promotedPiece == 4) blackRooks ^= targetMask;
                else if (move.promotedPiece == 5) blackQueens ^= targetMask;
                
                blackPawns ^= targetMask; 
            }

            // 1. Move the Black piece back
            if ((blackPawns & targetMask) != 0) {
                blackPawns ^= moveMask;
            } else if ((blackKnights & targetMask) != 0) {
                blackKnights ^= moveMask;
            } else if ((blackBishops & targetMask) != 0) {
                blackBishops ^= moveMask;
            } else if ((blackRooks & targetMask) != 0) {
                blackRooks ^= moveMask;
            } else if ((blackQueens & targetMask) != 0) {
                blackQueens ^= moveMask;
            } else if ((blackKing & targetMask) != 0) {
                blackKing ^= moveMask;
            }
            // --- REVERSE CASTLING ROOK TELEPORT ---
            if (move.isCastle) {
                if (move.targetSquare == 62) {
                    blackRooks ^= (1L << 63) | (1L << 61); // Kingside: F8 back to H8
                } else if (move.targetSquare == 58) {
                    blackRooks ^= (1L << 56) | (1L << 59); // Queenside: D8 back to A8
                }
            }
            
            // 2. Resurrect the captured White piece (if any)
            if (move.capturedPiece != 0) {
                // Black EP captures mean the White pawn was ABOVE the target
                long resurrectMask = move.isEnPassant ? (1L << (move.targetSquare + 8)) : targetMask;
                
                if (move.capturedPiece == 1) {
                    whitePawns |= resurrectMask;
                } else if (move.capturedPiece == 2) {
                    whiteKnights |= resurrectMask;
                } else if (move.capturedPiece == 3) {
                    whiteBishops |= resurrectMask;
                } else if (move.capturedPiece == 4) {
                    whiteRooks |= resurrectMask;
                } else if (move.capturedPiece == 5) {
                    whiteQueens |= resurrectMask;
                }
            }
        }

        // --- RESTORE STATE MEMORY ---
        this.whiteCanCastleKingside = move.prevWhiteCanCastleKingside;
        this.whiteCanCastleQueenside = move.prevWhiteCanCastleQueenside;
        this.blackCanCastleKingside = move.prevBlackCanCastleKingside;
        this.blackCanCastleQueenside = move.prevBlackCanCastleQueenside;
        this.enPassantTarget = move.prevEnPassantTarget;

        // 3. Update the occupancy summary boards
        updateOccupancies();
    }

    /**
     * Refreshes the summary bitboards after pieces have moved.
     */

    public void updateOccupancies() {
        whitePieces = whitePawns | whiteKnights | whiteBishops | whiteRooks | whiteQueens | whiteKing;
        blackPieces = blackPawns | blackKnights | blackBishops | blackRooks | blackQueens | blackKing;
        allPieces = whitePieces | blackPieces;
    }

    /**
     * Translates the current Bitboard state into a FEN string for the web frontend.
     */
    public String toFEN() {
        StringBuilder fen = new StringBuilder();
        
        // FEN starts at Rank 8 (Index 7) and goes down to Rank 1 (Index 0)
        for (int rank = 7; rank >= 0; rank--) {
            int emptyCount = 0;
            for (int file = 0; file < 8; file++) {
                int sq = rank * 8 + file;
                long mask = 1L << sq;
                
                char piece = ' ';
                if ((whitePawns & mask) != 0) piece = 'P';
                else if ((whiteKnights & mask) != 0) piece = 'N';
                else if ((whiteBishops & mask) != 0) piece = 'B';
                else if ((whiteRooks & mask) != 0) piece = 'R';
                else if ((whiteQueens & mask) != 0) piece = 'Q';
                else if ((whiteKing & mask) != 0) piece = 'K';
                else if ((blackPawns & mask) != 0) piece = 'p';
                else if ((blackKnights & mask) != 0) piece = 'n';
                else if ((blackBishops & mask) != 0) piece = 'b';
                else if ((blackRooks & mask) != 0) piece = 'r';
                else if ((blackQueens & mask) != 0) piece = 'q';
                else if ((blackKing & mask) != 0) piece = 'k';

                if (piece == ' ') {
                    emptyCount++;
                } else {
                    if (emptyCount > 0) {
                        fen.append(emptyCount);
                        emptyCount = 0;
                    }
                    fen.append(piece);
                }
            }
            if (emptyCount > 0) fen.append(emptyCount);
            if (rank > 0) fen.append('/'); // New line for the next rank
        }
        
        // Return the board state layout
        return fen.toString() + " w KQkq - 0 1"; 
    }
}