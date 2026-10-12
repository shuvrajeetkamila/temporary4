package ai.shuvrajeetkamila.mumbai.geo;

/**
 * The one place where latitude and longitude become metres.
 *
 * <p>A local east/north/up frame on the WGS84 ellipsoid, with the origin near
 * the middle of Salsette. The game's world frame is right-handed with
 * <b>x east, y up, z south</b>, so north is negative z. Everything downstream —
 * physics, streaming, collision, the minimap — works in these metres and never
 * sees a degree again. Feeding degrees to a physics integrator is how you get a
 * car that accelerates differently depending on which way it is pointing.
 *
 * <p>This is a deliberate reimplementation of the maths in {@code tools/aoi.py}.
 * {@code GeoProjectionTest} checks the two against each other at the AOI corners
 * using values exported by the Python side, because if the compiler and the game
 * disagree about where 19.076 N is, every building in the world is in the wrong
 * place and nothing else will tell you.
 *
 * <p>All methods are static and allocation-free, and the class holds no state,
 * so the streaming workers can call it concurrently.
 */
public final class GeoProjection {

    /** Semi-major axis, metres. */
    public static final double A = 6378137.0;
    /** Flattening. */
    public static final double F = 1.0 / 298.257223563;
    /** First eccentricity squared. */
    public static final double E2 = F * (2.0 - F);

    public static final double ORIGIN_LAT = 19.0760;
    public static final double ORIGIN_LON = 72.8777;

    /** Meridian arc from the equator to the origin latitude, metres. */
    private static final double M0 = meridianArc(ORIGIN_LAT);

    /** Series coefficients for the meridian arc, to e^6. */
    private static final double C0 = 1.0 - E2 / 4.0 - 3.0 * E2 * E2 / 64.0
            - 5.0 * E2 * E2 * E2 / 256.0;
    private static final double C2 = 3.0 / 8.0 * (E2 + E2 * E2 / 4.0
            + 15.0 * E2 * E2 * E2 / 128.0);
    private static final double C4 = 15.0 / 256.0 * (E2 * E2 + 3.0 * E2 * E2 * E2 / 4.0);
    private static final double C6 = 35.0 * E2 * E2 * E2 / 3072.0;

    private GeoProjection() {
    }

    /** Distance from the equator along the meridian to {@code latDeg}, metres. */
    public static double meridianArc(double latDeg) {
        double lat = Math.toRadians(latDeg);
        return A * (C0 * lat - C2 * Math.sin(2 * lat)
                + C4 * Math.sin(4 * lat) - C6 * Math.sin(6 * lat));
    }

    /** Radius of curvature in the prime vertical at {@code latDeg}, metres. */
    public static double primeVertical(double latDeg) {
        double s = Math.sin(Math.toRadians(latDeg));
        return A / Math.sqrt(1.0 - E2 * s * s);
    }

    /** Meridional radius of curvature at {@code latDeg}, metres. */
    public static double meridionalRadius(double latDeg) {
        double s = Math.sin(Math.toRadians(latDeg));
        double d = 1.0 - E2 * s * s;
        return A * (1.0 - E2) / (d * Math.sqrt(d));
    }

    /** East offset of a geodetic position from the origin, metres. */
    public static double toX(double lat, double lon) {
        return Math.toRadians(lon - ORIGIN_LON) * primeVertical(lat)
                * Math.cos(Math.toRadians(lat));
    }

    /** South offset of a geodetic position from the origin, metres (north is negative). */
    public static double toZ(double lat) {
        return -(meridianArc(lat) - M0);
    }

    /** Latitude, degrees, from a world z. Four Newton steps on the meridian arc. */
    public static double toLat(double z) {
        double target = M0 - z;
        double lat = Math.toDegrees(target / (A * C0));
        for (int i = 0; i < 4; i++) {
            lat -= Math.toDegrees((meridianArc(lat) - target) / meridionalRadius(lat));
        }
        return lat;
    }

    /** Longitude, degrees, from a world position. */
    public static double toLon(double x, double z) {
        double lat = toLat(z);
        return ORIGIN_LON + Math.toDegrees(
                x / (primeVertical(lat) * Math.cos(Math.toRadians(lat))));
    }

    /**
     * Great-ellipse surface distance between two geodetic points, metres, by
     * Vincenty's inverse formula.
     *
     * <p>Used only by tests as independent ground truth. A spherical haversine
     * with a mean Earth radius is about half a percent long over a north-south
     * line at this latitude, which is sixty metres on a twelve kilometre
     * checkpoint span — enough to make a correct projection look broken.
     */
    public static double vincenty(double lat1, double lon1, double lat2, double lon2) {
        double b = A * (1.0 - F);
        double u1 = Math.atan((1 - F) * Math.tan(Math.toRadians(lat1)));
        double u2 = Math.atan((1 - F) * Math.tan(Math.toRadians(lat2)));
        double sinU1 = Math.sin(u1), cosU1 = Math.cos(u1);
        double sinU2 = Math.sin(u2), cosU2 = Math.cos(u2);
        double lam = Math.toRadians(lon2 - lon1);
        double lamP, sinSigma = 0, cosSigma = 0, sigma = 0, cos2Am = 0, cosSqAlpha = 0;
        int iter = 0;
        do {
            double sinLam = Math.sin(lam), cosLam = Math.cos(lam);
            sinSigma = Math.sqrt(Math.pow(cosU2 * sinLam, 2)
                    + Math.pow(cosU1 * sinU2 - sinU1 * cosU2 * cosLam, 2));
            if (sinSigma == 0) {
                return 0;
            }
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLam;
            sigma = Math.atan2(sinSigma, cosSigma);
            double sinAlpha = cosU1 * cosU2 * sinLam / sinSigma;
            cosSqAlpha = 1 - sinAlpha * sinAlpha;
            cos2Am = cosSqAlpha == 0 ? 0 : cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha;
            double c = F / 16 * cosSqAlpha * (4 + F * (4 - 3 * cosSqAlpha));
            lamP = lam;
            lam = Math.toRadians(lon2 - lon1) + (1 - c) * F * sinAlpha
                    * (sigma + c * sinSigma * (cos2Am + c * cosSigma
                    * (-1 + 2 * cos2Am * cos2Am)));
        } while (Math.abs(lam - lamP) > 1e-12 && ++iter < 200);

        double uSq = cosSqAlpha * (A * A - b * b) / (b * b);
        double bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)));
        double bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)));
        double dSigma = bigB * sinSigma * (cos2Am + bigB / 4
                * (cosSigma * (-1 + 2 * cos2Am * cos2Am) - bigB / 6 * cos2Am
                * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2Am * cos2Am)));
        return b * bigA * (sigma - dSigma);
    }

    /**
     * Compass bearing of a world-frame direction, degrees clockwise from north.
     * North is -z, east is +x.
     */
    public static double bearingOf(double dx, double dz) {
        double d = Math.toDegrees(Math.atan2(dx, -dz));
        return d < 0 ? d + 360.0 : d;
    }

    /** Unit world-frame direction for a compass heading in radians. */
    public static double headingX(double headingRad) {
        return Math.sin(headingRad);
    }

    /** Unit world-frame direction for a compass heading in radians. */
    public static double headingZ(double headingRad) {
        return -Math.cos(headingRad);
    }
}
