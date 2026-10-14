package ai.shuvrajeetkamila.mumbai.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which way the triangles face.
 *
 * <p>The renderer culls back faces with {@code GL_CCW} front-facing, so a
 * triangle whose vertex order disagrees with its own shaded normal is
 * invisible from the side it is lit on and visible from the side it is
 * not. That is exactly what the city looked like: walk up to a building
 * and the near wall is not there, and through the gap you can see the
 * inside of the far wall and the inside of the roof.
 *
 * <p>The cause is that an OSM ring can be wound either way and the mesher
 * assumed one. These tests are blunt on purpose: for every triangle the
 * mesher emits, the geometric normal from the winding must agree with the
 * normal stored on the vertices, and for a building wall both must point
 * away from the footprint.
 */
final class MeshWindingTest {

    private static final int STRIDE = ChunkMesher.STRIDE;

    private static int denseChunk(WorldData w) {
        int best = -1;
        int most = 0;
        for (int id = 0; id < w.chunkCount(); id++) {
            if (!w.chunkHasData(id)) { continue; }
            int n = w.chunkBuildingCount(id);
            if (n > most) { most = n; best = id; }
            if (most > 300) { break; }
        }
        return best;
    }

    private static ChunkFeatures features(WorldData w, int id) throws IOException {
        return ChunkFeatures.decode(id, w.chunkOriginX(id), w.chunkOriginZ(id),
                w.readChunk(id), w.quant);
    }

    /** Geometric normal of a triangle, from its winding. */
    private static double[] geoNormal(ChunkMesher.Mesh m, int a, int b, int c) {
        double ax = m.vertices[a * STRIDE], ay = m.vertices[a * STRIDE + 1];
        double az = m.vertices[a * STRIDE + 2];
        double bx = m.vertices[b * STRIDE], by = m.vertices[b * STRIDE + 1];
        double bz = m.vertices[b * STRIDE + 2];
        double cx = m.vertices[c * STRIDE], cy = m.vertices[c * STRIDE + 1];
        double cz = m.vertices[c * STRIDE + 2];
        double ux = bx - ax, uy = by - ay, uz = bz - az;
        double vx = cx - ax, vy = cy - ay, vz = cz - az;
        return new double[]{uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
    }

    @Test
    @DisplayName("every triangle faces the way it is lit")
    void windingAgreesWithTheNormal() throws IOException {
        WorldData w = WorldFixture.world();
        int id = denseChunk(w);
        assertTrue(id >= 0, "no chunk with buildings");
        ChunkFeatures f = features(w, id);
        ChunkMesher mesher = new ChunkMesher(w);

        for (int lod : new int[]{ChunkMesher.LOD_HIGH, ChunkMesher.LOD_MED,
                ChunkMesher.LOD_LOW}) {
            ChunkMesher.Mesh m = mesher.build(f, lod);
            int wrong = 0;
            int checked = 0;
            for (int t = 0; t + 2 < m.indexCount; t += 3) {
                int a = m.indices[t];
                int b = m.indices[t + 1];
                int c = m.indices[t + 2];
                double[] g = geoNormal(m, a, b, c);
                double gl = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
                if (gl < 1e-7) { continue; }        // degenerate, invisible
                double nx = m.vertices[a * STRIDE + 3];
                double ny = m.vertices[a * STRIDE + 4];
                double nz = m.vertices[a * STRIDE + 5];
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl < 1e-6) { continue; }
                double dot = (g[0] * nx + g[1] * ny + g[2] * nz) / (gl * nl);
                checked++;
                if (dot < 0) { wrong++; }
            }
            assertTrue(checked > 200, "only checked " + checked + " triangles");
            assertTrue(wrong == 0,
                    wrong + " of " + checked + " triangles at lod " + lod
                            + " are wound against their own normal");
        }
    }

    private static boolean convex(ChunkFeatures f, int b) {
        int s = f.buildingOffset[b];
        int e = f.buildingOffset[b + 1];
        int n = e - s;
        if (n < 4) { return false; }
        int sign = 0;
        for (int i = 0; i < n; i++) {
            int p = s + i;
            int q = s + (i + 1) % n;
            int r = s + (i + 2) % n;
            double ax = f.buildingXZ[q * 2] - f.buildingXZ[p * 2];
            double az = f.buildingXZ[q * 2 + 1] - f.buildingXZ[p * 2 + 1];
            double bx = f.buildingXZ[r * 2] - f.buildingXZ[q * 2];
            double bz = f.buildingXZ[r * 2 + 1] - f.buildingXZ[q * 2 + 1];
            double cross = ax * bz - az * bx;
            if (Math.abs(cross) < 1e-6) { continue; }
            int sg = cross > 0 ? 1 : -1;
            if (sign == 0) { sign = sg; } else if (sg != sign) { return false; }
        }
        return sign != 0;
    }

    private static double ringArea2(ChunkFeatures f, int b) {
        int s = f.buildingOffset[b];
        int e = f.buildingOffset[b + 1];
        int n = e - s;
        double a2 = 0;
        for (int k = 0; k < n; k++) {
            int i0 = s + k;
            int i1 = s + (k + 1) % n;
            a2 += (double) f.buildingXZ[i0 * 2] * f.buildingXZ[i1 * 2 + 1]
                    - (double) f.buildingXZ[i1 * 2] * f.buildingXZ[i0 * 2 + 1];
        }
        return a2;
    }

    /**
     * Normal the mesher gave the wall on edge {@code k} of building
     * {@code b}, found by matching <em>both</em> of the edge's corners.
     * Matching one corner is not enough: terraces share them.
     */
    private static double[] wallNormal(ChunkMesher.Mesh m, ChunkFeatures f,
                                       int b, int k) {
        int s = f.buildingOffset[b];
        int e = f.buildingOffset[b + 1];
        int n = e - s;
        float ax = f.buildingXZ[(s + k) * 2];
        float az = f.buildingXZ[(s + k) * 2 + 1];
        float bx = f.buildingXZ[(s + (k + 1) % n) * 2];
        float bz = f.buildingXZ[(s + (k + 1) % n) * 2 + 1];
        for (int t = 0; t + 2 < m.indexCount; t += 3) {
            int ia = m.indices[t];
            if (m.vertices[ia * STRIDE + 4] != 0f) { continue; }
            boolean hasA = false;
            boolean hasB = false;
            for (int j = 0; j < 3; j++) {
                int v = m.indices[t + j] * STRIDE;
                if (Math.abs(m.vertices[v] - ax) < 0.02
                        && Math.abs(m.vertices[v + 2] - az) < 0.02) { hasA = true; }
                if (Math.abs(m.vertices[v] - bx) < 0.02
                        && Math.abs(m.vertices[v + 2] - bz) < 0.02) { hasB = true; }
            }
            if (hasA && hasB) {
                return new double[]{m.vertices[ia * STRIDE + 3],
                        m.vertices[ia * STRIDE + 5]};
            }
        }
        return null;
    }

    @Test
    @DisplayName("a wall faces out of its own building, whichever way the ring runs")
    void wallsFaceOutwards() throws IOException {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        int inward = 0;
        int tested = 0;
        int reverseWound = 0;
        // Scan until both windings have been seen in quantity. Seven per
        // cent of OSM footprints come through the other way round, and a
        // chunk that happens to hold none of them proves nothing.
        for (int id = 0; id < w.chunkCount() && reverseWound < 12; id++) {
            if (!w.chunkHasData(id) || w.chunkBuildingCount(id) < 20) { continue; }
            ChunkFeatures f = features(w, id);
            ChunkMesher.Mesh m = mesher.build(f, ChunkMesher.LOD_HIGH);
            for (int b = 0; b < f.buildingCount(); b++) {
                if (!convex(f, b)) { continue; }
                boolean reverse = ringArea2(f, b) < 0;
                int s = f.buildingOffset[b];
                int e = f.buildingOffset[b + 1];
                int n = e - s;
                double cx = 0;
                double cz = 0;
                for (int k = s; k < e; k++) {
                    cx += f.buildingXZ[k * 2];
                    cz += f.buildingXZ[k * 2 + 1];
                }
                cx /= n;
                cz /= n;
                boolean any = false;
                for (int k = 0; k < n; k++) {
                    double ax = f.buildingXZ[(s + k) * 2];
                    double az = f.buildingXZ[(s + k) * 2 + 1];
                    double bx = f.buildingXZ[(s + (k + 1) % n) * 2];
                    double bz = f.buildingXZ[(s + (k + 1) % n) * 2 + 1];
                    if (Math.hypot(bx - ax, bz - az) < 1.5) { continue; }
                    double[] nn = wallNormal(m, f, b, k);
                    if (nn == null) { continue; }
                    double mx = (ax + bx) * 0.5;
                    double mz = (az + bz) * 0.5;
                    tested++;
                    any = true;
                    if (nn[0] * (mx - cx) + nn[1] * (mz - cz) < 0) { inward++; }
                }
                if (any && reverse) { reverseWound++; }
            }
        }
        assertTrue(tested > 100, "only tested " + tested + " walls");
        assertTrue(reverseWound >= 5,
                "only found " + reverseWound + " reverse-wound buildings to test");
        assertTrue(inward == 0,
                inward + " of " + tested + " wall normals point into the building ("
                        + reverseWound + " reverse-wound buildings in the sample)");
    }

    @Test
    @DisplayName("roofs face the sky")
    void roofsFaceUp() throws IOException {
        WorldData w = WorldFixture.world();
        int id = denseChunk(w);
        ChunkFeatures f = features(w, id);
        ChunkMesher.Mesh m = new ChunkMesher(w).build(f, ChunkMesher.LOD_HIGH);
        int down = 0;
        int up = 0;
        for (int t = 0; t + 2 < m.indexCount; t += 3) {
            int a = m.indices[t];
            if (m.vertices[a * STRIDE + 4] < 0.9f) { continue; }   // not a roof
            double[] g = geoNormal(m, a, m.indices[t + 1], m.indices[t + 2]);
            if (Math.abs(g[1]) < 1e-7) { continue; }
            if (g[1] > 0) { up++; } else { down++; }
        }
        assertTrue(up + down > 100, "only found " + (up + down) + " roof triangles");
        assertTrue(down == 0, down + " of " + (up + down)
                + " roof triangles are wound face-down");
    }
}
