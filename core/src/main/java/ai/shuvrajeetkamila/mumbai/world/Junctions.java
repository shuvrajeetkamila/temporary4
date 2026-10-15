package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.math.MathUtil;

import java.util.HashMap;

/**
 * Where roads meet, and who goes first.
 *
 * <p>Traffic queued behind the car in front and bounced off anything it
 * touched, but nothing in the city had any rule about priority: at a
 * crossroads the cars that arrived first simply got there first, and
 * four streams converging produced a slow-motion pile of stationary
 * vehicles nudging each other.
 *
 * <p>Two things are derived here and both come from the road graph, so
 * the traffic and the renderer cannot disagree about where a junction
 * is or what colour it is showing:
 *
 * <ul>
 *   <li>Which nodes are junctions - degree three or more - and which of
 *       those are worth signalling, which is the ones where an arterial
 *       is involved. A lane meeting a lane is left to give way.</li>
 *   <li>The phase, as a pure function of time and the junction's own
 *       offset. No state, so a junction a kilometre away that nobody
 *       has simulated for ten minutes is showing exactly what it would
 *       have been showing had it been simulated the whole time.</li>
 * </ul>
 *
 * <p>Two phases, split by bearing against the junction's principal
 * axis, which is the bearing of its most important road. Everything
 * within 45 degrees of that axis goes together; everything else goes
 * on the other phase. That is a crossroads, and it degrades sensibly
 * on the five- and six-way junctions that Mumbai is full of.
 */
public final class Junctions {

    /** Seconds each phase holds green. */
    public static final double GREEN_S = 19.0;

    /** Seconds of amber between phases. */
    public static final double AMBER_S = 3.5;

    /** One full cycle: both phases, both ambers. */
    public static final double CYCLE_S = 2 * (GREEN_S + AMBER_S);

    /** Highest road class that gets lights. Lower numbers are bigger roads. */
    public static final int SIGNAL_MAX_CLASS = 4;

    /** Degree at or above which a node counts as a junction. */
    public static final int MIN_DEGREE = 3;

    /** Half-width of the band that shares a phase, radians. */
    public static final double PHASE_HALF_ANGLE = Math.PI / 4;

    /** How far back from the node the stop line sits, metres. */
    public static final double STOP_LINE_M = 7.0;

    /** Spatial hash cell, metres. */
    private static final double CELL = 192.0;

    private final WorldData world;

    /** Graph node id for each junction. */
    private final int[] node;
    /** Bearing of the principal axis, radians. */
    private final float[] axis;
    /** Phase offset in cycles, 0..1. Keeps the city from blinking as one. */
    private final float[] offset;
    /** True where the junction is signalled rather than give-way. */
    private final boolean[] lit;

    private final HashMap<Long, int[]> grid = new HashMap<>();

    public Junctions(WorldData w) {
        this.world = w;
        int nodes = w.graphNodeXZ.length / 2;
        int edges = w.edgeCount();

        int[] degree = new int[nodes];
        int[] best = new int[nodes];
        java.util.Arrays.fill(best, Integer.MAX_VALUE);
        for (int e = 0; e < edges; e++) {
            int a = w.graphEdge[e * 2];
            int b = w.graphEdge[e * 2 + 1];
            if (a < 0 || b < 0 || a >= nodes || b >= nodes) { continue; }
            degree[a]++;
            degree[b]++;
            int cls = w.edgeClass(e);
            if (cls < best[a]) { best[a] = cls; }
            if (cls < best[b]) { best[b] = cls; }
        }

        int n = 0;
        for (int i = 0; i < nodes; i++) {
            if (degree[i] >= MIN_DEGREE) { n++; }
        }
        node = new int[n];
        axis = new float[n];
        offset = new float[n];
        lit = new boolean[n];
        int k = 0;
        for (int i = 0; i < nodes; i++) {
            if (degree[i] < MIN_DEGREE) { continue; }
            node[k] = i;
            lit[k] = best[i] <= SIGNAL_MAX_CLASS;
            // Scattered deterministically, so neighbouring junctions are
            // not all green at the same instant - which looks like a
            // stadium light show rather than a city.
            offset[k] = (float) hash01(i);
            k++;
        }

        // Principal axis: the bearing of the most important edge at the
        // node. A second pass, because it needs the junction index.
        HashMap<Integer, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < n; i++) { indexOf.put(node[i], i); }
        int[] axisCls = new int[n];
        java.util.Arrays.fill(axisCls, Integer.MAX_VALUE);
        for (int e = 0; e < edges; e++) {
            int a = w.graphEdge[e * 2];
            int b = w.graphEdge[e * 2 + 1];
            if (a < 0 || b < 0 || a >= nodes || b >= nodes) { continue; }
            int cls = w.edgeClass(e);
            Integer ia = indexOf.get(a);
            if (ia != null && cls < axisCls[ia]) {
                axisCls[ia] = cls;
                axis[ia] = (float) bearing(w, a, b);
            }
            Integer ib = indexOf.get(b);
            if (ib != null && cls < axisCls[ib]) {
                axisCls[ib] = cls;
                axis[ib] = (float) bearing(w, b, a);
            }
        }

        buildIndex();
    }

    private static double bearing(WorldData w, int from, int to) {
        double dx = w.graphNodeXZ[to * 2] - w.graphNodeXZ[from * 2];
        double dz = w.graphNodeXZ[to * 2 + 1] - w.graphNodeXZ[from * 2 + 1];
        return Math.atan2(dx, -dz);
    }

    /** Deterministic 0..1 from an integer. */
    static double hash01(int i) {
        long x = i * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
        x ^= x >>> 29;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 32;
        return (x >>> 11) / (double) (1L << 53);
    }

    private void buildIndex() {
        HashMap<Long, java.util.ArrayList<Integer>> acc = new HashMap<>();
        for (int i = 0; i < node.length; i++) {
            acc.computeIfAbsent(cellKey(x(i), z(i)),
                    key -> new java.util.ArrayList<>()).add(i);
        }
        for (java.util.Map.Entry<Long, java.util.ArrayList<Integer>> e
                : acc.entrySet()) {
            java.util.ArrayList<Integer> v = e.getValue();
            int[] a = new int[v.size()];
            for (int i = 0; i < a.length; i++) { a[i] = v.get(i); }
            grid.put(e.getKey(), a);
        }
    }

    private static long cellKey(double x, double z) {
        long cx = (long) Math.floor(x / CELL);
        long cz = (long) Math.floor(z / CELL);
        return (cx << 32) ^ (cz & 0xffffffffL);
    }

    public int count() { return node.length; }

    public double x(int i) { return world.graphNodeXZ[node[i] * 2]; }

    public double z(int i) { return world.graphNodeXZ[node[i] * 2 + 1]; }

    public boolean signalled(int i) { return lit[i]; }

    public double axisOf(int i) { return axis[i]; }

    public double offsetOf(int i) { return offset[i]; }

    public int nodeOf(int i) { return node[i]; }

    /**
     * Which phase an approach on this bearing belongs to.
     *
     * <p>Modulo 180 degrees, because the two ends of the same street
     * must run together - otherwise a crossroads lets one direction of
     * a road through while holding the other, which is not a junction,
     * it is a trap.
     */
    public int phaseFor(int i, double approachBearing) {
        double d = Math.abs(MathUtil.wrapPi(approachBearing - axis[i]));
        if (d > Math.PI / 2) { d = Math.PI - d; }
        return d <= PHASE_HALF_ANGLE ? 0 : 1;
    }

    /**
     * What the given phase is showing. 0 green, 1 amber, 2 red.
     *
     * <p>A pure function of the clock. Phase 0 is green for the first
     * stretch of the cycle, amber for the next, and red while phase 1
     * has its turn.
     */
    public static int aspect(double seconds, double offsetCycles, int phase) {
        double t = seconds / CYCLE_S + offsetCycles;
        t = t - Math.floor(t);
        double s = t * CYCLE_S;
        double half = GREEN_S + AMBER_S;
        double local = phase == 0 ? s : s - half;
        if (local < 0 || local >= half) { return 2; }
        return local < GREEN_S ? 0 : 1;
    }

    /** What this junction is showing to an approach on this bearing. */
    public int aspectFor(int i, double seconds, double approachBearing) {
        if (!lit[i]) { return 0; }
        return aspect(seconds, offset[i], phaseFor(i, approachBearing));
    }

    /**
     * The signalled junction nearest a point, or -1.
     *
     * @param r how far to look, metres
     */
    public int nearestSignalled(double x, double z, double r) {
        int best = -1;
        double bestD2 = r * r;
        long cx = (long) Math.floor(x / CELL);
        long cz = (long) Math.floor(z / CELL);
        int span = (int) Math.ceil(r / CELL);
        for (long jz = cz - span; jz <= cz + span; jz++) {
            for (long jx = cx - span; jx <= cx + span; jx++) {
                int[] ids = grid.get((jx << 32) ^ (jz & 0xffffffffL));
                if (ids == null) { continue; }
                for (int id : ids) {
                    if (!lit[id]) { continue; }
                    double dx = x(id) - x, dz = z(id) - z;
                    double d2 = dx * dx + dz * dz;
                    if (d2 < bestD2) { bestD2 = d2; best = id; }
                }
            }
        }
        return best;
    }

    /** Every junction whose centre falls in a box. Allocates; not per frame. */
    public int[] inBox(double x0, double z0, double x1, double z1) {
        java.util.ArrayList<Integer> hits = new java.util.ArrayList<>();
        long cx0 = (long) Math.floor(Math.min(x0, x1) / CELL);
        long cx1 = (long) Math.floor(Math.max(x0, x1) / CELL);
        long cz0 = (long) Math.floor(Math.min(z0, z1) / CELL);
        long cz1 = (long) Math.floor(Math.max(z0, z1) / CELL);
        for (long jz = cz0; jz <= cz1; jz++) {
            for (long jx = cx0; jx <= cx1; jx++) {
                int[] ids = grid.get((jx << 32) ^ (jz & 0xffffffffL));
                if (ids == null) { continue; }
                for (int id : ids) {
                    double px = x(id), pz = z(id);
                    if (px >= Math.min(x0, x1) && px <= Math.max(x0, x1)
                            && pz >= Math.min(z0, z1)
                            && pz <= Math.max(z0, z1)) {
                        hits.add(id);
                    }
                }
            }
        }
        int[] out = new int[hits.size()];
        for (int i = 0; i < out.length; i++) { out[i] = hits.get(i); }
        return out;
    }

    public int signalledCount() {
        int n = 0;
        for (boolean b : lit) { if (b) { n++; } }
        return n;
    }
}
