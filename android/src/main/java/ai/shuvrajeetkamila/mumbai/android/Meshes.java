package ai.shuvrajeetkamila.mumbai.android;

import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.world.ChunkMesher;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural meshes for the things that are not map data.
 *
 * <p>Every shape here is generated from numbers in this file. Nothing is
 * imported, traced, or modelled on a real product. There is no badge, no
 * grille pattern and no real livery on anything.
 *
 * <p>The vehicles are built to the dimensions declared in
 * {@link VehicleKind}, so the thing you see is the size of the thing the
 * physics is solving. That matters more than it sounds: a bus drawn at car
 * scale over a 12.4 tonne rigid body looks like a bug in the handling, not
 * a bug in the mesh.
 *
 * <p>What makes these read as Mumbai is silhouette, not detail. At the
 * distance ambient traffic is seen, the eye identifies a vehicle by its
 * outline and its two-tone split - an upright black-and-yellow box with a
 * stubby bonnet, a three-wheeled pod with a canopy, a red double-decker -
 * long before it could resolve a panel line. So the budget goes on getting
 * proportions and colour blocking right and nothing at all on fine detail.
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

    /** Per-kind cache. Built on first use, on the GL thread, once. */
    private static final float[][] VEH_V = new float[VehicleKind.values().length][];
    private static final int[][] VEH_I = new int[VehicleKind.values().length][];

    private static float[] carV;
    private static int[] carI;

    /** Rolling stock dimensions come straight from the physics description. */
    static float[] vehicleVertices(VehicleKind k) {
        buildVehicle(k);
        return VEH_V[k.ordinal()];
    }

    static int[] vehicleIndices(VehicleKind k) {
        buildVehicle(k);
        return VEH_I[k.ordinal()];
    }

    private static void buildVehicle(VehicleKind k) {
        int o = k.ordinal();
        if (VEH_V[o] != null) { return; }
        reset();
        switch (k) {
            case AUTORICKSHAW: autorickshaw(k); break;
            case DOUBLE_DECKER: bus(k, true); break;
            case CITY_BUS: bus(k, false); break;
            case TEMPO: tempo(k); break;
            case SCOOTER: scooter(k); break;
            default: saloon(k); break;
        }
        VEH_V[o] = packVertices();
        VEH_I[o] = packIndices();
    }

    /** Wheels as blocks: at traffic distance a cylinder is not worth it. */
    private static void wheel(float x, float y, float z, float r, float w) {
        box(x, y, z, w, r, r, 0.07f, 0.07f, 0.08f, 0);
    }

    private static void headlamps(float hx, float y, float z) {
        box(-hx, y, z, 0.15f, 0.08f, 0.03f, 0.98f, 0.95f, 0.82f, 0.90f);
        box(hx, y, z, 0.15f, 0.08f, 0.03f, 0.98f, 0.95f, 0.82f, 0.90f);
    }

    private static void taillamps(float hx, float y, float z) {
        box(-hx, y, z, 0.14f, 0.07f, 0.03f, 0.80f, 0.10f, 0.08f, 0.70f);
        box(hx, y, z, 0.14f, 0.07f, 0.03f, 0.80f, 0.10f, 0.08f, 0.70f);
    }

    /**
     * Three-box car. The kaali-peeli, the private cars and the police
     * interceptor are all this shape at different sizes and colours; the
     * taxi reads as a taxi because it is short, tall and black under yellow,
     * which is exactly how it reads down a real street.
     */
    private static void saloon(VehicleKind k) {
        float hl = (float) k.lengthM * 0.5f;
        float hw = (float) k.widthM * 0.5f;
        float h = (float) k.heightM;
        float wr = h * 0.21f;
        boolean taxi = k == VehicleKind.KAALI_PEELI;
        // Upright older bodies sit their cabin further forward and higher.
        float cabinY = taxi ? h * 0.70f : h * 0.72f;
        float cabinHalfH = taxi ? h * 0.25f : h * 0.21f;

        // Lower body.
        box(0, h * 0.40f, 0, hw, h * 0.21f, hl * 0.97f, k.pr, k.pg, k.pb, 0);
        // Cabin, in the secondary colour. On the taxi that is the yellow
        // roof; on a private car the two colours are equal and it vanishes.
        box(0, cabinY, -hl * 0.06f, hw * 0.88f, cabinHalfH, hl * 0.52f,
                k.sr, k.sg, k.sb, 0);
        // Glass band, dark and faintly reflective.
        box(0, cabinY + cabinHalfH * 0.18f, hl * 0.44f,
                hw * 0.82f, cabinHalfH * 0.52f, 0.05f, 0.18f, 0.24f, 0.30f, 0.10f);
        box(0, cabinY + cabinHalfH * 0.18f, -hl * 0.58f,
                hw * 0.82f, cabinHalfH * 0.52f, 0.05f, 0.18f, 0.24f, 0.30f, 0.10f);

        float ax = hw * 0.90f;
        wheel(-ax, wr, hl * 0.62f, wr, 0.10f);
        wheel(ax, wr, hl * 0.62f, wr, 0.10f);
        wheel(-ax, wr, -hl * 0.62f, wr, 0.10f);
        wheel(ax, wr, -hl * 0.62f, wr, 0.10f);

        headlamps(hw * 0.66f, h * 0.46f, hl * 0.99f);
        taillamps(hw * 0.66f, h * 0.48f, -hl * 0.99f);

        if (k == VehicleKind.POLICE) {
            // Light bar. Blue and red, emissive, unmistakable in a mirror.
            box(-0.22f, h * 0.96f, 0, 0.20f, 0.07f, 0.16f, 0.10f, 0.22f, 0.95f, 0.95f);
            box(0.22f, h * 0.96f, 0, 0.20f, 0.07f, 0.16f, 0.95f, 0.14f, 0.10f, 0.95f);
            // A plain stripe down each flank. No crest, no insignia, no text.
            box(-hw - 0.01f, h * 0.40f, 0, 0.01f, h * 0.09f, hl * 0.80f,
                    k.sr, k.sg, k.sb, 0);
            box(hw + 0.01f, h * 0.40f, 0, 0.01f, h * 0.09f, hl * 0.80f,
                    k.sr, k.sg, k.sb, 0);
        }
        if (k == VehicleKind.KAALI_PEELI) {
            // Roof sign box, blank.
            box(0, h * 0.99f, hl * 0.10f, 0.26f, 0.09f, 0.10f,
                    0.92f, 0.90f, 0.86f, 0.30f);
        }
    }

    /**
     * Autorickshaw: a pod on three wheels under a stretched canopy, open at
     * the sides. The open sides are the whole silhouette, so they are real
     * geometry - a solid box would read as a tiny van.
     */
    private static void autorickshaw(VehicleKind k) {
        float hl = (float) k.lengthM * 0.5f;
        float hw = (float) k.widthM * 0.5f;
        float h = (float) k.heightM;
        float wr = 0.21f;

        // Lower pod.
        box(0, 0.40f, -hl * 0.12f, hw * 0.92f, 0.30f, hl * 0.74f, k.pr, k.pg, k.pb, 0);
        // Front cowl, tapering over the single wheel.
        box(0, 0.58f, hl * 0.66f, hw * 0.46f, 0.30f, hl * 0.26f, k.pr, k.pg, k.pb, 0);
        // Canopy roof in the yellow.
        box(0, h - 0.06f, -hl * 0.10f, hw * 0.96f, 0.06f, hl * 0.66f,
                k.sr, k.sg, k.sb, 0);
        // Four thin corner posts, leaving the sides genuinely open.
        float px = hw * 0.90f, pz = hl * 0.56f;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                box(px * sx, (h + 0.70f) * 0.5f, pz * sz - hl * 0.10f,
                        0.035f, (h - 0.70f) * 0.5f, 0.035f, 0.20f, 0.20f, 0.20f, 0);
            }
        }
        // Rear panel and bench back.
        box(0, 0.95f, -hl * 0.74f, hw * 0.92f, 0.34f, 0.05f, k.pr, k.pg, k.pb, 0);
        // Windscreen.
        box(0, 1.00f, hl * 0.40f, hw * 0.52f, 0.26f, 0.04f, 0.20f, 0.26f, 0.30f, 0.10f);

        wheel(0, wr, hl * 0.74f, wr, 0.08f);          // single front
        wheel(-hw * 0.88f, wr, -hl * 0.46f, wr, 0.09f);
        wheel(hw * 0.88f, wr, -hl * 0.46f, wr, 0.09f);

        box(0, 0.62f, hl * 0.92f, 0.11f, 0.08f, 0.03f, 0.98f, 0.95f, 0.82f, 0.90f);
        taillamps(hw * 0.60f, 0.72f, -hl * 0.78f);
    }

    /** City bus, optionally with an upper deck. */
    private static void bus(VehicleKind k, boolean doubleDeck) {
        float hl = (float) k.lengthM * 0.5f;
        float hw = (float) k.widthM * 0.5f;
        float h = (float) k.heightM;
        float wr = 0.50f;
        float lowerTop = doubleDeck ? h * 0.52f : h;

        box(0, lowerTop * 0.5f + 0.30f, 0, hw, lowerTop * 0.5f - 0.10f, hl,
                k.pr, k.pg, k.pb, 0);
        // Window band, in the pale secondary.
        box(0, lowerTop * 0.76f, 0, hw + 0.012f, lowerTop * 0.15f, hl * 0.96f,
                0.22f, 0.28f, 0.34f, 0.12f);
        if (doubleDeck) {
            box(0, (lowerTop + h) * 0.5f, -hl * 0.02f, hw, (h - lowerTop) * 0.5f,
                    hl * 0.97f, k.pr, k.pg, k.pb, 0);
            box(0, lowerTop + (h - lowerTop) * 0.56f, 0, hw + 0.012f,
                    (h - lowerTop) * 0.22f, hl * 0.94f, 0.22f, 0.28f, 0.34f, 0.12f);
        }
        // Windscreen.
        box(0, lowerTop * 0.74f, hl * 1.0f, hw * 0.92f, lowerTop * 0.17f, 0.05f,
                0.20f, 0.26f, 0.31f, 0.12f);
        // Blank destination panel. No route number, no operator name.
        box(0, lowerTop * 0.93f, hl * 1.0f, hw * 0.52f, 0.11f, 0.04f,
                0.92f, 0.88f, 0.74f, 0.45f);

        float ax = hw * 0.86f;
        wheel(-ax, wr, hl * 0.66f, wr, 0.13f);
        wheel(ax, wr, hl * 0.66f, wr, 0.13f);
        wheel(-ax, wr, -hl * 0.46f, wr, 0.13f);
        wheel(ax, wr, -hl * 0.46f, wr, 0.13f);
        wheel(-ax, wr, -hl * 0.70f, wr, 0.13f);
        wheel(ax, wr, -hl * 0.70f, wr, 0.13f);

        headlamps(hw * 0.68f, 0.72f, hl * 1.01f);
        taillamps(hw * 0.68f, 0.84f, -hl * 1.01f);
    }

    /** Three-wheeled goods carrier: a cab with an open flatbed behind. */
    private static void tempo(VehicleKind k) {
        float hl = (float) k.lengthM * 0.5f;
        float hw = (float) k.widthM * 0.5f;
        float h = (float) k.heightM;
        float wr = 0.28f;

        box(0, h * 0.52f, hl * 0.50f, hw * 0.92f, h * 0.34f, hl * 0.44f,
                k.pr, k.pg, k.pb, 0);
        box(0, h * 0.80f, hl * 0.90f, hw * 0.72f, h * 0.15f, 0.05f,
                0.20f, 0.26f, 0.31f, 0.12f);
        // Flatbed deck and three low sides.
        box(0, h * 0.34f, -hl * 0.42f, hw, 0.06f, hl * 0.54f, k.sr, k.sg, k.sb, 0);
        box(-hw, h * 0.46f, -hl * 0.42f, 0.04f, 0.18f, hl * 0.54f, k.sr, k.sg, k.sb, 0);
        box(hw, h * 0.46f, -hl * 0.42f, 0.04f, 0.18f, hl * 0.54f, k.sr, k.sg, k.sb, 0);
        box(0, h * 0.46f, -hl * 0.95f, hw, 0.18f, 0.04f, k.sr, k.sg, k.sb, 0);

        wheel(0, wr, hl * 0.86f, wr, 0.09f);
        wheel(-hw * 0.84f, wr, -hl * 0.58f, wr, 0.11f);
        wheel(hw * 0.84f, wr, -hl * 0.58f, wr, 0.11f);
        box(0, h * 0.40f, hl * 0.96f, 0.12f, 0.08f, 0.03f, 0.98f, 0.95f, 0.82f, 0.90f);
    }

    /** Scooter, with a rider: without one it reads as litter in the road. */
    private static void scooter(VehicleKind k) {
        float hl = (float) k.lengthM * 0.5f;
        float hw = (float) k.widthM * 0.5f;
        float wr = 0.19f;

        box(0, 0.36f, -hl * 0.10f, hw * 0.62f, 0.11f, hl * 0.52f, k.pr, k.pg, k.pb, 0);
        box(0, 0.62f, hl * 0.62f, hw * 0.70f, 0.26f, 0.09f, k.pr, k.pg, k.pb, 0);
        box(0, 0.60f, -hl * 0.40f, hw * 0.52f, 0.09f, hl * 0.26f, 0.12f, 0.12f, 0.13f, 0);
        box(0, 0.92f, hl * 0.66f, hw * 0.92f, 0.03f, 0.04f, 0.26f, 0.26f, 0.28f, 0);

        wheel(0, wr, hl * 0.72f, wr, 0.05f);
        wheel(0, wr, -hl * 0.62f, wr, 0.06f);

        // Rider: torso and helmet, no face, no features.
        box(0, 1.00f, -hl * 0.14f, 0.17f, 0.26f, 0.12f, 0.30f, 0.34f, 0.44f, 0);
        box(0, 1.34f, -hl * 0.06f, 0.12f, 0.11f, 0.12f, 0.86f, 0.86f, 0.88f, 0);
        box(0, 0.82f, -hl * 0.46f, 0.15f, 0.10f, 0.14f, 0.24f, 0.26f, 0.32f, 0);

        box(0, 0.80f, hl * 0.76f, 0.07f, 0.06f, 0.03f, 0.98f, 0.95f, 0.82f, 0.90f);
    }

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
