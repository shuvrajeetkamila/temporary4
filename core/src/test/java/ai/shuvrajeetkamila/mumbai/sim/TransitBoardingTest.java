package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Getting on a train.
 *
 * <p>Until now the trains, Metro, monorail and ferries ran the length
 * of their alignments and turned round at the ends, and that was all
 * they did: no stations, nowhere to wait, nothing to get on. A railway
 * you can only watch is scenery.
 *
 * <p>Stops are spaced along each line at the real spacing for the mode
 * and the service holds at each one with its doors open. These tests
 * run the simulation and check that a service actually stops, that it
 * can be boarded only while it is stopped, and that the player is
 * carried along the line and put back on the ground at a station.
 */
final class TransitBoardingTest {

    private static Transit transitOf(WorldData w) {
        Transit t = new Transit(w);
        t.cap = 24;
        return t;
    }

    /** Runs the whole network near a point for a while. */
    private static void run(Transit t, double px, double pz, double seconds) {
        for (double e = 0; e < seconds; e += 1 / 60.0) {
            t.update(1 / 60.0, px, pz, 40000);
        }
    }

    @Test
    @DisplayName("lines have stations on them")
    void everyLineHasStops() {
        WorldData w = WorldFixture.world();
        Transit t = transitOf(w);
        int withStops = 0;
        int routes = 0;
        for (int r = 0; r < w.transitCount(); r++) {
            if (t.stopCount(r) > 0) { withStops++; }
            if (w.transitLength(r) > 4000) { routes++; }
        }
        assertTrue(withStops > 20,
                "only " + withStops + " routes have any stations on them");
        assertTrue(routes > 0);
    }

    @Test
    @DisplayName("stations are spaced like real ones")
    void stopsAreSpacedRealistically() {
        WorldData w = WorldFixture.world();
        Transit t = transitOf(w);
        int checked = 0;
        for (int r = 0; r < w.transitCount() && checked < 40; r++) {
            int n = t.stopCount(r);
            if (n < 3) { continue; }
            checked++;
            double[] prev = t.stopPoint(r, 0);
            for (int i = 1; i < n; i++) {
                double[] here = t.stopPoint(r, i);
                double gap = Math.hypot(here[0] - prev[0], here[1] - prev[1]);
                // Straight-line distance between consecutive stops is at
                // most the distance along the line, and on a curve rather
                // less. Mumbai's tightest real spacing is a few hundred
                // metres; anything under 150 m would be two platforms of
                // the same station.
                assertTrue(gap > 150,
                        "route " + r + " has stations " + Math.round(gap)
                                + " m apart");
                assertTrue(gap < 2600,
                        "route " + r + " has stations " + Math.round(gap)
                                + " m apart, which is not a city railway");
                prev = here;
            }
        }
        assertTrue(checked > 5, "only " + checked + " routes were examined");
    }

    @Test
    @DisplayName("a service actually stops, and not only at the ends")
    void servicesCallAtStations() {
        WorldData w = WorldFixture.world();
        Transit t = transitOf(w);
        double[] spawn = WorldFixture.spawnXZ(w);
        // Somewhere with rail: the western line through the island.
        run(t, spawn[0], spawn[1], 5);
        int seenAtStop = 0;
        double stoppedTime = 0;
        for (double e = 0; e < 600; e += 1 / 60.0) {
            t.update(1 / 60.0, spawn[0], spawn[1], 40000);
            for (Transit.Service sv : t.services()) {
                if (sv.active && sv.atStop) { seenAtStop++; }
                if (sv.active && sv.speed < 0.01) { stoppedTime += 1 / 60.0; }
            }
        }
        assertTrue(seenAtStop > 0,
                "in ten minutes of running, no service ever stopped at a"
                        + " station");
        assertTrue(stoppedTime > 1.0, "nothing ever stood still");
    }

    @Test
    @DisplayName("you can only board a service that is standing at a station")
    void boardingNeedsAStoppedService() {
        WorldData w = WorldFixture.world();
        Transit t = transitOf(w);
        double[] spawn = WorldFixture.spawnXZ(w);
        boolean everBoardableWhileMoving = false;
        boolean everBoardableWhileStopped = false;
        for (double e = 0; e < 900; e += 1 / 60.0) {
            t.update(1 / 60.0, spawn[0], spawn[1], 40000);
            for (Transit.Service sv : t.services()) {
                if (!sv.active) { continue; }
                int got = t.boardableAt(sv.x, sv.z);
                if (got < 0) { continue; }
                Transit.Service there = t.services()[got];
                if (there.atStop && there.dwell > 0) {
                    everBoardableWhileStopped = true;
                } else {
                    everBoardableWhileMoving = true;
                }
            }
        }
        assertTrue(everBoardableWhileStopped,
                "no service was ever boardable, even standing at a"
                        + " station with its doors open");
        assertTrue(!everBoardableWhileMoving,
                "a service was boardable while it was not stopped at a"
                        + " station - you could flag down a moving train");
    }

    @Test
    @DisplayName("the player rides the line and is put down at a station")
    void ridingCarriesThePlayerAndPutsThemDown() throws Exception {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        Assumptions.assumeTrue(e.transit.routeCount() > 0,
                "no transit routes in this world");

        // Find a service holding at a station, and stand the player at it.
        Transit.Service target = null;
        for (double el = 0; el < 1200 && target == null; el += 1 / 60.0) {
            e.transit.update(1 / 60.0, e.human.position.x, e.human.position.z,
                    40000);
            for (Transit.Service sv : e.transit.services()) {
                if (sv.active && sv.atStop && sv.dwell > 4.0) {
                    target = sv;
                    break;
                }
            }
        }
        Assumptions.assumeTrue(target != null,
                "no service came to a station in twenty minutes");

        e.human.position.set(target.x, target.y + 1.0, target.z);
        double boardedX = target.x;
        double boardedZ = target.z;
        assertTrue(e.boardTransit(), "could not board a standing service");
        assertTrue(e.aboard >= 0,
                "boarding did not attach the player to a service");

        // Riding it must move the player, and must not let them off
        // between stations.
        for (int i = 0; i < 60 * 240; i++) {
            e.transit.update(1 / 60.0, e.human.position.x, e.human.position.z,
                    40000);
            Transit.Service sv = e.transit.services()[e.aboard];
            if (!sv.active) { break; }
            e.human.position.set(sv.x, sv.y + 1.0, sv.z);
            if (!sv.atStop) {
                assertTrue(!e.alightTransit(),
                        "the player got off between stations");
            }
        }
        double moved = Math.hypot(e.human.position.x - boardedX,
                e.human.position.z - boardedZ);
        assertTrue(moved > 200,
                "after four minutes aboard the player had moved "
                        + Math.round(moved) + " m");

        // And at a station they can get off, onto the ground.
        Transit.Service sv = e.transit.services()[e.aboard];
        for (int i = 0; i < 60 * 600 && !sv.atStop; i++) {
            e.transit.update(1 / 60.0, sv.x, sv.z, 40000);
        }
        if (sv.atStop) {
            assertTrue(e.alightTransit(), "could not get off at a station");
            assertTrue(e.aboard < 0, "still aboard after getting off");
        }
    }
}
