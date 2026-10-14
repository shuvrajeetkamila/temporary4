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

    /**
     * Emit a triangle wound to agree with the normal its vertices carry.
     *
     * <p>Same correction as {@code ChunkMesher.triN}, and needed for the
     * same reason: the renderer culls back faces, so a triangle wound
     * against its own normal is drawn from the side nobody is looking
     * from. A third of every prop in this file was in that state - two
     * faces of every box - which means every vehicle, every pedestrian,
     * every ship and train was partly inside out. The blob shadows were
     * worse: every one of their triangles faced down, so the only thing
     * that could see them was the ground.
     *
     * <p>Working the order out by hand per primitive is how it went wrong
     * in the first place. This derives it.
     */
    private static void triN(int a, int b, int c) {
        int oa = a * ChunkMesher.STRIDE;
        int ob = b * ChunkMesher.STRIDE;
        int oc = c * ChunkMesher.STRIDE;
        double ux = V.get(ob) - V.get(oa);
        double uy = V.get(ob + 1) - V.get(oa + 1);
        double uz = V.get(ob + 2) - V.get(oa + 2);
        double vx = V.get(oc) - V.get(oa);
        double vy = V.get(oc + 1) - V.get(oa + 1);
        double vz = V.get(oc + 2) - V.get(oa + 2);
        double gx = uy * vz - uz * vy;
        double gy = uz * vx - ux * vz;
        double gz = ux * vy - uy * vx;
        double d = gx * V.get(oa + 3) + gy * V.get(oa + 4) + gz * V.get(oa + 5);
        if (d < 0) {
            I.add(a); I.add(c); I.add(b);
        } else {
            I.add(a); I.add(b); I.add(c);
        }
    }

    /**
     * Limb tag written into the texV slot while a body is being built:
     * 0 static, {@link #LIMB_ARM}, {@link #LIMB_LEG}. Negative means "not
     * tagging", and the slot keeps its ordinary height value.
     */
    private static float limbTag = -1;

    /** Swings about the shoulder. */
    static final float LIMB_ARM = 1;

    /** Swings about the hip. */
    static final float LIMB_LEG = 2;

    private static int vert(float x, float y, float z, float nx, float ny, float nz,
                            float r, float g, float b, float aux) {
        int index = V.size() / ChunkMesher.STRIDE;
        V.add(x); V.add(y); V.add(z);
        V.add(nx); V.add(ny); V.add(nz);
        V.add(r); V.add(g); V.add(b);
        V.add(aux);
        // Vertical texture coordinate. Props are small and the instanced
        // shader derives its own, but the slot has to be there or every
        // vertex after the first is read at the wrong offset. On a
        // pedestrian the same slot carries the limb tag instead, which is
        // what lets the walk cycle move an arm without moving the torso.
        V.add(limbTag < 0 ? y : limbTag);
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
            triN(a, bb, c);
            triN(a, c, d);
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

    /** Transit bodies, indexed by the kinds in TRANSIT_KINDS. */
    /** Transit bodies: train, metro, monorail, vessel. */
    static final int TRANSIT_COUNT = 4;

    private static final float[][] TR_V = new float[TRANSIT_COUNT][];
    private static final int[][] TR_I = new int[4][];

    /** Mesh slots: 0 suburban carriage, 1 metro, 2 monorail, 3 vessel. */
    static final int TR_RAIL = 0, TR_METRO = 1, TR_MONO = 2, TR_SHIP = 3;

    static float[] transitVertices(int slot) { buildTransit(slot); return TR_V[slot]; }

    static int[] transitIndices(int slot) { buildTransit(slot); return TR_I[slot]; }

    private static void buildTransit(int slot) {
        if (TR_V[slot] != null) { return; }
        reset();
        switch (slot) {
            case TR_METRO: carriage(1.55f, 1.85f, 0.26f, 0.30f, 0.36f, true); break;
            case TR_MONO: carriage(1.30f, 1.70f, 0.78f, 0.80f, 0.84f, true); break;
            case TR_SHIP: vessel(); break;
            default: carriage(1.62f, 2.00f, 0.52f, 0.30f, 0.26f, false); break;
        }
        TR_V[slot] = packVertices();
        TR_I[slot] = packIndices();
    }

    /**
     * One carriage. Half-width and half-height in metres; the length comes
     * from Transit.CAR_LEN so the drawn train matches the simulated one.
     *
     * <p>Windows are a band of emissive strip rather than modelled glass:
     * at the distance a train is usually seen it is the lit line along the
     * side that identifies it, and a row of quads costs nothing.
     */
    private static void carriage(float hw, float hh, float r, float g, float b,
                                 boolean flatRoof) {
        float hl = 20.5f * 0.5f;
        box(0, hh, 0, hw, hh, hl, r, g, b, 0);
        if (!flatRoof) {
            box(0, hh * 2 + 0.16f, 0, hw * 0.72f, 0.16f, hl * 0.96f,
                    r * 0.7f, g * 0.7f, b * 0.7f, 0);
        }
        // Lit window strip down both sides, emissive so it reads at night.
        float wy = hh * 1.22f;
        for (int side = -1; side <= 1; side += 2) {
            box(side * (hw + 0.012f), wy, 0, 0.012f, hh * 0.30f, hl * 0.88f,
                    0.98f, 0.95f, 0.80f, 0.90f);
        }
        // Bogies, so it does not float.
        box(0, 0.34f, hl * 0.60f, hw * 0.78f, 0.34f, 1.5f, 0.09f, 0.09f, 0.10f, 0);
        box(0, 0.34f, -hl * 0.60f, hw * 0.78f, 0.34f, 1.5f, 0.09f, 0.09f, 0.10f, 0);
    }

    /** A harbour vessel: hull, deckhouse, a couple of containers. */
    private static void vessel() {
        float hl = 20.5f * 0.5f;
        box(0, 1.5f, 0, 4.2f, 1.5f, hl, 0.22f, 0.26f, 0.32f, 0);
        box(0, 3.4f, 0, 4.0f, 0.4f, hl * 0.94f, 0.52f, 0.50f, 0.46f, 0);
        box(0, 5.0f, -hl * 0.58f, 2.6f, 1.9f, 3.0f, 0.86f, 0.86f, 0.84f, 0);
        // Bridge windows, emissive.
        box(0, 6.1f, -hl * 0.58f - 3.02f, 2.2f, 0.5f, 0.02f,
                0.95f, 0.93f, 0.78f, 0.85f);
        box(-1.9f, 4.6f, hl * 0.25f, 1.7f, 1.3f, 3.4f, 0.70f, 0.32f, 0.22f, 0);
        box(1.9f, 4.6f, hl * 0.25f, 1.7f, 1.3f, 3.4f, 0.24f, 0.44f, 0.62f, 0);
        box(0, 7.6f, -hl * 0.58f, 0.12f, 1.4f, 0.12f, 0.80f, 0.80f, 0.80f, 0);
    }

    private static float[] markerV;
    private static int[] markerI;

    /** Radius of the checkpoint cylinder, metres. Matches the arrival test. */
    static final float MARKER_R = 7.0f;
    static final float MARKER_H = 6.0f;

    /**
     * The checkpoint marker: an open-ended cylinder you drive into.
     *
     * <p>Fully emissive, so it reads at night and in rain, and open at both
     * ends so it never hides the road surface or the car inside it. The
     * aux value is a whole material index plus a fractional emission, the
     * same packing the world geometry uses; material 0 is flat white, so
     * the vertex colour decides the tint.
     */
    /**
     * Pedestrian meshes: four civilian outfits and the khaki.
     *
     * <p>Index {@link ai.shuvrajeetkamila.mumbai.sim.Agents#OUTFIT_POLICE}
     * is the officer, so the simulation and the renderer agree on which
     * one of these is a constable without passing a flag.
     */
    static final int PED_OUTFITS = 5;

    private static final float[][] pedV = new float[PED_OUTFITS][];
    private static final int[][] pedI = new int[PED_OUTFITS][];

    static float[] pedVertices(int outfit) { buildPed(outfit); return pedV[outfit]; }

    static int[] pedIndices(int outfit) { buildPed(outfit); return pedI[outfit]; }

    /**
     * A person.
     *
     * <p>There were seventy of them walking the streets at any moment and
     * not one was ever drawn: the agent simulation ran, they scattered
     * when you drove at them, they were counted in the telemetry, and the
     * pavement was empty. Blocky on purpose - this is the fidelity of the
     * era the brief points at, and at the distance you see a pedestrian
     * from, a head, a torso, two arms and two legs is the whole of it.
     */
    private static void buildPed(int outfit) {
        if (pedV[outfit] != null) { return; }
        reset();
        // Four outfits, unbranded, in colours you actually see on a Mumbai
        // street: a white shirt, a blue shirt, a green kurta, a red sari.
        float[][] tops = {
            {0.88f, 0.88f, 0.86f}, {0.26f, 0.38f, 0.62f},
            {0.22f, 0.46f, 0.32f}, {0.68f, 0.18f, 0.24f},
            // Khaki. No crest, no insignia, no lettering: a colour and a
            // cap, which is as far as this goes.
            {0.60f, 0.55f, 0.36f},
        };
        float[][] legs = {
            {0.22f, 0.24f, 0.30f}, {0.18f, 0.19f, 0.22f},
            {0.34f, 0.30f, 0.24f}, {0.52f, 0.14f, 0.18f},
            {0.54f, 0.49f, 0.32f},
        };
        float[] skin = {0.52f, 0.37f, 0.26f};
        float[] t = tops[outfit];
        float[] l = legs[outfit];

        limbTag = 0;
        // Legs, slightly apart. Total height about 1.7 m.
        limbTag = LIMB_LEG;
        box(-0.09f, 0.38f, 0, 0.07f, 0.38f, 0.08f, l[0], l[1], l[2], 0);
        box(0.09f, 0.38f, 0, 0.07f, 0.38f, 0.08f, l[0], l[1], l[2], 0);
        // Torso.
        limbTag = 0;
        box(0, 1.03f, 0, 0.17f, 0.28f, 0.11f, t[0], t[1], t[2], 0);
        // Arms, and the hands with them: a hand left behind by its arm is
        // worse than no walk cycle at all.
        limbTag = LIMB_ARM;
        box(-0.22f, 1.00f, 0, 0.05f, 0.25f, 0.06f, t[0], t[1], t[2], 0);
        box(0.22f, 1.00f, 0, 0.05f, 0.25f, 0.06f, t[0], t[1], t[2], 0);
        box(-0.22f, 0.70f, 0, 0.05f, 0.06f, 0.05f, skin[0], skin[1], skin[2], 0);
        box(0.22f, 0.70f, 0, 0.05f, 0.06f, 0.05f, skin[0], skin[1], skin[2], 0);
        limbTag = 0;
        // Head.
        box(0, 1.44f, 0, 0.10f, 0.13f, 0.10f, skin[0], skin[1], skin[2], 0);
        // Hair, which is what stops a head reading as a flesh-coloured box.
        box(0, 1.55f, -0.01f, 0.105f, 0.04f, 0.105f, 0.08f, 0.07f, 0.07f, 0);
        if (outfit == ai.shuvrajeetkamila.mumbai.sim.Agents.OUTFIT_POLICE) {
            // Peaked cap and a baton on the belt, so an officer reads as
            // one from across the street.
            box(0, 1.60f, 0, 0.115f, 0.045f, 0.115f, 0.30f, 0.28f, 0.20f, 0);
            box(0, 1.585f, 0.10f, 0.10f, 0.02f, 0.045f, 0.22f, 0.21f, 0.15f, 0);
            box(0.19f, 0.70f, -0.04f, 0.022f, 0.19f, 0.022f,
                    0.26f, 0.20f, 0.14f, 0);
        }

        limbTag = -1;
        pedV[outfit] = packVertices();
        pedI[outfit] = packIndices();
    }

    /** Blob shadow sizes: a person, a car, a bus. */
    static final float[] SHADOW_R = {0.48f, 1.55f, 3.30f};
    static final int SHADOW_COUNT = 3;

    private static final float[][] shadowV = new float[SHADOW_COUNT][];
    private static final int[][] shadowI = new int[SHADOW_COUNT][];

    /**
     * A flat disc in the XZ plane. One mesh per size rather than a scale
     * in the instance, because the instance is (x, y, z, heading) and a
     * disc has no heading to give up.
     */
    static float[] shadowVertices(int size) { buildShadow(size); return shadowV[size]; }

    static int[] shadowIndices(int size) { buildShadow(size); return shadowI[size]; }

    private static void buildShadow(int size) {
        if (shadowV[size] != null) { return; }
        float rad = SHADOW_R[size];
        reset();
        final int sides = 14;
        // Unit radius. The instance scales it, so one mesh serves a scooter
        // and a double-decker. Darkness is carried in the albedo and faded
        // out at the rim so the edge is not a hard circle.
        int centre = vert(0, 0, 0, 0, 1, 0, 0.04f, 0.04f, 0.05f, Shaders.SHADOW_AUX);
        int first = -1;
        int prev = -1;
        for (int i = 0; i <= sides; i++) {
            double a = i * 2 * Math.PI / sides;
            int v = vert((float) Math.cos(a) * rad, 0, (float) Math.sin(a) * rad,
                    0, 1, 0, 0.04f, 0.04f, 0.05f, Shaders.SHADOW_AUX + 0.98f);
            if (i == 0) { first = v; }
            if (prev >= 0) { triN(centre, prev, v); }
            prev = v;
        }
        if (first >= 0 && prev >= 0) { triN(centre, prev, first); }
        shadowV[size] = packVertices();
        shadowI[size] = packIndices();
    }

    static float[] markerVertices() { buildMarker(); return markerV; }

    static int[] markerIndices() { buildMarker(); return markerI; }

    private static void buildMarker() {
        if (markerV != null) { return; }
        reset();
        final int sides = 20;
        // Drawn from both sides: a single ring of quads would vanish as you
        // drove through it, since the inside faces away.
        for (int pass = 0; pass < 2; pass++) {
            float sign = pass == 0 ? 1f : -1f;
            for (int i = 0; i < sides; i++) {
                double a0 = (Math.PI * 2 * i) / sides;
                double a1 = (Math.PI * 2 * (i + 1)) / sides;
                float x0 = (float) (Math.cos(a0) * MARKER_R);
                float z0 = (float) (Math.sin(a0) * MARKER_R);
                float x1 = (float) (Math.cos(a1) * MARKER_R);
                float z1 = (float) (Math.sin(a1) * MARKER_R);
                float nx = (float) (Math.cos((a0 + a1) * 0.5) * sign);
                float nz = (float) (Math.sin((a0 + a1) * 0.5) * sign);
                // Fades out towards the top so it looks like light rather
                // than a solid wall of plastic.
                int a = vert(x0, 0, z0, nx, 0, nz, 1f, 0.80f, 0.16f, 0.95f);
                int b = vert(x1, 0, z1, nx, 0, nz, 1f, 0.80f, 0.16f, 0.95f);
                int c = vert(x1, MARKER_H, z1, nx, 0, nz, 1f, 0.62f, 0.10f, 0.35f);
                int dd = vert(x0, MARKER_H, z0, nx, 0, nz, 1f, 0.62f, 0.10f, 0.35f);
                if (pass == 0) {
                    triN(a, b, c);
                    triN(a, c, dd);
                } else {
                    triN(a, c, b);
                    triN(a, dd, c);
                }
            }
        }
        markerV = packVertices();
        markerI = packIndices();
    }

    // ----------------------------------------------------------------- craft

    /** Mesh slots for the things the player can board that are not cars. */
    static final int CRAFT_BOAT = 0, CRAFT_HELI = 1, CRAFT_PLANE = 2;
    static final int CRAFT_COUNT = 3;

    private static final float[][] CR_V = new float[CRAFT_COUNT][];
    private static final int[][] CR_I = new int[CRAFT_COUNT][];

    static float[] craftVertices(int slot) { buildCraft(slot); return CR_V[slot]; }

    static int[] craftIndices(int slot) { buildCraft(slot); return CR_I[slot]; }

    private static void buildCraft(int slot) {
        if (CR_V[slot] != null) { return; }
        reset();
        switch (slot) {
            case CRAFT_HELI: helicopter(); break;
            case CRAFT_PLANE: aeroplane(); break;
            default: launch(); break;
        }
        CR_V[slot] = packVertices();
        CR_I[slot] = packIndices();
    }

    /**
     * A harbour launch: the open wooden passenger boat that works the
     * Gateway, Ferry Wharf and the creek crossings. Nine metres and change,
     * which is what BoatPhysics.ferry() is solving for.
     *
     * <p>Origin at the waterline, bow towards +Z, which is the forward
     * direction the physics uses.
     */
    private static void launch() {
        float hl = 4.7f, hw = 1.55f;
        // Hull: a shallow box with the forefoot cut back, so the bow has a
        // shape rather than being a brick.
        box(0, -0.25f, -0.4f, hw, 0.70f, hl * 0.86f, 0.30f, 0.26f, 0.22f, 0);
        box(0, -0.18f, hl * 0.80f, hw * 0.55f, 0.58f, hl * 0.22f,
                0.30f, 0.26f, 0.22f, 0);
        // Gunwale and a painted strake, the way these boats actually look.
        box(0, 0.30f, 0, hw + 0.04f, 0.14f, hl * 0.92f, 0.78f, 0.24f, 0.16f, 0);
        // Thwarts.
        for (int i = -2; i <= 2; i++) {
            box(0, 0.26f, i * 1.5f, hw * 0.92f, 0.06f, 0.18f,
                    0.62f, 0.50f, 0.34f, 0);
        }
        // Canopy on four posts, which is the silhouette you recognise.
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                box(sx * hw * 0.82f, 0.95f, sz * hl * 0.52f, 0.06f, 0.65f, 0.06f,
                        0.42f, 0.38f, 0.32f, 0);
            }
        }
        box(0, 1.66f, 0, hw * 0.95f, 0.06f, hl * 0.60f, 0.86f, 0.84f, 0.78f, 0);
        // Helm and an outboard well aft.
        box(0, 0.72f, -hl * 0.62f, 0.45f, 0.42f, 0.30f, 0.50f, 0.46f, 0.40f, 0);
        box(0, 0.10f, -hl * 0.94f, 0.26f, 0.46f, 0.30f, 0.14f, 0.14f, 0.16f, 0);
        // Navigation lights, emissive so she is visible on the water at night.
        box(-hw, 0.46f, hl * 0.74f, 0.07f, 0.07f, 0.07f, 0.90f, 0.12f, 0.10f, 0.95f);
        box(hw, 0.46f, hl * 0.74f, 0.07f, 0.07f, 0.07f, 0.12f, 0.85f, 0.22f, 0.95f);
    }

    /**
     * A light utility helicopter: cabin, tail boom, skids, main and tail
     * rotor discs. The main rotor is drawn as four blades at rest rather
     * than a blurred disc, because a parked machine is what you mostly see
     * and the spinning one is only ever a few frames old.
     *
     * <p>Rotor radius 5.35 m, matching HelicopterPhysics.
     */
    private static void helicopter() {
        float r = 5.35f;
        // Cabin.
        box(0, 1.55f, 0.55f, 1.05f, 0.95f, 1.85f, 0.86f, 0.84f, 0.80f, 0);
        // Glazed nose, emissive only very weakly: it is glass, not a lamp.
        box(0, 1.45f, 2.45f, 0.82f, 0.72f, 0.65f, 0.30f, 0.42f, 0.52f, 0.10f);
        // Tail boom and fin.
        box(0, 1.85f, -3.30f, 0.26f, 0.26f, 2.10f, 0.80f, 0.78f, 0.74f, 0);
        box(0, 2.45f, -5.10f, 0.08f, 0.62f, 0.55f, 0.74f, 0.20f, 0.16f, 0);
        box(0, 1.90f, -5.25f, 0.55f, 0.06f, 0.40f, 0.80f, 0.78f, 0.74f, 0);
        // Skids.
        for (int sx = -1; sx <= 1; sx += 2) {
            box(sx * 1.05f, 0.12f, 0.40f, 0.07f, 0.12f, 2.05f, 0.24f, 0.24f, 0.26f, 0);
            box(sx * 0.62f, 0.62f, 1.25f, 0.06f, 0.50f, 0.06f, 0.24f, 0.24f, 0.26f, 0);
            box(sx * 0.62f, 0.62f, -0.55f, 0.06f, 0.50f, 0.06f, 0.24f, 0.24f, 0.26f, 0);
        }
        // Mast and head.
        box(0, 2.75f, 0.40f, 0.14f, 0.45f, 0.14f, 0.40f, 0.40f, 0.42f, 0);
        // Four main blades.
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2.0 + 0.3;
            float ux = (float) Math.cos(a), uz = (float) Math.sin(a);
            bladeAt(0.40f, 3.18f, ux, uz, r, 0.19f);
        }
        // Tail rotor: two blades in the vertical plane.
        for (int i = -1; i <= 1; i += 2) {
            box(0.30f, 1.90f + i * 0.52f, -5.25f, 0.03f, 0.52f, 0.11f,
                    0.26f, 0.26f, 0.28f, 0);
        }
        // Anti-collision beacon.
        box(0, 2.58f, -1.20f, 0.09f, 0.09f, 0.09f, 0.90f, 0.14f, 0.10f, 0.95f);
    }

    /** One rotor blade laid out along a unit direction in the XZ plane. */
    private static void bladeAt(float cz, float y, float ux, float uz,
                                float len, float chord) {
        float half = len * 0.5f;
        float mx = ux * half, mz = cz + uz * half;
        // Axis-aligned bounding box of the blade. Honest at this size: a
        // blade is a thin plank and the renderer has no per-prop rotation.
        float hx = Math.max(chord, Math.abs(ux) * half);
        float hz = Math.max(chord, Math.abs(uz) * half);
        box(mx, y, mz, hx, 0.045f, hz, 0.22f, 0.22f, 0.24f, 0);
    }

    /**
     * A single-engine light aeroplane, high wing, fixed undercarriage:
     * 11.4 m span to match AeroplanePhysics, nose at +Z.
     */
    private static void aeroplane() {
        float span = 11.4f;
        // Fuselage in two boxes so the tail tapers.
        box(0, 1.35f, 0.45f, 0.58f, 0.62f, 2.30f, 0.90f, 0.90f, 0.92f, 0);
        box(0, 1.42f, -2.60f, 0.34f, 0.40f, 2.40f, 0.90f, 0.90f, 0.92f, 0);
        // Cabin glass.
        box(0, 1.98f, 1.00f, 0.52f, 0.34f, 1.10f, 0.32f, 0.44f, 0.54f, 0.12f);
        // High wing with a blue cheat line, and struts down to the belly.
        box(0, 2.18f, 0.35f, span * 0.5f, 0.11f, 0.78f, 0.93f, 0.93f, 0.95f, 0);
        box(0, 2.06f, 0.35f, span * 0.5f, 0.03f, 0.22f, 0.16f, 0.34f, 0.62f, 0);
        for (int sx = -1; sx <= 1; sx += 2) {
            box(sx * 1.85f, 1.60f, 0.35f, 0.05f, 0.55f, 0.08f,
                    0.70f, 0.70f, 0.72f, 0);
        }
        // Tailplane and fin.
        box(0, 1.55f, -4.75f, 1.85f, 0.08f, 0.52f, 0.93f, 0.93f, 0.95f, 0);
        box(0, 2.25f, -4.70f, 0.07f, 0.78f, 0.60f, 0.16f, 0.34f, 0.62f, 0);
        // Cowling, spinner, and a two-blade propeller at rest.
        box(0, 1.35f, 2.95f, 0.50f, 0.50f, 0.42f, 0.88f, 0.88f, 0.90f, 0);
        box(0, 1.35f, 3.42f, 0.16f, 0.16f, 0.10f, 0.30f, 0.30f, 0.32f, 0);
        box(0, 1.35f, 3.46f, 0.09f, 0.92f, 0.03f, 0.22f, 0.22f, 0.24f, 0);
        // Undercarriage: two mains and a nosewheel.
        for (int sx = -1; sx <= 1; sx += 2) {
            box(sx * 1.25f, 0.70f, 0.30f, 0.05f, 0.42f, 0.05f, 0.60f, 0.60f, 0.62f, 0);
            box(sx * 1.25f, 0.28f, 0.30f, 0.11f, 0.28f, 0.28f, 0.08f, 0.08f, 0.09f, 0);
        }
        box(0, 0.70f, 2.70f, 0.05f, 0.42f, 0.05f, 0.60f, 0.60f, 0.62f, 0);
        box(0, 0.26f, 2.70f, 0.09f, 0.26f, 0.26f, 0.08f, 0.08f, 0.09f, 0);
        // Navigation lights.
        box(-span * 0.5f, 2.18f, 0.35f, 0.08f, 0.07f, 0.07f, 0.90f, 0.12f, 0.10f, 0.95f);
        box(span * 0.5f, 2.18f, 0.35f, 0.08f, 0.07f, 0.07f, 0.12f, 0.85f, 0.22f, 0.95f);
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
            // Light bar. Blue one side, red the other, marked with the
            // beacon sentinel so the shader flashes them alternately
            // instead of treating them as two painted boxes that happen to
            // glow a little after dark.
            box(-0.22f, h * 0.96f, 0, 0.20f, 0.07f, 0.16f,
                    0.10f, 0.22f, 0.95f, Shaders.BEACON_AUX);
            box(0.22f, h * 0.96f, 0, 0.20f, 0.07f, 0.16f,
                    0.95f, 0.14f, 0.10f, Shaders.BEACON_AUX);
            // The bar they sit on, so the lamps are not floating.
            box(0, h * 0.925f, 0, 0.46f, 0.035f, 0.13f, 0.09f, 0.09f, 0.10f, 0);
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
