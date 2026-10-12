package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.app.Quality;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;
import ai.shuvrajeetkamila.mumbai.world.WorldStreamer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimulationTest {

    // ------------------------------------------------------------ fixed step

    @Test
    @DisplayName("sixty hertz in, sixty hertz out, whatever the display does")
    void fixedRate() {
        FixedStep c = new FixedStep();
        int steps = 0;
        for (int i = 0; i < 300; i++) { steps += c.beginFrame(1.0 / 30.0); }
        assertEquals(600, steps, 2, "10 s at 30 fps should be 600 physics steps");
        assertFalse(c.overran(), "30 fps is well within budget");

        FixedStep d = new FixedStep();
        steps = 0;
        // A jittering display: 60, 144 and 24 fps in rotation.
        double[] frames = {1 / 60.0, 1 / 144.0, 1 / 24.0};
        double wall = 0;
        for (int i = 0; i < 900; i++) {
            steps += d.beginFrame(frames[i % 3]);
            wall += frames[i % 3];
        }
        assertEquals(wall * 60, steps, 3,
                "step count must track wall clock, not frame count");
    }

    @Test
    @DisplayName("a long stall is clamped instead of queued")
    void clampsLongFrames() {
        FixedStep c = new FixedStep();
        int n = c.beginFrame(3.5);
        assertTrue(n <= FixedStep.MAX_SUBSTEPS,
                "a 3.5 s stall must not run " + n + " substeps at once");
        // And it must not have banked the rest to spend next frame.
        int next = c.beginFrame(1.0 / 60.0);
        assertTrue(next <= FixedStep.MAX_SUBSTEPS, "the backlog leaked into the next frame");
    }

    @Test
    @DisplayName("alpha stays in range so interpolation never overshoots")
    void alphaInRange() {
        FixedStep c = new FixedStep();
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 5000; i++) {
            c.beginFrame(r.nextDouble() * 0.12);
            assertTrue(c.alpha() >= 0 && c.alpha() < 1.0,
                    "alpha was " + c.alpha());
        }
    }

    @Test
    @DisplayName("garbage in does not corrupt the clock")
    void robustToBadInput() {
        FixedStep c = new FixedStep();
        c.beginFrame(Double.NaN);
        c.beginFrame(-5);
        c.beginFrame(Double.POSITIVE_INFINITY);
        assertTrue(c.alpha() >= 0 && c.alpha() < 1.0, "alpha survived at " + c.alpha());
        assertTrue(c.totalSteps() >= 0);
    }

    // -------------------------------------------------------- floating origin

    @Test
    @DisplayName("float precision 45 km out is bad enough to need rebasing")
    void precisionJustifiesTheMachinery() {
        double far = FloatingOrigin.floatResolutionAt(45000);
        assertTrue(far > 0.002,
                "if floats resolved better than 2 mm at 45 km this would be "
                        + "unnecessary, but the gap is " + far * 1000 + " mm");
        double near = FloatingOrigin.floatResolutionAt(1024);
        assertTrue(near < 0.0002,
                "near the origin the gap should be under 0.2 mm, was "
                        + near * 1000 + " mm");
    }

    @Test
    @DisplayName("rebasing keeps render coordinates small all the way across")
    void rebaseKeepsPrecision() {
        FloatingOrigin o = new FloatingOrigin();
        double worst = 0;
        double worstRes = 0;
        // Drive from the far south-west of the world to the far north-east.
        for (double t = 0; t <= 1.0; t += 0.0004) {
            double x = -26000 + t * 52000;
            double z = -36000 + t * 72000;
            o.update(x, z);
            double rx = Math.abs(o.renderX(x));
            double rz = Math.abs(o.renderZ(z));
            worst = Math.max(worst, Math.max(rx, rz));
            worstRes = Math.max(worstRes,
                    FloatingOrigin.floatResolutionAt(Math.max(rx, rz)));
        }
        assertTrue(worst < 2200,
                "render coordinates reached " + worst + " m from the origin");
        assertTrue(worstRes < 0.0005,
                "worst float resolution was " + worstRes * 1000
                        + " mm, which would be visible");
        assertTrue(o.rebaseCount() > 30,
                "crossing the world should rebase many times, got " + o.rebaseCount());
    }

    @Test
    @DisplayName("a rebase reports exactly how far everything must shift")
    void rebaseDeltaIsExact() {
        FloatingOrigin o = new FloatingOrigin();
        double[] d = new double[2];
        o.update(0, 0);
        o.consumeRebase(d);
        double cachedWorldX = 7777.0;
        float cachedRender = o.renderX(cachedWorldX);
        o.update(9000, 4000);
        assertTrue(o.hasRebase(), "crossing 1 km should trigger a rebase");
        o.consumeRebase(d);
        // Applying the delta to the cached value must land on the new value.
        double shifted = cachedRender - d[0];
        assertEquals(o.renderX(cachedWorldX), shifted, 1e-6,
                "the reported delta does not match the new offset");
        assertFalse(o.hasRebase(), "consuming should clear the flag");
    }

    @Test
    @DisplayName("the world position survives a round trip through render space")
    void roundTrip() {
        FloatingOrigin o = new FloatingOrigin();
        java.util.Random r = new java.util.Random(11);
        for (int i = 0; i < 4000; i++) {
            double x = (r.nextDouble() - 0.5) * 52000;
            double z = (r.nextDouble() - 0.5) * 72000;
            o.update(x, z);
            assertEquals(x, o.worldX(o.renderX(x)), 0.004, "x round trip at " + x);
            assertEquals(z, o.worldZ(o.renderZ(z)), 0.004, "z round trip at " + z);
        }
    }

    @Test
    @DisplayName("standing still does not churn rebases")
    void noRebaseThrash() {
        FloatingOrigin o = new FloatingOrigin();
        o.update(5000, 5000);
        int after = o.rebaseCount();
        // Wobble around just inside the threshold.
        for (int i = 0; i < 2000; i++) {
            double a = i * 0.1;
            o.update(5000 + Math.sin(a) * 900, 5000 + Math.cos(a) * 900);
        }
        assertEquals(after, o.rebaseCount(),
                "moving inside the threshold should not rebase");
    }

    // --------------------------------------------------------------- quality

    @Test
    @DisplayName("eco mode cuts actual work, not just the frame cap")
    void ecoReducesWork() {
        Quality base = Quality.BALANCED;
        for (Quality eco : new Quality[]{Quality.ECO, Quality.ECO_SEVERE}) {
            assertTrue(eco.targetFps < base.targetFps,
                    eco + " should cap frames lower");
            // The frame cap alone would be dishonest: the GPU would finish
            // each frame sooner and idle at the same power per frame. Every
            // one of these has to shrink for the heat to actually drop.
            assertTrue(eco.renderScale < base.renderScale,
                    eco + " must render fewer pixels: " + eco.renderScale);
            assertTrue(eco.highRadius < base.highRadius,
                    eco + " must shorten the high-detail ring");
            assertTrue(eco.medRadius < base.medRadius,
                    eco + " must shorten the medium ring");
            assertTrue(eco.farRadius < base.farRadius,
                    eco + " must shorten the far ring");
            assertTrue(eco.maxCars < base.maxCars,
                    eco + " must simulate fewer vehicles");
            assertTrue(eco.maxPedestrians < base.maxPedestrians,
                    eco + " must simulate fewer pedestrians");
            assertTrue(eco.seaGrid <= base.seaGrid,
                    eco + " must not tessellate the sea more finely");
            assertTrue(eco.uploadsPerFrame <= base.uploadsPerFrame,
                    eco + " must not upload more per frame");
            assertTrue(eco.isEco());
        }
        assertTrue(Quality.ECO_SEVERE.renderScale < Quality.ECO.renderScale,
                "severe must be stricter than ordinary eco");
    }

    @Test
    @DisplayName("thermal status picks the right tier")
    void thermalMapping() {
        assertEquals(Quality.HIGH, Quality.applyThermal(Quality.HIGH, 0));
        assertEquals(Quality.HIGH, Quality.applyThermal(Quality.HIGH, 1));
        assertEquals(Quality.ECO, Quality.applyThermal(Quality.HIGH, 2));
        assertEquals(Quality.ECO, Quality.applyThermal(Quality.HIGH, 3));
        assertEquals(Quality.ECO_SEVERE, Quality.applyThermal(Quality.HIGH, 4));
        assertEquals(Quality.ECO_SEVERE, Quality.applyThermal(Quality.HIGH, 6));
    }

    // ---------------------------------------------------------------- engine

    @Test
    @DisplayName("the engine starts, streams and keeps running in every vehicle")
    void engineRuns() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(18.9430, 72.8230, ControlProfile.ON_FOOT);   // Marine Drive
        InputState in = new InputState();
        in.moveY = -1;
        in.run = true;
        for (int i = 0; i < 600; i++) {
            e.frame(1.0 / 30.0, in);
            drainStreamer(e);          // stand in for the GL thread
        }
        assertTrue(e.human.position.isFinite(), "the human went non-finite");
        assertTrue(e.clock.totalSteps() > 1100, "the clock did not advance");
        assertTrue(e.streamer.residentCount() > 0,
                "nothing streamed in around Marine Drive");

        for (ControlProfile p : ControlProfile.values()) {
            e.switchProfile(p);
            in.reset();
            in.throttle = 0.8;
            in.collective = 0.75;
            in.moveY = -1;
            for (int i = 0; i < 400; i++) { e.frame(1.0 / 30.0, in); }
            assertTrue(e.activeBody().position.isFinite(),
                    p + " produced a non-finite position");
            assertTrue(e.activeBody().velocity.length() < 400,
                    p + " reached " + e.activeBody().velocity.length() + " m/s");
            assertTrue(w.inBounds(e.activeBody().position.x, e.activeBody().position.z)
                            || e.activeBody().position.y > -200,
                    p + " fell out of the world");
        }
    }

    @Test
    @DisplayName("telemetry reports a real place, not a blank screen")
    void telemetryIsPopulated() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(19.0760, 72.8777, ControlProfile.ON_FOOT);
        InputState in = new InputState();
        for (int i = 0; i < 120; i++) { e.frame(1.0 / 30.0, in); }
        Telemetry t = e.telemetry;
        assertEquals(19.0760, t.lat, 0.01, "latitude readout is wrong");
        assertEquals(72.8777, t.lon, 0.01, "longitude readout is wrong");
        assertTrue(t.simSteps > 100, "the step counter is not moving");
        assertNotNull(t.quality);
        assertTrue(t.tilesResident >= 0);
    }

    @Test
    @DisplayName("driving across the city streams in and evicts behind")
    void streamingUnderMotion() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.setBaseQuality(Quality.BALANCED);
        e.spawnAt(19.2307, 72.8567, ControlProfile.CAR);     // Borivali
        InputState in = new InputState();
        in.throttle = 1.0;
        // Teleport south in stages, which is the harshest streaming case.
        double[] lats = {19.2307, 19.1663, 19.1197, 19.0544, 18.9894, 18.9067};
        int maxResident = 0;
        for (double lat : lats) {
            e.spawnAt(lat, 72.84, ControlProfile.CAR);
            for (int i = 0; i < 90; i++) {
                e.frame(1.0 / 30.0, in);
                drainStreamer(e);
            }
            maxResident = Math.max(maxResident, e.streamer.residentCount());
            assertTrue(e.streamer.residentCount() <= e.streamer.maxResident(),
                    "resident set overflowed at " + lat);
        }
        assertTrue(maxResident > 4, "only " + maxResident + " tiles ever resident");
        assertTrue(e.streamer.decodeFailures() == 0,
                e.streamer.decodeFailures() + " chunks failed to decode");
    }

    /** Stand in for the GL thread: decode and 'upload' whatever is pending. */
    private void drainStreamer(Engine e) {
        ai.shuvrajeetkamila.mumbai.world.ChunkMesher m =
                new ai.shuvrajeetkamila.mumbai.world.ChunkMesher(e.world);
        for (int i = 0; i < 8; i++) {
            WorldStreamer.Tile t = e.streamer.nextForDecode();
            if (t == null) { break; }
            e.streamer.decode(t, m);
        }
        for (WorldStreamer.Tile t : e.streamer.pollReady(4)) {
            e.streamer.markUploaded(t, new Object());
        }
        for (WorldStreamer.Tile t : e.streamer.pollEvictions(8)) {
            e.streamer.markReleased(t);
        }
    }

    @Test
    @DisplayName("the streamer walks the full state machine and cancels cleanly")
    void streamerStates() {
        WorldData w = WorldFixture.world();
        WorldStreamer s = new WorldStreamer(w);
        s.setRadii(500, 2000, 9000);
        s.update(0, 0, 0, 0, 2);
        assertTrue(s.pendingIo() > 0, "nothing was queued for IO");
        ai.shuvrajeetkamila.mumbai.world.ChunkMesher m =
                new ai.shuvrajeetkamila.mumbai.world.ChunkMesher(w);
        WorldStreamer.Tile t = s.nextForDecode();
        assertNotNull(t);
        assertEquals(WorldStreamer.S_DECODING, t.state, "should be decoding");
        s.decode(t, m);
        assertEquals(WorldStreamer.S_READY_CPU, t.state, "should be ready on the CPU");
        java.util.List<WorldStreamer.Tile> ready = s.pollReady(1);
        assertEquals(1, ready.size());
        assertEquals(WorldStreamer.S_QUEUED_GPU, ready.get(0).state);
        s.markUploaded(ready.get(0), new Object());
        assertTrue(ready.get(0).isActive(), "should be active after upload");

        // Now run away from it and make sure it is evicted rather than leaked.
        for (int f = 0; f < WorldStreamer.EVICT_GRACE_FRAMES + 10; f++) {
            s.update(40000, 40000, 0, 0, 2);
        }
        int released = 0;
        for (WorldStreamer.Tile e : s.pollEvictions(64)) {
            s.markReleased(e);
            released++;
        }
        assertTrue(released > 0 || s.residentCount() < 4,
                "tiles far behind the player were never evicted");
    }
}
