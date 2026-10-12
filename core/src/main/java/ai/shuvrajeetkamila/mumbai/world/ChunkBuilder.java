package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.core.Mth;

/**
 * Deterministic chunk geometry compiler.
 *
 * <p>Given the packaged world layers, this reconstructs the renderable + collidable content
 * of one streaming tile: terrain surface, roads and bridges, procedural building massing
 * with facade detail, vegetation, hero landmarks and airport surfaces. Everything is a pure
 * function of (chunk coordinate, LOD, packaged data, world seed), so two devices generate
 * byte identical geometry and the JVM tests can assert on it without a GPU.
 *
 * <p>Coordinates are chunk local (0..size) which keeps float precision exact; the renderer
 * applies the floating-origin offset per draw.
 */
public final class ChunkBuilder {

    /** Terrain grid resolution per tile, constant across LODs (512 triangles / tile). */
    public static final int TERRAIN_QUADS = 16;

    private final WorldData world;
    private final MeshBuilder mesh = new MeshBuilder(4096, 8192);
    private final float[] normal = new float[3];

    public ChunkBuilder(WorldData world) {
        this.world = world;
    }

    /** Result of one build; plain arrays so the GL thread only has to upload. */
    public static final class Geometry {
        public int cx, cz, lod;
        public double originX, originZ;
        public float size;
        public float[] verts;
        public int[] indices;
        public int triangles;
        public float minY, maxY;
        /** Static collision proxies: x0,y0,z0,x1,y1,z1 per box, chunk local. */
        public float[] colliders;
        public int colliderCount;
        public int buildingCount;
        public int treeCount;
    }

    /**
     * Builds one tile.
     *
     * @param cx   chunk X index at LOD 0 granularity (tile covers {@code 1<<lod} chunks)
     * @param cz   chunk Z index
     * @param lod  0 = 256 m full detail, 1 = 1 km massing, 2 = 4 km skyline
     */
    public Geometry build(int cx, int cz, int lod) {
        mesh.reset();
        int span = WorldStreamer.spanFor(lod);
        float size = world.chunkSize * span;
        double ox = world.chunkX0 + cx * (double) world.chunkSize;
        double oz = world.chunkZ0 + cz * (double) world.chunkSize;

        Geometry g = new Geometry();
        g.cx = cx;
        g.cz = cz;
        g.lod = lod;
        g.originX = ox;
        g.originZ = oz;
        g.size = size;

        float[] minMax = new float[]{Float.MAX_VALUE, -Float.MAX_VALUE};
        buildTerrain(ox, oz, size, lod, minMax);

        boolean anyLand = false;
        for (int j = 0; j < span && !anyLand; j++) {
            for (int i = 0; i < span; i++) {
                if (world.chunkLandFraction(cx + i, cz + j) > 0.02f) {
                    anyLand = true;
                    break;
                }
            }
        }

        FloatList colliders = new FloatList(64);
        if (anyLand) {
            buildRoads(ox, oz, size, lod);
            buildRails(ox, oz, size, lod);
            if (lod <= 1) {
                buildBuildings(cx, cz, ox, oz, size, lod, colliders, g);
                buildLandmarks(ox, oz, size, lod, colliders, g);
            } else {
                buildSkylineMassing(cx, cz, ox, oz, size, g);
            }
            if (lod == 0) {
                buildVegetationAndProps(ox, oz, size, g);
            }
        }

        g.verts = mesh.vertexArray();
        g.indices = mesh.indexArray();
        g.triangles = mesh.triangleCount();
        g.minY = minMax[0];
        g.maxY = minMax[1];
        g.colliders = colliders.trimmed();
        g.colliderCount = colliders.size / 6;
        return g;
    }

    // ---------------------------------------------------------------- terrain
    private void buildTerrain(double ox, double oz, float size, int lod, float[] minMax) {
        int n = TERRAIN_QUADS;
        float step = size / n;
        int base = mesh.vertexCount;
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                float lx = i * step;
                float lz = j * step;
                double wx = ox + lx;
                double wz = oz + lz;
                float h = world.heightAt(wx, wz);
                int lu = world.landUseAt(wx, wz);
                // micro relief so flat reclaimed land is not mirror flat up close
                if (lod == 0 && !LandUse.isWater(lu)) {
                    h += (Mth.fbm((float) wx * 0.02f, (float) wz * 0.02f, 3, 2.1f, 0.5f, 77) - 0.5f) * 0.6f;
                }
                world.normalAt(wx, wz, normal);
                int rgb = LandUse.baseColour(lu);
                float r = ((rgb >> 16) & 0xFF) / 255f;
                float gg = ((rgb >> 8) & 0xFF) / 255f;
                float b = (rgb & 0xFF) / 255f;
                float tint = 0.92f + Mth.rand01((int) (wx * 0.37), (int) (wz * 0.37), 5) * 0.16f;
                mesh.vertex(lx, h, lz, normal[0], normal[1], normal[2],
                        r * tint, gg * tint, b * tint, 0f);
                if (h < minMax[0]) {
                    minMax[0] = h;
                }
                if (h > minMax[1]) {
                    minMax[1] = h;
                }
            }
        }
        int stride = n + 1;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int a = base + j * stride + i;
                int b = a + 1;
                int c = a + stride + 1;
                int d = a + stride;
                mesh.tri(a, d, c);
                mesh.tri(a, c, b);
            }
        }
    }

    // ---------------------------------------------------------------- roads
    private void buildRoads(double ox, double oz, float size, int lod) {
        double cx = ox + size * 0.5;
        double cz = oz + size * 0.5;
        double cull = size * 0.75 + 400.0;
        for (int ri = 0; ri < world.roads.size(); ri++) {
            WorldData.Polyline p = world.roads.get(ri);
            if (lod >= 2 && p.cls > 1) {
                continue;
            }
            if (lod == 1 && p.cls > 2) {
                continue;
            }
            float halfWidth = roadHalfWidth(p);
            for (int k = 1; k < p.pointCount(); k++) {
                float ax = p.x(k - 1), az = p.z(k - 1);
                float bx = p.x(k), bz = p.z(k);
                if (Mth.distancePointSegment(cx, cz, ax, az, bx, bz) > cull) {
                    continue;
                }
                emitRoadSegment(ox, oz, ax, az, bx, bz, halfWidth, p.layer, p.cls, lod);
            }
        }
    }

    private float roadHalfWidth(WorldData.Polyline p) {
        float laneW = 3.4f;
        int lanes = Math.max(1, p.lanes);
        float w = lanes * laneW;
        if (p.cls == 0) {
            w += 6f;     // shoulders + median
        } else if (p.cls == 1) {
            w += 3.5f;
        } else {
            w += 2.2f;   // footpaths
        }
        return w;
    }

    private void emitRoadSegment(double ox, double oz, float ax, float az, float bx, float bz,
                                 float halfWidth, int layer, int cls, int lod) {
        float dx = bx - ax;
        float dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 0.5f) {
            return;
        }
        int steps = Math.max(1, (int) (len / (lod == 0 ? 24f : 64f)));
        float px = -dz / len;
        float pz = dx / len;
        float prevX0 = 0, prevZ0 = 0, prevY0 = 0, prevX1 = 0, prevZ1 = 0, prevY1 = 0;
        boolean has = false;
        for (int s = 0; s <= steps; s++) {
            float t = s / (float) steps;
            float wx = ax + dx * t;
            float wz = az + dz * t;
            float ground = world.heightAt(wx, wz);
            float y = ground + 0.22f;
            if (layer > 0) {
                // Elevated structure: lift smoothly, never dip into terrain.
                float lift = 9.5f;
                y = Math.max(ground + 1.0f, groundProfile(ax, az, bx, bz) + lift);
            } else if (layer < 0) {
                y = ground - 6.0f;
            }
            float x0 = (float) (wx + px * halfWidth - ox);
            float z0 = (float) (wz + pz * halfWidth - oz);
            float x1 = (float) (wx - px * halfWidth - ox);
            float z1 = (float) (wz - pz * halfWidth - oz);
            if (has) {
                float shade = cls == 0 ? 0.26f : (cls == 1 ? 0.28f : 0.30f);
                mesh.quadXYZ(prevX0, prevY0, prevZ0, prevX1, prevY1, prevZ1,
                        x1, y, z1, x0, y, z0, shade, shade, shade * 1.04f, 0f);
                // lane marking strip down the middle
                if (lod == 0) {
                    float mx0 = (prevX0 + prevX1) * 0.5f;
                    float mz0 = (prevZ0 + prevZ1) * 0.5f;
                    float mx1 = (x0 + x1) * 0.5f;
                    float mz1 = (z0 + z1) * 0.5f;
                    float w = 0.22f;
                    mesh.quadXYZ(mx0 + px * w, prevY0 + 0.03f, mz0 + pz * w,
                            mx1 + px * w, y + 0.03f, mz1 + pz * w,
                            mx1 - px * w, y + 0.03f, mz1 - pz * w,
                            mx0 - px * w, prevY0 + 0.03f, mz0 - pz * w,
                            0.82f, 0.80f, 0.64f, 0f);
                }
                if (layer > 0 && lod == 0) {
                    // deck edge + piers
                    mesh.quadXYZ(prevX0, prevY0 - 1.4f, prevZ0, x0, y - 1.4f, z0,
                            x0, y, z0, prevX0, prevY0, prevZ0, 0.52f, 0.52f, 0.50f, 0f);
                    mesh.quadXYZ(x1, y - 1.4f, z1, prevX1, prevY1 - 1.4f, prevZ1,
                            prevX1, prevY1, prevZ1, x1, y, z1, 0.52f, 0.52f, 0.50f, 0f);
                    if ((s & 3) == 0) {
                        float gy = world.heightAt(wx, wz);
                        float h = Math.max(1f, y - 1.4f - gy);
                        mesh.cylinder((float) (wx - ox), gy, (float) (wz - oz), 1.6f, h, 6,
                                0.56f, 0.55f, 0.53f, 0f);
                    }
                }
            }
            prevX0 = x0;
            prevZ0 = z0;
            prevY0 = y;
            prevX1 = x1;
            prevZ1 = z1;
            prevY1 = y;
            has = true;
        }
    }

    /** Smoothed ground level used to keep bridge decks level over water. */
    private float groundProfile(float ax, float az, float bx, float bz) {
        float h = 0f;
        for (int i = 0; i <= 4; i++) {
            float t = i / 4f;
            h = Math.max(h, world.heightAt(ax + (bx - ax) * t, az + (bz - az) * t));
        }
        return Math.max(h, 1.5f);
    }

    // ---------------------------------------------------------------- rail
    private void buildRails(double ox, double oz, float size, int lod) {
        if (lod > 1) {
            return;
        }
        double cx = ox + size * 0.5;
        double cz = oz + size * 0.5;
        double cull = size * 0.75 + 300.0;
        for (int ri = 0; ri < world.rails.size(); ri++) {
            WorldData.Polyline p = world.rails.get(ri);
            for (int k = 1; k < p.pointCount(); k++) {
                float ax = p.x(k - 1), az = p.z(k - 1);
                float bx = p.x(k), bz = p.z(k);
                if (Mth.distancePointSegment(cx, cz, ax, az, bx, bz) > cull) {
                    continue;
                }
                float dx = bx - ax, dz = bz - az;
                float len = (float) Math.sqrt(dx * dx + dz * dz);
                if (len < 1f) {
                    continue;
                }
                float px = -dz / len, pz = dx / len;
                int steps = Math.max(1, (int) (len / 48f));
                float half = p.cls == 0 ? 7.5f : 5.0f;
                float lift = p.layer > 0 ? 11.0f : 0.5f;
                float prevY = 0, px0 = 0, pz0 = 0, px1 = 0, pz1 = 0;
                boolean has = false;
                for (int s = 0; s <= steps; s++) {
                    float t = s / (float) steps;
                    float wx = ax + dx * t;
                    float wz = az + dz * t;
                    float y = world.heightAt(wx, wz) + lift;
                    float x0 = (float) (wx + px * half - ox);
                    float z0 = (float) (wz + pz * half - oz);
                    float x1 = (float) (wx - px * half - ox);
                    float z1 = (float) (wz - pz * half - oz);
                    if (has) {
                        mesh.quadXYZ(px0, prevY, pz0, px1, prevY, pz1, x1, y, z1, x0, y, z0,
                                0.34f, 0.31f, 0.29f, 0f);
                        if (p.layer > 0 && (s & 1) == 0) {
                            float gy = world.heightAt(wx, wz);
                            mesh.cylinder((float) (wx - ox), gy, (float) (wz - oz), 1.3f,
                                    Math.max(1f, y - gy), 6, 0.6f, 0.6f, 0.58f, 0f);
                        }
                    }
                    px0 = x0;
                    pz0 = z0;
                    px1 = x1;
                    pz1 = z1;
                    prevY = y;
                    has = true;
                }
            }
        }
    }

    // ---------------------------------------------------------------- buildings
    private void buildBuildings(int cx, int cz, double ox, double oz, float size, int lod,
                                FloatList colliders, Geometry g) {
        int span = WorldStreamer.spanFor(lod);
        for (int sj = 0; sj < span; sj++) {
            for (int si = 0; si < span; si++) {
                int ccx = cx + si;
                int ccz = cz + sj;
                if (!world.chunkInRange(ccx, ccz)) {
                    continue;
                }
                float landFrac = world.chunkLandFraction(ccx, ccz);
                if (landFrac < 0.08f) {
                    continue;
                }
                double sub0x = world.chunkX0 + ccx * (double) world.chunkSize;
                double sub0z = world.chunkZ0 + ccz * (double) world.chunkSize;
                int luCentre = world.landUseAt(sub0x + world.chunkSize * 0.5,
                        sub0z + world.chunkSize * 0.5);
                int target = LandUse.buildingDensity(luCentre);
                if (target == 0) {
                    continue;
                }
                if (lod == 1) {
                    target = Math.max(3, target / 5);
                }
                int seed = Mth.hash3(ccx, ccz, world.seed);
                int placed = 0;
                int attempts = target * 3;
                for (int a = 0; a < attempts && placed < target; a++) {
                    int h1 = Mth.hash3(ccx * 131 + a, ccz * 17 + a * 7, world.seed + 3);
                    float fx = ((h1 >>> 8) & 0xFFF) / 4096f;
                    float fz = ((h1 >>> 20) & 0xFFF) / 4096f;
                    double wx = sub0x + fx * world.chunkSize;
                    double wz = sub0z + fz * world.chunkSize;
                    int lu = world.landUseAt(wx, wz);
                    if (!LandUse.isUrban(lu) && lu != LandUse.INDUSTRIAL) {
                        continue;
                    }
                    double roadDist = nearRoadDistance(wx, wz);
                    if (roadDist < 9.0) {
                        continue;   // keep the carriageway clear
                    }
                    float ground = world.heightAt(wx, wz);
                    if (ground < 0.4f) {
                        continue;
                    }
                    float hMin = LandUse.minHeight(lu);
                    float hMax = LandUse.maxHeight(lu);
                    float t = Mth.rand01((int) wx, (int) wz, seed);
                    float height = hMin + (hMax - hMin) * t * t;
                    float halfW = 4f + Mth.rand01((int) wx, (int) wz, seed + 1) * 9f;
                    float halfD = 4f + Mth.rand01((int) wx, (int) wz, seed + 2) * 9f;
                    if (lu == LandUse.URBAN_DENSE) {
                        halfW = 2.6f + Mth.rand01((int) wx, (int) wz, seed + 5) * 3.4f;
                        halfD = 2.6f + Mth.rand01((int) wx, (int) wz, seed + 6) * 3.4f;
                    }
                    if (lu == LandUse.CBD) {
                        halfW = 9f + Mth.rand01((int) wx, (int) wz, seed + 7) * 10f;
                        halfD = halfW * (0.7f + Mth.rand01((int) wx, (int) wz, seed + 8) * 0.5f);
                    }
                    float yaw = roadAlignedYaw(wx, wz);
                    float shade = 0.72f + Mth.rand01((int) wx, (int) wz, seed + 9) * 0.3f;
                    float r = shade * (0.82f + Mth.rand01((int) wx, (int) wz, seed + 11) * 0.18f);
                    float gg = shade * (0.80f + Mth.rand01((int) wx, (int) wz, seed + 12) * 0.16f);
                    float b = shade * (0.74f + Mth.rand01((int) wx, (int) wz, seed + 13) * 0.16f);
                    float windowAux = lu == LandUse.URBAN_DENSE ? 0.35f : 0.85f;
                    float lx = (float) (wx - ox);
                    float lz = (float) (wz - oz);
                    mesh.boxYaw(lx, ground - 0.4f, lz, halfW, height, halfD, yaw,
                            r, gg, b, r * 0.62f, gg * 0.62f, b * 0.6f, windowAux);
                    if (lod == 0) {
                        addRooftopDetail(lx, ground + height - 0.4f, lz, halfW, halfD, seed + a);
                    }
                    colliders.add(lx - halfW, ground - 0.4f, lz - halfD,
                            lx + halfW, ground + height, lz + halfD);
                    placed++;
                    g.buildingCount++;
                }
            }
        }
    }

    private void addRooftopDetail(float lx, float topY, float lz, float halfW, float halfD, int seed) {
        float r = Mth.rand01(seed, 3);
        if (r < 0.45f) {
            // water tanks, very Mumbai
            int n = 1 + (int) (Mth.rand01(seed, 5) * 2);
            for (int i = 0; i < n; i++) {
                float px = lx + (Mth.rand01(seed, 7 + i) - 0.5f) * halfW;
                float pz = lz + (Mth.rand01(seed, 11 + i) - 0.5f) * halfD;
                mesh.cylinder(px, topY, pz, 0.7f, 1.4f, 6, 0.16f, 0.24f, 0.34f, 0f);
            }
        } else if (r < 0.75f) {
            mesh.box(lx, topY, lz, halfW * 0.35f, 2.2f, halfD * 0.35f,
                    0.6f, 0.58f, 0.56f, 0.5f, 0.5f, 0.48f, 0f, false);
        }
        // parapet
        mesh.box(lx, topY, lz, halfW, 0.5f, halfD, 0.56f, 0.55f, 0.52f,
                0.5f, 0.49f, 0.47f, 0f, false);
    }

    /** Approximate distance to the nearest major road, used to leave corridors clear. */
    private double nearRoadDistance(double x, double z) {
        double best = 1e9;
        for (int i = 0; i < world.roads.size(); i++) {
            WorldData.Polyline p = world.roads.get(i);
            for (int k = 1; k < p.pointCount(); k++) {
                double ax = p.x(k - 1), az = p.z(k - 1);
                double bx = p.x(k), bz = p.z(k);
                double mx = (ax + bx) * 0.5, mz = (az + bz) * 0.5;
                double dx = mx - x, dz = mz - z;
                if (dx * dx + dz * dz > 400 * 400) {
                    continue;
                }
                double d = Mth.distancePointSegment(x, z, ax, az, bx, bz) - roadHalfWidth(p);
                if (d < best) {
                    best = d;
                }
            }
        }
        return best;
    }

    /** Yaw that aligns a building footprint with the nearest street. */
    private float roadAlignedYaw(double x, double z) {
        double best = 1e9;
        float yaw = Mth.rand01((int) x, (int) z, 991) * Mth.TAU;
        for (int i = 0; i < world.roads.size(); i++) {
            WorldData.Polyline p = world.roads.get(i);
            for (int k = 1; k < p.pointCount(); k++) {
                double ax = p.x(k - 1), az = p.z(k - 1);
                double bx = p.x(k), bz = p.z(k);
                double mx = (ax + bx) * 0.5, mz = (az + bz) * 0.5;
                double dx = mx - x, dz = mz - z;
                if (dx * dx + dz * dz > 300 * 300) {
                    continue;
                }
                double d = Mth.distancePointSegment(x, z, ax, az, bx, bz);
                if (d < best) {
                    best = d;
                    yaw = (float) Math.atan2(bz - az, bx - ax);
                }
            }
        }
        return yaw;
    }

    // ---------------------------------------------------------------- skyline LOD
    private void buildSkylineMassing(int cx, int cz, double ox, double oz, float size, Geometry g) {
        int span = 1 << 2;
        for (int sj = 0; sj < span; sj += 1) {
            for (int si = 0; si < span; si += 1) {
                int ccx = cx + si * 4;
                int ccz = cz + sj * 4;
                if (!world.chunkInRange(ccx, ccz)) {
                    continue;
                }
                if (world.chunkLandFraction(ccx, ccz) < 0.3f) {
                    continue;
                }
                int d = world.chunkDensityClass(ccx, ccz);
                if (d < 2) {
                    continue;
                }
                double wx = world.chunkCentreX(ccx);
                double wz = world.chunkCentreZ(ccz);
                float ground = world.heightAt(wx, wz);
                float h = 10f + d * 9f + Mth.rand01(ccx, ccz, 31) * d * 11f;
                float half = world.chunkSize * 0.42f;
                float shade = 0.62f + Mth.rand01(ccx, ccz, 41) * 0.2f;
                mesh.box((float) (wx - ox), ground, (float) (wz - oz), half, h, half,
                        shade * 0.86f, shade * 0.84f, shade * 0.8f,
                        shade * 0.6f, shade * 0.6f, shade * 0.58f, 0.5f, false);
                g.buildingCount++;
            }
        }
    }

    // ---------------------------------------------------------------- landmarks
    private void buildLandmarks(double ox, double oz, float size, int lod,
                                FloatList colliders, Geometry g) {
        double cx = ox + size * 0.5;
        double cz = oz + size * 0.5;
        double cull = size * 0.75 + 600.0;
        for (int i = 0; i < world.landmarks.size(); i++) {
            WorldData.Landmark lm = world.landmarks.get(i);
            double dx = lm.x - cx;
            double dz = lm.z - cz;
            if (Math.abs(dx) > cull || Math.abs(dz) > cull) {
                continue;
            }
            float lx = (float) (lm.x - ox);
            float lz = (float) (lm.z - oz);
            float ground = Math.max(0.5f, world.heightAt(lm.x, lm.z));
            emitLandmark(lm, lx, ground, lz, lod, colliders, g);
        }
    }

    private void emitLandmark(WorldData.Landmark lm, float lx, float ground, float lz, int lod,
                              FloatList colliders, Geometry g) {
        String k = lm.kind;
        float a = lm.a;
        float b = lm.b;
        float yaw = lm.heading * Mth.DEG2RAD;
        if ("arch".equals(k)) {
            float w = a, h = b;
            mesh.box(lx - w * 0.42f, ground, lz, w * 0.16f, h * 0.72f, w * 0.22f,
                    0.78f, 0.72f, 0.58f, 0.7f, 0.66f, 0.54f, 0f, false);
            mesh.box(lx + w * 0.42f, ground, lz, w * 0.16f, h * 0.72f, w * 0.22f,
                    0.78f, 0.72f, 0.58f, 0.7f, 0.66f, 0.54f, 0f, false);
            mesh.box(lx, ground + h * 0.72f, lz, w * 0.6f, h * 0.18f, w * 0.26f,
                    0.8f, 0.74f, 0.6f, 0.72f, 0.68f, 0.56f, 0f, false);
            mesh.dome(lx, ground + h * 0.9f, lz, w * 0.26f, h * 0.3f, 8, 3, 0.8f, 0.74f, 0.6f);
            colliders.add(lx - w * 0.6f, ground, lz - w * 0.3f, lx + w * 0.6f, ground + h, lz + w * 0.3f);
        } else if ("heritage_station".equals(k) || "heritage_block".equals(k)) {
            float w = a * 0.5f, d = b * 0.5f;
            mesh.boxYaw(lx, ground, lz, w, 26f, d, yaw, 0.62f, 0.52f, 0.42f,
                    0.5f, 0.42f, 0.34f, 0.8f);
            if ("heritage_station".equals(k)) {
                mesh.dome(lx, ground + 26f, lz, Math.min(w, d) * 0.55f, 26f, 8, 3,
                        0.58f, 0.48f, 0.4f);
                mesh.cylinder(lx, ground + 50f, lz, 1.4f, 10f, 6, 0.6f, 0.5f, 0.42f, 0f);
            }
            colliders.add(lx - w, ground, lz - d, lx + w, ground + 30f, lz + d);
        } else if ("tower".equals(k)) {
            mesh.boxYaw(lx, ground, lz, a * 0.5f, b, a * 0.42f, yaw,
                    0.56f, 0.6f, 0.66f, 0.4f, 0.42f, 0.46f, 1.0f);
            mesh.cylinder(lx, ground + b, lz, 1.0f, 12f, 5, 0.5f, 0.5f, 0.55f, 0f);
            colliders.add(lx - a * 0.5f, ground, lz - a * 0.5f, lx + a * 0.5f, ground + b, lz + a * 0.5f);
        } else if ("twin_tower".equals(k)) {
            mesh.box(lx - a * 0.6f, ground, lz, a * 0.4f, b, a * 0.4f,
                    0.58f, 0.62f, 0.68f, 0.42f, 0.44f, 0.48f, 1.0f, false);
            mesh.box(lx + a * 0.6f, ground, lz, a * 0.4f, b * 0.96f, a * 0.4f,
                    0.58f, 0.62f, 0.68f, 0.42f, 0.44f, 0.48f, 1.0f, false);
            colliders.add(lx - a, ground, lz - a * 0.5f, lx + a, ground + b, lz + a * 0.5f);
        } else if ("tower_cluster".equals(k) || "cbd_cluster".equals(k)) {
            int n = 7;
            for (int i = 0; i < n; i++) {
                float ang = i * Mth.TAU / n;
                float rr = a * (0.2f + 0.3f * Mth.rand01(i, (int) lx, 7));
                float px = lx + (float) Math.cos(ang) * rr;
                float pz = lz + (float) Math.sin(ang) * rr;
                float h = b * (0.45f + 0.55f * Mth.rand01(i, (int) lz, 9));
                float hw = 8f + Mth.rand01(i, (int) lx, 13) * 10f;
                mesh.box(px, ground, pz, hw, h, hw * 0.8f,
                        0.6f, 0.63f, 0.68f, 0.42f, 0.44f, 0.48f, 1.0f, false);
                colliders.add(px - hw, ground, pz - hw, px + hw, ground + h, pz + hw);
            }
        } else if ("dome_shrine".equals(k) || "temple".equals(k)) {
            mesh.box(lx, ground, lz, a * 0.5f, b * 0.45f, a * 0.5f,
                    0.88f, 0.86f, 0.8f, 0.8f, 0.78f, 0.72f, 0.4f, false);
            mesh.dome(lx, ground + b * 0.45f, lz, a * 0.42f, b * 0.55f, 10, 4,
                    0.92f, 0.9f, 0.84f);
            colliders.add(lx - a * 0.5f, ground, lz - a * 0.5f, lx + a * 0.5f, ground + b, lz + a * 0.5f);
        } else if ("stadium".equals(k)) {
            int n = 16;
            for (int i = 0; i < n; i++) {
                float ang0 = i * Mth.TAU / n;
                float ang1 = (i + 1) * Mth.TAU / n;
                float r0 = a * 0.42f;
                float r1 = a * 0.5f;
                mesh.quadXYZ(lx + (float) Math.cos(ang0) * r0, ground + 4f, lz + (float) Math.sin(ang0) * r0,
                        lx + (float) Math.cos(ang1) * r0, ground + 4f, lz + (float) Math.sin(ang1) * r0,
                        lx + (float) Math.cos(ang1) * r1, ground + b, lz + (float) Math.sin(ang1) * r1,
                        lx + (float) Math.cos(ang0) * r1, ground + b, lz + (float) Math.sin(ang0) * r1,
                        0.74f, 0.74f, 0.76f, 0f);
            }
        } else if ("cable_bridge".equals(k)) {
            buildCableBridge(lx, ground, lz, a, b, lod);
        } else if ("fort".equals(k)) {
            mesh.box(lx, ground, lz, a * 0.5f, b, a * 0.5f, 0.52f, 0.48f, 0.42f,
                    0.46f, 0.44f, 0.4f, 0f, false);
            colliders.add(lx - a * 0.5f, ground, lz - a * 0.5f, lx + a * 0.5f, ground + b, lz + a * 0.5f);
        } else if ("informal_quarter".equals(k)) {
            int n = lod == 0 ? 160 : 40;
            for (int i = 0; i < n; i++) {
                float px = lx + (Mth.rand01(i, (int) lx, 3) - 0.5f) * a;
                float pz = lz + (Mth.rand01(i, (int) lz, 5) - 0.5f) * a;
                float gy = world.heightAt(px + 0.0, pz + 0.0);
                float h = 3f + Mth.rand01(i, 7, 11) * b * 0.5f;
                float hw = 2.2f + Mth.rand01(i, 9, 13) * 1.8f;
                float tint = 0.6f + Mth.rand01(i, 11, 17) * 0.4f;
                mesh.boxYaw(px, ground, pz, hw, h, hw * 0.9f,
                        Mth.rand01(i, 13, 19) * Mth.TAU,
                        tint * 0.72f, tint * 0.62f, tint * 0.5f,
                        0.45f, 0.42f, 0.4f, 0.15f);
            }
        } else if ("promenade".equals(k)) {
            mesh.box(lx, ground, lz, b, 0.8f, b, 0.72f, 0.70f, 0.66f,
                    0.76f, 0.74f, 0.70f, 0f, false);
        } else if ("terminal".equals(k)) {
            mesh.boxYaw(lx, ground, lz, a * 0.5f, b, a * 0.22f, yaw,
                    0.74f, 0.76f, 0.8f, 0.66f, 0.68f, 0.72f, 0.9f);
            colliders.add(lx - a * 0.5f, ground, lz - a * 0.3f, lx + a * 0.5f, ground + b, lz + a * 0.3f);
        } else if ("port".equals(k) || "industrial".equals(k)) {
            for (int i = 0; i < 6; i++) {
                float px = lx + (Mth.rand01(i, 3, 23) - 0.5f) * a;
                float pz = lz + (Mth.rand01(i, 5, 29) - 0.5f) * a;
                mesh.cylinder(px, ground, pz, 6f, b * (0.5f + Mth.rand01(i, 7, 31) * 0.8f), 8,
                        0.7f, 0.68f, 0.64f, 0f);
            }
        } else if ("rock_ridge".equals(k)) {
            mesh.dome(lx, ground, lz, a * 0.5f, b, 7, 3, 0.42f, 0.4f, 0.34f);
        } else if ("market".equals(k)) {
            mesh.box(lx, ground, lz, a * 0.5f, b, a * 0.4f, 0.8f, 0.6f, 0.4f,
                    0.7f, 0.5f, 0.34f, 0.3f, false);
        } else if ("rail_bridge".equals(k) || "road_bridge".equals(k)) {
            buildCableBridge(lx, ground, lz, a, b * 0.6f, lod);
        }
        g.buildingCount++;
    }

    private void buildCableBridge(float lx, float ground, float lz, float length, float pylonH, int lod) {
        float deckY = ground + 18f;
        float half = length * 0.5f;
        int steps = lod == 0 ? 24 : 8;
        for (int i = 0; i < steps; i++) {
            float z0 = lz - half + length * i / steps;
            float z1 = lz - half + length * (i + 1) / steps;
            mesh.quadXYZ(lx - 14f, deckY, z0, lx + 14f, deckY, z0,
                    lx + 14f, deckY, z1, lx - 14f, deckY, z1, 0.4f, 0.4f, 0.42f, 0f);
        }
        for (int p = 0; p < 2; p++) {
            float pz = lz + (p == 0 ? -length * 0.22f : length * 0.22f);
            mesh.cylinder(lx - 9f, ground, pz, 3.2f, pylonH, 6, 0.86f, 0.86f, 0.88f, 0f);
            mesh.cylinder(lx + 9f, ground, pz, 3.2f, pylonH, 6, 0.86f, 0.86f, 0.88f, 0f);
            mesh.box(lx, ground + pylonH * 0.82f, pz, 12f, 3f, 2.4f,
                    0.84f, 0.84f, 0.86f, 0.8f, 0.8f, 0.82f, 0f, false);
            for (int c = 1; c <= 6; c++) {
                float off = c * length * 0.035f;
                cable(lx - 9f, ground + pylonH, pz, lx - 12f, deckY + 1f, pz - off);
                cable(lx - 9f, ground + pylonH, pz, lx - 12f, deckY + 1f, pz + off);
                cable(lx + 9f, ground + pylonH, pz, lx + 12f, deckY + 1f, pz - off);
                cable(lx + 9f, ground + pylonH, pz, lx + 12f, deckY + 1f, pz + off);
            }
        }
    }

    private void cable(float x0, float y0, float z0, float x1, float y1, float z1) {
        float w = 0.22f;
        mesh.quadXYZ(x0 - w, y0, z0, x0 + w, y0, z0, x1 + w, y1, z1, x1 - w, y1, z1,
                0.9f, 0.9f, 0.92f, 0f);
    }

    // ---------------------------------------------------------------- vegetation / props
    private void buildVegetationAndProps(double ox, double oz, float size, Geometry g) {
        int step = 24;
        for (float lz = step * 0.5f; lz < size; lz += step) {
            for (float lx = step * 0.5f; lx < size; lx += step) {
                double wx = ox + lx;
                double wz = oz + lz;
                int lu = world.landUseAt(wx, wz);
                float ground = world.heightAt(wx, wz);
                int h = Mth.hash3((int) wx, (int) wz, world.seed + 101);
                float rnd = ((h >>> 8) & 0xFFFF) / 65536f;
                if (lu == LandUse.FOREST) {
                    if (rnd < 0.85f) {
                        float jx = lx + (Mth.rand01((int) wx, 1, 3) - 0.5f) * step;
                        float jz = lz + (Mth.rand01((int) wz, 2, 5) - 0.5f) * step;
                        mesh.tree(jx, world.heightAt(ox + jx, oz + jz), jz,
                                9f + rnd * 9f, 3.4f + rnd * 2.2f, h);
                        g.treeCount++;
                    }
                } else if (lu == LandUse.MANGROVE) {
                    if (rnd < 0.7f) {
                        mesh.tree(lx, ground, lz, 3.5f + rnd * 2.5f, 2.6f, h);
                        g.treeCount++;
                    }
                } else if (lu == LandUse.PARK || lu == LandUse.OPEN) {
                    if (rnd < 0.28f) {
                        mesh.tree(lx, ground, lz, 7f + rnd * 6f, 3f, h);
                        g.treeCount++;
                    }
                } else if (LandUse.isUrban(lu)) {
                    double rd = nearRoadDistance(wx, wz);
                    if (rd > 0 && rd < 7 && rnd < 0.35f) {
                        // street tree / lamp post along the kerb
                        if (rnd < 0.18f) {
                            mesh.tree(lx, ground, lz, 6f + rnd * 5f, 2.4f, h);
                            g.treeCount++;
                        } else {
                            mesh.cylinder(lx, ground, lz, 0.13f, 7.5f, 4, 0.4f, 0.4f, 0.42f, 0f);
                            mesh.box(lx, ground + 7.5f, lz, 0.7f, 0.25f, 0.3f,
                                    0.9f, 0.85f, 0.6f, 0.9f, 0.85f, 0.6f, 1.0f, true);
                        }
                    }
                } else if (lu == LandUse.BEACH && rnd < 0.08f) {
                    mesh.tree(lx, ground, lz, 8f + rnd * 20f, 2.8f, h);
                    g.treeCount++;
                }
            }
        }
    }

    /** Minimal growable float list (no boxing, no java.util overhead on worker threads). */
    public static final class FloatList {
        public float[] data;
        public int size;

        public FloatList(int cap) {
            data = new float[Math.max(6, cap)];
        }

        public void add(float a, float b, float c, float d, float e, float f) {
            if (size + 6 > data.length) {
                float[] n = new float[data.length * 2 + 6];
                System.arraycopy(data, 0, n, 0, size);
                data = n;
            }
            data[size++] = a;
            data[size++] = b;
            data[size++] = c;
            data[size++] = d;
            data[size++] = e;
            data[size++] = f;
        }

        public float[] trimmed() {
            float[] out = new float[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }
}
