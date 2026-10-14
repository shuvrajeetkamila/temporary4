package ai.shuvrajeetkamila.mumbai.android;

import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.world.ChunkMesher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which way the props face.
 *
 * <p>The renderer culls back faces with CCW front-facing, so a triangle
 * wound against the normal its vertices carry is drawn only from the side
 * nobody is looking from. A third of every prop in {@code Meshes} was in
 * that state - two faces of every box - so every vehicle, pedestrian,
 * ship and train was partly inside out. The blob shadows were worse:
 * every triangle faced downwards, which meant the only thing that could
 * see them was the ground they were drawn on.
 *
 * <p>This module had no unit tests at all before this one, which is why
 * none of it was caught. The world mesher has the same check in
 * {@code MeshWindingTest}.
 */
final class MeshFacingTest {

    private static final int S = ChunkMesher.STRIDE;

    /** How many triangles disagree with the normal they are shaded by. */
    private static int backwards(float[] v, int[] idx) {
        int wrong = 0;
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t] * S;
            int b = idx[t + 1] * S;
            int c = idx[t + 2] * S;
            double ux = v[b] - v[a];
            double uy = v[b + 1] - v[a + 1];
            double uz = v[b + 2] - v[a + 2];
            double vx = v[c] - v[a];
            double vy = v[c + 1] - v[a + 1];
            double vz = v[c + 2] - v[a + 2];
            double gx = uy * vz - uz * vy;
            double gy = uz * vx - ux * vz;
            double gz = ux * vy - uy * vx;
            if (Math.sqrt(gx * gx + gy * gy + gz * gz) < 1e-9) { continue; }
            if (gx * v[a + 3] + gy * v[a + 4] + gz * v[a + 5] < 0) { wrong++; }
        }
        return wrong;
    }

    private static int triangles(int[] idx) { return idx.length / 3; }

    @Test
    @DisplayName("every vehicle is the right way out")
    void vehiclesFaceOutwards() {
        int total = 0;
        for (VehicleKind k : VehicleKind.values()) {
            float[] v = Meshes.vehicleVertices(k);
            int[] i = Meshes.vehicleIndices(k);
            int wrong = backwards(v, i);
            assertTrue(wrong == 0,
                    k + " has " + wrong + " of " + triangles(i)
                            + " triangles wound against their own normal");
            total += triangles(i);
        }
        assertTrue(total > 500, "only " + total + " vehicle triangles in total");
    }

    @Test
    @DisplayName("every pedestrian is the right way out")
    void pedestriansFaceOutwards() {
        for (int o = 0; o < Meshes.PED_OUTFITS; o++) {
            int wrong = backwards(Meshes.pedVertices(o), Meshes.pedIndices(o));
            assertTrue(wrong == 0, "outfit " + o + " has " + wrong + " backwards");
        }
    }

    @Test
    @DisplayName("boats, helicopters and aeroplanes are the right way out")
    void craftFaceOutwards() {
        for (int i = 0; i < Meshes.CRAFT_COUNT; i++) {
            int wrong = backwards(Meshes.craftVertices(i), Meshes.craftIndices(i));
            assertTrue(wrong == 0, "craft " + i + " has " + wrong + " backwards");
        }
    }

    @Test
    @DisplayName("trains, metros and ships are the right way out")
    void transitFacesOutwards() {
        for (int i = 0; i < Meshes.TRANSIT_COUNT; i++) {
            int wrong = backwards(Meshes.transitVertices(i),
                    Meshes.transitIndices(i));
            assertTrue(wrong == 0, "transit " + i + " has " + wrong + " backwards");
        }
    }

    @Test
    @DisplayName("a blob shadow faces the sky, not the floor it is drawn on")
    void shadowsFaceUp() {
        for (int i = 0; i < Meshes.SHADOW_COUNT; i++) {
            float[] v = Meshes.shadowVertices(i);
            int[] idx = Meshes.shadowIndices(i);
            assertTrue(backwards(v, idx) == 0,
                    "shadow " + i + " is wound face-down");
            // And the normal really is up, or it would be shaded as a wall.
            for (int k = 0; k < v.length / S; k++) {
                assertTrue(v[k * S + 4] > 0.9f, "a shadow vertex is not facing up");
            }
        }
    }

    @Test
    @DisplayName("a pedestrian is person-sized")
    void pedestriansAreTheRightSize() {
        float[] v = Meshes.pedVertices(0);
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        float wide = 0;
        for (int k = 0; k < v.length / S; k++) {
            lo = Math.min(lo, v[k * S + 1]);
            hi = Math.max(hi, v[k * S + 1]);
            wide = Math.max(wide, Math.abs(v[k * S]));
        }
        assertTrue(lo > -0.05 && lo < 0.05, "feet at y=" + lo);
        assertTrue(hi > 1.4 && hi < 1.95, "head at y=" + hi);
        assertTrue(wide < 0.45, "shoulders " + (wide * 2) + " m across");
    }

    @Test
    @DisplayName("there is a mesh for every outfit the simulation hands out")
    void everyOutfitHasAMesh() {
        assertTrue(ai.shuvrajeetkamila.mumbai.sim.Agents.OUTFITS
                        <= Meshes.PED_OUTFITS,
                "the simulation issues "
                        + ai.shuvrajeetkamila.mumbai.sim.Agents.OUTFITS
                        + " outfits and there are only " + Meshes.PED_OUTFITS
                        + " meshes");
    }
}
