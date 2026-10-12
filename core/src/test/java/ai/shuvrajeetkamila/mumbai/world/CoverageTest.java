package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.geo.GeoProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The geographic coverage gate.
 *
 * <p>This is the test that is allowed to fail the build when the map is
 * incomplete. The brief was a continuous Mumbai from Vasai Creek down to
 * Colaba, not South Mumbai with the rest implied, so every one of these
 * checkpoints is a real place that must exist in the shipped data with the
 * right kind of ground under it. Dropping the northern suburbs to save APK
 * size would turn a dozen of these red, which is the point.
 *
 * <p>The Python verifier runs the same list from {@code tools/aoi.py} against
 * the compiled files before the APK is assembled. This duplicate in Java runs
 * against the loader the game itself uses, so a bug in the reader cannot slip
 * through a green Python gate.
 */
class CoverageTest {

    /**
     * Every checkpoint: latitude, longitude, and whether the ground there
     * should be land or water. Positions come from OpenStreetMap.
     *
     * <p>This block is generated from {@code CHECKPOINTS} in
     * {@code tools/aoi.py}, which is the single source of truth and which the
     * Python verifier gates on before the APK is built. Hand-copying them
     * here was a mistake the first time round: two of the latitudes drifted,
     * and the Java gate failed on places the Python gate had passed. If the
     * list changes, regenerate rather than edit.
     */
    @ParameterizedTest(name = "{3} should be {2}")
    @CsvSource({
            "19.3250, 72.7900, water, Vasai Creek mouth",
            "19.3200, 72.8200, water, Vasai Creek channel",
            "19.3180, 72.8520, land, Vasai Creek south bank",
            "19.2900, 72.8700, land, Mira Road / Bhayandar side",
            "19.2410, 72.7822, land, Gorai",
            "19.2816, 72.7853, land, Uttan",
            "19.2500, 72.8600, land, Dahisar",
            "19.2300, 72.8570, land, Borivali",
            "19.2050, 72.8500, land, Kandivali",
            "19.1860, 72.8480, land, Malad",
            "19.1640, 72.8490, land, Goregaon",
            "19.2150, 72.9100, land, Sanjay Gandhi National Park",
            "19.2080, 72.9050, land, Kanheri ridge",
            "19.1539, 72.9129, water, Vihar/Tulsi lake system",
            "19.1281, 72.9059, water, Powai Lake",
            "19.1600, 72.8800, land, Aarey",
            "19.1900, 72.9850, water, Thane Creek north",
            "19.0900, 72.9750, water, Thane Creek navigable channel",
            "19.1200, 72.9550, land, Thane Creek mangroves",
            "19.1720, 72.9560, land, Mulund",
            "19.1430, 72.9370, land, Bhandup",
            "19.1180, 72.9050, land, Powai",
            "19.1050, 72.9280, land, Vikhroli",
            "19.0860, 72.9080, land, Ghatkopar",
            "19.0520, 72.8990, land, Chembur",
            "19.0332, 72.9489, land, Trombay",
            "19.0890, 72.8680, land, CSMIA",
            "19.0980, 72.8340, land, Juhu Aerodrome",
            "19.1190, 72.8470, land, Andheri",
            "19.1070, 72.8270, land, Juhu",
            "19.1350, 72.8130, land, Versova",
            "19.1375, 72.7919, land, Madh Island",
            "19.0590, 72.8300, land, Bandra",
            "19.0660, 72.8690, land, Bandra Kurla Complex",
            "19.0720, 72.8790, land, Kurla",
            "19.0430, 72.8520, land, Dharavi",
            "19.0400, 72.8400, land, Mahim",
            "19.0000, 72.8170, land, Worli",
            "19.0180, 72.8430, land, Dadar",
            "18.9950, 72.8300, land, Lower Parel",
            "18.9950, 72.8600, land, Sewri",
            "18.9430, 72.8230, land, Marine Drive",
            "18.9400, 72.8350, land, Fort / CSMT",
            "18.9500, 72.8450, land, Mumbai docks",
            "18.9100, 72.8150, land, Colaba",
            "18.8980, 72.8120, land, Navy Nagar",
            "19.1070, 72.7600, water, Arabian Sea off Juhu",
            "19.0400, 72.7700, water, Arabian Sea off Bandra",
            "18.9250, 72.8080, water, Back Bay",
            "18.9300, 72.9000, water, Mumbai Harbour",
            "19.0330, 72.8180, water, Mahim Bay",
    })
    void checkpoint(double lat, double lon, String expect, String label) {
        WorldData w = WorldFixture.world();
        double x = GeoProjection.toX(lat, lon);
        double z = GeoProjection.toZ(lat);
        assertTrue(w.inBounds(x, z),
                label + " at " + lat + "," + lon + " falls outside the world - "
                        + "the area of interest does not cover it");
        boolean land = w.isLand(x, z);
        if (expect.trim().equals("land")) {
            assertTrue(land, label + " should be land but the world says water");
        } else {
            assertFalse(land, label + " should be water but the world says land, "
                    + "elevation " + w.height(x, z) + " m");
        }
    }

    @Test
    @DisplayName("the world spans Vasai Creek to Colaba without a gap")
    void northToSouthIsContinuous() {
        WorldData w = WorldFixture.world();
        // Walk down the spine of Salsette at 500 m intervals. Every sample
        // must be inside the world; a hole would mean a missing region.
        int outside = 0;
        int samples = 0;
        for (double lat = 19.330; lat >= 18.900; lat -= 0.0045) {
            double lon = 72.84 + (19.33 - lat) * 0.06;    // follows the island
            double x = GeoProjection.toX(lat, lon);
            double z = GeoProjection.toZ(lat);
            samples++;
            if (!w.inBounds(x, z)) { outside++; }
        }
        assertEquals(0, outside,
                outside + " of " + samples + " samples down the length of the "
                        + "island fall outside the world");
        assertTrue(samples > 80, "the transect should be long, was " + samples);
    }

    @Test
    @DisplayName("the world really is about 52 by 72 kilometres")
    void extent() {
        WorldData w = WorldFixture.world();
        double widthKm = (w.maxWorldX() - w.minWorldX()) / 1000.0;
        double heightKm = (w.maxWorldZ() - w.minWorldZ()) / 1000.0;
        assertTrue(widthKm > 45 && widthKm < 60,
                "east-west extent was " + widthKm + " km");
        assertTrue(heightKm > 65 && heightKm < 80,
                "north-south extent was " + heightKm + " km");
    }

    @Test
    @DisplayName("there is a real water buffer beyond the coast")
    void waterBuffer() {
        WorldData w = WorldFixture.world();
        // Five kilometres due west of Marine Drive must still be in the world
        // and must be sea, so there is somewhere to sail and to fly.
        double x = GeoProjection.toX(18.9430, 72.8230) - 6000;
        double z = GeoProjection.toZ(18.9430);
        assertTrue(w.inBounds(x, z), "6 km offshore is outside the world");
        assertFalse(w.isLand(x, z), "6 km west of Marine Drive should be sea");
    }

    @Test
    @DisplayName("the land and sea split is plausible, not all one thing")
    void landFraction() {
        WorldData w = WorldFixture.world();
        int land = 0, total = 0;
        for (int j = 0; j < w.nz; j += 7) {
            for (int i = 0; i < w.nx; i += 7) {
                total++;
                if (w.landAt(i, j)) { land++; }
            }
        }
        double frac = land / (double) total;
        assertTrue(frac > 0.25 && frac < 0.60,
                "land fraction of " + frac + " does not look like a coastal city");
    }
}
