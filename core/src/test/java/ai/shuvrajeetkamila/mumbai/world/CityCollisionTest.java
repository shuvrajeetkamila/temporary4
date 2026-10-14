package ai.shuvrajeetkamila.mumbai.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index that makes the city solid.
 *
 * <p>Before this existed the only question a body could ask the world was
 * how high the ground was, so all 195,743 buildings were scenery and every
 * bridge was a painting: the Sea Link was drawn as an arch over the water
 * and the ground probe went on returning the seabed underneath it.
 */
final class CityCollisionTest {

    private static ChunkFeatures features(WorldData w, int id) throws IOException {
        return ChunkFeatures.decode(id, w.chunkOriginX(id), w.chunkOriginZ(id),
                w.readChunk(id), w.quant);
    }

    /** A chunk with plenty of buildings in it. */
    private static int denseChunk(WorldData w) throws IOException {
        int best = -1;
        int most = 0;
        for (int id = 0; id < w.chunkCount(); id++) {
            if (!w.chunkHasData(id)) { continue; }
            int n = w.chunkBuildingCount(id);
            if (n > most) { most = n; best = id; }
            if (most > 400) { break; }
        }
        assertTrue(best >= 0 && most > 50, "no dense chunk: best had " + most);
        return best;
    }

    @Test
    @DisplayName("a building footprint is solid where its walls are")
    void wallsAreSolid() throws IOException {
        WorldData w = WorldFixture.world();
        int id = denseChunk(w);
        ChunkFeatures f = features(w, id);
        CityCollision cc = new CityCollision(w);

        // Walk the ring of a real building and check every edge pushes back.
        int b = 0;
        for (int i = 0; i < f.buildingCount(); i++) {
            if (f.buildingOffset[i + 1] - f.buildingOffset[i] >= 4
                    && f.buildingHeight[i] > 6) { b = i; break; }
        }
        int s = f.buildingOffset[b];
        int e = f.buildingOffset[b + 1];
        double ox = w.chunkOriginX(id);
        double oz = w.chunkOriginZ(id);
        double[] out = new double[2];
        int solid = 0;
        for (int k = s; k + 1 < e; k++) {
            double mx = ox + (f.buildingXZ[k * 2] + f.buildingXZ[(k + 1) * 2]) * 0.5;
            double mz = oz + (f.buildingXZ[k * 2 + 1]
                    + f.buildingXZ[(k + 1) * 2 + 1]) * 0.5;
            double y = w.height(mx, mz) + 1.0;
            if (cc.wallPush(mx, mz, y, 0.4, out) > 0) { solid++; }
        }
        assertTrue(solid >= (e - s - 1),
                "only " + solid + " of " + (e - s - 1) + " wall midpoints are solid");
    }

    @Test
    @DisplayName("the push points away from the wall, not along it")
    void thePushIsANormal() throws IOException {
        WorldData w = WorldFixture.world();
        int id = denseChunk(w);
        ChunkFeatures f = features(w, id);
        CityCollision cc = new CityCollision(w);
        double ox = w.chunkOriginX(id);
        double oz = w.chunkOriginZ(id);
        double[] out = new double[2];
        int checked = 0;
        for (int b = 0; b < f.buildingCount() && checked < 25; b++) {
            int s = f.buildingOffset[b];
            int e = f.buildingOffset[b + 1];
            if (e - s < 4) { continue; }
            double ax = ox + f.buildingXZ[s * 2];
            double az = oz + f.buildingXZ[s * 2 + 1];
            double bx = ox + f.buildingXZ[(s + 1) * 2];
            double bz = oz + f.buildingXZ[(s + 1) * 2 + 1];
            double ex = bx - ax, ez = bz - az;
            double len = Math.hypot(ex, ez);
            if (len < 2) { continue; }
            ex /= len;
            ez /= len;
            // Stand just off the wall on its normal and check we are pushed
            // back the way we came, not sideways along the face.
            double mx = (ax + bx) * 0.5 + ez * 0.25;
            double mz = (az + bz) * 0.5 - ex * 0.25;
            double y = w.height(mx, mz) + 1.0;
            if (cc.wallPush(mx, mz, y, 0.4, out) <= 0) { continue; }
            double along = Math.abs(out[0] * ex + out[1] * ez);
            double across = Math.abs(out[0] * ez - out[1] * ex);
            assertTrue(across >= along,
                    "push is more along the wall than across it: "
                            + across + " vs " + along);
            checked++;
        }
        assertTrue(checked > 5, "only checked " + checked + " walls");
    }

    @Test
    @DisplayName("open street is not solid")
    void thereIsStillSomewhereToDrive() {
        WorldData w = WorldFixture.world();
        CityCollision cc = new CityCollision(w);
        // The middle of the Arabian Sea, which has no buildings in it.
        assertTrue(!cc.blocked(-12000, 14700, 1.0, 1.0),
                "the open sea is solid");
    }

    @Test
    @DisplayName("a wall is solid from below its base to its roof")
    void wallsReachTheGroundAndTheRoof() throws IOException {
        WorldData w = WorldFixture.world();
        int id = denseChunk(w);
        ChunkFeatures f = features(w, id);
        CityCollision cc = new CityCollision(w);
        double ox = w.chunkOriginX(id);
        double oz = w.chunkOriginZ(id);
        int b = -1;
        for (int i = 0; i < f.buildingCount(); i++) {
            if (f.buildingHeight[i] > 20 && f.buildingMinHeight[i] < 0.5
                    && f.buildingOffset[i + 1] - f.buildingOffset[i] >= 4) {
                b = i;
                break;
            }
        }
        if (b < 0) { return; }
        int s = f.buildingOffset[b];
        double mx = ox + (f.buildingXZ[s * 2] + f.buildingXZ[(s + 1) * 2]) * 0.5;
        double mz = oz + (f.buildingXZ[s * 2 + 1]
                + f.buildingXZ[(s + 1) * 2 + 1]) * 0.5;
        double g = w.height(mx, mz);
        // At head height and most of the way up, solid.
        assertTrue(cc.blocked(mx, mz, g + 1.0, 0.4), "not solid at head height");
        assertTrue(cc.blocked(mx, mz, g + f.buildingHeight[b] * 0.7, 0.4),
                "not solid two thirds of the way up");
        // Well above the roof, not solid: a helicopter can fly over it.
        assertTrue(!cc.blocked(mx, mz, g + f.buildingHeight[b] + 40, 0.4),
                "still solid forty metres above the roof");
    }

    @Test
    @DisplayName("a bridge has a deck you can stand on")
    void bridgesHaveDecks() throws IOException {
        WorldData w = WorldFixture.world();
        CityCollision cc = new CityCollision(w);
        int found = 0;
        double bestRise = 0;
        for (int id = 0; id < w.chunkCount() && found < 400; id++) {
            if (!w.chunkHasData(id)) { continue; }
            ChunkFeatures f = features(w, id);
            double ox = w.chunkOriginX(id);
            double oz = w.chunkOriginZ(id);
            for (int r = 0; r < f.roadCount(); r++) {
                if ((f.roadFlags[r] & ChunkFeatures.FLAG_BRIDGE) == 0) { continue; }
                int s = f.roadOffset[r];
                int e = f.roadOffset[r + 1];
                if (e - s < 2 || f.roadLength(r) < 60) { continue; }
                int mid = (s + e) / 2;
                double mx = ox + f.roadXZ[mid * 2];
                double mz = oz + f.roadXZ[mid * 2 + 1];
                double terrain = w.isLand(mx, mz) ? w.height(mx, mz) : 0;
                double deck = cc.deckAt(mx, mz, terrain + 3.0);
                if (deck != Double.NEGATIVE_INFINITY) {
                    found++;
                    bestRise = Math.max(bestRise, deck - terrain);
                }
            }
        }
        assertTrue(found > 20, "only " + found + " bridge decks in the whole city");
        assertTrue(bestRise > 2.0,
                "the highest bridge deck is only " + bestRise + " m over the ground");
    }

    @Test
    @DisplayName("the deck the physics finds is the deck the mesher draws")
    void theDeckMatchesTheDrawnBridge() {
        // One formula, shared. The point of RoadDeck existing at all.
        double y0 = 3.0;
        double y1 = 5.0;
        assertEquals(y0 + RoadDeck.ARCH_M * Math.sin(Math.PI * 0.5),
                RoadDeck.height(y0, y0, 0.5, -50), 1e-9);
        // Never closer than the clearance to whatever is underneath.
        assertEquals(20 + RoadDeck.MIN_CLEARANCE_M,
                RoadDeck.height(y0, y1, 0.0, 20), 1e-9);
        // At the abutments it meets the ground it started from.
        assertEquals(y0, RoadDeck.height(y0, y1, 0.0, y0 - RoadDeck.MIN_CLEARANCE_M),
                1e-9);
    }

    @Test
    @DisplayName("under a bridge is under it, not on it")
    void youCanDriveBeneathABridge() throws IOException {
        WorldData w = WorldFixture.world();
        CityCollision cc = new CityCollision(w);
        for (int id = 0; id < w.chunkCount(); id++) {
            if (!w.chunkHasData(id)) { continue; }
            ChunkFeatures f = features(w, id);
            for (int r = 0; r < f.roadCount(); r++) {
                if ((f.roadFlags[r] & ChunkFeatures.FLAG_BRIDGE) == 0) { continue; }
                int s = f.roadOffset[r];
                int e = f.roadOffset[r + 1];
                if (e - s < 3 || f.roadLength(r) < 120) { continue; }
                int mid = (s + e) / 2;
                double mx = w.chunkOriginX(id) + f.roadXZ[mid * 2];
                double mz = w.chunkOriginZ(id) + f.roadXZ[mid * 2 + 1];
                double terrain = w.isLand(mx, mz) ? w.height(mx, mz) : 0;
                double onDeck = cc.deckAt(mx, mz, terrain + 6.0);
                if (onDeck == Double.NEGATIVE_INFINITY) { continue; }
                // Only a bridge with real headroom. A deck sitting at the
                // minimum clearance over its own ground is a causeway: you
                // drive on it, and there is no "underneath" to be in.
                if (onDeck - terrain < 6.0) { continue; }
                // A body down at ground level must not be handed the deck.
                double below = cc.deckAt(mx, mz, terrain - 2.0);
                assertTrue(below == Double.NEGATIVE_INFINITY
                                || below <= terrain + 0.1,
                        "standing under the bridge and given its deck at "
                                + below + " against ground " + terrain);
                return;
            }
        }
    }

    @Test
    @DisplayName("the cache does not grow without bound")
    void theCacheIsBounded() {
        WorldData w = WorldFixture.world();
        CityCollision cc = new CityCollision(w);
        double[] out = new double[2];
        for (int i = 0; i < 400; i++) {
            double x = -20000 + i * 90;
            cc.wallPush(x, 14000 + i * 40, 5, 0.5, out);
        }
        assertTrue(cc.cachedChunks() <= CityCollision.CACHE,
                "cached " + cc.cachedChunks() + " chunks of collision");
        cc.clear();
        assertEquals(0, cc.cachedChunks());
    }

    @Test
    @DisplayName("querying out of bounds is quiet")
    void outOfBoundsIsEmpty() {
        WorldData w = WorldFixture.world();
        CityCollision cc = new CityCollision(w);
        double[] out = new double[2];
        assertEquals(0.0, cc.wallPush(1e9, 1e9, 0, 1, out), 0.0);
        assertTrue(cc.deckAt(1e9, 1e9, 0) == Double.NEGATIVE_INFINITY,
                "found a bridge deck outside the world");
        assertEquals(12.5, cc.surfaceAt(1e9, 1e9, 0, 12.5), 1e-9);
    }
}
