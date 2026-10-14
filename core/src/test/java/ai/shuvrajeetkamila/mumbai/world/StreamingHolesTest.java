package ai.shuvrajeetkamila.mumbai.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holes in the ground, and the GPU memory behind them.
 *
 * <p>Reported from play: land turns to water after a while, the view
 * gradually fills with sea, and cycling the graphics button puts it right
 * for a bit. Every part of that description points at the same place. The
 * sea is drawn as a grid around the camera and the land is drawn on top of
 * it, so a tile that stops being drawn is not a black hole — it is a hole
 * with the sea visible through it. The graphics button changes the
 * streaming radii, which re-requests tiles, which fills the holes in.
 *
 * <p>These tests drive the streamer exactly as the renderer does: request,
 * decode, upload, evict, with a stand-in for the GPU that counts handles
 * so a leak is visible. They are about the invariant the renderer relies
 * on and nothing else: <b>a tile that is in view and has been uploaded
 * must be drawable on every single frame.</b>
 */
final class StreamingHolesTest {

    /** Mirrors the renderer's draw test exactly. */
    private static boolean drawable(WorldStreamer.Tile t) {
        return t.isActive() && t.gpu != null;
    }

    /**
     * A stand-in for the GPU: hands out handles and records which ones
     * were released, so a leaked buffer is a number rather than a guess.
     */
    private static final class FakeGpu {
        int allocated;
        final Set<Object> live = new HashSet<>();

        Object upload() {
            allocated++;
            Object h = new Object();
            live.add(h);
            return h;
        }

        void release(Object h) { live.remove(h); }
    }

    /**
     * One frame of exactly what SceneRenderer does, in its order.
     *
     * <p>The decode runs at the <em>end</em> of the frame on purpose.
     * Decoding is done by worker threads, so work queued during this
     * frame's update cannot possibly be ready for this frame's upload;
     * the earliest it can appear on screen is the next frame. Running the
     * decode inline before the upload - which is the obvious way to write
     * this harness - papers over exactly the gap being looked for.
     */
    private static void pump(WorldStreamer s, ChunkMesher mesher, FakeGpu gpu,
                             double x, double z) {
        s.update(x, z, 0, 0, 0);
        for (WorldStreamer.Tile t : s.pollReady(64)) {
            s.markUploaded(t, gpu.upload());
        }
        List<WorldStreamer.Tile> evict = new ArrayList<>(s.pollEvictions(16));
        for (WorldStreamer.Tile t : evict) {
            gpu.release(t.gpu);
            s.markReleased(t);
        }
        // Exactly what the renderer does: free the meshes a rebuild
        // replaced. The harness must not be more careful than the real
        // thing, or the leak it is meant to catch cannot happen here.
        for (Object handle : s.pollDisplaced(12)) { gpu.release(handle); }
        // ... the renderer draws here ...
        workers(s, mesher);
    }

    /** The worker pool, emptying the I/O queue between frames. */
    private static void workers(WorldStreamer s, ChunkMesher mesher) {
        for (WorldStreamer.Tile t = s.nextForDecode(); t != null;
                t = s.nextForDecode()) {
            s.decode(t, mesher);
        }
    }

    private static WorldStreamer streamer(WorldData w) {
        WorldStreamer s = new WorldStreamer(w);
        s.setRadii(800, 3200, 9000);
        return s;
    }

    @Test
    @DisplayName("a tile changing detail level never stops being drawn")
    void aLodChangeDoesNotBlankTheGround() {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        WorldStreamer s = streamer(w);
        FakeGpu gpu = new FakeGpu();

        // Settle at a spot with ground under it.
        double x = -5761, z = 14722;
        for (int i = 0; i < 40; i++) { pump(s, mesher, gpu, x, z); }

        // Pick a tile that is up and drawable, and note how far away it is.
        WorldStreamer.Tile watch = null;
        for (WorldStreamer.Tile t : s.residentTiles()) {
            if (drawable(t) && t.lod == ChunkMesher.LOD_HIGH) { watch = t; }
        }
        assertTrue(watch != null, "nothing was resident to watch");

        // Now walk away from it, far enough to cross a detail boundary.
        // Every frame, the tile must still be drawable: the renderer has
        // no concept of 'being rebuilt', it just skips anything that is
        // not active, and the player sees the sea through the gap.
        int blanked = 0;
        for (int i = 0; i < 400; i++) {
            double px = x + i * 6.0;
            pump(s, mesher, gpu, px, z);
            if (!drawable(watch) && watch.gpu != null) { blanked++; }
        }
        assertEquals(0, blanked,
                "a tile that was already on the GPU stopped being drawn on "
                        + blanked + " frames while it was rebuilt at a new"
                        + " detail level. That gap is a hole in the ground"
                        + " with the sea showing through it.");
    }

    @Test
    @DisplayName("rebuilding a tile does not leak its old buffers")
    void aLodChangeFreesTheOldMesh() {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        WorldStreamer s = streamer(w);
        FakeGpu gpu = new FakeGpu();

        double x = -5761, z = 14722;
        for (int i = 0; i < 400; i++) { pump(s, mesher, gpu, x + i * 6.0, z); }

        // Everything handed out must either still be in use by a resident
        // tile or have been handed back. Anything else is GPU memory that
        // cannot be reclaimed for the rest of the session, and a driver
        // that runs out of it starts failing uploads - which is what
        // torn, striped geometry on screen actually is.
        Set<Object> inUse = new HashSet<>();
        for (WorldStreamer.Tile t : s.residentTiles()) {
            if (t.gpu != null) { inUse.add(t.gpu); }
        }
        List<Object> leaked = new ArrayList<>();
        for (Object h : gpu.live) {
            if (!inUse.contains(h)) { leaked.add(h); }
        }
        assertEquals(0, leaked.size(),
                leaked.size() + " of " + gpu.allocated + " tile meshes were"
                        + " replaced or dropped without being freed");
    }

    @Test
    @DisplayName("a cancelled rebuild leaves the tile on screen, not in limbo")
    void aCancelledRebuildDoesNotStrandTheTile() {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        WorldStreamer s = streamer(w);
        FakeGpu gpu = new FakeGpu();

        double x = -5761, z = 14722;
        for (int i = 0; i < 40; i++) { pump(s, mesher, gpu, x, z); }

        // A close-in tile: walking away from an already-coarse one never
        // changes its detail level, so it would never rebuild at all.
        WorldStreamer.Tile watch = null;
        for (WorldStreamer.Tile t : s.residentTiles()) {
            if (drawable(t) && t.lod == ChunkMesher.LOD_HIGH) { watch = t; }
        }
        assertTrue(watch != null);
        Object handle;

        // Walk away a little at a time until this tile crosses a detail
        // boundary, rather than teleporting: a long jump queues hundreds
        // of fresh tiles and the rebuild never gets a slot.
        double px = x;
        for (int i = 0; i < 80 && !watch.rebuilding; i++) {
            px += 60;
            s.update(px, z, 0, 0, 0);
            if (watch.rebuilding) { break; }
            workers(s, mesher);
            for (WorldStreamer.Tile t : s.pollReady(64)) {
                s.markUploaded(t, gpu.upload());
            }
            for (WorldStreamer.Tile t : new ArrayList<>(s.pollEvictions(16))) {
                gpu.release(t.gpu);
                s.markReleased(t);
            }
            for (Object h : s.pollDisplaced(12)) { gpu.release(h); }
        }
        assertTrue(watch.rebuilding, "the rebuild was never queued");
        handle = watch.gpu;

        // Cancel it mid-flight, which is what happens when the player
        // turns round, and let the worker discover that.
        watch.cancelled = true;
        WorldStreamer.Tile got = s.nextForDecode();
        while (got != null) {
            s.decode(got, mesher);
            if (got == watch) { break; }
            got = s.nextForDecode();
        }

        assertTrue(drawable(watch),
                "a tile whose rebuild was cancelled stopped being drawn"
                        + " while its mesh was still on the GPU");
        assertTrue(s.residentTiles().contains(watch),
                "a cancelled rebuild dropped the tile out of the resident"
                        + " set while it still owned GPU memory, so nothing"
                        + " will ever free it");
        assertEquals(handle, watch.gpu, "the old mesh was thrown away");
    }

    @Test
    @DisplayName("no resident tile is ever stuck holding memory it cannot use")
    void thereAreNoZombies() {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        WorldStreamer s = streamer(w);
        FakeGpu gpu = new FakeGpu();

        // A long drive with turns, which is when cancellation happens.
        double x = -5761, z = 14722;
        for (int i = 0; i < 300; i++) {
            double px = x + Math.sin(i * 0.07) * 1400 + i * 3;
            double pz = z + Math.cos(i * 0.05) * 1100;
            pump(s, mesher, gpu, px, pz);
        }
        int zombies = 0;
        for (WorldStreamer.Tile t : s.residentTiles()) {
            if (t.gpu != null && !t.isActive()
                    && t.state != WorldStreamer.S_EVICTING) {
                zombies++;
            }
        }
        assertEquals(0, zombies,
                zombies + " resident tiles hold GPU memory in a state that"
                        + " is neither drawable nor queued for release."
                        + " They fill the residency cap and the tiles the"
                        + " player can actually see stop loading.");
    }

    @Test
    @DisplayName("driving a long way keeps the ground under the player")
    void theGroundStaysUnderfoot() {
        // The symptom as the player describes it: after a while there is
        // more and more sea. Measured as the fraction of frames on which
        // the tile the player is standing on is not being drawn.
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        WorldStreamer s = streamer(w);
        FakeGpu gpu = new FakeGpu();

        double x = -5761, z = 14722;
        for (int i = 0; i < 40; i++) { pump(s, mesher, gpu, x, z); }

        int missing = 0, checked = 0;
        for (int i = 0; i < 500; i++) {
            double px = x + i * 8.0;
            pump(s, mesher, gpu, px, z);
            if (i < 60) { continue; }
            checked++;
            boolean under = false;
            for (WorldStreamer.Tile t : s.residentTiles()) {
                if (!drawable(t)) { continue; }
                if (px >= t.originX && px < t.originX + w.chunkM
                        && z >= t.originZ && z < t.originZ + w.chunkM) {
                    under = true;
                    break;
                }
            }
            if (!under) { missing++; }
        }
        assertTrue(missing == 0,
                "the ground directly under the player was missing on "
                        + missing + " of " + checked + " frames");
    }

    @Test
    @DisplayName("a lost GL context does not leave tiles pointing at nothing")
    void forgettingTheGpuStreamsEverythingBackIn() {
        // Android throws the GL context away when the app goes to the
        // background. Every buffer and vertex array dies with it, but the
        // handles are small integers that the driver hands out again for
        // completely different objects - so a tile still holding one
        // draws whatever owns that name now. This is what came back
        // "after playing for a while": the app had been backgrounded.
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        WorldStreamer s = streamer(w);
        FakeGpu gpu = new FakeGpu();

        double x = -5761, z = 14722;
        for (int i = 0; i < 60; i++) { pump(s, mesher, gpu, x, z); }
        int before = s.residentCount();
        assertTrue(before > 10, "nothing was resident to begin with");

        s.forgetGpu();
        assertEquals(0, s.residentCount(),
                "tiles are still resident after the context that owned"
                        + " their buffers was destroyed");
        for (WorldStreamer.Tile t : s.residentTiles()) {
            assertTrue(t.gpu == null);
        }

        // And it all comes back, rather than leaving a hole for ever.
        for (int i = 0; i < 60; i++) { pump(s, mesher, gpu, x, z); }
        assertTrue(s.residentCount() > before / 2,
                "after the context came back only " + s.residentCount()
                        + " of " + before + " tiles were streamed in again");
        boolean under = false;
        for (WorldStreamer.Tile t : s.residentTiles()) {
            if (!drawable(t)) { continue; }
            if (x >= t.originX && x < t.originX + w.chunkM
                    && z >= t.originZ && z < t.originZ + w.chunkM) {
                under = true;
            }
        }
        assertTrue(under, "there is no ground under the player any more");
    }
}
