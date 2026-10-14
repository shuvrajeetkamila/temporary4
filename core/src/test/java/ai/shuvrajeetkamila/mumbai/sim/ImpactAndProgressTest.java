package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.audio.AudioMixer;
import ai.shuvrajeetkamila.mumbai.game.Progress;
import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.render.Sky;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Running into traffic, keeping your money, the sun, and the mixer. */
final class ImpactAndProgressTest {

    // ---------------------------------------------------------------- traffic

    @Test
    @DisplayName("hitting a car moves it and slows you")
    void trafficIsSolid() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(18.9430, 72.8230, ControlProfile.CAR);
        e.car.configure(VehicleKind.SEDAN);
        InputState in = new InputState();
        for (int i = 0; i < 120; i++) { e.frame(1.0 / 60, in); }

        Agents.Car target = null;
        double best = Double.MAX_VALUE;
        for (Agents.Car c : e.agents.cars()) {
            if (!c.active) { continue; }
            double d = Math.hypot(c.x - e.car.position.x, c.z - e.car.position.z);
            if (d < best) { best = d; target = c; }
        }
        assertNotNull(target, "no traffic to hit");

        double h = Math.atan2(target.x - e.car.position.x,
                -(target.z - e.car.position.z));
        double sx = target.x - Math.sin(h) * 34;
        double sz = target.z + Math.cos(h) * 34;
        e.car.placeAt(sx, w.height(sx, sz) + 0.6, sz, h);
        double tx = target.x;
        double tz = target.z;

        in.throttle = 1.0;
        double closest = Double.MAX_VALUE;
        boolean struck = false;
        for (int i = 0; i < 60 * 10; i++) {
            e.frame(1.0 / 60, in);
            closest = Math.min(closest, Math.hypot(e.car.position.x - target.x,
                    e.car.position.z - target.z));
            if (e.carHitSpeed > 0) { struck = true; }
        }
        assertTrue(struck, "closed to " + closest + " m and never registered a hit");
        assertTrue(Math.hypot(target.x - tx, target.z - tz) > 1.5,
                "the car that was hit did not move");
    }

    @Test
    @DisplayName("two vehicles do not end up inside each other")
    void nothingInterpenetrates() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(18.9430, 72.8230, ControlProfile.CAR);
        e.car.configure(VehicleKind.SEDAN);
        InputState in = new InputState();
        in.throttle = 1.0;
        double worst = Double.MAX_VALUE;
        for (int i = 0; i < 60 * 40; i++) {
            e.frame(1.0 / 60, in);
            for (Agents.Car c : e.agents.cars()) {
                if (!c.active) { continue; }
                double d = Math.hypot(c.x - e.car.position.x,
                        c.z - e.car.position.z);
                worst = Math.min(worst, d);
            }
        }
        assertTrue(worst > 0.8,
                "a car and the player were " + worst + " m apart, centre to centre");
    }

    @Test
    @DisplayName("a shoved car finds its way back onto the road")
    void shovedCarsRecover() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(18.9430, 72.8230, ControlProfile.ON_FOOT);
        InputState in = new InputState();
        for (int i = 0; i < 120; i++) { e.frame(1.0 / 60, in); }
        Agents.Car c = null;
        for (Agents.Car x : e.agents.cars()) { if (x.active) { c = x; break; } }
        assertNotNull(c);
        e.agents.shove(c, 9, 4, 1.1);
        assertTrue(c.shoveTimer > 0);
        for (int i = 0; i < 60 * 8; i++) { e.frame(1.0 / 60, in); }
        if (c.active) {
            assertTrue(c.shoveTimer <= 0, "still free-rolling after eight seconds");
            assertTrue(Double.isFinite(c.x) && Double.isFinite(c.z));
        }
    }

    // --------------------------------------------------------------- progress

    @Test
    @DisplayName("money survives a restart")
    void progressRoundTrips() {
        Progress.Memory store = new Progress.Memory();
        WorldData w = WorldFixture.world();

        Engine first = new Engine(w);
        first.loadProgress(store);
        first.spawnAt(18.9430, 72.8230, ControlProfile.CAR);
        first.missions.money = 1234;
        first.missions.faresDone = 3;
        first.missions.racesWon = 1;
        first.wanted.timesBusted = 2;
        first.timeOfDay = 17.25;
        first.saveProgress();

        Engine second = new Engine(w);
        Progress p = second.loadProgress(store);
        assertEquals(1234, p.money);
        assertEquals(3, p.faresDone);
        assertEquals(1, p.racesWon);
        assertEquals(2, p.timesBusted);
        assertEquals(17.25, p.hour, 1e-6);
        assertEquals(1234, second.missions.money, "money was not handed to Missions");
        assertEquals(17.25, second.timeOfDay, 1e-6);
        assertTrue(Math.abs(p.x - first.car.position.x) < 1.0,
                "the position was not saved");
    }

    @Test
    @DisplayName("a corrupt save is a new game, not a crash")
    void corruptSavesAreSurvivable() {
        for (String junk : new String[]{"", "money", "money=", "money=zzz",
                "\u0000\u0001", "money=12\nhour=99\nx=notanumber"}) {
            Progress p = Progress.parse(junk);
            assertTrue(p.money >= 0, "negative money from \"" + junk + "\"");
            assertTrue(p.hour >= 0 && p.hour < 24,
                    "hour " + p.hour + " from \"" + junk + "\"");
        }
        assertEquals(12, Progress.parse("money=12\nhour=99\nx=notanumber").money,
                "a bad line cost the good ones");
    }

    @Test
    @DisplayName("nothing is saved until there is somewhere to save it")
    void aFreshEngineStartsEmpty() {
        Engine e = new Engine(WorldFixture.world());
        assertEquals(0, e.progress.money);
        assertEquals(0, e.missions.money);
        // And saving without a store must not throw.
        e.spawnAt(18.9430, 72.8230, ControlProfile.ON_FOOT);
        e.saveProgress();
    }

    // -------------------------------------------------------------------- sky

    @Test
    @DisplayName("the sun goes below the horizon at night")
    void theSunSets() {
        Sky s = new Sky();
        for (double h = 0; h < 24; h += 0.25) {
            s.evaluate(h, 0.0);
            if (s.sunElevation < -0.02) {
                assertTrue(s.sunDir[1] < 0,
                        "at " + h + "h the sun is at elevation " + s.sunElevation
                                + " but the light comes from " + s.sunDir[1]);
            }
            if (s.sunElevation > 0.02) {
                assertTrue(s.sunDir[1] > 0, "daylight from below at " + h + "h");
            }
        }
    }

    @Test
    @DisplayName("the sun is highest at noon and lowest at midnight")
    void theSunArcsTheRightWay() {
        Sky s = new Sky();
        s.evaluate(12.0, 0);
        double noon = s.sunDir[1];
        s.evaluate(0.0, 0);
        double midnight = s.sunDir[1];
        assertTrue(noon > 0.9, "noon sun at " + noon);
        assertTrue(midnight < -0.5, "midnight sun at " + midnight);
    }

    @Test
    @DisplayName("the sun direction stays a unit vector all day")
    void theSunVectorIsNormalised() {
        Sky s = new Sky();
        for (double h = 0; h < 24; h += 0.5) {
            s.evaluate(h, 0.0);
            double len = Math.sqrt(s.sunDir[0] * s.sunDir[0]
                    + s.sunDir[1] * s.sunDir[1] + s.sunDir[2] * s.sunDir[2]);
            assertEquals(1.0, len, 1e-4, "at " + h + "h");
        }
    }

    // ------------------------------------------------------------------ audio

    @Test
    @DisplayName("the mixer cannot be made to write past its buffer")
    void theMixerIsBounded() {
        AudioMixer m = new AudioMixer();
        short[] buf = new short[1024];
        // The obvious misreading of render(short[], int): the array length
        // as a frame count. This used to throw out of the audio thread.
        m.render(buf, buf.length);
        m.render(buf, 10_000);
        m.render(buf, 0);
        m.render(buf, -5);
        m.render(new short[0], 8);
        // And the correct call still fills the whole buffer.
        m.render(buf, buf.length / 2);
    }
}
