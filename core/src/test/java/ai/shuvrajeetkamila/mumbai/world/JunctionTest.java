package ai.shuvrajeetkamila.mumbai.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who goes first at a crossroads.
 *
 * <p>Traffic queued behind the car in front and bounced off whatever it
 * touched, but nothing had any rule about priority: four streams
 * converging on a junction produced a slow pile of stationary vehicles
 * nudging each other until one happened to get through.
 */
final class JunctionTest {

    @Test
    @DisplayName("the two phases are never green together")
    void phasesDoNotOverlap() {
        // The one property a junction cannot get wrong. Swept finely
        // over several cycles and at a spread of offsets, because an
        // overlap of a tenth of a second is still a head-on.
        for (double off = 0; off < 1.0; off += 0.037) {
            for (double t = 0; t < Junctions.CYCLE_S * 3; t += 0.02) {
                int a = Junctions.aspect(t, off, 0);
                int b = Junctions.aspect(t, off, 1);
                assertTrue(!(a == 0 && b == 0),
                        "both phases are green at t=" + t + " offset "
                                + off);
                assertTrue(!(a <= 1 && b <= 1),
                        "both phases are showing a proceed-or-clear"
                                + " aspect at t=" + t + " offset " + off);
            }
        }
    }

    @Test
    @DisplayName("every phase gets its turn, in order, every cycle")
    void theCycleIsComplete() {
        int green0 = 0, amber0 = 0, red0 = 0;
        double step = 0.01;
        for (double t = 0; t < Junctions.CYCLE_S; t += step) {
            switch (Junctions.aspect(t, 0, 0)) {
                case 0: green0++; break;
                case 1: amber0++; break;
                default: red0++; break;
            }
        }
        assertEquals(Junctions.GREEN_S, green0 * step, 0.05,
                "phase 0 is not green for its share of the cycle");
        assertEquals(Junctions.AMBER_S, amber0 * step, 0.05,
                "phase 0's amber is the wrong length");
        assertEquals(Junctions.GREEN_S + Junctions.AMBER_S, red0 * step, 0.05,
                "phase 0 is not red while phase 1 has its turn");
        // Amber always between green and red, never after red.
        boolean seenAmber = false;
        for (double t = 0; t < Junctions.GREEN_S + Junctions.AMBER_S; t += step) {
            int a = Junctions.aspect(t, 0, 0);
            if (a == 1) { seenAmber = true; }
            if (seenAmber) {
                assertTrue(a != 0,
                        "the light went back to green after amber");
            }
        }
    }

    @Test
    @DisplayName("both ends of the same street run together")
    void oppositeApproachesSharePhase() throws Exception {
        WorldData w = WorldFixture.world();
        Junctions j = w.junctions();
        assertTrue(j.count() > 1000,
                "only " + j.count() + " junctions in the whole city");
        assertTrue(j.signalledCount() > 100,
                "only " + j.signalledCount() + " signalled junctions");
        assertTrue(j.signalledCount() < j.count(),
                "every junction in Mumbai has been given traffic lights,"
                        + " including the lane behind the chawl");

        for (int i = 0; i < Math.min(400, j.count()); i++) {
            double a = j.axisOf(i);
            // A street and its reverse must be the same phase, or the
            // junction lets one direction through while holding the
            // other, which is not a junction, it is a trap.
            assertEquals(j.phaseFor(i, a), j.phaseFor(i, a + Math.PI),
                    "junction " + i + " splits a street between phases");
            // And the cross street must be the other phase.
            assertTrue(j.phaseFor(i, a) != j.phaseFor(i, a + Math.PI / 2),
                    "junction " + i + " runs the cross street on the"
                            + " same phase as the main road");
        }
    }

    @Test
    @DisplayName("junctions are where roads actually meet")
    void junctionsAreOnTheGraph() throws Exception {
        WorldData w = WorldFixture.world();
        Junctions j = w.junctions();
        int nodes = w.graphNodeXZ.length / 2;
        for (int i = 0; i < Math.min(300, j.count()); i++) {
            int n = j.nodeOf(i);
            assertTrue(n >= 0 && n < nodes, "junction off the graph");
            assertEquals(w.graphNodeXZ[n * 2], j.x(i), 1e-6);
            assertEquals(w.graphNodeXZ[n * 2 + 1], j.z(i), 1e-6);
        }
        // The lookup has to find a junction that is right there.
        int found = 0;
        for (int i = 0; i < Math.min(200, j.count()); i++) {
            if (!j.signalled(i)) { continue; }
            assertEquals(i, j.nearestSignalled(j.x(i), j.z(i), 5.0),
                    "the spatial index cannot find a junction standing"
                            + " on top of it");
            found++;
        }
        assertTrue(found > 0, "no signalled junction in the first 200");
    }

    @Test
    @DisplayName("the offsets are spread, so the city does not blink as one")
    void offsetsAreScattered() throws Exception {
        WorldData w = WorldFixture.world();
        Junctions j = w.junctions();
        int[] buckets = new int[10];
        int n = Math.min(4000, j.count());
        for (int i = 0; i < n; i++) {
            int b = (int) (j.offsetOf(i) * 10);
            buckets[Math.max(0, Math.min(9, b))]++;
        }
        for (int i = 0; i < 10; i++) {
            assertTrue(buckets[i] > n / 40,
                    "offset bucket " + i + " holds only " + buckets[i]
                            + " of " + n + " junctions, so the lights"
                            + " are clustered rather than scattered");
        }
    }
}
