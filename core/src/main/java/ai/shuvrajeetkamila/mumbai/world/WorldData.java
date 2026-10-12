package ai.shuvrajeetkamila.mumbai.world;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.geo.GeoProjection;
import ai.shuvrajeetkamila.mumbai.world.MwlReader.WorldFormatException;

/**
 * The packaged Mumbai world: manifest, terrain rasters, road / rail graph, city features
 * and the streaming chunk index. Loaded once at startup, then sampled by the streamer,
 * the renderer, the physics surfaces and the HUD.
 *
 * <p>Nothing here touches Android APIs, so the identical loader runs inside the JVM
 * coverage/startup tests against the real packaged asset tree.
 */
public final class WorldData {

    /** Abstracts assets vs. a plain directory so tests can load the shipped files. */
    public interface AssetSource {
        InputStream open(String name) throws IOException;
    }

    // ---- manifest ---------------------------------------------------------------
    public String aoiName = "";
    public String aoiVersion = "";
    public double originLat, originLon;
    public double aoiMinLat, aoiMaxLat, aoiMinLon, aoiMaxLon;
    public double minX, maxX, minZ, maxZ;
    public float waterBuffer;
    public int seed;
    public final List<String> layerNames = new ArrayList<>();
    public final List<Long> layerCrc = new ArrayList<>();

    public static final class Checkpoint {
        public String key;
        public String label;
        public double lat, lon;
        public float x, z;
    }

    public final List<Checkpoint> checkpoints = new ArrayList<>();

    public String spawnName = "Mumbai";
    public float spawnX, spawnZ, spawnHeading;

    // ---- terrain ----------------------------------------------------------------
    public int luW, luH;
    public float luRes;
    public double luX0, luZ0;
    public byte[] landUse;

    public int htW, htH;
    public float htRes;
    public double htX0, htZ0;
    public short[] heightDm;   // decimetres

    // ---- network ----------------------------------------------------------------
    public static final class Polyline {
        public String name;
        public int cls;        // road class 0..3  /  rail kind 0..2
        public int lanes;
        public int layer;      // -1 tunnel, 0 ground, 1 elevated
        public float[] pts;    // x,z pairs
        public float length;

        public int pointCount() {
            return pts.length / 2;
        }

        public float x(int i) {
            return pts[i * 2];
        }

        public float z(int i) {
            return pts[i * 2 + 1];
        }
    }

    public final List<Polyline> roads = new ArrayList<>();
    public final List<Polyline> rails = new ArrayList<>();

    public static final class Station {
        public String name;
        public float x, z;
        public int line;
    }

    public final List<Station> stations = new ArrayList<>();

    // ---- city -------------------------------------------------------------------
    public static final class District {
        public String name;
        public float x, z, radius;
        public int density;
    }

    public static final class Landmark {
        public String name;
        public String kind;
        public float x, z, heading, a, b;
    }

    public static final class Runway {
        public String designator;
        public float x, z, heading, length, width;
    }

    public static final class Airport {
        public String icao;
        public String name;
        public float x, z, elevation;
        public final List<Runway> runways = new ArrayList<>();
    }

    public static final class Spot {
        public String name;
        public String tag;
        public float x, z, value;
    }

    public final List<District> districts = new ArrayList<>();
    public final List<Landmark> landmarks = new ArrayList<>();
    public final List<Airport> airports = new ArrayList<>();
    public final List<Spot> helipads = new ArrayList<>();
    public final List<Spot> boatLaunches = new ArrayList<>();
    public final List<Spot> beaches = new ArrayList<>();

    // ---- chunk index -------------------------------------------------------------
    public int chunksX, chunksZ;
    public float chunkSize;
    public double chunkX0, chunkZ0;
    public byte[] chunkFlags;
    public byte[] chunkLandFrac;
    public byte[] chunkDensity;
    public byte[] chunkRoads;
    public int[] chunkBuildings;

    public static final int CHUNK_HAS_LAND = 1;
    public static final int CHUNK_HAS_WATER = 2;
    public static final int CHUNK_NATURAL = 4;
    public static final int CHUNK_AIRPORT = 8;
    public static final int CHUNK_CBD = 16;
    public static final int CHUNK_ROAD = 32;
    public static final int CHUNK_RAIL = 64;

    private GeoProjection projection;

    // =================================================================================
    public static WorldData load(AssetSource src) throws IOException, WorldFormatException {
        WorldData w = new WorldData();
        w.readManifest(read(src, "world/manifest.mwl"));
        w.readTerrain(read(src, "world/terrain.mwl"));
        w.readNetwork(read(src, "world/network.mwl"));
        w.readCity(read(src, "world/city.mwl"));
        w.readChunks(read(src, "world/chunks.mwl"));
        w.projection = new GeoProjection(w.originLat, w.originLon);
        w.buildRoadIndex();
        return w;
    }

    private static byte[] read(AssetSource src, String name) throws IOException {
        InputStream in = src.open(name);
        try {
            return MwlReader.readFully(in);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    public GeoProjection projection() {
        if (projection == null) {
            projection = new GeoProjection(originLat, originLon);
        }
        return projection;
    }

    // ---- parsing ------------------------------------------------------------------
    private void readManifest(byte[] data) throws WorldFormatException {
        MwlReader r = new MwlReader(data);
        if (r.layerId != MwlReader.LAYER_MANIFEST) {
            throw new WorldFormatException("manifest layer id " + r.layerId);
        }
        aoiName = r.string();
        aoiVersion = r.string();
        originLat = r.f64();
        originLon = r.f64();
        aoiMinLat = r.f64();
        aoiMaxLat = r.f64();
        aoiMinLon = r.f64();
        aoiMaxLon = r.f64();
        minX = r.f64();
        maxX = r.f64();
        minZ = r.f64();
        maxZ = r.f64();
        waterBuffer = r.f32();
        seed = (int) r.u32();
        int nLayers = (int) r.u32();
        for (int i = 0; i < nLayers; i++) {
            layerNames.add(r.string());
            layerCrc.add(r.u32());
        }
        int nCp = (int) r.u32();
        for (int i = 0; i < nCp; i++) {
            Checkpoint c = new Checkpoint();
            c.key = r.string();
            c.label = r.string();
            c.lat = r.f64();
            c.lon = r.f64();
            c.x = r.f32();
            c.z = r.f32();
            checkpoints.add(c);
        }
        spawnName = r.string();
        spawnX = r.f32();
        spawnZ = r.f32();
        spawnHeading = r.f32();
    }

    private void readTerrain(byte[] data) throws WorldFormatException {
        MwlReader r = new MwlReader(data);
        if (r.layerId != MwlReader.LAYER_TERRAIN) {
            throw new WorldFormatException("terrain layer id " + r.layerId);
        }
        luW = (int) r.u32();
        luH = (int) r.u32();
        luRes = r.f32();
        luX0 = r.f64();
        luZ0 = r.f64();
        landUse = r.blob();
        if (landUse.length != luW * luH) {
            throw new WorldFormatException("land use size " + landUse.length + " != " + (luW * luH));
        }
        htW = (int) r.u32();
        htH = (int) r.u32();
        htRes = r.f32();
        htX0 = r.f64();
        htZ0 = r.f64();
        heightDm = r.blobAsShorts();
        if (heightDm.length != htW * htH) {
            throw new WorldFormatException("height size " + heightDm.length + " != " + (htW * htH));
        }
    }

    private void readNetwork(byte[] data) throws WorldFormatException {
        MwlReader r = new MwlReader(data);
        if (r.layerId != MwlReader.LAYER_NETWORK) {
            throw new WorldFormatException("network layer id " + r.layerId);
        }
        int nRoads = (int) r.u32();
        for (int i = 0; i < nRoads; i++) {
            Polyline p = new Polyline();
            p.name = r.string();
            p.cls = r.u8();
            p.lanes = r.u8();
            p.layer = r.i8();
            int n = r.u16();
            p.pts = new float[n * 2];
            for (int k = 0; k < n; k++) {
                p.pts[k * 2] = r.f32();
                p.pts[k * 2 + 1] = r.f32();
            }
            p.length = polylineLength(p.pts);
            roads.add(p);
        }
        int nRails = (int) r.u32();
        for (int i = 0; i < nRails; i++) {
            Polyline p = new Polyline();
            p.name = r.string();
            p.cls = r.u8();
            p.layer = r.i8();
            int n = r.u16();
            p.pts = new float[n * 2];
            for (int k = 0; k < n; k++) {
                p.pts[k * 2] = r.f32();
                p.pts[k * 2 + 1] = r.f32();
            }
            p.length = polylineLength(p.pts);
            rails.add(p);
        }
        int nStations = (int) r.u32();
        for (int i = 0; i < nStations; i++) {
            Station s = new Station();
            s.name = r.string();
            s.x = r.f32();
            s.z = r.f32();
            s.line = r.u8();
            stations.add(s);
        }
    }

    private void readCity(byte[] data) throws WorldFormatException {
        MwlReader r = new MwlReader(data);
        if (r.layerId != MwlReader.LAYER_CITY) {
            throw new WorldFormatException("city layer id " + r.layerId);
        }
        int n = (int) r.u32();
        for (int i = 0; i < n; i++) {
            District d = new District();
            d.name = r.string();
            d.x = r.f32();
            d.z = r.f32();
            d.radius = r.f32();
            d.density = r.u8();
            districts.add(d);
        }
        n = (int) r.u32();
        for (int i = 0; i < n; i++) {
            Landmark l = new Landmark();
            l.name = r.string();
            l.kind = r.string();
            l.x = r.f32();
            l.z = r.f32();
            l.heading = r.f32();
            l.a = r.f32();
            l.b = r.f32();
            landmarks.add(l);
        }
        n = (int) r.u32();
        for (int i = 0; i < n; i++) {
            Airport a = new Airport();
            a.icao = r.string();
            a.name = r.string();
            a.x = r.f32();
            a.z = r.f32();
            a.elevation = r.f32();
            int rw = r.u16();
            for (int k = 0; k < rw; k++) {
                Runway run = new Runway();
                run.designator = r.string();
                run.x = r.f32();
                run.z = r.f32();
                run.heading = r.f32();
                run.length = r.f32();
                run.width = r.f32();
                a.runways.add(run);
            }
            airports.add(a);
        }
        n = (int) r.u32();
        for (int i = 0; i < n; i++) {
            Spot s = new Spot();
            s.name = r.string();
            s.x = r.f32();
            s.z = r.f32();
            s.value = r.f32();
            s.tag = "helipad";
            helipads.add(s);
        }
        n = (int) r.u32();
        for (int i = 0; i < n; i++) {
            Spot s = new Spot();
            s.name = r.string();
            s.tag = r.string();
            s.x = r.f32();
            s.z = r.f32();
            boatLaunches.add(s);
        }
        n = (int) r.u32();
        for (int i = 0; i < n; i++) {
            Spot s = new Spot();
            s.name = r.string();
            s.x = r.f32();
            s.z = r.f32();
            s.value = r.f32();
            s.tag = "beach";
            beaches.add(s);
        }
    }

    private void readChunks(byte[] data) throws WorldFormatException {
        MwlReader r = new MwlReader(data);
        if (r.layerId != MwlReader.LAYER_CHUNKS) {
            throw new WorldFormatException("chunk layer id " + r.layerId);
        }
        chunksX = (int) r.u32();
        chunksZ = (int) r.u32();
        chunkSize = r.f32();
        chunkX0 = r.f64();
        chunkZ0 = r.f64();
        chunkFlags = r.blob();
        chunkLandFrac = r.blob();
        chunkDensity = r.blob();
        chunkRoads = r.blob();
        chunkBuildings = r.blobAsUnsignedShorts();
        int expect = chunksX * chunksZ;
        if (chunkFlags.length != expect || chunkBuildings.length != expect) {
            throw new WorldFormatException("chunk index size mismatch");
        }
    }

    private static float polylineLength(float[] pts) {
        float total = 0;
        for (int i = 2; i < pts.length; i += 2) {
            float dx = pts[i] - pts[i - 2];
            float dz = pts[i + 1] - pts[i - 1];
            total += (float) Math.sqrt(dx * dx + dz * dz);
        }
        return total;
    }

    // ---- sampling -------------------------------------------------------------------

    /** Land use code at a world position (nearest cell). */
    public int landUseAt(double x, double z) {
        int i = (int) ((x - luX0) / luRes);
        int j = (int) ((z - luZ0) / luRes);
        if (i < 0 || j < 0 || i >= luW || j >= luH) {
            return LandUse.DEEP_SEA;
        }
        return landUse[j * luW + i] & 0xFF;
    }

    public boolean isWaterAt(double x, double z) {
        return LandUse.isWater(landUseAt(x, z));
    }

    /** Bilinear terrain / seabed elevation in metres. */
    public float heightAt(double x, double z) {
        double fx = (x - htX0) / htRes;
        double fz = (z - htZ0) / htRes;
        int i = (int) Math.floor(fx);
        int j = (int) Math.floor(fz);
        if (i < 0) {
            i = 0;
        }
        if (j < 0) {
            j = 0;
        }
        if (i > htW - 2) {
            i = htW - 2;
        }
        if (j > htH - 2) {
            j = htH - 2;
        }
        float tx = (float) Mth.clamp(fx - i, 0.0, 1.0);
        float tz = (float) Mth.clamp(fz - j, 0.0, 1.0);
        float h00 = heightDm[j * htW + i] * 0.1f;
        float h10 = heightDm[j * htW + i + 1] * 0.1f;
        float h01 = heightDm[(j + 1) * htW + i] * 0.1f;
        float h11 = heightDm[(j + 1) * htW + i + 1] * 0.1f;
        float a = h00 + (h10 - h00) * tx;
        float b = h01 + (h11 - h01) * tx;
        return a + (b - a) * tz;
    }

    /** Surface normal from the height field. */
    public void normalAt(double x, double z, float[] out) {
        float d = htRes;
        float hl = heightAt(x - d, z);
        float hr = heightAt(x + d, z);
        float hd = heightAt(x, z - d);
        float hu = heightAt(x, z + d);
        float nx = hl - hr;
        float nz = hd - hu;
        float ny = 2f * d;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        out[0] = nx / len;
        out[1] = ny / len;
        out[2] = nz / len;
    }

    public boolean inBounds(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public int chunkIndexX(double x) {
        return (int) Math.floor((x - chunkX0) / chunkSize);
    }

    public int chunkIndexZ(double z) {
        return (int) Math.floor((z - chunkZ0) / chunkSize);
    }

    public boolean chunkInRange(int cx, int cz) {
        return cx >= 0 && cz >= 0 && cx < chunksX && cz < chunksZ;
    }

    public int chunkFlags(int cx, int cz) {
        if (!chunkInRange(cx, cz)) {
            return 0;
        }
        return chunkFlags[cz * chunksX + cx] & 0xFF;
    }

    public float chunkLandFraction(int cx, int cz) {
        if (!chunkInRange(cx, cz)) {
            return 0f;
        }
        return (chunkLandFrac[cz * chunksX + cx] & 0xFF) / 255f;
    }

    public int chunkDensityClass(int cx, int cz) {
        if (!chunkInRange(cx, cz)) {
            return 0;
        }
        return chunkDensity[cz * chunksX + cx] & 0xFF;
    }

    public int chunkBuildingEstimate(int cx, int cz) {
        if (!chunkInRange(cx, cz)) {
            return 0;
        }
        return chunkBuildings[cz * chunksX + cx];
    }

    public double chunkCentreX(int cx) {
        return chunkX0 + (cx + 0.5) * chunkSize;
    }

    public double chunkCentreZ(int cz) {
        return chunkZ0 + (cz + 0.5) * chunkSize;
    }

    /** Nearest district name, for the HUD. */
    public String districtAt(double x, double z) {
        String best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < districts.size(); i++) {
            District d = districts.get(i);
            double dx = x - d.x;
            double dz = z - d.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double score = dist - d.radius * 0.55;
            if (score < bestScore) {
                bestScore = score;
                best = d.name;
            }
        }
        if (best == null) {
            return "Arabian Sea";
        }
        if (bestScore > 2600) {
            int lu = landUseAt(x, z);
            if (LandUse.isWater(lu)) {
                return LandUse.name(lu);
            }
        }
        return best;
    }

    // --- road spatial index ---------------------------------------------------------
    // A uniform bucket grid over the road segments. Grip, building placement and the
    // traffic system all ask "how far is the nearest road" many times per frame, and a
    // linear scan of 270 km of polylines is far too slow for that.
    private static final float ROAD_CELL = 256f;
    private int roadGridW, roadGridH;
    private int[][] roadGrid;          // cell -> packed (roadIndex << 16 | segmentIndex)

    private void buildRoadIndex() {
        roadGridW = Math.max(1, (int) Math.ceil((maxX - minX) / ROAD_CELL));
        roadGridH = Math.max(1, (int) Math.ceil((maxZ - minZ) / ROAD_CELL));
        int cells = roadGridW * roadGridH;
        int[] counts = new int[cells];
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                roadGrid = new int[cells][];
                for (int c = 0; c < cells; c++) {
                    if (counts[c] > 0) {
                        roadGrid[c] = new int[counts[c]];
                    }
                    counts[c] = 0;
                }
            }
            for (int i = 0; i < roads.size() && i < 0xFFFF; i++) {
                Polyline p = roads.get(i);
                for (int k = 1; k < p.pointCount() && k < 0xFFFF; k++) {
                    double ax = p.x(k - 1), az = p.z(k - 1);
                    double bx = p.x(k), bz = p.z(k);
                    int cx0 = cellX(Math.min(ax, bx) - ROAD_CELL);
                    int cx1 = cellX(Math.max(ax, bx) + ROAD_CELL);
                    int cz0 = cellZ(Math.min(az, bz) - ROAD_CELL);
                    int cz1 = cellZ(Math.max(az, bz) + ROAD_CELL);
                    int packed = (i << 16) | k;
                    for (int cz = cz0; cz <= cz1; cz++) {
                        for (int cx = cx0; cx <= cx1; cx++) {
                            int c = cz * roadGridW + cx;
                            if (pass == 0) {
                                counts[c]++;
                            } else {
                                roadGrid[c][counts[c]++] = packed;
                            }
                        }
                    }
                }
            }
        }
    }

    private int cellX(double x) {
        return Mth.clamp((int) Math.floor((x - minX) / ROAD_CELL), 0, roadGridW - 1);
    }

    private int cellZ(double z) {
        return Mth.clamp((int) Math.floor((z - minZ) / ROAD_CELL), 0, roadGridH - 1);
    }

    /** Distance (m) to the nearest road centre line. */
    public double distanceToRoad(double x, double z) {
        return distanceToRoad(x, z, null);
    }

    /** Distance (m) to the nearest road centre line, with the road index in out[0]. */
    public double distanceToRoad(double x, double z, int[] outIndex) {
        double best = Double.MAX_VALUE;
        int bestIdx = -1;
        if (roadGrid != null) {
            int cx = cellX(x);
            int cz = cellZ(z);
            for (int oz = -1; oz <= 1; oz++) {
                int gz = cz + oz;
                if (gz < 0 || gz >= roadGridH) {
                    continue;
                }
                for (int ox = -1; ox <= 1; ox++) {
                    int gx = cx + ox;
                    if (gx < 0 || gx >= roadGridW) {
                        continue;
                    }
                    int[] cell = roadGrid[gz * roadGridW + gx];
                    if (cell == null) {
                        continue;
                    }
                    for (int n = 0; n < cell.length; n++) {
                        int ri = cell[n] >>> 16;
                        int k = cell[n] & 0xFFFF;
                        Polyline p = roads.get(ri);
                        double d = Mth.distancePointSegment(x, z, p.x(k - 1), p.z(k - 1),
                                p.x(k), p.z(k));
                        if (d < best) {
                            best = d;
                            bestIdx = ri;
                        }
                    }
                }
            }
            if (bestIdx >= 0) {
                if (outIndex != null && outIndex.length > 0) {
                    outIndex[0] = bestIdx;
                }
                return best;
            }
            // Nothing in the 3x3 neighbourhood: the answer is at least a cell away.
            if (outIndex != null && outIndex.length > 0) {
                outIndex[0] = -1;
            }
            return ROAD_CELL;
        }
        for (int i = 0; i < roads.size(); i++) {
            Polyline p = roads.get(i);
            for (int k = 1; k < p.pointCount(); k++) {
                double d = Mth.distancePointSegment(x, z, p.x(k - 1), p.z(k - 1), p.x(k), p.z(k));
                if (d < best) {
                    best = d;
                    bestIdx = i;
                }
            }
        }
        if (outIndex != null && outIndex.length > 0) {
            outIndex[0] = bestIdx;
        }
        return best;
    }

    public Airport nearestAirport(double x, double z) {
        Airport best = null;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < airports.size(); i++) {
            Airport a = airports.get(i);
            double d = Math.hypot(a.x - x, a.z - z);
            if (d < bestD) {
                bestD = d;
                best = a;
            }
        }
        return best;
    }

    public Spot nearestSpot(List<Spot> list, double x, double z) {
        Spot best = null;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            Spot s = list.get(i);
            double d = Math.hypot(s.x - x, s.z - z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    public double worldWidthMetres() {
        return maxX - minX;
    }

    public double worldDepthMetres() {
        return maxZ - minZ;
    }
}
