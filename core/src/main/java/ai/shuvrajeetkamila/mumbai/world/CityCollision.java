package ai.shuvrajeetkamila.mumbai.world;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * The parts of the city you can hit.
 *
 * <p>Until this existed, the only question any physics body could ask the
 * world was "how high is the ground here", answered from the elevation
 * grid. Buildings were never in that answer, so you drove through all
 * 195,743 of them; bridges were never in it either, so the Sea Link was a
 * painting you passed through on your way down to the seabed.
 *
 * <p>Everything here is derived from the chunk payload that is already
 * shipped and already decoded for drawing - footprint rings, road
 * centrelines, widths and the bridge flag. No new data goes into the
 * world file. What is new is an index over it that answers two questions
 * fast enough to ask sixty times a second:
 *
 * <ul>
 *   <li>is there a wall within {@code r} of this point, and which way
 *       does it push;</li>
 *   <li>is there a bridge deck under this point that this body is
 *       standing on rather than driving beneath.</li>
 * </ul>
 *
 * <p>Chunks are built on demand and cached. The player crosses a chunk
 * boundary about once a kilometre, so the build cost is paid rarely; a
 * query that stays inside one chunk never touches another.
 */
public final class CityCollision {

    /** Index cell, metres. Small enough that a cell holds a few segments. */
    private static final double CELL_M = 32.0;

    /** How many chunks of collision to keep. The 3x3 around you, and room. */
    public static final int CACHE = 20;

    /**
     * How far above a deck a body may be and still be standing on it.
     *
     * <p>Generous, because a car on its suspension at the crest of an
     * arched span is a metre or so clear of the deck line, and losing the
     * deck for one step drops it through.
     */
    private static final double DECK_ABOVE_M = 4.0;

    /** And how far below, which is small: under the bridge is under it. */
    private static final double DECK_BELOW_M = 0.6;

    private final WorldData world;
    private final Map<Integer, Cell> cache = new HashMap<>();
    private final int[] lru = new int[CACHE];
    private int lruAt;

    public CityCollision(WorldData world) {
        this.world = world;
        for (int i = 0; i < CACHE; i++) { lru[i] = -1; }
    }

    // ------------------------------------------------------------------ query

    /**
     * Push a circle of radius {@code r} centred on {@code (x, z)} out of
     * any wall it overlaps, at height {@code y}.
     *
     * @param out2 filled with the push vector in metres, x then z
     * @return the deepest overlap found, or 0 if the point is clear
     */
    public double wallPush(double x, double z, double y, double r, double[] out2) {
        out2[0] = 0;
        out2[1] = 0;
        double worst = 0;
        for (Cell c : around(x, z, r)) {
            if (c == null) { continue; }
            int[] ids = c.wallsNear(x, z, r);
            if (ids == null) { continue; }
            for (int n = 0; n < ids.length; n++) {
                int s = ids[n];
                if (s < 0) { break; }
                if (y < c.wBase[s] || y > c.wTop[s]) { continue; }
                double ax = c.wx0[s], az = c.wz0[s];
                double bx = c.wx1[s], bz = c.wz1[s];
                double ex = bx - ax, ez = bz - az;
                double len2 = ex * ex + ez * ez;
                double t = len2 < 1e-9 ? 0
                        : Math.max(0, Math.min(1, ((x - ax) * ex + (z - az) * ez) / len2));
                double px = ax + ex * t, pz = az + ez * t;
                double nx = c.wnx[s], nz = c.wnz[s];
                if (nx == 0 && nz == 0) { continue; }
                // Signed distance along the wall's own outward normal.
                // Negative means inside the footprint, and the push is
                // still outward - which is the whole point.
                // Touching this segment at all? A capsule about the
                // segment, so a wall is local: a point outside one
                // building must not be caught by the extended plane of a
                // wall belonging to the building next door.
                double lat = Math.hypot(x - px, z - pz);
                if (lat >= r) { continue; }
                // Signed along the wall's own outward normal. Negative is
                // inside the footprint, and the push is still outward -
                // which is the whole point. Capped at a radius so a body
                // that is somehow well inside is walked out, not fired out.
                double sd = (x - px) * nx + (z - pz) * nz;
                double depth = Math.min(r - sd, r);
                if (depth <= 0) { continue; }
                if (depth > worst) { worst = depth; }
                out2[0] += nx * depth;
                out2[1] += nz * depth;
            }
        }
        return worst;
    }

    /** True if a wall stands within {@code r} of this point at this height. */
    public boolean blocked(double x, double z, double y, double r) {
        double[] tmp = new double[2];
        return wallPush(x, z, y, r, tmp) > 0;
    }

    /**
     * Height of the bridge deck this body is riding, or
     * {@link Double#NEGATIVE_INFINITY} if it is not on one.
     *
     * @param yRef the body's own height, used to tell a deck it is standing
     *             on from one it is driving underneath
     */
    public double deckAt(double x, double z, double yRef) {
        double best = Double.NEGATIVE_INFINITY;
        for (Cell c : around(x, z, 0)) {
            if (c == null) { continue; }
            int[] ids = c.decksNear(x, z);
            if (ids == null) { continue; }
            for (int n = 0; n < ids.length; n++) {
                int s = ids[n];
                if (s < 0) { break; }
                double ax = c.dx0[s], az = c.dz0[s];
                double bx = c.dx1[s], bz = c.dz1[s];
                double ex = bx - ax, ez = bz - az;
                double len2 = ex * ex + ez * ez;
                if (len2 < 1e-9) { continue; }
                double t = Math.max(0, Math.min(1,
                        ((x - ax) * ex + (z - az) * ez) / len2));
                double px = ax + ex * t, pz = az + ez * t;
                double lateral = Math.hypot(x - px, z - pz);
                if (lateral > c.dHalf[s]) { continue; }
                double deck = c.dy0[s] + (c.dy1[s] - c.dy0[s]) * t;
                if (yRef < deck - DECK_ABOVE_M) { continue; }   // under it
                if (yRef > deck + 60) { continue; }             // flying over
                if (deck > best) { best = deck; }
            }
        }
        return best;
    }

    /**
     * The surface a body at {@code (x, y, z)} is supported by: the bridge
     * deck if it is on one, otherwise the terrain.
     */
    public double surfaceAt(double x, double z, double yRef, double terrain) {
        double deck = deckAt(x, z, yRef);
        if (deck == Double.NEGATIVE_INFINITY) { return terrain; }
        // Standing on the deck, or close under it having just driven on.
        if (yRef >= deck - DECK_BELOW_M) { return Math.max(deck, terrain); }
        return terrain;
    }

    // ------------------------------------------------------------- management

    private final Cell[] scratch = new Cell[9];

    /**
     * The chunks a query can reach: the one it is in and all eight
     * neighbours.
     *
     * <p>Not paranoia. A footprint is stored in whichever chunk the
     * compiler assigned it to, and large buildings run over the boundary -
     * the one this was found on spans 104 m across a chunk edge. Its walls
     * live in one chunk's feature list while standing in the next, so a
     * query that looked only at its own chunk found nothing and the player
     * walked in through the side. Neighbours are built lazily and cached,
     * so this costs a handful of decodes the first time a chunk is
     * entered and nothing at all thereafter.
     */
    private Cell[] around(double x, double z, double r) {
        for (int i = 0; i < scratch.length; i++) { scratch[i] = null; }
        int n = 0;
        double m = world.chunkM;
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                n = add(scratch, n, world.chunkIdAt(x + di * m, z + dj * m));
            }
        }
        return scratch;
    }

    private int add(Cell[] into, int n, int id) {
        if (id < 0) { return n; }
        for (int i = 0; i < n; i++) {
            if (into[i] != null && into[i].id == id) { return n; }
        }
        Cell c = get(id);
        if (c == null) { return n; }
        into[n] = c;
        return n + 1;
    }

    private Cell get(int id) {
        Cell c = cache.get(id);
        if (c != null) { return c; }
        if (!world.chunkHasData(id)) {
            c = Cell.empty(id);
        } else {
            try {
                ChunkFeatures f = ChunkFeatures.decode(id, world.chunkOriginX(id),
                        world.chunkOriginZ(id), world.readChunk(id), world.quant);
                c = Cell.build(id, f, world);
            } catch (IOException | RuntimeException e) {
                // A chunk that will not decode is a chunk you can walk
                // through, which is better than a chunk that crashes.
                c = Cell.empty(id);
            }
        }
        int evict = lru[lruAt];
        if (evict >= 0) { cache.remove(evict); }
        lru[lruAt] = id;
        lruAt = (lruAt + 1) % CACHE;
        cache.put(id, c);
        return c;
    }

    /** Drops the cache. For tests, and for a teleport across the city. */
    public void clear() {
        cache.clear();
        for (int i = 0; i < CACHE; i++) { lru[i] = -1; }
        lruAt = 0;
    }

    public int cachedChunks() { return cache.size(); }

    // ------------------------------------------------------------------- data

    /** One chunk's worth of walls and decks, indexed on a uniform grid. */
    static final class Cell {
        final int id;
        final double minX, minZ;
        final int nx, nz;

        float[] wx0, wz0, wx1, wz1, wBase, wTop;
        /** Outward normal of each wall, pointing out of its own footprint. */
        float[] wnx, wnz;
        float[] dx0, dz0, dx1, dz1, dy0, dy1, dHalf;
        int[][] wallGrid;
        int[][] deckGrid;

        private Cell(int id, double minX, double minZ, int nx, int nz) {
            this.id = id;
            this.minX = minX;
            this.minZ = minZ;
            this.nx = nx;
            this.nz = nz;
        }

        static Cell empty(int id) {
            Cell c = new Cell(id, 0, 0, 0, 0);
            c.wx0 = c.wz0 = c.wx1 = c.wz1 = c.wBase = c.wTop = new float[0];
            c.wnx = c.wnz = new float[0];
            c.dx0 = c.dz0 = c.dx1 = c.dz1 = c.dy0 = c.dy1 = c.dHalf = new float[0];
            return c;
        }

        int[] wallsNear(double x, double z, double r) {
            return lookup(wallGrid, x, z);
        }

        int[] decksNear(double x, double z) {
            return lookup(deckGrid, x, z);
        }

        private int[] lookup(int[][] grid, double x, double z) {
            if (grid == null) { return null; }
            int i = (int) Math.floor((x - minX) / CELL_M);
            int j = (int) Math.floor((z - minZ) / CELL_M);
            // Clamped rather than rejected: a segment belonging to this
            // chunk but standing outside it is bucketed into the edge
            // cell, and a query from out there has to be able to find it.
            if (i < 0 || j < 0 || i >= nx || j >= nz) {
                if (i < -1 || j < -1 || i > nx || j > nz) { return null; }
                i = i < 0 ? 0 : (i > nx - 1 ? nx - 1 : i);
                j = j < 0 ? 0 : (j > nz - 1 ? nz - 1 : j);
            }
            return grid[j * nx + i];
        }

        static Cell build(int id, ChunkFeatures f, WorldData w) {
            double ox = w.chunkOriginX(id);
            double oz = w.chunkOriginZ(id);
            int n = (int) Math.ceil(w.chunkM / CELL_M);
            Cell c = new Cell(id, ox, oz, n, n);

            // ---- walls -------------------------------------------------
            int segs = 0;
            for (int b = 0; b < f.buildingCount(); b++) {
                int s = f.buildingOffset[b];
                int e = f.buildingOffset[b + 1];
                if (e - s >= 3) { segs += e - s; }
            }
            c.wx0 = new float[segs];
            c.wz0 = new float[segs];
            c.wx1 = new float[segs];
            c.wz1 = new float[segs];
            c.wBase = new float[segs];
            c.wTop = new float[segs];
            c.wnx = new float[segs];
            c.wnz = new float[segs];
            int at = 0;
            for (int b = 0; b < f.buildingCount(); b++) {
                int s = f.buildingOffset[b];
                int e = f.buildingOffset[b + 1];
                if (e - s < 3) { continue; }
                // Same base the mesher uses: the lowest ground under the
                // ring, or the walls lean out of the floor they are drawn on.
                double base = Double.MAX_VALUE;
                for (int k = s; k < e; k++) {
                    double wx = ox + f.buildingXZ[k * 2];
                    double wz = oz + f.buildingXZ[k * 2 + 1];
                    double g = w.isLand(wx, wz) ? w.height(wx, wz) : 0.0;
                    if (g < base) { base = g; }
                }
                // The mesher's base is the lowest ground under the ring,
                // which is what the walls are *drawn* from. It is not where
                // collision starts. The pavement a body walks on is sampled
                // at the body, not at the ring, and on anything but a flat
                // block the two differ by a couple of metres - which left a
                // gap under every wall in the city that the player strolled
                // straight through. Unless the part is genuinely raised on
                // a podium, a wall is solid from well below its base.
                boolean podium = f.buildingMinHeight[b] > 0.5f;
                float lo = podium ? (float) (base + f.buildingMinHeight[b])
                        : (float) (base - 30.0);
                float hi = (float) (base + f.buildingMinHeight[b] + f.buildingHeight[b]);
                // Winding, so "outward" means out of this footprint and not
                // merely "away from this line". A wall is a one-sided
                // thing: pushed by unsigned distance, a body that gets a
                // hand's breadth past the midline is pushed on through,
                // which is exactly how both a car and a person were still
                // ending up inside the building.
                double area2 = 0;
                for (int k = s; k < e; k++) {
                    int k2 = (k + 1 < e) ? k + 1 : s;
                    area2 += (double) f.buildingXZ[k * 2] * f.buildingXZ[k2 * 2 + 1]
                            - (double) f.buildingXZ[k2 * 2] * f.buildingXZ[k * 2 + 1];
                }
                double wind = area2 >= 0 ? 1.0 : -1.0;
                for (int k = s; k < e; k++) {
                    int k2 = (k + 1 < e) ? k + 1 : s;        // close the ring
                    float ax = (float) (ox + f.buildingXZ[k * 2]);
                    float az = (float) (oz + f.buildingXZ[k * 2 + 1]);
                    float bx = (float) (ox + f.buildingXZ[k2 * 2]);
                    float bz = (float) (oz + f.buildingXZ[k2 * 2 + 1]);
                    c.wx0[at] = ax;
                    c.wz0[at] = az;
                    c.wx1[at] = bx;
                    c.wz1[at] = bz;
                    double ex = bx - ax, ez = bz - az;
                    double len = Math.hypot(ex, ez);
                    if (len < 1e-6) {
                        c.wnx[at] = 0;
                        c.wnz[at] = 0;
                    } else {
                        c.wnx[at] = (float) (wind * ez / len);
                        c.wnz[at] = (float) (-wind * ex / len);
                    }
                    c.wBase[at] = lo;
                    c.wTop[at] = hi;
                    at++;
                }
            }
            c.wallGrid = index(c, c.wx0, c.wz0, c.wx1, c.wz1, at, 1.5);

            // ---- bridge decks -------------------------------------------
            int dsegs = 0;
            for (int r = 0; r < f.roadCount(); r++) {
                if ((f.roadFlags[r] & ChunkFeatures.FLAG_BRIDGE) == 0) { continue; }
                if (f.roadClass[r] == 23) { continue; }      // ferry route
                int s = f.roadOffset[r];
                int e = f.roadOffset[r + 1];
                if (e - s >= 2) { dsegs += e - s - 1; }
            }
            c.dx0 = new float[dsegs];
            c.dz0 = new float[dsegs];
            c.dx1 = new float[dsegs];
            c.dz1 = new float[dsegs];
            c.dy0 = new float[dsegs];
            c.dy1 = new float[dsegs];
            c.dHalf = new float[dsegs];
            int dat = 0;
            for (int r = 0; r < f.roadCount(); r++) {
                if ((f.roadFlags[r] & ChunkFeatures.FLAG_BRIDGE) == 0) { continue; }
                if (f.roadClass[r] == 23) { continue; }
                int s = f.roadOffset[r];
                int e = f.roadOffset[r + 1];
                if (e - s < 2) { continue; }
                double half = Math.max(1.2, f.roadWidth[r] * 0.5);
                double y0 = groundAt(w, ox, oz, f.roadXZ, s);
                double y1 = groundAt(w, ox, oz, f.roadXZ, e - 1);
                double total = Math.max(1e-6, f.roadLength(r));
                double travelled = 0;
                double prevY = 0;
                for (int k = s; k < e; k++) {
                    double wx = ox + f.roadXZ[k * 2];
                    double wz = oz + f.roadXZ[k * 2 + 1];
                    if (k > s) {
                        double sx = f.roadXZ[k * 2] - f.roadXZ[(k - 1) * 2];
                        double sz = f.roadXZ[k * 2 + 1] - f.roadXZ[(k - 1) * 2 + 1];
                        travelled += Math.sqrt(sx * sx + sz * sz);
                    }
                    double g = w.isLand(wx, wz) ? w.height(wx, wz) : 0.0;
                    double y = RoadDeck.height(y0, y1, Math.min(1, travelled / total), g);
                    if (k > s) {
                        c.dx0[dat] = (float) (ox + f.roadXZ[(k - 1) * 2]);
                        c.dz0[dat] = (float) (oz + f.roadXZ[(k - 1) * 2 + 1]);
                        c.dx1[dat] = (float) wx;
                        c.dz1[dat] = (float) wz;
                        c.dy0[dat] = (float) prevY;
                        c.dy1[dat] = (float) y;
                        c.dHalf[dat] = (float) half;
                        dat++;
                    }
                    prevY = y;
                }
            }
            c.deckGrid = index(c, c.dx0, c.dz0, c.dx1, c.dz1, dat, 14.0);
            return c;
        }

        private static double groundAt(WorldData w, double ox, double oz,
                                       float[] xz, int i) {
            double wx = ox + xz[i * 2];
            double wz = oz + xz[i * 2 + 1];
            return w.isLand(wx, wz) ? w.height(wx, wz) : 0.0;
        }

        /** Buckets segments into the grid, with a margin so edges are caught. */
        private static int[][] index(Cell c, float[] x0, float[] z0,
                                     float[] x1, float[] z1, int count,
                                     double margin) {
            if (c.nx <= 0 || count <= 0) { return null; }
            int cells = c.nx * c.nz;
            int[] n = new int[cells];
            for (int pass = 0; pass < 2; pass++) {
                int[][] grid = pass == 1 ? new int[cells][] : null;
                if (pass == 1) {
                    for (int k = 0; k < cells; k++) {
                        grid[k] = n[k] > 0 ? new int[n[k]] : null;
                    }
                    java.util.Arrays.fill(n, 0);
                }
                for (int s = 0; s < count; s++) {
                    double lo = Math.min(x0[s], x1[s]) - margin;
                    double hi = Math.max(x0[s], x1[s]) + margin;
                    double lz = Math.min(z0[s], z1[s]) - margin;
                    double hz = Math.max(z0[s], z1[s]) + margin;
                    int i0 = clamp((int) Math.floor((lo - c.minX) / CELL_M), c.nx);
                    int i1 = clamp((int) Math.floor((hi - c.minX) / CELL_M), c.nx);
                    int j0 = clamp((int) Math.floor((lz - c.minZ) / CELL_M), c.nz);
                    int j1 = clamp((int) Math.floor((hz - c.minZ) / CELL_M), c.nz);
                    for (int j = j0; j <= j1; j++) {
                        for (int i = i0; i <= i1; i++) {
                            int k = j * c.nx + i;
                            if (pass == 1) { grid[k][n[k]] = s; }
                            n[k]++;
                        }
                    }
                }
                if (pass == 1) { return grid; }
            }
            return null;
        }

        private static int clamp(int v, int n) {
            return v < 0 ? 0 : (v > n - 1 ? n - 1 : v);
        }
    }
}
