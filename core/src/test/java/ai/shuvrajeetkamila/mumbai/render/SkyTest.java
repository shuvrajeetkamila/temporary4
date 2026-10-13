package ai.shuvrajeetkamila.mumbai.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The day cycle has to be continuous, and night has to actually be night. */
final class SkyTest {

    @Test
    @DisplayName("the sun rises and sets at roughly the right hours")
    void sunFollowsTheDay() {
        Sky s = new Sky();
        s.evaluate(12.0, 0);
        assertTrue(s.sunElevation > 0.95, "noon sun should be overhead");
        assertTrue(s.sunDir[1] > 0.9, "noon sun direction should point up");

        s.evaluate(0.0, 0);
        assertTrue(s.sunElevation < -0.9, "midnight sun should be well down");

        s.evaluate(6.0, 0);
        assertTrue(Math.abs(s.sunElevation) < 0.05, "6am should be about sunrise");
        s.evaluate(18.0, 0);
        assertTrue(Math.abs(s.sunElevation) < 0.05, "6pm should be about sunset");
    }

    /**
     * The property that distinguishes this from linear keyframing.
     *
     * <p>With linear interpolation the colour is continuous but its
     * <i>velocity</i> is not: at every keyframe the palette instantly
     * changes speed, and the eye reads that as the sky changing gear. The
     * fix is smoothstep, whose derivative is zero at both ends of every
     * segment, so the velocity matches across each join.
     *
     * <p>So the measurement is: how fast is the palette moving right at a
     * keyframe, against how fast it moves in the middle of a segment. Under
     * smoothstep the join is the <i>slowest</i> point. Under linear
     * interpolation it would be the fastest, and this test would fail.
     *
     * <p>Deliberately not a second-difference test. That measures ordinary
     * curvature, which smoothstep has plenty of by design, and it reports a
     * perfectly smooth dusk as a defect - which is exactly what the first
     * version of this test did.
     */
    @Test
    @DisplayName("the palette does not change gear at a keyframe")
    void velocityIsContinuousAcrossKeyframes() {
        Sky a = new Sky();
        Sky b = new Sky();
        double step = 1.0 / 600.0;
        double[] keys = Sky.keyHours();

        for (int i = 1; i < keys.length - 1; i++) {
            double k = keys[i];
            double atJoin = speed(a, b, k, step);
            // Midpoints of the segments either side.
            double before = speed(a, b, (keys[i - 1] + k) * 0.5, step);
            double after = speed(a, b, (k + keys[i + 1]) * 0.5, step);
            double neighbourhood = Math.max(before, after);
            assertTrue(atJoin <= neighbourhood * 0.55 + 1e-6,
                    "at keyframe " + k + " the palette moves at " + atJoin
                            + ", against " + neighbourhood + " mid-segment:"
                            + " the join is not smooth");
        }
    }

    /** Total rate of change of the whole palette, per hour. */
    private static double speed(Sky a, Sky b, double h, double step) {
        a.evaluate(h - step * 0.5, 0);
        b.evaluate(h + step * 0.5, 0);
        double d = 0;
        for (int i = 0; i < 3; i++) {
            d += Math.abs(a.skyColour[i] - b.skyColour[i]);
            d += Math.abs(a.sunColour[i] - b.sunColour[i]);
            d += Math.abs(a.fogColour[i] - b.fogColour[i]);
        }
        return d / step;
    }

    /** No keyframe may be mistyped into something that reads as a cut. */
    @Test
    @DisplayName("the palette never moves fast enough to look like a cut")
    void paletteNeverJumps() {
        Sky a = new Sky();
        Sky b = new Sky();
        double step = 1.0 / 60.0;          // one game-minute, one real second
        double worst = 0, worstAt = -1;
        for (double h = 0; h < 24.0; h += step) {
            a.evaluate(h, 0);
            b.evaluate(h + step, 0);
            double d = 0;
            for (int i = 0; i < 3; i++) {
                d += Math.abs(a.skyColour[i] - b.skyColour[i]);
                d += Math.abs(a.sunColour[i] - b.sunColour[i]);
                d += Math.abs(a.fogColour[i] - b.fogColour[i]);
            }
            if (d > worst) { worst = d; worstAt = h; }
        }
        assertTrue(worst < 0.085,
                "palette moves " + worst + " in one second near hour " + worstAt);
    }

    @Test
    @DisplayName("midnight matches midnight coming the other way")
    void wrapsCleanlyAtMidnight() {
        Sky a = new Sky();
        Sky b = new Sky();
        a.evaluate(23.999, 0);
        b.evaluate(0.001, 0);
        for (int c = 0; c < 3; c++) {
            assertTrue(Math.abs(a.skyColour[c] - b.skyColour[c]) < 0.01,
                    "sky discontinuous across midnight");
            assertTrue(Math.abs(a.fogColour[c] - b.fogColour[c]) < 0.01,
                    "fog discontinuous across midnight");
        }
    }

    @Test
    @DisplayName("hours outside 0-24 wrap instead of clamping")
    void handlesWrappedHours() {
        Sky a = new Sky();
        Sky b = new Sky();
        a.evaluate(14.0, 0);
        b.evaluate(38.0, 0);
        for (int c = 0; c < 3; c++) {
            assertTrue(Math.abs(a.skyColour[c] - b.skyColour[c]) < 1e-6);
        }
        b.evaluate(-10.0, 0);
        for (int c = 0; c < 3; c++) {
            assertTrue(Math.abs(a.skyColour[c] - b.skyColour[c]) < 1e-6);
        }
    }

    @Test
    @DisplayName("night is dark and window lights come on")
    void nightIsActuallyDark() {
        Sky s = new Sky();
        s.evaluate(13.0, 0);
        double dayLum = s.skyColour[0] + s.skyColour[1] + s.skyColour[2];
        assertTrue(s.nightFactor < 0.01, "daytime must not trigger window lights");

        s.evaluate(1.0, 0);
        double nightLum = s.skyColour[0] + s.skyColour[1] + s.skyColour[2];
        assertTrue(s.nightFactor > 0.95, "1am must be fully night");
        assertTrue(nightLum < dayLum * 0.25,
                "night sky " + nightLum + " is not dark against day " + dayLum);
    }

    /**
     * The reason the city faces the sunset. If the evening light stops
     * coming from the west, the single most recognisable thing about this
     * place at six in the evening is gone.
     */
    @Test
    @DisplayName("the evening sun is out over the sea, to the west")
    void eveningSunIsInTheWest() {
        Sky s = new Sky();
        s.evaluate(17.8, 0);
        // World frame: +X is east, so the sun must be at negative X.
        assertTrue(s.sunDir[0] < -0.35,
                "evening sun x = " + s.sunDir[0] + ", should be well to the west");
        assertTrue(s.sunDir[1] > 0.02 && s.sunDir[1] < 0.45,
                "evening sun should be low, not overhead: y = " + s.sunDir[1]);

        s.evaluate(7.5, 0);
        assertTrue(s.sunDir[0] > 0.35,
                "morning sun x = " + s.sunDir[0] + ", should be in the east");
    }

    @Test
    @DisplayName("the monsoon greys the light and thickens the air")
    void rainFlattensEverything() {
        Sky dry = new Sky();
        Sky wet = new Sky();
        dry.evaluate(14.0, 0.0);
        wet.evaluate(14.0, 1.0);

        assertTrue(wet.fogDensity > dry.fogDensity * 2.0,
                "rain should cut visibility hard");
        double drySun = dry.sunColour[0] + dry.sunColour[1] + dry.sunColour[2];
        double wetSun = wet.sunColour[0] + wet.sunColour[1] + wet.sunColour[2];
        assertTrue(wetSun < drySun * 0.5, "rain should kill the direct sun");

        // Desaturated: the spread between channels should collapse.
        double drySpread = max3(dry.skyColour) - min3(dry.skyColour);
        double wetSpread = max3(wet.skyColour) - min3(wet.skyColour);
        assertTrue(wetSpread < drySpread,
                "rain should desaturate the sky, not tint it");
    }

    @Test
    @DisplayName("fog is always dense enough to hide the far plane")
    void fogIsNeverZero() {
        Sky s = new Sky();
        for (double h = 0; h < 24; h += 0.25) {
            s.evaluate(h, 0);
            assertTrue(s.fogDensity > 1e-5,
                    "fog density " + s.fogDensity + " at hour " + h
                            + " would leave chunk pop-in visible");
            // And it must survive being held in a mediump uniform, which
            // flushes anything below roughly 6e-5... which is why the shader
            // declares it highp. Guard the magnitude anyway.
            assertTrue(s.fogDensity < 1e-2, "fog density absurdly high at " + h);
        }
    }

    private static double max3(float[] v) {
        return Math.max(v[0], Math.max(v[1], v[2]));
    }

    private static double min3(float[] v) {
        return Math.min(v[0], Math.min(v[1], v[2]));
    }
}
