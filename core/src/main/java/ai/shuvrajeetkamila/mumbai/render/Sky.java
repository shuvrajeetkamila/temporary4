package ai.shuvrajeetkamila.mumbai.render;

/**
 * Time of day: where the sun is and what colour the air is.
 *
 * <p>In {@code core} rather than in the renderer because it is arithmetic
 * with no OpenGL in it, and because the thing most worth testing about a day
 * cycle is that it is continuous — that midnight matches midnight coming the
 * other way round, and that no keyframe boundary produces a visible jump.
 * {@code SkyTest} walks the whole twenty-four hours in small steps and fails
 * on any discontinuity.
 *
 * <p>The palette is Mumbai's, not a generic one. Three things make the light
 * here look the way it does:
 *
 * <ul>
 *   <li><b>Haze.</b> Sea air and dust mean the sky is almost never a deep
 *       blue. Even at noon in the dry season the zenith is pale and the
 *       horizon is nearly white. The fog colour stays close to the sky
 *       colour throughout, which is what makes distance read as depth
 *       rather than as a grey curtain.</li>
 *   <li><b>The sunset.</b> The city faces west into open sea, which is the
 *       entire reason people stand on Marine Drive at six in the evening.
 *       The dusk keyframes are heavily weighted for it.</li>
 *   <li><b>Latitude.</b> At 19 degrees north the sun climbs steeply and
 *       twilight is short — about twenty-five minutes, against an hour and
 *       a half in northern Europe. The transition out of daylight is
 *       therefore fast, and faking a long golden hour would be wrong.</li>
 * </ul>
 */
public final class Sky {

    /** Normalised direction *towards* the sun, in world axes. */
    public final float[] sunDir = new float[3];
    /** Direct sunlight, already attenuated by air mass near the horizon. */
    public final float[] sunColour = new float[3];
    /** Ambient sky term, also used for the sky dome. */
    public final float[] skyColour = new float[3];
    /** Distance fog, kept near the sky colour so the horizon dissolves. */
    public final float[] fogColour = new float[3];

    /** 0 in daylight, 1 once the sun is well down. Drives window lights. */
    public float nightFactor;
    /** Per-metre extinction for the exponential fog term. */
    public float fogDensity;
    /** Sine of the solar elevation. Negative when the sun is below. */
    public float sunElevation;

    /**
     * Keyframes: hour, then sky RGB, sun RGB, fog RGB.
     *
     * <p>Sun values above 1 are deliberate. The direct term is multiplied by
     * albedo and the result is not tone mapped, so a value of 1.0 at noon
     * gives a flat, dull midday. Overdriving it and letting the darker
     * albedos absorb the excess is what produces glare on pale concrete.
     */
    private static final double[][] KEYS = {
        //  h     sky r  g  b        sun r  g  b        fog r  g  b
        {  0.0,  0.030,0.038,0.070,  0.040,0.048,0.085,  0.055,0.062,0.095 },
        {  4.6,  0.055,0.060,0.105,  0.070,0.072,0.115,  0.085,0.088,0.130 },
        {  5.8,  0.300,0.235,0.260,  0.620,0.330,0.230,  0.330,0.268,0.278 },  // first light
        {  6.6,  0.560,0.450,0.420,  1.150,0.680,0.420,  0.560,0.470,0.430 },  // sunrise
        {  8.0,  0.520,0.620,0.760,  1.180,1.040,0.880,  0.640,0.680,0.730 },  // morning haze
        { 12.0,  0.480,0.600,0.780,  1.260,1.210,1.120,  0.700,0.740,0.790 },  // washed-out noon
        { 15.5,  0.500,0.600,0.740,  1.280,1.130,0.900,  0.710,0.720,0.730 },
        { 17.6,  0.620,0.520,0.460,  1.400,0.880,0.500,  0.720,0.610,0.520 },  // gold
        { 18.5,  0.680,0.420,0.330,  1.350,0.580,0.300,  0.700,0.470,0.380 },  // Marine Drive
        { 18.9,  0.540,0.330,0.320,  0.900,0.400,0.270,  0.540,0.370,0.350 },
        { 19.4,  0.330,0.230,0.290,  0.420,0.220,0.230,  0.330,0.250,0.300 },  // afterglow
        { 19.9,  0.180,0.145,0.195,  0.210,0.150,0.180,  0.195,0.165,0.215 },
        { 20.6,  0.080,0.075,0.120,  0.090,0.085,0.130,  0.105,0.100,0.145 },
        { 24.0,  0.030,0.038,0.070,  0.040,0.048,0.085,  0.055,0.062,0.095 },
    };

    /** The keyframe hours, for tests that check behaviour at the joins. */
    public static double[] keyHours() {
        double[] h = new double[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) { h[i] = KEYS[i][0]; }
        return h;
    }

    /**
     * @param hour    0 to 24, wrapped
     * @param wetness 0 dry, 1 monsoon downpour
     */
    public void evaluate(double hour, double wetness) {
        double h = hour % 24.0;
        if (h < 0) { h += 24.0; }

        int i = 0;
        while (i < KEYS.length - 2 && KEYS[i + 1][0] <= h) { i++; }
        double[] a = KEYS[i], b = KEYS[i + 1];
        double span = b[0] - a[0];
        double t = span <= 0 ? 0 : (h - a[0]) / span;
        // Smoothstep rather than linear, so the rate of change itself is
        // continuous at a keyframe. With linear blending the sky visibly
        // changes gear at every boundary.
        t = t * t * (3 - 2 * t);

        for (int c = 0; c < 3; c++) {
            skyColour[c] = (float) lerp(a[1 + c], b[1 + c], t);
            sunColour[c] = (float) lerp(a[4 + c], b[4 + c], t);
            fogColour[c] = (float) lerp(a[7 + c], b[7 + c], t);
        }

        // Elevation. Peaks at local noon and is negative at night. Mumbai is
        // close enough to the tropics that the arc is steep; no seasonal
        // declination term, because the game has no calendar.
        double elev = Math.sin(Math.PI * (h - 6.0) / 12.0);
        sunElevation = (float) elev;

        // Azimuth sweeps east to west through the day. The city's west coast
        // means the evening sun comes in over the sea, which is the whole
        // point of the hour before sunset here.
        double az = Math.PI * (h - 6.0) / 12.0;
        double horiz = Math.max(0.12, Math.sqrt(Math.max(0.0, 1.0 - elev * elev)));
        // +X is east. At 06:00 az is 0 and the sun must be due east, so
        // this is cos(az) and not its negation: with the sign flipped the
        // sun rose over the harbour and set behind Thane Creek, which is
        // exactly backwards for a city on the west coast.
        sunDir[0] = (float) (Math.cos(az) * horiz);
        // The real elevation, negative at night. It used to be clamped to
        // +0.045 so the vector never dipped below the horizon, which meant
        // the sun did not set: it climbed back up through the night and at
        // midnight the city was lit from forty-seven degrees overhead by a
        // sun that was in fact fifty-seven degrees under it. Night light
        // comes from the ambient and sky terms, which is where it belongs.
        sunDir[1] = (float) elev;
        sunDir[2] = (float) (Math.sin(az) * horiz * -0.35);
        normalise(sunDir);

        // Night ramps in across the twenty-five minutes of tropical twilight
        // rather than switching. Keyed to elevation, not to the clock, so it
        // stays in step if the day length is ever changed.
        nightFactor = (float) clamp01((0.06 - elev) / 0.16);

        // Haze. Thickest just after dawn when the land is cool and the air
        // off the sea is saturated; the monsoon roughly triples it.
        double dawnHaze = Math.exp(-Math.pow((h - 7.0) / 2.4, 2.0)) * 0.55;
        double base = 2.4e-5 + dawnHaze * 2.2e-5 + wetness * 6.5e-5;
        // Night air is clearer, and lights need to be visible at distance.
        base *= 1.0 - 0.35 * nightFactor;
        fogDensity = (float) base;

        // Rain flattens and desaturates everything.
        if (wetness > 0) {
            double k = wetness * 0.55;
            for (int c = 0; c < 3; c++) {
                double grey = 0.42 * (1.0 - 0.4 * nightFactor);
                skyColour[c] = (float) lerp(skyColour[c], grey, k);
                fogColour[c] = (float) lerp(fogColour[c], grey, k);
                sunColour[c] = (float) (sunColour[c] * (1.0 - 0.62 * wetness));
            }
        }
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static void normalise(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l > 1e-6f) { v[0] /= l; v[1] /= l; v[2] /= l; }
    }
}
