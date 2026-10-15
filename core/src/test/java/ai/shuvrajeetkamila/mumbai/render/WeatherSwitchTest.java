package ai.shuvrajeetkamila.mumbai.render;

import ai.shuvrajeetkamila.mumbai.game.Progress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monsoon has an off switch, and it is remembered.
 *
 * <p>Asked for directly: the rain overlay was drawing something that
 * looked like woven matting over the whole screen, and the reasonable
 * response to that was "get rid of it". The overlay is fixed, but a
 * full-screen weather effect is a matter of taste and the taste should
 * be the player's.
 */
final class WeatherSwitchTest {

    /** A clock position that is definitely in a squall. */
    private static double wetMoment() {
        Weather w = new Weather();
        for (double t = 0; t < 200000; t += 7) {
            if (w.scheduledRain(t) > 0.5) { return t; }
        }
        throw new IllegalStateException("the season never rains");
    }

    @Test
    @DisplayName("switched off, it does not rain even mid-squall")
    void offMeansOff() {
        double t = wetMoment();
        Weather on = new Weather();
        on.seek(t);
        assertTrue(on.rain > 0.5, "the fixture is not a squall");

        Weather off = new Weather();
        off.enabled = false;
        off.seek(t);
        assertEquals(0.0, off.rain, 1e-12,
                "it is still raining with the weather switched off");
        off.update(1.0 / 60.0);
        assertEquals(0.0, off.rain, 1e-12,
                "a step of the simulation brought the rain back");
        assertTrue(!off.raining(), "raining() disagrees with the switch");
    }

    @Test
    @DisplayName("the roads dry out rather than snapping dry")
    void wetnessIsLeftToItsOwnTimer() {
        Weather w = new Weather();
        w.seek(wetMoment());
        for (int i = 0; i < 60 * 120; i++) { w.update(1.0 / 60.0); }
        double soaked = w.wetness;
        assertTrue(soaked > 0.3, "the fixture never soaked the ground");

        w.enabled = false;
        w.update(1.0 / 60.0);
        assertTrue(w.wetness > soaked * 0.98,
                "the ground went from soaked to dry in one frame, which"
                        + " would snap the grip and the road sheen");
        // Ten minutes is two of the 300 s drying constants, so about an
        // eighth of the soaking should be left. Asserted against the
        // constant rather than against a number somebody liked.
        for (int i = 0; i < 60 * 600; i++) { w.update(1.0 / 60.0); }
        double expected = soaked * Math.exp(-600.0 / Weather.DRY_S);
        assertTrue(w.wetness < expected * 1.3 && w.wetness > expected * 0.7,
                "ten minutes after the weather went off the ground is at "
                        + w.wetness + ", not the " + expected
                        + " its own drying time implies");
        for (int i = 0; i < 60 * 2400; i++) { w.update(1.0 / 60.0); }
        assertTrue(w.wetness < 0.01,
                "the roads never dry out at all: " + w.wetness);
    }

    @Test
    @DisplayName("the clock keeps running, so turning it back on resumes")
    void theScheduleIsNotRewound() {
        Weather a = new Weather();
        Weather b = new Weather();
        b.enabled = false;
        for (int i = 0; i < 60 * 600; i++) {
            a.update(1.0 / 60.0);
            b.update(1.0 / 60.0);
        }
        assertEquals(a.clock(), b.clock(), 1e-9,
                "the weather clock stopped while it was switched off, so"
                        + " turning it back on would start a fresh squall"
                        + " rather than rejoining the season");
        b.enabled = true;
        b.update(1.0 / 60.0);
        a.update(1.0 / 60.0);
        assertEquals(a.rain, b.rain, 1e-9,
                "switching it back on did not rejoin the schedule");
    }

    @Test
    @DisplayName("the setting survives being closed")
    void itIsSaved() {
        Progress p = new Progress();
        assertTrue(p.weather, "a new game should start with weather on");
        p.weather = false;
        Progress back = Progress.parse(p.toString());
        assertTrue(!back.weather,
                "the monsoon switch was not written to the save, so it"
                        + " comes back on every launch");

        p.weather = true;
        assertTrue(Progress.parse(p.toString()).weather,
                "switching it back on was not saved either");

        // A save written before the setting existed must not turn the
        // weather off for everybody who had one.
        assertTrue(Progress.parse("version=1\nmoney=40\nhour=9.0\n").weather,
                "an older save is read as weather-off");
    }
}
