/**
 * This is just a test file to test if the logic works after every change/addition.
 */

import java.util.List;

public class ChessTest {

    public static void main(String[] args) {
        System.out.println("Initializing Board and Pre-calculating Attack Tables...");
        Board board = new Board();
        MoveGen generator = new MoveGen();

        // --- TEST PAWN MOVES (SINGLE & DOUBLE PUSHES) ---
        System.out.println("\n--- Testing Initial Pawn Moves ---");
        
        Board initialPawnBoard = new Board();
        // Clear the board
        initialPawnBoard.whitePawns = 0L; initialPawnBoard.whiteKnights = 0L; initialPawnBoard.whiteBishops = 0L; initialPawnBoard.whiteRooks = 0L; initialPawnBoard.whiteQueens = 0L; initialPawnBoard.whiteKing = 0L;
        initialPawnBoard.blackPawns = 0L; initialPawnBoard.blackKnights = 0L; initialPawnBoard.blackBishops = 0L; initialPawnBoard.blackRooks = 0L; initialPawnBoard.blackQueens = 0L; initialPawnBoard.blackKing = 0L;
        
        // Setup: White Pawn on E2 (12), Black Pawn on D7 (51)
        initialPawnBoard.whitePawns = 1L << 12;
        initialPawnBoard.blackPawns = 1L << 51;
        initialPawnBoard.updateOccupancies();

        // Test White
        List<Move> whiteInitialMoves = generator.generateWhitePawnMoves(initialPawnBoard);
        System.out.println("White Pawn on E2 Moves:");
        for (Move m : whiteInitialMoves) {
            System.out.println(m); // Expected: 12 to 20 (E3), 12 to 28 (E4)
        }

        // Test Black
        List<Move> blackInitialMoves = generator.generateBlackPawnMoves(initialPawnBoard);
        System.out.println("\nBlack Pawn on D7 Moves:");
        for (Move m : blackInitialMoves) {
            System.out.println(m); // Expected: 51 to 43 (D6), 51 to 35 (D5)
        }

        // --- TEST WHITE KNIGHTS ---
        System.out.println("\n--- Initial White Knights Bitboard ---");
        printBitboard(board.whiteKnights);

        List<Move> whiteKnightMoves = generator.generateWhiteKnightMoves(board);
        System.out.println("Total White Knight moves found: " + whiteKnightMoves.size());
        for (Move m : whiteKnightMoves) {
            System.out.println("White Knight moves from index " + m.startSquare + " to " + m.targetSquare);
        }

        // --- TEST BLACK KNIGHTS ---
        System.out.println("\n--- Initial Black Knights Bitboard ---");
        printBitboard(board.blackKnights);

        List<Move> blackKnightMoves = generator.generateBlackKnightMoves(board);
        System.out.println("Total Black Knight moves found: " + blackKnightMoves.size());
        for (Move m : blackKnightMoves) {
            System.out.println("Black Knight moves from index " + m.startSquare + " to " + m.targetSquare);
        }

        // --- TEST ROOK MASKS ---
        System.out.println("Initializing Engine and Pre-calculating Masks...");

        System.out.println("\n--- Testing Rook Mask for D4 (Index 27) ---");
        long rookMask = generator.getRookMask(27);
        printBitboard(rookMask);

        System.out.println("\n--- Testing Bishop Mask for D4 (Index 27) ---");
        long bishopMask = generator.getBishopMask(27);
        printBitboard(bishopMask);

        // --- TEST WHITE QUEEN MAGIC BITBOARDS ---
        System.out.println("\n--- Testing White Queen on D4 ---");
        
        // Create a blank custom board for testing
        Board queenTestBoard = new Board();
        // Clear all default pieces
        queenTestBoard.whitePawns = 0L; queenTestBoard.whiteKnights = 0L; queenTestBoard.whiteBishops = 0L; queenTestBoard.whiteRooks = 0L; queenTestBoard.whiteKing = 0L;
        queenTestBoard.blackPawns = 0L; queenTestBoard.blackKnights = 0L; queenTestBoard.blackBishops = 0L; queenTestBoard.blackRooks = 0L; queenTestBoard.blackQueens = 0L; queenTestBoard.blackKing = 0L;
        
        // 1. Place a White Queen on D4 (Index 27)
        queenTestBoard.whiteQueens = 1L << 27;
        
        // 2. Place some Black Pawns as target blockers
        // D7 (Index 51) - straight up, H8 (Index 63) - up-right diagonal
        queenTestBoard.blackPawns = (1L << 51) | (1L << 63);
        
        // 3. Update the occupancies manually
        queenTestBoard.whitePieces = queenTestBoard.whiteQueens;
        queenTestBoard.blackPieces = queenTestBoard.blackPawns;
        queenTestBoard.allPieces = queenTestBoard.whitePieces | queenTestBoard.blackPieces;

        // Generate the moves
        List<Move> queenMoves = generator.generateWhiteQueenMoves(queenTestBoard);
        
        // Visualize the resulting attacks on a bitboard!
        long queenAttacksBitboard = 0L;
        for (Move m : queenMoves) {
            queenAttacksBitboard |= (1L << m.targetSquare);
        }
        
        System.out.println("Total Queen moves found: " + queenMoves.size());
        printBitboard(queenAttacksBitboard);

        // --- TEST MAKEMOVE AND UNDOMOVE ---
        System.out.println("\n--- Testing makeMove() and undoMove() ---");
        
        // 1. Setup a fresh board
        Board stateTestBoard = new Board();
        stateTestBoard.whitePawns = 0L; stateTestBoard.whiteKnights = 0L; stateTestBoard.whiteBishops = 0L; stateTestBoard.whiteRooks = 0L; stateTestBoard.whiteQueens = 0L; stateTestBoard.whiteKing = 0L;
        stateTestBoard.blackPawns = 0L; stateTestBoard.blackKnights = 0L; stateTestBoard.blackBishops = 0L; stateTestBoard.blackRooks = 0L; stateTestBoard.blackQueens = 0L; stateTestBoard.blackKing = 0L;

        // Place White Queen on D4 (27) and Black Pawn on D7 (51)
        stateTestBoard.whiteQueens = 1L << 27;
        stateTestBoard.blackPawns = 1L << 51;
        stateTestBoard.updateOccupancies();

        System.out.println("1. BEFORE MOVE:");
        System.out.println("White Queens Bitboard:");
        printBitboard(stateTestBoard.whiteQueens);
        System.out.println("Black Pawns Bitboard:");
        printBitboard(stateTestBoard.blackPawns);

        // 2. Execute the Capture
        // Move from D4 (27) to D7 (51)
        Move captureMove = new Move(27, 51); 
        
        // Note: Make sure your makeMove method is updated with the capture memory logic!
        stateTestBoard.makeMove(captureMove, true); // true = White's turn

        System.out.println("2. AFTER MOVE (Queen captures Pawn):");
        System.out.println("Captured Piece ID recorded: " + captureMove.capturedPiece + " (Should be 1 for Pawn)");
        System.out.println("White Queens Bitboard (Should be on D7):");
        printBitboard(stateTestBoard.whiteQueens);
        System.out.println("Black Pawns Bitboard (Should be completely empty):");
        printBitboard(stateTestBoard.blackPawns);

        // 3. Undo the Capture
        stateTestBoard.undoMove(captureMove, true);

        System.out.println("3. AFTER UNDO (Queen retreats, Pawn resurrected):");
        System.out.println("White Queens Bitboard (Should be back on D4):");
        printBitboard(stateTestBoard.whiteQueens);
        System.out.println("Black Pawns Bitboard (Pawn should be back on D7!):");
        printBitboard(stateTestBoard.blackPawns);

        // --- TEST SQUARE ATTACKED ---
        System.out.println("\n--- Testing isSquareAttacked() ---");
        
        // 1. Setup a fresh board
        Board attackTestBoard = new Board();
        attackTestBoard.whitePawns = 0L; attackTestBoard.whiteKnights = 0L; attackTestBoard.whiteBishops = 0L; attackTestBoard.whiteRooks = 0L; attackTestBoard.whiteQueens = 0L; attackTestBoard.whiteKing = 0L;
        attackTestBoard.blackPawns = 0L; attackTestBoard.blackKnights = 0L; attackTestBoard.blackBishops = 0L; attackTestBoard.blackRooks = 0L; attackTestBoard.blackQueens = 0L; attackTestBoard.blackKing = 0L;

        // 2. Place a White Knight on D4 (Index 27) and a Black Rook on H8 (Index 63)
        attackTestBoard.whiteKnights = 1L << 27;
        attackTestBoard.blackRooks = 1L << 63;
        attackTestBoard.updateOccupancies();

        System.out.println("Board Setup: White Knight on D4, Black Rook on H8");

        // 3. Ask the engine about specific squares
        // A Knight on D4 attacks C6 (42) but DOES NOT attack D5 (35)
        boolean isC6AttackedByWhite = generator.isSquareAttacked(42, true, attackTestBoard); 
        boolean isD5AttackedByWhite = generator.isSquareAttacked(35, true, attackTestBoard); 
        
        System.out.println("Is C6 attacked by White? " + isC6AttackedByWhite + " (Expected: true)");
        System.out.println("Is D5 attacked by White? " + isD5AttackedByWhite + " (Expected: false)");

        // A Rook on H8 attacks down the H-file to H1 (7) and across the 8th rank to A8 (56)
        // It DOES NOT attack G7 (54)
        boolean isH1AttackedByBlack = generator.isSquareAttacked(7, false, attackTestBoard); 
        boolean isA8AttackedByBlack = generator.isSquareAttacked(56, false, attackTestBoard); 
        boolean isG7AttackedByBlack = generator.isSquareAttacked(54, false, attackTestBoard); 

        System.out.println("Is H1 attacked by Black? " + isH1AttackedByBlack + " (Expected: true)");
        System.out.println("Is A8 attacked by Black? " + isA8AttackedByBlack + " (Expected: true)");
        System.out.println("Is G7 attacked by Black? " + isG7AttackedByBlack + " (Expected: false)");

        // --- TEST CASTLING ---
        System.out.println("\n--- Testing Castling (O-O) ---");
        
        Board castleTestBoard = new Board();
        // Clear all pieces except the King and Rooks
        castleTestBoard.whitePawns = 0L; castleTestBoard.whiteKnights = 0L; castleTestBoard.whiteBishops = 0L; castleTestBoard.whiteQueens = 0L;
        castleTestBoard.blackPawns = 0L; castleTestBoard.blackKnights = 0L; castleTestBoard.blackBishops = 0L; castleTestBoard.blackRooks = 0L; castleTestBoard.blackQueens = 0L; castleTestBoard.blackKing = 0L;
        
        // 1. Setup: King on E1 (4), Rooks on A1 (0) and H1 (7)
        castleTestBoard.whiteKing = 1L << 4;
        castleTestBoard.whiteRooks = (1L << 0) | (1L << 7);
        castleTestBoard.updateOccupancies();
        
        // Ensure Castling Rights are true
        castleTestBoard.whiteCanCastleKingside = true;
        castleTestBoard.whiteCanCastleQueenside = true;

        // 2. Generate Moves
        List<Move> kingMoves = generator.generateWhiteKingMoves(castleTestBoard);
        
        Move kingsideCastle = null;
        System.out.println("Valid King Moves:");
        for(Move m : kingMoves) {
            System.out.println(m);
            // Catch the specific Kingside Castle move
            if(m.isCastle && m.targetSquare == 6) {
                kingsideCastle = m;
            }
        }

        // 3. Execute and Undo
        if (kingsideCastle != null) {
            System.out.println("\nExecuting Kingside Castle...");
            castleTestBoard.makeMove(kingsideCastle, true);
            
            System.out.println("After Castle:");
            System.out.println("King Bitboard (Should be on G1):");
            printBitboard(castleTestBoard.whiteKing);
            System.out.println("Rooks Bitboard (Should be on A1 and F1! H1 is empty):");
            printBitboard(castleTestBoard.whiteRooks);
            
            System.out.println("\nUndoing Kingside Castle...");
            castleTestBoard.undoMove(kingsideCastle, true);
            
            System.out.println("After Undo:");
            System.out.println("King Bitboard (Should be back on E1):");
            printBitboard(castleTestBoard.whiteKing);
            System.out.println("Rooks Bitboard (Should be back on A1 and H1):");
            printBitboard(castleTestBoard.whiteRooks);
        } else {
            System.out.println("ERROR: Kingside Castle move not found!");
        }

        // --- TEST CASTLING THROUGH ATTACKED SQUARE ---
        System.out.println("\n--- Testing Castling Through Check ---");
        
        Board attackCastleBoard = new Board();
        // Clear all pieces
        attackCastleBoard.whitePawns = 0L; attackCastleBoard.whiteKnights = 0L; attackCastleBoard.whiteBishops = 0L; attackCastleBoard.whiteQueens = 0L;
        attackCastleBoard.blackPawns = 0L; attackCastleBoard.blackKnights = 0L; attackCastleBoard.blackBishops = 0L; attackCastleBoard.blackRooks = 0L; attackCastleBoard.blackQueens = 0L; attackCastleBoard.blackKing = 0L;
        
        // 1. Setup: King on E1 (4), White Rooks on A1 (0) and H1 (7)
        attackCastleBoard.whiteKing = 1L << 4;
        attackCastleBoard.whiteRooks = (1L << 0) | (1L << 7);
        
        // 2. Place a Black Rook on F8 (61) to attack F1 (5)!
        attackCastleBoard.blackRooks = 1L << 61;
        attackCastleBoard.updateOccupancies();
        
        // Ensure Castling Rights are true
        attackCastleBoard.whiteCanCastleKingside = true;
        attackCastleBoard.whiteCanCastleQueenside = true;

        // 3. Generate Moves
        List<Move> blockedKingMoves = generator.generateWhiteKingMoves(attackCastleBoard);
        
        boolean foundKingside = false;
        boolean foundQueenside = false;
        
        System.out.println("Valid King Moves with F1 under attack by Black Rook:");
        for(Move m : blockedKingMoves) {
            System.out.println(m);
            if(m.isCastle && m.targetSquare == 6) foundKingside = true; // G1
            if(m.isCastle && m.targetSquare == 2) foundQueenside = true; // C1
        }

        System.out.println("\nResults:");
        System.out.println("Kingside Castle Available (Should be false!): " + foundKingside);
        System.out.println("Queenside Castle Available (Should be true): " + foundQueenside);

        // --- TEST EN PASSANT ---
        System.out.println("\n--- Testing En Passant ---");
        
        Board epTestBoard = new Board();
        // Clear all pieces
        epTestBoard.whitePawns = 0L; epTestBoard.whiteKnights = 0L; epTestBoard.whiteBishops = 0L; epTestBoard.whiteRooks = 0L; epTestBoard.whiteQueens = 0L; epTestBoard.whiteKing = 0L;
        epTestBoard.blackPawns = 0L; epTestBoard.blackKnights = 0L; epTestBoard.blackBishops = 0L; epTestBoard.blackRooks = 0L; epTestBoard.blackQueens = 0L; epTestBoard.blackKing = 0L;
        
        // 1. Setup: White Pawn on D5 (35), Black Pawn on E7 (52)
        epTestBoard.whitePawns = 1L << 35;
        epTestBoard.blackPawns = 1L << 52;
        epTestBoard.updateOccupancies();
        
        System.out.println("1. Initial State:");
        System.out.println("White Pawns (D5):");
        printBitboard(epTestBoard.whitePawns);
        System.out.println("Black Pawns (E7):");
        printBitboard(epTestBoard.blackPawns);

        // 2. Black plays E7 to E5 (Double Push)
        Move blackDoublePush = new Move(52, 36);
        epTestBoard.makeMove(blackDoublePush, false); // false = Black's turn
        
        System.out.println("\n2. After Black's Double Push (E7 to E5):");
        System.out.println("En Passant Target Square (Should be 44 for E6): " + epTestBoard.enPassantTarget);
        
        // 3. Generate White Pawn Moves
        List<Move> epMoves = generator.generateWhitePawnMoves(epTestBoard);
        Move theEpMove = null;
        for (Move m : epMoves) {
            if (m.isEnPassant) {
                theEpMove = m;
                System.out.println("Found En Passant Move: " + m);
            }
        }

        // 4. Execute En Passant Capture
        if (theEpMove != null) {
            System.out.println("\n3. Executing En Passant Capture...");
            epTestBoard.makeMove(theEpMove, true); // true = White's turn
            
            System.out.println("White Pawns (Should be on E6):");
            printBitboard(epTestBoard.whitePawns);
            System.out.println("Black Pawns (Should be completely EMPTY!):");
            printBitboard(epTestBoard.blackPawns);
            
            // 5. Undo En Passant Capture
            System.out.println("\n4. Undoing En Passant Capture...");
            epTestBoard.undoMove(theEpMove, true);
            
            System.out.println("White Pawns (Should be back on D5):");
            printBitboard(epTestBoard.whitePawns);
            System.out.println("Black Pawns (Should be resurrected on E5!):");
            printBitboard(epTestBoard.blackPawns);
        } else {
            System.out.println("ERROR: En Passant move not generated!");
        }

        // --- TEST PAWN PROMOTIONS ---
        System.out.println("\n--- Testing Pawn Promotions ---");
        
        Board promoBoard = new Board();
        // Clear the board
        promoBoard.whitePawns = 0L; promoBoard.whiteKnights = 0L; promoBoard.whiteBishops = 0L; promoBoard.whiteRooks = 0L; promoBoard.whiteQueens = 0L; promoBoard.whiteKing = 0L;
        promoBoard.blackPawns = 0L; promoBoard.blackKnights = 0L; promoBoard.blackBishops = 0L; promoBoard.blackRooks = 0L; promoBoard.blackQueens = 0L; promoBoard.blackKing = 0L;
        
        // Setup: White Pawn on A7 (48), Black Pawn on H2 (15)
        promoBoard.whitePawns = 1L << 48;
        promoBoard.blackPawns = 1L << 15;
        promoBoard.updateOccupancies();

        List<Move> whitePromoMoves = generator.generateWhitePawnMoves(promoBoard);
        System.out.println("White Pawn on A7 Moves Found: " + whitePromoMoves.size() + " (Expected: 4)");
        
        Move whiteQueenPromo = null;
        for (Move m : whitePromoMoves) {
            System.out.println(m);
            if (m.promotedPiece == 5) whiteQueenPromo = m; // Grab the Queen promotion
        }

        // Execute and Undo the White Queen Promotion
        if (whiteQueenPromo != null) {
            System.out.println("\nExecuting White Queen Promotion (A7 to A8)...");
            promoBoard.makeMove(whiteQueenPromo, true); // true = White's turn
            
            System.out.println("White Pawns Bitboard (Should be EMPTY):");
            printBitboard(promoBoard.whitePawns);
            System.out.println("White Queens Bitboard (Should have a 1 on A8):");
            printBitboard(promoBoard.whiteQueens);
            
            System.out.println("\nUndoing White Queen Promotion...");
            promoBoard.undoMove(whiteQueenPromo, true);
            
            System.out.println("White Pawns Bitboard (Should be back on A7):");
            printBitboard(promoBoard.whitePawns);
            System.out.println("White Queens Bitboard (Should be EMPTY again):");
            printBitboard(promoBoard.whiteQueens);
        } else {
            System.out.println("ERROR: Queen promotion move not generated!");
        }

        // --- TEST STATIC EVALUATION ---
        System.out.println("\n--- Testing Static Evaluation ---");
        
        Board evalBoard = new Board();
        Evaluator evaluator = new Evaluator();
        
        // Start position (Should be exactly 0)
        System.out.println("Starting Position Score: " + evaluator.evaluate(evalBoard));
        
        // Let's give White an extra Queen, and Black an extra Rook
        evalBoard.whiteQueens |= (1L << 27); // Add Queen (+900)
        evalBoard.blackRooks |= (1L << 35);  // Add Rook (-500)
        
        // The net score should be +400 (White is winning by 4 pawns)
        System.out.println("Modified Board Score (Expected +400): " + evaluator.evaluate(evalBoard));

        // --- TEST POSITIONAL EVALUATION (PIECE-SQUARE TABLES) ---
        System.out.println("\n--- Testing Positional Evaluation ---");
        
        Evaluator posEvaluator = new Evaluator();

        // 1. White Knight: Rim vs Center
        Board badWhiteBoard = new Board();
        Board goodWhiteBoard = new Board();
        
        // Clear both boards
        badWhiteBoard.whitePawns = 0L; badWhiteBoard.whiteKnights = 0L; badWhiteBoard.whiteBishops = 0L; badWhiteBoard.whiteRooks = 0L; badWhiteBoard.whiteQueens = 0L; badWhiteBoard.whiteKing = 0L;
        badWhiteBoard.blackPawns = 0L; badWhiteBoard.blackKnights = 0L; badWhiteBoard.blackBishops = 0L; badWhiteBoard.blackRooks = 0L; badWhiteBoard.blackQueens = 0L; badWhiteBoard.blackKing = 0L;
        
        goodWhiteBoard.whitePawns = 0L; goodWhiteBoard.whiteKnights = 0L; goodWhiteBoard.whiteBishops = 0L; goodWhiteBoard.whiteRooks = 0L; goodWhiteBoard.whiteQueens = 0L; goodWhiteBoard.whiteKing = 0L;
        goodWhiteBoard.blackPawns = 0L; goodWhiteBoard.blackKnights = 0L; goodWhiteBoard.blackBishops = 0L; goodWhiteBoard.blackRooks = 0L; goodWhiteBoard.blackQueens = 0L; goodWhiteBoard.blackKing = 0L;

        // Place White Knight on A1 (Index 0 - Corner)
        badWhiteBoard.whiteKnights = 1L << 0; 
        
        // Place White Knight on D4 (Index 27 - Center)
        goodWhiteBoard.whiteKnights = 1L << 27; 
        
        int badWhiteScore = posEvaluator.evaluate(badWhiteBoard);
        int goodWhiteScore = posEvaluator.evaluate(goodWhiteBoard);
        
        System.out.println("White Knight on A1 Score: " + badWhiteScore + " (Expected: 300 - 50 = 250)");
        System.out.println("White Knight on D4 Score: " + goodWhiteScore + " (Expected: 300 + 20 = 320)");
        System.out.println("Does White prefer the center? " + (goodWhiteScore > badWhiteScore));

        // 2. Black Knight Mirror Test
        Board blackBoard = new Board();
        blackBoard.whitePawns = 0L; blackBoard.whiteKnights = 0L; blackBoard.whiteBishops = 0L; blackBoard.whiteRooks = 0L; blackBoard.whiteQueens = 0L; blackBoard.whiteKing = 0L;
        blackBoard.blackPawns = 0L; blackBoard.blackKnights = 0L; blackBoard.blackBishops = 0L; blackBoard.blackRooks = 0L; blackBoard.blackQueens = 0L; blackBoard.blackKing = 0L;

        // Place Black Knight on D5 (Index 35 - Center for Black)
        // Flipped Index calculation: 35 ^ 56 = 27. It should read the exact same +20 bonus!
        blackBoard.blackKnights = 1L << 35;
        
        int blackScore = posEvaluator.evaluate(blackBoard);
        System.out.println("\nBlack Knight on D5 Score: " + blackScore + " (Expected: -(300 + 20) = -320)");

        // --- TEST MINIMAX AI: MATE IN 1 ---
        System.out.println("\n--- Testing AI: Find the Mate in 1 ---");
        
        Board mateBoard = new Board();
        // Clear everything
        mateBoard.whitePawns = 0L; mateBoard.whiteKnights = 0L; mateBoard.whiteBishops = 0L; mateBoard.whiteRooks = 0L; mateBoard.whiteQueens = 0L; mateBoard.whiteKing = 0L;
        mateBoard.blackPawns = 0L; mateBoard.blackKnights = 0L; mateBoard.blackBishops = 0L; mateBoard.blackRooks = 0L; mateBoard.blackQueens = 0L; mateBoard.blackKing = 0L;

        // Setup Back Rank Mate scenario
        // Black King on G8 (62), blocked by pawns on F7 (53), G7 (54), H7 (55)
        mateBoard.blackKing = 1L << 62;
        mateBoard.blackPawns = (1L << 53) | (1L << 54) | (1L << 55);

        // White Rook on E1 (4), White King safely tucked on A1 (0)
        mateBoard.whiteRooks = 1L << 4;
        mateBoard.whiteKing = 1L << 7;
        
        mateBoard.updateOccupancies();
        
        System.out.println("Board setup complete. Black King is trapped on G8.");

        Search ai = new Search();
        
        // We search at Depth 2 so the engine can see Black's lack of legal responses!
        long startTime = System.currentTimeMillis();
        Move bestMove = ai.getBestMove(mateBoard, 2, true); // true = White to move
        long endTime = System.currentTimeMillis();

        System.out.println("\nSearch took " + (endTime - startTime) + "ms");
        System.out.println("AI Chose: " + bestMove);
        System.out.println("(Expected: Move from 4 to 60)"); // E1 to E8
    }

    public static void printBitboard(long bitboard) {
        for (int rank = 7; rank >= 0; rank--) {
            for (int file = 0; file < 8; file++) {
                int squareIndex = rank * 8 + file;
                long mask = 1L << squareIndex;
                if ((bitboard & mask) != 0) {
                    System.out.print("1 ");
                } else {
                    System.out.print(". ");
                }
            }
            System.out.println("  // Rank " + (rank + 1));
        }
        System.out.println("A B C D E F G H\n");
    }
}