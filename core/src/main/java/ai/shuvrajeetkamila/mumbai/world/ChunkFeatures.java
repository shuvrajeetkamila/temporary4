package ai.shuvrajeetkamila.mumbai.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The real OpenStreetMap features inside one 1024 m chunk, decoded from the
 * packaged payload.
 *
 * <p>Coordinates arrive as int16 multiples of 5 cm relative to the chunk
 * origin. That is a 1 mm-free but 5 cm-accurate representation, which is finer
 * than OSM's own positional accuracy in a dense city and costs four bytes a
 * vertex instead of sixteen. They are expanded here into chunk-local floats;
 * the world-frame double position is the chunk origin plus these, and only the
 * origin ever needs double precision.
 *
 * <p>Instances are produced on streaming worker threads and handed to the GL
 * thread, so every field is final and nothing here is mutated after
 * construction.
 */
public final class ChunkFeatures {

    /** Buildings. Each footprint is a closed ring, stored open. */
    public final int[] buildingClass;
    public final int[] buildingRoof;
    public final float[] buildingHeight;      // metres above its own base
    public final float[] buildingMinHeight;   // metres, for parts on podiums
    public final int[] buildingOffset;        // index into buildingXZ / 2
    public final float[] buildingXZ;          // chunk-local metres, x,z pairs

    /** Road, rail, aeroway and pier centrelines. */
    public final int[] roadClass;
    public final int[] roadLanes;
    public final int[] roadFlags;             // 1 bridge, 2 tunnel, 4 unpaved, 24 oneway
    public final int[] roadSpeed;             // km/h
    public final float[] roadWidth;           // metres, full carriageway
    public final int[] roadLayer;             // OSM layer, -6..6
    public final int[] roadName;              // index into WorldData.roadName, -1 none
    public final int[] roadOffset;
    public final float[] roadXZ;

    public final int id;
    public final double originX;
    public final double originZ;

    public static final int FLAG_BRIDGE = 1;
    public static final int FLAG_TUNNEL = 2;
    public static final int FLAG_UNPAVED = 4;

    private ChunkFeatures(int id, double ox, double oz,
                          int[] bc, int[] br, float[] bh, float[] bmh,
                          int[] boff, float[] bxz,
                          int[] rc, int[] rl, int[] rf, int[] rs, float[] rw,
                          int[] rlay, int[] rn, int[] roff, float[] rxz) {
        this.id = id;
        this.originX = ox;
        this.originZ = oz;
        buildingClass = bc; buildingRoof = br; buildingHeight = bh;
        buildingMinHeight = bmh; buildingOffset = boff; buildingXZ = bxz;
        roadClass = rc; roadLanes = rl; roadFlags = rf; roadSpeed = rs;
        roadWidth = rw; roadLayer = rlay; roadName = rn;
        roadOffset = roff; roadXZ = rxz;
    }

    public static ChunkFeatures empty(int id, double ox, double oz) {
        return new ChunkFeatures(id, ox, oz,
                new int[0], new int[0], new float[0], new float[0], new int[]{0},
                new float[0],
                new int[0], new int[0], new int[0], new int[0], new float[0],
                new int[0], new int[0], new int[]{0}, new float[0]);
    }

    public int buildingCount() { return buildingClass.length; }

    public int roadCount() { return roadClass.length; }

    public boolean isEmpty() { return buildingClass.length == 0 && roadClass.length == 0; }

    public boolean roadOneway(int i) {
        int o = (roadFlags[i] >>> 3) & 3;
        return o == 2 || o == 0;      // encoded as oneway+1: 0 -> -1, 2 -> +1
    }

    /** +1 forward, -1 reversed, 0 two-way. */
    public int roadOnewayDir(int i) {
        return ((roadFlags[i] >>> 3) & 3) - 1;
    }

    /**
     * Decode a payload produced by {@code tools/compile_world.py}.
     *
     * @param quant coordinate quantum in metres, from the chunk metadata
     */
    public static ChunkFeatures decode(int id, double ox, double oz,
                                       byte[] payload, double quant) {
        if (payload.length == 0) { return empty(id, ox, oz); }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        float q = (float) quant;

        int nb = bb.getInt();
        if (nb < 0 || nb > 2_000_000) {
            throw new IllegalStateException("chunk " + id + " claims " + nb + " buildings");
        }
        int[] bc = new int[nb];
        int[] br = new int[nb];
        float[] bh = new float[nb];
        float[] bmh = new float[nb];
        int[] boff = new int[nb + 1];
        // Two passes would mean parsing twice; grow instead.
        float[] bxz = new float[Math.max(16, nb * 10)];
        int bp = 0;
        for (int i = 0; i < nb; i++) {
            bc[i] = bb.get() & 0xFF;
            br[i] = bb.get() & 0xFF;
            bh[i] = (bb.getShort() & 0xFFFF) * 0.1f;
            bmh[i] = (bb.getShort() & 0xFFFF) * 0.1f;
            int n = bb.getShort() & 0xFFFF;
            boff[i] = bp >> 1;
            if (bp + n * 2 > bxz.length) {
                bxz = grow(bxz, bp + n * 2);
            }
            for (int k = 0; k < n; k++) {
                bxz[bp++] = bb.getShort() * q;
                bxz[bp++] = bb.getShort() * q;
            }
        }
        boff[nb] = bp >> 1;
        bxz = trim(bxz, bp);

        int nr = bb.getInt();
        if (nr < 0 || nr > 2_000_000) {
            throw new IllegalStateException("chunk " + id + " claims " + nr + " roads");
        }
        int[] rc = new int[nr];
        int[] rl = new int[nr];
        int[] rf = new int[nr];
        int[] rs = new int[nr];
        float[] rw = new float[nr];
        int[] rlay = new int[nr];
        int[] rn = new int[nr];
        int[] roff = new int[nr + 1];
        float[] rxz = new float[Math.max(16, nr * 8)];
        int rp = 0;
        for (int i = 0; i < nr; i++) {
            rc[i] = bb.get() & 0xFF;
            rl[i] = bb.get() & 0xFF;
            rf[i] = bb.get() & 0xFF;
            rs[i] = bb.get() & 0xFF;
            rw[i] = (bb.getShort() & 0xFFFF) * 0.1f;
            rlay[i] = bb.get();
            int n = bb.getShort() & 0xFFFF;
            int name = bb.getInt();
            rn[i] = name == 0xFFFFFFFF ? -1 : name;
            roff[i] = rp >> 1;
            if (rp + n * 2 > rxz.length) {
                rxz = grow(rxz, rp + n * 2);
            }
            for (int k = 0; k < n; k++) {
                rxz[rp++] = bb.getShort() * q;
                rxz[rp++] = bb.getShort() * q;
            }
        }
        roff[nr] = rp >> 1;
        rxz = trim(rxz, rp);

        return new ChunkFeatures(id, ox, oz, bc, br, bh, bmh, boff, bxz,
                rc, rl, rf, rs, rw, rlay, rn, roff, rxz);
    }

    private static float[] grow(float[] a, int need) {
        int n = Math.max(need, a.length * 2);
        float[] b = new float[n];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private static float[] trim(float[] a, int n) {
        if (a.length == n) { return a; }
        float[] b = new float[n];
        System.arraycopy(a, 0, b, 0, n);
        return b;
    }

    /** Approximate footprint area of building {@code i}, square metres. */
    public double buildingArea(int i) {
        int s = buildingOffset[i], e = buildingOffset[i + 1];
        int n = e - s;
        if (n < 3) { return 0; }
        double a = 0;
        for (int k = 0; k < n; k++) {
            int p = (s + k) * 2;
            int qi = (s + (k + 1) % n) * 2;
            a += buildingXZ[p] * buildingXZ[qi + 1] - buildingXZ[qi] * buildingXZ[p + 1];
        }
        return Math.abs(a) * 0.5;
    }

    /** Centroid of building {@code i} in chunk-local metres. */
    public void buildingCentroid(int i, float[] out2) {
        int s = buildingOffset[i], e = buildingOffset[i + 1];
        double sx = 0, sz = 0;
        int n = e - s;
        for (int k = s; k < e; k++) {
            sx += buildingXZ[k * 2];
            sz += buildingXZ[k * 2 + 1];
        }
        out2[0] = (float) (sx / Math.max(1, n));
        out2[1] = (float) (sz / Math.max(1, n));
    }

    /** Total centreline length of road {@code i}, metres. */
    public double roadLength(int i) {
        int s = roadOffset[i], e = roadOffset[i + 1];
        double len = 0;
        for (int k = s; k + 1 < e; k++) {
            double dx = roadXZ[(k + 1) * 2] - roadXZ[k * 2];
            double dz = roadXZ[(k + 1) * 2 + 1] - roadXZ[k * 2 + 1];
            len += Math.sqrt(dx * dx + dz * dz);
        }
        return len;
    }
}
