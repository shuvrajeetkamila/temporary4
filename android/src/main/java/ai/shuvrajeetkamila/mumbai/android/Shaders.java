package ai.shuvrajeetkamila.mumbai.android;

/**
 * Every GLSL ES 3.00 program the game uses. Kept in one file so the vertex layout
 * (position, normal, albedo, aux) is defined once and cannot drift away from
 * {@code MeshBuilder.STRIDE} on the Java side.
 *
 * <p>The lighting model is deliberately cheap: one directional sun, a hemispheric
 * ambient term, exponential-squared distance fog and a specular lobe on water only.
 * That is what a mid-range phone can afford at 1:1 city scale with a 14 km view.
 *
 * <h2>Precision</h2>
 * Every symbol that crosses the vertex/fragment boundary is qualified <em>explicitly
 * and identically on both sides</em>. GLSL ES makes the vertex stage default to
 * {@code highp} and leaves the fragment default to the shader's own
 * {@code precision} line, so an unqualified shared uniform is a link error on a
 * conforming driver. That exact mistake - {@code uCamera} highp in the water vertex
 * shader and mediump in its fragment shader - is why nothing but the sky rendered on
 * the first emulator run.
 *
 * <p>The choice of precision is not cosmetic either. World coordinates reach
 * +/-45 km, which mediump float (10-bit mantissa) quantises to roughly 45 m, and the
 * fog density at the longest view ring is about 4.5e-5, which is below the smallest
 * normal mediump value and would flush to zero. Positions, distances and fog density
 * are therefore highp; colours, normals and masks stay mediump.
 */
final class Shaders {

    private Shaders() {
    }

    /** Shared lighting helper, injected into the fragment shaders. */
    private static final String LIGHT_LIB =
            "vec3 litSurface(vec3 albedo, vec3 n, vec3 sun, float ambient, float aux) {\n"
                    + "    float ndl = max(dot(n, sun), 0.0);\n"
                    // Hemispheric fill: sky above, warm bounce from the ground below.
                    + "    float sky = n.y * 0.5 + 0.5;\n"
                    + "    vec3 fill = mix(vec3(0.32, 0.29, 0.25), vec3(0.62, 0.70, 0.86), sky);\n"
                    + "    vec3 c = albedo * (fill * ambient + vec3(1.05, 0.98, 0.88) * ndl);\n"
                    + "    if (aux > 0.001) {\n"
                    // Emissive windows and lamps, strongest when the sun is low.
                    + "        float night = clamp(1.0 - sun.y * 2.2, 0.0, 1.0);\n"
                    + "        c += albedo * aux * night * 1.4;\n"
                    + "    }\n"
                    + "    return c;\n"
                    + "}\n";

    /** Varyings of the world/instance programs, declared identically in both stages. */
    private static final String WORLD_VARYINGS_OUT =
            "out mediump vec3 vNormal;\n"
                    + "out mediump vec3 vColour;\n"
                    + "out mediump float vAux;\n"
                    + "out highp float vDist;\n"
                    + "out highp vec3 vWorld;\n";

    private static final String WORLD_VARYINGS_IN =
            "in mediump vec3 vNormal;\n"
                    + "in mediump vec3 vColour;\n"
                    + "in mediump float vAux;\n"
                    + "in highp float vDist;\n"
                    + "in highp vec3 vWorld;\n";

    static final String WORLD_VS =
            "#version 300 es\n"
                    + "precision highp float;\n"
                    + "layout(location = 0) in highp vec3 aPos;\n"
                    + "layout(location = 1) in mediump vec3 aNormal;\n"
                    + "layout(location = 2) in mediump vec3 aColour;\n"
                    + "layout(location = 3) in mediump float aAux;\n"
                    + "uniform highp mat4 uViewProj;\n"
                    + "uniform highp vec3 uOrigin;\n"
                    + "uniform highp vec3 uCamera;\n"
                    + "uniform highp float uTime;\n"
                    + WORLD_VARYINGS_OUT
                    + "void main() {\n"
                    // Chunk-local float positions are lifted into the floating-origin
                    // frame here, never into absolute world metres.
                    + "    highp vec3 p = aPos + uOrigin;\n"
                    + "    vNormal = aNormal;\n"
                    + "    vColour = aColour;\n"
                    + "    vAux = aAux;\n"
                    + "    vWorld = p;\n"
                    + "    vDist = length(p - uCamera);\n"
                    + "    gl_Position = uViewProj * vec4(p, 1.0);\n"
                    + "}\n";

    /**
     * Ambient traffic: the same lit fragment shader, but the model transform is a
     * per-instance position and yaw, so a hundred vehicles cost one draw call.
     */
    static final String INSTANCE_VS =
            "#version 300 es\n"
                    + "precision highp float;\n"
                    + "layout(location = 0) in highp vec3 aPos;\n"
                    + "layout(location = 1) in mediump vec3 aNormal;\n"
                    + "layout(location = 2) in mediump vec3 aColour;\n"
                    + "layout(location = 3) in mediump float aAux;\n"
                    + "layout(location = 4) in highp vec4 aInstance;\n"
                    + "uniform highp mat4 uViewProj;\n"
                    + "uniform highp vec3 uCamera;\n"
                    + WORLD_VARYINGS_OUT
                    + "void main() {\n"
                    + "    highp float s = sin(aInstance.w);\n"
                    + "    highp float c = cos(aInstance.w);\n"
                    // Heading 0 faces north (-Z); rotate about +Y accordingly.
                    + "    highp mat3 rot = mat3(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c);\n"
                    + "    highp vec3 p = rot * aPos + aInstance.xyz;\n"
                    + "    vNormal = rot * aNormal;\n"
                    + "    vColour = aColour;\n"
                    + "    vAux = aAux;\n"
                    + "    vWorld = p;\n"
                    + "    vDist = length(p - uCamera);\n"
                    + "    gl_Position = uViewProj * vec4(p, 1.0);\n"
                    + "}\n";

    static final String WORLD_FS =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + WORLD_VARYINGS_IN
                    + "uniform mediump vec3 uSun;\n"
                    // Fog density is ~4.5e-5 at the longest ring: below mediump's
                    // smallest normal value, so it must be highp or it becomes zero.
                    + "uniform highp float uFogDensity;\n"
                    + "uniform mediump vec3 uFogColour;\n"
                    + "uniform mediump float uAmbient;\n"
                    + "out mediump vec4 fragColour;\n"
                    + LIGHT_LIB
                    + "void main() {\n"
                    + "    mediump vec3 n = normalize(vNormal);\n"
                    + "    mediump vec3 albedo = vColour;\n"
                    + "    if (vAux < -0.5) {\n"
                    // Inland water: flatter, darker, with a sun glint off the surface.
                    + "        mediump float spec = pow(max(dot(reflect(-uSun, n),"
                    + " vec3(0.0, 1.0, 0.0)), 0.0), 24.0);\n"
                    + "        albedo = albedo * 0.85 + vec3(0.9, 0.95, 1.0) * spec * 0.35;\n"
                    + "    }\n"
                    + "    mediump vec3 c = litSurface(albedo, n, uSun, uAmbient, max(vAux, 0.0));\n"
                    + "    highp float f = vDist * uFogDensity;\n"
                    + "    mediump float fog = clamp(1.0 - exp(-f * f), 0.0, 1.0);\n"
                    + "    fragColour = vec4(mix(c, uFogColour, fog), 1.0);\n"
                    + "}\n";

    /**
     * Sea surface. The vertex shader evaluates exactly the wave trains the buoyancy
     * solver uses, with the floating-origin phase folded in on the CPU in double
     * precision, so the visible swell and the physical swell are the same function.
     */
    static final String WATER_VS =
            "#version 300 es\n"
                    + "precision highp float;\n"
                    + "layout(location = 0) in highp vec2 aPos;\n"
                    + "uniform highp mat4 uViewProj;\n"
                    + "uniform highp vec3 uCamera;\n"
                    + "uniform highp vec2 uGridCentre;\n"
                    + "uniform highp float uLevel;\n"
                    + "uniform highp vec4 uWave[4];\n"     // amp, k, dirX, dirZ
                    + "uniform highp float uPhase[4];\n"
                    + "out mediump vec3 vNormal;\n"
                    + "out highp float vDist;\n"
                    + "out highp vec3 vPos;\n"
                    + "void main() {\n"
                    + "    highp vec2 p = uGridCentre + aPos;\n"
                    + "    highp float h = 0.0;\n"
                    + "    highp float dhdx = 0.0;\n"
                    + "    highp float dhdz = 0.0;\n"
                    + "    for (int i = 0; i < 4; i++) {\n"
                    + "        highp float amp = uWave[i].x;\n"
                    + "        highp float k = uWave[i].y;\n"
                    + "        highp vec2 dir = uWave[i].zw;\n"
                    + "        highp float ph = k * dot(p, dir) + uPhase[i];\n"
                    + "        h += amp * sin(ph);\n"
                    + "        highp float d = amp * k * cos(ph);\n"
                    + "        dhdx += d * dir.x;\n"
                    + "        dhdz += d * dir.y;\n"
                    + "    }\n"
                    + "    highp vec3 world = vec3(p.x, uLevel + h, p.y);\n"
                    + "    vNormal = normalize(vec3(-dhdx, 1.0, -dhdz));\n"
                    + "    vPos = world;\n"
                    + "    vDist = length(world - uCamera);\n"
                    + "    gl_Position = uViewProj * vec4(world, 1.0);\n"
                    + "}\n";

    static final String WATER_FS =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "in mediump vec3 vNormal;\n"
                    + "in highp float vDist;\n"
                    + "in highp vec3 vPos;\n"
                    + "uniform mediump vec3 uSun;\n"
                    // Shared with the vertex stage: must be highp on both sides.
                    + "uniform highp vec3 uCamera;\n"
                    + "uniform highp float uFogDensity;\n"
                    + "uniform mediump vec3 uFogColour;\n"
                    + "uniform mediump vec3 uTint;\n"
                    + "out mediump vec4 fragColour;\n"
                    + "void main() {\n"
                    + "    mediump vec3 n = normalize(vNormal);\n"
                    + "    mediump vec3 v = normalize(vec3(uCamera - vPos));\n"
                    // Schlick fresnel: the sea goes mirror-like towards the horizon.
                    + "    mediump float fres = 0.02 + 0.98"
                    + " * pow(1.0 - max(dot(n, v), 0.0), 5.0);\n"
                    + "    mediump float spec = pow(max(dot(reflect(-uSun, n), v), 0.0), 220.0);\n"
                    + "    mediump vec3 c = mix(uTint, uFogColour * 1.06, clamp(fres, 0.0, 1.0));\n"
                    + "    c += vec3(1.0, 0.96, 0.86) * spec * 1.6;\n"
                    + "    highp float f = vDist * uFogDensity;\n"
                    + "    mediump float fog = clamp(1.0 - exp(-f * f), 0.0, 1.0);\n"
                    + "    fragColour = vec4(mix(c, uFogColour, fog), 1.0);\n"
                    + "}\n";

    /** Full-screen triangle sky gradient with a soft sun glow. */
    static final String SKY_VS =
            "#version 300 es\n"
                    + "precision highp float;\n"
                    + "out mediump vec2 vUv;\n"
                    + "void main() {\n"
                    + "    highp vec2 p = vec2(float((gl_VertexID << 1) & 2),"
                    + " float(gl_VertexID & 2));\n"
                    + "    vUv = p;\n"
                    + "    gl_Position = vec4(p * 2.0 - 1.0, 1.0, 1.0);\n"
                    + "}\n";

    static final String SKY_FS =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "in mediump vec2 vUv;\n"
                    + "uniform mediump vec3 uHorizon;\n"
                    + "uniform mediump vec3 uZenith;\n"
                    + "uniform mediump vec3 uSun;\n"
                    + "out mediump vec4 fragColour;\n"
                    + "void main() {\n"
                    + "    mediump float t = clamp(vUv.y, 0.0, 1.0);\n"
                    + "    mediump vec3 c = mix(uHorizon, uZenith, pow(t, 0.75));\n"
                    // A diffuse glow where the sun is, without a hard disc.
                    + "    mediump float glow = pow(clamp(1.0 - abs(t"
                    + " - clamp(uSun.y, 0.0, 1.0)) * 2.4, 0.0, 1.0), 3.0);\n"
                    + "    c += vec3(0.55, 0.42, 0.26) * glow * clamp(uSun.y + 0.25, 0.0, 1.0);\n"
                    + "    fragColour = vec4(c, 1.0);\n"
                    + "}\n";
}
