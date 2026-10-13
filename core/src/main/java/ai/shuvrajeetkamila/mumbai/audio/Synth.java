package ai.shuvrajeetkamila.mumbai.audio;

/**
 * Sound, generated from nothing.
 *
 * <p>Every noise in this game is computed sample by sample. There are no
 * recordings anywhere in the package, which keeps the whole soundtrack
 * original and unencumbered for the same reason the artwork is
 * procedural, and it means the engine note follows the actual crankshaft
 * speed from the physics rather than a loop that is nearly the right
 * pitch.
 *
 * <p>Everything here is a pure function of phase, so a voice can be
 * resumed, mixed or resampled without clicking, and the whole thing is
 * testable without an audio device.
 */
public final class Synth {

    private Synth() { }

    /** Output rate. Low on purpose: this is all synthesised per sample. */
    public static final int SAMPLE_RATE = 22050;

    /** Two pi, used on nearly every line below. */
    public static final double TAU = Math.PI * 2;

    /**
     * Band-limited-ish sawtooth.
     *
     * <p>A naive ramp aliases badly at engine frequencies once the
     * harmonics pass Nyquist, which on a small speaker sounds like a
     * kazoo. Summing a fixed number of sine partials instead costs more
     * but stays clean, and an engine only needs a handful to sound like
     * one.
     *
     * @param phase 0..1
     */
    public static double saw(double phase, int partials) {
        double v = 0;
        for (int k = 1; k <= partials; k++) {
            v += Math.sin(TAU * phase * k) / k;
        }
        return v * (2.0 / Math.PI);
    }

    /** Band-limited square, odd partials only. Hollower than the saw. */
    public static double square(double phase, int partials) {
        double v = 0;
        for (int k = 1; k <= partials; k += 2) {
            v += Math.sin(TAU * phase * k) / k;
        }
        return v * (4.0 / Math.PI);
    }

    public static double sine(double phase) { return Math.sin(TAU * phase); }

    /**
     * How many partials fit under Nyquist for a given fundamental.
     *
     * <p>This is what keeps the saw clean as the engine revs: the partial
     * count falls as the pitch rises, so nothing ever folds back.
     */
    public static int partialsFor(double hz) {
        if (hz < 1e-6) { return 1; }
        int n = (int) ((SAMPLE_RATE * 0.5) / hz);
        return Math.max(1, Math.min(16, n));
    }

    /**
     * One-pole low pass. Returns the new state.
     *
     * @param cutoffHz -3 dB point
     */
    public static double lowPass(double state, double in, double cutoffHz) {
        double a = cutoffHz <= 0 ? 0
                : 1.0 - Math.exp(-TAU * cutoffHz / SAMPLE_RATE);
        if (a > 1) { a = 1; }
        return state + a * (in - state);
    }

    /** Cheap deterministic noise in -1..1, from a 32-bit state. */
    public static int noiseNext(int state) {
        state ^= state << 13;
        state ^= state >>> 17;
        state ^= state << 5;
        return state;
    }

    public static double noiseValue(int state) {
        return state / 2.147483647e9;
    }

    /**
     * Distance attenuation and stereo pan for a source around the player.
     *
     * <p>Inverse-distance rather than inverse-square: true inverse-square
     * makes everything beyond twenty metres inaudible and everything
     * inside five deafening, which is correct and useless. Rolloff is
     * clamped so a siren two streets away is still a presence.
     *
     * @param out  two floats written: left gain, right gain
     */
    public static void pan(double dx, double dz, double listenerHeading,
                           double refDistance, double maxDistance,
                           double[] out) {
        double d = Math.hypot(dx, dz);
        double gain;
        if (d <= refDistance) {
            gain = 1.0;
        } else if (d >= maxDistance) {
            gain = 0.0;
        } else {
            gain = refDistance / d;
            // Fade the tail to nothing at maxDistance so sources do not
            // click out when they are culled.
            double t = (d - refDistance) / (maxDistance - refDistance);
            gain *= 1.0 - t * t;
        }
        if (gain <= 0) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        // Where is it, relative to the way the listener is facing? Right of
        // travel in this frame is (-dz, dx); see the note in Quat.roll.
        double c = Math.cos(-listenerHeading), s = Math.sin(-listenerHeading);
        double rel = d < 1e-6 ? 0 : (dx * c - dz * s) / d;
        // Constant-power pan, narrowed so nothing is ever fully silent in
        // one ear; hard panning on headphones is unpleasant in a game you
        // are steering.
        double p = (rel * 0.75 + 1) * 0.5;
        out[0] = (float) (gain * Math.cos(p * Math.PI * 0.5));
        out[1] = (float) (gain * Math.sin(p * Math.PI * 0.5));
    }

    /**
     * Doppler ratio for a source, clamped to something musical.
     *
     * <p>Real Doppler on a car passing at 25 m/s is a shift of about a
     * tone, which is right, but the formula goes to infinity as the
     * closing speed approaches the speed of sound and a physics glitch
     * should not produce a shriek.
     */
    public static double doppler(double closingSpeed) {
        double c = 343.0;
        double r = c / Math.max(1.0, c - closingSpeed);
        return Math.max(0.75, Math.min(1.35, r));
    }

    /**
     * Soft clipper, so a busy mix saturates instead of wrapping round.
     *
     * <p>The cubic and its knee are not arbitrary. {@code v - v^3/6.75}
     * has zero gradient exactly at v = 1.5, where it reaches exactly 1.0,
     * so the curve meets the rail tangentially: it is monotonic all the
     * way up, never overshoots unity, and has unity gain for small
     * signals. Get the divisor wrong and the peak lands above 1.0, which
     * then clamps in the integer conversion and you hear the hard edge
     * you were trying to avoid.
     */
    public static double softClip(double v) {
        if (v >= 1.5) { return 1.0; }
        if (v <= -1.5) { return -1.0; }
        return v - (v * v * v) / 6.75;
    }
}
