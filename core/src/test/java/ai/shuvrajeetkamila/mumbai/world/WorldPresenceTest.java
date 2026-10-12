package ai.shuvrajeetkamila.mumbai.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gate that cannot be skipped.
 *
 * <p>Every other world test is allowed to skip when the compiled assets are
 * absent, so the physics suite runs on a bare checkout. That creates a hole:
 * a release build with no world data would show forty green skips and nobody
 * would notice. This test closes it. When {@code mumbai.requireWorld} is set,
 * which CI always does, a missing world is a hard failure.
 */
class WorldPresenceTest {

    private static boolean required() {
        return Boolean.parseBoolean(System.getProperty("mumbai.requireWorld", "false"));
    }

    @Test
    @DisplayName("CI must not build an APK with no world in it")
    void worldMustBePresentInCi() {
        if (!required()) {
            assertTrue(true, "local run, world optional");
            return;
        }
        assertTrue(WorldFixture.present(),
                "mumbai.requireWorld is set but no compiled world exists at "
                        + WorldFixture.assetDir().toAbsolutePath()
                        + " - refusing to ship an empty city");
        assertNotNull(WorldFixture.world(), WorldFixture.failure());
    }

    @Test
    @DisplayName("the attribution OpenStreetMap requires is actually carried")
    void attributionIsPresent() {
        WorldData w = WorldFixture.world();
        String m = w.manifestJson();
        assertTrue(m.contains("OpenStreetMap"),
                "the data is OSM derived, so the manifest must name it");
        assertTrue(m.contains("ODbL"),
                "the ODbL licence must be recorded with the data");
        assertTrue(m.contains("Copernicus"),
                "the elevation came from Copernicus and must be credited");
    }

    @Test
    @DisplayName("the shipped world is the real one, not the self-test stub")
    void notTheSelfTestWorld() {
        WorldData w = WorldFixture.world();
        if (required()) {
            assertFalse(w.selfTest,
                    "this is the synthetic self-test world, not real map data");
        }
        assertTrue(w.places().size() > 200,
                "only " + w.places().size() + " named places - that is not Mumbai");
        assertTrue(w.roadNameCount() > 2000,
                "only " + w.roadNameCount() + " road names in the table");
    }

    @Test
    @DisplayName("chunks decode, and what comes out is real geometry")
    void chunksDecode() throws IOException {
        WorldData w = WorldFixture.world();
        int checked = 0;
        long buildings = 0;
        long roads = 0;
        for (int id = 0; id < w.chunkCount() && checked < 60; id++) {
            if (!w.chunkHasData(id)) { continue; }
            byte[] raw = w.readChunk(id);
            assertNotNull(raw, "chunk " + id + " decoded to null");
            assertTrue(raw.length > 0, "chunk " + id + " decoded to nothing");
            ChunkFeatures f = ChunkFeatures.decode(id, w.chunkOriginX(id),
                    w.chunkOriginZ(id), raw, w.quant);
            buildings += f.buildingClass.length;
            roads += f.roadClass.length;
            for (int b = 0; b < f.buildingClass.length; b++) {
                assertTrue(f.buildingHeight[b] >= 2.0 && f.buildingHeight[b] <= 450,
                        "building height " + f.buildingHeight[b] + " m in chunk " + id);
            }
            checked++;
        }
        assertTrue(checked >= 40, "only found " + checked + " non-empty chunks");
        assertTrue(buildings > 2000,
                "60 chunks held only " + buildings + " buildings");
        assertTrue(roads > 500, "60 chunks held only " + roads + " road runs");
    }

    @Test
    @DisplayName("the road graph is big enough to drive across the city")
    void roadGraph() {
        WorldData w = WorldFixture.world();
        int nodes = w.graphNodeXZ.length / 2;
        int edges = w.graphEdge.length / 2;
        assertTrue(nodes > 100000, "only " + nodes + " graph nodes");
        assertTrue(edges > 100000, "only " + edges + " graph edges");
        double km = 0;
        for (int e = 0; e < edges; e++) {
            int a = w.graphEdge[e * 2], b = w.graphEdge[e * 2 + 1];
            km += Math.hypot(w.graphNodeXZ[b * 2] - w.graphNodeXZ[a * 2],
                    w.graphNodeXZ[b * 2 + 1] - w.graphNodeXZ[a * 2 + 1]);
        }
        km /= 1000.0;
        assertTrue(km > 3000, "only " + km + " km of road centreline");
    }
}
