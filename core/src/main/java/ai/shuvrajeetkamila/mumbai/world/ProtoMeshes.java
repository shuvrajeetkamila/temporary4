package ai.shuvrajeetkamila.mumbai.world;

/**
 * Procedural meshes for every actor the player can be or can see.
 *
 * <p>All of it is generated from numbers in this file: boxes, wedges and cylinders
 * assembled into shapes that read as a hatchback, an autorickshaw, a ferry or a light
 * aeroplane without copying any real manufacturer's design, badge or livery. Nothing
 * here is imported, traced or derived from another game's assets.
 *
 * <p>Geometry is built in the physics body frame (+X right, +Y up, +Z forward) with the
 * origin at the centre of mass projected onto the ground plane, so a mesh can be drawn
 * directly with the body's own transform.
 */
public final class ProtoMeshes {

    /** One finished mesh: interleaved vertices in {@link MeshBuilder#STRIDE} floats. */
    public static final class Mesh {
        public float[] verts;
        public int[] indices;
        public int triangles;
        public float radius;
    }

    public static final int CAR_HATCH = 0;
    public static final int CAR_SEDAN = 1;
    public static final int CAR_TAXI = 2;
    public static final int AUTORICKSHAW = 3;
    public static final int BUS = 4;
    public static final int TRUCK = 5;
    public static final int MOTORCYCLE = 6;
    public static final int VEHICLE_KINDS = 7;

    private ProtoMeshes() {
    }

    // ---------------------------------------------------------------- public API

    public static Mesh vehicle(int kind) {
        switch (kind) {
            case CAR_SEDAN:
                return car(4.42f, 1.78f, 1.44f, 0.30f, 0x2E3B4E, false);
            case CAR_TAXI:
                return car(3.90f, 1.62f, 1.56f, 0.26f, 0x1C1C1C, true);
            case AUTORICKSHAW:
                return autorickshaw();
            case BUS:
                return boxVehicle(11.6f, 2.55f, 3.25f, 0xB03A2E, 6, 1.02f);
            case TRUCK:
                return boxVehicle(8.4f, 2.45f, 3.05f, 0x7E6246, 6, 1.02f);
            case MOTORCYCLE:
                return motorcycle();
            default:
                return car(3.82f, 1.66f, 1.50f, 0x3C6E71, false);
        }
    }

    public static Mesh playerCar() {
        return car(4.26f, 1.80f, 1.47f, 0.30f, 0x9C2B2B, false);
    }

    public static Mesh human() {
        MeshBuilder m = new MeshBuilder(96, 160);
        // Head, torso, arms, legs: a readable silhouette, no faces or markings.
        box(m, 0f, 1.56f, 0f, 0.11f, 0.12f, 0.11f, 0x6B4A33, 0f);
        box(m, 0f, 1.18f, 0f, 0.20f, 0.28f, 0.11f, 0x2E5C8A, 0f);
        box(m, -0.26f, 1.16f, 0f, 0.06f, 0.26f, 0.07f, 0x6B4A33, 0f);
        box(m, 0.26f, 1.16f, 0f, 0.06f, 0.26f, 0.07f, 0x6B4A33, 0f);
        box(m, -0.10f, 0.45f, 0f, 0.08f, 0.44f, 0.09f, 0x34404F, 0f);
        box(m, 0.10f, 0.45f, 0f, 0.08f, 0.44f, 0.09f, 0x34404F, 0f);
        return finish(m);
    }

    public static Mesh boat(float length, float beam, float hullHeight, int hullColour,
                            boolean cabin) {
        MeshBuilder m = new MeshBuilder(192, 320);
        float hl = length * 0.5f;
        float hb = beam * 0.5f;
        float r = ((hullColour >> 16) & 0xFF) / 255f;
        float g = ((hullColour >> 8) & 0xFF) / 255f;
        float b = (hullColour & 0xFF) / 255f;

        // Hull: eight stations from a pointed bow to a square transom, with the
        // waterline beam tapering below so the thing looks like it displaces water.
        int sections = 8;
        float[] prevTop = null;
        float[] prevBot = null;
        for (int i = 0; i <= sections; i++) {
            float t = i / (float) sections;
            float z = -hl + t * length;
            float taper = (float) Math.sin(Math.min(1.0, t * 1.22) * Math.PI * 0.5);
            float w = hb * (0.18f + 0.82f * taper);
            float keel = -hullHeight * 0.52f * (0.35f + 0.65f * taper);
            float[] top = {-w, hullHeight * 0.48f, z, w, hullHeight * 0.48f, z};
            float[] bot = {-w * 0.55f, keel, z, w * 0.55f, keel, z};
            if (prevTop != null) {
                // port side
                m.quadXYZ(prevTop[0], prevTop[1], prevTop[2], prevBot[0], prevBot[1], prevBot[2],
                        bot[0], bot[1], bot[2], top[0], top[1], top[2], r, g, b, 0f);
                // starboard side
                m.quadXYZ(top[3], top[4], top[5], bot[3], bot[4], bot[5],
                        prevBot[3], prevBot[4], prevBot[5], prevTop[3], prevTop[4], prevTop[5],
                        r, g, b, 0f);
                // bottom
                m.quadXYZ(prevBot[0], prevBot[1], prevBot[2], prevBot[3], prevBot[4], prevBot[5],
                        bot[3], bot[4], bot[5], bot[0], bot[1], bot[2],
                        r * 0.7f, g * 0.7f, b * 0.7f, 0f);
                // deck
                m.quadXYZ(prevTop[0], prevTop[1], prevTop[2], top[0], top[1], top[2],
                        top[3], top[4], top[5], prevTop[3], prevTop[4], prevTop[5],
                        0.80f, 0.76f, 0.68f, 0f);
            }
            prevTop = top;
            prevBot = bot;
        }
        if (cabin) {
            box(m, 0f, hullHeight * 0.48f + hullHeight * 0.42f, -length * 0.06f,
                    beam * 0.34f, hullHeight * 0.42f, length * 0.16f, 0xE8E4DA, 0.35f);
        }
        return finish(m);
    }

    public static Mesh helicopter() {
        MeshBuilder m = new MeshBuilder(256, 420);
        // Cabin pod, tail boom, fin, skids, rotor disc markers.
        box(m, 0f, 1.35f, 0.30f, 0.90f, 0.78f, 1.70f, 0x1F4E5F, 0.40f);
        box(m, 0f, 1.45f, -2.60f, 0.22f, 0.22f, 2.10f, 0x1F4E5F, 0f);
        box(m, 0f, 2.00f, -4.30f, 0.07f, 0.52f, 0.42f, 0x16323D, 0f);
        box(m, -0.80f, 0.18f, 0.10f, 0.07f, 0.18f, 1.70f, 0x3A3A3A, 0f);
        box(m, 0.80f, 0.18f, 0.10f, 0.07f, 0.18f, 1.70f, 0x3A3A3A, 0f);
        box(m, -0.46f, 0.70f, 0.10f, 0.05f, 0.52f, 0.06f, 0x3A3A3A, 0f);
        box(m, 0.46f, 0.70f, 0.10f, 0.05f, 0.52f, 0.06f, 0x3A3A3A, 0f);
        box(m, 0f, 2.14f, 0.30f, 0.14f, 0.18f, 0.14f, 0x2B2B2B, 0f);
        // Main rotor: four blades, drawn flat so the renderer can spin the whole mesh.
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2.0;
            float dx = (float) Math.cos(a);
            float dz = (float) Math.sin(a);
            blade(m, 0f, 2.30f, 0.30f, dx, dz, 5.3f, 0.26f, 0x2B2B2B);
        }
        for (int i = 0; i < 2; i++) {
            double a = i * Math.PI;
            blade(m, 0.12f, 2.00f, -4.30f, 0f, (float) Math.cos(a), 0.95f, 0.12f, 0x2B2B2B);
        }
        return finish(m);
    }

    public static Mesh aeroplane(float span, float length, boolean twinEngine) {
        MeshBuilder m = new MeshBuilder(320, 520);
        float fusR = Math.max(0.42f, length * 0.055f);
        // Fuselage as a tapered tube.
        tube(m, 0f, fusR * 1.6f, -length * 0.42f, length * 0.86f, fusR, 10, 0xE9E9E4, 0.25f);
        // Wing: thin box with a slight dihedral, split so it reads as two panels.
        float wingY = fusR * 1.2f;
        float halfSpan = span * 0.5f;
        wingPanel(m, -halfSpan, wingY, 0f, -1f, span, length, 0xDCDCD6);
        wingPanel(m, halfSpan, wingY, 0f, 1f, span, length, 0xDCDCD6);
        // Tailplane and fin.
        box(m, 0f, fusR * 1.9f, -length * 0.40f, span * 0.17f, 0.05f, length * 0.07f,
                0xDCDCD6, 0f);
        box(m, 0f, fusR * 1.9f + length * 0.055f, -length * 0.41f, 0.05f, length * 0.075f,
                length * 0.08f, 0xB4453A, 0f);
        // Undercarriage.
        box(m, 0f, 0.22f, length * 0.26f, 0.08f, 0.22f, 0.08f, 0x303030, 0f);
        box(m, -span * 0.10f, 0.22f, -length * 0.02f, 0.09f, 0.22f, 0.09f, 0x303030, 0f);
        box(m, span * 0.10f, 0.22f, -length * 0.02f, 0.09f, 0.22f, 0.09f, 0x303030, 0f);
        if (twinEngine) {
            box(m, -span * 0.26f, wingY - 0.30f, length * 0.02f, 0.46f, 0.40f, 1.70f,
                    0xC8C8C4, 0.2f);
            box(m, span * 0.26f, wingY - 0.30f, length * 0.02f, 0.46f, 0.40f, 1.70f,
                    0xC8C8C4, 0.2f);
        } else {
            box(m, 0f, fusR * 1.5f, length * 0.44f, 0.30f, 0.30f, 0.26f, 0x303030, 0f);
            blade(m, 0f, fusR * 1.5f, length * 0.47f, 0f, 1f, 0.95f, 0.10f, 0x2B2B2B);
            blade(m, 0f, fusR * 1.5f, length * 0.47f, 0f, -1f, 0.95f, 0.10f, 0x2B2B2B);
        }
        return finish(m);
    }

    // ---------------------------------------------------------------- shapes

    private static Mesh car(float length, float width, float height, int bodyColour,
                            boolean twoTone) {
        return car(length, width, height, 0.30f, bodyColour, twoTone);
    }

    private static Mesh car(float length, float width, float height, float wheelR,
                            int bodyColour, boolean twoTone) {
        MeshBuilder m = new MeshBuilder(256, 420);
        float hw = width * 0.5f;
        float hl = length * 0.5f;
        float sill = wheelR * 0.75f;
        float bodyH = height * 0.44f;
        float cabinH = height - bodyH - sill;

        box(m, 0f, sill + bodyH * 0.5f, 0f, hw, bodyH * 0.5f, hl, bodyColour, 0f);
        // Greenhouse: narrower, set back, with a windscreen rake.
        int glass = twoTone ? 0xF2C14E : 0x8FB8C9;
        float cz = -length * 0.06f;
        box(m, 0f, sill + bodyH + cabinH * 0.5f, cz, hw * 0.88f, cabinH * 0.5f,
                hl * 0.52f, twoTone ? 0xF2C14E : bodyColour, 0f);
        // Window band, slightly proud so it catches the light.
        box(m, 0f, sill + bodyH + cabinH * 0.62f, cz, hw * 0.90f, cabinH * 0.26f,
                hl * 0.50f, glass, 0.55f);
        // Bumpers and lights.
        box(m, 0f, sill + bodyH * 0.35f, hl - 0.06f, hw * 0.96f, bodyH * 0.22f, 0.07f,
                0x2A2A2A, 0f);
        box(m, 0f, sill + bodyH * 0.35f, -hl + 0.06f, hw * 0.96f, bodyH * 0.22f, 0.07f,
                0x2A2A2A, 0f);
        box(m, -hw * 0.68f, sill + bodyH * 0.72f, hl - 0.02f, hw * 0.20f, bodyH * 0.16f,
                0.05f, 0xFFF4D0, 0.9f);
        box(m, hw * 0.68f, sill + bodyH * 0.72f, hl - 0.02f, hw * 0.20f, bodyH * 0.16f,
                0.05f, 0xFFF4D0, 0.9f);
        box(m, -hw * 0.68f, sill + bodyH * 0.72f, -hl + 0.02f, hw * 0.20f, bodyH * 0.16f,
                0.05f, 0xC02A28, 0.8f);
        box(m, hw * 0.68f, sill + bodyH * 0.72f, -hl + 0.02f, hw * 0.20f, bodyH * 0.16f,
                0.05f, 0xC02A28, 0.8f);

        float ax = hw - 0.10f;
        float az = hl * 0.62f;
        wheel(m, -ax, wheelR, az, wheelR);
        wheel(m, ax, wheelR, az, wheelR);
        wheel(m, -ax, wheelR, -az, wheelR);
        wheel(m, ax, wheelR, -az, wheelR);
        return finish(m);
    }

    private static Mesh autorickshaw() {
        MeshBuilder m = new MeshBuilder(192, 320);
        // Three wheels, a canopy and an open passenger bench: the shape everyone in
        // the city knows, drawn generically with no operator markings.
        box(m, 0f, 0.56f, -0.10f, 0.62f, 0.34f, 1.05f, 0x1E6B3A, 0f);
        box(m, 0f, 1.16f, -0.26f, 0.64f, 0.30f, 0.86f, 0xF0EFE6, 0.1f);
        box(m, 0f, 1.44f, -0.26f, 0.66f, 0.05f, 0.92f, 0x1E6B3A, 0f);
        box(m, -0.62f, 1.10f, -0.26f, 0.04f, 0.52f, 0.90f, 0x1E6B3A, 0f);
        box(m, 0.62f, 1.10f, -0.26f, 0.04f, 0.52f, 0.90f, 0x1E6B3A, 0f);
        box(m, 0f, 0.92f, 0.82f, 0.30f, 0.28f, 0.22f, 0x2A2A2A, 0f);
        box(m, 0f, 1.08f, 1.00f, 0.16f, 0.12f, 0.08f, 0xFFF4D0, 0.9f);
        wheel(m, 0f, 0.27f, 1.00f, 0.27f);
        wheel(m, -0.58f, 0.27f, -0.62f, 0.27f);
        wheel(m, 0.58f, 0.27f, -0.62f, 0.27f);
        return finish(m);
    }

    private static Mesh motorcycle() {
        MeshBuilder m = new MeshBuilder(96, 160);
        box(m, 0f, 0.62f, 0f, 0.14f, 0.20f, 0.62f, 0x2B4C7E, 0f);
        box(m, 0f, 0.86f, -0.26f, 0.16f, 0.09f, 0.30f, 0x1C1C1C, 0f);
        box(m, 0f, 0.92f, 0.44f, 0.30f, 0.04f, 0.05f, 0x9A9A9A, 0f);
        wheel(m, 0f, 0.31f, 0.66f, 0.31f);
        wheel(m, 0f, 0.31f, -0.62f, 0.31f);
        return finish(m);
    }

    private static Mesh boxVehicle(float length, float width, float height, int colour,
                                   int wheels, float wheelR) {
        MeshBuilder m = new MeshBuilder(192, 320);
        float hw = width * 0.5f;
        float hl = length * 0.5f;
        box(m, 0f, wheelR + (height - wheelR) * 0.5f, 0f, hw, (height - wheelR) * 0.5f, hl,
                colour, 0f);
        box(m, 0f, wheelR + (height - wheelR) * 0.78f, hl * 0.70f, hw * 0.97f,
                (height - wheelR) * 0.16f, hl * 0.26f, 0x8FB8C9, 0.5f);
        for (int i = 0; i < wheels / 2; i++) {
            float z = hl * (0.72f - i * (1.44f / Math.max(1, wheels / 2 - 1)));
            wheel(m, -hw + 0.12f, wheelR * 0.5f, z, wheelR * 0.5f);
            wheel(m, hw - 0.12f, wheelR * 0.5f, z, wheelR * 0.5f);
        }
        return finish(m);
    }

    private static void wheel(MeshBuilder m, float cx, float cy, float cz, float r) {
        int sides = 8;
        float halfW = r * 0.36f;
        float prevY = cy + r;
        float prevZ = cz;
        for (int i = 1; i <= sides; i++) {
            double a = i * 2.0 * Math.PI / sides;
            float y = cy + (float) Math.cos(a) * r;
            float z = cz + (float) Math.sin(a) * r;
            m.quadXYZ(cx - halfW, prevY, prevZ, cx - halfW, y, z,
                    cx + halfW, y, z, cx + halfW, prevY, prevZ, 0.09f, 0.09f, 0.10f, 0f);
            // hub caps
            m.quadXYZ(cx - halfW, cy, cz, cx - halfW, prevY, prevZ,
                    cx - halfW, y, z, cx - halfW, cy, cz, 0.35f, 0.35f, 0.37f, 0f);
            m.quadXYZ(cx + halfW, cy, cz, cx + halfW, y, z,
                    cx + halfW, prevY, prevZ, cx + halfW, cy, cz, 0.35f, 0.35f, 0.37f, 0f);
            prevY = y;
            prevZ = z;
        }
    }

    private static void blade(MeshBuilder m, float cx, float cy, float cz,
                              float dx, float dz, float len, float chord, int colour) {
        float px = -dz * chord * 0.5f;
        float pz = dx * chord * 0.5f;
        float ex = cx + dx * len;
        float ez = cz + dz * len;
        float r = ((colour >> 16) & 0xFF) / 255f;
        float g = ((colour >> 8) & 0xFF) / 255f;
        float b = (colour & 0xFF) / 255f;
        m.quadXYZ(cx + px, cy, cz + pz, cx - px, cy, cz - pz,
                ex - px, cy, ez - pz, ex + px, cy, ez + pz, r, g, b, 0f);
        m.quadXYZ(cx + px, cy, cz + pz, ex + px, cy, ez + pz,
                ex - px, cy, ez - pz, cx - px, cy, cz - pz, r, g, b, 0f);
    }

    private static void wingPanel(MeshBuilder m, float tipX, float rootY, float rootZ,
                                  float side, float span, float length, int colour) {
        float rootChord = length * 0.17f;
        float tipChord = rootChord * 0.62f;
        float tipY = rootY + span * 0.035f;          // dihedral
        float rootX = side * length * 0.045f;
        float r = ((colour >> 16) & 0xFF) / 255f;
        float g = ((colour >> 8) & 0xFF) / 255f;
        float b = (colour & 0xFF) / 255f;
        float t = 0.055f * rootChord;
        // Upper and lower surfaces, leading and trailing edges.
        m.quadXYZ(rootX, rootY + t, rootZ + rootChord * 0.5f,
                tipX, tipY + t, rootZ + tipChord * 0.5f,
                tipX, tipY + t, rootZ - tipChord * 0.5f,
                rootX, rootY + t, rootZ - rootChord * 0.5f, r, g, b, 0f);
        m.quadXYZ(rootX, rootY - t, rootZ - rootChord * 0.5f,
                tipX, tipY - t, rootZ - tipChord * 0.5f,
                tipX, tipY - t, rootZ + tipChord * 0.5f,
                rootX, rootY - t, rootZ + rootChord * 0.5f,
                r * 0.86f, g * 0.86f, b * 0.86f, 0f);
        m.quadXYZ(rootX, rootY - t, rootZ + rootChord * 0.5f,
                tipX, tipY - t, rootZ + tipChord * 0.5f,
                tipX, tipY + t, rootZ + tipChord * 0.5f,
                rootX, rootY + t, rootZ + rootChord * 0.5f, r, g, b, 0f);
        m.quadXYZ(rootX, rootY + t, rootZ - rootChord * 0.5f,
                tipX, tipY + t, rootZ - tipChord * 0.5f,
                tipX, tipY - t, rootZ - tipChord * 0.5f,
                rootX, rootY - t, rootZ - rootChord * 0.5f, r, g, b, 0f);
    }

    private static void tube(MeshBuilder m, float cx, float cy, float z0, float length,
                             float radius, int sides, int colour, float aux) {
        float r = ((colour >> 16) & 0xFF) / 255f;
        float g = ((colour >> 8) & 0xFF) / 255f;
        float b = (colour & 0xFF) / 255f;
        for (int i = 0; i < sides; i++) {
            double a0 = i * 2.0 * Math.PI / sides;
            double a1 = (i + 1) * 2.0 * Math.PI / sides;
            float x0 = cx + (float) Math.cos(a0) * radius;
            float y0 = cy + (float) Math.sin(a0) * radius;
            float x1 = cx + (float) Math.cos(a1) * radius;
            float y1 = cy + (float) Math.sin(a1) * radius;
            // Nose taper at the front, tail taper at the back.
            float nose = 0.45f;
            m.quadXYZ(x0, y0, z0, x1, y1, z0, x1, y1, z0 + length, x0, y0, z0 + length,
                    r, g, b, aux);
            m.quadXYZ(x0 * nose + cx * (1 - nose), y0 * nose + cy * (1 - nose), z0 - radius * 2.2f,
                    x0, y0, z0, x0, y0, z0, x1 * nose + cx * (1 - nose),
                    y1 * nose + cy * (1 - nose), z0 - radius * 2.2f, r, g, b, aux);
            m.quadXYZ(x0, y0, z0 + length, x1, y1, z0 + length,
                    cx, cy, z0 + length + radius * 2.6f, cx, cy, z0 + length + radius * 2.6f,
                    r, g, b, aux);
        }
    }

    private static void box(MeshBuilder m, float cx, float cy, float cz,
                            float hx, float hy, float hz, int colour, float aux) {
        float r = ((colour >> 16) & 0xFF) / 255f;
        float g = ((colour >> 8) & 0xFF) / 255f;
        float b = (colour & 0xFF) / 255f;
        float x0 = cx - hx, x1 = cx + hx;
        float y0 = cy - hy, y1 = cy + hy;
        float z0 = cz - hz, z1 = cz + hz;
        // +Z front, -Z back, +X right, -X left, +Y top, -Y bottom (counter clockwise)
        m.quadXYZ(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, aux);
        m.quadXYZ(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0,
                r * 0.9f, g * 0.9f, b * 0.9f, aux);
        m.quadXYZ(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1,
                r * 0.95f, g * 0.95f, b * 0.95f, aux);
        m.quadXYZ(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0,
                r * 0.95f, g * 0.95f, b * 0.95f, aux);
        m.quadXYZ(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0,
                Math.min(1f, r * 1.08f), Math.min(1f, g * 1.08f), Math.min(1f, b * 1.08f), aux);
        m.quadXYZ(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1,
                r * 0.55f, g * 0.55f, b * 0.55f, aux);
    }

    private static Mesh finish(MeshBuilder m) {
        Mesh mesh = new Mesh();
        mesh.verts = m.vertexArray();
        mesh.indices = m.indexArray();
        mesh.triangles = m.triangleCount();
        float worst = 0f;
        for (int i = 0; i < mesh.verts.length; i += MeshBuilder.STRIDE) {
            float x = mesh.verts[i];
            float y = mesh.verts[i + 1];
            float z = mesh.verts[i + 2];
            worst = Math.max(worst, x * x + y * y + z * z);
        }
        mesh.radius = (float) Math.sqrt(worst);
        return mesh;
    }
}
