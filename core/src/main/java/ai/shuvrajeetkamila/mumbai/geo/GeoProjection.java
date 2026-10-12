package ai.shuvrajeetkamila.mumbai.geo;

import ai.shuvrajeetkamila.mumbai.core.Mth;

/**
 * WGS84 -> local metric East/North projection for the Mumbai / Salsette area of interest.
 *
 * <p>The game world uses a right handed, metre scaled coordinate frame:
 * <ul>
 *   <li><b>X</b> = metres East of the geodetic origin</li>
 *   <li><b>Y</b> = metres Up (orthometric-ish height above mean sea level)</li>
 *   <li><b>Z</b> = metres South of the geodetic origin (so North is -Z)</li>
 * </ul>
 *
 * <p>A local tangent plane (ENU) projection is used instead of a full UTM implementation.
 * Across the ~60 km x 36 km AOI the ellipsoidal tangent plane keeps distance error well below
 * one metre, which is far finer than the resolution of the world data, and it round-trips
 * exactly. Master positions are held in {@code double}; only the floating-origin relative
 * values handed to the renderer and the physics solver are narrowed to {@code float}.
 *
 * <p>Nothing here is random: the projection is a pure function of the configured origin.
 */
public final class GeoProjection {

    /** WGS84 semi-major axis (metres). */
    public static final double WGS84_A = 6378137.0;
    /** WGS84 flattening. */
    public static final double WGS84_F = 1.0 / 298.257223563;
    /** WGS84 first eccentricity squared. */
    public static final double WGS84_E2 = WGS84_F * (2.0 - WGS84_F);

    /** Documented geodetic origin: Mumbai, near Dadar / Shivaji Park, centre of the island. */
    public static final double ORIGIN_LAT = 19.0176;
    public static final double ORIGIN_LON = 72.8562;

    private final double originLat;
    private final double originLon;
    private final double meridionalRadius;

    public GeoProjection() {
        this(ORIGIN_LAT, ORIGIN_LON);
    }

    public GeoProjection(double originLatDeg, double originLonDeg) {
        this.originLat = originLatDeg;
        this.originLon = originLonDeg;
        this.meridionalRadius = meridionalRadius(originLatDeg);
    }

    public double originLat() {
        return originLat;
    }

    public double originLon() {
        return originLon;
    }

    /** Radius of curvature in the prime vertical at a latitude (metres). */
    public static double primeVerticalRadius(double latDeg) {
        double s = Math.sin(Math.toRadians(latDeg));
        return WGS84_A / Math.sqrt(1.0 - WGS84_E2 * s * s);
    }

    /** Radius of curvature in the meridian at a latitude (metres). */
    public static double meridionalRadius(double latDeg) {
        double s = Math.sin(Math.toRadians(latDeg));
        double t = 1.0 - WGS84_E2 * s * s;
        return WGS84_A * (1.0 - WGS84_E2) / (t * Math.sqrt(t));
    }

    /** Metres East of the origin for a WGS84 position. */
    public double toEast(double latDeg, double lonDeg) {
        double n = primeVerticalRadius(latDeg);
        return Math.toRadians(lonDeg - originLon) * n * Math.cos(Math.toRadians(latDeg));
    }

    /** Metres North of the origin for a WGS84 latitude. */
    public double toNorth(double latDeg) {
        return Math.toRadians(latDeg - originLat) * meridionalRadius;
    }

    /** World X (East metres). */
    public double worldX(double latDeg, double lonDeg) {
        return toEast(latDeg, lonDeg);
    }

    /** World Z (South metres; north is negative). */
    public double worldZ(double latDeg) {
        return -toNorth(latDeg);
    }

    /** Inverse: latitude for a world Z (South metres). */
    public double toLat(double worldZ) {
        return originLat + Math.toDegrees((-worldZ) / meridionalRadius);
    }

    /** Inverse: longitude for a world X/Z pair. */
    public double toLon(double worldX, double worldZ) {
        double lat = toLat(worldZ);
        double n = primeVerticalRadius(lat);
        double cos = Math.cos(Math.toRadians(lat));
        if (Math.abs(cos) < 1e-12) {
            return originLon;
        }
        return originLon + Math.toDegrees(worldX / (n * cos));
    }

    /**
     * Great-circle-ish ground distance between two WGS84 points using the haversine formula.
     * Used by the GIS tests to validate that projected metre distances are faithful.
     */
    public static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371008.8;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2.0 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Planar distance between two world positions. */
    public static double planarDistance(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Compass bearing (degrees, 0 = North, 90 = East) for a world-space direction. */
    public static float bearingDeg(double dirX, double dirZ) {
        // North is -Z, East is +X.
        return Mth.wrap360((float) Math.toDegrees(Math.atan2(dirX, -dirZ)));
    }
}
