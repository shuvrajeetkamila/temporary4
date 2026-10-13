package ai.shuvrajeetkamila.mumbai.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monsoon.
 *
 * <p>The properties worth pinning are that the weather is a pure function
 * of the clock, that the ground dries far more slowly than it soaks, and
 * that a dry season is actually dry rather than a permanent drizzle.
 */
final class WeatherTest {

    @Test
    @DisplayName("the weather is the same every time you ask for a moment")
    void scheduleIsDeterministic() {
        Weather a = new Weather();
        Weather b = new Weather();
        for (double t = 0; t < 4000; t += 7.3) {
            assertEquals(a.scheduledRain(t), b.scheduledRain(t), 0.0,
                    "two instances disagreed about the weather at t=" + t);
        }
    }

    @Test
    @DisplayName("stepping in small or large steps reaches the same weather")
    void steppingIsFramerateIndependent() {
        Weather fine = new Weather();
        Weather coarse = new Weather();
        for (int i = 0; i < 6000; i++) { fine.update(1.0 / 60); }
        for (int i = 0; i < 100; i++) { coarse.update(1.0); }

        assertEquals(fine.clock(), coarse.clock(), 1e-6);
        assertEquals(fine.rain, coarse.rain, 1e-9,
                "rain depends on how the time was chopped up");
        // Wetness is an exponential approach, so it is not bit-identical
        // across step sizes, but it must not drift anywhere meaningful.
        assertEquals(fine.wetness, coarse.wetness, 0.02,
                "ground wetness drifted with the frame rate");
    }

    @Test
    @DisplayName("rain stays inside its range, whatever the season")
    void rainIsBounded() {
        Weather w = new Weather();
        for (double m = 0; m <= 1.0; m += 0.1) {
            w.monsoon = m;
            for (double t = 0; t < 3000; t += 3.1) {
                double r = w.scheduledRain(t);
                assertTrue(r >= 0 && r <= 1.0,
                        "rain out of range at season " + m + ": " + r);
            }
        }
    }

    @Test
    @DisplayName("a dry season is dry, a monsoon is wet")
    void seasonChangesHowOftenItRains() {
        double dry = wetFraction(0.05);
        double mid = wetFraction(0.5);
        double peak = wetFraction(0.95);

        assertTrue(dry < 0.15, "it rained " + dry + " of a dry season");
        assertTrue(peak > 0.7, "it only rained " + peak + " of the monsoon");
        assertTrue(mid > dry && mid < peak,
                "the season does not interpolate: " + dry + " " + mid + " " + peak);
    }

    private double wetFraction(double monsoon) {
        Weather w = new Weather();
        w.monsoon = monsoon;
        int wet = 0, n = 0;
        for (double t = 0; t < 200_000; t += 11.0) {
            if (w.scheduledRain(t) > 0.02) { wet++; }
            n++;
        }
        return wet / (double) n;
    }

    @Test
    @DisplayName("the road stays wet long after the rain stops")
    void groundDriesSlowly() {
        Weather w = new Weather();
        w.monsoon = 0;          // schedule is dry, so rain is always 0
        w.set(0, 1.0);          // but the ground starts soaked

        // One minute later it should still be distinctly wet, because
        // this is the whole reason wetness and rain are separate numbers.
        for (int i = 0; i < 60 * 60; i++) { w.update(1.0 / 60); }
        assertTrue(w.wetness > 0.6,
                "the road dried out in a minute: " + w.wetness);

        // Ten minutes and it has gone.
        for (int i = 0; i < 600 * 60; i++) { w.update(1.0 / 60); }
        assertTrue(w.wetness < 0.15,
                "the road never dried: " + w.wetness);
    }

    @Test
    @DisplayName("the ground soaks far faster than it dries")
    void soakingBeatsDrying() {
        Weather wet = new Weather();
        wet.monsoon = 0;
        wet.set(0, 0.0);
        // Force a downpour by driving the lag directly for a fixed time.
        int soakSteps = stepsToCross(0.0, 1.0, 0.5, Weather.WET_S);
        int drySteps = stepsToCross(1.0, 0.0, 0.5, Weather.DRY_S);
        assertTrue(drySteps > soakSteps * 4,
                "drying (" + drySteps + " steps) should take far longer than "
                        + "soaking (" + soakSteps + ")");
        assertTrue(Weather.DRY_S > Weather.WET_S * 4,
                "the two time constants are too close to tell apart");
    }

    /** Steps for an exponential approach to cross a threshold. */
    private int stepsToCross(double from, double to, double threshold, double tau) {
        double v = from;
        double dt = 1.0 / 60;
        double k = 1.0 - Math.exp(-dt / tau);
        for (int i = 1; i <= 200_000; i++) {
            v += (to - v) * k;
            boolean crossed = to > from ? v >= threshold : v <= threshold;
            if (crossed) { return i; }
        }
        return Integer.MAX_VALUE;
    }

    @Test
    @DisplayName("wetness never leaves zero to one")
    void wetnessIsBounded() {
        Weather w = new Weather();
        w.monsoon = 1.0;
        for (int i = 0; i < 200_000; i++) {
            w.update(0.05);
            assertTrue(w.wetness >= 0 && w.wetness <= 1.0,
                    "wetness left its range: " + w.wetness);
        }
    }

    @Test
    @DisplayName("a huge frame does not overshoot the target")
    void longFramesDoNotOvershoot() {
        Weather w = new Weather();
        w.monsoon = 0;
        w.set(0, 1.0);
        // A ten minute hitch, far longer than either time constant. A
        // linear rate would shoot straight past zero into negative.
        w.update(600.0);
        assertTrue(w.wetness >= 0 && w.wetness <= 1.0,
                "a long frame pushed wetness to " + w.wetness);
    }

    @Test
    @DisplayName("seeking the clock gives the scheduled weather at once")
    void seekingWorks() {
        Weather w = new Weather();
        w.seek(12_345.0);
        assertEquals(w.scheduledRain(12_345.0), w.rain, 1e-12);
        assertEquals(12_345.0, w.clock(), 1e-9);
    }
}
