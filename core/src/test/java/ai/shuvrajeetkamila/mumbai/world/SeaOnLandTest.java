package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.physics.Ocean;
import ai.shuvrajeetkamila.mumbai.render.SeaMask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sea drawn over the city.
 *
 * <p>Reported from play: after a while the view gradually goes
 * underwater, and flat ground develops shimmering lines. Measured, the
 * cause is not subtle. The tide swings a shade under two metres either
 * side of mean level, which is what Mumbai Harbour really does, and in
 * the 20 m terrain model six per cent of the land is below that line.
 * At high water the sea surface was simply above the ground: open water
 * over the low reclaimed land, and where the two surfaces passed within
 * a few centimetres of each other they fought for the depth buffer and
 * striped the road.
 *
 * <p>The settlement is that the land mask already is, and has always
 * been, the single authority for where the coast is - the boat physics
 * has used it from the start. The renderer now reads the same mask and
 * refuses to paint sea on land.
 */
final class SeaOnLandTest {

    @Test
    @DisplayName("the terrain really does sit below high water")
    void theProblemIsReal() {
        // Documents the measurement the fix is based on. If a future DEM
        // raises the low ground this test is the place that says so.
        WorldData w = WorldFixture.world();
        Ocean o = new Ocean();
        double highWater = 0;
        for (double t = 0; t < 50000; t += 600) {
            o.setTime(t);
            highWater = Math.max(highWater, o.tide());
        }
        assertTrue(highWater > 1.5,
                "the tide barely moves (" + highWater + " m), so this test"
                        + " is no longer describing the game");

        long land = 0, flooded = 0;
        double lowest = Double.MAX_VALUE;
        for (double x = -26000; x < 26000; x += 240) {
            for (double z = -36000; z < 36000; z += 240) {
                if (!w.inBounds(x, z) || !w.isLand(x, z)) { continue; }
                land++;
                double h = w.height(x, z);
                lowest = Math.min(lowest, h);
                if (h < highWater) { flooded++; }
            }
        }
        assertTrue(land > 1000, "no land was sampled");
        assertTrue(lowest < highWater,
                "nothing is below high water any more, so the sea could"
                        + " safely be drawn without the mask");
        double share = 100.0 * flooded / land;
        assertTrue(share > 1.0,
                "only " + share + "% of land is below high water");
    }

    @Test
    @DisplayName("the mask the renderer gets agrees with the one physics uses")
    void theMaskAgreesWithTheWorld() {
        // The whole fix rests on these being the same mask. If the
        // texture said water where the physics says land, the sea would
        // be drawn over that spot and the player would be driving
        // through the sea again.
        WorldData w = WorldFixture.world();
        int[] size = new int[2];
        byte[] mask = w.landMaskTexels(40.0, size);
        int tw = size[0], th = size[1];
        assertTrue(tw > 100 && th > 100, "mask is " + tw + "x" + th);
        assertEquals(tw * th, mask.length);

        double sx = w.maskSpanX() / tw;
        double sz = w.maskSpanZ() / th;
        int checked = 0, disagreed = 0;
        for (int ty = 1; ty < th - 1; ty += 7) {
            for (int tx = 1; tx < tw - 1; tx += 7) {
                // Mid-texel, so this is not a test of rounding.
                double x = w.minX + (tx + 0.5) * sx;
                double z = w.minZ + (ty + 0.5) * sz;
                boolean texLand = (mask[ty * tw + tx] & 0xFF) > 127;
                boolean realLand = w.isLand(x, z);
                checked++;
                // The texel is coarser than the mask, and land wins
                // inside a texel, so the texture may say land where a
                // single point says water. The reverse must never happen:
                // that is sea drawn over ground.
                if (realLand && !texLand) { disagreed++; }
            }
        }
        assertTrue(checked > 1000, "only checked " + checked + " texels");
        assertEquals(0, disagreed,
                disagreed + " of " + checked + " places are land in the"
                        + " world and water in the texture the sea shader"
                        + " samples. The sea would be drawn over them.");
    }

    @Test
    @DisplayName("the mask is conservative: land wins inside a texel")
    void landWinsInsideATexel() {
        WorldData w = WorldFixture.world();
        int[] fine = new int[2];
        int[] coarse = new int[2];
        byte[] a = w.landMaskTexels(20.0, fine);
        byte[] b = w.landMaskTexels(80.0, coarse);
        int fineLand = 0;
        for (byte v : a) { if ((v & 0xFF) > 127) { fineLand++; } }
        int coarseLand = 0;
        for (byte v : b) { if ((v & 0xFF) > 127) { coarseLand++; } }
        double fineArea = fineLand * (w.maskSpanX() / fine[0])
                * (w.maskSpanZ() / fine[1]);
        double coarseArea = coarseLand * (w.maskSpanX() / coarse[0])
                * (w.maskSpanZ() / coarse[1]);
        assertTrue(coarseArea >= fineArea * 0.999,
                "the coarse mask covers less land (" + coarseArea / 1e6
                        + " km2) than the fine one (" + fineArea / 1e6
                        + " km2), so coarsening it opens holes for the sea");
        assertTrue(coarseArea < fineArea * 1.9,
                "the coarse mask has bloated the coastline to "
                        + coarseArea / 1e6 + " km2");
    }

    @Test
    @DisplayName("the mask covers the whole world, with the sea outside it")
    void theMaskCoversTheWorld() {
        WorldData w = WorldFixture.world();
        int[] size = new int[2];
        byte[] mask = w.landMaskTexels(40.0, size);
        // The transform the shader uses must land inside the texture for
        // anywhere the player can be, or the clamp will smear the edge
        // texel across the map.
        assertTrue(w.maskSpanX() > 40000, "mask spans " + w.maskSpanX() + " m");
        assertTrue(w.maskSpanZ() > 60000, "mask spans " + w.maskSpanZ() + " m");

        int tw = size[0], th = size[1];
        // The corners of the area of interest are open sea or buffer, and
        // must read as water so the ocean is drawn out to the horizon.
        assertEquals(0, mask[0] & 0xFF, "the north-west corner is land");
        assertEquals(0, mask[tw - 1] & 0xFF, "the north-east corner is land");
        assertEquals(0, mask[(th - 1) * tw] & 0xFF,
                "the south-west corner is land");
    }

    @Test
    @DisplayName("the mask follows the floating origin")
    void theMaskRebasesWithTheOrigin() {
        // The sea shader works in render space. If the mask's transform
        // were built from world constants alone, everything would look
        // right until the player drove far enough to rebase the origin,
        // and then the coastline would slide out from under the sea -
        // the hardest kind of bug to catch, because it needs a long
        // drive to appear.
        WorldData w = WorldFixture.world();
        float[] a = new float[4];
        float[] b = new float[4];
        SeaMask.transform(w, 0, 0, a);

        double worldX = 1234.5, worldZ = -4321.0;
        double u0 = SeaMask.u(a, worldX);
        double v0 = SeaMask.v(a, worldZ);
        assertTrue(u0 > 0 && u0 < 1, "u was " + u0);
        assertTrue(v0 > 0 && v0 < 1, "v was " + v0);

        // Now rebase a long way, as a flight across the city would.
        double offX = 18000, offZ = -24000;
        SeaMask.transform(w, offX, offZ, b);
        double u1 = SeaMask.u(b, worldX - offX);
        double v1 = SeaMask.v(b, worldZ - offZ);
        assertEquals(u0, u1, 1e-6,
                "the same place on the ground sampled a different texel"
                        + " after the origin rebased");
        assertEquals(v0, v1, 1e-6,
                "the same place on the ground sampled a different texel"
                        + " after the origin rebased");
    }

    @Test
    @DisplayName("a known stretch of coast lands on the right side of the mask")
    void marineDriveIsLand() {
        WorldData w = WorldFixture.world();
        int[] size = new int[2];
        byte[] mask = w.landMaskTexels(SeaMask.TEXEL_M, size);
        float[] xf = new float[4];
        SeaMask.transform(w, 0, 0, xf);

        // Marine Drive, which is exactly the low reclaimed ground that
        // was going under at high water.
        double x = ai.shuvrajeetkamila.mumbai.geo.GeoProjection
                .toX(18.9430, 72.8230);
        double z = ai.shuvrajeetkamila.mumbai.geo.GeoProjection.toZ(18.9430);
        assertTrue(w.isLand(x, z), "the spawn point is not on land");
        assertTrue(w.height(x, z) < 4.0,
                "this test is meant to stand on low ground; it is at "
                        + w.height(x, z) + " m");

        int tx = (int) (SeaMask.u(xf, x) * size[0]);
        int ty = (int) (SeaMask.v(xf, z) * size[1]);
        assertTrue((mask[ty * size[0] + tx] & 0xFF) > 127,
                "the sea shader would draw water over the spawn point");

        // And a point well out in Back Bay must still be sea, or the
        // mask has swallowed the harbour.
        double sx = ai.shuvrajeetkamila.mumbai.geo.GeoProjection
                .toX(18.9300, 72.7900);
        double sz = ai.shuvrajeetkamila.mumbai.geo.GeoProjection.toZ(18.9300);
        assertTrue(!w.isLand(sx, sz), "the test point is not at sea");
        int sxi = (int) (SeaMask.u(xf, sx) * size[0]);
        int szi = (int) (SeaMask.v(xf, sz) * size[1]);
        assertEquals(0, mask[szi * size[0] + sxi] & 0xFF,
                "the mask calls open water land, so no sea would be drawn"
                        + " there at all");
    }
}
