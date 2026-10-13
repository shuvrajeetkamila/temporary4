package ai.shuvrajeetkamila.mumbai.render;

/**
 * The monsoon.
 *
 * <p>Two quantities, and the difference between them is the point:
 *
 * <ul>
 *   <li><b>rain</b> is what is falling right now. It drives the overlay and
 *       the sky.</li>
 *   <li><b>wetness</b> is how wet the ground is. It drives grip and the
 *       specular sheen on the roads.</li>
 * </ul>
 *
 * <p>They are deliberately not the same number. Tarmac soaks up fast and
 * dries slowly, so the road stays slick and shining for minutes after a
 * squall has passed - which is most of what a Mumbai monsoon actually
 * looks like, and means the handling change outlasts the downpour that
 * caused it.
 *
 * <p>Squalls come from value noise over the clock rather than a random
 * walk, so the weather is a pure function of time: the same moment always
 * gives the same sky, nothing drifts if frames are dropped, and it can be
 * tested without stepping through an hour of simulation.
 */
public final class Weather {

    /** Seconds of game time per squall cell. About 3.5 game minutes. */
    private static final double CELL_S = 210.0;

    /** Seconds for soaked ground to dry out. */
    public static final double DRY_S = 300.0;

    /** Seconds for dry ground to soak through. */
    public static final double WET_S = 36.0;

    /**
     * How monsoon the season is: 0 is a dry December, 1 is July. Drives
     * how much of the time it is raining at all.
     */
    public double monsoon = 0.55;

    /** Rain falling now, 0 to 1. */
    public double rain;

    /** Ground wetness, 0 to 1. Lags {@link #rain} in both directions. */
    public double wetness;

    /** Seconds of weather time elapsed. */
    private double clock;

    /** Starts mid-squall or dry, for a test or a fresh game. */
    public void set(double rainNow, double wetNow) {
        rain = rainNow;
        wetness = wetNow;
    }

    /**
     * Rain intensity the sky should show at a given moment, with no state.
     *
     * <p>Value noise: hash each cell of the timeline, smoothstep between
     * neighbours, then threshold against the season so that dry weather is
     * genuinely dry rather than a permanent drizzle.
     */
    public double scheduledRain(double seconds) {
        double t = seconds / CELL_S;
        long cell = (long) Math.floor(t);
        double f = t - cell;
        double a = hash(cell), b = hash(cell + 1);
        double s = f * f * (3 - 2 * f);
        double n = a + (b - a) * s;

        // Below the threshold there is no rain at all; above it, the
        // intensity ramps. A dry season raises the bar rather than
        // scaling a drizzle that never stops.
        double threshold = 1.0 - monsoon;
        if (n <= threshold) { return 0; }
        double span = 1.0 - threshold;
        return span < 1e-9 ? 1.0 : Math.min(1.0, (n - threshold) / span);
    }

    /** Deterministic 0..1 from an integer. */
    private static double hash(long i) {
        long x = i * 0x9E3779B97F4A7C15L;
        x ^= x >>> 29;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 32;
        // Top 53 bits into [0, 1).
        return (x >>> 11) / (double) (1L << 53);
    }

    /**
     * Advances the weather.
     *
     * @param dt seconds of game time
     */
    public void update(double dt) {
        if (dt <= 0) { return; }
        clock += dt;
        rain = scheduledRain(clock);

        // Ground wetness chases the rain, fast up and slow down.
        double tau = rain > wetness ? WET_S : DRY_S;
        // Exponential approach, framerate independent: at dt == tau this
        // closes 63% of the gap whatever the step size, where a linear
        // rate would overshoot on a long frame.
        double k = 1.0 - Math.exp(-dt / tau);
        wetness += (rain - wetness) * k;
        if (wetness < 0) { wetness = 0; }
        if (wetness > 1) { wetness = 1; }
    }

    /** Where the weather clock is, in seconds. */
    public double clock() { return clock; }

    /** Puts the clock somewhere specific, for a test or a fixed scenario. */
    public void seek(double seconds) {
        clock = seconds;
        rain = scheduledRain(clock);
    }

    /** True when it is raining hard enough to be worth drawing. */
    public boolean raining() { return rain > 0.02; }
}
