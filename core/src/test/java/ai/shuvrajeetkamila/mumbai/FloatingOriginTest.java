package ai.shuvrajeetkamila.mumbai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ai.shuvrajeetkamila.mumbai.game.FloatingOrigin;
import ai.shuvrajeetkamila.mumbai.geo.GeoProjection;
import ai.shuvrajeetkamila.mumbai.world.WorldData;

/**
 * The world is 44 km by 65 km in true metres. A 32 bit float has about 24 bits of
 * mantissa, so at 45 km from the origin the smallest representable step is roughly
 * 4 mm and vertex jitter becomes visible; at 65 km it is nearly a centimetre and
 * z-fighting and shimmering start. Every position the GPU sees therefore has to be
 * expressed relative to a periodically rebased origin, while the simulation itself
 * keeps double precision world coordinates.
 *
 * <p>These tests prove three separate things:
 * <ul>
 *   <li>rebasing never changes where anything actually is;</li>
 *   <li>local coordinates stay small enough for float precision anywhere in the AOI;</li>
 *   <li>the rebase is hysteretic, so standing on a boundary does not rebase every frame.</li>
 * </ul>
 */
public class FloatingOriginTest {

    private static final double EPS = 1e-9;

    @Test
    public void rebasingPreservesEveryWorldPosition() {
        FloatingOrigin origin = new FloatingOrigin();
        origin.reset(0, 0, 0);

        // A scatter of landmarks expressed in doubles, which must survive rebasing.
        double[][] points = {
                {0, 0, 0},
                {12345.678901, 31.25, -44444.4444},
                {-20699.5, 302.0, 19674.0},
                {23599.9, 0.0, -45674.9},
        };

        double camX = 0, camZ = 0;
        for (int step = 0; step < 400; step++) {
            camX += 137.0;          // ~8 km of travel, many rebases
            camZ -= 211.0;
            origin.update(camX, 0, camZ);

            for (double[] p : points) {
                float lx = origin.localX(p[0]);
                float ly = origin.localY(p[1]);
                float lz = origin.localZ(p[2]);
                double rx = origin.worldX(lx);
                double ry = origin.worldY(ly);
                double rz = origin.worldZ(lz);
                // The round trip goes through a float, so allow float resolution at
                // the local distance involved, never more.
                double tol = Math.max(0.05, Math.abs(lx) * 1e-6);
                assertEquals("rebase moved a point in X", p[0], rx, tol);
                assertEquals("rebase moved a point in Y", p[1], ry, 0.05);
                assertEquals("rebase moved a point in Z", p[2], rz, Math.max(0.05,
                        Math.abs(lz) * 1e-6));
            }
        }
        assertTrue("the origin never rebased during 8 km of travel",
                origin.rebaseCount() > 4);
    }

    @Test
    public void cameraRelativeCoordinatesStaySmallAnywhereInTheCity() {
        FloatingOrigin origin = new FloatingOrigin();
        WorldData world = TestWorld.get();
        origin.reset(world.minX, 0, world.minZ);

        double worst = 0;
        // Walk the whole AOI on a 500 m lattice as if the player drove everywhere.
        for (double z = world.minZ; z <= world.maxZ; z += 500) {
            for (double x = world.minX; x <= world.maxX; x += 500) {
                origin.update(x, 0, z);
                worst = Math.max(worst, Math.abs(origin.localX(x)));
                worst = Math.max(worst, Math.abs(origin.localZ(z)));
            }
        }
        assertTrue("camera-local coordinates grew to " + worst
                        + " m, which float32 cannot render without jitter",
                worst < FloatingOrigin.THRESHOLD + FloatingOrigin.GRID + 1);

        // At that magnitude the float step must still be sub-millimetre.
        float f = (float) worst;
        float next = Math.nextUp(f);
        assertTrue("float resolution at the rebase radius is " + (next - f) + " m",
                next - f < 0.001f);
    }

    @Test
    public void rebasingIsHystereticAndSnapsToTheGrid() {
        FloatingOrigin origin = new FloatingOrigin();
        origin.reset(0, 0, 0);
        int before = origin.rebaseCount();

        // Sitting just inside the threshold must not rebase, however long we sit.
        for (int i = 0; i < 600; i++) {
            assertFalse("rebased while inside the hysteresis band",
                    origin.update(FloatingOrigin.THRESHOLD - 1, 0, 0));
        }
        assertEquals(before, origin.rebaseCount());

        // Crossing it rebases exactly once, onto the grid.
        assertTrue("did not rebase after crossing the threshold",
                origin.update(FloatingOrigin.THRESHOLD + 10, 0, 0));
        assertEquals("the origin must snap to the rebase grid",
                0.0, origin.x() % FloatingOrigin.GRID, EPS);
        assertFalse("rebased twice for one crossing",
                origin.update(FloatingOrigin.THRESHOLD + 11, 0, 0));
    }

    @Test
    public void physicsNeverSeesDegreesOnlyMetres() {
        // A regression guard for the single most damaging possible mistake: feeding
        // latitude and longitude into something that expects metres. One degree of
        // latitude is ~110 km, so if the projection were ever bypassed the numbers
        // below would collapse into a few hundredths instead of tens of thousands.
        GeoProjection p = TestWorld.get().projection();
        double x1 = p.worldX(19.0760, 72.8777);
        double z1 = p.worldZ(19.0760);
        double x2 = p.worldX(19.0760, 72.9777);
        double z2 = p.worldZ(19.1760);

        assertTrue("0.1 deg of longitude must be about 10.5 km, got " + (x2 - x1),
                Math.abs((x2 - x1) - 10512) < 120);
        assertTrue("0.1 deg of latitude must be about 11.06 km, got " + Math.abs(z2 - z1),
                Math.abs(Math.abs(z2 - z1) - 11061) < 120);

        // And the inverse has to land back on the same degrees.
        assertEquals(19.0760, p.toLat(z1), 1e-7);
        assertEquals(72.8777, p.toLon(x1, z1), 1e-7);
    }
}
