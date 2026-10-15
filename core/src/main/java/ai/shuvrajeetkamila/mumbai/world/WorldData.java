package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.geo.GeoProjection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Everything about the world that stays resident for the whole session.
 *
 * <p>That is the 20 m rasters — ground elevation, land cover, and the land/sea
 * mask derived from the real OpenStreetMap coastline — plus the drivable road
 * graph, the chunk directory, and the place-name table. Together they are a few
 * tens of megabytes, and keeping them resident means the terrain under the
 * player, the minimap and the "where am I" readout never have to wait for a
 * streaming round trip. The per-chunk vector geometry is the part that streams.
 *
 * <p>Elevation is stored as int16 decimetres. Mumbai runs from chart datum to
 * about 450 m on the Sanjay Gandhi ridge, so decimetres give 0.1 m of
 * resolution with eleven times the headroom; centimetres would overflow at
 * 327 m, which is below the top of the hills.
 */
public final class WorldData {

    /** Raster spacing in metres. Must match {@code tools/compile_world.py}. */
    public final double gridM;
    /** World-frame position of raster cell (0, 0). */
    public final double minX;
    public final double minZ;
    public final int nx;
    public final int nz;

    private final short[] elevDm;
    private final byte[] cover;
    private final long[] landMask;

    /** Chunk directory: 6 uint32 per chunk. */
    public final int chunkNx;
    public final int chunkNz;
    public final int chunkMinX;
    public final int chunkMinZ;
    public final double chunkM;
    public final double quant;
    private final int[] chunkIndex;
    private final WorldFile chunkFile;
    private final String[] roadNames;

    /** Drivable road graph. */
    public final float[] graphNodeXZ;
    public final int[] graphEdge;
    /**
     * Five floats per edge. Use the accessors below rather than indexing
     * this directly: every consumer of it had the speed limit wrong,
     * reading slot 0 - the road class - as a speed in km/h. Motorways are
     * class 0, so the fastest roads in the city got a limit of zero and no
     * traffic at all, while everything else crawled at its class number.
     */
    public final float[] graphAttr;

    /**
     * Rail, metro, monorail and ferry alignments, as stitched polylines.
     *
     * <p>Kept out of {@link #graphEdge} on purpose: the road graph is
     * filtered to driveable classes so that traffic cannot spawn in a
     * subway tunnel and a taxi fare cannot be sent to a point in the
     * middle of the harbour. Transit runs on its own network.
     *
     * <p>{@code transitPts} is x,z pairs; {@code transitOff} indexes it in
     * points, with one extra entry on the end.
     */
    public final float[] transitPts;
    public final int[] transitOff;
    public final int[] transitClassId;

    public static final int ATTR_STRIDE = 5;
    public static final int ATTR_CLASS = 0;
    public static final int ATTR_LANES = 1;
    public static final int ATTR_ONEWAY = 2;
    public static final int ATTR_WIDTH = 3;
    public static final int ATTR_SPEED_KPH = 4;

    /** OSM road class of an edge; 0 is motorway. */
    public int edgeClass(int e) {
        return (int) graphAttr[e * ATTR_STRIDE + ATTR_CLASS];
    }

    /** Posted or inferred speed limit, km/h. */
    public double edgeSpeedKph(int e) {
        return graphAttr[e * ATTR_STRIDE + ATTR_SPEED_KPH];
    }

    public int edgeLanes(int e) {
        return (int) graphAttr[e * ATTR_STRIDE + ATTR_LANES];
    }

    public double edgeWidthM(int e) {
        return graphAttr[e * ATTR_STRIDE + ATTR_WIDTH];
    }

    public int edgeCount() { return graphEdge.length / 2; }

    private volatile Junctions junctionCache;

    /**
     * The junction network, derived from the road graph on first use.
     *
     * <p>Cached here rather than owned by the engine because the mesher
     * needs it too - it plants the signal masts - and the two must be
     * looking at the same junctions or the lights will not be where the
     * traffic thinks they are. Costs about 140 ms to build once.
     */
    public Junctions junctions() {
        Junctions j = junctionCache;
        if (j == null) {
            synchronized (this) {
                j = junctionCache;
                if (j == null) {
                    j = new Junctions(this);
                    junctionCache = j;
                }
            }
        }
        return j;
    }

    // --- transit ----------------------------------------------------------

    /** Road class ids for the transit network, matching tools/extract_osm.py. */
    public static final int CLS_RAIL = 15;
    public static final int CLS_LIGHT_RAIL = 16;
    public static final int CLS_SUBWAY = 17;
    public static final int CLS_MONORAIL = 18;
    public static final int CLS_FERRY = 23;

    public int transitCount() { return transitClassId.length; }

    public int transitClass(int route) { return transitClassId[route]; }

    /** Points in a route. */
    public int transitPointCount(int route) {
        return transitOff[route + 1] - transitOff[route];
    }

    public double transitX(int route, int i) {
        return transitPts[(transitOff[route] + i) * 2];
    }

    public double transitZ(int route, int i) {
        return transitPts[(transitOff[route] + i) * 2 + 1];
    }

    /** Length of a route in metres. */
    public double transitLength(int route) {
        double d = 0;
        int n = transitPointCount(route);
        for (int i = 1; i < n; i++) {
            d += Math.hypot(transitX(route, i) - transitX(route, i - 1),
                    transitZ(route, i) - transitZ(route, i - 1));
        }
        return d;
    }

    /** True for a route carried by a vessel rather than a train. */
    public static boolean isWaterRoute(int cls) { return cls == CLS_FERRY; }

    private final String manifestJson;
    private final String buildId;
    private final long builtUnix;
    public final boolean selfTest;

    private final Places places;
    private final CraftSpawns craft;

    public static final int COVER_WATER = 0;

    private WorldData(WorldFile terrain, WorldFile chunks, WorldFile network,
                      WorldFile manifest) throws IOException {
        this.manifestJson = manifest.readString("manifest");
        this.buildId = manifest.buildId();
        this.builtUnix = manifest.builtUnixSeconds();
        this.selfTest = Json.bool(manifestJson, "selftest", false);

        String rmeta = terrain.readString("rmeta");
        nx = Json.integer(rmeta, "nx", 0);
        nz = Json.integer(rmeta, "nz", 0);
        gridM = Json.number(rmeta, "grid_m", 20.0);
        minX = Json.number(rmeta, "min_x", 0);
        minZ = Json.number(rmeta, "min_z", 0);
        if (nx <= 1 || nz <= 1 || (long) nx * nz > 80_000_000L) {
            throw new IOException("implausible raster " + nx + "x" + nz);
        }
        elevDm = terrain.readGridI16("elev_dm", nx, nz);
        cover = terrain.read("cover");
        if (cover.length != nx * nz) {
            throw new IOException("cover raster is " + cover.length + ", expected "
                    + (nx * nz));
        }
        landMask = terrain.readBitset("landmask", nx * nz);

        String cmeta = chunks.readString("cmeta");
        chunkNx = Json.integer(cmeta, "cnx", 0);
        chunkNz = Json.integer(cmeta, "cnz", 0);
        chunkMinX = Json.integer(cmeta, "cminx", 0);
        chunkMinZ = Json.integer(cmeta, "cminz", 0);
        chunkM = Json.number(cmeta, "chunk_m", 1024.0);
        quant = Json.number(cmeta, "quant", 0.05);
        ByteBuffer ci = chunks.readBuffer("cindex");
        chunkIndex = new int[chunkNx * chunkNz * 6];
        if (ci.remaining() < chunkIndex.length * 4) {
            throw new IOException("chunk index is short: " + ci.remaining());
        }
        for (int i = 0; i < chunkIndex.length; i++) { chunkIndex[i] = ci.getInt(); }
        roadNames = chunks.readStringTable("names");
        chunkFile = chunks;

        ByteBuffer gn = network.readBuffer("gnodes");
        graphNodeXZ = new float[gn.remaining() / 4];
        for (int i = 0; i < graphNodeXZ.length; i++) { graphNodeXZ[i] = gn.getFloat(); }
        ByteBuffer ge = network.readBuffer("gedges");
        graphEdge = new int[ge.remaining() / 4];
        for (int i = 0; i < graphEdge.length; i++) { graphEdge[i] = ge.getInt(); }
        ByteBuffer ga = network.readBuffer("gattr");
        graphAttr = new float[ga.remaining() / 4];
        for (int i = 0; i < graphAttr.length; i++) { graphAttr[i] = ga.getFloat(); }

        // Tolerate a world built before transit existed rather than
        // refusing to load it: an older package is still a playable city.
        if (network.has("tpts") && network.has("toff") && network.has("tcls")) {
            ByteBuffer tp = network.readBuffer("tpts");
            transitPts = new float[tp.remaining() / 4];
            for (int i = 0; i < transitPts.length; i++) { transitPts[i] = tp.getFloat(); }
            ByteBuffer to = network.readBuffer("toff");
            transitOff = new int[to.remaining() / 4];
            for (int i = 0; i < transitOff.length; i++) { transitOff[i] = to.getInt(); }
            ByteBuffer tc = network.readBuffer("tcls");
            transitClassId = new int[tc.remaining() / 4];
            for (int i = 0; i < transitClassId.length; i++) {
                transitClassId[i] = tc.getInt();
            }
        } else {
            transitPts = new float[0];
            transitOff = new int[]{0};
            transitClassId = new int[0];
        }

        places = Places.parse(network.readString("places"));
        // Tolerate a world compiled before craft moorings existed, the same
        // way transit is tolerated, rather than refusing to open it.
        CraftSpawns cs;
        try {
            cs = CraftSpawns.parse(network.readString("craft"));
        } catch (IOException | RuntimeException ignored) {
            cs = CraftSpawns.empty();
        }
        craft = cs;
    }

    /** Load the four packaged files through whatever asset source the host has. */
    public static WorldData load(Assets assets) throws IOException {
        WorldFile terrain = WorldFile.open(assets.open("world/terrain.mwl"));
        WorldFile chunks = WorldFile.open(assets.open("world/chunks.mwl"));
        WorldFile network = WorldFile.open(assets.open("world/network.mwl"));
        WorldFile manifest = WorldFile.open(assets.open("world/manifest.mwl"));
        return new WorldData(terrain, chunks, network, manifest);
    }

    public String manifestJson() { return manifestJson; }

    public String buildId() { return buildId; }

    public long builtUnixSeconds() { return builtUnix; }

    public Places places() { return places; }

    /** Where the boats, helicopters and aeroplanes are parked. */
    public CraftSpawns craft() { return craft; }

    public String roadName(int index) {
        return index >= 0 && index < roadNames.length ? roadNames[index] : "";
    }

    public int roadNameCount() { return roadNames.length; }

    // ---------------------------------------------------------------- rasters

    private int cellX(double x) {
        int i = (int) Math.floor((x - minX) / gridM);
        return i < 0 ? 0 : (i > nx - 1 ? nx - 1 : i);
    }

    private int cellZ(double z) {
        int j = (int) Math.floor((z - minZ) / gridM);
        return j < 0 ? 0 : (j > nz - 1 ? nz - 1 : j);
    }

    /** Raw elevation of one raster cell, metres. */
    public double cellElevation(int i, int j) {
        return elevDm[j * nx + i] * 0.1;
    }

    /**
     * Ground height, metres above chart datum, bilinearly interpolated.
     *
     * <p>Bilinear rather than nearest because the car suspension differentiates
     * this surface sixty times a second; a nearest-cell lookup turns every 20 m
     * raster boundary into a step and the whole city feels like cobblestones.
     */
    public double height(double x, double z) {
        double fx = (x - minX) / gridM;
        double fz = (z - minZ) / gridM;
        int i = (int) Math.floor(fx);
        int j = (int) Math.floor(fz);
        if (i < 0) { i = 0; fx = 0; }
        if (j < 0) { j = 0; fz = 0; }
        if (i > nx - 2) { i = nx - 2; fx = i; }
        if (j > nz - 2) { j = nz - 2; fz = j; }
        double tx = fx - i;
        double tz = fz - j;
        int b = j * nx + i;
        double h00 = elevDm[b];
        double h10 = elevDm[b + 1];
        double h01 = elevDm[b + nx];
        double h11 = elevDm[b + nx + 1];
        return 0.1 * ((h00 * (1 - tx) + h10 * tx) * (1 - tz)
                + (h01 * (1 - tx) + h11 * tx) * tz);
    }

    /** Surface normal from central differences on the height field. */
    public void normal(double x, double z, double[] out3) {
        double d = gridM;
        double hx = height(x + d, z) - height(x - d, z);
        double hz = height(x, z + d) - height(x, z - d);
        double nxv = -hx / (2 * d);
        double nzv = -hz / (2 * d);
        double len = Math.sqrt(nxv * nxv + 1 + nzv * nzv);
        out3[0] = nxv / len;
        out3[1] = 1.0 / len;
        out3[2] = nzv / len;
    }

    /** Steepest slope at a point, radians. */
    public double slope(double x, double z) {
        double[] n = new double[3];
        normal(x, z, n);
        return Math.acos(Math.max(-1, Math.min(1, n[1])));
    }

    /** Land cover class id; see {@code LANDUSE_CLASSES} in the extractor. */
    public int cover(double x, double z) {
        return cover[cellZ(z) * nx + cellX(x)] & 0xFF;
    }

    /**
     * True where the real OSM coastline says there is land.
     *
     * <p>This is the single authority for land versus water in the game. The
     * boat cannot drive up a beach because this says the beach is land; the car
     * cannot drive into Back Bay because this says Back Bay is not.
     */
    public boolean isLand(double x, double z) {
        int bit = cellZ(z) * nx + cellX(x);
        return (landMask[bit >>> 6] & (1L << (bit & 63))) != 0;
    }

    public boolean isWater(double x, double z) { return !isLand(x, z); }

    /**
     * The land mask as a texture the renderer can sample, one byte a cell.
     *
     * <p>Why this exists. The sea is drawn as a surface at mean level plus
     * the tide, and the tide is a real one: a shade under two metres
     * either way, which is what Mumbai Harbour actually does. The terrain
     * is a 20 m DEM, and 6 % of the land in it sits below the two metre
     * mark - the reclaimed ground along Back Bay, the dock aprons, the
     * low ground behind Mahim. So at high water the sea was being drawn
     * straight over the city: shimmering where the two surfaces met
     * within a few centimetres of each other, open water where it had
     * risen clear. Neither is a tide. It is a disagreement between the
     * terrain's datum and the sea's, and the way to settle it is to let
     * the renderer read the same land mask that the boat physics has
     * always used, and never paint sea on land.
     *
     * @param metres how coarse the texture may be; rounded up to a whole
     *               multiple of the mask's own cell size
     * @return a row-major mask, 255 for land and 0 for water, starting at
     *         {@link #minX}, {@link #minZ}
     */
    public byte[] landMaskTexels(double metres, int[] sizeOut) {
        int step = Math.max(1, (int) Math.round(metres / gridM));
        int tw = (nx + step - 1) / step;
        int th = (nz + step - 1) / step;
        byte[] out = new byte[tw * th];
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                // Land wins over water within a texel. A texel that is
                // half pavement must not be painted as sea, and the
                // terrain is drawn over the top of it anyway.
                boolean land = false;
                for (int dz = 0; dz < step && !land; dz++) {
                    int j = ty * step + dz;
                    if (j >= nz) { break; }
                    for (int dx = 0; dx < step; dx++) {
                        int i = tx * step + dx;
                        if (i >= nx) { break; }
                        int bit = j * nx + i;
                        if ((landMask[bit >>> 6] & (1L << (bit & 63))) != 0) {
                            land = true;
                            break;
                        }
                    }
                }
                out[ty * tw + tx] = (byte) (land ? 255 : 0);
            }
        }
        if (sizeOut != null && sizeOut.length >= 2) {
            sizeOut[0] = tw;
            sizeOut[1] = th;
        }
        return out;
    }

    /** Width in metres covered by the mask, for the texture transform. */
    public double maskSpanX() { return nx * gridM; }

    /** Height in metres covered by the mask, for the texture transform. */
    public double maskSpanZ() { return nz * gridM; }

    /** Depth below chart datum, metres, zero on land. Shallows near the shore. */
    public double seaDepth(double x, double z) {
        if (isLand(x, z)) { return 0; }
        double d = distanceToLand(x, z, 2200.0);
        return Math.min(34.0, 1.2 + d * 0.016);
    }

    /**
     * Approximate distance to the nearest land cell, metres, searching outward
     * in rings and giving up at {@code limit}.
     */
    public double distanceToLand(double x, double z, double limit) {
        int ci = cellX(x);
        int cj = cellZ(z);
        int maxR = (int) Math.ceil(limit / gridM);
        if (isLand(x, z)) { return 0; }
        for (int r = 1; r <= maxR; r++) {
            int i0 = Math.max(0, ci - r), i1 = Math.min(nx - 1, ci + r);
            int j0 = Math.max(0, cj - r), j1 = Math.min(nz - 1, cj + r);
            for (int i = i0; i <= i1; i++) {
                if (landAt(i, j0) || landAt(i, j1)) { return r * gridM; }
            }
            for (int j = j0; j <= j1; j++) {
                if (landAt(i0, j) || landAt(i1, j)) { return r * gridM; }
            }
        }
        return limit;
    }

    /**
     * Whether any part of a box contains land, at the land mask's own
     * resolution.
     *
     * <p>Exists because the terrain mesh and the sea mask were asking
     * two different questions and getting two different answers. The
     * mesh dropped a cell whose corners were all sea - sampled every
     * 32 m at full detail and every 128 m at the lowest - while the
     * mask marked a texel as land if any 20 m cell inside it was land,
     * and the sea is discarded wherever the mask says land. Along a
     * ragged coast that leaves strips up to a cell wide where the sea
     * has been discarded and the terrain was never emitted: a hole, in
     * the colour the frame was cleared to.
     *
     * <p>Inclusive of the bounds and clamped to the grid, so a query
     * that runs off the edge of the world answers for the part of it
     * that exists.
     */
    public boolean anyLand(double x0, double z0, double x1, double z1) {
        int i0 = (int) Math.floor((Math.min(x0, x1) - minX) / gridM);
        int i1 = (int) Math.floor((Math.max(x0, x1) - minX) / gridM);
        int j0 = (int) Math.floor((Math.min(z0, z1) - minZ) / gridM);
        int j1 = (int) Math.floor((Math.max(z0, z1) - minZ) / gridM);
        if (i1 < 0 || j1 < 0 || i0 >= nx || j0 >= nz) { return false; }
        i0 = Math.max(0, i0);
        j0 = Math.max(0, j0);
        i1 = Math.min(nx - 1, i1);
        j1 = Math.min(nz - 1, j1);
        for (int j = j0; j <= j1; j++) {
            int row = j * nx;
            for (int i = i0; i <= i1; i++) {
                int bit = row + i;
                if ((landMask[bit >>> 6] & (1L << (bit & 63))) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean landAt(int i, int j) {
        int bit = j * nx + i;
        return (landMask[bit >>> 6] & (1L << (bit & 63))) != 0;
    }

    public int coverAt(int i, int j) { return cover[j * nx + i] & 0xFF; }

    // ----------------------------------------------------------------- chunks

    public int chunkCount() { return chunkNx * chunkNz; }

    public int chunkIdAt(double x, double z) {
        int i = (int) Math.floor(x / chunkM) - chunkMinX;
        int j = (int) Math.floor(z / chunkM) - chunkMinZ;
        if (i < 0 || j < 0 || i >= chunkNx || j >= chunkNz) { return -1; }
        return j * chunkNx + i;
    }

    public double chunkOriginX(int id) { return ((id % chunkNx) + chunkMinX) * chunkM; }

    public double chunkOriginZ(int id) { return ((id / chunkNx) + chunkMinZ) * chunkM; }

    public boolean chunkHasData(int id) {
        return id >= 0 && id < chunkCount() && chunkIndex[id * 6 + 1] > 0;
    }

    public int chunkBuildingCount(int id) { return chunkIndex[id * 6 + 3]; }

    public int chunkRoadCount(int id) { return chunkIndex[id * 6 + 4]; }

    public int chunkStoredBytes(int id) { return chunkIndex[id * 6 + 1]; }

    /**
     * Inflate one chunk's vector payload. Called on streaming workers, so it
     * must not touch any mutable shared state — it does not.
     */
    public byte[] readChunk(int id) throws IOException {
        int off = chunkIndex[id * 6];
        int stored = chunkIndex[id * 6 + 1];
        int raw = chunkIndex[id * 6 + 2];
        if (stored == 0) { return new byte[0]; }
        byte[] comp = chunkFile.readStored("cdata", off, stored);
        byte[] out = new byte[raw];
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        try {
            inf.setInput(comp);
            int got = 0;
            while (got < raw) {
                int n = inf.inflate(out, got, raw - got);
                if (n == 0) {
                    if (inf.finished() || inf.needsInput()) { break; }
                }
                got += n;
            }
            if (got != raw) {
                throw new IOException("chunk " + id + " inflated to " + got
                        + " of " + raw + " bytes");
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("chunk " + id + " is corrupt", e);
        } finally {
            inf.end();
        }
        return out;
    }

    // ------------------------------------------------------------- navigation

    /** Latitude of a world position, for the HUD and for checkpoint tests. */
    public double latOf(double z) { return GeoProjection.toLat(z); }

    public double lonOf(double x, double z) { return GeoProjection.toLon(x, z); }

    public double minWorldX() { return minX; }

    public double minWorldZ() { return minZ; }

    public double maxWorldX() { return minX + (nx - 1) * gridM; }

    public double maxWorldZ() { return minZ + (nz - 1) * gridM; }

    public boolean inBounds(double x, double z) {
        return x >= minWorldX() && x <= maxWorldX()
                && z >= minWorldZ() && z <= maxWorldZ();
    }

    /** Total resident bytes, so the HUD can be honest about memory. */
    public long residentBytes() {
        return (long) elevDm.length * 2 + cover.length + (long) landMask.length * 8
                + (long) chunkIndex.length * 4 + (long) graphNodeXZ.length * 4
                + (long) graphEdge.length * 4 + (long) graphAttr.length * 4;
    }
}
