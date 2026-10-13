package ai.shuvrajeetkamila.mumbai.game;

import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.sim.Agents;
import ai.shuvrajeetkamila.mumbai.sim.Engine;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Traffic that yields, pedestrians that move away, and the radar. */
final class LivingCityTest {

    /**
     * The bug this locks down was live for the whole project.
     *
     * <p>{@code graphAttr} is five floats per edge - class, lanes, oneway,
     * width, speed - and every consumer read slot 0 as the speed limit. Slot
     * 0 is the road class. Motorway is class 0, so the fastest roads in
     * Mumbai were given a limit of zero and spawned no traffic at all, while
     * everything else drove at its class number in km/h: a residential
     * street at 13 km/h, a trunk road at 1.
     */
    @Test
    @DisplayName("edge speed limits are real speeds, not road class numbers")
    void speedLimitsAreNotClassIds() {
        WorldData w = WorldFixture.world();
        int edges = w.edgeCount();
        assertTrue(edges > 1000, "expected a real graph, got " + edges);

        int motorways = 0;
        double fastest = 0;
        for (int e = 0; e < edges; e++) {
            double kph = w.edgeSpeedKph(e);
            int cls = w.edgeClass(e);
            assertTrue(kph > 0 && kph <= 130,
                    "edge " + e + " class " + cls + " has limit " + kph + " km/h");
            if (cls == 0) { motorways++; }
            fastest = Math.max(fastest, kph);
        }
        assertTrue(motorways > 0, "the AOI should contain motorway edges");
        assertTrue(fastest >= 60,
                "nothing in the city is faster than " + fastest
                        + " km/h, which means class is still being read as speed");
    }

    @Test
    @DisplayName("motorways are not excluded from traffic")
    void motorwaysCarryTraffic() {
        WorldData w = WorldFixture.world();
        // The old guard was 'skip if attr[0] <= 0', which skipped class 0.
        int drivableMotorways = 0;
        for (int e = 0; e < w.edgeCount(); e++) {
            if (w.edgeClass(e) == 0 && w.edgeSpeedKph(e) > 0) { drivableMotorways++; }
        }
        assertTrue(drivableMotorways > 0,
                "every motorway edge would be skipped by the traffic spawner");
    }

    /**
     * Traffic yields, tested on the geometry itself.
     *
     * <p>Against the live {@code Agents} this could not be tested at all:
     * {@code stepCar} ends by snapping the car onto its edge, so a car
     * planted at the origin is somewhere else entirely by the end of the
     * first update and the player is no longer in front of it. Pulling the
     * decision out into a pure function made it testable and needs no
     * compiled world, so these run everywhere.
     */
    @Test
    @DisplayName("a car brakes for the player standing in its lane")
    void trafficYieldsToThePlayer() {
        // Facing north, which is -Z. Player three metres in front.
        double v = Agents.desiredSpeed(0, 0, 0, 0, -3.0, 14.0);
        assertEquals(0.0, v, 1e-9, "should have stopped dead");

        // Further out it should be slowing but still moving.
        double mid = Agents.desiredSpeed(0, 0, 0, 0, -14.0, 14.0);
        assertTrue(mid > 0 && mid < 14.0, "should be easing off, got " + mid);

        // Well beyond notice range, untouched.
        assertEquals(14.0, Agents.desiredSpeed(0, 0, 0, 0, -80.0, 14.0), 1e-9);
    }

    /**
     * The test that matters. With the lateral offset computed as a sum of
     * products rather than a cross product, every axis-aligned case passes
     * and every diagonal road in the city silently stops yielding - which
     * is most of them.
     */
    @Test
    @DisplayName("a car on a diagonal heading still brakes for the player")
    void trafficYieldsOnDiagonalHeadings() {
        for (double h : new double[]{0, Math.PI / 6, Math.PI / 4, 2.1, -1.3, 4.7}) {
            double px = Math.sin(h) * 3.0;
            double pz = -Math.cos(h) * 3.0;
            double v = Agents.desiredSpeed(0, 0, h, px, pz, 14.0);
            assertEquals(0.0, v, 1e-9,
                    "on heading " + h + " the car failed to brake");
        }
    }

    @Test
    @DisplayName("a car ignores someone on the pavement beside it")
    void trafficDoesNotBrakeForBystanders() {
        for (double h : new double[]{0, Math.PI / 3, -2.4}) {
            // Eight metres perpendicular to the direction of travel.
            double fx = Math.sin(h), fz = -Math.cos(h);
            double px = -fz * 8.0, pz = fx * 8.0;
            double v = Agents.desiredSpeed(0, 0, h, px, pz, 14.0);
            assertEquals(14.0, v, 1e-9,
                    "braked for a bystander on heading " + h);
        }
    }

    @Test
    @DisplayName("a car ignores the player it has already passed")
    void trafficDoesNotBrakeForWhatIsBehindIt() {
        for (double h : new double[]{0, 1.2, -0.7, 3.9}) {
            double px = -Math.sin(h) * 4.0;
            double pz = Math.cos(h) * 4.0;
            double v = Agents.desiredSpeed(0, 0, h, px, pz, 14.0);
            assertEquals(14.0, v, 1e-9, "braked for something behind it");
        }
    }

    @Test
    @DisplayName("slowing is gradual with distance, never negative")
    void yieldingIsMonotonic() {
        double last = -1;
        for (double d = 5.0; d < 30.0; d += 0.5) {
            double v = Agents.desiredSpeed(0, 0, 0, 0, -d, 14.0);
            assertTrue(v >= 0, "negative speed at " + d + " m");
            assertTrue(v <= 14.0 + 1e-9, "sped up at " + d + " m");
            assertTrue(v >= last - 1e-9,
                    "speed fell as the player got further away, at " + d + " m");
            last = v;
        }
    }

    /**
     * People get out of the way of a fast vehicle, and only of a fast
     * vehicle.
     */
    @Test
    @DisplayName("pedestrians flee a speeding car but ignore someone on foot")
    void pedestriansFleeOnlyRealThreats() {
        assertTrue(!Agents.shouldFlee(1.5, 4.0),
                "fled from somebody walking past");
        assertTrue(!Agents.shouldFlee(0.0, 1.0),
                "fled from somebody standing still");
        assertTrue(!Agents.shouldFlee(18.0, 40.0),
                "fled from a car forty metres away");
        assertTrue(Agents.shouldFlee(15.0, 6.0),
                "did not flee from a car at 54 km/h six metres away");
    }

    @Test
    @DisplayName("a fleeing pedestrian runs away from the player, not into them")
    void fleeingIsAwayFromTheThreat() {
        // Pedestrian east of the player should head east.
        double h = Agents.fleeHeading(10, 0, 0, 0);
        double fx = Math.sin(h), fz = -Math.cos(h);
        assertTrue(fx > 0.99, "should run east, heading points " + fx);

        // And north of the player, north.
        h = Agents.fleeHeading(0, -10, 0, 0);
        fx = Math.sin(h);
        fz = -Math.cos(h);
        assertTrue(fz < -0.99, "should run north, heading points " + fz);

        // In every direction, the step must increase the separation.
        for (double a = 0; a < 6.28; a += 0.37) {
            double pedX = Math.cos(a) * 7.0, pedZ = Math.sin(a) * 7.0;
            double hh = Agents.fleeHeading(pedX, pedZ, 0, 0);
            double nx = pedX + Math.sin(hh) * 1.0;
            double nz = pedZ - Math.cos(hh) * 1.0;
            assertTrue(Math.hypot(nx, nz) > Math.hypot(pedX, pedZ),
                    "ran towards the player from angle " + a);
        }
    }

    // ----------------------------------------------------------------- radar

    @Test
    @DisplayName("the radar finds real roads around the player")
    void radarShowsNearbyRoads() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        Minimap m = new Minimap(w);

        double px = e.human.position.x, pz = e.human.position.z;
        m.update(px, pz, 0);
        assertTrue(m.segmentCount > 5,
                "expected roads near the spawn, found " + m.segmentCount);

        // Everything returned must be within range, or close enough that it
        // only crosses the disc.
        double limit = Minimap.RANGE_M * 2.5;
        for (int i = 0; i < m.segmentCount; i++) {
            int o = i * 4;
            double d0 = Math.hypot(m.segments[o], m.segments[o + 1]);
            double d1 = Math.hypot(m.segments[o + 2], m.segments[o + 3]);
            assertTrue(Math.min(d0, d1) < limit,
                    "segment " + i + " is " + Math.min(d0, d1) + " m away");
        }
    }

    /**
     * Rotation is the whole point of a heading-up radar: turn, and the map
     * must turn under you. A road dead ahead has to stay dead ahead.
     */
    @Test
    @DisplayName("the radar rotates with the player's heading")
    void radarIsHeadingUp() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        Minimap m = new Minimap(w);
        double px = e.human.position.x, pz = e.human.position.z;

        m.update(px, pz, 0);
        int n = m.segmentCount;
        double[] north = new double[n * 4];
        System.arraycopy(m.segments, 0, north, 0, n * 4);

        m.update(px, pz, Math.PI / 2);
        assertEquals(n, m.segmentCount, "rotating must not change what is visible");

        // A quarter turn maps (x, y) to (y, -x) or (-y, x) depending on the
        // sense; whichever it is, it must be consistent and it must not be
        // the identity.
        boolean anyMoved = false;
        for (int i = 0; i < n * 4; i += 2) {
            if (Math.abs(north[i] - m.segments[i]) > 1.0) { anyMoved = true; break; }
        }
        assertTrue(anyMoved, "the radar did not rotate with the heading");

        // Lengths are preserved by a rotation.
        for (int i = 0; i < n; i++) {
            int o = i * 4;
            double lenA = Math.hypot(north[o + 2] - north[o], north[o + 3] - north[o + 1]);
            double lenB = Math.hypot(m.segments[o + 2] - m.segments[o],
                    m.segments[o + 3] - m.segments[o + 1]);
            assertEquals(lenA, lenB, 0.05, "segment " + i + " changed length");
        }
    }

    /**
     * A long road whose two endpoints are both outside the disc can still
     * run straight through the middle of it. Rejecting on endpoints alone
     * puts a gap in exactly the road the player is driving down.
     */
    @Test
    @DisplayName("a road crossing the radar is kept even with both ends outside")
    void longRoadsAreNotDroppedByEndpointTest() {
        WorldData w = WorldFixture.world();
        Minimap m = new Minimap(w);

        // Walk the graph for an edge longer than the radar diameter.
        int found = -1;
        for (int e = 0; e < w.edgeCount() && found < 0; e++) {
            int a = w.graphEdge[e * 2], b = w.graphEdge[e * 2 + 1];
            double len = Math.hypot(
                    w.graphNodeXZ[b * 2] - w.graphNodeXZ[a * 2],
                    w.graphNodeXZ[b * 2 + 1] - w.graphNodeXZ[a * 2 + 1]);
            if (len > Minimap.RANGE_M * 2.2) { found = e; }
        }
        if (found < 0) { return; }   // no such edge in this graph; nothing to prove

        int a = w.graphEdge[found * 2], b = w.graphEdge[found * 2 + 1];
        double mx = (w.graphNodeXZ[a * 2] + w.graphNodeXZ[b * 2]) * 0.5;
        double mz = (w.graphNodeXZ[a * 2 + 1] + w.graphNodeXZ[b * 2 + 1]) * 0.5;

        m.update(mx, mz, 0);
        boolean present = false;
        for (int i = 0; i < m.segmentCount && !present; i++) {
            int o = i * 4;
            // The segment through the centre should pass very close to it.
            double d = pointToSegment(0, 0, m.segments[o], m.segments[o + 1],
                    m.segments[o + 2], m.segments[o + 3]);
            if (d < 2.0) { present = true; }
        }
        assertTrue(present,
                "standing in the middle of a long road, it is missing from the radar");
    }

    private static double pointToSegment(double px, double pz,
                                         double ax, double az,
                                         double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-9) { return Math.hypot(px - ax, pz - az); }
        double t = ((px - ax) * dx + (pz - az) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + dx * t), pz - (az + dz * t));
    }

    @Test
    @DisplayName("the radar keeps up when it is rebuilt every telemetry tick")
    void radarIsFastEnoughToRunAtFifteenHertz() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        Minimap m = new Minimap(w);
        double px = e.human.position.x, pz = e.human.position.z;

        m.update(px, pz, 0);              // build the index outside the timing

        long t0 = System.nanoTime();
        int n = 200;
        for (int i = 0; i < n; i++) {
            m.update(px + i * 3.0, pz + i * 2.0, i * 0.03);
        }
        double msEach = (System.nanoTime() - t0) / 1e6 / n;
        // The point is that it is indexed rather than a linear scan of
        // 352,000 edges. Generous bound; a scan would be orders out.
        // Generous: CI runners are slow and shared. The point is that it is
        // indexed rather than a scan of 352,000 edges, which would be orders
        // of magnitude past this.
        assertTrue(msEach < 12.0,
                "radar rebuild takes " + msEach + " ms, which is a linear scan");
    }

    @Test
    @DisplayName("the engine refreshes the radar as it runs")
    void engineDrivesTheRadar() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        InputState in = new InputState();
        for (int i = 0; i < 40; i++) { e.frame(1 / 30.0, in); }
        assertTrue(e.minimap.segmentCount > 0,
                "the engine never populated the radar");
    }
}
