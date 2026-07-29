import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class OpeningBook {
    private Map<String, String[]> book;
    private Random rand;

    public OpeningBook() {
        book = new HashMap<>();
        rand = new Random();
        loadBook();
    }

    private void loadBook() {
        
        // 1. Starting Position
        book.put("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", new String[]{"e2e4", "d2d4", "g1f3", "c2c4"});

        // 2. Black's responses to 1. e4 (e5, c5, e6, c6)
        book.put("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR", new String[]{"e7e5", "c7c5", "e7e6", "c7c6"});

        // 3. White's responses to the Sicilian (1. e4 c5)
        book.put("rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR", new String[]{"g1f3", "b1c3"});
        
        // 4. Black's responses to 1. d4 (Nf6, d5)
        book.put("rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR", new String[]{"g8f6", "d7d5"});
    }

    /**
     * Checks if we have a prepared move for this piece layout.
     */
    public String getBookMove(String currentFen) {
        // Split the FEN by spaces and ONLY grab the piece layout at index 0
        String cleanFen = currentFen.split(" ")[0]; 
        
        if (book.containsKey(cleanFen)) {
            String[] options = book.get(cleanFen);
            // Pick a random theoretical move to give the engine variety!
            return options[rand.nextInt(options.length)];
        }
        return null; // Position not in book
    }
}