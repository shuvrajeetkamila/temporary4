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

    /** Must equal TextureLibrary.MAT_COUNT; the uniform array is sized by it. */
    private static final int MAT_COUNT =
            ai.shuvrajeetkamila.mumbai.render.TextureLibrary.MAT_COUNT;

    /**
     * Aux value marking an emergency lamp rather than a surface, well clear
     * of the material indices, which run 0 to MAT_COUNT - 1. A beacon is a
     * light source: it must not be shaded, textured, or go out at dusk.
     */
    static final float BEACON_AUX = 60.0f;

    /**
     * Aux value marking a blob shadow. Like the beacon it is handled
     * before any lighting: a shadow is an absence of light, so shading it
     * as a surface makes it glow at noon. The fraction carries the
     * distance from the centre of the disc, used to fade the rim.
     */
    static final float SHADOW_AUX = 70.0f;

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
            + "precision mediump sampler2D;\n"
            + "precision mediump sampler2DArray;\n";

    /**
     * The wave set and its evaluation, shared verbatim by the sea's
     * vertex and fragment stages so the surface they draw and the slope
     * they light it with cannot disagree. The numbers arrive from
     * Ocean.java as uniforms; there are no wave constants in GLSL.
     */
    private static final String WAVE_COMMON =
            "uniform highp vec4 uWaveAmp;\n"
            + "uniform highp vec4 uWaveK;\n"
            + "uniform highp vec4 uWaveDirX;\n"
            + "uniform highp vec4 uWaveDirZ;\n"
            + "uniform highp vec4 uWavePhase;\n"
            // Render space is world space minus the floating origin. The
            // waves have to be evaluated where the physics evaluates
            // them, or after the first rebase the drawn surface slides
            // out from under every hull in the harbour.
            + "uniform highp vec2 uWorldOffset;\n"
            + "uniform highp float uTide;\n"
            // Height and slope together. The slope used to be four extra
            // height samples differenced 1.5 m apart - a subtraction of
            // two nearly equal numbers, the one operation a float is
            // worst at. The derivative of a sine is a cosine; there is no
            // reason to approximate it.
            + "highp float waveAt(highp vec2 pr, out highp vec2 slope) {\n"
            + "  highp vec2 p = pr + uWorldOffset;\n"
            + "  highp float h = 0.0;\n"
            + "  slope = vec2(0.0);\n"
            + "  for (int i = 0; i < 4; i++) {\n"
            + "    highp vec2 d = vec2(uWaveDirX[i], uWaveDirZ[i]);\n"
            + "    highp float k = uWaveK[i];\n"
            // Reduce the distance along the wave into one wavelength
            // before turning it into an angle. Mumbai is 70 km across and
            // the shortest wave is 7.5 m, so without this the angle
            // reaches 60,000 radians at the far corner of the map and a
            // 32-bit float can no longer tell one crest from the next.
            + "    highp float lambda = 6.2831853 / k;\n"
            + "    highp float a = mod(dot(p, d), lambda) * k + uWavePhase[i];\n"
            + "    h += uWaveAmp[i] * sin(a);\n"
            + "    slope += uWaveAmp[i] * k * cos(a) * d;\n"
            + "  }\n"
            // Insurance. The real surface is a metre and a half of swell
            // on a two metre tide; anything outside this is arithmetic
            // having gone wrong, and an unclamped vertex does not just
            // look wrong, it throws a triangle across the whole screen.
            + "  return clamp(h, -4.0, 4.0) + uTide;\n"
            + "}\n";

    /** Terrain and buildings. One shader, because the vertex format is one. */
    static final String SCENE_VS = VS_HEAD
            + "layout(location = 0) in vec3 aPos;\n"
            + "layout(location = 1) in vec3 aNormal;\n"
            + "layout(location = 2) in vec3 aAlbedo;\n"
            + "layout(location = 3) in float aAux;\n"
            + "layout(location = 5) in float aTexV;\n"
            + "uniform highp mat4 uViewProj;\n"
            + "uniform highp vec3 uChunkOrigin;\n"
            + "uniform highp vec3 uCamera;\n"
            + "uniform highp float uTime;\n"
            + "out highp vec3 vWorld;\n"
            + "out mediump vec3 vNormal;\n"
            + "out mediump vec3 vAlbedo;\n"
            + "out highp float vAux;\n"
            + "out highp float vTexV;\n"
            + "out highp float vDepth;\n"
            + WAVE_COMMON
            + "void main() {\n"
            // Chunk-local float plus a double-derived origin passed as a
            // uniform. The vertex itself is never more than a kilometre from
            // its own origin, so 32 bits is plenty for it.
            + "  highp vec3 world = aPos + uChunkOrigin;\n"
  // The coastal fringe. Cells that are all sea are not meshed at
  // all - the sea surface draws those - but a cell with any land
  // in it is kept, so the shoreline is drawn by the terrain at
  // whatever detail the chunk is built at. Its sea corners sat
  // flat at chart datum while the sea beside them rode the tide
  // and the swell, so at high water there was a two metre step
  // all the way round the island with a dark static rim below
  // it. They are put on the same surface here, a few centimetres
  // proud so the join cannot fight the sea for the depth buffer.
            + "  if (aAux < -0.5) {\n"
            + "    highp vec2 slope;\n"
            + "    world.y = waveAt(world.xz, slope) + 0.05;\n"
            + "  }\n"
            + "  vWorld = world;\n"
            + "  vNormal = aNormal;\n"
            + "  vAlbedo = aAlbedo;\n"
            + "  vAux = aAux;\n"
            + "  vTexV = aTexV;\n"
            + "  vDepth = length(world - uCamera);\n"
            + "  gl_Position = uViewProj * vec4(world, 1.0);\n"
            + "}\n";

    static final String SCENE_FS = FS_HEAD
            + "in highp vec3 vWorld;\n"
            + "in mediump vec3 vNormal;\n"
            + "in mediump vec3 vAlbedo;\n"
            // highp, and not for show: this carries a packed material index
            // in its integer part. mediump has a ten bit mantissa, so 14.0
            // can be held as 13.99, floor() returns 13, and the building is
            // dressed in the wrong texture.
            + "in highp float vAux;\n"
            + "in highp float vTexV;\n"
            + "in highp float vDepth;\n"
            + "uniform mediump vec3 uSunDir;\n"
            + "uniform mediump vec3 uSunColour;\n"
            + "uniform mediump vec3 uSkyColour;\n"
            + "uniform mediump vec3 uFogColour;\n"
            // highp: at 4.5e-5 this underflows mediump to zero and the fog
            // vanishes. It cost a day to find the first time.
            + "uniform highp float uFogDensity;\n"
            // Shared with the vertex stage, which already receives it.
            + "uniform highp vec3 uCamera;\n"
            + "uniform mediump float uWetness;\n"
            + "uniform mediump sampler2DArray uAtlas;\n"
            + "uniform mediump float uMatScale[" + MAT_COUNT + "];\n"
            // 0 in daylight, 1 after dark. Drives window emission.
            + "uniform mediump float uNight;\n"
            // Beacon phase, 0..1, or negative for "nobody is responding".
            + "uniform mediump float uSiren;\n"
            + "out mediump vec4 fragColour;\n"
            + "void main() {\n"
            + "  mediump vec3 n = normalize(vNormal);\n"
            + "  mediump float ndl = max(dot(n, uSunDir), 0.0);\n"
            // Hemispheric ambient: sky from above, warm bounce from below.
            + "  mediump float hemi = 0.5 + 0.5 * n.y;\n"
            + "  mediump vec3 ambient = mix(vec3(0.22, 0.20, 0.18), uSkyColour, hemi) * 0.55;\n"
            + "  mediump vec3 albedo = vAlbedo;\n"
            + "  if (vAux > " + (ai.shuvrajeetkamila.mumbai.world.ChunkMesher
                    .LAMP_AUX - 0.5f) + ") {\n"
            // A street lamp head. Tested before the shadow and beacon
            // tags because its value is above both. Unlit and
            // untextured for the same reason the beacon is: a lamp is
            // a light source, not a surface, so it must not go dim in
            // shadow or at dusk. Dull metal by day, burning by night,
            // and the city has no dynamic lights so it throws nothing
            // onto the road beneath it.
            + "    mediump vec3 lamp = mix(vAlbedo * 0.30, vAlbedo * 2.4, uNight);\n"
            + "    highp float lf = clamp(1.0 - exp(-vDepth * uFogDensity), 0.0, 1.0);\n"
            + "    fragColour = vec4(mix(lamp, uFogColour, lf), 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  if (vAux > " + (SHADOW_AUX - 0.5f) + ") {\n"
            // A blob shadow. Not a surface: it is multiplied over whatever
            // is already there, faded to nothing at the rim so the edge is
            // soft, and weakest when the sun is low or it is raining.
            + "    mediump float rim = vAux - " + SHADOW_AUX + ";\n"
            + "    mediump float k = (1.0 - rim * rim) * 0.55\n"
            + "        * clamp(uSunDir.y * 1.6, 0.0, 1.0) * (1.0 - 0.45 * uNight);\n"
            + "    if (k < 0.01) { discard; }\n"
            + "    fragColour = vec4(0.0, 0.0, 0.0, k);\n"
            + "    return;\n"
            + "  }\n"
            + "  if (vAux > " + (BEACON_AUX - 0.5f) + ") {\n"
            // An emergency beacon. Unlit and untextured on purpose: a lamp
            // is a light source, not a surface, so it must not go dim in
            // shadow or at dusk. Red lags blue by half a cycle, and the
            // whole thing is dark unless somebody is actually responding.
            + "    mediump float off = vAlbedo.b > vAlbedo.r ? 0.0 : 0.5;\n"
            + "    mediump float on = uSiren < 0.0 ? 0.0\n"
            + "        : step(0.5, fract(uSiren + off));\n"
            + "    mediump vec3 lamp = vAlbedo * (0.22 + 2.5 * on);\n"
            + "    highp float bf = clamp(1.0 - exp(-vDepth * uFogDensity), 0.0, 1.0);\n"
            + "    fragColour = vec4(mix(lamp, uFogColour, bf), 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  if (vAux < -0.5) {\n"
            // aux = -1 marks water. Fresnel plus a sky tint, cheap but reads
            // correctly against the land. Untextured by design: the sea gets
            // its detail from the wave geometry, not from a tile.
            // The same water as the sea surface, to the number: this is
            // the shoreline strip and the sea begins at the far edge of
            // it, so any difference in the two shadings draws a line
            // round the island.
            + "    mediump vec3 view = normalize(uCamera - vWorld);\n"
            + "    mediump float fres = pow(1.0 - max(dot(n, view), 0.0), 4.0);\n"
            + "    mediump vec3 lit = mix(vec3(0.030, 0.082, 0.106),\n"
            + "                           uSkyColour * 0.85, 0.18 + 0.68 * fres);\n"
            + "    mediump vec3 hv = normalize(uSunDir + view);\n"
            + "    lit += uSunColour * pow(max(dot(n, hv), 0.0), 220.0) * 1.35;\n"
            + "    highp float fw = clamp(1.0 - exp(-vDepth * uFogDensity), 0.0, 1.0);\n"
            + "    fragColour = vec4(mix(lit, uFogColour, fw), 1.0);\n"
            + "    return;\n"
            + "  }\n"
            // Unpack: integer part selects the tile, fraction is how hard the
            // windows burn after dark.
            + "  highp float matF = floor(vAux + 0.0005);\n"
            + "  mediump float emis = vAux - matF;\n"
            + "  int mat = int(matF);\n"
            + "  mediump float scale = uMatScale[mat];\n"
            // UVs from world position rather than from the vertex format. A
            // per-vertex UV would have meant widening the vertex, rebuilding
            // every chunk and re-running the whole data pipeline; this costs
            // four instructions and leaves the compiled world untouched.
            + "  highp vec2 uv;\n"
            + "  if (abs(n.y) > 0.65) {\n"
            + "    uv = vWorld.xz / scale;\n"
            + "  } else {\n"
            // Tangential distance around the facade, and height. Negated so
            // the tile's first row renders at the top of the wall.
            + "    uv = vec2(vWorld.x * n.z - vWorld.z * n.x, -vTexV) / scale;\n"
            + "  }\n"
            + "  mediump vec4 tex = texture(uAtlas, vec3(uv, matF));\n"
            + "  albedo *= tex.rgb;\n"
            // Lane markings. The mesher writes a road vertex's distance
            // from its own centreline into vTexV - the channel the
            // horizontal case above does not use - and pushes anything
            // that should stay unpainted far past this test, so the
            // paint needs no flag of its own in a vertex that has no
            // room for one. fwidth keeps the line one pixel wide when
            // it is nearly edge on, instead of flickering in and out
            // between fragments the way a hard threshold does.
            + "  if (mat == " + ai.shuvrajeetkamila.mumbai.render.TextureLibrary.MAT_TARMAC + ") {\n"
            + "    highp float wline = fwidth(vTexV) * 0.9 + 0.055;\n"
            + "    mediump float paint = 1.0 - smoothstep(0.0, wline, vTexV);\n"
            + "    albedo = mix(albedo, vec3(0.80, 0.78, 0.70), paint * 0.88);\n"
            + "  }\n"
            // Rain darkens and glosses surfaces.
            + "  albedo *= mix(1.0, 0.72, uWetness);\n"
            + "  mediump vec3 lit = albedo * (ambient + uSunColour * ndl);\n"
            // Wet horizontal surfaces throw a weak sun glare back up.
            + "  if (uWetness > 0.01 && n.y > 0.6) {\n"
            + "    lit += uSunColour * pow(ndl, 22.0) * uWetness * 0.45;\n"
            + "  }\n"
            // The texture's alpha is a window mask, so only glass lights up.
            // Emission applied to a whole face makes the entire slab
            // fluoresce, which is the thing that always looks wrong.
            + "  lit += tex.rgb * (emis * tex.a * uNight * 2.6);\n"
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
            // vec2(phase, stride), divisor 1. Only pedestrian VAOs bind
            // this; everything else leaves the attribute disabled, which
            // reads as zero, and zero stride means nothing moves.
            + "layout(location = 6) in vec2 aGait;\n"
            // Per-vertex limb tag: 0 static, 1 arm, 2 leg. It rides in the
            // texV slot, which props do not use, and is only bound on
            // pedestrian VAOs.
            + "layout(location = 5) in float aLimb;\n"
            + "uniform highp mat4 uViewProj;\n"
            + "uniform highp vec3 uCamera;\n"
            + "out highp vec3 vWorld;\n"
            + "out mediump vec3 vNormal;\n"
            + "out mediump vec3 vAlbedo;\n"
            + "out highp float vAux;\n"
            + "out highp float vTexV;\n"
            + "out highp float vDepth;\n"
            + "void main() {\n"
            // aInstance.w is a compass heading, not a rotation angle:
            // zero is north, which is -Z, while a rotation of zero
            // leaves the nose on +Z. The rotation wanted is therefore
            // (pi - heading), and sin(pi-h), cos(pi-h) is just sin h,
            // -cos h. Without the negation every instanced body in the
            // world - traffic, trains, ferries - was drawn mirrored
            // north to south against the direction it was moving.
            + "  highp float s = sin(aInstance.w);\n"
            + "  highp float c = -cos(aInstance.w);\n"
            // A walk cycle without a skeleton. Each limb is tagged in the
            // mesh and swings about its own joint, arms against legs, with
            // a small bob on the whole body at twice the rate. Tagging
            // rather than classifying by position matters: the inner face
            // of an arm and the side of the torso are the same plane, so
            // any threshold splits the arm box and stretches it.
            + "  highp vec3 local = aPos;\n"
            + "  if (aGait.y > 0.001) {\n"
            + "    if (aLimb > 0.5) {\n"
            + "      mediump float leg = step(1.5, aLimb);\n"
            + "      mediump float pivot = mix(1.25, 0.76, leg);\n"
            + "      mediump float amp = mix(-0.38, 0.52, leg);\n"
            + "      mediump float side = local.x < 0.0 ? -1.0 : 1.0;\n"
            + "      mediump float a = sin(aGait.x) * aGait.y * amp * side;\n"
            + "      mediump float dy = local.y - pivot;\n"
            + "      mediump float ca = cos(a), sa = sin(a);\n"
            + "      local.y = pivot + dy * ca - local.z * sa;\n"
            + "      local.z = dy * sa + local.z * ca;\n"
            + "    }\n"
            + "    local.y += 0.022 * cos(aGait.x * 2.0) * aGait.y;\n"
            + "  }\n"
            + "  highp vec3 p = vec3(local.x * c + local.z * s, local.y,\n"
            + "                      -local.x * s + local.z * c);\n"
            + "  highp vec3 world = p + aInstance.xyz;\n"
            + "  vWorld = world;\n"
            + "  vNormal = vec3(aNormal.x * c + aNormal.z * s, aNormal.y,\n"
            + "                 -aNormal.x * s + aNormal.z * c);\n"
            + "  vAlbedo = aAlbedo;\n"
            + "  vAux = aAux;\n"
            + "  vTexV = world.y;\n"
            + "  vDepth = length(world - uCamera);\n"
            + "  gl_Position = uViewProj * vec4(world, 1.0);\n"
            + "}\n";

    /** The sea surface, displaced by the same wave sum the physics uses. */
    static final String SEA_VS = VS_HEAD
            + "layout(location = 0) in vec2 aGrid;\n"
            + "uniform highp mat4 uViewProj;\n"
            + "uniform highp vec3 uCamera;\n"
            + "uniform highp float uExtent;\n"
            + "out highp vec3 vWorld;\n"
            + "out highp float vDepth;\n"
            + WAVE_COMMON
            + "void main() {\n"
            // A grid that follows the camera, snapped so it does not crawl.
            + "  highp vec2 centre = floor(uCamera.xz / 8.0) * 8.0;\n"
            + "  highp vec2 p = centre + aGrid * uExtent;\n"
            + "  highp vec2 slope;\n"
            + "  highp float h = waveAt(p, slope);\n"
            // Flatten the displacement where the mesh is too coarse to
            // carry it. This grid spends its vertices near the camera, so
            // a cell a kilometre out spans several wavelengths: the
            // height it samples there is not a wave, it is noise on the
            // horizon. The shape is still there in the lighting, which is
            // computed per pixel.
            + "  highp float away = distance(p, uCamera.xz);\n"
            + "  h = mix(h, uTide, smoothstep(250.0, 900.0, away));\n"
            + "  vWorld = vec3(p.x, h, p.y);\n"
            + "  vDepth = length(vWorld - uCamera);\n"
            + "  gl_Position = uViewProj * vec4(vWorld, 1.0);\n"
            + "}\n";

    static final String SEA_FS = FS_HEAD
            + "in highp vec3 vWorld;\n"
            + "in highp float vDepth;\n"
            + "uniform mediump vec3 uSunDir;\n"
            + "uniform mediump vec3 uSunColour;\n"
            + "uniform mediump vec3 uSkyColour;\n"
            + "uniform mediump vec3 uFogColour;\n"
            + "uniform highp float uFogDensity;\n"
            + "uniform highp vec3 uCamera;\n"
            + WAVE_COMMON
            // The land mask, exactly as the physics reads it. xy is the
            // world origin of the mask, zw is one over its span.
            + "uniform highp vec4 uLandXform;\n"
            + "uniform lowp sampler2D uLandMask;\n"
            + "out mediump vec4 fragColour;\n"
            + "void main() {\n"
            // Never paint sea on land. The tide is a real one, just under
            // two metres either way, and 6 per cent of this city's
            // terrain sits below that line - the reclaimed ground along
            // Back Bay, the dock aprons, the low ground behind Mahim. Left
            // alone, high water drew the sea straight over those streets,
            // and where the two surfaces passed within a few centimetres
            // of each other they fought for the depth buffer and striped
            // the ground. The land mask settles it: the terrain says where
            // the coast is, and the sea stops there.
            + "  highp vec2 luv = (vWorld.xz - uLandXform.xy) * uLandXform.zw;\n"
            + "  if (texture(uLandMask, luv).r > 0.5) { discard; }\n"
            // The slope, per pixel. Taken at the vertices it was
            // meaningless: the grid is tens of metres across out here and
            // the swell is seven, so each vertex sampled an unrelated part
            // of a different wave and the triangle between them
            // interpolated nonsense. Through a 220-power specular,
            // nonsense is a sheet of moving white - which is what the
            // water turned into after a while.
            + "  highp vec2 slope;\n"
            + "  waveAt(vWorld.xz, slope);\n"
            // And fade the ripple out with distance. A seven metre wave a
            // kilometre away is finer than a pixel; keeping it only
            // aliases. Real water goes glassy towards the horizon for the
            // same reason.
            + "  mediump float detail = 1.0 - smoothstep(180.0, 1400.0, vDepth);\n"
            + "  mediump vec3 n = normalize(vec3(-slope.x * detail, 1.0,\n"
            + "      -slope.y * detail));\n"
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

    /**
     * Monsoon rain, drawn as a full-screen pass over the finished scene
     * using the same quad as the sky.
     *
     * <p>Three layers of procedural streaks at different scales and fall
     * speeds, slanted, with only a fraction of the columns carrying a drop
     * so it does not read as a comb. Screen space rather than particles:
     * rain that fills the frame needs tens of thousands of quads to look
     * like anything, and none of them survive contact with a tile-based
     * mobile GPU.
     *
     * <p>{@code uTime} is wrapped to ten seconds on the CPU, because this
     * is mediump and a monotonically growing clock loses its fractional
     * bits within minutes. Ten is chosen so that every layer's speed times
     * ten is a whole number, which puts the wrap exactly on each layer's
     * period and makes it invisible. Changing the speeds without changing
     * the wrap puts a visible jump in the rain every ten seconds.
     */
    static final String RAIN_FS = FS_HEAD
            + "in mediump vec2 vNdc;\n"
            + "uniform highp float uTime;\n"
            + "uniform mediump float uRain;\n"
            + "uniform mediump float uAspect;\n"
            + "uniform mediump vec3 uTint;\n"
            + "out mediump vec4 fragColour;\n"
            + "highp float h11(highp float x) {\n"
            + "  return fract(sin(x * 127.1) * 43758.5453);\n"
            + "}\n"
            + "void main() {\n"
            // Everything here is highp on purpose. The previous version
            // held the screen position at mediump, where a value of 80
            // steps by 1/16 - and the feature it was driving, the head
            // of a streak, was 1/20 wide. The drops could not resolve
            // and quantised into blocks.
            + "  highp vec2 uv = vec2(vNdc.x * uAspect, vNdc.y);\n"
            + "  highp float acc = 0.0;\n"
            + "  for (int i = 0; i < 3; i++) {\n"
            + "    highp float fi = float(i);\n"
            // Three sheets at different distances. The near one has the
            // fewest, longest, fastest drops.
            + "    highp float cols = 6.0 + fi * 4.0;\n"
            + "    highp float rows = 1.15 + fi * 0.525;\n"
            + "    highp float speed = 1.3 + fi * 0.8;\n"
            + "    highp float gain = 0.62 - fi * 0.15;\n"
            // A slight lean, so it is falling rather than hanging.
            + "    highp float px = (uv.x + uv.y * 0.10) * cols;\n"
            + "    highp float py = uv.y * rows;\n"
            + "    highp float col = floor(px);\n"
            + "    highp float xf = fract(px) - 0.5;\n"
            + "    highp float off = h11(col + fi * 31.7);\n"
            // The wrap on uTime is ten seconds and every speed times ten
            // is a whole number, so the wrap lands on each layer's own
            // period and cannot be seen.
            + "    highp float y = fract(py - uTime * speed - off);\n"
            // Long and thin: a bright head with a tail fading behind it.
            // The old shape was as wide as it was tall, which is why a
            // screen of them read as woven matting rather than rain.
            + "    highp float streak = smoothstep(0.0, 0.02, y)\n"
            + "        * (1.0 - smoothstep(0.02, 0.46, y));\n"
            + "    highp float bar = 1.0 - smoothstep(0.035, 0.115, abs(xf));\n"
            // Heavier rain fills in more of the columns rather than
            // making the same drops brighter, so a shower is sparse and
            // a squall is dense.
            + "    highp float present = step(0.74 - 0.46 * uRain,\n"
            + "        h11(col * 1.7 + fi * 5.3));\n"
            + "    acc += streak * bar * present * gain;\n"
            + "  }\n"
            + "  mediump float a = clamp(acc * uRain * 1.35, 0.0, 1.0);\n"
            + "  fragColour = vec4(uTint, a);\n"
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
