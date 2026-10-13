package ai.shuvrajeetkamila.mumbai.game;

import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jobs, fares and progression.
 *
 * <p>The arithmetic is tested on its own, because money is the one thing a
 * player will notice being wrong. The generation tests need the real road
 * graph: a mission system that produces stops inside buildings or on the
 * far side of the harbour is only discoverable against real geometry.
 */
final class MissionsTest {

    // ------------------------------------------------------------- the money

    @Test
    @DisplayName("a longer fare pays more, and nothing pays less than the flag")
    void faresRiseWithDistance() {
        int shortHop = Missions.taxiFare(400, 0, 0);
        int acrossTown = Missions.taxiFare(7200, 0, 0);
        assertTrue(shortHop >= Missions.TAXI_BASE,
                "a fare came in under the base rate: " + shortHop);
        assertTrue(acrossTown > shortHop,
                "18 km/h of fare did not rise with distance");

        // The rate is per kilometre, so ten of them is ten times the rate
        // on top of the base.
        assertEquals(Missions.TAXI_BASE + (int) Math.round(Missions.TAXI_PER_KM * 10),
                Missions.taxiFare(10_000, 0, 0), 1);
    }

    @Test
    @DisplayName("beating the clock pays a bonus, dawdling does not cost you")
    void quickRunsPayMore() {
        double par = Missions.parTime(5000, 26);
        int quick = Missions.taxiFare(5000, par, par * 0.4);
        int onTime = Missions.taxiFare(5000, par, par);
        int slow = Missions.taxiFare(5000, par, par * 3);

        assertTrue(quick > onTime, "no bonus for a quick run");
        assertEquals(onTime, slow,
                "a slow run was penalised; the fare should only ever go up");
        // Capped, so a teleport cannot pay ten times the meter.
        assertTrue(quick <= onTime * 1.34,
                "the early bonus is unbounded: " + quick + " vs " + onTime);
    }

    @Test
    @DisplayName("par time is the distance at the given speed, in seconds")
    void parTimeIsSane() {
        // 36 km/h is 10 m/s, so a kilometre is 100 seconds.
        assertEquals(100.0, Missions.parTime(1000, 36), 1e-9);
        assertTrue(Missions.parTime(5000, 26) > Missions.parTime(5000, 46),
                "going faster did not take less time");
    }

    @Test
    @DisplayName("delivery pay rises with distance and beats a taxi fare")
    void deliveriesPayMore() {
        assertTrue(Missions.deliveryFee(3000) > Missions.deliveryFee(1000));
        assertTrue(Missions.deliveryFee(3000) > Missions.taxiFare(3000, 0, 0),
                "a timed delivery should be worth more than an untimed fare");
    }

    // ------------------------------------------------------------ which work

    @Test
    @DisplayName("the vehicle decides the work, and some vehicles are not for work")
    void vehicleChoosesTheJob() {
        assertEquals(Missions.Kind.TAXI, Missions.workFor(VehicleKind.KAALI_PEELI));
        assertEquals(Missions.Kind.DELIVERY, Missions.workFor(VehicleKind.TEMPO));
        assertEquals(Missions.Kind.RACE, Missions.workFor(VehicleKind.SEDAN));
        assertEquals(Missions.Kind.RACE, Missions.workFor(VehicleKind.SUV));

        assertEquals(null, Missions.workFor(VehicleKind.DOUBLE_DECKER),
                "you cannot run a street race in a double-decker");
        assertEquals(null, Missions.workFor(VehicleKind.POLICE));
        assertEquals(null, Missions.workFor(null), "on foot is not a job");
    }

    // ------------------------------------------------------- radar geometry

    @Test
    @DisplayName("the radar is heading-up: what is ahead is drawn upwards")
    void radarSpaceIsHeadingUp() {
        for (double h : new double[]{0, 0.9, Math.PI / 2, 2.7, -1.4, 5.1}) {
            double c = Math.cos(-h), s = Math.sin(-h);
            // A point 100 m straight ahead. Forward is (sin h, -cos h).
            double fx = Math.sin(h) * 100, fz = -Math.cos(h) * 100;
            assertEquals(0.0, Minimap.mapX(fx, fz, c, s), 1e-9,
                    "what is ahead drifted sideways on heading " + h);
            assertEquals(100.0, Minimap.mapY(fx, fz, c, s), 1e-9,
                    "what is ahead was not drawn upwards on heading " + h);

            // And 100 m to the right: right of travel is (cos h, sin h).
            double rx = Math.cos(h) * 100, rz = Math.sin(h) * 100;
            assertEquals(100.0, Minimap.mapX(rx, rz, c, s), 1e-9,
                    "what is to the right was not drawn right on heading " + h);
            assertEquals(0.0, Minimap.mapY(rx, rz, c, s), 1e-9,
                    "what is to the right drifted up or down on heading " + h);
        }
    }

    @Test
    @DisplayName("the radar transform is a rotation: it preserves distance")
    void radarSpaceIsARotation() {
        for (double h : new double[]{0.3, 2.2, -0.8}) {
            double c = Math.cos(-h), s = Math.sin(-h);
            for (double[] p : new double[][]{{130, 0}, {0, -75}, {60, 90}}) {
                double mx = Minimap.mapX(p[0], p[1], c, s);
                double my = Minimap.mapY(p[0], p[1], c, s);
                assertEquals(Math.hypot(p[0], p[1]), Math.hypot(mx, my), 1e-9,
                        "the radar transform changed a distance");
            }
        }
    }

    // --------------------------------------------------------- the real city

    private Missions build(WorldData w) {
        return new Missions(w, new Minimap(w), 20251013L);
    }

    @Test
    @DisplayName("a mission stop is always on a road you can drive to")
    void stopsLandOnTarmac() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Minimap map = new Minimap(w);
        Missions m = new Missions(w, map, 7L);

        double[] spawn = WorldFixture.spawnXZ(w);
        Missions.Job j = m.generate(Missions.Kind.TAXI, spawn[0], spawn[1]);
        Assumptions.assumeTrue(j != null, "no fare available at the spawn");

        for (Missions.Stop s : j.stops) {
            double[] on = map.nearestRoad(s.x, s.z, 60);
            assertNotNull(on, "stop '" + s.label + "' has no road within 60 m");
            double off = Math.hypot(on[0] - s.x, on[1] - s.z);
            assertTrue(off < 1.0,
                    "stop '" + s.label + "' sits " + off + " m off the road");
        }
    }

    @Test
    @DisplayName("a fare goes somewhere worth driving to, not next door")
    void faresAreWorthTaking() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Missions m = build(w);
        double[] spawn = WorldFixture.spawnXZ(w);

        int made = 0;
        for (int i = 0; i < 12; i++) {
            Missions.Job j = m.generate(Missions.Kind.TAXI, spawn[0], spawn[1]);
            if (j == null) { continue; }
            made++;
            assertEquals(2, j.stops.size(), "a fare is a pickup and a drop-off");
            Missions.Stop a = j.stops.get(0), b = j.stops.get(1);
            double leg = Math.hypot(b.x - a.x, b.z - a.z);
            assertTrue(leg > 600,
                    "the drop-off is only " + (int) leg + " m from the pickup");
            assertTrue(leg < 6000,
                    "the drop-off is " + (int) leg + " m away, across the city");
            assertTrue(!a.label.isEmpty() && !b.label.isEmpty(),
                    "stops should carry real place names");
        }
        assertTrue(made > 0, "no fare could be generated anywhere at the spawn");
    }

    @Test
    @DisplayName("a race is a loop of gates that finishes where it started")
    void racesAreCircuits() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Missions m = build(w);
        double[] spawn = WorldFixture.spawnXZ(w);

        Missions.Job j = null;
        for (int i = 0; i < 12 && j == null; i++) {
            j = m.generate(Missions.Kind.RACE, spawn[0], spawn[1]);
        }
        Assumptions.assumeTrue(j != null, "no race could be laid out at the spawn");

        assertTrue(j.stops.size() >= 4,
                "a race needs gates, got " + j.stops.size());
        Missions.Stop last = j.stops.get(j.stops.size() - 1);
        assertTrue(Math.hypot(last.x - spawn[0], last.z - spawn[1]) < 300,
                "the race does not finish near where it started");
        assertTrue(j.timeLimit > 20, "the race target time is not achievable");
        assertTrue(j.reward > 0, "a race with no prize");

        // No two gates on top of each other, or the circuit is degenerate.
        for (int i = 1; i < j.stops.size() - 1; i++) {
            Missions.Stop p = j.stops.get(i - 1), q = j.stops.get(i);
            assertTrue(Math.hypot(q.x - p.x, q.z - p.z) > 60,
                    "gates " + (i - 1) + " and " + i + " are the same corner");
        }
    }

    @Test
    @DisplayName("driving the route start to finish pays out exactly once")
    void completingAFarePays() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Minimap map = new Minimap(w);
        Missions m = new Missions(w, map, 99L);
        double[] spawn = WorldFixture.spawnXZ(w);

        // Let the system offer the job itself, the way the game does.
        double x = spawn[0], z = spawn[1];
        for (int i = 0; i < 20 && m.active == null; i++) {
            m.update(1.0 / 60, x, z, 0, VehicleKind.KAALI_PEELI);
        }
        assertNotNull(m.active, "no fare was offered at the spawn");
        assertEquals(0, m.money, "money should start at nothing");

        int guard = 0;
        while (m.active != null && guard++ < 10) {
            Missions.Stop s = m.active.target();
            if (s == null) { break; }
            // Arrive slowly enough to count.
            x = s.x;
            z = s.z;
            m.update(1.0 / 60, x, z, 1.0, VehicleKind.KAALI_PEELI);
        }

        assertEquals(null, m.active, "the job never finished");
        assertEquals(1, m.jobsDone, "the job did not count as done exactly once");
        assertEquals(0, m.jobsFailed, "a completed fare was also counted failed");
        assertTrue(m.money >= Missions.TAXI_BASE,
                "completing a fare paid " + m.money);
    }

    @Test
    @DisplayName("clipping a stop at speed does not count as arriving")
    void youHaveToActuallyStop() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Missions m = build(w);
        double[] spawn = WorldFixture.spawnXZ(w);
        for (int i = 0; i < 20 && m.active == null; i++) {
            m.update(1.0 / 60, spawn[0], spawn[1], 0, VehicleKind.KAALI_PEELI);
        }
        assertNotNull(m.active, "no fare was offered at the spawn");

        Missions.Stop s = m.active.target();
        int stageBefore = m.active.stage;
        // Straight through the pickup at 90 km/h.
        m.update(1.0 / 60, s.x, s.z, 25.0, VehicleKind.KAALI_PEELI);
        assertEquals(stageBefore, m.active.stage,
                "the passenger boarded a taxi doing 90 km/h");
    }

    @Test
    @DisplayName("getting out of the taxi cancels the fare")
    void leavingTheVehicleAbandonsTheJob() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Missions m = build(w);
        double[] spawn = WorldFixture.spawnXZ(w);
        for (int i = 0; i < 20 && m.active == null; i++) {
            m.update(1.0 / 60, spawn[0], spawn[1], 0, VehicleKind.KAALI_PEELI);
        }
        assertNotNull(m.active, "no fare was offered at the spawn");

        m.update(1.0 / 60, spawn[0], spawn[1], 0, null);
        assertEquals(null, m.active, "the fare survived the driver walking off");
        assertEquals(1, m.jobsFailed);
        assertEquals(0, m.money, "an abandoned fare still paid out");
    }

    @Test
    @DisplayName("a delivery that runs out of time fails and pays nothing")
    void lateDeliveriesPayNothing() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Missions m = build(w);
        double[] spawn = WorldFixture.spawnXZ(w);
        for (int i = 0; i < 20 && m.active == null; i++) {
            m.update(1.0 / 60, spawn[0], spawn[1], 0, VehicleKind.TEMPO);
        }
        assertNotNull(m.active, "no delivery was offered at the spawn");
        assertTrue(m.active.timed(), "a delivery should be against the clock");

        double limit = m.active.timeLimit;
        assertTrue(limit > 60, "the delivery window is impossibly short: " + limit);

        for (int i = 0; i < 100_000 && m.active != null; i++) {
            m.update(0.05, spawn[0], spawn[1], 0, VehicleKind.TEMPO);
        }
        assertEquals(null, m.active, "the clock never ran out");
        assertEquals(0, m.money, "a late delivery was paid for");
        assertTrue(m.jobsFailed >= 1, "the failure was not recorded");
    }

    @Test
    @DisplayName("the radar shows the target, clamped to the rim when far off")
    void theTargetIsOnTheRadar() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Minimap map = new Minimap(w);
        Missions m = new Missions(w, map, 5L);
        double[] spawn = WorldFixture.spawnXZ(w);
        for (int i = 0; i < 20 && m.active == null; i++) {
            m.update(1.0 / 60, spawn[0], spawn[1], 0, VehicleKind.KAALI_PEELI);
        }
        assertNotNull(m.active, "no fare was offered at the spawn");

        map.update(spawn[0], spawn[1], 0);
        m.blip(spawn[0], spawn[1], 0);
        assertTrue(map.blipCount >= 1, "the target is not on the radar");
        for (int i = 0; i < map.blipCount; i++) {
            double bx = map.blips[i * 3], by = map.blips[i * 3 + 1];
            assertTrue(Math.hypot(bx, by) <= Minimap.RANGE_M + 1e-6,
                    "a blip was drawn outside the radar disc");
        }
    }

    @Test
    @DisplayName("nearestRoad finds the road, and gives up when there is none")
    void nearestRoadBehaves() {
        WorldData w = WorldFixture.world();
        Assumptions.assumeTrue(w != null, "needs a compiled world");
        Minimap map = new Minimap(w);
        double[] spawn = WorldFixture.spawnXZ(w);

        double[] near = map.nearestRoad(spawn[0], spawn[1], 400);
        assertNotNull(near, "no road within 400 m of the spawn point");
        assertTrue(Math.hypot(near[0] - spawn[0], near[1] - spawn[1]) <= 400);

        // Far out in the Arabian Sea there is no road, and it must say so
        // rather than return the nearest thing in the whole city.
        assertEquals(null, map.nearestRoad(spawn[0] - 40_000, spawn[1], 500),
                "found a road in the middle of the sea");
    }
}
