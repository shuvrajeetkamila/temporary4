package ai.shuvrajeetkamila.mumbai.android;

/**
 * Every GLSL ES 3.00 program the game uses. Kept in one file so the vertex layout
 * (position, normal, albedo, aux) is defined once and cannot drift away from
 * {@code MeshBuilder.STRIDE} on the Java side.
 *
 * <p>The lighting model is deliberately cheap: one directional sun, a hemispheric
 * ambient term, exponential-squared distance fog and a specular lobe on water only.
 * That is what a mid-range phone can afford at 1:1 city scale with a 14 km view.
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

    static final String WORLD_VS =
            "#version 300 es\n"
                    + "layout(location = 0) in vec3 aPos;\n"
                    + "layout(location = 1) in vec3 aNormal;\n"
                    + "layout(location = 2) in vec3 aColour;\n"
                    + "layout(location = 3) in float aAux;\n"
                    + "uniform mat4 uViewProj;\n"
                    + "uniform vec3 uOrigin;\n"
                    + "uniform vec3 uCamera;\n"
                    + "uniform float uTime;\n"
                    + "out vec3 vNormal;\n"
                    + "out vec3 vColour;\n"
                    + "out float vAux;\n"
                    + "out float vDist;\n"
                    + "out vec3 vWorld;\n"
                    + "void main() {\n"
                    + "    vec3 p = aPos + uOrigin;\n"
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
                    + "layout(location = 0) in vec3 aPos;\n"
                    + "layout(location = 1) in vec3 aNormal;\n"
                    + "layout(location = 2) in vec3 aColour;\n"
                    + "layout(location = 3) in float aAux;\n"
                    + "layout(location = 4) in vec4 aInstance;\n"
                    + "uniform mat4 uViewProj;\n"
                    + "uniform vec3 uCamera;\n"
                    + "out vec3 vNormal;\n"
                    + "out vec3 vColour;\n"
                    + "out float vAux;\n"
                    + "out float vDist;\n"
                    + "out vec3 vWorld;\n"
                    + "void main() {\n"
                    + "    float s = sin(aInstance.w);\n"
                    + "    float c = cos(aInstance.w);\n"
                    // Heading 0 faces north (-Z); rotate about +Y accordingly.
                    + "    mat3 rot = mat3(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c);\n"
                    + "    vec3 p = rot * aPos + aInstance.xyz;\n"
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
                    + "in vec3 vNormal;\n"
                    + "in vec3 vColour;\n"
                    + "in float vAux;\n"
                    + "in float vDist;\n"
                    + "in vec3 vWorld;\n"
                    + "uniform vec3 uSun;\n"
                    + "uniform float uFogDensity;\n"
                    + "uniform vec3 uFogColour;\n"
                    + "uniform float uAmbient;\n"
                    + "out vec4 fragColour;\n"
                    + LIGHT_LIB
                    + "void main() {\n"
                    + "    vec3 n = normalize(vNormal);\n"
                    + "    vec3 albedo = vColour;\n"
                    + "    float alpha = 1.0;\n"
                    + "    if (vAux < -0.5) {\n"
                    // Inland water: flat, darker, with a sun glint.
                    + "        vec3 v = normalize(vWorld - vec3(0.0));\n"
                    + "        float spec = pow(max(dot(reflect(-uSun, n), vec3(0.0, 1.0, 0.0)),"
                    + " 0.0), 24.0);\n"
                    + "        albedo = albedo * 0.85 + vec3(0.9, 0.95, 1.0) * spec * 0.35;\n"
                    + "    }\n"
                    + "    vec3 c = litSurface(albedo, n, uSun, uAmbient, max(vAux, 0.0));\n"
                    + "    float f = vDist * uFogDensity;\n"
                    + "    float fog = 1.0 - exp(-f * f);\n"
                    + "    fragColour = vec4(mix(c, uFogColour, clamp(fog, 0.0, 1.0)), alpha);\n"
                    + "}\n";

    /**
     * Sea surface. The vertex shader evaluates exactly the wave trains the buoyancy
     * solver uses, with the floating-origin phase folded in on the CPU in double
     * precision, so the visible swell and the physical swell are the same function.
     */
    static final String WATER_VS =
            "#version 300 es\n"
                    + "layout(location = 0) in vec2 aOffset;\n"
                    + "uniform mat4 uViewProj;\n"
                    + "uniform vec3 uCamera;\n"
                    + "uniform vec2 uGridCentre;\n"
                    + "uniform float uLevel;\n"
                    + "uniform vec4 uWave[4];\n"     // amp, k, dirX, dirZ
                    + "uniform float uPhase[4];\n"
                    + "out vec3 vNormal;\n"
                    + "out float vDist;\n"
                    + "out vec3 vPos;\n"
                    + "void main() {\n"
                    + "    vec2 p = uGridCentre + aOffset;\n"
                    + "    float h = 0.0;\n"
                    + "    float dhdx = 0.0;\n"
                    + "    float dhdz = 0.0;\n"
                    + "    for (int i = 0; i < 4; i++) {\n"
                    + "        float amp = uWave[i].x;\n"
                    + "        float k = uWave[i].y;\n"
                    + "        vec2 dir = uWave[i].zw;\n"
                    + "        float ph = k * dot(p, dir) + uPhase[i];\n"
                    + "        h += amp * sin(ph);\n"
                    + "        float d = amp * k * cos(ph);\n"
                    + "        dhdx += d * dir.x;\n"
                    + "        dhdz += d * dir.y;\n"
                    + "    }\n"
                    + "    vec3 world = vec3(p.x, uLevel + h, p.y);\n"
                    + "    vNormal = normalize(vec3(-dhdx, 1.0, -dhdz));\n"
                    + "    vPos = world;\n"
                    + "    vDist = length(world - uCamera);\n"
                    + "    gl_Position = uViewProj * vec4(world, 1.0);\n"
                    + "}\n";

    static final String WATER_FS =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "in vec3 vNormal;\n"
                    + "in float vDist;\n"
                    + "in vec3 vPos;\n"
                    + "uniform vec3 uSun;\n"
                    + "uniform vec3 uCamera;\n"
                    + "uniform float uFogDensity;\n"
                    + "uniform vec3 uFogColour;\n"
                    + "uniform vec3 uTint;\n"
                    + "out vec4 fragColour;\n"
                    + "void main() {\n"
                    + "    vec3 n = normalize(vNormal);\n"
                    + "    vec3 v = normalize(uCamera - vPos);\n"
                    // Schlick fresnel: the sea goes mirror-like towards the horizon.
                    + "    float fres = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);\n"
                    + "    float spec = pow(max(dot(reflect(-uSun, n), v), 0.0), 220.0);\n"
                    + "    vec3 deep = uTint;\n"
                    + "    vec3 sky = uFogColour * 1.06;\n"
                    + "    vec3 c = mix(deep, sky, clamp(fres, 0.0, 1.0));\n"
                    + "    c += vec3(1.0, 0.96, 0.86) * spec * 1.6;\n"
                    + "    float f = vDist * uFogDensity;\n"
                    + "    float fog = 1.0 - exp(-f * f);\n"
                    + "    fragColour = vec4(mix(c, uFogColour, clamp(fog, 0.0, 1.0)), 1.0);\n"
                    + "}\n";

    /** Full-screen triangle sky gradient with a soft sun disc. */
    static final String SKY_VS =
            "#version 300 es\n"
                    + "out vec2 vUv;\n"
                    + "void main() {\n"
                    + "    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n"
                    + "    vUv = p;\n"
                    + "    gl_Position = vec4(p * 2.0 - 1.0, 1.0, 1.0);\n"
                    + "}\n";

    static final String SKY_FS =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "in vec2 vUv;\n"
                    + "uniform vec3 uHorizon;\n"
                    + "uniform vec3 uZenith;\n"
                    + "uniform vec3 uSun;\n"
                    + "out vec4 fragColour;\n"
                    + "void main() {\n"
                    + "    float t = clamp(vUv.y, 0.0, 1.0);\n"
                    + "    vec3 c = mix(uHorizon, uZenith, pow(t, 0.75));\n"
                    // A diffuse glow where the sun is, without a hard disc.
                    + "    float glow = pow(clamp(1.0 - abs(t - clamp(uSun.y, 0.0, 1.0)) * 2.4,"
                    + " 0.0, 1.0), 3.0);\n"
                    + "    c += vec3(0.55, 0.42, 0.26) * glow * clamp(uSun.y + 0.25, 0.0, 1.0);\n"
                    + "    fragColour = vec4(c, 1.0);\n"
                    + "}\n";
}
