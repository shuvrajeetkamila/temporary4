package ai.shuvrajeetkamila.mumbai.world;

import java.util.Map;

/**
 * Where the piers of an elevated railway stand, and how wide its deck is.
 *
 * <p>One authority, because two things need the answer and they must
 * agree exactly: {@code ChunkMesher} draws the structure and
 * {@link CityCollision} makes it solid. Three hundred and eighty-two
 * kilometres of Metro and monorail viaduct were drawn but not collided,
 * so you drove through the piers.
 *
 * <p>Pier positions are a pure function of distance along the route
 * from its first vertex. That matters more than it looks: the mesher
 * used to walk the alignment with a running counter that only advanced
 * over segments near the chunk being built, so the same viaduct got its
 * piers in different places depending on which chunk was asking, and
 * they doubled up or left a gap at every chunk boundary.
 */
public final class Viaduct {

    private Viaduct() { }

    /** Deck width by transit class, metres. */
    private static final Map<Integer, Double> DECK_W_M = Map.of(
            WorldData.CLS_RAIL, 9.0,
            WorldData.CLS_LIGHT_RAIL, 8.4,
            WorldData.CLS_SUBWAY, 8.4,
            WorldData.CLS_MONORAIL, 2.6);

    /** Depth of the deck slab below the rail head, metres. */
    public static final double DECK_T_M = 1.6;

    /** Pier spacing along the alignment, metres. A typical precast span. */
    public static final double PIER_SPACING_M = 26.0;

    /** Half-width of a square pier, metres. */
    public static final double PIER_HALF_M = 1.1;

    /** A pier is only worth building if the deck is this far up. */
    public static final double MIN_PIER_M = 1.5;

    public static double deckWidth(int cls) {
        Double w = DECK_W_M.get(cls);
        return w == null ? 8.4 : w;
    }

    public static double deckHalfWidth(int cls) {
        return deckWidth(cls) * 0.5;
    }

    /**
     * True if this segment of this route is carried on a structure.
     *
     * <p>At grade, on an embankment, or bored: no structure. The test is
     * the height of the rail above the ground at the segment's midpoint,
     * which is the same quantity {@code TransitStops} uses to decide
     * whether a station is an elevated one.
     */
    public static boolean elevatedSegment(WorldData w, int route, int i) {
        if (w.transitClass(route) == WorldData.CLS_FERRY) { return false; }
        double ax = w.transitX(route, i - 1), az = w.transitZ(route, i - 1);
        double bx = w.transitX(route, i), bz = w.transitZ(route, i);
        double mx = (ax + bx) * 0.5, mz = (az + bz) * 0.5;
        double g = w.isLand(mx, mz) ? w.height(mx, mz) : 0.0;
        double railY = (w.transitY(route, i - 1) + w.transitY(route, i)) * 0.5;
        return railY - g >= TransitStops.ELEVATED_M;
    }

    /** Receives one pier. All coordinates are world metres. */
    public interface PierSink {
        /**
         * @param x    pier centre, east
         * @param z    pier centre, south
         * @param top  underside of the deck, which is the pier's top
         * @param base ground under the pier
         * @param fx   unit forward along the alignment, east component
         * @param fz   unit forward along the alignment, south component
         */
        void pier(double x, double z, double top, double base, double fx, double fz);
    }

    /**
     * Every pier of one route whose centre lies inside the box.
     *
     * <p>The arc length is accumulated from the route's first vertex
     * whether or not that part of it is anywhere near the box, so the
     * answer does not depend on who is asking.
     */
    public static void piers(WorldData w, int route,
                             double minX, double minZ, double maxX, double maxZ,
                             PierSink sink) {
        int n = w.transitPointCount(route);
        if (n < 2) { return; }
        if (!w.transitNear(route, minX, minZ, maxX, maxZ)) { return; }
        double cum = 0;
        int lastPier = -1;
        for (int i = 1; i < n; i++) {
            double ax = w.transitX(route, i - 1), az = w.transitZ(route, i - 1);
            double bx = w.transitX(route, i), bz = w.transitZ(route, i);
            double seg = Math.hypot(bx - ax, bz - az);
            if (seg < 1e-6) { continue; }
            double segStart = cum;
            cum += seg;
            // Which pier indices fall inside this segment.
            int first = (int) Math.floor(segStart / PIER_SPACING_M) + 1;
            int last = (int) Math.floor(cum / PIER_SPACING_M);
            if (last < first) { continue; }
            // Only now is the expensive part worth doing.
            if (Math.max(ax, bx) < minX || Math.min(ax, bx) > maxX
                    || Math.max(az, bz) < minZ || Math.min(az, bz) > maxZ) {
                lastPier = last;
                continue;
            }
            if (!elevatedSegment(w, route, i)) { lastPier = last; continue; }
            double fx = (bx - ax) / seg, fz = (bz - az) / seg;
            double ay = w.transitY(route, i - 1), by = w.transitY(route, i);
            for (int p = Math.max(first, lastPier + 1); p <= last; p++) {
                double t = p * PIER_SPACING_M - segStart;
                double px = ax + fx * t, pz = az + fz * t;
                if (px < minX || px > maxX || pz < minZ || pz > maxZ) { continue; }
                double base = w.isLand(px, pz) ? w.height(px, pz) : -1.0;
                double top = ay + (by - ay) * (t / seg) - DECK_T_M;
                if (top - base < MIN_PIER_M) { continue; }
                sink.pier(px, pz, top, base, fx, fz);
            }
            lastPier = last;
        }
    }
}
