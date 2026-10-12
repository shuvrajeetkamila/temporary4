package ai.shuvrajeetkamila.mumbai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import ai.shuvrajeetkamila.mumbai.game.ControlProfile;
import ai.shuvrajeetkamila.mumbai.game.Engine;
import ai.shuvrajeetkamila.mumbai.game.InputState;
import ai.shuvrajeetkamila.mumbai.game.QualityProfile;
import ai.shuvrajeetkamila.mumbai.game.Telemetry;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldStreamer;

/**
 * Drives the whole engine exactly as the Android renderer does — load from the packaged
 * assets, then run frames — and asserts that the result is a world you could actually
 * stand in.
 *
 * <p>The unit suites elsewhere prove individual models in isolation. This one exists to
 * catch the failure that none of them can: an app that compiles, passes every component
 * test, and then shows the user a black screen because nothing was ever streamed, the
 * camera sat inside the terrain, or a NaN leaked into the transform on frame two.
 */
public class StartupSmokeTest {

    /** Reads the same files the APK packages, from the same directory. */
    private static final class DiskAssets implements WorldData.AssetSource {
        private final File root = TestWorld.assetRoot();

        @Override
        public InputStream open(String path) throws IOException {
            File f = new File(root, path);
            if (!f.isFile()) {
                throw new IOException("missing packaged asset: " + path);
            }
            return new FileInputStream(f);
        }
    }

    private static final class CountingSink implements WorldStreamer.TileSink {
        final List<Long> ready = new ArrayList<>();
        int evicted;

        @Override
        public synchronized void onTileReady(WorldStreamer.Tile tile) {
            ready.add(tile.key);
        }

        @Override
        public synchronized void onTileEvicted(WorldStreamer.Tile tile) {
            evicted++;
        }
    }

    private static Engine boot(CountingSink sink) throws Exception {
        uploadedTriangles = 0;
        uploadedWithGeometry = 0;
        Telemetry t = new Telemetry();
        Engine e = new Engine(t, new InputState());
        e.load(new DiskAssets(), sink, 2);
        assertTrue("engine reported not ready after load", e.ready);
        assertFalse("engine reported a fatal error: " + t.fatalMessage, t.fatalError);
        return e;
    }

    /**
     * Pump frames the way the renderer does, with a little jitter, including the
     * bounded "GPU upload" the host is responsible for. There is no GL context here,
     * so a plain marker object stands in for the VAO, which is exactly what the
     * streamer's opaque handle contract allows. Everything else - the ring logic, the
     * state machine, eviction, the budget - is the real code path.
     */
    private static void run(Engine e, int frames) {
        for (int i = 0; i < frames; i++) {
            e.update(1f / 60f + (i % 7) * 0.0008f);
            List<WorldStreamer.Tile> ready = e.streamer.pollReady(UPLOADS_PER_FRAME);
            for (int k = ready.size() - 1; k >= 0; k--) {
                WorldStreamer.Tile t = ready.get(k);
                if (t.geometry != null) {
                    uploadedTriangles += t.geometry.triangles;
                    if (t.geometry.triangles > 0) {
                        uploadedWithGeometry++;
                    }
                }
                e.streamer.markUploaded(t, FAKE_GPU);
            }
        }
    }

    private static final int UPLOADS_PER_FRAME = 3;
    private static final Object FAKE_GPU = new Object();
    private static int uploadedTriangles;
    private static int uploadedWithGeometry;

    @Test
    public void worldLoadsAndStreamsGeometryAroundTheSpawn() throws Exception {
        CountingSink sink = new CountingSink();
        Engine e = boot(sink);
        try {
            run(e, 240);

            assertTrue("no tiles were ever streamed - the world would be empty",
                    sink.ready.size() > 0);
            assertTrue("no tiles reached the active set", e.streamer.activeCount() > 0);

            List<WorldStreamer.Tile> tiles = e.renderTiles(new ArrayList<WorldStreamer.Tile>());
            assertTrue("nothing to render after 4 seconds of streaming", tiles.size() > 0);

            assertTrue("every tile uploaded was empty", uploadedWithGeometry > 0);
            assertTrue("suspiciously little geometry: " + uploadedTriangles + " triangles",
                    uploadedTriangles > 2000);
        } finally {
            e.shutdown();
        }
    }

    @Test
    public void spawnIsOnSolidGroundNotInsideItAndNotUnderwater() throws Exception {
        CountingSink sink = new CountingSink();
        Engine e = boot(sink);
        try {
            run(e, 120);

            double x = e.focusX();
            double y = e.focusY();
            double z = e.focusZ();
            assertTrue("spawn X is not finite", isFinite(x));
            assertTrue("spawn Y is not finite", isFinite(y));
            assertTrue("spawn Z is not finite", isFinite(z));

            float ground = e.world.heightAt(x, z);
            assertTrue("the player spawned below the terrain (y=" + y + " ground=" + ground + ")",
                    y > ground - 0.75);
            assertTrue("the player spawned far above the terrain (y=" + y + " ground=" + ground + ")",
                    y < ground + 60.0);
            assertTrue("the default spawn is underwater (ground " + ground + " m)", ground > 0.3f);

            // Two seconds of standing still must not drift the player anywhere.
            double sx = e.focusX();
            double sz = e.focusZ();
            run(e, 120);
            double drift = Math.hypot(e.focusX() - sx, e.focusZ() - sz);
            assertTrue("the player slid " + drift + " m while no input was applied",
                    drift < 1.0);
        } finally {
            e.shutdown();
        }
    }

    @Test
    public void everyModeRunsAndStaysFinite() throws Exception {
        CountingSink sink = new CountingSink();
        Engine e = boot(sink);
        try {
            for (ControlProfile mode : ControlProfile.values()) {
                e.switchMode(mode);
                // Hold a plausible amount of control input so the models are actually
                // exercised, not just integrated at rest.
                e.input.throttle = 0.6f;
                e.input.collective = 0.65f;
                e.input.steer = 0.12f;
                e.input.moveY = 1f;
                run(e, 180);

                assertEquals("mode did not take effect", mode, e.mode);
                double x = e.focusX();
                double y = e.focusY();
                double z = e.focusZ();
                assertTrue(mode + ": X went non-finite", isFinite(x));
                assertTrue(mode + ": Y went non-finite", isFinite(y));
                assertTrue(mode + ": Z went non-finite", isFinite(z));
                assertTrue(mode + ": left the world in X (" + x + ")", Math.abs(x) < 120000);
                assertTrue(mode + ": left the world in Z (" + z + ")", Math.abs(z) < 160000);
                assertTrue(mode + ": reached an absurd altitude (" + y + " m)",
                        y > -400 && y < 25000);

                assertTrue(mode + ": camera position went non-finite",
                        isFinite(e.camera.px) && isFinite(e.camera.py) && isFinite(e.camera.pz));
                assertTrue(mode + ": camera yaw went non-finite", isFinite(e.camera.yaw));
                assertTrue(mode + ": field of view is nonsense (" + e.camera.fovDeg + ")",
                        e.camera.fovDeg > 20f && e.camera.fovDeg < 130f);

                assertFalse(mode + ": fatal error raised - " + e.telemetry.fatalMessage,
                        e.telemetry.fatalError);
                assertTrue(mode + ": telemetry speed went non-finite",
                        isFinite(e.telemetry.speedKmh));
            }
        } finally {
            e.input.throttle = 0f;
            e.input.collective = 0f;
            e.input.steer = 0f;
            e.input.moveY = 0f;
            e.shutdown();
        }
    }

    @Test
    public void streamingKeepsUpWithAFastDriveAcrossTheCity() throws Exception {
        CountingSink sink = new CountingSink();
        Engine e = boot(sink);
        try {
            // Take off and climb away: a fast, climbing camera is the worst case the
            // streamer will ever see, because the preload ring grows with both speed
            // and altitude.
            e.switchMode(ControlProfile.HELICOPTER);
            e.input.collective = 1f;
            e.input.pitch = -0.35f;        // nose down for translational lift
            run(e, 120);

            double startX = e.focusX();
            double startY = e.focusY();
            double startZ = e.focusZ();
            int before = sink.ready.size();
            int worstPending = 0;
            for (int i = 0; i < 600; i++) {
                run(e, 1);
                worstPending = Math.max(worstPending, e.streamer.pendingCount());
                assertTrue("in-flight tiles exceeded the hard cap: " + e.streamer.pendingCount(),
                        e.streamer.pendingCount() <= 64);
                assertTrue("resident tiles exceeded the memory budget",
                        e.streamer.activeCount() + e.streamer.readyCount() <= 600);
            }

            double travelled = Math.hypot(e.focusX() - startX, e.focusZ() - startZ);
            double climbed = e.focusY() - startY;
            assertTrue("the helicopter neither flew (" + (int) travelled + " m) nor climbed ("
                    + (int) climbed + " m), so this test proved nothing about streaming",
                    travelled > 400 || climbed > 150);
            assertTrue("the streamer produced nothing while crossing " + (int) travelled
                    + " m of city", sink.ready.size() > before);
            assertTrue("nothing is resident after the flight", e.streamer.activeCount() > 0);
            assertFalse("a fatal error appeared during flight", e.telemetry.fatalError);
        } finally {
            e.input.collective = 0f;
            e.input.pitch = 0f;
            e.shutdown();
        }
    }

    @Test
    public void ecoQualityActuallyReducesWork() throws Exception {
        CountingSink sink = new CountingSink();
        Engine e = boot(sink);
        try {
            e.applyQuality(QualityProfile.HIGH);
            run(e, 420);
            assertTrue("no road cells were indexed - traffic can never spawn",
                    e.agents.roadCellCount() > 200);
            int highTiles = e.streamer.activeCount();
            int highCars = e.agents.vehicleCount();
            int highPeds = e.agents.pedestrianCount();

            e.applyQuality(QualityProfile.ECO_SEVERE);
            run(e, 300);
            int ecoTiles = e.streamer.activeCount();
            int ecoCars = e.agents.vehicleCount();
            int ecoPeds = e.agents.pedestrianCount();

            assertTrue("eco did not reduce the streamed tile count ("
                    + highTiles + " -> " + ecoTiles + ")", ecoTiles < highTiles);
            assertTrue("eco did not reduce traffic (" + highCars + " -> " + ecoCars + ")",
                    ecoCars < highCars);
            assertTrue("eco did not reduce pedestrians (" + highPeds + " -> " + ecoPeds + ")",
                    ecoPeds < highPeds);
            assertTrue("eco did not lower the render scale",
                    QualityProfile.ECO_SEVERE.renderScale < QualityProfile.HIGH.renderScale);
            assertTrue("eco did not shorten the view distance",
                    QualityProfile.ECO_SEVERE.farRing < QualityProfile.HIGH.farRing);
        } finally {
            e.shutdown();
        }
    }

    @Test
    public void telemetryIsPopulatedForTheHud() throws Exception {
        CountingSink sink = new CountingSink();
        Engine e = boot(sink);
        try {
            run(e, 180);
            Telemetry t = e.telemetry;
            assertFalse("still reporting 'loading' after four seconds", t.loading);
            assertNotNull("no location name for the HUD", t.locationName);
            assertTrue("location name is blank", t.locationName.trim().length() > 0);
            assertNotNull("no control profile string", t.controlProfile);
            assertTrue("time of day is out of range: " + t.timeOfDayHours,
                    t.timeOfDayHours >= 0f && t.timeOfDayHours < 24f);
            assertTrue("heading is out of range: " + t.headingDeg,
                    t.headingDeg >= -360f && t.headingDeg <= 720f);
            assertTrue("telemetry never saw an active chunk", t.chunksActive > 0);
        } finally {
            e.shutdown();
        }
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
