package ai.shuvrajeetkamila.mumbai.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shuvrajeetkamila.mumbai.sim.Agents;
import ai.shuvrajeetkamila.mumbai.world.ChunkMesher;
import org.junit.jupiter.api.Test;

/**
 * The pedestrian walk cycle, which lives in the vertex shader.
 *
 * <p>The shader itself cannot run here, so these tests work on the two
 * halves that can be checked on the CPU: the limb tags baked into the
 * mesh, and a faithful transcription of the swing applied to the real
 * vertex data. The transcription is only worth anything if it stays in
 * step with the GLSL, so {@link #theShaderStillMatchesThisTranscription()}
 * reads the shader source and fails if the constants have moved.
 */
final class WalkCycleTest {

    private static final float ARM_PIVOT = 1.25f;
    private static final float ARM_AMP = -0.38f;
    private static final float LEG_PIVOT = 0.76f;
    private static final float LEG_AMP = 0.52f;
    private static final float BOB = 0.022f;

    /** The shader's swing, on one vertex. Returns {x, y, z}. */
    private static float[] swing(float x, float y, float z, float limb,
                                 double phase, double stride) {
        float[] p = {x, y, z};
        if (stride <= 0.001) { return p; }
        if (limb > 0.5f) {
            boolean leg = limb > 1.5f;
            float pivot = leg ? LEG_PIVOT : ARM_PIVOT;
            float amp = leg ? LEG_AMP : ARM_AMP;
            float side = x < 0 ? -1 : 1;
            double a = Math.sin(phase) * stride * amp * side;
            double dy = y - pivot;
            double ca = Math.cos(a), sa = Math.sin(a);
            p[1] = (float) (pivot + dy * ca - z * sa);
            p[2] = (float) (dy * sa + z * ca);
        }
        p[1] += (float) (BOB * Math.cos(phase * 2) * stride);
        return p;
    }

    private static float at(float[] v, int vertex, int component) {
        return v[vertex * ChunkMesher.STRIDE + component];
    }

    /** The limb tag rides in the texV slot, index 10 of the stride. */
    private static float limb(float[] v, int vertex) {
        return at(v, vertex, 10);
    }

    @Test
    void everyPedestrianVertexIsTagged() {
        for (int o = 0; o < Meshes.PED_OUTFITS; o++) {
            float[] v = Meshes.pedVertices(o);
            int n = v.length / ChunkMesher.STRIDE;
            int arms = 0, legs = 0, still = 0;
            for (int i = 0; i < n; i++) {
                float t = limb(v, i);
                assertTrue(t == 0 || t == Meshes.LIMB_ARM || t == Meshes.LIMB_LEG,
                        "outfit " + o + " vertex " + i + " has limb tag " + t
                                + ", which is not one of 0, 1, 2. An untagged"
                                + " value is a height left in the slot and the"
                                + " shader will read it as a limb.");
                if (t == Meshes.LIMB_ARM) { arms++; } else if (t == Meshes.LIMB_LEG) {
                    legs++;
                } else { still++; }
            }
            // Two arm boxes plus two hands, two leg boxes: 24 vertices each.
            assertEquals(96, arms, "outfit " + o + " arm vertices");
            assertEquals(48, legs, "outfit " + o + " leg vertices");
            assertTrue(still > 0, "outfit " + o + " has no torso");
        }
    }

    @Test
    void armsAndLegsSwingInOppositeDirections() {
        float[] v = Meshes.pedVertices(0);
        int n = v.length / ChunkMesher.STRIDE;
        double phase = Math.PI / 2;
        double arm = 0, leg = 0;
        int armN = 0, legN = 0;
        for (int i = 0; i < n; i++) {
            float t = limb(v, i);
            if (t == 0) { continue; }
            float x = at(v, i, 0), y = at(v, i, 1), z = at(v, i, 2);
            if (x < 0) { continue; }
            float dz = swing(x, y, z, t, phase, 1.0)[2] - z;
            if (t == Meshes.LIMB_ARM) { arm += dz; armN++; } else { leg += dz; legN++; }
        }
        arm /= armN;
        leg /= legN;
        // Same side of the body, same instant: the arm goes one way and
        // the leg the other, which is what walking looks like.
        assertTrue(arm * leg < 0,
                "the right arm and the right leg swung the same way (arm "
                        + arm + ", leg " + leg + "): that is a shuffle");
        assertTrue(Math.abs(arm) > 0.05, "right arm barely moved: " + arm);
        assertTrue(Math.abs(leg) > 0.1, "right leg barely moved: " + leg);
    }

    @Test
    void theTwoSidesAreOutOfPhase() {
        float[] v = Meshes.pedVertices(0);
        int n = v.length / ChunkMesher.STRIDE;
        double left = 0, right = 0;
        for (int i = 0; i < n; i++) {
            if (limb(v, i) != Meshes.LIMB_LEG) { continue; }
            float x = at(v, i, 0), y = at(v, i, 1), z = at(v, i, 2);
            if (y > 0.1f) { continue; }
            float dz = swing(x, y, z, Meshes.LIMB_LEG, 0.9, 1.0)[2] - z;
            if (x < 0) { left += dz; } else { right += dz; }
        }
        assertTrue(left * right < 0,
                "both feet moved the same way (left " + left + ", right "
                        + right + "): that is a hop, not a walk");
    }

    @Test
    void theBodyDoesNotComeApart() {
        // The shoulder end of an arm must stay at the shoulder and the hip
        // end of a leg at the hip, or the limb detaches as it swings.
        float[] v = Meshes.pedVertices(0);
        int n = v.length / ChunkMesher.STRIDE;
        for (int i = 0; i < n; i++) {
            float t = limb(v, i);
            if (t == 0) { continue; }
            float x = at(v, i, 0), y = at(v, i, 1), z = at(v, i, 2);
            float pivot = t == Meshes.LIMB_LEG ? LEG_PIVOT : ARM_PIVOT;
            if (Math.abs(y - pivot) > 0.02f) { continue; }
            for (double ph = 0; ph < 6.3; ph += 0.4) {
                float[] p = swing(x, y, z, t, ph, 1.0);
                double moved = Math.hypot(p[1] - y - BOB * Math.cos(ph * 2),
                        p[2] - z);
                assertTrue(moved < 0.09,
                        "a joint vertex moved " + moved + " m away from its"
                                + " socket at phase " + ph);
            }
        }
    }

    @Test
    void standingStillIsStandingStill() {
        float[] v = Meshes.pedVertices(1);
        int n = v.length / ChunkMesher.STRIDE;
        for (int i = 0; i < n; i++) {
            float x = at(v, i, 0), y = at(v, i, 1), z = at(v, i, 2);
            float[] p = swing(x, y, z, limb(v, i), 1.7, 0.0);
            assertEquals(y, p[1], 1e-6f, "a standing pedestrian bobbed");
            assertEquals(z, p[2], 1e-6f, "a standing pedestrian swung");
        }
    }

    @Test
    void feetStayNearTheGround() {
        // A full-stride swing must not drive a foot through the pavement
        // or leave it hanging in the air.
        float[] v = Meshes.pedVertices(2);
        int n = v.length / ChunkMesher.STRIDE;
        double lowest = 0, highest = 0;
        for (int i = 0; i < n; i++) {
            if (limb(v, i) != Meshes.LIMB_LEG) { continue; }
            float x = at(v, i, 0), y = at(v, i, 1), z = at(v, i, 2);
            // The sole only: a vertex at the hip is supposed to be at hip
            // height and says nothing about where the foot went.
            if (y > 0.05f) { continue; }
            for (double ph = 0; ph < 6.3; ph += 0.3) {
                float[] p = swing(x, y, z, Meshes.LIMB_LEG, ph, 1.0);
                lowest = Math.min(lowest, p[1]);
                highest = Math.max(highest, p[1]);
            }
        }
        assertTrue(lowest > -0.08, "a foot went " + (-lowest) + " m underground");
        assertTrue(highest < 0.22, "a foot was lifted " + highest + " m,"
                + " which is a march rather than a walk");
        // And it has to leave the ground at all.
        assertTrue(highest > 0.04, "the feet never left the ground: " + highest);
    }

    @Test
    void theOfficerIsTheLastOutfit() {
        assertEquals(Meshes.PED_OUTFITS - 1, Agents.OUTFIT_POLICE,
                "the simulation and the meshes disagree about which outfit"
                        + " is the khaki");
        float[] cop = Meshes.pedVertices(Agents.OUTFIT_POLICE);
        float[] civ = Meshes.pedVertices(0);
        assertTrue(cop.length > civ.length,
                "the officer has no cap and no baton");
    }

    @Test
    void theShaderStillMatchesThisTranscription() {
        String vs = Shaders.INSTANCED_VS;
        assertTrue(vs.contains("layout(location = 5) in float aLimb"),
                "the shader no longer reads a per-vertex limb tag");
        assertTrue(vs.contains("layout(location = 6) in vec2 aGait"),
                "the shader no longer reads the gait attribute");
        assertTrue(vs.contains("mix(" + ARM_PIVOT + ", " + LEG_PIVOT + ", leg)"),
                "pivots moved in the shader but not in this test");
        assertTrue(vs.contains("mix(" + ARM_AMP + ", " + LEG_AMP + ", leg)"),
                "amplitudes moved in the shader but not in this test");
        assertTrue(vs.contains(BOB + " * cos(aGait.x * 2.0)"),
                "the bob moved in the shader but not in this test");
        assertTrue(vs.contains("if (aGait.y > 0.001)"),
                "the guard is gone: every instanced mesh in the game, not"
                        + " just pedestrians, would now be walking");
    }

    @Test
    void theSeaRefusesToDrawOnLand() {
        // The player's report: the view gradually goes underwater and
        // flat ground develops lines. Both are the sea being drawn over
        // low land at high tide. The mask lookup and the discard are the
        // fix, and this fails if either is removed.
        String fs = Shaders.SEA_FS;
        assertTrue(fs.contains("uniform lowp sampler2D uLandMask"),
                "the sea shader no longer samples the land mask");
        assertTrue(fs.contains("uniform highp vec4 uLandXform"),
                "the sea shader has no mask transform, so it cannot know"
                        + " where it is");
        assertTrue(fs.contains("discard"),
                "the sea no longer discards anything: it will be drawn"
                        + " over the city again at high water");
        int sample = fs.indexOf("texture(uLandMask");
        int shade = fs.indexOf("fragColour =");
        assertTrue(sample > 0 && sample < shade,
                "the mask is sampled after the pixel is shaded, which"
                        + " wastes the work it is supposed to save");
    }

    @Test
    void theSeaHasNoWaveConstantsOfItsOwn() {
        // The shader used to carry its own copy of the wave set under a
        // comment promising it matched Ocean.java. Not one of the twelve
        // numbers matched: the drawn sea ran 93 m swells of 0.62 m while
        // the boats floated on 38 m swells of 0.26 m. The only way that
        // cannot come back is for the shader to have no constants.
        String vs = Shaders.SEA_VS;
        for (String ghost : new String[]{"93.0", "47.0", "0.62", "0.41",
                                         "0.886", "0.629", "WA", "WL", "WS"}) {
            assertTrue(!vs.contains(ghost),
                    "the sea shader still carries its own wave constant \""
                            + ghost + "\" instead of reading Ocean's");
        }
        for (String u : new String[]{"uWaveAmp", "uWaveK", "uWaveDirX",
                                     "uWaveDirZ", "uWavePhase"}) {
            assertTrue(vs.contains("uniform highp vec4 " + u),
                    "the sea shader does not read " + u + " from the model");
        }
    }

    @Test
    void theSeaDoesNotDifferenceHeightsForItsNormal() {
        // Four extra height samples 1.5 m apart, subtracted: two nearly
        // equal numbers, which is where the white noise came from once
        // the phase had grown. The derivative of a sine is a cosine.
        String vs = Shaders.SEA_VS;
        assertTrue(vs.contains("cos(a)"),
                "the sea no longer takes its slope analytically");
        assertTrue(Shaders.SEA_FS.contains("waveAt(vWorld.xz, slope)"),
                "the sea's slope is back on the vertices, where the grid"
                        + " is tens of metres across and the swell is"
                        + " seven - the normal there is noise, and noise"
                        + " through a 220-power specular is white");
        assertTrue(Shaders.SEA_FS.contains("smoothstep(180.0, 1400.0, vDepth)"),
                "the sea no longer fades its ripple out with distance, so"
                        + " waves finer than a pixel will alias");
        assertTrue(!vs.contains("hx / (2.0 * e)"),
                "the sea is back to differencing height samples for its"
                        + " normal");
        assertTrue(vs.contains("mod(dot(p, d), lambda)"),
                "the sea no longer reduces the distance along a wave into"
                        + " one wavelength, so the angle grows without"
                        + " bound across the map");
        assertTrue(vs.contains("clamp(h, -4.0, 4.0)"),
                "the drawn surface is unclamped again: if the arithmetic"
                        + " ever goes wrong, a sea vertex throws a triangle"
                        + " across the whole screen");
        assertTrue(vs.contains("uWorldOffset"),
                "the sea evaluates its waves in render space, so they will"
                        + " slide off the physics after a rebase");
    }
}
