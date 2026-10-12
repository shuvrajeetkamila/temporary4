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
    public void projectedDistanceMatchesVincentyGeodesicAcrossTheWholeAoi() {
        GeoProjection p = new GeoProjection();
        double[][] pairs = {
                {COLABA_LAT, COLABA_LON, VASAI_CREEK_LAT, VASAI_CREEK_LON},
                {GATEWAY_LAT, GATEWAY_LON, CSMIA_LAT, CSMIA_LON},
                {19.0176, 72.8562, 19.0176, 73.0000},
                {19.0176, 72.8562, 19.2500, 72.8562},
                {18.8900, 72.8200, 19.3400, 72.7950},
                {19.1400, 72.7700, 19.1400, 73.0200},
        };
        for (double[] q : pairs) {
            double x1 = p.worldX(q[0], q[1]);
            double z1 = p.worldZ(q[0]);
            double x2 = p.worldX(q[2], q[3]);
            double z2 = p.worldZ(q[2]);
            double projected = GeoProjection.planarDistance(x1, z1, x2, z2);
            double truth = vincenty(q[0], q[1], q[2], q[3]);
            double relErr = Math.abs(projected - truth) / Math.max(1.0, truth);
            assertTrue("distance error " + relErr + " (" + projected + " vs " + truth + ")",
                    relErr < 0.0012);
        }
    }

    /**
     * Independent WGS84 geodesic reference (Vincenty inverse), used only by the tests so the
     * projection is validated against a different algorithm rather than against itself.
     */
    private static double vincenty(double lat1, double lon1, double lat2, double lon2) {
        double a = 6378137.0;
        double f = 1 / 298.257223563;
        double b = (1 - f) * a;
        double L = Math.toRadians(lon2 - lon1);
        double U1 = Math.atan((1 - f) * Math.tan(Math.toRadians(lat1)));
        double U2 = Math.atan((1 - f) * Math.tan(Math.toRadians(lat2)));
        double sinU1 = Math.sin(U1), cosU1 = Math.cos(U1);
        double sinU2 = Math.sin(U2), cosU2 = Math.cos(U2);
        double lambda = L, lambdaP, sinSigma = 0, cosSigma = 0, sigma = 0, cos2SigmaM = 0,
                sinAlpha, cosSqAlpha = 0;
        int iter = 0;
        do {
            double sinLambda = Math.sin(lambda), cosLambda = Math.cos(lambda);
            sinSigma = Math.sqrt((cosU2 * sinLambda) * (cosU2 * sinLambda)
                    + (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda)
                    * (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda));
            if (sinSigma == 0) {
                return 0;
            }
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma;
            cosSqAlpha = 1 - sinAlpha * sinAlpha;
            cos2SigmaM = cosSqAlpha == 0 ? 0 : cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha;
            double C = f / 16 * cosSqAlpha * (4 + f * (4 - 3 * cosSqAlpha));
            lambdaP = lambda;
            lambda = L + (1 - C) * f * sinAlpha
                    * (sigma + C * sinSigma * (cos2SigmaM + C * cosSigma
                    * (-1 + 2 * cos2SigmaM * cos2SigmaM)));
        } while (Math.abs(lambda - lambdaP) > 1e-12 && ++iter < 200);
        double uSq = cosSqAlpha * (a * a - b * b) / (b * b);
        double A = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)));
        double B = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)));
        double deltaSigma = B * sinSigma * (cos2SigmaM + B / 4 * (cosSigma
                * (-1 + 2 * cos2SigmaM * cos2SigmaM) - B / 6 * cos2SigmaM
                * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
        return b * A * (sigma - deltaSigma);
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
