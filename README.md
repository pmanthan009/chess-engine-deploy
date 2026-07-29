# Java Bitboard Chess Engine

**Author:** Manthan Patel

## Overview
A high-performance chess engine built from scratch in Java. This project bypasses traditional array-based board representations in favor of **Bitboards** (64-bit integer mapping) to achieve lightning-fast move generation and collision detection using raw bitwise operations. Regularly adding positional heuristics and advanced positional concepts to improve search for better moves.

## Tech Stack
* **Core Engine:** Java (No external dependencies/frameworks)
* **Web Server:** Native Java `com.sun.net.httpserver`
* **Frontend:** HTML5, CSS3 (Custom animations & SVGs), Vanilla JavaScript
* **API Communication:** RESTful `POST` endpoints, Fetch API, FEN (Forsyth-Edwards Notation) strings
* **Algorithms:** Minimax, Alpha-Beta Pruning, MVV-LVA Move Ordering
* **Data Structures:** 64-bit Bitboards, Magic Bitboards, Piece-Square Tables (PSTs)

## Current Progress

### 1. Board Representation
* **Bitboard Architecture:** The board is represented using 64-bit `long` primitives, where each bit maps to a specific square on the 8x8 chessboard.
* **Hexadecimal Initialization:** The standard starting position is initialized using compressed Hex values for efficient memory allocation.
* **Occupancy Tracking:** Maintained combined bitboards for White pieces, Black pieces, and all pieces to perform O(1) collision detection.

### 2. Move Generation
The engine currently supports pseudo-legal and strictly legal move generation for all piece types:

* **Pawns (White & Black):**
    * Calculates single pushes, double pushes, and diagonal captures simultaneously using bitwise shifts.
    * Implements File A and File H masks to prevent the "Wrap-Around Bug."
    * Utilizes Java's unsigned right shift (`>>>`) for Black pawns to prevent sign extension errors.
    * **En Passant:** Accurately detects and captures "Ghost Pawns" left behind by double-pushes, reaching behind the target square to clear the enemy piece.
    * **Promotions:** Utilizes a specialized extraction loop to seamlessly morph pawns into Knights, Bishops, Rooks, or Queens upon reaching the final rank.
* **Knights & Kings (Leaping Pieces):**
    * Utilizes **Pre-calculated Attack Look-Up Tables**.
    * Calculates all possible attack squares for all 64 board positions exactly once upon initialization.
    * In-game move generation is reduced to an O(1) array lookup masked against friendly occupancies.
* **Rooks, Bishops, & Queens (Sliding Pieces):**
    * Implemented **Magic Bitboards** for O(1) sliding piece move generation, completely eliminating in-game `while` loops.
    * Generates blocker masks that strategically exclude outer edges to compress the hash tables (e.g., 4096 combinations for Rooks, 512 for Bishops).
    * **Dynamic Magic Generator:** Bypasses fragile hardcoded arrays by implementing a brute-force algorithm that discovers perfect, collision-free 64-bit Magic Numbers dynamically during engine startup (mirroring the architecture of modern engines like Stockfish).
    * Queens efficiently reuse and combine (bitwise OR) the Rook and Bishop lookup tables.

### 3. Move Extraction & Bit-Twiddling
* Implemented a highly optimized move extraction loop using `Long.numberOfTrailingZeros()` to identify target squares.
* Utilizes the `targetBitboard &= (targetBitboard - 1)` trick to instantly clear the least significant bit, avoiding unnecessary loop iterations.

### 4. Board State Management & Spatial Awareness
* Implemented `makeMove()` and `undoMove()` functions to physically transition the board between states using extremely fast bitwise XOR (`^`) operations.
* **Special Moves Execution:** Handles complex state changes like Castling (simultaneous King/Rook teleportation via XOR) and piece morphing without array corruption.
* Engineered a memory-aware `Move` object that acts as a historical snapshot. It records captured piece IDs, castling rights, and En Passant targets, allowing the engine to perfectly reconstruct previous game states during deep search tree reversions.
* **Reverse Attack Detection:** Implemented an O(1) `isSquareAttacked()` function utilizing the pre-calculated attack tables to give the engine spatial awareness for Castling path validation and Check detection.

### 5. Static Evaluation (The AI's "Taste")
* **Hardware-Accelerated Material Counting:** Utilizes Java's `Long.bitCount()` (which maps to the CPU's native `popcnt` instruction) to count pieces across 64 squares in a single CPU cycle.
* **Centipawn Scoring:** Evaluates board states using standard centipawn metrics (Pawn = 100, Knight/Bishop = 300, Rook = 500, Queen = 900) to allow for granular positional bonuses.
* **Piece-Square Tables (PSTs):** Implemented an array of positional bonuses to grant the engine strategic intuition (e.g., centralizing knights, tucking the King away, pushing pawns).
* **Bitwise Mirroring:** Reuses White's Piece-Square Tables for Black by dynamically flipping the target square indices using a vertical mirror XOR calculation (`square ^ 56`).

### 6. Minimax Search Algorithm & Alpha-Beta Pruning (The AI Brain)
* **Recursive Search Tree:** Implemented a Minimax algorithm to traverse future game states by recursively simulating and unmaking moves via the bitboard engine.
* **Alpha-Beta Pruning:** Drastically optimized the search tree by implementing $\alpha$ and $\beta$ cutoffs. This mathematically reduces the time complexity from $O(b^d)$ to $O(b^{d/2})$, allowing the engine to sever useless branches and search twice as deep in the same amount of time.
* **Strictly Legal Filter:** Engineered an on-the-fly move filter that physically tests pseudo-legal moves against the `isSquareAttacked` function to eliminate timelines where the King is left in check.
* **Checkmate & Stalemate Detection:** The engine detects end-game states by evaluating empty legal move lists and returns depth-modified astronomical scores (+/- 99999) to force the fastest possible checkmate.
* **UCI-Style Output Formatting:** Translates raw centipawn evaluations into standard engine readouts (e.g., `+1.20`) and converts forced mate scores into clean `+M2` formatting.
* **Interactive Playable Loop:** Developed a human vs. AI console interface that parses standard algebraic notation (`e2e4`) and validates human inputs against the strictly legal move generator.

### 7. Full-Stack Web Interface
* **Native HTTP Server:** Built a lightweight local server handling static file delivery and RESTful API requests.
* **FEN Serialization:** Translates complex 64-bit board states into Forsyth-Edwards Notation strings to communicate seamlessly with the frontend.
* **Premium UI/UX:** Developed a responsive, dark-mode graphical interface using Vanilla JS, featuring optimistic UI rendering for zero-latency human piece movement, dynamic legal-move highlighting, and scrolling SVG backgrounds.

## Test Outputs (Backend):

### 1. Knights:
<img width="733" height="845" alt="Screenshot 2026-03-04 172742" src="https://github.com/user-attachments/assets/124c9622-db8f-420d-a362-96481980fa5d" />

### 2. Sliding Pieces (Bishops, Rooks, Queens):
<img width="618" height="869" alt="Screenshot 2026-03-04 172759" src="https://github.com/user-attachments/assets/ab344b77-9c10-4024-aff5-7b25f0e00ae6" />

### 3. Captures (with a demo of undoMove() as well):
<img width="537" height="548" alt="Screenshot 2026-03-04 172826" src="https://github.com/user-attachments/assets/24b2a8c7-9aa2-404d-a256-4c7f7372d298" />
<img width="681" height="548" alt="Screenshot 2026-03-04 172847" src="https://github.com/user-attachments/assets/09765381-4cad-407b-bae3-819b391b52eb" />
<img width="659" height="550" alt="Screenshot 2026-03-04 172858" src="https://github.com/user-attachments/assets/296c151c-3dfa-480a-8bdc-6f6ad90801f5" />

### 4. Castling:
<img width="695" height="788" alt="Screenshot 2026-03-04 172934" src="https://github.com/user-attachments/assets/69132b18-284c-4026-a7c6-c0205dff1fdb" />
<img width="707" height="869" alt="Screenshot 2026-03-04 172955" src="https://github.com/user-attachments/assets/298d0135-3fd8-45fc-9547-37d7ab937057" />

### 5. Pawn Promotions:
<img width="669" height="694" alt="Screenshot 2026-03-06 235203" src="https://github.com/user-attachments/assets/2cba195b-04a8-4ba7-aa81-1b0f32b26988" />
<img width="632" height="531" alt="Screenshot 2026-03-06 235214" src="https://github.com/user-attachments/assets/114cf213-e894-499b-9626-a75aa2ce3f80" />

Frontend Design:

### 1. Home Page/ Landing:
<img width="1893" height="902" alt="Screenshot 2026-03-17 231915" src="https://github.com/user-attachments/assets/27e3b684-0864-4c48-80a3-03410bdbffb7" />
<img width="1889" height="902" alt="Screenshot 2026-03-17 231949" src="https://github.com/user-attachments/assets/8bef0569-880c-4b4c-9a92-ad9473613c3b" />
<img width="1881" height="871" alt="Screenshot 2026-03-17 232010" src="https://github.com/user-attachments/assets/589e4837-9af0-47fd-aaa7-728b58ccec12" />

### 2. Board Page:
<img width="1919" height="903" alt="Screenshot 2026-03-17 232103" src="https://github.com/user-attachments/assets/07d2c73a-91a5-40fd-a96c-864546738651" />

## Next Steps
* [x] Develop a static board evaluation function.
* [x] Implement the pure Minimax algorithm.
* [x] Implement Alpha-Beta Pruning optimization.
* [x] Teach openings.
* [ ] Keep adding more heuristics.
* [ ] Implement Transposition Tables (Zobrist Hashing) to give the Engine positional memory.
