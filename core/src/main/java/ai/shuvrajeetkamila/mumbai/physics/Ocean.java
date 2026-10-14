package ai.shuvrajeetkamila.mumbai.physics;

/**
 * The sea surface, as a small sum of directional waves plus a tide.
 *
 * <p>There is exactly one of these and both the boat physics and the water
 * shader evaluate it. That is the point: if the renderer drew one surface and
 * the physics floated the hull on another, a boat would visibly sit inside a
 * wave crest or hover above a trough, and no amount of tuning either side
 * would fix it. The same four components, the same phases, the same tide.
 *
 * <p>Amplitudes are modest on purpose. The Arabian Sea inside Mumbai Harbour
 * and along the western shore is usually a swell of a few tens of centimetres
 * outside the monsoon, not an ocean passage.
 */
public final class Ocean {

    /** Mean sea level in world metres, matching the compiler's chart datum. */
    public static final double MEAN_LEVEL = 0.0;

    // amplitude (m), wavelength (m), direction (rad, compass), speed (m/s)
    private static final double[] AMP = {0.26, 0.17, 0.11, 0.06};
    private static final double[] LEN = {38.0, 23.0, 13.0, 7.5};
    private static final double[] DIR = {4.20, 3.75, 4.75, 3.10};
    private static final double[] SPD = {5.8, 4.4, 3.2, 2.1};

    /** Mumbai's tidal range is large for a west-coast port: around 4 m springs. */
    private static final double TIDE_AMPLITUDE = 1.9;
    private static final double TIDE_PERIOD = 44712.0;     // M2, 12 h 25 min

    private double time;
    private double choppiness = 1.0;

    public void advance(double dt) { time += dt; }

    public double time() { return time; }

    public void setTime(double t) { time = t; }

    /** Scales wave amplitude; the weather system drives this. */
    public void setChoppiness(double c) { choppiness = Math.max(0, Math.min(2.5, c)); }

    public double choppiness() { return choppiness; }

    /** Tide height above mean sea level, metres. */
    public double tide() {
        return TIDE_AMPLITUDE * Math.sin(2 * Math.PI * time / TIDE_PERIOD);
    }

    /** Water surface height at a world position, metres. */
    public double heightAt(double x, double z) {
        double h = MEAN_LEVEL + tide();
        for (int i = 0; i < AMP.length; i++) {
            double k = 2 * Math.PI / LEN[i];
            double dx = Math.sin(DIR[i]);
            double dz = -Math.cos(DIR[i]);
            h += AMP[i] * choppiness * Math.sin(k * (dx * x + dz * z) - SPD[i] * k * time);
        }
        return h;
    }

    /** How many wave components there are. */
    public static final int WAVES = 4;

    /**
     * The wave set, packed for the renderer: amplitude, wavenumber, the two
     * direction components, and the time phase.
     *
     * <p>This exists so that there is one set of wave numbers in the game
     * rather than two. The shader used to carry its own copy as GLSL
     * constants, under a comment promising they matched these - and they
     * did not match in a single value. The drawn sea had 93 m swells of
     * 0.62 m running one way while the sea the boats floated on had 38 m
     * swells of 0.26 m running another.
     *
     * <p>The phase is wrapped into one cycle before it leaves here, and
     * that matters more than it looks. The shader evaluates sine in 32-bit
     * floats; the phase grows by a few radians a second and after an hour
     * of play it is tens of thousands of radians, where a float can no
     * longer resolve a wave crest. The surface decays into noise, and
     * because the lighting takes a 220-power specular off the slope, noise
     * reads as a sheet of moving white. Wrapping costs nothing and the sine
     * is unchanged: it is periodic, so a whole number of cycles is free.
     *
     * @param out five groups of {@value #WAVES}: amplitude, wavenumber,
     *            direction x, direction z, phase
     */
    public void waveParameters(float[] out) {
        for (int i = 0; i < WAVES; i++) {
            double k = 2 * Math.PI / LEN[i];
            double dx = Math.sin(DIR[i]);
            double dz = -Math.cos(DIR[i]);
            // The phase the shader adds, wrapped to one cycle. Computed in
            // double, where the wrap is exact.
            double phase = -SPD[i] * k * time;
            phase = phase % (2 * Math.PI);
            if (phase < 0) { phase += 2 * Math.PI; }
            out[i] = (float) (AMP[i] * choppiness);
            out[WAVES + i] = (float) k;
            out[WAVES * 2 + i] = (float) dx;
            out[WAVES * 3 + i] = (float) dz;
            out[WAVES * 4 + i] = (float) phase;
        }
    }

    /** Wavelength of the longest component, for the shader's phase wrap. */
    public static double longestWave() { return LEN[0]; }

    /** Surface normal, for buoyancy torque and for the shader's lighting. */
    public void normalAt(double x, double z, double[] out3) {
        double dhx = 0, dhz = 0;
        for (int i = 0; i < AMP.length; i++) {
            double k = 2 * Math.PI / LEN[i];
            double dx = Math.sin(DIR[i]);
            double dz = -Math.cos(DIR[i]);
            double c = AMP[i] * choppiness * k
                    * Math.cos(k * (dx * x + dz * z) - SPD[i] * k * time);
            dhx += c * dx;
            dhz += c * dz;
        }
        double len = Math.sqrt(dhx * dhx + 1 + dhz * dhz);
        out3[0] = -dhx / len;
        out3[1] = 1.0 / len;
        out3[2] = -dhz / len;
    }

    /**
     * Tidal stream, m/s. Flood runs into the creeks and harbour, ebb runs out;
     * the sign follows the derivative of the tide so slack water happens at
     * high and low, which is when it actually happens.
     */
    public void currentAt(double x, double z, double[] out2) {
        double phase = 2 * Math.PI * time / TIDE_PERIOD;
        double rate = Math.cos(phase);                 // d(tide)/dt, normalised
        // Streams set roughly north-east into Thane Creek and the harbour.
        double strength = 0.85 * rate;
        out2[0] = strength * 0.55;
        out2[1] = -strength * 0.83;
    }

    /** Vertical velocity of the surface, used for added-mass style damping. */
    public double verticalVelocityAt(double x, double z) {
        double v = 0;
        for (int i = 0; i < AMP.length; i++) {
            double k = 2 * Math.PI / LEN[i];
            double dx = Math.sin(DIR[i]);
            double dz = -Math.cos(DIR[i]);
            v += -AMP[i] * choppiness * SPD[i] * k
                    * Math.cos(k * (dx * x + dz * z) - SPD[i] * k * time);
        }
        return v + TIDE_AMPLITUDE * (2 * Math.PI / TIDE_PERIOD)
                * Math.cos(2 * Math.PI * time / TIDE_PERIOD);
    }
}
