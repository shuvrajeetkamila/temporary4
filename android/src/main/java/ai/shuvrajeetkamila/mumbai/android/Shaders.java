package ai.shuvrajeetkamila.mumbai.android;

/**
 * GLSL ES 3.00 sources.
 *
 * <p>Two things here are not stylistic.
 *
 * <p><b>Precision is a link-time contract.</b> A uniform or varying declared
 * {@code highp} in the vertex shader and left to the default in the fragment
 * shader is a link error on some drivers and silently reinterpreted on
 * others. Every shared declaration below is qualified identically on both
 * sides, including the ones that look redundant.
 *
 * <p><b>mediump cannot hold world coordinates.</b> It has ten bits of
 * mantissa, so near the edge of a 90 km world the spacing between
 * representable values is tens of metres. Worse, the fog density is about
 * 4.5e-5, which is below the smallest mediump denormal and flushes to zero,
 * so distance fog silently disappears. Position and fog are highp
 * throughout.
 */
final class Shaders {

    /** Shared header. Keep the two stages in step. */
    private static final String VS_HEAD =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "precision highp int;\n";

    private static final String FS_HEAD =
            "#version 300 es\n"
            // The fragment stage has no guaranteed default float precision in
            // ES 3, so it must be stated or the shader will not compile at
            // all on a conforming driver.
            + "precision highp float;\n"
            + "precision highp int;\n"
            + "precision mediump sampler2D;\n";

    /** Terrain and buildings. One shader, because the vertex format is one. */
    static final String SCENE_VS = VS_HEAD
            + "layout(location = 0) in vec3 aPos;\n"
            + "layout(location = 1) in vec3 aNormal;\n"
            + "layout(location = 2) in vec3 aAlbedo;\n"
            + "layout(location = 3) in float aAux;\n"
            + "uniform highp mat4 uViewProj;\n"
            + "uniform highp vec3 uChunkOrigin;\n"
            + "uniform highp vec3 uCamera;\n"
            + "uniform highp float uTime;\n"
            + "out highp vec3 vWorld;\n"
            + "out mediump vec3 vNormal;\n"
            + "out mediump vec3 vAlbedo;\n"
            + "out mediump float vAux;\n"
            + "out highp float vDepth;\n"
            + "void main() {\n"
            // Chunk-local float plus a double-derived origin passed as a
            // uniform. The vertex itself is never more than a kilometre from
            // its own origin, so 32 bits is plenty for it.
            + "  highp vec3 world = aPos + uChunkOrigin;\n"
            + "  vWorld = world;\n"
            + "  vNormal = aNormal;\n"
            + "  vAlbedo = aAlbedo;\n"
            + "  vAux = aAux;\n"
            + "  vDepth = length(world - uCamera);\n"
            + "  gl_Position = uViewProj * vec4(world, 1.0);\n"
            + "}\n";

    static final String SCENE_FS = FS_HEAD
            + "in highp vec3 vWorld;\n"
            + "in mediump vec3 vNormal;\n"
            + "in mediump vec3 vAlbedo;\n"
            + "in mediump float vAux;\n"
            + "in highp float vDepth;\n"
            + "uniform mediump vec3 uSunDir;\n"
            + "uniform mediump vec3 uSunColour;\n"
            + "uniform mediump vec3 uSkyColour;\n"
            + "uniform mediump vec3 uFogColour;\n"
            // highp: at 4.5e-5 this underflows mediump to zero and the fog
            // vanishes. It cost a day to find the first time.
            + "uniform highp float uFogDensity;\n"
            + "uniform mediump float uWetness;\n"
            + "out mediump vec4 fragColour;\n"
            + "void main() {\n"
            + "  mediump vec3 n = normalize(vNormal);\n"
            + "  mediump float ndl = max(dot(n, uSunDir), 0.0);\n"
            // Hemispheric ambient: sky from above, warm bounce from below.
            + "  mediump float hemi = 0.5 + 0.5 * n.y;\n"
            + "  mediump vec3 ambient = mix(vec3(0.22, 0.20, 0.18), uSkyColour, hemi) * 0.55;\n"
            + "  mediump vec3 albedo = vAlbedo;\n"
            // Rain darkens and glosses surfaces.
            + "  albedo *= mix(1.0, 0.72, uWetness);\n"
            + "  mediump vec3 lit = albedo * (ambient + uSunColour * ndl);\n"
            + "  if (vAux < -0.5) {\n"
            // aux = -1 marks water. Fresnel plus a sky tint, cheap but reads
            // correctly against the land.
            + "    mediump vec3 v = normalize(uSkyColour);\n"
            + "    mediump float fres = pow(1.0 - max(n.y, 0.0), 3.0);\n"
            + "    lit = mix(vec3(0.045, 0.105, 0.135), uSkyColour, 0.22 + 0.55 * fres);\n"
            + "    lit += uSunColour * pow(ndl, 48.0) * 0.8;\n"
            + "  } else if (vAux > 0.01) {\n"
            + "    lit += albedo * vAux;\n"
            + "  }\n"
            + "  highp float f = 1.0 - exp(-vDepth * uFogDensity);\n"
            + "  f = clamp(f, 0.0, 1.0);\n"
            + "  fragColour = vec4(mix(lit, uFogColour, f), 1.0);\n"
            + "}\n";

    /** Instanced props: vehicles, trees, street furniture. */
    static final String INSTANCED_VS = VS_HEAD
            + "layout(location = 0) in vec3 aPos;\n"
            + "layout(location = 1) in vec3 aNormal;\n"
            + "layout(location = 2) in vec3 aAlbedo;\n"
            + "layout(location = 3) in float aAux;\n"
            // vec4(x, y, z, yaw), divisor 1.
            + "layout(location = 4) in vec4 aInstance;\n"
            + "uniform highp mat4 uViewProj;\n"
            + "uniform highp vec3 uCamera;\n"
            + "out highp vec3 vWorld;\n"
            + "out mediump vec3 vNormal;\n"
            + "out mediump vec3 vAlbedo;\n"
            + "out mediump float vAux;\n"
            + "out highp float vDepth;\n"
            + "void main() {\n"
            + "  highp float s = sin(aInstance.w);\n"
            + "  highp float c = cos(aInstance.w);\n"
            + "  highp vec3 p = vec3(aPos.x * c + aPos.z * s, aPos.y,\n"
            + "                      -aPos.x * s + aPos.z * c);\n"
            + "  highp vec3 world = p + aInstance.xyz;\n"
            + "  vWorld = world;\n"
            + "  vNormal = vec3(aNormal.x * c + aNormal.z * s, aNormal.y,\n"
            + "                 -aNormal.x * s + aNormal.z * c);\n"
            + "  vAlbedo = aAlbedo;\n"
            + "  vAux = aAux;\n"
            + "  vDepth = length(world - uCamera);\n"
            + "  gl_Position = uViewProj * vec4(world, 1.0);\n"
            + "}\n";

    /** The sea surface, displaced by the same wave sum the physics uses. */
    static final String SEA_VS = VS_HEAD
            + "layout(location = 0) in vec2 aGrid;\n"
            + "uniform highp mat4 uViewProj;\n"
            + "uniform highp vec3 uCamera;\n"
            + "uniform highp float uTime;\n"
            + "uniform highp float uExtent;\n"
            + "uniform highp float uTide;\n"
            + "uniform mediump float uChoppiness;\n"
            + "out highp vec3 vWorld;\n"
            + "out mediump vec3 vNormal;\n"
            + "out highp float vDepth;\n"
            // The same four directional waves as Ocean.java. If these two
            // ever disagree, boats float above or sink into the drawn
            // surface, which is immediately obvious and impossible to tune
            // away. The constants are duplicated rather than uploaded because
            // they never change.
            + "const vec4 WA = vec4(0.62, 0.41, 0.24, 0.13);\n"
            + "const vec4 WL = vec4(93.0, 47.0, 23.0, 11.0);\n"
            + "const vec4 WS = vec4(0.95, 1.25, 1.65, 2.30);\n"
            + "const vec2 D0 = vec2(0.886, 0.464);\n"
            + "const vec2 D1 = vec2(0.629, -0.777);\n"
            + "const vec2 D2 = vec2(0.998, -0.070);\n"
            + "const vec2 D3 = vec2(0.342, 0.940);\n"
            + "highp float waveAt(highp vec2 p) {\n"
            + "  highp float h = 0.0;\n"
            + "  h += WA.x * uChoppiness * sin(dot(p, D0) * 6.2831853 / WL.x + uTime * WS.x);\n"
            + "  h += WA.y * uChoppiness * sin(dot(p, D1) * 6.2831853 / WL.y + uTime * WS.y);\n"
            + "  h += WA.z * uChoppiness * sin(dot(p, D2) * 6.2831853 / WL.z + uTime * WS.z);\n"
            + "  h += WA.w * uChoppiness * sin(dot(p, D3) * 6.2831853 / WL.w + uTime * WS.w);\n"
            + "  return h + uTide;\n"
            + "}\n"
            + "void main() {\n"
            // A grid that follows the camera, snapped so it does not crawl.
            + "  highp vec2 centre = floor(uCamera.xz / 8.0) * 8.0;\n"
            + "  highp vec2 p = centre + aGrid * uExtent;\n"
            + "  highp float h = waveAt(p);\n"
            + "  highp float e = 0.75;\n"
            + "  highp float hx = waveAt(p + vec2(e, 0.0)) - waveAt(p - vec2(e, 0.0));\n"
            + "  highp float hz = waveAt(p + vec2(0.0, e)) - waveAt(p - vec2(0.0, e));\n"
            + "  vNormal = normalize(vec3(-hx / (2.0 * e), 1.0, -hz / (2.0 * e)));\n"
            + "  vWorld = vec3(p.x, h, p.y);\n"
            + "  vDepth = length(vWorld - uCamera);\n"
            + "  gl_Position = uViewProj * vec4(vWorld, 1.0);\n"
            + "}\n";

    static final String SEA_FS = FS_HEAD
            + "in highp vec3 vWorld;\n"
            + "in mediump vec3 vNormal;\n"
            + "in highp float vDepth;\n"
            + "uniform mediump vec3 uSunDir;\n"
            + "uniform mediump vec3 uSunColour;\n"
            + "uniform mediump vec3 uSkyColour;\n"
            + "uniform mediump vec3 uFogColour;\n"
            + "uniform highp float uFogDensity;\n"
            + "uniform highp vec3 uCamera;\n"
            + "out mediump vec4 fragColour;\n"
            + "void main() {\n"
            + "  mediump vec3 n = normalize(vNormal);\n"
            + "  mediump vec3 view = normalize(uCamera - vWorld);\n"
            + "  mediump float fres = pow(1.0 - max(dot(n, view), 0.0), 4.0);\n"
            + "  mediump vec3 deep = vec3(0.030, 0.082, 0.106);\n"
            + "  mediump vec3 col = mix(deep, uSkyColour * 0.85, 0.18 + 0.68 * fres);\n"
            + "  mediump vec3 h = normalize(uSunDir + view);\n"
            + "  col += uSunColour * pow(max(dot(n, h), 0.0), 220.0) * 1.35;\n"
            + "  highp float f = clamp(1.0 - exp(-vDepth * uFogDensity), 0.0, 1.0);\n"
            + "  fragColour = vec4(mix(col, uFogColour, f), 1.0);\n"
            + "}\n";

    /** Sky: a gradient dome, drawn last at the far plane. */
    static final String SKY_VS = VS_HEAD
            + "layout(location = 0) in vec2 aPos;\n"
            + "out mediump vec2 vNdc;\n"
            + "void main() {\n"
            + "  vNdc = aPos;\n"
            + "  gl_Position = vec4(aPos, 1.0, 1.0);\n"
            + "}\n";

    static final String SKY_FS = FS_HEAD
            + "in mediump vec2 vNdc;\n"
            + "uniform mediump vec3 uSkyColour;\n"
            + "uniform mediump vec3 uFogColour;\n"
            + "uniform mediump float uHorizon;\n"
            + "out mediump vec4 fragColour;\n"
            + "void main() {\n"
            + "  mediump float t = clamp((vNdc.y - uHorizon) * 0.9 + 0.5, 0.0, 1.0);\n"
            + "  fragColour = vec4(mix(uFogColour, uSkyColour, t), 1.0);\n"
            + "}\n";

    /** Flat colour, for the HUD, the minimap and debug lines. */
    static final String UI_VS = VS_HEAD
            + "layout(location = 0) in vec2 aPos;\n"
            + "layout(location = 1) in vec4 aColour;\n"
            + "uniform highp mat4 uOrtho;\n"
            + "out mediump vec4 vColour;\n"
            + "void main() {\n"
            + "  vColour = aColour;\n"
            + "  gl_Position = uOrtho * vec4(aPos, 0.0, 1.0);\n"
            + "}\n";

    static final String UI_FS = FS_HEAD
            + "in mediump vec4 vColour;\n"
            + "out mediump vec4 fragColour;\n"
            + "void main() { fragColour = vColour; }\n";

    private Shaders() { }
}
