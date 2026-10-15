package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.sim.Transit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stations: where they are, how high, what they are called, and when
 * the next train is.
 *
 * <p>The stops were points on an alignment and nothing else. A train
 * halted for sixteen seconds in the middle of nowhere, you boarded it
 * off the ballast, and the prompt said "local train" because there was
 * no name for the line and nothing to stand on.
 */
final class TransitStationTest {

    @Test
    @DisplayName("the platform builder and the trains agree on the stops")
    void oneDefinitionOfWhereAStationIs() throws Exception {
        // The mesher builds the platform and the simulation halts the
        // train. If those are two calculations they will drift, and a
        // platform a hundred metres down the line from the doors is
        // worse than no platform.
        WorldData w = WorldFixture.world();
        Transit t = new Transit(w);
        TransitStops stops = w.transitStops();
        int compared = 0;
        for (int r = 0; r < w.transitCount(); r++) {
            int n = t.stopCount(r);
            if (n == 0) { continue; }
            int fromIndex = 0;
            for (int i = 0; i < stops.count(); i++) {
                if (stops.get(i).route == r) { fromIndex++; }
            }
            assertEquals(n, fromIndex,
                    "route " + r + " has " + n + " stops for the trains"
                            + " and " + fromIndex + " for the platforms");
            for (int k = 0; k < n; k++) {
                double[] p = t.stopPoint(r, k);
                TransitStops.Stop s = null;
                for (int i = 0; i < stops.count(); i++) {
                    TransitStops.Stop c = stops.get(i);
                    if (c.route == r && c.index == k) { s = c; break; }
                }
                assertTrue(s != null, "no platform stop for " + r + "/" + k);
                assertEquals(p[0], s.x, 0.6,
                        "platform and train disagree on x at " + r + "/" + k);
                assertEquals(p[1], s.z, 0.6,
                        "platform and train disagree on z at " + r + "/" + k);
                compared++;
            }
        }
        assertTrue(compared > 100,
                "only " + compared + " stations in the whole network");
        assertEquals(TransitStops.spacing(WorldData.CLS_RAIL), 1600.0, 1e-9);
    }

    @Test
    @DisplayName("lines run over, on and under the ground")
    void allThreeGradesExist() throws Exception {
        assertEquals(TransitStops.Grade.SURFACE,
                TransitStops.gradeOf(WorldData.CLS_RAIL),
                "the suburban network is at grade in Mumbai");
        assertEquals(TransitStops.Grade.ELEVATED,
                TransitStops.gradeOf(WorldData.CLS_LIGHT_RAIL),
                "Metro Line 1 is a viaduct the whole way; it was being"
                        + " run along the carriageway");
        assertEquals(TransitStops.Grade.ELEVATED,
                TransitStops.gradeOf(WorldData.CLS_MONORAIL));
        assertEquals(TransitStops.Grade.UNDERGROUND,
                TransitStops.gradeOf(WorldData.CLS_SUBWAY));

        assertTrue(TransitStops.railHeight(WorldData.CLS_SUBWAY) < -5,
                "the bored line is not underground");
        assertTrue(TransitStops.railHeight(WorldData.CLS_LIGHT_RAIL) > 5,
                "the Metro viaduct is not above the street");
        assertTrue(TransitStops.railHeight(WorldData.CLS_MONORAIL) > 5,
                "the monorail guideway is not above the street");
        assertTrue(Math.abs(TransitStops.railHeight(WorldData.CLS_RAIL)) < 2,
                "surface rail is not on the surface");

        // And the network really has all three in it.
        WorldData w = WorldFixture.world();
        boolean over = false, on = false, under = false;
        for (int r = 0; r < w.transitCount(); r++) {
            switch (TransitStops.gradeOf(w.transitClass(r))) {
                case ELEVATED: over = true; break;
                case SURFACE: on = true; break;
                case UNDERGROUND: under = true; break;
                default: break;
            }
        }
        assertTrue(over && on, "the city has no elevated or surface rail");
        assertTrue(under, "the city has no bored line");
    }

    @Test
    @DisplayName("no platform is built in a tunnel")
    void undergroundStationsAreNotSlabsInTheStreet() throws Exception {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        int chunks = 0;
        for (int c = 0; c < w.chunkCount() && chunks < 10; c += 37) {
            double ox = w.chunkOriginX(c);
            double oz = w.chunkOriginZ(c);
            if (!w.inBounds(ox, oz)) { continue; }
            if (w.transitStops().inBox(ox, oz, ox + w.chunkM,
                    oz + w.chunkM).isEmpty()) {
                continue;
            }
            chunks++;
            ChunkFeatures f = w.chunkHasData(c)
                    ? ChunkFeatures.decode(c, ox, oz, w.readChunk(c), w.quant)
                    : ChunkFeatures.empty(c, ox, oz);
            ChunkMesher.Mesh m = mesher.build(f, ChunkMesher.LOD_HIGH);
            for (int v = 0; v < m.vertexCount; v++) {
                float y = m.vertices[v * ChunkMesher.STRIDE + 1];
                assertTrue(Float.isFinite(y), "a platform vertex is " + y);
                assertTrue(y > -40,
                        "something was meshed " + y + " m down, which is"
                                + " geometry nobody can see from the street");
            }
        }
        assertTrue(chunks > 0, "no chunk with a station in it");
    }

    @Test
    @DisplayName("lines are named after where they actually go")
    void linesHaveNames() throws Exception {
        WorldData w = WorldFixture.world();
        Transit t = new Transit(w);
        int named = 0;
        int withTerminals = 0;
        for (int r = 0; r < w.transitCount(); r++) {
            String n = t.routeName(r);
            assertTrue(n != null && !n.isEmpty(), "route " + r + " unnamed");
            assertTrue(n.startsWith("Local") || n.startsWith("Metro")
                            || n.startsWith("Monorail") || n.startsWith("Ferry"),
                    "route " + r + " has no mode in its name: " + n);
            named++;
            if (n.contains(" - ")) { withTerminals++; }
            // Cached, so the second call is the same object's content.
            assertEquals(n, t.routeName(r), "the name is not stable");
        }
        assertTrue(named > 100, "only " + named + " named routes");
        assertTrue(withTerminals > named / 4,
                "only " + withTerminals + " of " + named + " lines could"
                        + " be named after a place, so the names are"
                        + " mostly just the mode again");
    }

    @Test
    @DisplayName("the timetable is a function of the clock, not of state")
    void departuresAreScheduled() {
        // A board at a platform nobody is standing on still has to be
        // right when somebody walks up to it.
        for (int cls : new int[]{WorldData.CLS_RAIL, WorldData.CLS_LIGHT_RAIL,
                                 WorldData.CLS_MONORAIL}) {
            double h = Transit.headway(cls);
            assertTrue(h > 60 && h < 3600, "silly headway for " + cls);
            for (int route = 0; route < 7; route++) {
                double prev = Transit.nextDeparture(route, cls, 0);
                assertTrue(prev > 0 && prev <= h,
                        "a departure outside the headway: " + prev);
                // Counts down, then wraps to a full headway.
                double at = prev - 1.0;
                double now = Transit.nextDeparture(route, cls, 1.0);
                assertEquals(at, now, 1e-6,
                        "the countdown does not track the clock");
                double after = Transit.nextDeparture(route, cls, prev + 0.5);
                assertTrue(after > h - 1.0,
                        "the board did not roll over to the next train");
            }
        }
        // Different lines do not all leave together.
        double a = Transit.nextDeparture(3, WorldData.CLS_RAIL, 0);
        double b = Transit.nextDeparture(4, WorldData.CLS_RAIL, 0);
        assertTrue(Math.abs(a - b) > 1.0,
                "every line in the city departs at the same instant");
    }
}
