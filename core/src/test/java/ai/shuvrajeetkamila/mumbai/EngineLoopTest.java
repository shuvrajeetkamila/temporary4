package ai.shuvrajeetkamila.mumbai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import ai.shuvrajeetkamila.mumbai.game.ControlProfile;
import ai.shuvrajeetkamila.mumbai.game.Engine;
import ai.shuvrajeetkamila.mumbai.game.InputState;
import ai.shuvrajeetkamila.mumbai.game.QualityProfile;
import ai.shuvrajeetkamila.mumbai.game.Telemetry;
import ai.shuvrajeetkamila.mumbai.world.WorldStreamer;

/**
 * The simulation has to behave identically whatever the display is doing, which means
 * a fixed 60 Hz accumulator with a hard substep cap, a clamped frame delta so a stall
 * cannot teleport anything through a wall, and interpolation for rendering only.
 *
 * <p>This class drives the real {@link Engine} with the real packaged world.
 */
public class EngineLoopTest {

    private Engine engine;

    private Engine engine() throws Exception {
        if (engine == null) {
            Telemetry t = new Telemetry();
            engine = new Engine(t, new InputState());
            engine.load(TestWorld.source(), new WorldStreamer.TileSink() {
                @Override
                public void onTileReady(WorldStreamer.Tile tile) {
                }

                @Override
                public void onTileEvicted(WorldStreamer.Tile tile) {
                }
            }, 2);
        }
        return engine;
    }

    @After
    public void tearDown() {
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
    }

    @Test
    public void theWorldLoadsAndTheEngineBecomesReady() throws Exception {
        Engine e = engine();
        assertTrue("the engine never became ready after loading the world", e.ready);
        assertNotNull(e.world);
        assertNotNull(e.collision);
        assertNotNull(e.streamer);
        assertEquals("the player should start on foot", ControlProfile.ON_FOOT, e.mode);

        // The spawn must be on dry land above the tide, not in the sea.
        double x = e.focusX();
        double z = e.focusZ();
        assertTrue("spawned in water", e.world.heightAt(x, z) > 0.5f);
    }

    @Test
    public void physicsRunsAtSixtyHertzRegardlessOfFrameRate() throws Exception {
        Engine e = engine();
        long before = e.physicsStepCount();

        // Ten seconds of wall clock delivered as a wildly uneven 23 fps.
        double delivered = 0;
        while (delivered < 10.0) {
            float dt = 1f / 23f;
            e.update(dt);
            delivered += dt;
        }
        long steps = e.physicsStepCount() - before;
        // 10 s at 60 Hz is 600 steps; allow the accumulator's partial step either way.
        assertTrue("10 s of frames produced " + steps + " physics steps, not ~600",
                Math.abs(steps - 600) <= 3);
    }

    @Test
    public void aLongStallIsClampedInsteadOfTeleportingThePlayer() throws Exception {
        Engine e = engine();
        long before = e.physicsStepCount();

        // A three second hitch: garbage collection, an app switch, a thermal stall.
        e.update(3.0f);

        long steps = e.physicsStepCount() - before;
        assertTrue("a 3 s stall ran " + steps + " physics steps; the frame delta must "
                        + "be clamped and the substeps capped",
                steps <= Engine.MAX_SUBSTEPS);
        assertTrue("the substep counter disagrees with the step counter",
                e.lastSubstepCount() <= Engine.MAX_SUBSTEPS);
    }

    @Test
    public void renderInterpolationStaysBetweenTheLastTwoStates() throws Exception {
        Engine e = engine();
        e.switchMode(ControlProfile.CAR);
        e.input.throttle = 1f;

        double[] prev = new double[3];
        e.renderPosition(prev);
        for (int i = 0; i < 120; i++) {
            // A frame rate that never lines up with the physics step, so alpha moves.
            e.update(1f / 37f);
            double[] now = new double[3];
            e.renderPosition(now);
            float alpha = e.interpolationAlpha();
            assertTrue("interpolation alpha out of range: " + alpha,
                    alpha >= 0f && alpha <= 1f);
            double jump = Math.hypot(now[0] - prev[0], now[2] - prev[2]);
            assertTrue("the render position jumped " + jump + " m in one frame", jump < 12.0);
            prev = now;
        }
        e.input.throttle = 0f;
    }

    @Test
    public void pausingStopsTheSimulationCompletely() throws Exception {
        Engine e = engine();
        e.switchMode(ControlProfile.CAR);
        e.input.throttle = 1f;
        for (int i = 0; i < 60; i++) {
            e.update(1f / 60f);
        }
        e.paused = true;
        long steps = e.physicsStepCount();
        double x = e.activeBody().px;
        for (int i = 0; i < 60; i++) {
            e.update(1f / 60f);
        }
        assertEquals("physics advanced while paused", steps, e.physicsStepCount());
        assertEquals("the world moved while paused", x, e.activeBody().px, 1e-9);
        e.paused = false;
    }

    @Test
    public void switchingVehicleAlwaysLandsSomewhereLegal() throws Exception {
        Engine e = engine();

        e.switchMode(ControlProfile.CAR);
        assertEquals(ControlProfile.CAR, e.mode);
        assertTrue("the car was placed in the sea",
                e.world.heightAt(e.car.body.px, e.car.body.pz) > -0.5f);

        e.switchMode(ControlProfile.BOAT);
        assertEquals(ControlProfile.BOAT, e.mode);
        assertTrue("the boat was placed on dry land",
                e.world.heightAt(e.boat.body.px, e.boat.body.pz) < 1.5f);

        e.switchMode(ControlProfile.HELICOPTER);
        assertEquals(ControlProfile.HELICOPTER, e.mode);

        e.switchMode(ControlProfile.AEROPLANE);
        assertEquals(ControlProfile.AEROPLANE, e.mode);
        assertTrue("the aeroplane was not placed at an airport",
                e.world.heightAt(e.plane.body.px, e.plane.body.pz) > -0.5f);

        e.switchMode(ControlProfile.ON_FOOT);
        assertEquals(ControlProfile.ON_FOOT, e.mode);

        // Every switch is a teleport, so the renderer must not interpolate across it.
        double[] p = new double[3];
        e.renderPosition(p);
        double d = Math.hypot(p[0] - e.human.body.px, p[2] - e.human.body.pz);
        assertTrue("the camera smeared across a teleport: " + d + " m", d < 2.0);
    }

    @Test
    public void nothingEverProducesANonFiniteNumber() throws Exception {
        Engine e = engine();
        ControlProfile[] modes = {
                ControlProfile.ON_FOOT, ControlProfile.CAR, ControlProfile.BOAT,
                ControlProfile.HELICOPTER, ControlProfile.AEROPLANE
        };
        java.util.Random rnd = new java.util.Random(7);
        for (ControlProfile m : modes) {
            e.switchMode(m);
            for (int i = 0; i < 600; i++) {
                // Mash every control at once, which is what players actually do.
                e.input.throttle = rnd.nextFloat();
                e.input.brake = rnd.nextFloat();
                e.input.steer = rnd.nextFloat() * 2f - 1f;
                e.input.pitch = rnd.nextFloat() * 2f - 1f;
                e.input.yaw = rnd.nextFloat() * 2f - 1f;
                e.input.collective = rnd.nextFloat();
                e.update(1f / 60f);

                var b = e.activeBody();
                assertTrue(m + " produced a non-finite position",
                        Double.isFinite(b.px) && Double.isFinite(b.py) && Double.isFinite(b.pz));
                assertTrue(m + " produced a non-finite velocity",
                        Float.isFinite(b.vx) && Float.isFinite(b.vy) && Float.isFinite(b.vz));
                assertFalse(m + " fell out of the world at y=" + b.py, b.py < -500 || b.py > 20000);
            }
            e.input.throttle = 0f;
            e.input.brake = 0f;
            e.input.steer = 0f;
            e.input.pitch = 0f;
            e.input.yaw = 0f;
        }
    }

    @Test
    public void thermalPressureReducesRealWorkNotJustTheFrameCap() {
        QualityProfile base = QualityProfile.BALANCED;
        QualityProfile eco = QualityProfile.applyThermal(base, 2);
        QualityProfile severe = QualityProfile.applyThermal(base, 4);

        assertTrue("moderate thermal pressure did not drop into an eco profile",
                eco.isEco());
        assertTrue("eco did not lower the frame cap", eco.targetFps < base.targetFps);
        assertTrue("severe thermal pressure did not lower the frame cap further",
                severe.targetFps < eco.targetFps);

        // The master requirement: eco must do less work, not merely draw less often.
        assertTrue("eco did not reduce render resolution", eco.renderScale < base.renderScale);
        assertTrue("eco did not shorten the detail rings", eco.highRing < base.highRing);
        assertTrue("eco did not shorten the far ring", eco.farRing < base.farRing);
        assertTrue("eco did not cut the traffic budget", eco.trafficCap < base.trafficCap);
        assertTrue("eco did not cut the pedestrian budget",
                eco.pedestrianCap < base.pedestrianCap);
        assertFalse("eco kept shadows on", eco.shadows);

        assertTrue(severe.renderScale < eco.renderScale);
        assertTrue(severe.farRing < eco.farRing);
        assertTrue(severe.trafficCap < eco.trafficCap);

        assertEquals("the frame cap must be expressed in nanoseconds",
                1_000_000_000L / base.targetFps, base.frameNanos());
    }
}
