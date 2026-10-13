package ai.shuvrajeetkamila.mumbai.game;

import ai.shuvrajeetkamila.mumbai.world.WorldData;

/**
 * Gathers what the radar should draw, without drawing any of it.
 *
 * <p>In {@code core} and free of any Canvas or GL reference, for the same
 * reason everything else here is: it can then be tested against the real
 * compiled world. The renderer receives flat arrays of line segments in
 * <i>map space</i> - metres relative to the player, already rotated so that
 * up is the direction of travel - and does nothing but stroke them.
 *
 * <p>The brief allows a vector minimap and forbids it standing in for the
 * world. This is vector: real OSM centrelines out of the same routing graph
 * the traffic drives on, clipped to a disc. No tiles, no imagery, nothing
 * pre-rendered.
 *
 * <p><b>The spatial index is doing the work.</b> The graph has 352,267 edges
 * and the radar refreshes several times a second. Scanning all of them would
 * cost more than the rest of the frame put together; the grid built here
 * narrows it to the few hundred within range. That index is built once, on
 * first use, and reused.
 */
public final class Minimap {

    /** Radius covered by the radar, in metres of world. */
    public static final double RANGE_M = 320.0;

    /** Cell size for the edge index. Comfortably larger than the range. */
    private static final double CELL_M = 384.0;

    private final WorldData world;

    // Flattened grid: cellStart[i] .. cellStart[i+1] indexes into cellEdge.
    private int[] cellStart;
    private int[] cellEdge;
    private int gridW, gridH;
    private double gridMinX, gridMinZ;

    /**
     * Segments to stroke, four doubles each: x0, y0, x1, y1 in map space,
     * metres, +X right and +Y up the screen. Valid up to {@link #segmentCount}.
     */
    public double[] segments = new double[4096];
    public int segmentCount;

    /** Road class per segment, parallel to {@link #segments}. */
    public int[] segmentClass = new int[1024];

    public Minimap(WorldData world) {
        this.world = world;
    }

    /**
     * Rebuilds the visible segment list.
     *
     * @param px      player world X
     * @param pz      player world Z
     * @param heading bearing to place at the top of the map, radians
     */
    public void update(double px, double pz, double heading) {
        if (cellStart == null) { buildIndex(); }
        segmentCount = 0;

        double cosH = Math.cos(-heading), sinH = Math.sin(-heading);
        double r2 = RANGE_M * RANGE_M;

        int cx = (int) Math.floor((px - gridMinX) / CELL_M);
        int cz = (int) Math.floor((pz - gridMinZ) / CELL_M);

        for (int gz = cz - 1; gz <= cz + 1; gz++) {
            if (gz < 0 || gz >= gridH) { continue; }
            for (int gx = cx - 1; gx <= cx + 1; gx++) {
                if (gx < 0 || gx >= gridW) { continue; }
                int cell = gz * gridW + gx;
                for (int i = cellStart[cell]; i < cellStart[cell + 1]; i++) {
                    addEdge(cellEdge[i], px, pz, cosH, sinH, r2);
                }
            }
        }
    }

    private void addEdge(int e, double px, double pz,
                         double cosH, double sinH, double r2) {
        int a = world.graphEdge[e * 2];
        int b = world.graphEdge[e * 2 + 1];
        double ax = world.graphNodeXZ[a * 2] - px;
        double az = world.graphNodeXZ[a * 2 + 1] - pz;
        double bx = world.graphNodeXZ[b * 2] - px;
        double bz = world.graphNodeXZ[b * 2 + 1] - pz;

        // Reject only if both ends are out of range *and* the segment does
        // not pass through it. A long edge whose endpoints are both outside
        // the disc can still cross the middle of the radar, and dropping it
        // puts a gap in exactly the road the player is driving along.
        boolean aIn = ax * ax + az * az <= r2;
        boolean bIn = bx * bx + bz * bz <= r2;
        if (!aIn && !bIn && !crossesDisc(ax, az, bx, bz, r2)) { return; }

        if (segmentCount * 4 + 4 > segments.length) { grow(); }

        // World is X east, Z south. Screen is X right, Y up, so Z negates.
        double x0 = ax * cosH - az * sinH;
        double y0 = -(ax * sinH + az * cosH);
        double x1 = bx * cosH - bz * sinH;
        double y1 = -(bx * sinH + bz * cosH);

        int o = segmentCount * 4;
        segments[o] = x0;
        segments[o + 1] = y0;
        segments[o + 2] = x1;
        segments[o + 3] = y1;
        segmentClass[segmentCount] = world.edgeClass(e);
        segmentCount++;
    }

    /** Does the segment pass within the radar disc at any point? */
    private static boolean crossesDisc(double ax, double az,
                                       double bx, double bz, double r2) {
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-9) { return false; }
        // Closest approach to the origin, clamped to the segment.
        double t = -(ax * dx + az * dz) / len2;
        if (t < 0) { t = 0; } else if (t > 1) { t = 1; }
        double qx = ax + dx * t, qz = az + dz * t;
        return qx * qx + qz * qz <= r2;
    }

    private void grow() {
        double[] s = new double[segments.length * 2];
        System.arraycopy(segments, 0, s, 0, segments.length);
        segments = s;
        int[] c = new int[segmentClass.length * 2];
        System.arraycopy(segmentClass, 0, c, 0, segmentClass.length);
        segmentClass = c;
    }

    /**
     * Buckets every edge into a coarse grid, by both endpoints.
     *
     * <p>Counting pass then a fill pass, into two flat int arrays rather
     * than a grid of lists. Across 352,000 edges the difference between this
     * and {@code List<Integer>[]} is a few hundred thousand boxed Integers
     * and the garbage collection that follows them.
     */
    private void buildIndex() {
        int edges = world.graphEdge.length / 2;
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        int nodes = world.graphNodeXZ.length / 2;
        for (int i = 0; i < nodes; i++) {
            double x = world.graphNodeXZ[i * 2], z = world.graphNodeXZ[i * 2 + 1];
            if (x < minX) { minX = x; }
            if (x > maxX) { maxX = x; }
            if (z < minZ) { minZ = z; }
            if (z > maxZ) { maxZ = z; }
        }
        if (nodes == 0) {
            minX = minZ = 0;
            maxX = maxZ = 1;
        }
        gridMinX = minX;
        gridMinZ = minZ;
        gridW = Math.max(1, (int) Math.ceil((maxX - minX) / CELL_M) + 1);
        gridH = Math.max(1, (int) Math.ceil((maxZ - minZ) / CELL_M) + 1);

        int cells = gridW * gridH;
        int[] count = new int[cells + 1];
        for (int e = 0; e < edges; e++) {
            int ca = cellOf(world.graphEdge[e * 2]);
            int cb = cellOf(world.graphEdge[e * 2 + 1]);
            count[ca]++;
            if (cb != ca) { count[cb]++; }
        }
        cellStart = new int[cells + 1];
        int run = 0;
        for (int i = 0; i < cells; i++) {
            cellStart[i] = run;
            run += count[i];
        }
        cellStart[cells] = run;

        int[] cursor = new int[cells];
        cellEdge = new int[run];
        for (int e = 0; e < edges; e++) {
            int ca = cellOf(world.graphEdge[e * 2]);
            int cb = cellOf(world.graphEdge[e * 2 + 1]);
            cellEdge[cellStart[ca] + cursor[ca]++] = e;
            if (cb != ca) { cellEdge[cellStart[cb] + cursor[cb]++] = e; }
        }
    }

    private int cellOf(int node) {
        double x = world.graphNodeXZ[node * 2];
        double z = world.graphNodeXZ[node * 2 + 1];
        int gx = (int) Math.floor((x - gridMinX) / CELL_M);
        int gz = (int) Math.floor((z - gridMinZ) / CELL_M);
        if (gx < 0) { gx = 0; } else if (gx >= gridW) { gx = gridW - 1; }
        if (gz < 0) { gz = 0; } else if (gz >= gridH) { gz = gridH - 1; }
        return gz * gridW + gx;
    }
}
