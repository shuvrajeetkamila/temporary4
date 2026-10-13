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
            assertTrue(kph >= 5 && kph <= 130,
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
     * Traffic must slow for the player in front of it. Checked through the
     * public update, so it exercises the same path the game uses.
     */
    @Test
    @DisplayName("a car brakes for the player standing in its lane")
    void trafficYieldsToThePlayer() {
        WorldData w = WorldFixture.world();
        Agents a = new Agents(w);
        a.setCaps(8, 0);

        Agents.Car c = a.cars()[0];
        c.active = true;
        c.parked = false;
        c.kindOrdinal = VehicleKind.SEDAN.ordinal();
        c.heading = 0;                        // facing north, which is -Z
        c.x = 0;
        c.z = 0;
        c.speed = 14.0;
        c.targetSpeed = 14.0;
        c.edge = 0;

        // Player three metres directly in front: north of the car is -Z.
        for (int i = 0; i < 30; i++) {
            a.update(1 / 60.0, 0, -3.0, 0, 0, 1e9, 1e9);
        }
        assertTrue(c.speed < 1.0,
                "car should have stopped for the player, still doing " + c.speed);
        assertTrue(c.braking, "the car should be flagged as braking");
    }

    /**
     * The same yield, on a heading that is not axis-aligned.
     *
     * <p>This is the test that matters. With the lateral offset computed as
     * a sum of products rather than a cross product, the axis-aligned cases
     * above all pass and every diagonal road in the city silently stops
     * yielding - which is most of them.
     */
    @Test
    @DisplayName("a car on a diagonal heading still brakes for the player")
    void trafficYieldsOnDiagonalHeadings() {
        WorldData w = WorldFixture.world();
        for (double h : new double[]{Math.PI / 6, Math.PI / 4, 2.1, -1.3, 4.7}) {
            Agents a = new Agents(w);
            a.setCaps(8, 0);
            Agents.Car c = a.cars()[0];
            c.active = true;
            c.kindOrdinal = VehicleKind.SEDAN.ordinal();
            c.heading = h;
            c.x = 0;
            c.z = 0;
            c.speed = 14.0;
            c.targetSpeed = 14.0;
            c.edge = 0;

            // Three metres straight down the car's own forward vector.
            double px = Math.sin(h) * 3.0;
            double pz = -Math.cos(h) * 3.0;
            for (int i = 0; i < 30; i++) {
                a.update(1 / 60.0, px, pz, 0, 0, 1e9, 1e9);
            }
            assertTrue(c.speed < 1.0,
                    "on heading " + h + " the car did not brake: " + c.speed);
        }
    }

    @Test
    @DisplayName("a car ignores someone on the pavement beside it")
    void trafficDoesNotBrakeForBystanders() {
        WorldData w = WorldFixture.world();
        Agents a = new Agents(w);
        a.setCaps(8, 0);

        Agents.Car c = a.cars()[0];
        c.active = true;
        c.kindOrdinal = VehicleKind.SEDAN.ordinal();
        c.heading = 0;
        c.x = 0;
        c.z = 0;
        c.speed = 14.0;
        c.targetSpeed = 14.0;
        c.edge = 0;

        // Eight metres off to the side, not in the lane.
        for (int i = 0; i < 30; i++) {
            a.update(1 / 60.0, 8.0, -3.0, 0, 0, 1e9, 1e9);
        }
        assertTrue(c.speed > 10.0,
                "car braked for a bystander on the pavement: " + c.speed);
    }

    @Test
    @DisplayName("a car ignores the player it has already passed")
    void trafficDoesNotBrakeForWhatIsBehindIt() {
        WorldData w = WorldFixture.world();
        Agents a = new Agents(w);
        a.setCaps(8, 0);

        Agents.Car c = a.cars()[0];
        c.active = true;
        c.kindOrdinal = VehicleKind.SEDAN.ordinal();
        c.heading = 0;
        c.x = 0;
        c.z = 0;
        c.speed = 14.0;
        c.targetSpeed = 14.0;
        c.edge = 0;

        // Behind means +Z when facing north.
        for (int i = 0; i < 30; i++) {
            a.update(1 / 60.0, 0, 4.0, 0, 0, 1e9, 1e9);
        }
        assertTrue(c.speed > 10.0,
                "car braked for something behind it: " + c.speed);
    }

    /**
     * People get out of the way of a fast vehicle, and only of a fast
     * vehicle. A crowd that scatters from someone standing still reads as a
     * hostile city rather than a busy one.
     */
    @Test
    @DisplayName("pedestrians flee a speeding car but ignore a person walking by")
    void pedestriansFleeOnlyRealThreats() {
        WorldData w = WorldFixture.world();
        Agents a = new Agents(w);
        a.setCaps(0, 8);

        double spawnX = 0, spawnZ = 0;
        // Find somewhere on land so the pedestrian can actually move.
        outer:
        for (double x = -4000; x <= 4000; x += 500) {
            for (double z = -4000; z <= 4000; z += 500) {
                if (w.inBounds(x, z) && w.isLand(x, z) && w.slope(x, z) < 0.2) {
                    spawnX = x;
                    spawnZ = z;
                    break outer;
                }
            }
        }

        Agents.Ped p = a.peds()[0];
        p.active = true;
        p.x = spawnX + 4.0;
        p.z = spawnZ;
        p.y = w.height(p.x, p.z);
        p.speed = 1.2;
        p.heading = 0;
        p.fleeTimer = 0;

        // A pedestrian walking past at 1.5 m/s is not a threat.
        a.update(1 / 60.0, spawnX, spawnZ, 1.5, 0, 1e9, 1e9);
        assertEquals(0.0, p.fleeTimer, 1e-9,
                "a pedestrian should not flee from somebody on foot");

        // A car at 15 m/s is.
        a.update(1 / 60.0, spawnX, spawnZ, 15.0, 0, 1e9, 1e9);
        assertTrue(p.fleeTimer > 0, "a pedestrian should flee from a speeding car");

        // And the direction must be away, not into it.
        double before = Math.hypot(p.x - spawnX, p.z - spawnZ);
        for (int i = 0; i < 90; i++) {
            a.update(1 / 60.0, spawnX, spawnZ, 15.0, 0, 1e9, 1e9);
        }
        double after = Math.hypot(p.x - spawnX, p.z - spawnZ);
        assertTrue(after > before,
                "pedestrian ran towards the car: " + before + " -> " + after);
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
        assertTrue(msEach < 3.0,
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
