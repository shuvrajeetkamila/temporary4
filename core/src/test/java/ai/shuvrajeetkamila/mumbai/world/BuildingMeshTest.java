package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.geo.GeoProjection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the buildings actually come out looking like.
 *
 * <p>Two faults are pinned here. The first is that levels of detail used to
 * <em>delete</em> small buildings rather than simplify them, which emptied
 * the low-rise two thirds of the city the moment it was more than a chunk
 * or two away: from a kilometre up, Dharavi, Govandi and most of the
 * suburbs were bare ground with a few towers standing in it. The second is
 * that a facade's texture was addressed by absolute world height, so the
 * bottom row of windows was sliced wherever a multiple of the tile height
 * happened to fall relative to the pavement.
 */
final class BuildingMeshTest {

    private static int chunkAt(WorldData w, double lat, double lon) {
        return w.chunkIdAt(GeoProjection.toX(lat, lon), GeoProjection.toZ(lat));
    }

    private static ChunkFeatures features(WorldData w, int id) throws IOException {
        byte[] raw = w.readChunk(id);
        return ChunkFeatures.decode(id, w.chunkOriginX(id), w.chunkOriginZ(id),
                raw, w.quant);
    }

    /** A dense low-rise chunk: the kind the old level of detail erased. */
    private static int lowRiseChunk(WorldData w) throws IOException {
        // Dharavi and its neighbours. Picked by what is in the chunk, not
        // by name, so a shift in the raster cannot leave this probing sea.
        double[][] tries = {
            {19.0430, 72.8500}, {19.0500, 72.8560}, {19.0380, 72.8450},
            {19.0620, 72.8800}, {19.1100, 72.8830},
        };
        int best = -1;
        int bestSmall = 0;
        for (double[] ll : tries) {
            int id = chunkAt(w, ll[0], ll[1]);
            if (id < 0 || !w.chunkHasData(id)) { continue; }
            ChunkFeatures f = features(w, id);
            int small = 0;
            for (int b = 0; b < f.buildingCount(); b++) {
                if (f.buildingHeight[b] < 9 && f.buildingArea(b) < 220) { small++; }
            }
            if (small > bestSmall) { bestSmall = small; best = id; }
        }
        assertTrue(best >= 0 && bestSmall > 50,
                "no dense low-rise chunk found (best had " + bestSmall + ")");
        return best;
    }

    /** Vertices standing clear of the ground: the built mass of the chunk. */
    private static int raisedVertices(ChunkMesher.Mesh m, double groundY,
                                      double clearance) {
        int n = 0;
        for (int i = 0; i < m.vertexCount; i++) {
            if (m.vertices[i * ChunkMesher.STRIDE + 1] > groundY + clearance) { n++; }
        }
        return n;
    }

    @Test
    @DisplayName("the vertex carries a vertical texture coordinate of its own")
    void vertexHasItsOwnTextureHeight() {
        assertEquals(11, ChunkMesher.STRIDE,
                "the facade V channel is gone from the vertex format");
    }

    @Test
    @DisplayName("a suburb of low-rise does not vanish at medium range")
    void lowRiseSurvivesMediumDetail() throws IOException {
        WorldData w = WorldFixture.world();
        int id = lowRiseChunk(w);
        ChunkFeatures f = features(w, id);
        ChunkMesher mesher = new ChunkMesher(w);
        double g = w.height(w.chunkOriginX(id) + 512, w.chunkOriginZ(id) + 512);

        ChunkMesher.Mesh hi = mesher.build(f, ChunkMesher.LOD_HIGH);
        ChunkMesher.Mesh med = mesher.build(f, ChunkMesher.LOD_MED);
        int a = raisedVertices(hi, g, 4.0);
        int b = raisedVertices(med, g, 4.0);
        assertTrue(a > 100, "the close-up chunk is flat: " + a);
        assertTrue(b > 0, "every building disappeared at medium detail");
        assertTrue(b > a * 0.02,
                "medium detail kept only " + b + " of " + a + " raised vertices");
    }

    @Test
    @DisplayName("and does not vanish at long range either")
    void lowRiseSurvivesLowDetail() throws IOException {
        WorldData w = WorldFixture.world();
        int id = lowRiseChunk(w);
        ChunkFeatures f = features(w, id);
        ChunkMesher mesher = new ChunkMesher(w);
        double g = w.height(w.chunkOriginX(id) + 512, w.chunkOriginZ(id) + 512);
        ChunkMesher.Mesh low = mesher.build(f, ChunkMesher.LOD_LOW);
        assertTrue(raisedVertices(low, g, 3.0) > 0,
                "the whole neighbourhood is bare ground from a distance");
        assertTrue(low.maxY > g + 4.0,
                "nothing stands above the ground at long range: maxY "
                        + low.maxY + " vs ground " + g);
    }

    @Test
    @DisplayName("distance still costs less to draw than close up")
    void detailStillFallsOffWithDistance() throws IOException {
        WorldData w = WorldFixture.world();
        int id = lowRiseChunk(w);
        ChunkFeatures f = features(w, id);
        ChunkMesher mesher = new ChunkMesher(w);
        int hi = mesher.build(f, ChunkMesher.LOD_HIGH).triangleCount();
        int med = mesher.build(f, ChunkMesher.LOD_MED).triangleCount();
        int low = mesher.build(f, ChunkMesher.LOD_LOW).triangleCount();
        assertTrue(med < hi, "medium detail is not cheaper: " + med + " vs " + hi);
        assertTrue(low < med, "long range is not cheaper: " + low + " vs " + med);
        // The impostors are meant to be a rounding error, not a second city.
        assertTrue(low < hi / 4,
                "long range costs " + low + " triangles against " + hi);
    }

    @Test
    @DisplayName("facade texture heights are measured from the pavement")
    void facadeTextureStartsAtTheBase() throws IOException {
        WorldData w = WorldFixture.world();
        int id = lowRiseChunk(w);
        ChunkFeatures f = features(w, id);
        ChunkMesher.Mesh m = new ChunkMesher(w).build(f, ChunkMesher.LOD_HIGH);

        int walls = 0;
        int atBase = 0;
        double maxV = 0;
        double maxY = -1e9;
        for (int i = 0; i < m.vertexCount; i++) {
            int o = i * ChunkMesher.STRIDE;
            // Building walls are the only thing pushed with an exactly
            // horizontal normal; terrain normals come off the height field.
            if (m.vertices[o + 4] != 0f) { continue; }
            if (m.vertices[o + 3] == 0f && m.vertices[o + 5] == 0f) { continue; }
            walls++;
            double v = m.vertices[o + 10];
            double y = m.vertices[o + 1];
            if (v == 0.0) { atBase++; }
            maxV = Math.max(maxV, v);
            maxY = Math.max(maxY, y);
            assertTrue(v >= -0.001, "negative facade height " + v);
            assertTrue(v <= 460, "facade height " + v + " m is taller than anything");
        }
        assertTrue(walls > 500, "only " + walls + " wall vertices");
        assertTrue(atBase > walls / 8,
                "only " + atBase + " of " + walls
                        + " wall vertices sit at the bottom of their own wall");
        // The whole point: the coordinate is a local height, not the world
        // height it used to be, so on raised ground it is strictly smaller.
        assertTrue(maxV <= maxY + 0.001,
                "facade height " + maxV + " exceeds world height " + maxY);
    }

    @Test
    @DisplayName("a flat roof gets a parapet and something standing on it")
    void roofsAreNotBareSlabs() throws IOException {
        WorldData w = WorldFixture.world();
        // Somewhere with substantial flat-roofed blocks: Nariman Point.
        int id = chunkAt(w, 18.9270, 72.8230);
        if (id < 0 || !w.chunkHasData(id)) { id = lowRiseChunk(w); }
        ChunkFeatures f = features(w, id);
        ChunkMesher mesher = new ChunkMesher(w);
        ChunkMesher.Mesh hi = mesher.build(f, ChunkMesher.LOD_HIGH);
        ChunkMesher.Mesh med = mesher.build(f, ChunkMesher.LOD_MED);
        // Parapets and roof clutter stand above the building top, so the
        // close-up mesh reaches higher than the simplified one.
        assertTrue(hi.maxY > med.maxY,
                "nothing was added on top up close: " + hi.maxY + " vs " + med.maxY);
    }

    @Test
    @DisplayName("the block impostors stay inside their own chunk")
    void impostorsDoNotLeakIntoTheNextChunk() throws IOException {
        WorldData w = WorldFixture.world();
        int id = lowRiseChunk(w);
        ChunkFeatures f = features(w, id);
        ChunkMesher.Mesh m = new ChunkMesher(w).build(f, ChunkMesher.LOD_LOW);
        double size = w.chunkM;
        for (int i = 0; i < m.vertexCount; i++) {
            int o = i * ChunkMesher.STRIDE;
            double x = m.vertices[o];
            double z = m.vertices[o + 2];
            assertTrue(x >= -1 && x <= size + 1,
                    "a vertex sits at x " + x + " outside a " + size + " m chunk");
            assertTrue(z >= -1 && z <= size + 1,
                    "a vertex sits at z " + z + " outside a " + size + " m chunk");
        }
    }

    @Test
    @DisplayName("impostor heights are the heights of what they stand for")
    void impostorsAreTheRightHeight() throws IOException {
        WorldData w = WorldFixture.world();
        int id = lowRiseChunk(w);
        ChunkFeatures f = features(w, id);
        double tallest = 0;
        for (int b = 0; b < f.buildingCount(); b++) {
            tallest = Math.max(tallest, f.buildingHeight[b]);
        }
        ChunkMesher.Mesh low = new ChunkMesher(w).build(f, ChunkMesher.LOD_LOW);
        double g = w.height(w.chunkOriginX(id) + 512, w.chunkOriginZ(id) + 512);
        assertTrue(low.maxY - g < tallest + 30,
                "a distant block stands " + (low.maxY - g)
                        + " m against a tallest building of " + tallest);
    }
}
