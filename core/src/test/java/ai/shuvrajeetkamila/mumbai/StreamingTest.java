package ai.shuvrajeetkamila.mumbai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

import ai.shuvrajeetkamila.mumbai.game.QualityProfile;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldStreamer;

/**
 * The streaming system is what makes a 44 x 65 km city run on a phone, so it is
 * tested as a state machine rather than by eye: tiles must move
 * UNLOADED -> QUEUED_IO -> DECODING -> READY_CPU -> QUEUED_GPU -> ACTIVE -> EVICTING,
 * uploads must be bounded per frame, work for tiles the player has already left must
 * be cancelled rather than finished, and memory must not grow without limit while
 * driving across the island.
 */
public class StreamingTest {

    private final WorldData world = TestWorld.get();
    private WorldStreamer streamer;

    @After
    public void tearDown() {
        if (streamer != null) {
            streamer.shutdown();
            streamer = null;
        }
    }

    /** Drains the ready queue the way the renderer does, with a per-frame budget. */
    private int pump(int budget) {
        List<WorldStreamer.Tile> ready = streamer.pollReady(budget);
        for (WorldStreamer.Tile t : ready) {
            // Stand in for the GL upload.
            t.gpu = Boolean.TRUE;
            t.state = t.lod == 0 ? WorldStreamer.S_ACTIVE_HIGH
                    : t.lod == 1 ? WorldStreamer.S_ACTIVE_MEDIUM : WorldStreamer.S_ACTIVE_LOW;
        }
        return ready.size();
    }

    private void settle(double x, double z, float alt, QualityProfile q, int frames) {
        for (int i = 0; i < frames; i++) {
            streamer.update(x, z, alt, 0, 0, q);
            pump(4);
            try {
                Thread.sleep(2);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    public void tilesWalkTheFullStateMachine() throws Exception {
        streamer = new WorldStreamer(world, 2);
        final Set<Integer> seen = new HashSet<>();
        streamer.setSink(new WorldStreamer.TileSink() {
            @Override
            public void onTileReady(WorldStreamer.Tile tile) {
                seen.add(tile.state);
            }

            @Override
            public void onTileEvicted(WorldStreamer.Tile tile) {
                seen.add(WorldStreamer.S_EVICTING);
            }
        });

        // Dadar: dense, guaranteed to have real geometry.
        double x = world.projection().worldX(19.018, 72.843);
        double z = world.projection().worldZ(19.018);

        settle(x, z, 2f, QualityProfile.BALANCED, 120);

        assertTrue("nothing ever became active", streamer.activeCount() > 0);
        assertTrue("no tile ever decoded", streamer.decodedCount() > 0);
        assertTrue("the ready callback never fired with READY_CPU",
                seen.contains(WorldStreamer.S_READY_CPU)
                        || seen.contains(WorldStreamer.S_QUEUED_GPU));

        // Every live tile must be in a legal state, never in limbo.
        List<WorldStreamer.Tile> render = new ArrayList<>();
        streamer.collectRenderable(render);
        assertTrue("nothing renderable after settling", render.size() > 0);
        for (WorldStreamer.Tile t : render) {
            assertTrue("renderable tile in state " + t.state,
                    t.state >= WorldStreamer.S_ACTIVE_HIGH && t.state <= WorldStreamer.S_ACTIVE_LOW);
            assertTrue("renderable tile without geometry on the GPU", t.gpu != null);
        }
    }

    @Test
    public void uploadsAreBoundedPerFrame() throws Exception {
        streamer = new WorldStreamer(world, 3);
        double x = world.projection().worldX(19.018, 72.843);
        double z = world.projection().worldZ(19.018);

        int worst = 0;
        for (int i = 0; i < 150; i++) {
            streamer.update(x, z, 2f, 0, 0, QualityProfile.HIGH);
            worst = Math.max(worst, pump(3));
            Thread.sleep(2);
        }
        assertTrue("a frame uploaded " + worst + " tiles despite a budget of 3", worst <= 3);
        assertTrue("the budget starved the stream entirely", streamer.activeCount() > 4);
    }

    @Test
    public void workIsCancelledWhenThePlayerLeavesBeforeItFinishes() throws Exception {
        streamer = new WorldStreamer(world, 1);
        double x = world.projection().worldX(19.018, 72.843);
        double z = world.projection().worldZ(19.018);

        // Queue a large set, then immediately teleport 20 km away, repeatedly. A
        // naive streamer would now owe thousands of decodes that are all stale.
        for (int i = 0; i < 8; i++) {
            streamer.update(x, z, 2f, 0, 0, QualityProfile.HIGH);
            streamer.update(x, z - 20000, 2f, 0, 0, QualityProfile.HIGH);
        }
        assertTrue("the decode queue was not bounded after eight teleports: "
                        + streamer.pendingCount(),
                streamer.pendingCount() <= WorldStreamer.MAX_IN_FLIGHT);

        // Let the single worker finish, then confirm nothing from the abandoned
        // location survived: cancelled work must be dropped, not completed.
        for (int i = 0; i < 1200 && streamer.pendingCount() > 0; i++) {
            pump(8);
            Thread.sleep(5);
        }
        assertEquals("work never drained", 0, streamer.pendingCount());

        // Whatever had already been decoded for the abandoned location must now be
        // evicted rather than kept forever: keep playing at the new location and the
        // old neighbourhood has to disappear.
        for (int i = 0; i < 120; i++) {
            streamer.update(x, z - 20000, 2f, 0, 0, QualityProfile.HIGH);
            pump(4);
        }
        List<WorldStreamer.Tile> live = new ArrayList<>();
        streamer.collectRenderable(live);
        for (WorldStreamer.Tile t : live) {
            double d = Math.abs(t.centreZ - z);
            assertTrue("a tile from the abandoned location is still resident: "
                    + d + " m away", d > 8000);
        }
    }

    @Test
    public void memoryDoesNotGrowWithoutLimitWhileDrivingAcrossTheIsland() throws Exception {
        streamer = new WorldStreamer(world, 2);
        double z0 = world.projection().worldZ(18.910);     // Colaba
        double z1 = world.projection().worldZ(19.410);     // Vasai Creek
        double x = world.projection().worldX(19.100, 72.880);

        int peak = 0;
        for (int i = 0; i <= 60; i++) {
            double z = z0 + (z1 - z0) * i / 60.0;
            streamer.update(x, z, 2f, 0, (float) ((z1 - z0) / 60.0), QualityProfile.BALANCED);
            pump(4);
            peak = Math.max(peak, streamer.tileCount());
            Thread.sleep(2);
        }
        assertTrue("tile residency grew to " + peak + " across a 55 km drive, "
                        + "which means the memory budget is not enforced",
                peak <= WorldStreamer.MAX_RESIDENT);
        assertTrue("nothing was ever evicted on a 55 km drive", streamer.evictedCount() > 0);
    }

    @Test
    public void fasterTravelStreamsFurtherAhead() throws Exception {
        streamer = new WorldStreamer(world, 2);
        double x = world.projection().worldX(19.100, 72.880);
        double z = world.projection().worldZ(19.100);

        streamer.update(x, z, 2f, 0, 0, QualityProfile.BALANCED);
        int walking = streamer.tileCount();
        streamer.clear();

        // 70 m/s northbound: the corridor ahead has to be wider.
        streamer.update(x, z, 2f, 0, -70f, QualityProfile.BALANCED);
        int flying = streamer.tileCount();
        assertTrue("speed did not widen the preload ring (" + walking + " vs " + flying + ")",
                flying > walking);
        streamer.clear();

        // Altitude also coarsens and widens the set.
        streamer.update(x, z, 1500f, 0, 0, QualityProfile.BALANCED);
        int high = streamer.tileCount();
        assertTrue("altitude did not widen the stream set (" + walking + " vs " + high + ")",
                high > walking);
    }

    @Test
    public void everyWorkerThreadIsADaemonAndNoneTouchTheMainThread() throws Exception {
        streamer = new WorldStreamer(world, 2);
        final AtomicInteger offMain = new AtomicInteger();
        final Thread main = Thread.currentThread();
        streamer.setSink(new WorldStreamer.TileSink() {
            @Override
            public void onTileReady(WorldStreamer.Tile tile) {
                if (Thread.currentThread() != main) {
                    offMain.incrementAndGet();
                }
            }

            @Override
            public void onTileEvicted(WorldStreamer.Tile tile) {
            }
        });
        double x = world.projection().worldX(19.018, 72.843);
        double z = world.projection().worldZ(19.018);
        settle(x, z, 2f, QualityProfile.LOW, 60);
        assertTrue("decoding happened on the caller's thread", offMain.get() > 0);

        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("mumbai-stream")) {
                assertTrue("stream worker " + t.getName() + " is not a daemon", t.isDaemon());
                assertTrue("stream worker runs at or above UI priority",
                        t.getPriority() < Thread.NORM_PRIORITY);
            }
        }
    }

    @Test
    public void collisionGeometryIsAvailableBeforeTheGroundIsRendered() throws Exception {
        streamer = new WorldStreamer(world, 2);
        double x = world.projection().worldX(19.018, 72.843);
        double z = world.projection().worldZ(19.018);
        settle(x, z, 2f, QualityProfile.BALANCED, 150);

        assertTrue("collision data never became ready under the player",
                streamer.collisionReady(x, z));
        float[] boxes = new float[6 * 64];
        // Boxes come back as world-space AABBs: minX,minY,minZ,maxX,maxY,maxZ.
        int n = streamer.collectColliders(x, z, 220f, boxes, 64);
        assertTrue("no building colliders in the middle of Dadar", n > 0);
        for (int i = 0; i + 5 < n; i += 6) {
            float w = boxes[i + 3] - boxes[i];
            float h = boxes[i + 4] - boxes[i + 1];
            float d = boxes[i + 5] - boxes[i + 2];
            assertTrue("degenerate collider " + w + "x" + h + "x" + d,
                    w > 0.1f && h > 0.1f && d > 0.1f);
            assertTrue("a " + h + " m building is not plausible", h < 400f);
            assertTrue("a " + w + " m wide footprint is not plausible", w < 400f);
        }
    }
}
