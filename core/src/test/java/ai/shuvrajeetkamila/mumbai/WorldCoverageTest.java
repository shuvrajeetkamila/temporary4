package ai.shuvrajeetkamila.mumbai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ai.shuvrajeetkamila.mumbai.geo.GeoProjection;
import ai.shuvrajeetkamila.mumbai.world.LandUse;
import ai.shuvrajeetkamila.mumbai.world.WorldData;

/**
 * Geographic coverage gate.
 *
 * <p>These are the tests that make it impossible to quietly ship "South Mumbai only".
 * Every checkpoint from Vasai Creek in the north to Navy Nagar in the south, and from the
 * Arabian Sea in the west to the western bank of Thane Creek in the east, has to exist in
 * the packaged data with the right character (land where there should be land, water where
 * there should be water, a national park that is actually forest, airports that are
 * actually flat). If a region is dropped to save space, this fails and the build fails.
 */
public class WorldCoverageTest {

    private final WorldData world = TestWorld.get();

    /** Terrain character a checkpoint must have. */
    private static final int LAND = 0;
    private static final int OPEN_WATER = 1;
    /** Shoreline: land, water, mangrove or mudflat all count, but open sea does not. */
    private static final int COASTAL = 2;

    /** The fourteen mandated regions and the character each must have. */
    private static final Object[][] REGIONS = {
            //  key,                     lat,     lon,     character
            {"vasai_creek_north", 19.330, 72.840, COASTAL},
            {"northern_salsette", 19.290, 72.860, LAND},
            {"dahisar_borivali_belt", 19.220, 72.855, LAND},
            {"sgnp_lakes", 19.170, 72.905, COASTAL},
            {"thane_creek_west", 19.150, 72.955, COASTAL},
            {"eastern_suburbs", 19.070, 72.915, LAND},
            {"airports", 19.094, 72.852, LAND},
            {"andheri_juhu_versova", 19.120, 72.830, LAND},
            {"bandra_bkc_dharavi", 19.055, 72.852, LAND},
            {"central_mumbai", 19.000, 72.825, LAND},
            {"south_mumbai", 18.935, 72.830, LAND},
            {"colaba_navy_nagar", 18.900, 72.818, LAND},
            {"arabian_sea_west", 19.140, 72.770, OPEN_WATER},
            {"mumbai_harbour", 18.950, 72.890, OPEN_WATER},
    };

    @Test
    public void allFourteenCheckpointsArePackaged() {
        assertEquals("checkpoint count baked into the world manifest",
                REGIONS.length, world.checkpoints.size());
        for (Object[] region : REGIONS) {
            String key = (String) region[0];
            boolean found = false;
            for (WorldData.Checkpoint c : world.checkpoints) {
                if (c.key.equals(key)) {
                    found = true;
                    break;
                }
            }
            assertTrue("missing geographic checkpoint: " + key, found);
        }
    }

    @Test
    public void everyCheckpointIsInsideTheWorldBounds() {
        for (WorldData.Checkpoint c : world.checkpoints) {
            assertTrue(c.key + " is outside the packaged world in X: " + c.x,
                    c.x > world.minX && c.x < world.maxX);
            assertTrue(c.key + " is outside the packaged world in Z: " + c.z,
                    c.z > world.minZ && c.z < world.maxZ);
            assertTrue(c.key + " is outside the land-use grid", world.inBounds(c.x, c.z));
        }
    }

    @Test
    public void everyRegionHasTheRightLandOrWaterCharacter() {
        GeoProjection proj = world.projection();
        StringBuilder problems = new StringBuilder();
        for (Object[] region : REGIONS) {
            String key = (String) region[0];
            double lat = (Double) region[1];
            double lon = (Double) region[2];
            int want = (Integer) region[3];
            double x = proj.worldX(lat, lon);
            double z = proj.worldZ(lat);
            int lu = world.landUseAt(x, z);
            boolean ok;
            switch (want) {
                case LAND:
                    ok = !LandUse.isWater(lu);
                    break;
                case OPEN_WATER:
                    ok = LandUse.isWater(lu);
                    break;
                default:
                    // Coastal: anything except open ocean, and within 2.5 km there must
                    // be both land and water, which is what makes it a shoreline.
                    ok = lu != LandUse.DEEP_SEA && hasBoth(x, z, 2500.0);
                    break;
            }
            if (!ok) {
                problems.append(String.format(
                        "%s at (%.4f, %.4f) -> (%.0f, %.0f) is %s, which fails its "
                                + "required character%n",
                        key, lat, lon, x, z, LandUse.name(lu)));
            }
        }
        assertTrue(problems.toString(), problems.length() == 0);
    }

    /** True when both land and water occur within a radius: the test for a shoreline. */
    private boolean hasBoth(double x, double z, double radius) {
        boolean land = false;
        boolean water = false;
        for (int a = 0; a < 24; a++) {
            double ang = a / 24.0 * Math.PI * 2.0;
            for (double r = 150; r <= radius; r += 150) {
                double sx = x + Math.cos(ang) * r;
                double sz = z + Math.sin(ang) * r;
                if (!world.inBounds(sx, sz)) {
                    continue;
                }
                if (LandUse.isWater(world.landUseAt(sx, sz))) {
                    water = true;
                } else {
                    land = true;
                }
                if (land && water) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void northSouthExtentCoversVasaiCreekToNavyNagar() {
        GeoProjection proj = world.projection();
        double northZ = proj.worldZ(19.43);      // north of Vasai Creek
        double southZ = proj.worldZ(18.84);      // south of Navy Nagar
        assertTrue("world does not reach the northern AOI edge",
                world.minZ <= northZ + 1.0);
        assertTrue("world does not reach the southern AOI edge",
                world.maxZ >= southZ - 1.0);
        double spanKm = (world.maxZ - world.minZ) / 1000.0;
        assertTrue("north-south span is only " + spanKm + " km, expected at least 60 km",
                spanKm >= 60.0);
    }

    @Test
    public void eastWestExtentCoversTheSeaAndThaneCreek() {
        GeoProjection proj = world.projection();
        double westX = proj.worldX(19.1, 72.66);
        double eastX = proj.worldX(19.1, 73.08);
        assertTrue("world does not reach the Arabian Sea buffer", world.minX <= westX + 1.0);
        assertTrue("world does not reach the Thane Creek / mainland edge",
                world.maxX >= eastX - 1.0);
        double spanKm = (world.maxX - world.minX) / 1000.0;
        assertTrue("east-west span is only " + spanKm + " km, expected at least 40 km",
                spanKm >= 40.0);
    }

    @Test
    public void northernSuburbsContainRealLandNotJustOcean() {
        // Sample a grid over the northern third of the island and require that a large
        // part of it is land. This is the specific failure mode the brief calls out:
        // shipping only the southern tourist strip.
        GeoProjection proj = world.projection();
        int land = 0;
        int total = 0;
        for (double lat = 19.15; lat <= 19.30; lat += 0.01) {
            for (double lon = 72.80; lon <= 72.93; lon += 0.01) {
                double x = proj.worldX(lat, lon);
                double z = proj.worldZ(lat);
                if (!world.inBounds(x, z)) {
                    continue;
                }
                total++;
                if (!LandUse.isWater(world.landUseAt(x, z))) {
                    land++;
                }
            }
        }
        assertTrue("no samples taken in the northern suburbs", total > 50);
        double fraction = land / (double) total;
        assertTrue("northern suburbs are only " + Math.round(fraction * 100)
                + "% land; the northern landmass is missing", fraction > 0.6);
    }

    @Test
    public void easternSuburbsContainRealLand() {
        GeoProjection proj = world.projection();
        int land = 0;
        int total = 0;
        for (double lat = 19.02; lat <= 19.14; lat += 0.01) {
            for (double lon = 72.88; lon <= 72.93; lon += 0.01) {
                double x = proj.worldX(lat, lon);
                double z = proj.worldZ(lat);
                if (!world.inBounds(x, z)) {
                    continue;
                }
                total++;
                if (!LandUse.isWater(world.landUseAt(x, z))) {
                    land++;
                }
            }
        }
        assertTrue("no samples taken in the eastern suburbs", total > 20);
        double fraction = land / (double) total;
        assertTrue("eastern suburbs are only " + Math.round(fraction * 100) + "% land",
                fraction > 0.5);
    }

    @Test
    public void nationalParkIsForestedAndHilly() {
        GeoProjection proj = world.projection();
        double x = proj.worldX(19.21, 72.91);
        double z = proj.worldZ(19.21);
        int lu = world.landUseAt(x, z);
        assertTrue("Sanjay Gandhi National Park should be forest or park, got "
                + LandUse.name(lu), lu == LandUse.FOREST || lu == LandUse.PARK);
        float peak = 0f;
        for (double la = 19.16; la <= 19.26; la += 0.005) {
            for (double lo = 72.88; lo <= 72.98; lo += 0.005) {
                peak = Math.max(peak, world.heightAt(proj.worldX(la, lo), proj.worldZ(la)));
            }
        }
        assertTrue("the park hills are flat (" + peak + " m); expected a real ridge",
                peak > 200f);
    }

    @Test
    public void bothAirportsExistWithRealRunwayLengths() {
        assertTrue("no airports packaged", world.airports.size() >= 2);
        WorldData.Airport main = null;
        for (WorldData.Airport a : world.airports) {
            if ("VABB".equals(a.icao)) {
                main = a;
            }
        }
        assertNotNull("the main international airport is missing", main);
        assertTrue("the main airport has no runways", main.runways.size() >= 2);
        float longest = 0f;
        for (WorldData.Runway r : main.runways) {
            longest = Math.max(longest, r.length);
        }
        // The real primary runway is about 3.4 km; a toy 800 m strip would fail this.
        assertTrue("primary runway is only " + longest + " m long", longest > 3000f);
        assertTrue("primary runway is implausibly long: " + longest, longest < 4200f);
    }

    @Test
    public void railwayAndRoadNetworksSpanTheIsland() {
        assertTrue("too few road corridors: " + world.roads.size(), world.roads.size() >= 20);
        assertTrue("too few railway corridors: " + world.rails.size(), world.rails.size() >= 5);
        double roadKm = 0;
        for (WorldData.Polyline p : world.roads) {
            roadKm += p.length;
        }
        roadKm /= 1000.0;
        assertTrue("only " + Math.round(roadKm) + " km of road corridors", roadKm > 150);

        // A corridor has to actually reach the north: find the northernmost road vertex.
        float northernmost = Float.MAX_VALUE;
        for (WorldData.Polyline p : world.roads) {
            for (int i = 0; i < p.pointCount(); i++) {
                northernmost = Math.min(northernmost, p.z(i));
            }
        }
        GeoProjection proj = world.projection();
        double vasaiZ = proj.worldZ(19.30);
        assertTrue("no road corridor reaches the northern suburbs",
                northernmost < vasaiZ + 500);
    }

    @Test
    public void districtsCoverTheWholeIslandNotJustTheSouth() {
        assertTrue("too few districts: " + world.districts.size(), world.districts.size() >= 50);
        int north = 0;
        int east = 0;
        GeoProjection proj = world.projection();
        double northBoundary = proj.worldZ(19.15);
        double eastBoundary = proj.worldX(19.1, 72.90);
        for (WorldData.District d : world.districts) {
            if (d.z < northBoundary) {
                north++;
            }
            if (d.x > eastBoundary) {
                east++;
            }
        }
        assertTrue("only " + north + " districts north of 19.15", north >= 10);
        assertTrue("only " + east + " districts east of 72.90", east >= 4);
    }

    @Test
    public void chunkIndexAgreesWithTheLandUseGrid() {
        int landChunks = 0;
        for (int cz = 0; cz < world.chunksZ; cz++) {
            for (int cx = 0; cx < world.chunksX; cx++) {
                if ((world.chunkFlags(cx, cz) & WorldData.CHUNK_HAS_LAND) != 0) {
                    landChunks++;
                }
            }
        }
        assertTrue("only " + landChunks + " chunks contain land", landChunks > 6000);
        // The chunk index must agree with the land-use raster everywhere, not just on
        // average: a drifting index makes the streamer render sea over a suburb.
        GeoProjection proj = world.projection();
        int mismatches = 0;
        int checked = 0;
        for (double lat = 18.86; lat <= 19.41; lat += 0.013) {
            for (double lon = 72.68; lon <= 73.06; lon += 0.013) {
                double x = proj.worldX(lat, lon);
                double z = proj.worldZ(lat);
                if (!world.inBounds(x, z)) {
                    continue;
                }
                int ccx = world.chunkIndexX(x);
                int ccz = world.chunkIndexZ(z);
                if (!world.chunkInRange(ccx, ccz)) {
                    continue;
                }
                checked++;
                boolean cellIsLand = !LandUse.isWater(world.landUseAt(x, z));
                boolean chunkHasLand =
                        (world.chunkFlags(ccx, ccz) & WorldData.CHUNK_HAS_LAND) != 0;
                if (cellIsLand && !chunkHasLand) {
                    mismatches++;
                }
            }
        }
        assertTrue("no samples checked", checked > 500);
        assertTrue(mismatches + " of " + checked
                + " land samples fall in chunks the index calls water-only",
                mismatches == 0);
    }

    @Test
    public void waterBufferSurroundsTheLandmass() {
        assertTrue("water buffer too small: " + world.waterBuffer, world.waterBuffer >= 5000f);
        GeoProjection proj = world.projection();
        // 5 km west of the Versova shoreline must still be navigable water inside bounds.
        double x = proj.worldX(19.14, 72.72);
        double z = proj.worldZ(19.14);
        assertTrue("the western sea buffer is not inside the world", world.inBounds(x, z));
        assertTrue("the western buffer is not water",
                LandUse.isWater(world.landUseAt(x, z)));
    }

    @Test
    public void elevationFieldIsPlausible() {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (double x = world.minX + 500; x < world.maxX; x += 500) {
            for (double z = world.minZ + 500; z < world.maxZ; z += 500) {
                float h = world.heightAt(x, z);
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        assertTrue("sea bed never goes below sea level", min < -2f);
        assertTrue("highest point is only " + max + " m", max > 300f);
        assertTrue("highest point is implausible: " + max, max < 700f);
    }

    @Test
    public void spawnPointIsOnLand() {
        assertFalse("the default spawn is in the sea",
                LandUse.isWater(world.landUseAt(world.spawnX, world.spawnZ)));
    }
}
