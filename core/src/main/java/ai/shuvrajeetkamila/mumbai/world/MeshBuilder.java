package ai.shuvrajeetkamila.mumbai.world;

/**
 * Growable interleaved mesh buffer used by every procedural generator.
 *
 * <p>Vertex layout (10 floats, must match the GLES3 attribute bindings):
 * <pre>
 *   0..2  position (chunk local metres)
 *   3..5  normal
 *   6..8  albedo rgb
 *   9     aux: emissive / window mask strength
 * </pre>
 * All geometry is produced on worker threads, so this class performs no allocation beyond
 * array growth and never touches GL.
 */
public final class MeshBuilder {

    public static final int STRIDE = 10;

    public float[] verts;
    public int[] indices;
    public int vertexCount;
    public int indexCount;

    public MeshBuilder() {
        this(512, 1024);
    }

    public MeshBuilder(int vertCapacity, int idxCapacity) {
        verts = new float[Math.max(16, vertCapacity) * STRIDE];
        indices = new int[Math.max(16, idxCapacity)];
    }

    public void reset() {
        vertexCount = 0;
        indexCount = 0;
    }

    public boolean isEmpty() {
        return indexCount == 0;
    }

    private void ensureVerts(int extra) {
        int need = (vertexCount + extra) * STRIDE;
        if (need > verts.length) {
            int cap = verts.length;
            while (cap < need) {
                cap = cap + (cap >> 1) + STRIDE * 64;
            }
            float[] n = new float[cap];
            System.arraycopy(verts, 0, n, 0, vertexCount * STRIDE);
            verts = n;
        }
    }

    private void ensureIdx(int extra) {
        if (indexCount + extra > indices.length) {
            int cap = indices.length;
            while (cap < indexCount + extra) {
                cap = cap + (cap >> 1) + 256;
            }
            int[] n = new int[cap];
            System.arraycopy(indices, 0, n, 0, indexCount);
            indices = n;
        }
    }

    public int vertex(float x, float y, float z, float nx, float ny, float nz,
                      float r, float g, float b, float aux) {
        ensureVerts(1);
        int p = vertexCount * STRIDE;
        verts[p] = x;
        verts[p + 1] = y;
        verts[p + 2] = z;
        verts[p + 3] = nx;
        verts[p + 4] = ny;
        verts[p + 5] = nz;
        verts[p + 6] = r;
        verts[p + 7] = g;
        verts[p + 8] = b;
        verts[p + 9] = aux;
        return vertexCount++;
    }

    public void tri(int a, int b, int c) {
        ensureIdx(3);
        indices[indexCount++] = a;
        indices[indexCount++] = b;
        indices[indexCount++] = c;
    }

    public void quad(int a, int b, int c, int d) {
        tri(a, b, c);
        tri(a, c, d);
    }

    /** Flat quad from four corners wound counter-clockwise when seen from the normal side. */
    public void quad(float[] p0, float[] p1, float[] p2, float[] p3,
                     float nx, float ny, float nz, float r, float g, float b, float aux) {
        int a = vertex(p0[0], p0[1], p0[2], nx, ny, nz, r, g, b, aux);
        int bb = vertex(p1[0], p1[1], p1[2], nx, ny, nz, r, g, b, aux);
        int c = vertex(p2[0], p2[1], p2[2], nx, ny, nz, r, g, b, aux);
        int d = vertex(p3[0], p3[1], p3[2], nx, ny, nz, r, g, b, aux);
        quad(a, bb, c, d);
    }

    private final float[] t0 = new float[3];
    private final float[] t1 = new float[3];
    private final float[] t2 = new float[3];
    private final float[] t3 = new float[3];

    public void quadXYZ(float x0, float y0, float z0, float x1, float y1, float z1,
                        float x2, float y2, float z2, float x3, float y3, float z3,
                        float r, float g, float b, float aux) {
        float ux = x1 - x0, uy = y1 - y0, uz = z1 - z0;
        float vx = x3 - x0, vy = y3 - y0, vz = z3 - z0;
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-7f) {
            nx = 0;
            ny = 1;
            nz = 0;
        } else {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        t0[0] = x0; t0[1] = y0; t0[2] = z0;
        t1[0] = x1; t1[1] = y1; t1[2] = z1;
        t2[0] = x2; t2[1] = y2; t2[2] = z2;
        t3[0] = x3; t3[1] = y3; t3[2] = z3;
        quad(t0, t1, t2, t3, nx, ny, nz, r, g, b, aux);
    }

    /**
     * Axis aligned box with per-face shading. Bottom face is skipped (never visible for
     * buildings standing on terrain) unless {@code closed}.
     */
    public void box(float cx, float baseY, float cz, float halfW, float height, float halfD,
                    float r, float g, float b, float roofR, float roofG, float roofB,
                    float windowAux, boolean closed) {
        float x0 = cx - halfW, x1 = cx + halfW;
        float y0 = baseY, y1 = baseY + height;
        float z0 = cz - halfD, z1 = cz + halfD;

        // +Z
        quadXYZ(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, windowAux);
        // -Z
        quadXYZ(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0,
                r * 0.86f, g * 0.86f, b * 0.86f, windowAux);
        // +X
        quadXYZ(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1,
                r * 0.93f, g * 0.93f, b * 0.93f, windowAux);
        // -X
        quadXYZ(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0,
                r * 0.80f, g * 0.80f, b * 0.80f, windowAux);
        // top
        quadXYZ(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, roofR, roofG, roofB, 0f);
        if (closed) {
            quadXYZ(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1,
                    r * 0.5f, g * 0.5f, b * 0.5f, 0f);
        }
    }

    /** Rotated box (yaw around Y), used for buildings aligned to a street. */
    public void boxYaw(float cx, float baseY, float cz, float halfW, float height, float halfD,
                       float yaw, float r, float g, float b,
                       float roofR, float roofG, float roofB, float windowAux) {
        float cs = (float) Math.cos(yaw);
        float sn = (float) Math.sin(yaw);
        float[][] corners = new float[4][2];
        float[][] local = {{-halfW, -halfD}, {halfW, -halfD}, {halfW, halfD}, {-halfW, halfD}};
        for (int i = 0; i < 4; i++) {
            corners[i][0] = cx + local[i][0] * cs - local[i][1] * sn;
            corners[i][1] = cz + local[i][0] * sn + local[i][1] * cs;
        }
        float y0 = baseY;
        float y1 = baseY + height;
        float[] shade = {1.0f, 0.93f, 0.86f, 0.80f};
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            float s = shade[i];
            quadXYZ(corners[i][0], y0, corners[i][1],
                    corners[j][0], y0, corners[j][1],
                    corners[j][0], y1, corners[j][1],
                    corners[i][0], y1, corners[i][1],
                    r * s, g * s, b * s, windowAux);
        }
        quadXYZ(corners[0][0], y1, corners[0][1], corners[1][0], y1, corners[1][1],
                corners[2][0], y1, corners[2][1], corners[3][0], y1, corners[3][1],
                roofR, roofG, roofB, 0f);
    }

    /** Vertical cylinder approximated with {@code sides} faces (towers, pylons, tanks). */
    public void cylinder(float cx, float baseY, float cz, float radius, float height,
                         int sides, float r, float g, float b, float aux) {
        int base = vertexCount;
        for (int i = 0; i < sides; i++) {
            float a0 = (float) (i * 2.0 * Math.PI / sides);
            float a1 = (float) ((i + 1) * 2.0 * Math.PI / sides);
            float x0 = cx + (float) Math.cos(a0) * radius;
            float z0 = cz + (float) Math.sin(a0) * radius;
            float x1 = cx + (float) Math.cos(a1) * radius;
            float z1 = cz + (float) Math.sin(a1) * radius;
            float nx = (float) Math.cos((a0 + a1) * 0.5);
            float nz = (float) Math.sin((a0 + a1) * 0.5);
            int v0 = vertex(x0, baseY, z0, nx, 0, nz, r, g, b, aux);
            int v1 = vertex(x1, baseY, z1, nx, 0, nz, r, g, b, aux);
            int v2 = vertex(x1, baseY + height, z1, nx, 0, nz, r, g, b, aux);
            int v3 = vertex(x0, baseY + height, z0, nx, 0, nz, r, g, b, aux);
            quad(v0, v1, v2, v3);
        }
        // cap
        int centre = vertex(cx, baseY + height, cz, 0, 1, 0, r * 1.05f, g * 1.05f, b * 1.05f, 0f);
        for (int i = 0; i < sides; i++) {
            float a0 = (float) (i * 2.0 * Math.PI / sides);
            float a1 = (float) ((i + 1) * 2.0 * Math.PI / sides);
            int v0 = vertex(cx + (float) Math.cos(a0) * radius, baseY + height,
                    cz + (float) Math.sin(a0) * radius, 0, 1, 0, r, g, b, 0f);
            int v1 = vertex(cx + (float) Math.cos(a1) * radius, baseY + height,
                    cz + (float) Math.sin(a1) * radius, 0, 1, 0, r, g, b, 0f);
            tri(centre, v0, v1);
        }
    }

    /** Low poly dome (shrines, pagoda, tanks). */
    public void dome(float cx, float baseY, float cz, float radius, float height,
                     int sides, int rings, float r, float g, float b) {
        for (int ring = 0; ring < rings; ring++) {
            float t0 = ring / (float) rings;
            float t1 = (ring + 1) / (float) rings;
            float r0 = radius * (float) Math.cos(t0 * Math.PI * 0.5);
            float r1 = radius * (float) Math.cos(t1 * Math.PI * 0.5);
            float y0 = baseY + height * (float) Math.sin(t0 * Math.PI * 0.5);
            float y1 = baseY + height * (float) Math.sin(t1 * Math.PI * 0.5);
            for (int i = 0; i < sides; i++) {
                float a0 = (float) (i * 2.0 * Math.PI / sides);
                float a1 = (float) ((i + 1) * 2.0 * Math.PI / sides);
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
                float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                quadXYZ(cx + c0 * r0, y0, cz + s0 * r0,
                        cx + c1 * r0, y0, cz + s1 * r0,
                        cx + c1 * r1, y1, cz + s1 * r1,
                        cx + c0 * r1, y1, cz + s0 * r1, r, g, b, 0f);
            }
        }
    }

    /** Simple low poly tree: trunk + two canopy prisms. */
    public void tree(float x, float groundY, float z, float height, float spread, int seedHash) {
        float trunkR = Math.max(0.12f, height * 0.045f);
        cylinder(x, groundY, z, trunkR, height * 0.45f, 5, 0.32f, 0.24f, 0.16f, 0f);
        float g1 = 0.18f + ((seedHash >>> 9) & 31) / 255f;
        float canopyY = groundY + height * 0.4f;
        dome(x, canopyY, z, spread, height * 0.62f, 6, 2, 0.14f + g1 * 0.3f, 0.34f + g1, 0.12f + g1 * 0.2f);
    }

    /** Copies the built mesh into exact-size arrays for upload. */
    public float[] vertexArray() {
        float[] out = new float[vertexCount * STRIDE];
        System.arraycopy(verts, 0, out, 0, out.length);
        return out;
    }

    public int[] indexArray() {
        int[] out = new int[indexCount];
        System.arraycopy(indices, 0, out, 0, indexCount);
        return out;
    }

    public int triangleCount() {
        return indexCount / 3;
    }
}
