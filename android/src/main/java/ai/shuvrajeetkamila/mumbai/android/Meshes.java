package ai.shuvrajeetkamila.mumbai.android;

import ai.shuvrajeetkamila.mumbai.world.ChunkMesher;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural meshes for the things that are not map data.
 *
 * <p>Every shape here is generated from numbers in this file. Nothing is
 * imported, traced, or modelled on a real product. The car is a generic
 * three-box saloon with no badge, no grille pattern and no livery, because
 * the brief rules out branded vehicles and because an unbranded box is what
 * ambient traffic needs to be anyway.
 *
 * <p>Vertex layout matches {@link ChunkMesher#STRIDE}: position, normal,
 * albedo, aux.
 */
final class Meshes {

    private static final List<Float> V = new ArrayList<>();
    private static final List<Integer> I = new ArrayList<>();

    private static void reset() {
        V.clear();
        I.clear();
    }

    private static int vert(float x, float y, float z, float nx, float ny, float nz,
                            float r, float g, float b, float aux) {
        int index = V.size() / ChunkMesher.STRIDE;
        V.add(x); V.add(y); V.add(z);
        V.add(nx); V.add(ny); V.add(nz);
        V.add(r); V.add(g); V.add(b);
        V.add(aux);
        return index;
    }

    /** An axis-aligned box. Six quads, each with its own outward normal. */
    private static void box(float cx, float cy, float cz,
                            float hx, float hy, float hz,
                            float r, float g, float b, float aux) {
        float[][] faces = {
            {0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
        };
        for (float[] n : faces) {
            float[] u;
            float[] w;
            if (Math.abs(n[1]) > 0.5f) {
                u = new float[]{1, 0, 0};
                w = new float[]{0, 0, n[1]};
            } else if (Math.abs(n[0]) > 0.5f) {
                u = new float[]{0, 0, -n[0]};
                w = new float[]{0, 1, 0};
            } else {
                u = new float[]{n[2], 0, 0};
                w = new float[]{0, 1, 0};
            }
            float px = cx + n[0] * hx, py = cy + n[1] * hy, pz = cz + n[2] * hz;
            int a = vert(px - u[0] * hx - w[0] * hx, py - u[1] * hy - w[1] * hy,
                    pz - u[2] * hz - w[2] * hz, n[0], n[1], n[2], r, g, b, aux);
            int bb = vert(px + u[0] * hx - w[0] * hx, py + u[1] * hy - w[1] * hy,
                    pz + u[2] * hz - w[2] * hz, n[0], n[1], n[2], r, g, b, aux);
            int c = vert(px + u[0] * hx + w[0] * hx, py + u[1] * hy + w[1] * hy,
                    pz + u[2] * hz + w[2] * hz, n[0], n[1], n[2], r, g, b, aux);
            int d = vert(px - u[0] * hx + w[0] * hx, py - u[1] * hy + w[1] * hy,
                    pz - u[2] * hz + w[2] * hz, n[0], n[1], n[2], r, g, b, aux);
            I.add(a); I.add(bb); I.add(c);
            I.add(a); I.add(c); I.add(d);
        }
    }

    private static float[] packVertices() {
        float[] out = new float[V.size()];
        for (int i = 0; i < out.length; i++) { out[i] = V.get(i); }
        return out;
    }

    private static int[] packIndices() {
        int[] out = new int[I.size()];
        for (int i = 0; i < out.length; i++) { out[i] = I.get(i); }
        return out;
    }

    private static float[] carV;
    private static int[] carI;

    private static void buildCar() {
        if (carV != null) { return; }
        reset();
        // A plain three-box shape, 4.1 m long, 1.72 m wide, 1.45 m tall.
        // Origin on the ground under the centre, +Z forward.
        box(0, 0.62f, 0.05f, 0.86f, 0.30f, 2.05f, 0.62f, 0.64f, 0.68f, 0);
        box(0, 1.10f, -0.12f, 0.74f, 0.26f, 1.05f, 0.30f, 0.34f, 0.40f, 0);
        // Glass, slightly emissive so it catches the eye at distance.
        box(0, 1.12f, 0.92f, 0.70f, 0.20f, 0.06f, 0.22f, 0.30f, 0.36f, 0.08f);
        // Wheels as simple blocks: at ambient-traffic distance a cylinder is
        // not worth the vertices.
        float wr = 0.30f;
        box(-0.78f, wr, 1.32f, 0.10f, wr, wr, 0.08f, 0.08f, 0.09f, 0);
        box(0.78f, wr, 1.32f, 0.10f, wr, wr, 0.08f, 0.08f, 0.09f, 0);
        box(-0.78f, wr, -1.28f, 0.10f, wr, wr, 0.08f, 0.08f, 0.09f, 0);
        box(0.78f, wr, -1.28f, 0.10f, wr, wr, 0.08f, 0.08f, 0.09f, 0);
        // Lamps.
        box(-0.58f, 0.74f, 2.08f, 0.16f, 0.09f, 0.03f, 0.95f, 0.92f, 0.78f, 0.55f);
        box(0.58f, 0.74f, 2.08f, 0.16f, 0.09f, 0.03f, 0.95f, 0.92f, 0.78f, 0.55f);
        box(-0.58f, 0.76f, -2.05f, 0.16f, 0.08f, 0.03f, 0.72f, 0.12f, 0.10f, 0.35f);
        box(0.58f, 0.76f, -2.05f, 0.16f, 0.08f, 0.03f, 0.72f, 0.12f, 0.10f, 0.35f);
        carV = packVertices();
        carI = packIndices();
    }

    static float[] genericCar() {
        buildCar();
        return carV;
    }

    static int[] genericCarIndices() {
        buildCar();
        return carI;
    }

    private Meshes() { }
}
