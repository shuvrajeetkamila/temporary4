package ai.shuvrajeetkamila.mumbai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ai.shuvrajeetkamila.mumbai.world.MeshBuilder;
import ai.shuvrajeetkamila.mumbai.world.ProtoMeshes;

/**
 * The vehicles are generated, not imported, so the only way to know they are sane is
 * to check the numbers: every mesh must be closed enough to draw, correctly sized for
 * the physics body that uses it, and free of degenerate or non-finite vertices.
 */
public class ProtoMeshTest {

    private void check(String what, ProtoMeshes.Mesh m, float minRadius, float maxRadius) {
        assertTrue(what + " has no geometry", m.triangles > 0);
        assertEquals(what + " vertex array is not a whole number of vertices",
                0, m.verts.length % MeshBuilder.STRIDE);
        assertEquals(what + " index count is not a whole number of triangles",
                0, m.indices.length % 3);
        assertEquals(what + " triangle count disagrees with the index array",
                m.indices.length / 3, m.triangles);
        int vertices = m.verts.length / MeshBuilder.STRIDE;
        for (int i : m.indices) {
            assertTrue(what + " index " + i + " is out of range", i >= 0 && i < vertices);
        }
        for (float f : m.verts) {
            assertTrue(what + " has a non-finite vertex component", Float.isFinite(f));
        }
        for (int v = 0; v < vertices; v++) {
            int o = v * MeshBuilder.STRIDE;
            float nx = m.verts[o + 3];
            float ny = m.verts[o + 4];
            float nz = m.verts[o + 5];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            assertTrue(what + " has an unnormalised normal of length " + len,
                    Math.abs(len - 1f) < 0.02f);
            for (int c = 6; c < 9; c++) {
                assertTrue(what + " colour channel out of range: " + m.verts[o + c],
                        m.verts[o + c] >= 0f && m.verts[o + c] <= 1.001f);
            }
        }
        assertTrue(what + " is " + m.radius + " m across, expected "
                + minRadius + ".." + maxRadius, m.radius > minRadius && m.radius < maxRadius);
    }

    @Test
    public void everyAmbientVehicleIsWellFormed() {
        String[] names = {"hatchback", "sedan", "taxi", "autorickshaw", "bus", "truck",
                "motorcycle"};
        for (int k = 0; k < ProtoMeshes.VEHICLE_KINDS; k++) {
            check(names[k], ProtoMeshes.vehicle(k), 0.6f, 12f);
        }
    }

    @Test
    public void thePlayerActorsAreWellFormed() {
        check("player car", ProtoMeshes.playerCar(), 1.5f, 5f);
        check("human", ProtoMeshes.human(), 0.8f, 2.2f);
        check("launch", ProtoMeshes.boat(7.8f, 2.6f, 1.5f, 0x2E5C8A, true), 2f, 8f);
        check("ferry", ProtoMeshes.boat(34f, 9f, 5f, 0x1F4E5F, true), 10f, 30f);
        check("helicopter", ProtoMeshes.helicopter(), 3f, 12f);
        check("trainer", ProtoMeshes.aeroplane(11f, 8.3f, false), 3f, 12f);
        check("airliner", ProtoMeshes.aeroplane(34f, 37f, true), 12f, 40f);
    }

    @Test
    public void meshesSitOnTheGroundPlaneNotThroughIt() {
        // The body frame has its origin on the ground under the centre of mass, so a
        // wheeled vehicle must not have geometry far below zero or it will look sunk.
        for (int k = 0; k < ProtoMeshes.VEHICLE_KINDS; k++) {
            ProtoMeshes.Mesh m = ProtoMeshes.vehicle(k);
            float lowest = Float.MAX_VALUE;
            for (int i = 1; i < m.verts.length; i += MeshBuilder.STRIDE) {
                lowest = Math.min(lowest, m.verts[i]);
            }
            assertTrue("vehicle kind " + k + " has geometry " + lowest + " m underground",
                    lowest > -0.05f);
        }
    }
}
