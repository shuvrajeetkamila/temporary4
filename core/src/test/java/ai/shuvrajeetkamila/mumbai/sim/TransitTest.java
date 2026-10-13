package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trains, metros, the monorail and harbour vessels.
 *
 * <p>The properties worth pinning: a service stays on its own alignment,
 * a train bends through curves instead of being a rigid box, it reverses
 * at a terminus rather than running off the end, and nothing underground
 * is drawn at street level.
 */
final class TransitTest {

    private static final double DT = 1.0 / 60;

    // -------------------------------------------------------- pure geometry

    @Test
    @DisplayName("each kind gets a sensible consist and speed")
    void consistsAndSpeeds() {
        assertTrue(Transit.carriagesFor(WorldData.CLS_RAIL)
                        > Transit.carriagesFor(WorldData.CLS_MONORAIL),
                "a suburban rake should be longer than a monorail set");
        assertEquals(1, Transit.carriagesFor(WorldData.CLS_FERRY),
                "a ship is one hull");

        assertTrue(Transit.cruiseFor(WorldData.CLS_RAIL)
                        > Transit.cruiseFor(WorldData.CLS_MONORAIL),
                "the monorail should not outrun suburban rail");
        assertTrue(Transit.cruiseFor(WorldData.CLS_FERRY)
                        < Transit.cruiseFor(WorldData.CLS_SUBWAY),
                "a ship should be the slowest thing on the network");
        for (int cls : new int[]{WorldData.CLS_RAIL, WorldData.CLS_LIGHT_RAIL,
                WorldData.CLS_SUBWAY, WorldData.CLS_MONORAIL, WorldData.CLS_FERRY}) {
            assertTrue(Transit.cruiseFor(cls) > 0, "class " + cls + " cannot move");
            assertTrue(Transit.carriagesFor(cls) >= 1, "class " + cls + " is empty");
        }
    }

    // ---------------------------------------------------------- the network

    @Test
    @DisplayName("the compiled world carries rail, metro, monorail and ferry")
    void networkIsPresent() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Assumptions.assumeTrue(w.transitCount() > 0,
                "world predates the transit network");

        boolean rail = false, metro = false, mono = false, ferry = false;
        for (int r = 0; r < w.transitCount(); r++) {
            switch (w.transitClass(r)) {
                case WorldData.CLS_RAIL: rail = true; break;
                case WorldData.CLS_LIGHT_RAIL:
                case WorldData.CLS_SUBWAY: metro = true; break;
                case WorldData.CLS_MONORAIL: mono = true; break;
                case WorldData.CLS_FERRY: ferry = true; break;
                default: break;
            }
        }
        assertTrue(rail, "no suburban rail in the world");
        assertTrue(metro, "no metro in the world");
        assertTrue(mono, "no monorail in the world");
        assertTrue(ferry, "no ferry routes in the world");
    }

    @Test
    @DisplayName("stitching produced routes long enough to run a train on")
    void routesAreStitched() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Assumptions.assumeTrue(w.transitCount() > 0, "no transit network");

        double longest = 0;
        for (int r = 0; r < w.transitCount(); r++) {
            longest = Math.max(longest, w.transitLength(r));
            assertTrue(w.transitPointCount(r) >= 2,
                    "route " + r + " has fewer than two points");
        }
        // Unstitched OSM railway fragments average under 200 m. If the
        // chaining silently stopped working this is what would catch it.
        assertTrue(longest > 8000,
                "longest route is only " + (int) longest
                        + " m; the fragments were not stitched");
    }

    @Test
    @DisplayName("arc-length lookup is monotonic and clamps at both ends")
    void pointAtIsWellBehaved() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Assumptions.assumeTrue(w.transitCount() > 0, "no transit network");
        Transit t = new Transit(w);

        int route = -1;
        for (int r = 0; r < w.transitCount(); r++) {
            if (w.transitLength(r) > 3000) { route = r; break; }
        }
        Assumptions.assumeTrue(route >= 0, "no long route to walk");
        double len = w.transitLength(route);

        // Walking forward must always move forward along the line.
        double[] prev = t.pointAt(route, 0);
        double travelled = 0;
        for (double s = 25; s <= len; s += 25) {
            double[] p = t.pointAt(route, s);
            travelled += Math.hypot(p[0] - prev[0], p[1] - prev[1]);
            prev = p;
        }
        assertEquals(len, travelled, len * 0.02,
                "walking the route in 25 m steps did not cover its length");

        // Off either end it clamps rather than extrapolating into the sea.
        double[] before = t.pointAt(route, -500);
        double[] start = t.pointAt(route, 0);
        assertEquals(start[0], before[0], 1e-6);
        assertEquals(start[1], before[1], 1e-6);
        double[] after = t.pointAt(route, len + 5000);
        double[] end = t.pointAt(route, len);
        assertTrue(Math.hypot(after[0] - end[0], after[1] - end[1]) < 1.0,
                "the route extrapolated past its end");
    }

    // ------------------------------------------------------------- services

    @Test
    @DisplayName("services appear near the player and run")
    void servicesRun() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Assumptions.assumeTrue(w.transitCount() > 0, "no transit network");
        Engine e = new Engine(w);
        double px = e.human.position.x, pz = e.human.position.z;

        for (int i = 0; i < 60 * 60; i++) {
            e.transit.update(DT, px, pz, 3000);
        }
        assertTrue(e.transit.activeCount() > 0,
                "nothing ever ran on the transit network");

        // Everything running must be somewhere sane and actually moving.
        int moving = 0;
        for (Transit.Service s : e.transit.services()) {
            if (!s.active) { continue; }
            assertTrue(Double.isFinite(s.x) && Double.isFinite(s.z),
                    "a service left the world");
            if (s.speed > 0.1) { moving++; }
        }
        assertTrue(moving > 0, "every service was stationary");
    }

    @Test
    @DisplayName("a train stays on its own alignment")
    void servicesStayOnTheirRoute() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Assumptions.assumeTrue(w.transitCount() > 0, "no transit network");
        Engine e = new Engine(w);
        double px = e.human.position.x, pz = e.human.position.z;

        int checked = 0;
        for (int i = 0; i < 90 * 60; i++) {
            e.transit.update(DT, px, pz, 4000);
            if (i % 600 != 0) { continue; }
            for (Transit.Service s : e.transit.services()) {
                if (!s.active) { continue; }
                double best = nearestOnRoute(w, s.route, s.x, s.z);
                assertTrue(best < 6.0,
                        "a service drifted " + (int) best + " m off its route");
                checked++;
            }
        }
        Assumptions.assumeTrue(checked > 0, "no service to check");
    }

    private double nearestOnRoute(WorldData w, int route, double x, double z) {
        double best = Double.MAX_VALUE;
        int n = w.transitPointCount(route);
        for (int i = 1; i < n; i++) {
            double ax = w.transitX(route, i - 1), az = w.transitZ(route, i - 1);
            double bx = w.transitX(route, i), bz = w.transitZ(route, i);
            double ex = bx - ax, ez = bz - az;
            double l2 = ex * ex + ez * ez;
            double t = l2 < 1e-9 ? 0 : ((x - ax) * ex + (z - az) * ez) / l2;
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
            double qx = ax + ex * t, qz = az + ez * t;
            best = Math.min(best, Math.hypot(qx - x, qz - z));
        }
        return best;
    }

    @Test
    @DisplayName("a train bends through curves instead of being a rigid box")
    void carriagesFollowTheLine() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Assumptions.assumeTrue(w.transitCount() > 0, "no transit network");
        Engine e = new Engine(w);
        double px = e.human.position.x, pz = e.human.position.z;
        for (int i = 0; i < 40 * 60; i++) { e.transit.update(DT, px, pz, 4000); }

        float[] poses = new float[64];
        boolean sawTrain = false;
        for (Transit.Service s : e.transit.services()) {
            if (!s.active || s.carriages < 3) { continue; }
            sawTrain = true;
            int n = e.transit.carriagePoses(s, poses, 0, 0, 0, 0);
            assertEquals(s.carriages, n, "not every carriage was placed");

            double spacing = Transit.CAR_LEN + Transit.CAR_GAP;
            for (int c = 1; c < n; c++) {
                double dx = poses[c * 4] - poses[(c - 1) * 4];
                double dz = poses[c * 4 + 2] - poses[(c - 1) * 4 + 2];
                double gap = Math.hypot(dx, dz);
                // Straight-line spacing is at most the arc spacing, and on
                // a curve it is slightly less. Far less would mean
                // carriages piling into each other.
                assertTrue(gap <= spacing + 0.5 && gap > spacing * 0.80,
                        "carriage " + c + " sits " + gap
                                + " m from the one ahead, expected about "
                                + spacing);
                // And each one must be on the route, not cutting the corner.
                double off = nearestOnRoute(w, s.route, poses[c * 4],
                        poses[c * 4 + 2]);
                assertTrue(off < 6.0,
                        "carriage " + c + " is " + (int) off + " m off the rails");
            }
        }
        Assumptions.assumeTrue(sawTrain, "no multi-carriage service appeared");
    }

    @Test
    @DisplayName("a service reverses at the terminus instead of running off the end")
    void servicesTurnAround() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Assumptions.assumeTrue(w.transitCount() > 0, "no transit network");
        Transit t = new Transit(w);

        // Find the shortest usable route so a terminus is reached quickly.
        int route = -1;
        double len = Double.MAX_VALUE;
        for (int r = 0; r < w.transitCount(); r++) {
            double l = w.transitLength(r);
            if (l > 950 && l < len) { len = l; route = r; }
        }
        Assumptions.assumeTrue(route >= 0, "no short route to drive to its end");

        Transit.Service s = t.services()[0];
        s.active = true;
        s.route = route;
        s.cls = w.transitClass(route);
        s.vessel = WorldData.isWaterRoute(s.cls);
        s.carriages = Transit.carriagesFor(s.cls);
        s.dir = 1;
        s.targetSpeed = Transit.cruiseFor(s.cls);
        s.speed = s.targetSpeed;
        s.s = len * 0.5;

        boolean reversed = false;
        double maxS = 0, minS = Double.MAX_VALUE;
        for (int i = 0; i < 1200 * 60 && !reversed; i++) {
            t.update(DT, s.x, s.z, 1e9);
            if (!s.active) { break; }
            maxS = Math.max(maxS, s.s);
            minS = Math.min(minS, s.s);
            if (s.dir < 0) { reversed = true; }
        }
        assertTrue(reversed, "the service never turned round at the terminus");
        assertTrue(maxS <= len + 1e-6,
                "the service ran " + (maxS - len) + " m past the end of the line");
        assertTrue(minS >= 0, "the service ran off the start of the line");
    }

    @Test
    @DisplayName("the metro runs below the street and the monorail above it")
    void verticalPlacementMatchesTheMode() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Assumptions.assumeTrue(w.transitCount() > 0, "no transit network");
        Engine e = new Engine(w);
        double px = e.human.position.x, pz = e.human.position.z;
        for (int i = 0; i < 120 * 60; i++) { e.transit.update(DT, px, pz, 6000); }

        int checkedSub = 0, checkedMono = 0, checkedShip = 0;
        for (Transit.Service s : e.transit.services()) {
            if (!s.active) { continue; }
            double ground = w.height(s.x, s.z);
            if (s.cls == WorldData.CLS_SUBWAY) {
                assertTrue(s.y < ground - 5,
                        "a subway train is running at street level");
                checkedSub++;
            } else if (s.cls == WorldData.CLS_MONORAIL) {
                assertTrue(s.y > ground + 4,
                        "the monorail is running on the road surface");
                checkedMono++;
            } else if (s.vessel) {
                assertTrue(Math.abs(s.y) < 1.0,
                        "a ship is not at sea level: " + s.y);
                checkedShip++;
            }
        }
        // Not every kind is guaranteed to spawn near the spawn point, so
        // this only asserts about what did turn up.
        Assumptions.assumeTrue(checkedSub + checkedMono + checkedShip > 0,
                "no underground, elevated or floating service appeared");
    }
}
