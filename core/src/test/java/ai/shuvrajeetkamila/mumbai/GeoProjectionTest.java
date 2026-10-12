package ai.shuvrajeetkamila.mumbai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ai.shuvrajeetkamila.mumbai.geo.GeoProjection;

/**
 * The projection is the foundation of 1:1 scale: a metre in the game must be a metre in
 * Mumbai. These tests check round-tripping and true ground distances across the AOI.
 */
public class GeoProjectionTest {

    private static final double COLABA_LAT = 18.8970, COLABA_LON = 72.8147;
    private static final double VASAI_CREEK_LAT = 19.3700, VASAI_CREEK_LON = 72.8300;
    private static final double GATEWAY_LAT = 18.9220, GATEWAY_LON = 72.8347;
    private static final double CSMIA_LAT = 19.0896, CSMIA_LON = 72.8656;

    @Test
    public void roundTripsEveryCornerOfTheAoi() {
        GeoProjection p = new GeoProjection();
        double[][] pts = {
                {COLABA_LAT, COLABA_LON},
                {VASAI_CREEK_LAT, VASAI_CREEK_LON},
                {19.2000, 72.7700},      // Arabian Sea, west of Madh
                {19.1000, 73.0200},      // Thane Creek western bank
                {GATEWAY_LAT, GATEWAY_LON},
                {CSMIA_LAT, CSMIA_LON},
        };
        for (double[] pt : pts) {
            double x = p.worldX(pt[0], pt[1]);
            double z = p.worldZ(pt[0]);
            double lat = p.toLat(z);
            double lon = p.toLon(x, z);
            assertEquals("lat round trip", pt[0], lat, 1e-9);
            assertEquals("lon round trip", pt[1], lon, 1e-9);
        }
    }

    @Test
    public void projectedDistanceMatchesGroundTruthWithinOneMetrePerKilometre() {
        GeoProjection p = new GeoProjection();
        double[][] pairs = {
                {COLABA_LAT, COLABA_LON, VASAI_CREEK_LAT, VASAI_CREEK_LON},
                {GATEWAY_LAT, GATEWAY_LON, CSMIA_LAT, CSMIA_LON},
                {19.0176, 72.8562, 19.0176, 73.0000},
                {19.0176, 72.8562, 19.2500, 72.8562},
        };
        for (double[] q : pairs) {
            double x1 = p.worldX(q[0], q[1]);
            double z1 = p.worldZ(q[0]);
            double x2 = p.worldX(q[2], q[3]);
            double z2 = p.worldZ(q[2]);
            double projected = GeoProjection.planarDistance(x1, z1, x2, z2);
            double truth = GeoProjection.haversineMetres(q[0], q[1], q[2], q[3]);
            double relErr = Math.abs(projected - truth) / Math.max(1.0, truth);
            assertTrue("distance error " + relErr + " for " + projected + " vs " + truth,
                    relErr < 0.002);
        }
    }

    @Test
    public void northIsNegativeZAndEastIsPositiveX() {
        GeoProjection p = new GeoProjection();
        double zNorth = p.worldZ(19.3000);
        double zSouth = p.worldZ(18.9000);
        assertTrue("north must be more negative in Z", zNorth < zSouth);
        double xEast = p.worldX(19.0176, 73.0000);
        double xWest = p.worldX(19.0176, 72.7000);
        assertTrue("east must be larger in X", xEast > xWest);
    }

    @Test
    public void bearingsAreCompassCorrect() {
        assertEquals(0f, GeoProjection.bearingDeg(0, -1), 0.01f);    // north
        assertEquals(90f, GeoProjection.bearingDeg(1, 0), 0.01f);    // east
        assertEquals(180f, GeoProjection.bearingDeg(0, 1), 0.01f);   // south
        assertEquals(270f, GeoProjection.bearingDeg(-1, 0), 0.01f);  // west
    }

    @Test
    public void theAoiSpansAtLeastFiftyKilometresNorthToSouth() {
        double span = GeoProjection.haversineMetres(COLABA_LAT, COLABA_LON, VASAI_CREEK_LAT, COLABA_LON);
        assertTrue("Mumbai island must span >= 50 km N-S, got " + span, span > 50_000.0);
    }
}
