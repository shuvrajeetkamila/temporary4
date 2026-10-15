package ai.shuvrajeetkamila.mumbai.world;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Where the stations are, for everything that needs to know.
 *
 * <p>The services knew where they stopped and nothing else did, so
 * there was nowhere to stand: the stops were points on an alignment
 * with no platform, no sign and no structure of any kind.
 *
 * <p>Derived here, once, and cached on the world, so that the mesher
 * that builds the platform and the simulation that halts the train are
 * reading the same list. Two separate calculations of "where is the
 * station" is how a platform ends up a hundred metres down the line
 * from the doors.
 *
 * <p>The spacing rule is the one in {@code Transit}, restated here
 * rather than imported because {@code sim} depends on {@code world}
 * and not the other way round. {@code TransitStopsTest} compares the
 * two and fails if they ever drift apart.
 */
public final class TransitStops {

    /** Spatial hash cell, metres. */
    private static final double CELL = 512.0;

    /** Longest rake, used to keep stations clear of the line ends. */
    private static final double CLEAR_M = 9 * (20.5 + 1.2) + 10;

    /** How far below the street a bored tunnel runs, m. */
    public static final double SUBWAY_DEPTH_M = -12.0;

    /** Height of a monorail guideway above the street, m. */
    public static final double MONORAIL_H_M = 8.5;

    /** Height of a Metro viaduct above the street, m. */
    public static final double METRO_H_M = 7.8;

    /** Grade a class of line runs at. */
    public enum Grade { UNDERGROUND, SURFACE, ELEVATED, WATER }

    /**
     * Which of the three levels a line runs on.
     *
     * <p>Mumbai has all of them and they are not interchangeable: the
     * suburban network is at grade, Metro Line 1 and the monorail are
     * on viaducts, and Line 3 is bored. It lives here, in the world
     * package, because the mesher builds the platforms and the
     * simulation runs the trains and the two deciding separately is
     * how a platform ends up twelve metres above its own train.
     */
    public static Grade gradeOf(int cls) {
        switch (cls) {
            case WorldData.CLS_SUBWAY: return Grade.UNDERGROUND;
            case WorldData.CLS_MONORAIL:
            case WorldData.CLS_LIGHT_RAIL: return Grade.ELEVATED;
            case WorldData.CLS_FERRY: return Grade.WATER;
            default: return Grade.SURFACE;
        }
    }

    /** Height of the running surface above the ground, m. */
    public static double railHeight(int cls) {
        switch (cls) {
            case WorldData.CLS_SUBWAY: return SUBWAY_DEPTH_M;
            case WorldData.CLS_MONORAIL: return MONORAIL_H_M;
            case WorldData.CLS_LIGHT_RAIL: return METRO_H_M;
            case WorldData.CLS_FERRY: return 0.0;
            default: return 0.9;
        }
    }

    /** Station spacing by line class, metres. 0 means terminals only. */
    public static double spacing(int cls) {
        switch (cls) {
            case WorldData.CLS_RAIL: return 1600.0;
            case WorldData.CLS_LIGHT_RAIL:
            case WorldData.CLS_SUBWAY: return 1150.0;
            case WorldData.CLS_MONORAIL: return 900.0;
            default: return 0.0;
        }
    }

    /** One station. */
    public static final class Stop {
        public final int route;
        public final int index;
        public final int cls;
        public final double x, z;
        /** Bearing of the line through the station, radians. */
        public final double bearing;

        Stop(int route, int index, int cls, double x, double z,
             double bearing) {
            this.route = route;
            this.index = index;
            this.cls = cls;
            this.x = x;
            this.z = z;
            this.bearing = bearing;
        }
    }

    private final ArrayList<Stop> all = new ArrayList<>();
    private final HashMap<Long, int[]> grid = new HashMap<>();

    TransitStops(WorldData w) {
        for (int r = 0; r < w.transitCount(); r++) {
            int cls = w.transitClass(r);
            double sp = spacing(cls);
            double len = w.transitLength(r);
            if (sp <= 0 || len < 2 * CLEAR_M + sp) { continue; }
            int count = (int) Math.floor((len - 2 * CLEAR_M) / sp);
            if (count < 1) { continue; }
            double first = (len - (count - 1) * sp) * 0.5;
            for (int k = 0; k < count; k++) {
                double s = first + k * sp;
                double[] p = pointAt(w, r, s);
                double[] q = pointAt(w, r, Math.min(len, s + 12));
                double b = Math.atan2(q[0] - p[0], -(q[1] - p[1]));
                all.add(new Stop(r, k, cls, p[0], p[1], b));
            }
        }
        HashMap<Long, ArrayList<Integer>> acc = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            Stop s = all.get(i);
            acc.computeIfAbsent(key(s.x, s.z), k -> new ArrayList<>()).add(i);
        }
        for (java.util.Map.Entry<Long, ArrayList<Integer>> e : acc.entrySet()) {
            ArrayList<Integer> v = e.getValue();
            int[] a = new int[v.size()];
            for (int i = 0; i < a.length; i++) { a[i] = v.get(i); }
            grid.put(e.getKey(), a);
        }
    }

    /** Arc-length walk along a route, matching {@code Transit.pointAt}. */
    private static double[] pointAt(WorldData w, int route, double s) {
        int n = w.transitPointCount(route);
        if (n == 0) { return new double[]{0, 0}; }
        if (n == 1 || s <= 0) {
            return new double[]{w.transitX(route, 0), w.transitZ(route, 0)};
        }
        double acc = 0;
        for (int i = 1; i < n; i++) {
            double ax = w.transitX(route, i - 1), az = w.transitZ(route, i - 1);
            double bx = w.transitX(route, i), bz = w.transitZ(route, i);
            double d = Math.hypot(bx - ax, bz - az);
            if (acc + d >= s) {
                double t = d < 1e-9 ? 0 : (s - acc) / d;
                return new double[]{ax + (bx - ax) * t, az + (bz - az) * t};
            }
            acc += d;
        }
        return new double[]{w.transitX(route, n - 1), w.transitZ(route, n - 1)};
    }

    private static long key(double x, double z) {
        long cx = (long) Math.floor(x / CELL);
        long cz = (long) Math.floor(z / CELL);
        return (cx << 32) ^ (cz & 0xffffffffL);
    }

    public int count() { return all.size(); }

    public Stop get(int i) { return all.get(i); }

    /** Stations whose centre falls in a box. Allocates; not per frame. */
    public ArrayList<Stop> inBox(double x0, double z0, double x1, double z1) {
        ArrayList<Stop> out = new ArrayList<>();
        long cx0 = (long) Math.floor(Math.min(x0, x1) / CELL);
        long cx1 = (long) Math.floor(Math.max(x0, x1) / CELL);
        long cz0 = (long) Math.floor(Math.min(z0, z1) / CELL);
        long cz1 = (long) Math.floor(Math.max(z0, z1) / CELL);
        for (long jz = cz0; jz <= cz1; jz++) {
            for (long jx = cx0; jx <= cx1; jx++) {
                int[] ids = grid.get((jx << 32) ^ (jz & 0xffffffffL));
                if (ids == null) { continue; }
                for (int id : ids) {
                    Stop s = all.get(id);
                    if (s.x >= Math.min(x0, x1) && s.x <= Math.max(x0, x1)
                            && s.z >= Math.min(z0, z1)
                            && s.z <= Math.max(z0, z1)) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    /** The nearest station to a point, or null. */
    public Stop nearest(double x, double z, double r) {
        Stop best = null;
        double bestD2 = r * r;
        for (Stop s : inBox(x - r, z - r, x + r, z + r)) {
            double d2 = (s.x - x) * (s.x - x) + (s.z - z) * (s.z - z);
            if (d2 < bestD2) { bestD2 = d2; best = s; }
        }
        return best;
    }
}
