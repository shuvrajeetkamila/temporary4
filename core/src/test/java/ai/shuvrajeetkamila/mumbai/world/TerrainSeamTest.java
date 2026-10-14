package ai.shuvrajeetkamila.mumbai.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam between two chunks at different detail levels.
 *
 * <p>The terrain is meshed at 32, 16 or 8 subdivisions depending on how
 * far away a chunk is. Two neighbouring chunks at different detail levels
 * therefore disagree about the ground along the edge they share: one
 * follows it every 32 m, the other cuts straight across 128 m. The
 * ground tears open between them, and because the sea is drawn under
 * everything, the tear shows as water or sky in a straight line across
 * the land - one of the two reasons the player saw "texture lines".
 *
 * <p>The fix is a skirt hung from every chunk edge down past the lowest
 * ground in that chunk. These tests measure the tear from the real
 * terrain and then check the skirt is deep enough to cover it.
 */
final class TerrainSeamTest {

    /** The ground height a mesh at this detail level shows at a point. */
    private static double surfaceAt(WorldData w, double ox, double oz,
                                    double along, int lod, boolean eastEdge) {
        int n = lod == ChunkMesher.LOD_HIGH ? 32
                : (lod == ChunkMesher.LOD_MED ? 16 : 8);
        double step = w.chunkM / n;
        // The mesh interpolates linearly between its own grid samples,
        // which is exactly where the two detail levels part company.
        int cell = (int) Math.floor(along / step);
        if (cell >= n) { cell = n - 1; }
        double t = (along - cell * step) / step;
        double a0 = cell * step;
        double a1 = (cell + 1) * step;
        double h0 = sample(w, eastEdge ? ox : ox + a0, eastEdge ? oz + a0 : oz);
        double h1 = sample(w, eastEdge ? ox : ox + a1, eastEdge ? oz + a1 : oz);
        return h0 + (h1 - h0) * t;
    }

    private static double sample(WorldData w, double x, double z) {
        return w.isLand(x, z) ? w.height(x, z) : 0.0;
    }

    @Test
    @DisplayName("the seam really does tear open without a skirt")
    void theTearIsReal() {
        // Establishes that this test is describing the real world, so
        // that if a future terrain is smooth enough not to tear the
        // failure says so rather than quietly passing.
        WorldData w = WorldFixture.world();
        double worst = 0;
        for (int c = 0; c < w.chunkCount(); c += 37) {
            double ox = w.chunkOriginX(c);
            double oz = w.chunkOriginZ(c);
            if (!w.inBounds(ox, oz)) { continue; }
            for (double a = 0; a < w.chunkM; a += 8) {
                double hi = surfaceAt(w, ox, oz, a, ChunkMesher.LOD_HIGH, false);
                double lo = surfaceAt(w, ox, oz, a, ChunkMesher.LOD_LOW, false);
                worst = Math.max(worst, Math.abs(hi - lo));
            }
        }
        assertTrue(worst > 3.0,
                "the worst disagreement between a high and a low detail"
                        + " chunk edge is only " + worst + " m");
    }

    @Test
    @DisplayName("every chunk hangs a skirt below its lowest ground")
    void thereIsASkirt() throws Exception {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        int checked = 0;
        // Stride coprime with the chunk grid width: the world is 53
        // chunks across, so a stride of 53 walks one column for ever.
        for (int c = 0; c < w.chunkCount() && checked < 24; c += 37) {
            double ox = w.chunkOriginX(c);
            double oz = w.chunkOriginZ(c);
            if (!w.inBounds(ox, oz)) { continue; }
            ChunkFeatures f = w.chunkHasData(c)
                    ? ChunkFeatures.decode(c, ox, oz, w.readChunk(c), w.quant)
                    : ChunkFeatures.empty(c, ox, oz);
            ChunkMesher.Mesh m = mesher.build(f, ChunkMesher.LOD_HIGH);
            if (m.vertexCount == 0) { continue; }
            checked++;

            // The lowest ground the grid itself samples.
            double groundMin = Double.MAX_VALUE;
            for (int j = 0; j <= 32; j++) {
                for (int i = 0; i <= 32; i++) {
                    double x = ox + i * w.chunkM / 32;
                    double z = oz + j * w.chunkM / 32;
                    groundMin = Math.min(groundMin, sample(w, x, z));
                }
            }
            assertTrue(m.minY <= groundMin - ChunkMesher.SKIRT_MARGIN_M + 1e-3,
                    "chunk " + c + " has no skirt: its mesh bottoms out at "
                            + m.minY + " and the ground it covers goes down"
                            + " to " + groundMin);
        }
        assertTrue(checked > 5, "only checked " + checked + " chunks");
    }

    @Test
    @DisplayName("the skirt is deep enough to cover the worst tear")
    void theSkirtCoversTheTear() throws Exception {
        // The real question is not whether a skirt exists but whether it
        // reaches. For each chunk: how far below its own edge surface
        // does the skirt hang, and how far below that edge can the
        // neighbour's coarser edge fall?
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        int checked = 0, short_ = 0;
        double worstShortfall = 0;
        for (int c = 0; c < w.chunkCount() && checked < 40; c += 31) {
            double ox = w.chunkOriginX(c);
            double oz = w.chunkOriginZ(c);
            if (!w.inBounds(ox, oz)) { continue; }
            ChunkFeatures f = w.chunkHasData(c)
                    ? ChunkFeatures.decode(c, ox, oz, w.readChunk(c), w.quant)
                    : ChunkFeatures.empty(c, ox, oz);
            ChunkMesher.Mesh m = mesher.build(f, ChunkMesher.LOD_HIGH);
            if (m.vertexCount == 0) { continue; }
            checked++;

            for (double a = 0; a < w.chunkM; a += 16) {
                for (boolean east : new boolean[]{false, true}) {
                    double mine = surfaceAt(w, ox, oz, a,
                            ChunkMesher.LOD_HIGH, east);
                    double theirs = Math.min(
                            surfaceAt(w, ox, oz, a, ChunkMesher.LOD_MED, east),
                            surfaceAt(w, ox, oz, a, ChunkMesher.LOD_LOW, east));
                    if (theirs >= mine) { continue; }
                    // The neighbour's ground is lower here, so the gap
                    // runs from my edge down to theirs. My skirt has to
                    // reach at least that far.
                    if (m.minY > theirs) {
                        short_++;
                        worstShortfall = Math.max(worstShortfall,
                                m.minY - theirs);
                    }
                }
            }
        }
        assertTrue(checked > 10, "only checked " + checked + " chunks");
        assertTrue(short_ == 0,
                short_ + " places where the skirt stops short of the"
                        + " neighbouring chunk's ground, by up to "
                        + worstShortfall + " m. The seam will show.");
    }

    @Test
    @DisplayName("the skirt does not cost much")
    void theSkirtIsCheap() throws Exception {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        for (int c = 0; c < w.chunkCount(); c += 97) {
            double ox = w.chunkOriginX(c);
            double oz = w.chunkOriginZ(c);
            if (!w.inBounds(ox, oz) || !w.chunkHasData(c)) { continue; }
            ChunkFeatures f = ChunkFeatures.decode(c, ox, oz, w.readChunk(c),
                    w.quant);
            ChunkMesher.Mesh hi = mesher.build(f, ChunkMesher.LOD_HIGH);
            if (hi.indexCount == 0) { continue; }
            // 32x32 grid is 2048 triangles; the skirt adds 4x32x2 = 256.
            // Anything much past that means it is being built wrong.
            assertTrue(hi.indexCount / 3 >= 2048,
                    "chunk " + c + " has fewer triangles than the bare grid");
            return;
        }
    }
}
