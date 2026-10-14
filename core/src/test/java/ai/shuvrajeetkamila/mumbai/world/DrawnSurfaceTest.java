package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.render.TextureLibrary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is actually on the screen, as opposed to what is in the data.
 *
 * <p>Two faults reported from play came down to the same mistake made
 * twice: something was placed using the ground in the terrain model
 * while the player was looking at the ground in the mesh, and those are
 * not the same surface.
 *
 * <p>The first was the sea. Every chunk drew a flat water plane at chart
 * datum over its sea cells, and the animated sea surface was drawn over
 * the same water at the tide plus a metre and a half of swell. The two
 * cross constantly - the tide passes through zero twice a cycle and the
 * waves straddle it the whole time - so over open water they interleaved
 * in the depth buffer along a join that crawled with the waves. That is
 * the moving sheet that comes over the view after a while.
 *
 * <p>The second was the roads. A road was laid at the true ground height
 * plus seven centimetres, but the terrain under it is drawn from samples
 * 32, 64 or 128 m apart depending on how far away the chunk is. On a
 * slope the drawn ground is metres away from the true height, so the
 * road sank into the hillside in patches and hung over it in others.
 */
final class DrawnSurfaceTest {

    private static final int STRIDE = 11;

    /** The features for a chunk, or null if it is outside the world. */
    private static ChunkFeatures features(WorldData w, int c)
            throws java.io.IOException {
        double ox = w.chunkOriginX(c);
        double oz = w.chunkOriginZ(c);
        if (!w.inBounds(ox, oz)) { return null; }
        return w.chunkHasData(c)
                ? ChunkFeatures.decode(c, ox, oz, w.readChunk(c), w.quant)
                : ChunkFeatures.empty(c, ox, oz);
    }

    @Test
    @DisplayName("open water is meshed once, not twice")
    void openWaterIsLeftToTheSea() throws Exception {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        int checked = 0;
        int offenders = 0;
        for (int c = 0; c < w.chunkCount(); c += 37) {
            ChunkFeatures f = features(w, c);
            if (f == null) { continue; }
            for (int lod : new int[]{ChunkMesher.LOD_HIGH, ChunkMesher.LOD_MED,
                                     ChunkMesher.LOD_LOW}) {
                ChunkMesher.Mesh m = mesher.build(f, lod);
                for (int t = 0; t < m.indexCount; t += 3) {
                    if (!isFlatWater(m, m.indices[t])
                            || !isFlatWater(m, m.indices[t + 1])
                            || !isFlatWater(m, m.indices[t + 2])) {
                        continue;
                    }
                    offenders++;
                }
            }
            checked++;
            if (checked > 24) { break; }
        }
        assertTrue(checked > 4, "no chunks were examined");
        assertTrue(offenders == 0,
                offenders + " triangles of flat water at chart datum are"
                        + " still being meshed. The animated sea is drawn"
                        + " over the same water and the two fight for the"
                        + " depth buffer along a join that moves with the"
                        + " waves.");
    }

    /** An upward-facing water vertex sitting at chart datum. */
    private static boolean isFlatWater(ChunkMesher.Mesh m, int v) {
        int o = v * STRIDE;
        return m.vertices[o + 9] < -0.5f          // aux -1 marks water
                && m.vertices[o + 4] > 0.5f       // upward facing, not a skirt
                && Math.abs(m.vertices[o + 1]) < 1e-3f;
    }

    @Test
    @DisplayName("roads lie on the ground that is drawn, at every detail level")
    void roadsLieOnTheDrawnGround() throws Exception {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        double worst = 0;
        int sampled = 0;
        int chunks = 0;
        for (int c = 0; c < w.chunkCount() && chunks < 14; c += 37) {
            ChunkFeatures f = features(w, c);
            if (f == null || f.roadCount() == 0) { continue; }
            // Bridges are meant to leave the ground, and RoadDeck
            // decides where their deck goes. Only chunks with none in
            // them can be checked against the ground.
            boolean spanned = false;
            for (int r = 0; r < f.roadCount(); r++) {
                if ((f.roadFlags[r] & ChunkFeatures.FLAG_BRIDGE) != 0) {
                    spanned = true;
                    break;
                }
            }
            if (spanned) { continue; }
            chunks++;
            for (int lod : new int[]{ChunkMesher.LOD_HIGH, ChunkMesher.LOD_MED,
                                     ChunkMesher.LOD_LOW}) {
                ChunkMesher.Mesh m = mesher.build(f, lod);
                for (int v = 0; v < m.vertexCount; v++) {
                    int o = v * STRIDE;
                    // Paved road surface: flat, upward, tarmac.
                    if (Math.abs(m.vertices[o + 9] - TextureLibrary.MAT_TARMAC)
                            > 1e-3f) {
                        continue;
                    }
                    if (m.vertices[o + 4] < 0.99f) { continue; }
                    double lx = m.vertices[o];
                    double lz = m.vertices[o + 2];
                    if (lx < 0 || lz < 0 || lx > w.chunkM || lz > w.chunkM) {
                        continue;                 // ribbon running off the edge
                    }
                    double ground = mesher.lodGround(m.originX, m.originZ,
                            lod, lx, lz);
                    double over = m.vertices[o + 1] - ground;
                    sampled++;
                    worst = Math.max(worst,
                            Math.abs(over - ChunkMesher.ROAD_LIFT_M));
                }
            }
        }
        assertTrue(sampled > 500,
                "only " + sampled + " road vertices were examined");
        assertTrue(worst < 0.02,
                "a road surface sits " + worst + " m away from where it"
                        + " should relative to the ground that is drawn"
                        + " under it. On a slope that is the difference"
                        + " between a road and a line of torn ground.");
    }

    @Test
    @DisplayName("the road lift survives the depth buffer at drawing range")
    void theLiftClearsTheDepthBuffer() {
        // A 24-bit depth buffer with the near plane at n resolves roughly
        // d * d / (n * 2^24) metres at distance d. Beyond the distance
        // where that exceeds the lift, every road in view shimmers
        // against the ground it is lying on. These two constants have to
        // be chosen together, so this records the range they buy.
        double near = ChunkMesher.NEAR_PLANE_M;
        double lift = ChunkMesher.ROAD_LIFT_M;
        double range = Math.sqrt(lift * near * 16777216.0);
        assertTrue(range > 1200,
                "roads start fighting the ground at " + Math.round(range)
                        + " m, which is well inside the city. Either the"
                        + " lift or the near plane has moved.");
        // And the lift must stay small enough to read as a kerb rather
        // than a wall: a road hanging half a metre over its verge is
        // just as wrong, and the ribbon has no sides to hide the gap.
        assertTrue(lift < 0.25,
                "a " + lift + " m lift is a step, not a road surface");
    }

    @Test
    @DisplayName("the drawn ground agrees with the mesh it came from")
    void theDrawnGroundMatchesTheMesh() throws Exception {
        // lodGround claims to report the height of the terrain mesh.
        // This checks it against the triangles the mesher actually
        // emitted, rather than taking its word for it.
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        double worst = 0;
        int sampled = 0;
        for (int c = 0; c < w.chunkCount() && sampled < 4000; c += 97) {
            ChunkFeatures f = features(w, c);
            if (f == null) { continue; }
            ChunkMesher.Mesh m = mesher.build(f, ChunkMesher.LOD_MED);
            for (int v = 0; v < m.vertexCount; v++) {
                int o = v * STRIDE;
                // Terrain grid nodes: upward facing, and sitting exactly
                // on a multiple of the grid step.
                if (m.vertices[o + 4] < 0.1f) { continue; }
                double lx = m.vertices[o];
                double lz = m.vertices[o + 2];
                if (lx < 0 || lz < 0 || lx > w.chunkM || lz > w.chunkM) {
                    continue;
                }
                double step = w.chunkM / 16;
                if (Math.abs(lx / step - Math.round(lx / step)) > 1e-6) {
                    continue;
                }
                if (Math.abs(lz / step - Math.round(lz / step)) > 1e-6) {
                    continue;
                }
                double claimed = mesher.lodGround(m.originX, m.originZ,
                        ChunkMesher.LOD_MED, lx, lz);
                double actual = m.vertices[o + 1];
                // Roads and buildings sit above the ground; only compare
                // against vertices that are the ground.
                if (Math.abs(m.vertices[o + 9] - TextureLibrary.MAT_TARMAC)
                        < 1e-3f) {
                    continue;
                }
                if (actual - claimed > 0.5) { continue; }
                sampled++;
                worst = Math.max(worst, Math.abs(actual - claimed));
            }
        }
        assertTrue(sampled > 200, "only " + sampled + " nodes were examined");
        assertTrue(worst < 0.01,
                "the drawn ground reported for a terrain node is " + worst
                        + " m away from the node itself");
    }
}
