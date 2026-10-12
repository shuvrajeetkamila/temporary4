package ai.shuvrajeetkamila.mumbai.world;

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
 * <p>{@code aux} is a small channel the shader reads for material behaviour:
 * zero or more for emissive strength, and exactly -1 to mark a water surface,
 * which the fragment stage animates. Chunk-local floats keep the mesh precise:
 * at 45 km from the origin a float world coordinate quantises to about 4 mm,
 * which would make distant buildings visibly jitter as the camera moves.
 */
public final class ChunkMesher {

    /** Floats per vertex. */
    public static final int STRIDE = 10;

    /** Level of detail. 0 is full, 2 is a distant silhouette. */
    public static final int LOD_HIGH = 0;
    public static final int LOD_MED = 1;
    public static final int LOD_LOW = 2;

    private final WorldData world;

    private float[] verts = new float[1 << 16];
    private int vCount;
    private int[] idx = new int[1 << 16];
    private int iCount;

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

        terrain(ox, oz, size, lod);
        if (lod <= LOD_MED) {
            roads(f, ox, oz, lod);
        }
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
     * raster. Water cells are pushed to chart datum and flagged so the shader
     * can animate them in step with the analytic wave the boat physics uses.
     */
    private void terrain(double ox, double oz, double size, int lod) {
        int n = lod == LOD_HIGH ? 32 : (lod == LOD_MED ? 16 : 8);
        double step = size / n;
        int base = vCount;
        float[] rgb = new float[3];
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                double lx = i * step;
                double lz = j * step;
                double wx = ox + lx;
                double wz = oz + lz;
                boolean land = world.isLand(wx, wz);
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
                        rgb[0], rgb[1], rgb[2], land ? 0f : -1f);
            }
        }
        int row = n + 1;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int a = base + j * row + i;
                tri(a, a + row, a + 1);
                tri(a + 1, a + row, a + row + 1);
            }
        }
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
    private void roads(ChunkFeatures f, double ox, double oz, int lod) {
        for (int r = 0; r < f.roadCount(); r++) {
            int cls = f.roadClass[r];
            if (lod == LOD_MED && cls > 4 && cls != 19 && cls != 20) {
                continue;                       // keep arterials and runways only
            }
            if (cls == 23) { continue; }        // ferry route: not a surface
            int s = f.roadOffset[r];
            int e = f.roadOffset[r + 1];
            if (e - s < 2) { continue; }
            float half = Math.max(1.2f, f.roadWidth[r] * 0.5f);
            boolean bridge = (f.roadFlags[r] & ChunkFeatures.FLAG_BRIDGE) != 0;
            boolean tunnel = (f.roadFlags[r] & ChunkFeatures.FLAG_TUNNEL) != 0;
            if (tunnel) { continue; }
            float lift = bridge ? 0.0f : 0.07f;
            float[] rgb = new float[3];
            roadColour(cls, (f.roadFlags[r] & ChunkFeatures.FLAG_UNPAVED) != 0, rgb);

            // Bridge decks follow a straight line between their ends rather
            // than the seabed underneath, which is what makes the Sea Link and
            // the harbour jetties behave like structures and not like trenches.
            double y0 = groundAt(ox, oz, f.roadXZ, s);
            double y1 = groundAt(ox, oz, f.roadXZ, e - 1);
            double total = f.roadLength(r);

            int prevL = -1, prevR = -1;
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
                if (k > s) {
                    double sx = lx - f.roadXZ[(k - 1) * 2];
                    double sz = lz - f.roadXZ[(k - 1) * 2 + 1];
                    travelled += Math.sqrt(sx * sx + sz * sz);
                }
                double y;
                if (bridge && total > 1) {
                    double t = Math.min(1, travelled / total);
                    double deck = y0 + (y1 - y0) * t;
                    double arch = 4.5 * Math.sin(Math.PI * t);
                    double ground = world.isLand(ox + lx, oz + lz)
                            ? world.height(ox + lx, oz + lz) : 0.0;
                    y = Math.max(deck + arch, ground + 1.2);
                } else {
                    y = world.height(ox + lx, oz + lz) + lift;
                }
                int li = push(lx + nx * half, (float) y, lz + nz * half,
                        0, 1, 0, rgb[0], rgb[1], rgb[2], 0f);
                int ri = push(lx - nx * half, (float) y, lz - nz * half,
                        0, 1, 0, rgb[0], rgb[1], rgb[2], 0f);
                if (prevL >= 0) {
                    tri(prevL, li, prevR);
                    tri(prevR, li, ri);
                }
                prevL = li;
                prevR = ri;
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
        float[] rgb = new float[3];
        for (int b = 0; b < f.buildingCount(); b++) {
            float h = f.buildingHeight[b];
            if (h < minHeight) { continue; }
            if (minArea > 0 && f.buildingArea(b) < minArea) { continue; }
            int s = f.buildingOffset[b];
            int e = f.buildingOffset[b + 1];
            int n = e - s;
            if (n < 3) { continue; }

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
            int floors = 1;
            if (lod == LOD_HIGH) {
                floors = Math.max(1, Math.min(14, (int) (h / 9.0)));
            }

            // Walls.
            for (int k = 0; k < n; k++) {
                int i0 = s + k;
                int i1 = s + (k + 1) % n;
                float ax = f.buildingXZ[i0 * 2], az = f.buildingXZ[i0 * 2 + 1];
                float bx = f.buildingXZ[i1 * 2], bz = f.buildingXZ[i1 * 2 + 1];
                double ex = bx - ax, ez = bz - az;
                double el = Math.sqrt(ex * ex + ez * ez);
                if (el < 0.25) { continue; }
                float nx = (float) (ez / el);
                float nz = (float) (-ex / el);
                // Faces catch the light differently; a flat albedo makes a city
                // of boxes look like a single grey mass.
                float shade = 0.84f + 0.16f * Math.abs(nx);
                for (int ff = 0; ff < floors; ff++) {
                    double fy0 = y0 + (y1 - y0) * ff / (double) floors;
                    double fy1 = y0 + (y1 - y0) * (ff + 1) / (double) floors;
                    float band = (ff % 2 == 0) ? 1.0f : 0.93f;
                    float cr = rgb[0] * shade * band;
                    float cg = rgb[1] * shade * band;
                    float cb = rgb[2] * shade * band;
                    // Windows glow a little at the top floors of tall blocks.
                    float aux = (lod == LOD_HIGH && h > 24 && ff > 0) ? 0.22f : 0f;
                    int v0 = push(ax, (float) fy0, az, nx, 0, nz, cr, cg, cb, aux);
                    int v1 = push(bx, (float) fy0, bz, nx, 0, nz, cr, cg, cb, aux);
                    int v2 = push(bx, (float) fy1, bz, nx, 0, nz, cr, cg, cb, aux);
                    int v3 = push(ax, (float) fy1, az, nx, 0, nz, cr, cg, cb, aux);
                    tri(v0, v1, v2);
                    tri(v0, v2, v3);
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
            double roofY = y1 + (f.buildingRoof[b] != 0 ? 1.6 : 0.0);
            int c = push((float) cx, (float) roofY, (float) cz, 0, 1, 0, rr, rg, rb, 0f);
            int first = -1, prev = -1;
            for (int k = 0; k < n; k++) {
                int ik = s + k;
                int v = push(f.buildingXZ[ik * 2], (float) y1, f.buildingXZ[ik * 2 + 1],
                        0, 1, 0, rr, rg, rb, 0f);
                if (first < 0) { first = v; }
                if (prev >= 0) { tri(c, prev, v); }
                prev = v;
            }
            if (first >= 0 && prev >= 0) { tri(c, prev, first); }
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

    // ----------------------------------------------------------------- buffers

    private int push(float x, float y, float z, float nx, float ny, float nz,
                     float r, float g, float b, float aux) {
        if ((vCount + 1) * STRIDE > verts.length) {
            float[] n = new float[verts.length * 2];
            System.arraycopy(verts, 0, n, 0, verts.length);
            verts = n;
        }
        int o = vCount * STRIDE;
        verts[o] = x; verts[o + 1] = y; verts[o + 2] = z;
        verts[o + 3] = nx; verts[o + 4] = ny; verts[o + 5] = nz;
        verts[o + 6] = r; verts[o + 7] = g; verts[o + 8] = b;
        verts[o + 9] = aux;
        return vCount++;
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
