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
        WorldData w = WorldFixture.world();
        assertTrue(w.hasTransitProfile(),
                "the world has no compiled vertical alignment, so every"
                        + " train is draped over the raw terrain");

        // Grade is a property of a PLACE on a line, not of its OSM
        // class. A single suburban route is at grade, then up on an
        // embankment, then over a creek on a bridge. Deciding it from
        // the class alone is what buried the whole Mumbai Metro.
        assertEquals(TransitStops.Grade.ELEVATED,
                TransitStops.gradeAt(WorldData.CLS_SUBWAY, 18.0, 10.0, true),
                "a metro eight metres over the street is on a viaduct");
        assertEquals(TransitStops.Grade.UNDERGROUND,
                TransitStops.gradeAt(WorldData.CLS_SUBWAY, -2.0, 10.0, true));
        assertEquals(TransitStops.Grade.SURFACE,
                TransitStops.gradeAt(WorldData.CLS_RAIL, 10.9, 10.0, true));
        assertEquals(TransitStops.Grade.WATER,
                TransitStops.gradeAt(WorldData.CLS_FERRY, 0, 0, false));

        // And the network really has all three in it.
        boolean over = false, on = false, under = false;
        TransitStops stops = w.transitStops();
        for (int i = 0; i < stops.count(); i++) {
            switch (stops.get(i).grade) {
                case ELEVATED: over = true; break;
                case SURFACE: on = true; break;
                case UNDERGROUND: under = true; break;
                default: break;
            }
        }
        assertTrue(over, "no elevated station in the city");
        assertTrue(on, "no surface station in the city");
        assertTrue(under, "no bored station in the city");
    }

    @Test
    @DisplayName("the track is laid to a gradient a train could climb")
    void theTrackIsNotARollerCoaster() throws Exception {
        // The alignment used to be the terrain plus a constant, which
        // gave a worst gradient of 91 per cent and 18 m steps between
        // vertices twenty metres apart. Nothing on steel wheels climbs
        // that. Six per cent is the loosest class here, the monorail.
        WorldData w = WorldFixture.world();
        double worst = 0;
        int worstRoute = -1;
        for (int r = 0; r < w.transitCount(); r++) {
            if (w.transitClass(r) == WorldData.CLS_FERRY) { continue; }
            int n = w.transitPointCount(r);
            for (int i = 1; i < n; i++) {
                double d = Math.hypot(w.transitX(r, i) - w.transitX(r, i - 1),
                        w.transitZ(r, i) - w.transitZ(r, i - 1));
                if (d < 0.5) { continue; }
                double g = Math.abs(w.transitY(r, i) - w.transitY(r, i - 1)) / d;
                if (g > worst) { worst = g; worstRoute = r; }
            }
        }
        assertTrue(worst <= 0.065,
                "worst gradient is " + Math.round(worst * 100) + "% on route "
                        + worstRoute + "; a railway cannot climb that");
    }

    @Test
    @DisplayName("the railway crosses water on a bridge, not through it")
    void theTrackDoesNotFordTheCreeks() throws Exception {
        // Nearly nine per cent of the suburban network is over water -
        // Vasai Creek, Thane Creek, the Mahim causeway. Taking the
        // height from the terrain put the rail head at 0.9 m there,
        // which is inside a tide that swings 1.9 m either way.
        WorldData w = WorldFixture.world();
        int wet = 0, low = 0;
        double lowest = Double.MAX_VALUE;
        for (int r = 0; r < w.transitCount(); r++) {
            if (w.transitClass(r) == WorldData.CLS_FERRY) { continue; }
            int n = w.transitPointCount(r);
            for (int i = 0; i < n; i++) {
                double x = w.transitX(r, i), z = w.transitZ(r, i);
                if (w.isLand(x, z)) { continue; }
                double y = w.transitY(r, i);
                // A bored line passing under a creek is not fording it.
                // Metro Line 3 runs under Mahalaxmi, and under the water
                // there, at about nine metres down - which is a tunnel,
                // not a train in the sea.
                if (TransitStops.gradeAt(w.transitClass(r), y, 0.0, false)
                        == TransitStops.Grade.UNDERGROUND) {
                    continue;
                }
                wet++;
                lowest = Math.min(lowest, y);
                if (y < 2.5) { low++; }
            }
        }
        assertTrue(wet > 200, "only " + wet + " rail vertices over water");
        assertEquals(0, low,
                low + " of " + wet + " over-water rail vertices are under"
                        + " 2.5 m, the lowest at " + String.format("%.2f", lowest)
                        + " m - that is a train wading through the sea");
    }

    @Test
    @DisplayName("the Mumbai Metro is on its viaduct, not buried")
    void theMetroIsAboveGround() throws Exception {
        // 147 of the 355 OSM ways making up the metro are tagged as
        // bridges carrying layer +1 to +3. The class says "subway" and
        // the code believed it, so all 196 km of line ran twelve metres
        // underground with no platforms and nothing to see.
        WorldData w = WorldFixture.world();
        int above = 0, below = 0;
        for (int r = 0; r < w.transitCount(); r++) {
            if (w.transitClass(r) != WorldData.CLS_SUBWAY) { continue; }
            int n = w.transitPointCount(r);
            for (int i = 0; i < n; i++) {
                double x = w.transitX(r, i), z = w.transitZ(r, i);
                double g = w.isLand(x, z) ? w.height(x, z) : 0.0;
                double rel = w.transitY(r, i) - g;
                if (rel > TransitStops.ELEVATED_M) { above++; }
                if (rel < TransitStops.UNDERGROUND_M) { below++; }
            }
        }
        assertTrue(above + below > 500, "the metro has barely any geometry");
        assertTrue(above > below,
                "only " + above + " of " + (above + below) + " metro vertices"
                        + " are on a viaduct; Mumbai's metro is mostly"
                        + " elevated, not bored");
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

    @Test
    @DisplayName("every station in the city can actually be used")
    void everyStationIsReachable() throws Exception {
        // Two in every five stations were impossible to board. The
        // height test was flat - "within four metres of the door" - and
        // 267 platforms sit up to twenty metres above the street on a
        // viaduct while another 58 are in a tunnel under it, one of them
        // 144 m down beneath a hill. The train called, the prompt never
        // appeared, and you stood underneath looking up at it.
        WorldData w = WorldFixture.world();
        Transit t = new Transit(w);
        t.setOcean(new ai.shuvrajeetkamila.mumbai.physics.Ocean());
        TransitStops stops = w.transitStops();

        int elevated = 0, underground = 0, surface = 0;
        int reachable = 0, tested = 0;
        for (int r = 0; r < w.transitCount(); r++) {
            if (w.transitClass(r) == WorldData.CLS_FERRY) { continue; }
            for (int i = 0; i < t.stopCount(r); i++) {
                Transit.Service sv = t.services()[0];
                sv.active = true;
                sv.atStop = true;
                sv.dwell = 10;
                sv.route = r;
                sv.cls = w.transitClass(r);
                sv.carriages = 1;
                sv.dir = 1;
                sv.s = t.stopDistance(r, i);
                double[] q = t.pointAt(r, sv.s);
                boolean land = w.isLand(q[0], q[1]);
                double street = land ? w.height(q[0], q[1]) : 0.0;
                TransitStops.Grade g = TransitStops.gradeAt(
                        w.transitClass(r), q[2], street, land);
                tested++;
                switch (g) {
                    case ELEVATED: elevated++; break;
                    case UNDERGROUND: underground++; break;
                    default: surface++; break;
                }
                // A player on the pavement outside, which is the only
                // place they can be: the platform is unreachable on foot
                // either way, up a viaduct or down a tunnel.
                if (t.boardableAt(q[0], street + 0.9, q[1]) >= 0) { reachable++; }
            }
        }
        assertTrue(tested > 100, "only sampled " + tested + " stations");
        assertTrue(elevated > 20 && underground > 5 && surface > 20,
                "the sample missed a whole kind of station: elevated="
                        + elevated + " underground=" + underground
                        + " surface=" + surface);
        assertEquals(tested, reachable,
                (tested - reachable) + " of " + tested + " stations could"
                        + " not be boarded from the street outside them");
    }
}
