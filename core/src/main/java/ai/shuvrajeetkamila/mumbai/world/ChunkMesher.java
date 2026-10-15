package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.render.SeaMask;
import ai.shuvrajeetkamila.mumbai.render.TextureLibrary;

/**
 * Builds renderable triangles from the real OSM features in a chunk.
 *
 * <p>Meshes are built here, on streaming worker threads, rather than baked into
 * the APK. Baking triangles for a million and a half real building footprints
 * would multiply the download for no visual gain, and it would freeze the level
 * of detail at compile time. Building them on the fly costs a couple of
 * milliseconds a chunk and lets the same footprint produce a detailed mesh up
 * close and four triangles from across the harbour.
 *
 * <p>Vertices are chunk-local floats in a single interleaved array:
 *
 * <pre>  position.xyz | normal.xyz | albedo.rgb | aux </pre>
 *
 * <p>{@code aux} packs two things into one float, which is why the vertex
 * format did not have to grow to carry textures. Its integer part is the
 * material index into {@link TextureLibrary}; its fractional part is how
 * strongly the surface emits light after dark. A value of -1 still means
 * water and is tested for before either is unpacked. Encoding it this way
 * meant the compiled world, the container format and all 123 verification
 * gates were untouched by the entire texturing change.
 *
 * <p>The older meaning of {@code aux} was:
 * zero or more for emissive strength, and exactly -1 to mark a water surface,
 * which the fragment stage animates. Chunk-local floats keep the mesh precise:
 * at 45 km from the origin a float world coordinate quantises to about 4 mm,
 * which would make distant buildings visibly jitter as the camera moves.
 */
public final class ChunkMesher {

    /** Floats per vertex. */
    public static final int STRIDE = 11;

    /**
     * Side of the cell used to gather dropped footprints into one block of
     * urban mass, metres. Eight by eight of these cover a chunk.
     */
    private static final double MASS_CELL_M = 128.0;

    /** Height of the parapet wall added around a flat roof up close, metres. */
    private static final double PARAPET_M = 0.95;

    /** Level of detail. 0 is full, 2 is a distant silhouette. */
    public static final int LOD_HIGH = 0;
    public static final int LOD_MED = 1;
    public static final int LOD_LOW = 2;

    private final WorldData world;

    private float[] verts = new float[1 << 16];
    private int vCount;
    private int[] idx = new int[1 << 16];
    private int iCount;
    /** Chunk origin of the mesh being built, for world-space ground lookups. */
    private double lastOx, lastOz;

    public ChunkMesher(WorldData world) {
        this.world = world;
    }

    /** Result handed to the GL thread. */
    public static final class Mesh {
        public final float[] vertices;
        public final int[] indices;
        public final int vertexCount;
        public final int indexCount;
        public final double originX;
        public final double originZ;
        public final float minY;
        public final float maxY;

        Mesh(float[] v, int vc, int[] i, int ic, double ox, double oz,
             float minY, float maxY) {
            vertices = v; vertexCount = vc; indices = i; indexCount = ic;
            originX = ox; originZ = oz; this.minY = minY; this.maxY = maxY;
        }

        public int triangleCount() { return indexCount / 3; }

        public long byteSize() {
            return (long) vertexCount * STRIDE * 4L + (long) indexCount * 4L;
        }
    }

    // ------------------------------------------------------------------ build

    public Mesh build(ChunkFeatures f, int lod) {
        vCount = 0;
        iCount = 0;
        double ox = f.originX;
        double oz = f.originZ;
        double size = world.chunkM;
        lastOx = ox;
        lastOz = oz;

        terrain(ox, oz, size, lod);
        // Roads at every level of detail, not just the near two. A city
        // seen from a kilometre up with no streets in it reads as a field
        // of blocks; the arterials are what make it legible, and at long
        // range there are few enough of them to be nearly free.
        roads(f, ox, oz, lod);
        buildings(f, ox, oz, lod);

        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < vCount; i++) {
            float y = verts[i * STRIDE + 1];
            if (y < minY) { minY = y; }
            if (y > maxY) { maxY = y; }
        }
        if (vCount == 0) { minY = 0; maxY = 0; }

        float[] v = new float[vCount * STRIDE];
        System.arraycopy(verts, 0, v, 0, v.length);
        int[] ix = new int[iCount];
        System.arraycopy(idx, 0, ix, 0, iCount);
        return new Mesh(v, vCount, ix, iCount, ox, oz, minY, maxY);
    }

    // ---------------------------------------------------------------- terrain

    /**
     * A height-field patch for the chunk, coloured by the real land-cover
     * raster.
     *
     * <p>Cells that are sea at all four corners are left out entirely.
     * The animated sea surface draws them. Drawing both - a flat plane
     * at chart datum here, and the sea grid at tide plus swell - meant
     * two surfaces crossing each other all over the harbour, fighting
     * for the depth buffer along a join that crawled with the waves.
     */
    private void terrain(double ox, double oz, double size, int lod) {
        int n = lod == LOD_HIGH ? 32 : (lod == LOD_MED ? 16 : 8);
        double step = size / n;
        int base = vCount;
        float[] rgb = new float[3];
        boolean[] wet = new boolean[(n + 1) * (n + 1)];
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                double lx = i * step;
                double lz = j * step;
                double wx = ox + lx;
                double wz = oz + lz;
                boolean land = world.isLand(wx, wz);
                wet[j * (n + 1) + i] = !land;
                double h = land ? world.height(wx, wz) : 0.0;
                double[] nrm = new double[3];
                world.normal(wx, wz, nrm);
                int cv = world.cover(wx, wz);
                coverColour(cv, land, h, rgb);
                if (!land) {
                    nrm[0] = 0; nrm[1] = 1; nrm[2] = 0;
                }
                push((float) lx, (float) h, (float) lz,
                        (float) nrm[0], (float) nrm[1], (float) nrm[2],
                        rgb[0], rgb[1], rgb[2],
                        land ? (float) coverMaterial(cv) : -1f);
            }
        }
        int row = n + 1;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                // A cell with no land anywhere in it is not drawn. The
                // animated sea surface covers it, and until now both were
                // drawn: this flat plane at chart datum, and the sea grid
                // at the tide plus a metre and a half of swell. The two
                // cross each other constantly - the tide passes through
                // zero twice a cycle and the waves straddle it the whole
                // time - so over open water they interleaved in the depth
                // buffer and the join crawled with the waves. That is the
                // moving sheet that comes over the view after a while.
                int a = base + j * row + i;
                if (!seaOnly(ox + i * step, oz + j * step,
                             ox + (i + 1) * step, oz + (j + 1) * step)) {
                    triN(a, a + row, a + 1);
                    triN(a + 1, a + row, a + row + 1);
                }
            }
        }
        // The skirt must reach past the lowest ground any neighbour might
        // show along this edge, and a neighbour at full detail samples
        // the ground 16 times more finely than a distant chunk does. Using
        // this chunk's own coarse minimum would leave a low-detail chunk
        // with a skirt that stops above its detailed neighbour's valley
        // floor, and the seam opens again. So the bottom is found at full
        // detail whatever this chunk is being built at.
        skirt(base, n, step, lowestGround(ox, oz, size) - SKIRT_MARGIN_M);
    }

    /**
     * How far above the ground a road surface sits, metres.
     *
     * <p>Enough to clear the depth buffer at the distances roads are
     * drawn at. A 24-bit buffer with the near plane at
     * {@value #NEAR_PLANE_M} m resolves about
     * {@code d * d / (near * 2^24)} metres at distance d, so seven
     * centimetres - what this used to be - stops being enough at around
     * 700 m, and beyond that every road in view shimmers against the
     * ground it is lying on.
     */
    public static final float ROAD_LIFT_M = 0.14f;

    /**
     * How much a road is raised above the base lift for its class.
     *
     * <p>Spread over ten centimetres, which reads as a kerb lip rather
     * than a step, and quantised so two roads of different class are
     * always at least a centimetre apart - enough to hold the depth
     * test out to about 400 m, past which a junction is a few pixels.
     * Lower class number is a more important road, and sits higher.
     */
    static float junctionLift(int cls) {
        int band = cls < 0 ? 0 : (cls > 9 ? 9 : cls);
        return (9 - band) * 0.011f;
    }

    /**
     * The renderer's near plane, metres. Here because the road lift is
     * derived from it and the two have to be chosen together.
     */
    public static final double NEAR_PLANE_M = 0.70;

    /**
     * The height of the drawn terrain surface at a chunk-local point.
     *
     * <p>Not {@code world.height}: that is the ground in the data. This
     * is the ground in the mesh, which samples it on the chunk's own
     * grid and interpolates across the same two triangles the terrain
     * pass emits. Anything laid on the ground has to use this or it
     * will not be lying on what the player can see.
     */
    double lodGround(double ox, double oz, int lod, double lx, double lz) {
        int n = lod == LOD_HIGH ? 32 : (lod == LOD_MED ? 16 : 8);
        double step = world.chunkM / n;
        double gu = lx / step;
        double gv = lz / step;
        int i = (int) Math.floor(gu);
        int j = (int) Math.floor(gv);
        if (i < 0) { i = 0; } else if (i > n - 1) { i = n - 1; }
        if (j < 0) { j = 0; } else if (j > n - 1) { j = n - 1; }
        double u = gu - i;
        double v = gv - j;
        u = u < 0 ? 0 : (u > 1 ? 1 : u);
        v = v < 0 ? 0 : (v > 1 ? 1 : v);
        double h00 = nodeHeight(ox + i * step, oz + j * step);
        double h10 = nodeHeight(ox + (i + 1) * step, oz + j * step);
        double h01 = nodeHeight(ox + i * step, oz + (j + 1) * step);
        double h11 = nodeHeight(ox + (i + 1) * step, oz + (j + 1) * step);
        // The grid is split along the diagonal from (i, j+1) to (i+1, j),
        // which is the diagonal the terrain pass emits.
        if (u + v <= 1.0) {
            return h00 + u * (h10 - h00) + v * (h01 - h00);
        }
        return h11 + (1 - u) * (h01 - h11) + (1 - v) * (h10 - h11);
    }

    /** A terrain grid node, by the same rule the terrain pass uses. */
    private double nodeHeight(double wx, double wz) {
        return world.isLand(wx, wz) ? world.height(wx, wz) : 0.0;
    }

    /** The lowest ground in a chunk, sampled at full detail. */
    private double lowestGround(double ox, double oz, double size) {
        double lo = Double.MAX_VALUE;
        // At the finest the terrain is ever drawn. Sampling any coarser
        // leaves the skirt of a distant chunk stopping above the valley
        // floor its detailed neighbour shows, and the seam opens again -
        // TerrainSeamTest measures exactly that.
        int n = 32;
        double step = size / n;
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                double x = ox + i * step;
                double z = oz + j * step;
                double h = world.isLand(x, z) ? world.height(x, z) : 0.0;
                if (h < lo) { lo = h; }
            }
        }
        return lo == Double.MAX_VALUE ? 0 : lo;
    }

    /** How far below a chunk's lowest ground its skirt hangs, metres. */
    public static final double SKIRT_MARGIN_M = 2.0;

    /**
     * A curtain hung around the edge of a chunk.
     *
     * <p>The terrain is meshed at 32, 16 or 8 subdivisions depending on
     * how far away the chunk is, which means two neighbouring chunks at
     * different detail levels do not share vertices along the edge
     * between them. One follows the ground every 32 m and the other cuts
     * a straight line across 128 m, and the ground tears open between
     * them. Measured across this world the tear reaches <b>137 m</b> on
     * the steep ground at Malabar Hill and the Powai slopes, is 15 m at
     * the 99.9th percentile and a few metres almost everywhere else -
     * and through it you see whatever is behind the terrain, which is
     * the sea. That is one of the two ways this game drew water on dry
     * land.
     *
     * <p>Stitching the edges instead would mean each chunk knowing its
     * neighbours' detail levels at the moment it is meshed, which it
     * cannot: they are streamed independently and change constantly.
     * A skirt needs no such agreement. It hangs from every edge vertex
     * down past the lowest ground in the chunk, so whatever the
     * neighbour does with its edge, there is geometry behind the gap. It
     * costs four strips of quads and is invisible unless it is doing its
     * job.
     */
    private void skirt(int base, int n, double step, double bottom) {
        int row = n + 1;
        for (int side = 0; side < 4; side++) {
            for (int k = 0; k < n; k++) {
                // Two vertices along this edge of the grid, in order.
                int i0, j0, i1, j1;
                switch (side) {
                    case 0:  i0 = k;     j0 = 0; i1 = k + 1; j1 = 0;     break;
                    case 1:  i0 = n;     j0 = k; i1 = n;     j1 = k + 1; break;
                    case 2:  i0 = k + 1; j0 = n; i1 = k;     j1 = n;     break;
                    default: i0 = 0;     j0 = k + 1; i1 = 0; j1 = k;     break;
                }
                int va = base + j0 * row + i0;
                int vb = base + j1 * row + i1;
                int sa = vCount;
                copyDown(va, bottom);
                copyDown(vb, bottom);
                // Outward and horizontal, which is what a vertical wall
                // is. Giving it the ground's upward normal would leave
                // triN with nothing to decide the winding from.
                float nx = side == 1 ? 1 : (side == 3 ? -1 : 0);
                float nz = side == 2 ? 1 : (side == 0 ? -1 : 0);
                setNormal(sa, nx, 0, nz);
                setNormal(sa + 1, nx, 0, nz);
                int ta = vCount;
                copyOf(va, nx, nz);
                copyOf(vb, nx, nz);
                triN(ta, sa, ta + 1);
                triN(ta + 1, sa, sa + 1);
            }
        }
    }

    /**
     * Duplicates a vertex at a lower height, keeping its colour.
     *
     * <p>The texture coordinate is carried over from the vertex above
     * rather than taken from the new height. A skirt hangs below the
     * lowest ground in the chunk, which at the coast is below zero, and
     * the vertical texture coordinate is a height above a base: it must
     * never go negative, both because the shader would read the facade
     * atlas backwards and because it is the one channel that says where
     * the bottom of a wall is.
     */
    private void copyDown(int v, double y) {
        int o = v * STRIDE;
        push(verts[o], (float) y, verts[o + 2],
                verts[o + 3], verts[o + 4], verts[o + 5],
                verts[o + 6], verts[o + 7], verts[o + 8], verts[o + 9],
                verts[o + 10]);
    }

    /** Duplicates a vertex with a new normal, so the skirt shades flat. */
    private void copyOf(int v, float nx, float nz) {
        int o = v * STRIDE;
        push(verts[o], verts[o + 1], verts[o + 2], nx, 0, nz,
                verts[o + 6], verts[o + 7], verts[o + 8], verts[o + 9],
                verts[o + 10]);
    }

    private void setNormal(int v, float nx, float ny, float nz) {
        int o = v * STRIDE;
        verts[o + 3] = nx;
        verts[o + 4] = ny;
        verts[o + 5] = nz;
    }

    /**
     * Land-cover palette. Deliberately muted and slightly desaturated: Mumbai
     * read through a haze of sea air and dust, not a saturated map legend.
     */
    static void coverColour(int cover, boolean land, double h, float[] out) {
        switch (cover) {
            case 0:  set(out, 0.09f, 0.19f, 0.26f); return;   // water
            case 1:  set(out, 0.27f, 0.31f, 0.24f); return;   // wetland
            case 2:  set(out, 0.19f, 0.28f, 0.20f); return;   // mangrove
            case 3:  set(out, 0.76f, 0.70f, 0.56f); return;   // beach
            case 4:  set(out, 0.74f, 0.68f, 0.55f); return;   // sand
            case 5:  set(out, 0.44f, 0.42f, 0.39f); return;   // rock
            case 6:  set(out, 0.17f, 0.30f, 0.17f); return;   // forest
            case 7:  set(out, 0.33f, 0.38f, 0.25f); return;   // scrub
            case 8:  set(out, 0.36f, 0.45f, 0.26f); return;   // grass
            case 9:  set(out, 0.28f, 0.42f, 0.24f); return;   // park
            case 10: set(out, 0.46f, 0.44f, 0.28f); return;   // farmland
            case 11: set(out, 0.34f, 0.39f, 0.31f); return;   // cemetery
            case 12: set(out, 0.42f, 0.39f, 0.36f); return;   // residential
            case 13: set(out, 0.40f, 0.39f, 0.40f); return;   // commercial
            case 14: set(out, 0.38f, 0.36f, 0.34f); return;   // industrial
            case 15: set(out, 0.43f, 0.40f, 0.38f); return;   // retail
            case 16: set(out, 0.36f, 0.37f, 0.32f); return;   // military
            case 17: set(out, 0.33f, 0.32f, 0.31f); return;   // railway land
            case 18: set(out, 0.35f, 0.35f, 0.36f); return;   // airport land
            case 19: set(out, 0.45f, 0.42f, 0.37f); return;   // construction
            case 20: set(out, 0.48f, 0.45f, 0.41f); return;   // quarry
            case 21: set(out, 0.30f, 0.40f, 0.27f); return;   // pitch
            case 22: set(out, 0.34f, 0.34f, 0.35f); return;   // parking
            case 23: set(out, 0.37f, 0.36f, 0.35f); return;   // port
            default:
                float t = (float) Math.min(1.0, Math.max(0.0, h / 400.0));
                set(out, 0.34f + 0.10f * t, 0.36f + 0.04f * t, 0.29f + 0.06f * t);
        }
    }

    private static void set(float[] o, float r, float g, float b) {
        o[0] = r; o[1] = g; o[2] = b;
    }

    // ------------------------------------------------------------------ roads

    /**
     * Each centreline becomes a ribbon of its real OSM width, lifted a few
     * centimetres so it beats the terrain in the depth test, and lifted
     * properly where OSM marks a bridge.
     */
    /**
     * Half-width to use at a bend so the ribbon stays the width of the
     * road.
     *
     * <p>The ribbon edge is offset perpendicular to the average of the
     * two segments meeting at the vertex, which is the right direction
     * and the wrong length. The road only keeps its width if that offset
     * is divided by the cosine of half the turn. Without it the outside
     * of every corner pinches in by that factor - a quarter of the width
     * at a 60 degree bend - and leaves a row of bites out of the kerb
     * all the way round a flyover.
     *
     * @param half    the road's true half-width, m
     * @param ax, az  the segment arriving at the vertex
     * @param bx, bz  the segment leaving it
     */
    static float mitre(float half, double ax, double az,
                       double bx, double bz) {
        double al = Math.sqrt(ax * ax + az * az);
        double bl = Math.sqrt(bx * bx + bz * bz);
        if (al < 1e-5 || bl < 1e-5) { return half; }
        double dot = (ax * bx + az * bz) / (al * bl);
        double cosHalf = Math.sqrt(Math.max(0.0, (1.0 + dot) * 0.5));
        // Capped at about 2.6, or a hairpin throws a spike kilometres
        // long out of a single vertex.
        return (float) (half / Math.max(0.38, cosHalf));
    }

    /**
     * Lateral coordinate written to unmarked road vertices.
     *
     * <p>The fragment stage paints a centre line wherever a tarmac
     * surface reports a lateral coordinate near zero. A road that
     * should not be marked - a lane, a track, a car park - is pushed
     * out past that test by this offset instead of needing a flag of
     * its own, which there is no room for in the vertex.
     */
    static final float UNMARKED_V = 64.0f;

    /** Half-width below which a road gets no centre line, m. */
    static final float MARKING_MIN_HALF_M = 3.0f;

    /** Whether a road of this width and surface is painted. */
    static boolean isMarked(float half, boolean unpaved, int lod) {
        return lod == LOD_HIGH && !unpaved && half >= MARKING_MIN_HALF_M;
    }

    /**
     * Whether a terrain cell may be left to the sea.
     *
     * <p>This has to be at least as cautious as the land mask, or there
     * is a hole between them. The mask is built at 40 m and a texel
     * counts as land if any 20 m cell inside it is land; the sea is
     * discarded wherever the mask says land. The mesh used to drop a
     * cell whose own corners were all sea, sampled every 32 m at full
     * detail and every 128 m at the lowest - so along a ragged coast,
     * and over every islet and sandbar and jetty narrower than a cell,
     * the sea was discarded and the terrain was never emitted. Nothing
     * was drawn there at all, and a hole is whatever colour the frame
     * was cleared to, which is the fog.
     *
     * <p>So the question asked here is the mask's question, over the
     * cell grown by a mask texel on every side - because a texel that
     * straddles the cell boundary takes its answer from land that may
     * lie outside the cell entirely.
     */
    private boolean seaOnly(double x0, double z0, double x1, double z1) {
        double m = SeaMask.TEXEL_M;
        return !world.anyLand(x0 - m, z0 - m, x1 + m, z1 + m);
    }

    /** How far each end of a ribbon runs past its last centreline point, m. */
    static float endExtension(float half) {
        return Math.min(half, 2.4f);
    }

    private void roads(ChunkFeatures f, double ox, double oz, int lod) {
        for (int r = 0; r < f.roadCount(); r++) {
            int cls = f.roadClass[r];
            if (lod == LOD_MED && cls > 4 && cls != 19 && cls != 20) {
                continue;                       // keep arterials and runways only
            }
            if (lod == LOD_LOW && cls > 2 && cls != 19) {
                continue;                       // motorway, trunk, primary, runway
            }
            if (cls == 23) { continue; }        // ferry route: not a surface
            int s = f.roadOffset[r];
            int e = f.roadOffset[r + 1];
            if (e - s < 2) { continue; }
            float half = Math.max(1.2f, f.roadWidth[r] * 0.5f);
            boolean bridge = (f.roadFlags[r] & ChunkFeatures.FLAG_BRIDGE) != 0;
            boolean tunnel = (f.roadFlags[r] & ChunkFeatures.FLAG_TUNNEL) != 0;
            if (tunnel) { continue; }
            // Roads cross. Two ribbons laid at the same height over the
            // same ground are coplanar, and a crossroads is then a patch
            // of two surfaces fighting for the depth buffer - which in a
            // city of 105,000 ways is a flickering speckle at every
            // junction in view. They are separated by class, which is
            // also the right answer physically: where a trunk road and a
            // lane meet, the trunk road is the one that carries through.
            float lift = bridge ? 0.0f : ROAD_LIFT_M + junctionLift(cls);
            float[] rgb = new float[3];
            boolean unpavedRoad = (f.roadFlags[r] & ChunkFeatures.FLAG_UNPAVED) != 0;
            roadColour(cls, unpavedRoad, rgb);
            float roadMat = unpavedRoad ? TextureLibrary.MAT_DIRT : TextureLibrary.MAT_TARMAC;

            // Bridge decks follow a straight line between their ends rather
            // than the seabed underneath, which is what makes the Sea Link and
            // the harbour jetties behave like structures and not like trenches.
            double y0 = groundAt(ox, oz, f.roadXZ, s);
            double y1 = groundAt(ox, oz, f.roadXZ, e - 1);
            double total = f.roadLength(r);

            int prevL = -1, prevR = -1, prevC = -1;
            boolean marked = isMarked(half, unpavedRoad, lod);
            double travelled = 0;
            for (int k = s; k < e; k++) {
                float lx = f.roadXZ[k * 2];
                float lz = f.roadXZ[k * 2 + 1];
                float dx, dz;
                if (k == s) {
                    dx = f.roadXZ[(k + 1) * 2] - lx;
                    dz = f.roadXZ[(k + 1) * 2 + 1] - lz;
                } else if (k == e - 1) {
                    dx = lx - f.roadXZ[(k - 1) * 2];
                    dz = lz - f.roadXZ[(k - 1) * 2 + 1];
                } else {
                    dx = f.roadXZ[(k + 1) * 2] - f.roadXZ[(k - 1) * 2];
                    dz = f.roadXZ[(k + 1) * 2 + 1] - f.roadXZ[(k - 1) * 2 + 1];
                }
                double dl = Math.sqrt(dx * dx + dz * dz);
                if (dl < 1e-5) { continue; }
                float nx = (float) (dz / dl);
                float nz = (float) (-dx / dl);

                // Mitre the corner. The offset above is perpendicular to
                // the average of the two segments meeting here, which is
                // the right direction and the wrong length: on a bend the
                // ribbon has to be wider than the road to stay the width
                // of the road, by one over the cosine of half the turn.
                // Without it the outside of every corner pinches in and
                // leaves a notch, which on a sweeping flyover or a
                // roundabout approach is a row of bites out of the kerb.
                float hw = half;
                if (k > s && k < e - 1) {
                    hw = mitre(half,
                            lx - f.roadXZ[(k - 1) * 2],
                            lz - f.roadXZ[(k - 1) * 2 + 1],
                            f.roadXZ[(k + 1) * 2] - lx,
                            f.roadXZ[(k + 1) * 2 + 1] - lz);
                }

                if (k > s) {
                    double sx = lx - f.roadXZ[(k - 1) * 2];
                    double sz = lz - f.roadXZ[(k - 1) * 2 + 1];
                    travelled += Math.sqrt(sx * sx + sz * sz);
                }
                // Run the ribbon a little past each end.
                //
                // OSM splits a street into a way per attribute change and
                // the compiler splits again at every chunk border, so a
                // single road is a chain of ribbons that stop dead at a
                // shared node. Where four of them meet, the four square
                // ends leave an unpaved notch in the middle of the
                // crossroads, and at a chunk border there is a hairline
                // of bare ground straight across the carriageway. Pushing
                // each end out by about half a lane makes them overlap
                // instead of abut; the class lift above keeps the overlap
                // from fighting in the depth buffer. A cul-de-sac gets a
                // two metre tongue of tarmac, which nobody will see.
                float px = lx, pz = lz;
                if (k == s || k == e - 1) {
                    float ext = endExtension(half);
                    float sgn = k == s ? -1f : 1f;
                    px += (float) (dx / dl) * ext * sgn;
                    pz += (float) (dz / dl) * ext * sgn;
                }

                double y;
                if (bridge && total > 1) {
                    // The drawn ground again, not the true one: a deck
                    // that clears the real hillside by a metre can still
                    // be buried in the coarse mesh a distant chunk draws.
                    double ground = lodGround(ox, oz, lod, px, pz);
                    y = RoadDeck.height(y0, y1, Math.min(1, travelled / total),
                            ground);
                } else {
                    // On the terrain that is actually drawn, not the
                    // terrain in the data. The mesh samples the ground
                    // every 32, 64 or 128 m depending on how far away the
                    // chunk is and interpolates between those samples,
                    // so on any slope the drawn surface is metres away
                    // from the true height. A road laid at the true
                    // height disappears into the hillside in patches and
                    // hangs in the air in others, and the edge of the
                    // ribbon where it crosses the ground tears into
                    // exactly the sort of line that gets reported as
                    // texture on the land.
                    y = lodGround(ox, oz, lod, px, pz) + lift;
                }
                // Each edge of the ribbon takes the ground under itself.
                // Using the centreline height for both, as this did,
                // leaves a wide road on a cross slope with one edge
                // buried in the hillside and the other hanging over it -
                // measured at 3.8 m on the Ghatkopar and Powai slopes.
                double yl = y;
                double yr = y;
                if (!(bridge && total > 1)) {
                    yl = lodGround(ox, oz, lod, px + nx * hw, pz + nz * hw)
                            + lift;
                    yr = lodGround(ox, oz, lod, px - nx * hw, pz - nz * hw)
                            + lift;
                }
                // The lateral coordinate, in metres from the centreline.
                // The fragment stage paints the centre line from it; an
                // unpainted road is pushed past the test rather than
                // carrying a flag the vertex has no room for.
                float vBase = marked ? 0f : UNMARKED_V;
                int li = push(px + nx * hw, (float) yl, pz + nz * hw,
                        0, 1, 0, rgb[0], rgb[1], rgb[2], roadMat, vBase + hw);
                int ri = push(px - nx * hw, (float) yr, pz - nz * hw,
                        0, 1, 0, rgb[0], rgb[1], rgb[2], roadMat, vBase + hw);
                if (!marked) {
                    if (prevL >= 0) {
                        triN(prevL, li, prevR);
                        triN(prevR, li, ri);
                    }
                    prevL = li;
                    prevR = ri;
                    continue;
                }
                // A marked road carries a third vertex down the middle,
                // because the paint has to be interpolated towards
                // something. Two vertices both a half-width from the
                // centre interpolate to a half-width everywhere in
                // between, and the line never appears. The extra station
                // also samples the ground once more across the
                // carriageway, so a road on a cross slope sits on it a
                // little better than two samples could manage.
                int ci = push(px, (float) y, pz,
                        0, 1, 0, rgb[0], rgb[1], rgb[2], roadMat, 0f);
                if (prevL >= 0) {
                    triN(prevL, li, prevC);
                    triN(prevC, li, ci);
                    triN(prevC, ci, prevR);
                    triN(prevR, ci, ri);
                }
                prevL = li;
                prevR = ri;
                prevC = ci;
            }
        }
    }

    private double groundAt(double ox, double oz, float[] xz, int i) {
        double wx = ox + xz[i * 2];
        double wz = oz + xz[i * 2 + 1];
        return world.isLand(wx, wz) ? world.height(wx, wz) : 0.0;
    }

    private static void roadColour(int cls, boolean unpaved, float[] out) {
        if (unpaved) { set(out, 0.42f, 0.37f, 0.30f); return; }
        switch (cls) {
            case 0: case 1: set(out, 0.22f, 0.22f, 0.23f); return;      // motorway
            case 2: case 3: set(out, 0.24f, 0.24f, 0.25f); return;      // primary
            case 4: case 5: case 6: set(out, 0.26f, 0.26f, 0.27f); return;
            case 7: case 8: set(out, 0.28f, 0.28f, 0.28f); return;      // service
            case 9: case 10: case 11: case 12: case 13:
                set(out, 0.40f, 0.38f, 0.35f); return;                  // foot
            case 15: case 16: case 17: case 18:
                set(out, 0.30f, 0.27f, 0.24f); return;                  // rail
            case 19: set(out, 0.19f, 0.19f, 0.21f); return;             // runway
            case 20: set(out, 0.23f, 0.23f, 0.24f); return;             // taxiway
            case 22: set(out, 0.36f, 0.32f, 0.27f); return;             // pier
            default: set(out, 0.27f, 0.27f, 0.28f);
        }
    }

    // -------------------------------------------------------------- buildings

    /**
     * Extrude each real footprint from the ground to its height. Up close the
     * walls are split into floors so the facade has some vertical structure;
     * far away a building is a prism, and below the medium threshold small
     * buildings are dropped entirely rather than drawn as sub-pixel confetti.
     */
    private void buildings(ChunkFeatures f, double ox, double oz, int lod) {
        double minArea = lod == LOD_HIGH ? 0 : (lod == LOD_MED ? 220 : 2600);
        double minHeight = lod == LOD_HIGH ? 0 : (lod == LOD_MED ? 9 : 34);
        double cells = Math.max(1, Math.ceil(world.chunkM / MASS_CELL_M));
        int mn = (int) cells;
        BlockMass mass = lod == LOD_HIGH ? null : new BlockMass(mn, world.chunkM / mn);
        float[] rgb = new float[3];
        for (int b = 0; b < f.buildingCount(); b++) {
            float h = f.buildingHeight[b];
            if (h < minHeight || (minArea > 0 && f.buildingArea(b) < minArea)) {
                // Too small to draw as itself at this range, but a suburb of
                // chawls is not empty ground. Fold it into the cell's block
                // mass and re-emit the lot as one box further down.
                if (mass != null) { mass.add(f, b, h); }
                continue;
            }
            int s = f.buildingOffset[b];
            int e = f.buildingOffset[b + 1];
            int n = e - s;
            if (n < 3) { continue; }
            int bcls = f.buildingClass[b];
            float wallMat = facadeMaterial(bcls, h, b);
            float roofMat = roofMaterial(bcls, h);

            // Base at the lowest ground under the ring, so a building on a
            // slope does not float at one corner and sink at the other.
            double base = Double.MAX_VALUE;
            for (int k = s; k < e; k++) {
                double wx = ox + f.buildingXZ[k * 2];
                double wz = oz + f.buildingXZ[k * 2 + 1];
                double g = world.isLand(wx, wz) ? world.height(wx, wz) : 0.0;
                if (g < base) { base = g; }
            }
            double y0 = base + f.buildingMinHeight[b] - 0.4;
            double y1 = base + f.buildingMinHeight[b] + h;

            buildingColour(f.buildingClass[b], b, h, rgb);

            // Ground floors in this city are shops, and after dark they are
            // the brightest thing at street level. Give the bottom storey its
            // own material instead of running the residential tile down to
            // the pavement.
            boolean pitched = f.buildingRoof[b] != 0;
            double shopTop = y0;
            boolean shops = lod == LOD_HIGH && h >= 6.0 && shopfront(bcls);
            if (shops) { shopTop = Math.min(y0 + 4.2, y1 - 2.0); }
            int floors = 1;
            if (lod == LOD_HIGH) {
                floors = Math.max(1, Math.min(14, (int) ((y1 - shopTop) / 9.0)));
            }
            // A parapet only makes sense on a flat roof, and only where the
            // player is close enough to see the lip at all.
            boolean parapet = lod == LOD_HIGH && !pitched && h > 6.5
                    && f.buildingArea(b) > 90;
            double capY = y1 + (parapet ? PARAPET_M : 0.0);

            // Which way round the ring goes. OSM gives both, and an
            // outward normal is right-of-travel on one and left on the
            // other - so a third of the city was lit, and after culling
            // drawn, inside out. Shoelace once per building, not per edge.
            double area2 = 0;
            for (int k = 0; k < n; k++) {
                int i0 = s + k;
                int i1 = s + (k + 1) % n;
                area2 += (double) f.buildingXZ[i0 * 2] * f.buildingXZ[i1 * 2 + 1]
                        - (double) f.buildingXZ[i1 * 2] * f.buildingXZ[i0 * 2 + 1];
            }
            double wind = area2 >= 0 ? 1.0 : -1.0;

            // Walls.
            for (int k = 0; k < n; k++) {
                int i0 = s + k;
                int i1 = s + (k + 1) % n;
                float ax = f.buildingXZ[i0 * 2], az = f.buildingXZ[i0 * 2 + 1];
                float bx = f.buildingXZ[i1 * 2], bz = f.buildingXZ[i1 * 2 + 1];
                double ex = bx - ax, ez = bz - az;
                double el = Math.sqrt(ex * ex + ez * ez);
                if (el < 0.25) { continue; }
                float nx = (float) (wind * ez / el);
                float nz = (float) (-wind * ex / el);
                // Faces catch the light differently; a flat albedo makes a city
                // of boxes look like a single grey mass. Four distinguishable
                // steps rather than two, so a corner reads as a corner.
                float shade = 0.82f + 0.11f * Math.abs(nx) + (nz > 0 ? 0.07f : 0f);
                if (shops) {
                    float cr = rgb[0] * shade * 0.94f;
                    float cg = rgb[1] * shade * 0.92f;
                    float cb = rgb[2] * shade * 0.88f;
                    quad(ax, az, bx, bz, y0, shopTop, nx, nz, cr, cg, cb,
                            TextureLibrary.MAT_FACADE_SHOP + 0.62f, y0);
                }
                for (int ff = 0; ff < floors; ff++) {
                    double fy0 = shopTop + (y1 - shopTop) * ff / (double) floors;
                    double fy1 = shopTop + (y1 - shopTop) * (ff + 1) / (double) floors;
                    float cr = rgb[0] * shade;
                    float cg = rgb[1] * shade;
                    float cb = rgb[2] * shade;
                    // Material in the integer part, night emission in the
                    // fraction. The emission is masked by the texture's alpha
                    // in the shader, so only the windows light up and not the
                    // whole slab - which is what a per-face constant does.
                    float aux = wallMat;
                    if (lod == LOD_HIGH) { aux += 0.34f; }
                    quad(ax, az, bx, bz, fy0, fy1, nx, nz, cr, cg, cb, aux, y0);
                }
                if (parapet) {
                    float cr = rgb[0] * shade * 0.80f;
                    float cg = rgb[1] * shade * 0.80f;
                    float cb = rgb[2] * shade * 0.78f;
                    quad(ax, az, bx, bz, y1, capY, nx, nz, cr, cg, cb,
                            (float) TextureLibrary.MAT_CONCRETE, y0);
                }
            }

            // Roof, as a fan from the centroid. Footprints from OSM are simple
            // polygons but not always convex, so a fan can fold; at this size
            // on a roof nobody can see it, and a full ear-clip would cost more
            // than it returns across a million buildings.
            double cx = 0, cz = 0;
            for (int k = s; k < e; k++) {
                cx += f.buildingXZ[k * 2];
                cz += f.buildingXZ[k * 2 + 1];
            }
            cx /= n;
            cz /= n;
            float rr = rgb[0] * 0.72f, rg = rgb[1] * 0.74f, rb = rgb[2] * 0.70f;
            double roofY = y1 + (pitched ? 1.6 : 0.0);
            int c = push((float) cx, (float) roofY, (float) cz, 0, 1, 0, rr, rg, rb, roofMat);
            int first = -1, prev = -1;
            for (int k = 0; k < n; k++) {
                int ik = s + k;
                int v = push(f.buildingXZ[ik * 2], (float) y1, f.buildingXZ[ik * 2 + 1],
                        0, 1, 0, rr, rg, rb, roofMat);
                if (first < 0) { first = v; }
                if (prev >= 0) { triN(c, prev, v); }
                prev = v;
            }
            if (first >= 0 && prev >= 0) { triN(c, prev, first); }

            if (parapet) { roofClutter(f, b, cx, cz, y1, h, roofMat); }
        }
        if (mass != null) { mass.emit(this); }
    }

    /** One wall panel, with the vertical texture coordinate taken from base. */
    private void quad(float ax, float az, float bx, float bz,
                      double y0, double y1, float nx, float nz,
                      float r, float g, float b, float aux, double base) {
        float v0h = (float) (y0 - base);
        float v1h = (float) (y1 - base);
        int v0 = push(ax, (float) y0, az, nx, 0, nz, r, g, b, aux, v0h);
        int v1 = push(bx, (float) y0, bz, nx, 0, nz, r, g, b, aux, v0h);
        int v2 = push(bx, (float) y1, bz, nx, 0, nz, r, g, b, aux, v1h);
        int v3 = push(ax, (float) y1, az, nx, 0, nz, r, g, b, aux, v1h);
        triN(v0, v1, v2);
        triN(v0, v2, v3);
    }

    /**
     * What stands on a Mumbai roof: the stair and lift head, and the black
     * plastic water tanks that every building carries. Two small boxes, and
     * only up close, but they are what stops a skyline being a row of cut
     * blocks.
     */
    private void roofClutter(ChunkFeatures f, int b, double cx, double cz,
                             double y1, float h, float roofMat) {
        int s = f.buildingOffset[b];
        int e = f.buildingOffset[b + 1];
        float lo0 = Float.MAX_VALUE, hi0 = -Float.MAX_VALUE;
        float lo1 = Float.MAX_VALUE, hi1 = -Float.MAX_VALUE;
        for (int k = s; k < e; k++) {
            float px = f.buildingXZ[k * 2], pz = f.buildingXZ[k * 2 + 1];
            if (px < lo0) { lo0 = px; }
            if (px > hi0) { hi0 = px; }
            if (pz < lo1) { lo1 = pz; }
            if (pz > hi1) { hi1 = pz; }
        }
        double halfX = (hi0 - lo0) * 0.5, halfZ = (hi1 - lo1) * 0.5;
        if (halfX < 3.0 || halfZ < 3.0) { return; }
        // Deterministic per building, so a roof does not reshuffle its tanks
        // every time the chunk is re-meshed at a different level of detail.
        int r = (int) ((b * 2654435761L) >>> 13) & 1023;
        double sx = Math.min(3.2, halfX * 0.42);
        double sz = Math.min(2.8, halfZ * 0.42);
        double ox = (r % 7 - 3) / 3.0 * (halfX - sx) * 0.55;
        double oz = ((r / 7) % 7 - 3) / 3.0 * (halfZ - sz) * 0.55;
        // Stair and lift head.
        prism(cx + ox, cz + oz, sx, sz, y1, y1 + 2.9, 0.58f, 0.57f, 0.54f, roofMat);
        if (h > 14.0) {
            double tx = cx - ox * 0.8, tz = cz - oz * 0.8;
            double t = Math.min(1.25, Math.min(halfX, halfZ) * 0.22);
            prism(tx, tz, t, t, y1 + 0.5, y1 + 2.3, 0.10f, 0.10f, 0.11f,
                    (float) TextureLibrary.MAT_CONCRETE);
        }
    }

    /** An axis-aligned box: four walls and a lid. */
    private void prism(double cx, double cz, double hx, double hz,
                       double y0, double y1,
                       float r, float g, float b, float mat) {
        float x0 = (float) (cx - hx), x1 = (float) (cx + hx);
        float z0 = (float) (cz - hz), z1 = (float) (cz + hz);
        quad(x0, z0, x1, z0, y0, y1, 0, -1, r, g, b, mat, y0);
        quad(x1, z0, x1, z1, y0, y1, 1, 0, r * 0.96f, g * 0.96f, b * 0.96f, mat, y0);
        quad(x1, z1, x0, z1, y0, y1, 0, 1, r * 0.92f, g * 0.92f, b * 0.92f, mat, y0);
        quad(x0, z1, x0, z0, y0, y1, -1, 0, r * 0.96f, g * 0.96f, b * 0.96f, mat, y0);
        int a = push(x0, (float) y1, z0, 0, 1, 0, r, g, b, mat);
        int c = push(x1, (float) y1, z0, 0, 1, 0, r, g, b, mat);
        int d = push(x1, (float) y1, z1, 0, 1, 0, r, g, b, mat);
        int ee = push(x0, (float) y1, z1, 0, 1, 0, r, g, b, mat);
        triN(a, c, d);
        triN(a, d, ee);
    }

    /** Which building classes have a shop on the ground floor. */
    private static boolean shopfront(int cls) {
        switch (cls) {
            case 14: case 15: case 16:        // hut, shed, garage
            case 11: case 12:                 // hospital, religious
            case 7: case 8:                   // industrial, warehouse
                return false;
            default:
                return true;
        }
    }

    /**
     * Footprints too small to draw individually at range, gathered per cell
     * and re-emitted as one box of the same plan area and the same
     * area-weighted height.
     *
     * <p>The alternative - what this replaced - was to drop them, which meant
     * that from a kilometre away Dharavi, Govandi and most of the suburbs
     * were bare ground with a few towers standing in it. Folding them into a
     * block costs ten triangles a cell instead of ten thousand and keeps the
     * mass, the height and the colour of the real low-rise city.
     */
    private static final class BlockMass {
        private final int n;
        private final double cell;
        private final double[] area, areaH, sx, sz;
        private final float[] cr, cg, cb;

        BlockMass(int n, double cell) {
            this.n = n;
            this.cell = cell;
            int c = n * n;
            area = new double[c];
            areaH = new double[c];
            sx = new double[c];
            sz = new double[c];
            cr = new float[c];
            cg = new float[c];
            cb = new float[c];
        }

        void add(ChunkFeatures f, int b, float h) {
            double a = f.buildingArea(b);
            if (a <= 0) { return; }
            int s = f.buildingOffset[b];
            int e = f.buildingOffset[b + 1];
            if (e - s < 3) { return; }
            double cx = 0, cz = 0;
            for (int k = s; k < e; k++) {
                cx += f.buildingXZ[k * 2];
                cz += f.buildingXZ[k * 2 + 1];
            }
            cx /= (e - s);
            cz /= (e - s);
            int i = (int) (cx / cell);
            int j = (int) (cz / cell);
            if (i < 0 || j < 0 || i >= n || j >= n) { return; }
            int c = j * n + i;
            float[] rgb = new float[3];
            buildingColour(f.buildingClass[b], b, h, rgb);
            area[c] += a;
            areaH[c] += a * h;
            sx[c] += a * cx;
            sz[c] += a * cz;
            cr[c] += (float) (a * rgb[0]);
            cg[c] += (float) (a * rgb[1]);
            cb[c] += (float) (a * rgb[2]);
        }

        void emit(ChunkMesher m) {
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int c = j * n + i;
                    double a = area[c];
                    // Below this the cell holds a shed or two and a box there
                    // would be worse than nothing.
                    if (a < 240) { continue; }
                    double h = areaH[c] / a;
                    double cx = sx[c] / a, cz = sz[c] / a;
                    // Same plan area as the footprints it stands for, but
                    // never wider than the cell that gathered them.
                    double half = Math.min(Math.sqrt(a) * 0.5, cell * 0.44);
                    cx = clamp(cx, i * cell + half, (i + 1) * cell - half);
                    cz = clamp(cz, j * cell + half, (j + 1) * cell - half);
                    double base = Double.MAX_VALUE;
                    for (int k = 0; k < 4; k++) {
                        double px = cx + ((k & 1) == 0 ? -half : half);
                        double pz = cz + ((k & 2) == 0 ? -half : half);
                        double wx = m.lastOx + px, wz = m.lastOz + pz;
                        double g = m.world.isLand(wx, wz) ? m.world.height(wx, wz) : 0.0;
                        if (g < base) { base = g; }
                    }
                    float rr = (float) (cr[c] / a), rg = (float) (cg[c] / a),
                            rb = (float) (cb[c] / a);
                    m.prism(cx, cz, half, half, base - 0.4, base + h,
                            rr, rg, rb, (float) TextureLibrary.MAT_FACADE_CHAWL);
                }
            }
        }

        private static double clamp(double v, double lo, double hi) {
            return hi < lo ? (lo + hi) * 0.5 : (v < lo ? lo : (v > hi ? hi : v));
        }
    }

    /**
     * Building colour by OSM class, with a stable per-building jitter so a
     * terrace of identical house tags does not render as one solid slab.
     */
    static void buildingColour(int cls, int seed, float h, float[] out) {
        switch (cls) {
            case 2: case 1: set(out, 0.62f, 0.58f, 0.50f); break;   // apartments
            case 3: set(out, 0.66f, 0.60f, 0.52f); break;           // house
            case 4: case 5: set(out, 0.56f, 0.58f, 0.60f); break;   // commercial
            case 6: set(out, 0.60f, 0.56f, 0.52f); break;           // retail
            case 7: case 8: set(out, 0.52f, 0.52f, 0.50f); break;   // industrial
            case 9: case 10: set(out, 0.68f, 0.64f, 0.56f); break;  // civic, school
            case 11: set(out, 0.72f, 0.70f, 0.64f); break;          // hospital
            case 12: set(out, 0.70f, 0.64f, 0.52f); break;          // religious
            case 13: set(out, 0.58f, 0.58f, 0.58f); break;          // transport
            case 14: case 15: set(out, 0.55f, 0.49f, 0.41f); break; // hut, shed
            case 16: set(out, 0.50f, 0.48f, 0.45f); break;          // garage
            case 17: set(out, 0.60f, 0.58f, 0.52f); break;          // construction
            case 18: set(out, 0.48f, 0.46f, 0.42f); break;          // ruins
            case 19: set(out, 0.64f, 0.64f, 0.62f); break;          // stadium
            case 20: set(out, 0.63f, 0.59f, 0.54f); break;          // hotel
            case 21: set(out, 0.57f, 0.59f, 0.62f); break;          // tower
            default: set(out, 0.60f, 0.57f, 0.51f);
        }
        int hsh = seed * 2654435761L % 1000 < 0 ? 0 : (int) ((seed * 2654435761L) % 1000);
        float j = 0.90f + 0.20f * (hsh / 1000.0f);
        // Taller blocks in Mumbai are mostly newer and lighter rendered glass.
        float g = h > 60 ? 1.06f : 1.0f;
        out[0] = Math.min(1f, out[0] * j * g);
        out[1] = Math.min(1f, out[1] * j * g);
        out[2] = Math.min(1f, out[2] * j * g);
    }

    /**
     * Which facade to dress a building in.
     *
     * <p>Driven by the OSM class first and the height second, because the
     * class is real evidence and the height is a reasonable proxy for age
     * and construction type where the class is vague. Anything above sixty
     * metres in this city is recent and almost always glazed.
     */
    static int facadeMaterial(int cls, float h, int seed) {
        switch (cls) {
            case 14: case 15:                      // hut, shed
                return TextureLibrary.MAT_FACADE_INFORMAL;
            case 6:                                // retail
                return TextureLibrary.MAT_FACADE_SHOP;
            case 4: case 5: case 21:               // commercial, office, tower
                return h > 24 ? TextureLibrary.MAT_FACADE_OFFICE
                              : TextureLibrary.MAT_FACADE_SHOP;
            case 7: case 8: case 16: case 17: case 18:
                return TextureLibrary.MAT_FACADE_CHAWL;
            case 9: case 10: case 11: case 12: case 13: case 19:
                return TextureLibrary.MAT_CONCRETE;
            default:
                if (h > 60) { return TextureLibrary.MAT_FACADE_OFFICE; }
                if (h < 9) {
                    // Low housing is a mix of older stock and newer infill.
                    return ((seed * 2654435761L) >>> 11 & 1L) == 0L
                            ? TextureLibrary.MAT_FACADE_CHAWL
                            : TextureLibrary.MAT_FACADE_APARTMENT;
                }
                return TextureLibrary.MAT_FACADE_APARTMENT;
        }
    }

    /** Roofs: sheet over self-built and light industrial, slab over the rest. */
    static int roofMaterial(int cls, float h) {
        switch (cls) {
            case 14: case 15: case 16:
                return TextureLibrary.MAT_ROOF_TIN;
            case 3:
                return h < 8 ? TextureLibrary.MAT_ROOF_TILE
                             : TextureLibrary.MAT_ROOF_CONCRETE;
            case 7: case 8:
                return TextureLibrary.MAT_ROOF_TIN;
            default:
                return TextureLibrary.MAT_ROOF_CONCRETE;
        }
    }

    /** Ground texture for a land-cover class. */
    static int coverMaterial(int cover) {
        switch (cover) {
            case 1: case 2: return TextureLibrary.MAT_GRASS;      // wetland, mangrove
            case 3: case 4: return TextureLibrary.MAT_SAND;       // beach, sand
            case 5: return TextureLibrary.MAT_ROCK;
            case 6: case 7: case 8: case 9: case 10: case 11: case 21:
                return TextureLibrary.MAT_GRASS;
            case 12: case 13: case 14: case 15: case 16: case 17:
            case 18: case 22: case 23:
                return TextureLibrary.MAT_CONCRETE;
            case 19: case 20: return TextureLibrary.MAT_DIRT;
            default: return TextureLibrary.MAT_DIRT;
        }
    }

    // ----------------------------------------------------------------- buffers

    private int push(float x, float y, float z, float nx, float ny, float nz,
                     float r, float g, float b, float aux) {
        // Default vertical texture coordinate is the world height, which is
        // what the shader used before the channel existed.
        return push(x, y, z, nx, ny, nz, r, g, b, aux, y);
    }

    /**
     * @param texV vertical texture coordinate in metres. For a building wall
     *             this is the height above that building's own base, so the
     *             bottom row of windows starts at the pavement instead of
     *             wherever a multiple of the tile height happens to fall.
     */
    private int push(float x, float y, float z, float nx, float ny, float nz,
                     float r, float g, float b, float aux, float texV) {
        if ((vCount + 1) * STRIDE > verts.length) {
            float[] n = new float[verts.length * 2];
            System.arraycopy(verts, 0, n, 0, verts.length);
            verts = n;
        }
        int o = vCount * STRIDE;
        verts[o] = x; verts[o + 1] = y; verts[o + 2] = z;
        verts[o + 3] = nx; verts[o + 4] = ny; verts[o + 5] = nz;
        verts[o + 6] = r; verts[o + 7] = g; verts[o + 8] = b;
        verts[o + 9] = aux; verts[o + 10] = texV;
        return vCount++;
    }

    /**
     * Emit a triangle wound to agree with the normal it is shaded by.
     *
     * <p>The renderer culls back faces with CCW front-facing, so a
     * triangle whose winding disagrees with its own normal is invisible
     * from the side it is lit on and visible from the side it is not.
     * Ninety-one per cent of this mesher's triangles were in that state:
     * walls, roofs and the road ribbons. Walking up to a building, the
     * near wall simply was not there, and through the gap you could see
     * the inside of the far wall; roads were culled from above, so the
     * street network was invisible from the only angle anyone looks at it.
     *
     * <p>The cause is that nothing here can assume a winding. An OSM ring
     * comes either way round and a road runs in either direction, so the
     * order the vertices happen to arrive in means nothing. Rather than
     * work the sign out at a dozen call sites and get one of them wrong
     * again, the answer is computed from the geometry: take the normal
     * the vertices are shaded by and flip the winding if it disagrees.
     */
    private void triN(int a, int b, int c) {
        int oa = a * STRIDE;
        int ob = b * STRIDE;
        int oc = c * STRIDE;
        double ux = verts[ob] - verts[oa];
        double uy = verts[ob + 1] - verts[oa + 1];
        double uz = verts[ob + 2] - verts[oa + 2];
        double vx = verts[oc] - verts[oa];
        double vy = verts[oc + 1] - verts[oa + 1];
        double vz = verts[oc + 2] - verts[oa + 2];
        double gx = uy * vz - uz * vy;
        double gy = uz * vx - ux * vz;
        double gz = ux * vy - uy * vx;
        double d = gx * verts[oa + 3] + gy * verts[oa + 4] + gz * verts[oa + 5];
        if (d < 0) { tri(a, c, b); } else { tri(a, b, c); }
    }

    private void tri(int a, int b, int c) {
        if (iCount + 3 > idx.length) {
            int[] n = new int[idx.length * 2];
            System.arraycopy(idx, 0, n, 0, idx.length);
            idx = n;
        }
        idx[iCount++] = a;
        idx[iCount++] = b;
        idx[iCount++] = c;
    }
}
