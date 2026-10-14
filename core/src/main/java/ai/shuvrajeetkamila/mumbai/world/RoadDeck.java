package ai.shuvrajeetkamila.mumbai.world;

/**
 * The shape of a bridge deck.
 *
 * <p>This exists so the renderer and the physics cannot disagree about
 * where the Bandra-Worli Sea Link is. They did: the mesher lifted bridge
 * decks into an arch over the water, and the ground probe went on
 * returning the bare elevation grid underneath, so every bridge in the
 * city was a painting you drove straight through on your way to the
 * seabed. One formula, one place, two callers.
 */
public final class RoadDeck {

    /** Rise at midspan over the straight line between the abutments. */
    public static final double ARCH_M = 4.5;

    /** A deck never sits closer than this to whatever is under it. */
    public static final double MIN_CLEARANCE_M = 1.2;

    private RoadDeck() { }

    /**
     * Height of the deck a fraction {@code t} along the span.
     *
     * @param y0          ground at the first abutment
     * @param y1          ground at the last abutment
     * @param t           0 at the first abutment, 1 at the last
     * @param groundHere  ground directly below this point, or 0 over water
     */
    public static double height(double y0, double y1, double t, double groundHere) {
        double deck = y0 + (y1 - y0) * t;
        double arch = ARCH_M * Math.sin(Math.PI * t);
        return Math.max(deck + arch, groundHere + MIN_CLEARANCE_M);
    }
}
