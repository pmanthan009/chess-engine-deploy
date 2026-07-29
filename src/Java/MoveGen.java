import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Arrays;

public class MoveGen {

    // --- BITMASKS ---
    // Masks to prevent the "Wrap-Around Bug"
    private static final long NOT_A_FILE = ~0x0101010101010101L;
    private static final long NOT_H_FILE = ~0x8080808080808080L;
    
    // Masks for pieces that move two squares horizontally (Knights)
    private static final long NOT_AB_FILE = ~0x0303030303030303L;
    private static final long NOT_GH_FILE = ~0xC0C0C0C0C0C0C0C0L;
    
    // Masks for double pawn pushes
    private static final long RANK_4 = 0x00000000FF000000L;
    private static final long RANK_5 = 0x000000FF00000000L;

    // --- PRE-CALCULATED TABLES ---
    private final long[] knightAttacks = new long[64];
    private final long[] kingAttacks = new long[64];

    // --- MAGIC BITBOARD VARIABLES ---
    
    // 1. The Masks (Which squares can block a sliding piece on a given square?)
    private final long[] rookMasks = new long[64];
    private final long[] bishopMasks = new long[64];
    
    // 2. The Attack Tables (The pre-calculated moves)
    // First index is the square (0-63). 
    // Second index is the hashed blocker arrangement (up to 4096 possible combinations for a Rook).
    private final long[][] rookAttacks = new long[64][4096];
    private final long[][] bishopAttacks = new long[64][512]; // Bishops have fewer blockers

    // 3. The Magic Numbers
    private final long[] rookMagics = new long[64];
    private final long[] bishopMagics = new long[64];

    // --- CONSTRUCTOR ---

    public MoveGen() {
        initKnightAttacks();
        initKingAttacks();
        initSlidingMasks();
        initMagicBitboards();
    }

    // --- MAGIC BITBOARD GENERATOR ---

    private long randomSparse(Random random) {
        // ANDing three random longs drastically reduces the number of '1' bits, 
        // creating the sparse numbers needed for perfect hashing.
        return random.nextLong() & random.nextLong() & random.nextLong();
    }

    private long findMagic(int square, int bits, boolean isBishop) {
        long mask = isBishop ? bishopMasks[square] : rookMasks[square];
        int numCombinations = 1 << bits;
        long[] occupancies = new long[numCombinations];
        long[] attacks = new long[numCombinations];
        long[] used = new long[4096]; // Max size for Rook combinations
        
        // 1. Pre-calculate all occupancies and their true Ray-Casted attacks
        for (int i = 0; i < numCombinations; i++) {
            occupancies[i] = setOccupancy(i, bits, mask);
            attacks[i] = isBishop ? bishopAttacksOnTheFly(square, occupancies[i]) : rookAttacksOnTheFly(square, occupancies[i]);
        }
        
        Random random = new Random(100); // Fixed seed so the engine boots up consistently
        
        // 2. Brute-force a Magic Number
        for (int k = 0; k < 100000000; k++) {
            long magic = randomSparse(random);
            Arrays.fill(used, 0L);
            boolean fail = false;
            
            // 3. Test the Magic Hash against all combinations
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
        System.out.println("Failed to find magic for square " + square);
        return 0L;
    }

    private void initKnightAttacks() {
        for (int square = 0; square < 64; square++) {
            long knight = 1L << square;
            long attacks = 0L;

            // Generate the 8 possible L-shapes
            attacks |= (knight << 17) & NOT_A_FILE;
            attacks |= (knight << 15) & NOT_H_FILE;
            attacks |= (knight << 10) & NOT_AB_FILE;
            attacks |= (knight << 6)  & NOT_GH_FILE;
            
            attacks |= (knight >>> 17) & NOT_H_FILE;
            attacks |= (knight >>> 15) & NOT_A_FILE;
            attacks |= (knight >>> 10) & NOT_GH_FILE;
            attacks |= (knight >>> 6)  & NOT_AB_FILE;

            knightAttacks[square] = attacks;
        }
    }

    private void initKingAttacks() {
        for (int square = 0; square < 64; square++) {
            long king = 1L << square;
            long attacks = 0L;

            // 1. Horizontal and Vertical
            attacks |= (king << 8); // Up
            attacks |= (king >>> 8); // Down
            attacks |= (king & NOT_H_FILE) << 1; // Right (+1 index)
            attacks |= (king & NOT_A_FILE) >>> 1; // Left (-1 index)

            // 2. Diagonals
            attacks |= (king & NOT_H_FILE) << 9; // Up-Right
            attacks |= (king & NOT_A_FILE) << 7; // Up-Left
            attacks |= (king & NOT_H_FILE) >>> 7; // Down-Right
            attacks |= (king & NOT_A_FILE) >>> 9; // Down-Left

            // Store the fully calculated attack bitboard in the array
            kingAttacks[square] = attacks;
        }
    }

    private void initSlidingMasks() {
        for (int square = 0; square < 64; square++) {
            rookMasks[square] = maskRookAttacks(square);
            bishopMasks[square] = maskBishopAttacks(square);
        }
    }

    private void initMagicBitboards() {
        for (int square = 0; square < 64; square++) {
            // --- ROOKS ---
            long rookMask = rookMasks[square];
            int rookBits = Long.bitCount(rookMask);

            // Generate the magic number specifically for this square!
            rookMagics[square] = findMagic(square, rookBits, false);

            int rookCombinations = 1 << rookBits; // 2^rookBits (max 4096)

            for (int i = 0; i < rookCombinations; i++) {
                long blockers = setOccupancy(i, rookBits, rookMask);
                int magicIndex = magicHash(blockers, rookMagics[square], rookBits);
                rookAttacks[square][magicIndex] = rookAttacksOnTheFly(square, blockers);
            }

            // --- BISHOPS ---
            long bishopMask = bishopMasks[square];
            int bishopBits = Long.bitCount(bishopMask);

            // Generate the magic number specifically for this square!
            bishopMagics[square] = findMagic(square, bishopBits, true);

            int bishopCombinations = 1 << bishopBits; // 2^bishopBits (max 512)

            for (int i = 0; i < bishopCombinations; i++) {
                long blockers = setOccupancy(i, bishopBits, bishopMask);
                int magicIndex = magicHash(blockers, bishopMagics[square], bishopBits);
                bishopAttacks[square][magicIndex] = bishopAttacksOnTheFly(square, blockers);
            }
        }
    }

    // --- PAWN MOVE GENERATION ---

    public List<Move> generateWhitePawnMoves(Board board) {
        List<Move> moves = new ArrayList<>();
        long emptySquares = ~board.allPieces;

        long singlePushes = (board.whitePawns << 8) & emptySquares;
        extractPawnMoves(singlePushes, -8, moves); // CHANGED

        long doublePushes = (singlePushes << 8) & emptySquares & RANK_4;
        extractPawnMoves(doublePushes, -16, moves); // CHANGED

        long capturesRight = ((board.whitePawns & NOT_H_FILE) << 9) & board.blackPieces;
        extractPawnMoves(capturesRight, -9, moves); // CHANGED

        long capturesLeft = ((board.whitePawns & NOT_A_FILE) << 7) & board.blackPieces;
        extractPawnMoves(capturesLeft, -7, moves); // CHANGED

        // --- EN PASSANT (WHITE) ---
        if (board.enPassantTarget != -1) {
            long epMask = 1L << board.enPassantTarget;
            
            // Can a White pawn attack this square to the Right?
            long epCapturesRight = ((board.whitePawns & NOT_H_FILE) << 9) & epMask;
            if (epCapturesRight != 0) {
                Move epMove = new Move(board.enPassantTarget - 9, board.enPassantTarget);
                epMove.isEnPassant = true;
                moves.add(epMove);
            }
            
            // Can a White pawn attack this square to the Left?
            long epCapturesLeft = ((board.whitePawns & NOT_A_FILE) << 7) & epMask;
            if (epCapturesLeft != 0) {
                Move epMove = new Move(board.enPassantTarget - 7, board.enPassantTarget);
                epMove.isEnPassant = true;
                moves.add(epMove);
            }
        }

        return moves;
    }

    public List<Move> generateBlackPawnMoves(Board board) {
        List<Move> moves = new ArrayList<>();
        long emptySquares = ~board.allPieces;

        long singlePushes = (board.blackPawns >>> 8) & emptySquares;
        extractPawnMoves(singlePushes, 8, moves); // CHANGED

        long doublePushes = (singlePushes >>> 8) & emptySquares & RANK_5;
        extractPawnMoves(doublePushes, 16, moves); // CHANGED

        long capturesAFile = ((board.blackPawns & NOT_A_FILE) >>> 9) & board.whitePieces;
        extractPawnMoves(capturesAFile, 9, moves); // CHANGED

        long capturesHFile = ((board.blackPawns & NOT_H_FILE) >>> 7) & board.whitePieces;
        extractPawnMoves(capturesHFile, 7, moves); // CHANGED

        // --- EN PASSANT (BLACK) ---
        if (board.enPassantTarget != -1) {
            long epMask = 1L << board.enPassantTarget;
            
            // Can a Black pawn attack this square to the A-File (Right from Black's perspective)?
            long epCapturesAFile = ((board.blackPawns & NOT_A_FILE) >>> 9) & epMask;
            if (epCapturesAFile != 0) {
                Move epMove = new Move(board.enPassantTarget + 9, board.enPassantTarget);
                epMove.isEnPassant = true;
                moves.add(epMove);
            }
            
            // Can a Black pawn attack this square to the H-File (Left from Black's perspective)?
            long epCapturesHFile = ((board.blackPawns & NOT_H_FILE) >>> 7) & epMask;
            if (epCapturesHFile != 0) {
                Move epMove = new Move(board.enPassantTarget + 7, board.enPassantTarget);
                epMove.isEnPassant = true;
                moves.add(epMove);
            }
        }
        
        return moves;
    }

    // --- KNIGHT MOVE GENERATION ---

    public List<Move> generateWhiteKnightMoves(Board board) {
        List<Move> moves = new ArrayList<>();
        long knights = board.whiteKnights; 
        
        // White Knights can land on empty squares OR Black pieces, but NOT White pieces
        long validSquares = ~board.whitePieces; 

        while (knights != 0) {
            int startSquare = Long.numberOfTrailingZeros(knights);
            long attacks = knightAttacks[startSquare] & validSquares;
            
            extractMoves(attacks, startSquare, moves);
            knights &= (knights - 1); 
        }
        return moves;
    }

    public List<Move> generateBlackKnightMoves(Board board) {
        List<Move> moves = new ArrayList<>();
        long knights = board.blackKnights; 
        
        // Black Knights can land on empty squares OR White pieces, but NOT Black pieces
        long validSquares = ~board.blackPieces; 

        while (knights != 0) {
            int startSquare = Long.numberOfTrailingZeros(knights);
            long attacks = knightAttacks[startSquare] & validSquares;
            
            extractMoves(attacks, startSquare, moves);
            knights &= (knights - 1); 
        }
        return moves;
    }

    // --- KING MOVE GENERATION ---

    public List<Move> generateWhiteKingMoves(Board board) {
        List<Move> moves = new ArrayList<>();
        long kingBoard = board.whiteKing; 
        long validSquares = ~board.whitePieces; 

        if (kingBoard != 0) {
            int startSquare = Long.numberOfTrailingZeros(kingBoard);
            long attacks = kingAttacks[startSquare] & validSquares;
            
            extractMoves(attacks, startSquare, moves);

            // --- CASTLING ---
            // Ensure the King is on its starting square (E1 = 4)
            if (startSquare == 4) {
                // Kingside (O-O): King goes to G1 (6), Rook goes to F1 (5)
                if (board.whiteCanCastleKingside) {
                    long f1g1Mask = (1L << 5) | (1L << 6);
                    // 1. Are F1 and G1 empty?
                    if ((board.allPieces & f1g1Mask) == 0) {
                        // 2. Are E1, F1, and G1 safe from Black attacks?
                        if (!isSquareAttacked(4, false, board) && 
                            !isSquareAttacked(5, false, board) && 
                            !isSquareAttacked(6, false, board)) {
                            
                            Move castleMove = new Move(4, 6);
                            castleMove.isCastle = true;
                            moves.add(castleMove);
                        }
                    }
                }
                
                // Queenside (O-O-O): King goes to C1 (2), Rook goes to D1 (3)
                if (board.whiteCanCastleQueenside) {
                    long b1c1d1Mask = (1L << 1) | (1L << 2) | (1L << 3);
                    // 1. Are B1, C1, and D1 empty?
                    if ((board.allPieces & b1c1d1Mask) == 0) {
                        // 2. Are E1, D1, and C1 safe from Black attacks? 
                        // Note: B1 does not need to be safe, just empty!
                        if (!isSquareAttacked(4, false, board) && 
                            !isSquareAttacked(3, false, board) && 
                            !isSquareAttacked(2, false, board)) {
                            
                            Move castleMove = new Move(4, 2);
                            castleMove.isCastle = true;
                            moves.add(castleMove);
                        }
                    }
                }
            }
        }
        return moves;
    }

    public List<Move> generateBlackKingMoves(Board board) {
        List<Move> moves = new ArrayList<>();
        long kingBoard = board.blackKing; 
        long validSquares = ~board.blackPieces; 

        if (kingBoard != 0) {
            int startSquare = Long.numberOfTrailingZeros(kingBoard);
            long attacks = kingAttacks[startSquare] & validSquares;
            
            extractMoves(attacks, startSquare, moves);

            // --- CASTLING ---
            // Ensure the King is on its starting square (E8 = 60)
            if (startSquare == 60) {
                // Kingside (O-O): King goes to G8 (62), Rook goes to F8 (61)
                if (board.blackCanCastleKingside) {
                    long f8g8Mask = (1L << 61) | (1L << 62);
                    if ((board.allPieces & f8g8Mask) == 0) {
                        // Are E8, F8, and G8 safe from White attacks?
                        if (!isSquareAttacked(60, true, board) && 
                            !isSquareAttacked(61, true, board) && 
                            !isSquareAttacked(62, true, board)) {
                            
                            Move castleMove = new Move(60, 62);
                            castleMove.isCastle = true;
                            moves.add(castleMove);
                        }
                    }
                }
                
                // Queenside (O-O-O): King goes to C8 (58), Rook goes to D8 (59)
                if (board.blackCanCastleQueenside) {
                    long b8c8d8Mask = (1L << 57) | (1L << 58) | (1L << 59);
                    if ((board.allPieces & b8c8d8Mask) == 0) {
                        // Are E8, D8, and C8 safe from White attacks?
                        if (!isSquareAttacked(60, true, board) && 
                            !isSquareAttacked(59, true, board) && 
                            !isSquareAttacked(58, true, board)) {
                            
                            Move castleMove = new Move(60, 58);
                            castleMove.isCastle = true;
                            moves.add(castleMove);
                        }
                    }
                }
            }
        }
        return moves;
    }
    public List<Move> generateWhiteRookMoves(Board board) {
        List<Move> moves = new ArrayList<>();
        long rooks = board.whiteRooks; // Make sure to add this to your Board class!
        long validSquares = ~board.whitePieces; 

        while (rooks != 0) {
            int startSquare = Long.numberOfTrailingZeros(rooks);
            
            // 1. Get ONLY the blockers on the board that matter for this square
            long blockers = board.allPieces & rookMasks[startSquare];
            
            // 2. Hash it to find the index
            int magicIndex = magicHash(blockers, rookMagics[startSquare], Long.bitCount(rookMasks[startSquare]));
            
            // 3. O(1) Lookup and mask out friendly pieces
            long attacks = rookAttacks[startSquare][magicIndex] & validSquares;
            
            extractMoves(attacks, startSquare, moves);
            rooks &= (rooks - 1); 
        }
        return moves;
    }

    public List<Move> generateBlackRookMoves(Board board) {
        List<Move> moves = new ArrayList<>();
        long rooks = board.blackRooks; // Make sure to add this to your Board class!
        long validSquares = ~board.blackPieces; 

        while (rooks != 0) {
            int startSquare = Long.numberOfTrailingZeros(rooks);
            
            // 1. Get ONLY the blockers on the board that matter for this square
            long blockers = board.allPieces & rookMasks[startSquare];
            
            // 2. Hash it to find the index
            int magicIndex = magicHash(blockers, rookMagics[startSquare], Long.bitCount(rookMasks[startSquare]));
            
            // 3. O(1) Lookup and mask out friendly pieces
            long attacks = rookAttacks[startSquare][magicIndex] & validSquares;
            
            extractMoves(attacks, startSquare, moves);
            rooks &= (rooks - 1); 
        }
        return moves;
    }

    public List<Move> generateWhiteBishopMoves(Board board) {
        List<Move> moves = new ArrayList<>();
        long bishops = board.whiteBishops; // Make sure to add this to your Board class!
        long validSquares = ~board.whitePieces; 

        while (bishops != 0) {
            int startSquare = Long.numberOfTrailingZeros(bishops);
            
            // 1. Get ONLY the blockers on the board that matter for this square
            long blockers = board.allPieces & bishopMasks[startSquare];
            
            // 2. Hash it to find the index
            int magicIndex = magicHash(blockers, bishopMagics[startSquare], Long.bitCount(bishopMasks[startSquare]));
            
            // 3. O(1) Lookup and mask out friendly pieces
            long attacks = bishopAttacks[startSquare][magicIndex] & validSquares;
            
            extractMoves(attacks, startSquare, moves);
            bishops &= (bishops - 1); 
        }
        return moves;
    }

    public List<Move> generateBlackBishopMoves(Board board) {
        List<Move> moves = new ArrayList<>();
        long bishops = board.blackBishops; // Make sure to add this to your Board class!
        long validSquares = ~board.blackPieces; 

        while (bishops != 0) {
            int startSquare = Long.numberOfTrailingZeros(bishops);
            
            // 1. Get ONLY the blockers on the board that matter for this square
            long blockers = board.allPieces & bishopMasks[startSquare];
            
            // 2. Hash it to find the index
            int magicIndex = magicHash(blockers, bishopMagics[startSquare], Long.bitCount(bishopMasks[startSquare]));
            
            // 3. O(1) Lookup and mask out friendly pieces
            long attacks = bishopAttacks[startSquare][magicIndex] & validSquares;
            
            extractMoves(attacks, startSquare, moves);
            bishops &= (bishops - 1); 
        }
        return moves;
    }

    // --- QUEEN MOVE GENERATION ---

    public List<Move> generateWhiteQueenMoves(Board board) {
        List<Move> moves = new ArrayList<>();
        long queens = board.whiteQueens; // Ensure this is in your Board class!
        long validSquares = ~board.whitePieces; 

        while (queens != 0) {
            int startSquare = Long.numberOfTrailingZeros(queens);
            
            // 1. Get Rook-style attacks
            long rookBlockers = board.allPieces & rookMasks[startSquare];
            int rookIndex = magicHash(rookBlockers, rookMagics[startSquare], Long.bitCount(rookMasks[startSquare]));
            long rookAttacksBoard = rookAttacks[startSquare][rookIndex];

            // 2. Get Bishop-style attacks
            long bishopBlockers = board.allPieces & bishopMasks[startSquare];
            int bishopIndex = magicHash(bishopBlockers, bishopMagics[startSquare], Long.bitCount(bishopMasks[startSquare]));
            long bishopAttacksBoard = bishopAttacks[startSquare][bishopIndex];

            // 3. Combine them and mask out friendly pieces
            long queenAttacksBoard = (rookAttacksBoard | bishopAttacksBoard) & validSquares;
            
            extractMoves(queenAttacksBoard, startSquare, moves);
            queens &= (queens - 1); 
        }
        return moves;
    }

    public List<Move> generateBlackQueenMoves(Board board) {
        List<Move> moves = new ArrayList<>();
        long queens = board.blackQueens; 
        long validSquares = ~board.blackPieces; 

        while (queens != 0) {
            int startSquare = Long.numberOfTrailingZeros(queens);
            
            // 1. Get Rook-style attacks
            long rookBlockers = board.allPieces & rookMasks[startSquare];
            int rookIndex = magicHash(rookBlockers, rookMagics[startSquare], Long.bitCount(rookMasks[startSquare]));
            long rookAttacksBoard = rookAttacks[startSquare][rookIndex];

            // 2. Get Bishop-style attacks
            long bishopBlockers = board.allPieces & bishopMasks[startSquare];
            int bishopIndex = magicHash(bishopBlockers, bishopMagics[startSquare], Long.bitCount(bishopMasks[startSquare]));
            long bishopAttacksBoard = bishopAttacks[startSquare][bishopIndex];

            // 3. Combine them and mask out friendly pieces
            long queenAttacksBoard = (rookAttacksBoard | bishopAttacksBoard) & validSquares;
            
            extractMoves(queenAttacksBoard, startSquare, moves);
            queens &= (queens - 1); 
        }
        return moves;
    }

    /**
     * Gathers every single pseudo-legal move for White into one master list.
     */
    public List<Move> generateAllWhiteMoves(Board board) {
        List<Move> allMoves = new ArrayList<>();
        allMoves.addAll(generateWhitePawnMoves(board));
        // Note: Make sure the names below match the actual methods you wrote earlier!
        allMoves.addAll(generateWhiteKnightMoves(board));
        allMoves.addAll(generateWhiteBishopMoves(board));
        allMoves.addAll(generateWhiteRookMoves(board));
        allMoves.addAll(generateWhiteQueenMoves(board));
        allMoves.addAll(generateWhiteKingMoves(board));
        return allMoves;
    }

    /**
     * Gathers every single pseudo-legal move for Black into one master list.
     */
    public List<Move> generateAllBlackMoves(Board board) {
        List<Move> allMoves = new ArrayList<>();
        allMoves.addAll(generateBlackPawnMoves(board));
        allMoves.addAll(generateBlackKnightMoves(board));
        allMoves.addAll(generateBlackBishopMoves(board));
        allMoves.addAll(generateBlackRookMoves(board));
        allMoves.addAll(generateBlackQueenMoves(board));
        allMoves.addAll(generateBlackKingMoves(board));
        return allMoves;
    }

    /**
     * Generates only STRICTLY LEGAL moves by simulating each pseudo-legal move
     * and ensuring it doesn't leave the King in check.
     */
    public List<Move> getLegalMoves(Board board, boolean isWhite) {
        List<Move> pseudoLegalMoves = isWhite ? generateAllWhiteMoves(board) : generateAllBlackMoves(board);
        List<Move> strictlyLegalMoves = new ArrayList<>();

        for (Move move : pseudoLegalMoves) {
            // 1. Play the move
            board.makeMove(move, isWhite);

            // 2. Find our King
            long kingBoard = isWhite ? board.whiteKing : board.blackKing;
            
            // Note: If the king was captured in a pseudo-legal timeline, kingBoard is 0.
            if (kingBoard != 0) {
                int kingSquare = Long.numberOfTrailingZeros(kingBoard);
                
                // 3. Is the King safe? If yes, the move is legal!
                if (!isSquareAttacked(kingSquare, !isWhite, board)) {
                    strictlyLegalMoves.add(move);
                }
            }

            // 4. Undo the move
            board.undoMove(move, isWhite);
        }

        return strictlyLegalMoves;
    }
    
    // --- MAGIC BITBOARD MASK GENERATION ---

    /**
     * Generates a mask for a Rook on a specific square, EXCLUDING the outer edges.
     */
    private long maskRookAttacks(int square) {
        long mask = 0L;
        int targetRank = square / 8;
        int targetFile = square % 8;

        // Up (Stop at rank 6, leaving rank 7/edge alone)
        for (int r = targetRank + 1; r <= 6; r++) {
            mask |= (1L << (r * 8 + targetFile));
        }
        // Down (Stop at rank 1, leaving rank 0/edge alone)
        for (int r = targetRank - 1; r >= 1; r--) {
            mask |= (1L << (r * 8 + targetFile));
        }
        // Right (Stop at file 6, leaving file 7/edge alone)
        for (int f = targetFile + 1; f <= 6; f++) {
            mask |= (1L << (targetRank * 8 + f));
        }
        // Left (Stop at file 1, leaving file 0/edge alone)
        for (int f = targetFile - 1; f >= 1; f--) {
            mask |= (1L << (targetRank * 8 + f));
        }
        return mask;
    }

    /**
     * Generates a mask for a Bishop on a specific square, EXCLUDING the outer edges.
     */
    private long maskBishopAttacks(int square) {
        long mask = 0L;
        int targetRank = square / 8;
        int targetFile = square % 8;

        // Up-Right
        for (int r = targetRank + 1, f = targetFile + 1; r <= 6 && f <= 6; r++, f++) {
            mask |= (1L << (r * 8 + f));
        }
        // Up-Left
        for (int r = targetRank + 1, f = targetFile - 1; r <= 6 && f >= 1; r++, f--) {
            mask |= (1L << (r * 8 + f));
        }
        // Down-Right
        for (int r = targetRank - 1, f = targetFile + 1; r >= 1 && f <= 6; r--, f++) {
            mask |= (1L << (r * 8 + f));
        }
        // Down-Left
        for (int r = targetRank - 1, f = targetFile - 1; r >= 1 && f >= 1; r--, f--) {
            mask |= (1L << (r * 8 + f));
        }
        return mask;
    }

    /**
     * Maps an integer index to a specific combination of blockers on a mask.
     * * @param index The arrangement number (e.g., 0 to 4095)
     * @param bitsInMask The number of '1's in the attack mask
     * @param attackMask The chopped-edge mask we generated earlier
     * @return A bitboard representing one specific combination of blocking pieces
     */
    private long setOccupancy(int index, int bitsInMask, long attackMask) {
        long occupancy = 0L;

        // Loop through the total number of bits in our mask
        for (int count = 0; count < bitsInMask; count++) {
            // Find the square index of the lowest '1' in the attack mask
            int square = Long.numberOfTrailingZeros(attackMask);
            
            // Instantly clear that lowest '1' so we can find the next one on the next loop iteration
            attackMask &= (attackMask - 1);
            
            // Check if the 'count'-th bit is set to '1' in our index integer
            // If it is, we place a blocker on the corresponding square in our occupancy board
            if ((index & (1 << count)) != 0) {
                occupancy |= (1L << square);
            }
        }
        return occupancy;
    }

    /**
     * Calculates true Rook attacks using slow Ray Casting. 
     * ONLY USED DURING INITIALIZATION to populate the Magic arrays.
     */
    private long rookAttacksOnTheFly(int square, long block) {
        long attacks = 0L;
        int targetRank = square / 8;
        int targetFile = square % 8;

        // Up
        for (int r = targetRank + 1; r <= 7; r++) {
            attacks |= (1L << (r * 8 + targetFile));
            if ((block & (1L << (r * 8 + targetFile))) != 0) break; // Hit a piece, stop
        }
        // Down
        for (int r = targetRank - 1; r >= 0; r--) {
            attacks |= (1L << (r * 8 + targetFile));
            if ((block & (1L << (r * 8 + targetFile))) != 0) break;
        }
        // Right
        for (int f = targetFile + 1; f <= 7; f++) {
            attacks |= (1L << (targetRank * 8 + f));
            if ((block & (1L << (targetRank * 8 + f))) != 0) break;
        }
        // Left
        for (int f = targetFile - 1; f >= 0; f--) {
            attacks |= (1L << (targetRank * 8 + f));
            if ((block & (1L << (targetRank * 8 + f))) != 0) break;
        }
        return attacks;
    }

    /**
     * Calculates true Bishop attacks using slow Ray Casting. 
     * ONLY USED DURING INITIALIZATION to populate the Magic arrays.
     */
    private long bishopAttacksOnTheFly(int square, long block) {
        long attacks = 0L;
        int targetRank = square / 8;
        int targetFile = square % 8;

        // Up-Right
        for (int r = targetRank + 1, f = targetFile + 1; r <= 7 && f <= 7; r++, f++) {
            attacks |= (1L << (r * 8 + f));
            if ((block & (1L << (r * 8 + f))) != 0) break;
        }
        // Up-Left
        for (int r = targetRank + 1, f = targetFile - 1; r <= 7 && f >= 0; r++, f--) {
            attacks |= (1L << (r * 8 + f));
            if ((block & (1L << (r * 8 + f))) != 0) break;
        }
        // Down-Right
        for (int r = targetRank - 1, f = targetFile + 1; r >= 0 && f <= 7; r--, f++) {
            attacks |= (1L << (r * 8 + f));
            if ((block & (1L << (r * 8 + f))) != 0) break;
        }
        // Down-Left
        for (int r = targetRank - 1, f = targetFile - 1; r >= 0 && f >= 0; r--, f--) {
            attacks |= (1L << (r * 8 + f));
            if ((block & (1L << (r * 8 + f))) != 0) break;
        }
        return attacks;
    }

    // --- HELPER METHODS ---

    /**
     * Extracts pawn moves from a bitboard and automatically handles promotions.
     */
    private void extractPawnMoves(long targetBitboard, int offset, List<Move> moves) {
        while (targetBitboard != 0) {
            int targetSquare = Long.numberOfTrailingZeros(targetBitboard);
            int startSquare = targetSquare + offset;
            
            // If the target square is on Rank 8 (56-63) or Rank 1 (0-7), it's a promotion!
            if (targetSquare >= 56 || targetSquare <= 7) {
                // Generate 4 separate promotion moves for this single pawn
                for (int piece = 2; piece <= 5; piece++) { // 2=Knight, 3=Bishop, 4=Rook, 5=Queen
                    Move promoMove = new Move(startSquare, targetSquare);
                    promoMove.promotedPiece = piece;
                    moves.add(promoMove);
                }
            } else {
                // Standard pawn move
                moves.add(new Move(startSquare, targetSquare));
            }
            
            // Clear the least significant bit
            targetBitboard &= (targetBitboard - 1); 
        }
    }

    /**
     * For pieces like Knights where we already know the exact start square.
     */
    private void extractMoves(long targetBitboard, int startSquare, List<Move> moves) {
        while (targetBitboard != 0) {
            int targetSquare = Long.numberOfTrailingZeros(targetBitboard);
            moves.add(new Move(startSquare, targetSquare));
            targetBitboard &= (targetBitboard - 1);
        }
    }

    /**
     * The Magic Hash: Compresses a 64-bit blocker board into a small array index.
     * @param blockers The current pieces blocking the sliding piece
     * @param magic The Magic Number for this specific square
     * @param bits The number of bits in the mask (e.g., 12 for a Rook on D4)
     */
    private int magicHash(long blockers, long magic, int bits) {
        return (int) ((blockers * magic) >>> (64 - bits));
    }

    /**
     * Checks if a specific square is attacked by the ENEMY color.
     * @param square The square index to check (0-63)
     * @param byWhite True if we are checking if WHITE is attacking the square (so the square belongs to Black).
     * @param board The current board state
     * @return True if the square is under attack, false if it is safe.
     */
    public boolean isSquareAttacked(int square, boolean byWhite, Board board) {
        long squareBit = 1L << square;

        if (byWhite) {
            // 1. Attacked by White Pawns? (A Black pawn on this square would attack UP-LEFT and UP-RIGHT)
            // If we shift this square DOWN-LEFT or DOWN-RIGHT, do we hit a White Pawn?
            if ((((squareBit & NOT_A_FILE) >>> 9) & board.whitePawns) != 0) return true;
            if ((((squareBit & NOT_H_FILE) >>> 7) & board.whitePawns) != 0) return true;

            // 2. Attacked by White Knights?
            if ((knightAttacks[square] & board.whiteKnights) != 0) return true;

            // 3. Attacked by White King?
            if ((kingAttacks[square] & board.whiteKing) != 0) return true;

            // 4. Attacked by White Bishops or Queens? (Using Magic Bitboards!)
            long bishopBlockers = board.allPieces & bishopMasks[square];
            int bishopIndex = magicHash(bishopBlockers, bishopMagics[square], Long.bitCount(bishopMasks[square]));
            if ((bishopAttacks[square][bishopIndex] & (board.whiteBishops | board.whiteQueens)) != 0) return true;

            // 5. Attacked by White Rooks or Queens? (Using Magic Bitboards!)
            long rookBlockers = board.allPieces & rookMasks[square];
            int rookIndex = magicHash(rookBlockers, rookMagics[square], Long.bitCount(rookMasks[square]));
            if ((rookAttacks[square][rookIndex] & (board.whiteRooks | board.whiteQueens)) != 0) return true;

        } else {
            // 1. Attacked by Black Pawns? (A White pawn on this square would attack DOWN-LEFT and DOWN-RIGHT)
            if ((((squareBit & NOT_H_FILE) << 9) & board.blackPawns) != 0) return true;
            if ((((squareBit & NOT_A_FILE) << 7) & board.blackPawns) != 0) return true;

            // 2. Attacked by Black Knights?
            if ((knightAttacks[square] & board.blackKnights) != 0) return true;

            // 3. Attacked by Black King?
            if ((kingAttacks[square] & board.blackKing) != 0) return true;

            // 4. Attacked by Black Bishops or Queens?
            long bishopBlockers = board.allPieces & bishopMasks[square];
            int bishopIndex = magicHash(bishopBlockers, bishopMagics[square], Long.bitCount(bishopMasks[square]));
            if ((bishopAttacks[square][bishopIndex] & (board.blackBishops | board.blackQueens)) != 0) return true;

            // 5. Attacked by Black Rooks or Queens?
            long rookBlockers = board.allPieces & rookMasks[square];
            int rookIndex = magicHash(rookBlockers, rookMagics[square], Long.bitCount(rookMasks[square]));
            if ((rookAttacks[square][rookIndex] & (board.blackRooks | board.blackQueens)) != 0) return true;
        }

        return false; // If we survive all checks, the square is perfectly safe!
    }

    // --- GETTERS & SETTERS ---
    public long getRookMask(int square) {
        return rookMasks[square];
    }

    public long getBishopMask(int square) {
        return bishopMasks[square];
    }
}