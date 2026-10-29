# Java Bitboard Chess Engine

**Author:** Manthan Patel

**In production at:** https://chess-engine-deploy.vercel.app

## Overview
A high-performance chess engine built from scratch in Java. This project bypasses traditional array-based board representations in favor of **Bitboards** (64-bit integer mapping) to achieve lightning-fast move generation and collision detection using raw bitwise operations. Regularly adding positional heuristics and advanced positional concepts to improve search for better moves.

## Tech Stack
* **Core Engine:** Java (No external dependencies/frameworks)
* **Web Server:** Native Java `com.sun.net.httpserver`
* **Frontend:** HTML5, CSS3 (Custom animations & SVGs), Vanilla JavaScript
* **API Communication:** RESTful `POST` endpoints, Fetch API, FEN (Forsyth-Edwards Notation) strings
* **Algorithms:** Negamax with Alpha-Beta Pruning, Iterative Deepening, Aspiration Windows, Principal Variation Search, Transposition Table (Zobrist Hashing), Quiescence Search, Null-Move Pruning, Reverse Futility / Futility / Late Move Pruning, Late Move Reductions, Check Extension, Lazy SMP (multi-threaded search), MVV-LVA / Killer / History Move Ordering
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
* **Allocation-free make/unmake:** Moves are packed into a single `int` (start/target square, moving and captured piece, promotion, and flags for En Passant, castling and double pushes). The board keeps an undo stack of castling rights, En Passant target and Zobrist hash, so the search never allocates objects. A mailbox array mirrors the bitboards for O(1) "which piece is on this square" lookups. The `Move` object is kept for the web server, console game and tests.
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
* **Checkmate & Stalemate Detection:** The engine detects end-game states by evaluating empty legal move lists and returns mate scores that depend on the distance from the root (+/- 30000 minus the ply), so it always prefers the fastest checkmate.
* **UCI-Style Output Formatting:** Translates raw centipawn evaluations into standard engine readouts (e.g., `+1.20`) and converts forced mate scores into clean `+M2` formatting.
* **Interactive Playable Loop:** Developed a human vs. AI console interface that parses standard algebraic notation (`e2e4`) and validates human inputs against the strictly legal move generator.

### 6b. Search Speed-Ups (Searching Deeper)
* **Iterative Deepening + Time Control:** The search runs depth 1, 2, 3 and so on until the time budget (2 seconds per CPU move by default, `ENGINE_TIME_MS` to change it) is spent, and always has a best move ready from the last finished depth.
* **Transposition Table:** An incrementally updated Zobrist hash identifies positions, so a position reached by a different move order is never searched twice, and the best move found earlier is tried first.
* **Principal Variation Search, Null-Move Pruning, Late Move Reductions:** Spend the search effort on the most promising line and cut most of the rest early.
* **Quiescence Search:** Keeps resolving captures past the depth limit, so the evaluation is never taken in the middle of an exchange. Evasions are searched when in check.
* **Move Ordering:** Transposition table move, then promotions and captures (MVV-LVA), killer moves, and a history table for quiet moves. Every move is scored once.
* **Cheaper Nodes:** Magic bitboard tables are built once and shared, with precomputed shifts and a flattened attack table. Move lists live in preallocated per-ply buffers.
* **Pseudo-legal, staged move generation:** The search tries the remembered best move first, then captures, and only generates quiet moves if nothing has cut the search off yet. Legality is checked only after a move is made, and only for moves that can actually be illegal (king moves, en passant, moves while in check, and pieces standing on a line with their king).
* **Incremental evaluation:** Material and piece-square scores are updated as moves are made, so evaluating a position only adds the pawn and rook terms.
* **Selective pruning:** Reverse futility pruning, futility pruning and late move pruning skip hopeless moves near the leaves; aspiration windows and a check extension keep the main line sharp.
* **Multi-threaded search (Lazy SMP):** Helper threads search the same position and share what they learn through a lock-free transposition table. Set `ENGINE_THREADS` (default: number of cores, at most 4).
* **Opening book:** `src/Java/book.txt` lists well-known opening lines (one per row, coordinate moves). In CPU games the first moves are answered instantly from the book, chosen at random in proportion to how many lines use them, so the server spends no CPU on openings and games vary. The page shows the name of the opening being played, or "Not in our opening book" once the game leaves it. Add your own lines to the file (format `Opening name: moves`); illegal lines are reported at startup and skipped. Settings: `BOOK=off`, `BOOK_PATH`, `BOOK_MAX_PLIES` (default 20). Search, Bench and Match never use the book.
* **Measured, not guessed:** Every search change was kept only if it won (or did not lose) in engine-vs-engine matches against the previous version (`java Match`, 200â€“300 games each). Two tried ideas lost and were removed: log-based late move reductions and adaptive null-move reductions with internal iterative reductions; a 4-entry bucket transposition table also lost and was reverted.
* **Depth reached (desktop CPU, one thread, average over 6 test positions):** about 14 plies in 1 s, 15 in 2 s and 16 in 4 s, up from 13 / 14 / 15 before; simple endgames reach 20+. A free hosting plan has much less CPU, so it searches several plies less in the same time.
* **Benchmarking:** `java Bench depth [ms] [threads]` reports depth, speed and branching factor; `java Bench perft` checks move generation against known node counts, `fast` and `verify` run the same through the int-move path (verify also checks the hash, mailbox and captures-only generation at every node), `search [depth]` and `time [ms]` report nodes, time and nodes per second, and `tactics` checks known mates.

### 7. Full-Stack Web Interface
* **Native HTTP Server:** Built a lightweight local server handling static file delivery and RESTful API requests.
* **FEN Serialization:** Translates complex 64-bit board states into Forsyth-Edwards Notation strings to communicate seamlessly with the frontend.
* **Stateless API:** Every request carries the whole game as a list of moves (`MODE|MOVES|COLOR`, for example `CPU|e2e4 e7e5|WHITE`), and the server replays it. Two visitors never share a board, and a game survives the host restarting or sleeping. Modes are `CPU`, `PVP` and `EVAL` (analysis only). The reply is `FEN|CPU_MOVE|LEGAL_MOVES|TURN|EVAL|STATUS|FEN_BEFORE_CPU`.
* **Game rules:** Checkmate, stalemate, threefold repetition and the 50-move rule are detected on the server. The engine also treats repetitions and the 50-move limit as draws while searching, so it avoids them when ahead and aims for them when behind.
* **Review:** Back and forward buttons (and the arrow keys, Home and End) step through the game, and any move in the move list can be clicked. The board is read-only while reviewing.
* **Evaluation bar:** A switch shows or hides an engine evaluation bar next to the board (the choice is remembered). Positions the engine has not scored yet are analysed on demand.
* **Resign and game over:** Resigning needs a second click to confirm. After checkmate or a resignation the losing king is tipped over on a red square and the winner's king is on green. Draws colour both kings a neutral grey with a Â½ in the corner.
* **Promotion picker:** Choose queen, rook, bishop or knight when a pawn reaches the last rank.
* **Waking the free host:** Free hosting puts the API to sleep when idle, so the first request can take 30 to 60 seconds. The menu page starts waking it in the background, and the board page shows a warning banner and keeps retrying while it waits.
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

## Hosting

* **Frontend (Vercel):** Import the repository in Vercel. `vercel.json` already tells it to publish the `web` folder with no build step, so the defaults work. The pages call the API at the address in `PROD_API` in `web/Board.html` and in the small script at the end of `web/EnPassant.html`. Change it in both places if the backend moves.
* **Backend (Docker, for example on Render):** `src/Java/Dockerfile` builds and runs `WebServer`. Optional environment variables: `PORT`, `ENGINE_TIME_MS` (thinking time per move, default 2000), `ANALYSIS_TIME_MS` (evaluation bar requests, default 400), `ENGINE_THREADS` (search threads, default the number of cores up to 4) and `TT_MB` (transposition table size, default 32), plus `BOOK`, `BOOK_PATH` and `BOOK_MAX_PLIES` for the opening book.
* **Local development:** Run the server with `java -cp out WebServer` and serve the `web` folder with any static server. Pages opened from `localhost` talk to `http://localhost:8080` automatically (use `?api=http://localhost:PORT` for another port).
* **Tests:** `java -cp out Bench rules` checks mate, stalemate, repetition, the 50-move rule, promotion and the API format. `java -cp out Bench verify` re-checks move generation, hashing and evaluation at every node of known perft trees.
* **Strength testing:** `java -cp NEW Match <games> <ms> <baselineClasspath>` plays a new build against an older compiled copy and prints the result with an Elo estimate.

## Next Steps
* [x] Develop a static board evaluation function.
* [x] Implement the pure Minimax algorithm.
* [x] Implement Alpha-Beta Pruning optimization.
* [x] Teach openings.
* [ ] Keep adding more heuristics.
* [x] Implement Transposition Tables (Zobrist Hashing) to give the Engine positional memory.
* [x] Iterative deepening, quiescence search and selective pruning to search deeper.
* [x] Threefold repetition and 50-move draw rules.
* [x] Back and forward through the game, evaluation bar and resign button.
