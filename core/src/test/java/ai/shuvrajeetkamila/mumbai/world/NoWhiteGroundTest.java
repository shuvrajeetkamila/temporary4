package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.app.Quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ground has to be there, out to the distance it is drawn at.
 *
 * <p>This is the fault reported from play three times running, under
 * three different descriptions, because the hole kept changing colour.
 * The streaming radius was set from the quality tier - 22 km at HIGH -
 * while only {@link WorldStreamer#MAX_RESIDENT} tiles may be resident.
 * A disc of 22 km is about 1,500 chunks of 1,024 m, so the nearest 420
 * loaded and the remaining thousand silently never did. Beyond roughly
 * 11 km there was simply no ground.
 *
 * <p>What the player saw through the hole depended on what was behind
 * it at the time. When the sea was drawn everywhere it was water in the
 * middle of the city. Once the sea was masked off over land it became
 * the colour the frame is cleared to, which is the fog: pale grey-white
 * in daylight. Hence "land becomes water", then "everything becomes
 * white", then "white land".
 *
 * <p>And at LOW quality the radius is 9 km, which is 250 chunks and
 * fits inside the budget, so there were no holes at all. That is why
 * cycling the graphics button sometimes fixed it and sometimes did not.
 * It was not fixing anything: it was choosing a tier whose view
 * distance the memory budget could meet.
 */
final class NoWhiteGroundTest {

    /** A GPU that hands out handles and counts what is live. */
    private static final class FakeGpu {
        private int next = 1;
        private int live;
        Object upload() { live++; return "gpu" + (next++); }
        void release(Object h) { if (h != null) { live--; } }
    }

    private static void workers(WorldStreamer s, ChunkMesher mesher) {
        for (WorldStreamer.Tile t = s.nextForDecode(); t != null;
                t = s.nextForDecode()) {
            s.decode(t, mesher);
        }
    }

    /** One frame, in the renderer's order. */
    private static void pump(WorldStreamer s, ChunkMesher mesher, FakeGpu gpu,
                             double x, double z, double vx, double vz,
                             double alt) {
        s.update(x, z, vx, vz, alt);
        for (WorldStreamer.Tile t : s.pollReady(64)) {
            s.markUploaded(t, gpu.upload());
        }
        List<WorldStreamer.Tile> evict = new ArrayList<>(s.pollEvictions(16));
        for (WorldStreamer.Tile t : evict) {
            gpu.release(t.gpu);
            s.markReleased(t);
        }
        for (Object handle : s.pollDisplaced(12)) { gpu.release(handle); }
        workers(s, mesher);
    }

    @Test
    @DisplayName("every tier streams a radius its tile budget can hold")
    void theRadiusFitsTheBudget() {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        for (Quality q : Quality.values()) {
            WorldStreamer s = new WorldStreamer(w);
            s.setRadii(q.highRadius, q.medRadius, q.farRadius);
            FakeGpu gpu = new FakeGpu();
            double[] p = WorldFixture.spawnXZ(w);
            for (int f = 0; f < 140; f++) {
                pump(s, mesher, gpu, p[0], p[1], 0, 0, 0);
            }
            double r = s.effectiveRadius();
            // The disc that is actually being asked for must fit, with
            // room to spare, in what may be resident at once.
            double needed = Math.PI * r * r / (w.chunkM * w.chunkM);
            assertTrue(needed <= WorldStreamer.MAX_RESIDENT,
                    q + " streams a radius of " + Math.round(r)
                            + " m, which is " + Math.round(needed)
                            + " chunks, and only "
                            + WorldStreamer.MAX_RESIDENT + " may be resident."
                            + " The rest of the ground will never load.");
            // The severe thermal tier is allowed to be short; nothing
            // is allowed to be so short that the city vanishes.
            assertTrue(r >= Math.min(q.farRadius, 5500),
                    q + " only reaches " + Math.round(r)
                            + " m, which is not a view distance");
        }
    }

    @Test
    @DisplayName("nothing inside the streamed radius is ever missing")
    void thereAreNoHolesInTheGround() {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        WorldStreamer s = new WorldStreamer(w);
        Quality q = Quality.HIGH;
        s.setRadii(q.highRadius, q.medRadius, q.farRadius);
        FakeGpu gpu = new FakeGpu();
        double[] p = WorldFixture.spawnXZ(w);

        // Settle, standing still.
        for (int f = 0; f < 400; f++) {
            pump(s, mesher, gpu, p[0], p[1], 0, 0, 0);
        }

        double r = s.effectiveRadius();
        int missing = 0;
        int inRange = 0;
        double worst = 0;
        for (int cz = 0; cz < w.chunkNz; cz++) {
            for (int cx = 0; cx < w.chunkNx; cx++) {
                int id = cz * w.chunkNx + cx;
                double tx = w.chunkOriginX(id) + w.chunkM * 0.5;
                double tz = w.chunkOriginZ(id) + w.chunkM * 0.5;
                double d = Math.hypot(tx - p[0], tz - p[1]);
                // Inside the radius, allowing for the half-diagonal of a
                // chunk: a tile whose centre is just outside is partly
                // inside and is legitimately not wanted.
                if (d > r - w.chunkM) { continue; }
                if (!w.chunkHasData(id)) { continue; }
                inRange++;
                if (!s.tile(id).isActive()) {
                    missing++;
                    worst = Math.max(worst, d);
                }
            }
        }
        assertTrue(inRange > 100,
                "only " + inRange + " chunks were in range to check");
        assertTrue(missing == 0,
                missing + " of " + inRange + " chunks inside the streamed"
                        + " radius of " + Math.round(r) + " m never loaded,"
                        + " the furthest at " + Math.round(worst) + " m."
                        + " Each one is a hole in the ground that shows"
                        + " whatever the frame was cleared to.");
    }

    @Test
    @DisplayName("height does not open holes underneath")
    void climbingDoesNotOutrunTheBudget() {
        // Altitude widens the rings, because height reveals distance.
        // It must not widen them past what can be held, or taking the
        // helicopter up puts holes in the city below.
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        WorldStreamer s = new WorldStreamer(w);
        s.setRadii(Quality.HIGH.highRadius, Quality.HIGH.medRadius,
                Quality.HIGH.farRadius);
        FakeGpu gpu = new FakeGpu();
        double[] p = WorldFixture.spawnXZ(w);
        for (int f = 0; f < 240; f++) {
            pump(s, mesher, gpu, p[0], p[1], 0, 0, 1800);
        }
        double r = s.effectiveRadius();
        double needed = Math.PI * r * r / (w.chunkM * w.chunkM);
        assertTrue(needed <= WorldStreamer.MAX_RESIDENT,
                "at 1,800 m the streamer asks for " + Math.round(needed)
                        + " chunks and may hold "
                        + WorldStreamer.MAX_RESIDENT);
    }

    @Test
    @DisplayName("driving across the city does not tear a hole behind the lead")
    void speedDoesNotOutrunTheBudget() {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        WorldStreamer s = new WorldStreamer(w);
        s.setRadii(Quality.BALANCED.highRadius, Quality.BALANCED.medRadius,
                Quality.BALANCED.farRadius);
        FakeGpu gpu = new FakeGpu();
        double[] p = WorldFixture.spawnXZ(w);
        double x = p[0];
        double z = p[1];
        double v = 40.0;                       // 144 km/h, flat out
        int holes = 0;
        for (int f = 0; f < 900; f++) {
            pump(s, mesher, gpu, x, z, 0, v, 0);
            z += v / 60.0;
            if (!w.inBounds(x, z)) { break; }
            if (f < 300) { continue; }         // let it settle first
            // The chunk under the wheels, and its ring of neighbours,
            // must be there. Anything missing that close is a hole the
            // player is about to drive into.
            int cx = (int) Math.floor(x / w.chunkM) - w.chunkMinX;
            int cz = (int) Math.floor(z / w.chunkM) - w.chunkMinZ;
            for (int j = cz - 1; j <= cz + 1; j++) {
                for (int i = cx - 1; i <= cx + 1; i++) {
                    if (i < 0 || j < 0 || i >= w.chunkNx || j >= w.chunkNz) {
                        continue;
                    }
                    int id = j * w.chunkNx + i;
                    if (w.chunkHasData(id) && !s.tile(id).isActive()) { holes++; }
                }
            }
        }
        assertTrue(holes == 0,
                holes + " chunk-frames of missing ground within one chunk"
                        + " of a car doing 144 km/h");
    }
}
