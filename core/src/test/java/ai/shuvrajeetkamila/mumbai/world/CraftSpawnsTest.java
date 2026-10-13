package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.geo.GeoProjection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The moorings the compiler produced have to be moorings a craft can
 * actually sit on.
 *
 * <p>The failure this guards against is the quiet one: a pipeline change
 * that drops the craft section, or starts putting boats on dry land, would
 * leave the flight and sailing models exactly as unreachable as they were
 * before they had anywhere to be parked, and nothing else in the suite
 * would notice.
 */
final class CraftSpawnsTest {

    private static CraftSpawns craft() {
        return WorldFixture.world().craft();
    }

    @Test
    @DisplayName("the world ships somewhere to find each kind of craft")
    void everyKindIsPresent() {
        CraftSpawns c = craft();
        assertTrue(c.countOf(CraftSpawns.BOAT) >= 20,
                "boats moored: " + c.countOf(CraftSpawns.BOAT));
        assertTrue(c.countOf(CraftSpawns.HELI) >= 5,
                "helicopters parked: " + c.countOf(CraftSpawns.HELI));
        assertTrue(c.countOf(CraftSpawns.PLANE) >= 2,
                "aeroplanes parked: " + c.countOf(CraftSpawns.PLANE));
    }

    @Test
    @DisplayName("every boat can be stepped off onto dry land")
    void boatsCanBeLeft() {
        WorldData w = WorldFixture.world();
        int stranded = 0;
        for (CraftSpawns.Spawn s : craft().all()) {
            if (s.kind != CraftSpawns.BOAT) { continue; }
            boolean found = false;
            for (double r = 2; r <= 30 && !found; r += 2) {
                for (int a = 0; a < 16 && !found; a++) {
                    double th = a * Math.PI / 8.0;
                    double px = s.x + Math.cos(th) * r;
                    double pz = s.z + Math.sin(th) * r;
                    if (w.inBounds(px, pz) && w.isLand(px, pz)) { found = true; }
                }
            }
            if (!found) { stranded++; }
        }
        assertEquals(0, stranded,
                stranded + " boats are moored out of reach of dry land");
    }

    @Test
    @DisplayName("every boat is moored in water")
    void boatsFloat() {
        WorldData w = WorldFixture.world();
        CraftSpawns c = craft();
        int dry = 0;
        for (CraftSpawns.Spawn s : c.all()) {
            if (s.kind != CraftSpawns.BOAT) { continue; }
            if (w.isLand(s.x, s.z)) { dry++; }
        }
        assertEquals(0, dry, dry + " boats are moored on dry land");
    }

    @Test
    @DisplayName("boats have room to get under way")
    void boatsHaveSeaRoom() {
        WorldData w = WorldFixture.world();
        int stuck = 0;
        for (CraftSpawns.Spawn s : craft().all()) {
            if (s.kind != CraftSpawns.BOAT) { continue; }
            // Moorings are brought alongside so the player can step off,
            // so some of the compass is land by design. What matters is
            // that there is a way out: three of eight directions clear at
            // 40 m, or she is wedged in a drain.
            int open = 0;
            for (int a = 0; a < 8; a++) {
                double th = a * Math.PI / 4.0;
                double px = s.x + Math.cos(th) * 40.0;
                double pz = s.z + Math.sin(th) * 40.0;
                if (!w.inBounds(px, pz) || !w.isLand(px, pz)) { open++; }
            }
            if (open < 3) { stuck++; }
        }
        assertTrue(stuck == 0, stuck + " boats are moored with no sea room");
    }

    @Test
    @DisplayName("every aircraft is parked on land")
    void aircraftAreOnLand() {
        WorldData w = WorldFixture.world();
        int wet = 0;
        for (CraftSpawns.Spawn s : craft().all()) {
            if (s.kind == CraftSpawns.BOAT) { continue; }
            if (!w.isLand(s.x, s.z)) { wet++; }
        }
        assertEquals(0, wet, wet + " aircraft are parked in the sea");
    }

    @Test
    @DisplayName("aeroplanes are held on real runways, lined up with them")
    void aeroplanesAreOnRunways() {
        CraftSpawns c = craft();
        int withLength = 0;
        for (CraftSpawns.Spawn s : c.all()) {
            if (s.kind != CraftSpawns.PLANE) { continue; }
            // The compiler names each one with the runway it stands on and
            // that runway's real length.
            assertTrue(s.name.endsWith(" m"), "unnamed runway: " + s.name);
            String[] bits = s.name.split(" ");
            double len = Double.parseDouble(bits[bits.length - 2]);
            assertTrue(len >= 400, s.name + " is too short to take off from");
            if (len >= 2000) { withLength++; }
        }
        assertTrue(withLength >= 1,
                "no runway over 2 km: the main airport is missing");
    }

    @Test
    @DisplayName("the long runways are the ones at the real airport")
    void theLongRunwaysAreAtSahar() {
        // Chhatrapati Shivaji Maharaj International, 19.0887 N 72.8679 E.
        double ax = GeoProjection.toX(19.0887, 72.8679);
        double az = GeoProjection.toZ(19.0887);
        double best = Double.MAX_VALUE;
        for (CraftSpawns.Spawn s : craft().all()) {
            if (s.kind != CraftSpawns.PLANE) { continue; }
            String[] bits = s.name.split(" ");
            double len = Double.parseDouble(bits[bits.length - 2]);
            if (len < 2000) { continue; }
            best = Math.min(best, Math.hypot(s.x - ax, s.z - az));
        }
        assertTrue(best < 3000,
                "nearest 2 km runway is " + (int) best + " m from the airport");
    }

    @Test
    @DisplayName("craft are not all piled up in one corner of the island")
    void craftAreSpreadOut() {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (CraftSpawns.Spawn s : craft().all()) {
            minX = Math.min(minX, s.x);
            maxX = Math.max(maxX, s.x);
            minZ = Math.min(minZ, s.z);
            maxZ = Math.max(maxZ, s.z);
        }
        assertTrue(maxZ - minZ > 20000,
                "craft span only " + (int) (maxZ - minZ) + " m north to south");
        assertTrue(maxX - minX > 8000,
                "craft span only " + (int) (maxX - minX) + " m east to west");
    }

    @Test
    @DisplayName("no two craft of a kind are moored on top of each other")
    void noneAreStacked() {
        CraftSpawns c = craft();
        for (int i = 0; i < c.size(); i++) {
            for (int j = i + 1; j < c.size(); j++) {
                CraftSpawns.Spawn a = c.get(i), b = c.get(j);
                if (a.kind != b.kind) { continue; }
                double d = Math.hypot(a.x - b.x, a.z - b.z);
                assertTrue(d > 40, a.label() + " at " + (int) d + " m apart");
            }
        }
    }

    @Test
    @DisplayName("a mooring can be found, taken, moved and put back")
    void resetRestoresTheMooring() {
        CraftSpawns c = craft();
        CraftSpawns.Spawn s = c.get(0);
        double hx = s.homeX, hz = s.homeZ;
        s.x += 500;
        s.z -= 320;
        s.taken = true;
        c.reset();
        assertEquals(hx, s.x, 1e-9);
        assertEquals(hz, s.z, 1e-9);
        assertTrue(!s.taken);
    }

    @Test
    @DisplayName("a taken craft is not offered to anybody else")
    void takenCraftAreSkipped() {
        CraftSpawns c = craft();
        c.reset();
        int i = c.nearestOfKind(0, 0, CraftSpawns.BOAT);
        assertTrue(i >= 0);
        CraftSpawns.Spawn s = c.get(i);
        int found = c.nearest(s.x, s.z, 20, 0, 1, 0);
        assertEquals(i, found);
        s.taken = true;
        assertEquals(-1, c.nearest(s.x, s.z, 20, 0, 1, 0),
                "a craft already being flown was offered again");
        c.reset();
    }

    @Test
    @DisplayName("looking at one of two craft picks the one you are facing")
    void facingBreaksTheTie() {
        CraftSpawns c = craft();
        c.reset();
        // Stand exactly between the two nearest boats and look at each.
        int a = c.nearestOfKind(0, 0, CraftSpawns.BOAT);
        assertTrue(a >= 0);
        CraftSpawns.Spawn sa = c.get(a);
        int b = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < c.size(); i++) {
            if (i == a || c.get(i).kind != CraftSpawns.BOAT) { continue; }
            double d = Math.hypot(c.get(i).x - sa.x, c.get(i).z - sa.z);
            if (d < bd) { bd = d; b = i; }
        }
        assertTrue(b >= 0);
        CraftSpawns.Spawn sb = c.get(b);
        double mx = (sa.x + sb.x) * 0.5, mz = (sa.z + sb.z) * 0.5;
        double len = Math.hypot(sa.x - mx, sa.z - mz);
        double fx = (sa.x - mx) / len, fz = (sa.z - mz) / len;
        double reach = bd + 10;
        assertEquals(a, c.nearest(mx, mz, reach, fx, fz, 1.6));
        assertEquals(b, c.nearest(mx, mz, reach, -fx, -fz, 1.6));
    }

    @Test
    @DisplayName("every mooring carries a name a prompt can show")
    void everyMooringIsNamed() {
        for (CraftSpawns.Spawn s : craft().all()) {
            assertNotNull(s.name);
            assertNotNull(s.label());
            assertTrue(!s.label().isEmpty());
        }
    }
}
