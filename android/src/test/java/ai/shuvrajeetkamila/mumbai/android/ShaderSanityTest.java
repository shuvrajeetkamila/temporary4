package ai.shuvrajeetkamila.mumbai.android;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the shader sources the way a driver would.
 *
 * <p>These are Java strings concatenated by hand, so nothing in the
 * build checks them: a shader with a missing declaration compiles
 * perfectly as Java and then fails to link on the device, where the only
 * symptom is a black screen or a crash at startup. That happened while
 * editing the sea - a search and replace removed the normal output from
 * the terrain shader instead of the sea's, and nothing noticed until the
 * emulator refused to link it.
 *
 * <p>This is not a GLSL compiler. It checks the specific things that go
 * wrong when these strings are edited: a varying used but not declared,
 * a varying written by the vertex stage with nobody reading it, a
 * fragment stage reading a varying the vertex stage never writes, and
 * uniforms declared with different precision in the two stages of one
 * program, which is a link error on a conforming driver.
 */
final class ShaderSanityTest {

    /** Every program the renderer links, as (name, vertex, fragment). */
    private static String[][] programs() {
        return new String[][]{
            {"scene", Shaders.SCENE_VS, Shaders.SCENE_FS},
            {"instanced", Shaders.INSTANCED_VS, Shaders.SCENE_FS},
            {"sea", Shaders.SEA_VS, Shaders.SEA_FS},
            {"sky", Shaders.SKY_VS, Shaders.SKY_FS},
            {"rain", Shaders.SKY_VS, Shaders.RAIN_FS},
        };
    }

    private static final Pattern DECL = Pattern.compile(
            "(?m)^\\s*(in|out|uniform)\\s+(?:(lowp|mediump|highp)\\s+)?"
                    + "([A-Za-z0-9_]+)\\s+([A-Za-z_][A-Za-z0-9_]*)"
                    + "\\s*(\\[[^\\]]*\\])?\\s*;");

    private static final Pattern LOCAL = Pattern.compile(
            "(?m)^\\s*(?:(?:lowp|mediump|highp)\\s+)?"
                    + "(?:float|int|uint|bool|vec2|vec3|vec4|ivec2|ivec3|ivec4"
                    + "|mat2|mat3|mat4)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /** Names declared at file scope with the given qualifier. */
    private static Set<String> declared(String src, String qualifier) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = DECL.matcher(src);
        while (m.find()) {
            if (m.group(1).equals(qualifier)) { out.add(m.group(4)); }
        }
        return out;
    }

    /** Every identifier that looks like a varying, as used in the body. */
    private static Set<String> usedVaryings(String src) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\bv[A-Z][A-Za-z0-9_]*\\b").matcher(src);
        while (m.find()) { out.add(m.group()); }
        return out;
    }

    @Test
    @DisplayName("every varying a stage uses is declared in that stage")
    void noUndeclaredVaryings() {
        List<String> bad = new ArrayList<>();
        for (String[] p : programs()) {
            for (int stage = 1; stage <= 2; stage++) {
                String src = p[stage];
                Set<String> ok = new LinkedHashSet<>();
                ok.addAll(declared(src, "in"));
                ok.addAll(declared(src, "out"));
                ok.addAll(declared(src, "uniform"));
                Matcher lm = LOCAL.matcher(src);
                while (lm.find()) { ok.add(lm.group(1)); }
                for (String used : usedVaryings(src)) {
                    if (!ok.contains(used)) {
                        bad.add(p[0] + " " + (stage == 1 ? "vertex" : "fragment")
                                + " stage uses " + used + " without declaring it");
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    @DisplayName("nothing is declared twice in one stage")
    void noRedefinitions() {
        // A driver rejects the whole shader for this, and the app dies
        // on its first frame. It happened the moment the wave code was
        // factored into a block shared by both sea stages: the vertex
        // stage already declared uTide, and the shared block declared
        // it again. Nothing in a Java build can see that, because the
        // shader is a string built by concatenation - which is exactly
        // why the check has to read the finished string.
        List<String> bad = new ArrayList<>();
        for (String[] p : programs()) {
            for (int stage = 1; stage <= 2; stage++) {
                String src = p[stage];
                Set<String> seen = new LinkedHashSet<>();
                Matcher m = DECL.matcher(src);
                while (m.find()) {
                    String name = m.group(4);
                    if (!seen.add(name)) {
                        bad.add(p[0] + " "
                                + (stage == 1 ? "vertex" : "fragment")
                                + " stage declares " + name + " twice");
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    @DisplayName("the two stages of a program agree on their varyings")
    void varyingsMatchAcrossTheProgram() {
        List<String> bad = new ArrayList<>();
        for (String[] p : programs()) {
            Set<String> produced = declared(p[1], "out");
            Set<String> consumed = declared(p[2], "in");
            for (String want : consumed) {
                if (!produced.contains(want)) {
                    bad.add(p[0] + ": the fragment stage reads " + want
                            + " but the vertex stage never declares it");
                }
            }
            for (String have : produced) {
                if (!consumed.contains(have) && !have.equals("gl_Position")) {
                    bad.add(p[0] + ": the vertex stage writes " + have
                            + " and nothing reads it");
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    @DisplayName("a uniform has one precision across both stages")
    void uniformPrecisionIsConsistent() {
        // A link error on a conforming driver, and the reason the fog
        // density had to be forced to highp: the same name declared
        // mediump in one stage and highp in the other.
        List<String> bad = new ArrayList<>();
        for (String[] p : programs()) {
            Matcher a = DECL.matcher(p[1]);
            java.util.Map<String, String> vs = new java.util.LinkedHashMap<>();
            while (a.find()) {
                if (a.group(1).equals("uniform")) {
                    vs.put(a.group(4), a.group(2) + " " + a.group(3));
                }
            }
            Matcher b = DECL.matcher(p[2]);
            while (b.find()) {
                if (!b.group(1).equals("uniform")) { continue; }
                String name = b.group(4);
                String here = b.group(2) + " " + b.group(3);
                String there = vs.get(name);
                if (there != null && !there.equals(here)) {
                    bad.add(p[0] + ": uniform " + name + " is " + there
                            + " in the vertex stage and " + here
                            + " in the fragment stage");
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    @DisplayName("every stage declares a version and ends its main")
    void stagesAreWellFormed() {
        for (String[] p : programs()) {
            for (int stage = 1; stage <= 2; stage++) {
                String src = p[stage];
                String what = p[0] + " " + (stage == 1 ? "vertex" : "fragment");
                assertTrue(src.startsWith("#version 300 es\n"),
                        what + " does not open with a version directive");
                assertTrue(src.contains("void main() {"),
                        what + " has no main");
                int open = 0;
                for (int i = 0; i < src.length(); i++) {
                    char c = src.charAt(i);
                    if (c == '{') { open++; }
                    if (c == '}') { open--; }
                }
                assertTrue(open == 0, what + " has unbalanced braces");
            }
        }
    }

    /** The text of waveAt, from its signature to its closing brace. */
    private static String body(String src) {
        int at = src.indexOf("highp float waveAt(");
        int end = src.indexOf("\n}\n", at);
        return src.substring(at, end + 3);
    }

    @Test
    @DisplayName("the shoreline strip is on the same water as the sea")
    void theCoastIsContinuousWithTheSea() {
        // Cells that are all sea are not meshed; cells with any land in
        // them are kept, so the shoreline is drawn by the terrain. Its
        // sea corners sat flat at chart datum while the sea beside them
        // rode the tide and the swell, which at high water left a two
        // metre step and a dark static rim right round the island.
        assertTrue(Shaders.SCENE_VS.contains("highp float waveAt("),
                "the terrain no longer evaluates the wave, so its"
                        + " shoreline strip is back at chart datum while"
                        + " the sea beside it rides the tide");
        assertTrue(Shaders.SCENE_VS.contains("aAux < -0.5"),
                "the terrain no longer singles out its water vertices");
        assertTrue(body(Shaders.SCENE_VS).equals(body(Shaders.SEA_VS)),
                "the terrain and the sea evaluate different waves, so"
                        + " the shoreline and the water beyond it are"
                        + " two different surfaces");
        // And shaded the same, to the number, or the difference draws a
        // line round the island.
        for (String constant : new String[]{"0.030, 0.082, 0.106",
                                            "0.18 + 0.68 * fres",
                                            "220.0"}) {
            assertTrue(Shaders.SCENE_FS.contains(constant)
                            && Shaders.SEA_FS.contains(constant),
                    "the shoreline water and the sea disagree on "
                            + constant);
        }
    }

    @Test
    @DisplayName("the sea's two stages share one wave evaluation")
    void theSeaEvaluatesOneWave() {
        // The surface the vertex stage draws and the slope the fragment
        // stage lights it with have to come from the same function, or
        // the water is lit as though it were a different shape.
        assertTrue(Shaders.SEA_VS.contains("highp float waveAt(")
                        && Shaders.SEA_FS.contains("highp float waveAt("),
                "the sea's stages no longer share the wave function");
        String fn = body(Shaders.SEA_VS);
        assertTrue(Shaders.SEA_FS.contains(fn),
                "the sea's vertex and fragment stages carry different"
                        + " copies of the wave function");
    }
}
