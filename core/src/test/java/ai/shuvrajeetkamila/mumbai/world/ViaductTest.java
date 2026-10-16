package ai.shuvrajeetkamila.mumbai.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The elevated railway has to be made of something.
 *
 * <p>Three hundred and eighty-two kilometres of Metro and monorail
 * guideway were drawn on screen and then driven straight through,
 * columns and all. The deck and the piers came from the mesher and the
 * collision grid never heard about them.
 */
final class ViaductTest {

    @Test
    @DisplayName("pier positions do not depend on who is asking")
    void piersAreAPureFunctionOfTheAlignment() throws Exception {
        WorldData w = WorldFixture.world();
        // Find a route with a decent run of viaduct on it.
        int route = -1;
        int most = 0;
        for (int r = 0; r < w.transitCount(); r++) {
            int[] n = {0};
            Viaduct.piers(w, r, -1e9, -1e9, 1e9, 1e9, (x, z, t, b, fx, fz) -> n[0]++);
            if (n[0] > most) { most = n[0]; route = r; }
        }
        assertTrue(most > 20, "no route has a viaduct on it, most was " + most);

        // Collect every pier in one sweep.
        java.util.List<String> whole = new java.util.ArrayList<>();
        double[] bounds = {1e18, 1e18, -1e18, -1e18};
        Viaduct.piers(w, route, -1e9, -1e9, 1e9, 1e9, (x, z, t, b, fx, fz) -> {
            whole.add(String.format("%.2f,%.2f,%.2f", x, z, t));
            bounds[0] = Math.min(bounds[0], x);
            bounds[1] = Math.min(bounds[1], z);
            bounds[2] = Math.max(bounds[2], x);
            bounds[3] = Math.max(bounds[3], z);
        });

        // Now sweep the same route in a grid of tiles, as the chunk
        // builder does, and demand the identical set. The mesher used to
        // carry a running counter that only advanced over segments near
        // the chunk being built, so a viaduct crossing a chunk boundary
        // got its piers in different places depending on the asker, and
        // they doubled up or left a gap at every seam.
        java.util.List<String> tiled = new java.util.ArrayList<>();
        double step = 1024.0;
        for (double x = bounds[0] - step; x < bounds[2] + step; x += step) {
            for (double z = bounds[1] - step; z < bounds[3] + step; z += step) {
                final double x0 = x, z0 = z;
                Viaduct.piers(w, route, x0, z0, x0 + step, z0 + step,
                        (px, pz, t, b, fx, fz) ->
                                tiled.add(String.format("%.2f,%.2f,%.2f", px, pz, t)));
            }
        }
        // Sets, not lists. Both real callers query a box slightly larger
        // than their chunk so that geometry on the seam is never dropped,
        // which means a pier sitting on a boundary is legitimately
        // reported to both neighbours. What must not happen is a pier
        // appearing in one tiling and not the other.
        java.util.Set<String> a = new java.util.TreeSet<>(whole);
        java.util.Set<String> b = new java.util.TreeSet<>(tiled);
        java.util.Set<String> missing = new java.util.TreeSet<>(a);
        missing.removeAll(b);
        java.util.Set<String> extra = new java.util.TreeSet<>(b);
        extra.removeAll(a);
        assertTrue(missing.isEmpty(),
                missing.size() + " piers vanished when asked tile by tile");
        assertTrue(extra.isEmpty(),
                extra.size() + " piers appeared from nowhere when asked"
                        + " tile by tile");
        assertTrue(whole.size() > 200,
                "expected a long viaduct to test, got " + whole.size() + " piers");
    }

    @Test
    @DisplayName("you cannot drive through a viaduct pier")
    void piersAreSolid() throws Exception {
        WorldData w = WorldFixture.world();
        CityCollision cc = new CityCollision(w);
        double[] out = new double[2];
        int tested = 0, solid = 0;
        java.util.Random rnd = new java.util.Random(11);
        for (int r = 0; r < w.transitCount() && tested < 60; r++) {
            java.util.List<double[]> ps = new java.util.ArrayList<>();
            Viaduct.piers(w, r, -1e9, -1e9, 1e9, 1e9,
                    (x, z, t, b, fx, fz) -> ps.add(new double[]{x, z, t, b}));
            if (ps.isEmpty()) { continue; }
            double[] p = ps.get(rnd.nextInt(ps.size()));
            tested++;
            // Dead centre of the column, at the height of a car's body.
            if (cc.wallPush(p[0], p[1], p[3] + 1.0, 1.4, out) > 0) { solid++; }
        }
        assertTrue(tested > 20, "found almost no piers to test, " + tested);
        assertEquals(tested, solid,
                solid + " of " + tested + " sampled piers were solid");
    }

    @Test
    @DisplayName("the clear span between piers is still clear")
    void thereIsRoomToDriveBetweenThem() throws Exception {
        WorldData w = WorldFixture.world();
        CityCollision cc = new CityCollision(w);
        double[] out = new double[2];
        // Halfway between two consecutive piers on the same route there
        // must be nothing, or the viaduct is a wall rather than a bridge.
        int open = 0, checked = 0;
        for (int r = 0; r < w.transitCount() && checked < 25; r++) {
            java.util.List<double[]> ps = new java.util.ArrayList<>();
            Viaduct.piers(w, r, -1e9, -1e9, 1e9, 1e9,
                    (x, z, t, b, fx, fz) -> ps.add(new double[]{x, z, t, b, fx, fz}));
            for (int i = 1; i < ps.size() && checked < 25; i++) {
                double[] a = ps.get(i - 1), bb = ps.get(i);
                double d = Math.hypot(bb[0] - a[0], bb[1] - a[1]);
                // Only consecutive spans, not the jump between two
                // separate stretches of structure.
                if (d < Viaduct.PIER_SPACING_M * 0.9
                        || d > Viaduct.PIER_SPACING_M * 1.1) { continue; }
                checked++;
                double mx = (a[0] + bb[0]) * 0.5, mz = (a[1] + bb[1]) * 0.5;
                // No building there either, or this proves nothing.
                if (cc.wallPush(mx, mz, a[3] + 1.0, 0.9, out) == 0) { open++; }
            }
        }
        assertTrue(checked > 10, "not enough spans to check, " + checked);
        assertTrue(open > checked * 0.7,
                "only " + open + " of " + checked + " spans had room to drive"
                        + " between the piers");
    }
}
