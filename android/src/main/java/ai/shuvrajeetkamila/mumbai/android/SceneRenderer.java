package ai.shuvrajeetkamila.mumbai.android;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.app.Quality;
import ai.shuvrajeetkamila.mumbai.math.Vec3;
import ai.shuvrajeetkamila.mumbai.sim.Agents;
import ai.shuvrajeetkamila.mumbai.sim.Engine;
import ai.shuvrajeetkamila.mumbai.world.ChunkMesher;
import ai.shuvrajeetkamila.mumbai.render.TextureLibrary;
import ai.shuvrajeetkamila.mumbai.world.WorldStreamer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws the world. Owns the GL thread and drives the simulation from it.
 *
 * <p>Thread ownership runs one way: this class owns the renderer, which owns
 * the engine, which owns the physics. The only thing that travels back to the
 * UI thread is the volatile {@code Telemetry} block, read at about fifteen
 * hertz by the HUD. No locks on the frame path.
 */
final class SceneRenderer implements GLSurfaceView.Renderer {

    private final GameHost host;
    private final InputState input;

    private int sceneProg;
    private int instProg;
    private int seaProg;
    private int skyProg;
    private int rainProg;
    private int uRainTime, uRainAmount, uRainAspect, uRainTint;
    private float aspect = 1.78f;

    private int uSceneViewProj, uSceneOrigin, uSceneCam, uSceneSunDir;
    private int uSceneSunCol, uSceneSky, uSceneFogCol, uSceneFogDen, uSceneWet;
    private int uSceneAtlas, uSceneMatScale, uSceneNight;
    private int uInstViewProj, uInstCam, uInstSunDir, uInstSunCol, uInstSky;
    private int uInstFogCol, uInstFogDen, uInstWet, uInstAtlas, uInstMatScale, uInstNight;

    /** The procedural texture array. One GL_TEXTURE_2D_ARRAY, built once. */
    private int atlas;
    /** 0 in daylight, 1 after dark. Set from the sun elevation each frame. */
    private float nightFactor;
    private int uSeaViewProj, uSeaCam, uSeaTime, uSeaExtent, uSeaTide;
    private int uSeaChop, uSeaSunDir, uSeaSunCol, uSeaSky, uSeaFogCol, uSeaFogDen;
    private int uSkySky, uSkyFog, uSkyHorizon;

    private int seaVao, seaVbo, seaIbo, seaIndexCount, seaGridBuilt;
    private int skyVao, skyVbo;
    /**
     * One of these per vehicle type. Ten instanced draws a frame rather than
     * one, which is a trade worth making: a single shared box for everything
     * made a double-decker the size of a scooter, and silhouette is most of
     * what identifies a vehicle at traffic distance.
     */
    private static final class VehicleGpu {
        int vao, vbo, ibo, instVbo, indexCount;
    }

    private final VehicleGpu[] vehGpu = new VehicleGpu[VehicleKind.values().length];
    private VehicleGpu markerGpu;
    private FloatBuffer markerScratch;
    /** Instance counts per kind, refilled every frame. */
    private final int[] vehCount = new int[VehicleKind.values().length];
    private FloatBuffer[] vehScratch;
    private static final int MAX_PER_KIND = 192;

    private int propVao, propVbo, propIbo, propIndexCount, propInstVbo;

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] viewProj = new float[16];
    private final Frustum frustum = new Frustum();

    private final Vec3 camPos = new Vec3();
    private final Vec3 target = new Vec3();

    private int width = 1;
    private int height = 1;
    private long lastNanos;
    private long frameCount;

    private ChunkMesher mesher;
    private final List<WorldStreamer.Tile> uploadScratch = new ArrayList<>();
    private FloatBuffer instanceScratch;

    // Thread pool for chunk decoding, kept off the GL thread entirely.
    private Thread[] workers;
    private volatile boolean running = true;

    SceneRenderer(GameHost host, InputState input) {
        this.host = host;
        this.input = input;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        Log.i(GLUtil.TAG, "GL vendor " + GLES30.glGetString(GLES30.GL_VENDOR)
                + " renderer " + GLES30.glGetString(GLES30.GL_RENDERER)
                + " version " + GLES30.glGetString(GLES30.GL_VERSION));

        sceneProg = GLUtil.link(Shaders.SCENE_VS, Shaders.SCENE_FS);
        instProg = GLUtil.link(Shaders.INSTANCED_VS, Shaders.SCENE_FS);
        seaProg = GLUtil.link(Shaders.SEA_VS, Shaders.SEA_FS);
        skyProg = GLUtil.link(Shaders.SKY_VS, Shaders.SKY_FS);
        rainProg = GLUtil.link(Shaders.SKY_VS, Shaders.RAIN_FS);
        // GLUtil.link throws on failure, so reaching here means every
        // program compiled and linked on this driver. Logged explicitly so
        // CI can assert it rather than infer it from the app not crashing:
        // a shader that fails only on a real driver is the most likely
        // silent breakage in the whole renderer, and precision mismatches
        // between stages do not show up anywhere else.
        int[] progs = {sceneProg, instProg, seaProg, skyProg, rainProg};
        int linked = 0;
        for (int p : progs) { if (p != 0) { linked++; } }
        Log.i(GLUtil.TAG, "MUMBAI_SHADERS_OK programs=" + linked + "/" + progs.length);

        uSceneViewProj = GLES30.glGetUniformLocation(sceneProg, "uViewProj");
        uSceneOrigin = GLES30.glGetUniformLocation(sceneProg, "uChunkOrigin");
        uSceneCam = GLES30.glGetUniformLocation(sceneProg, "uCamera");
        uSceneSunDir = GLES30.glGetUniformLocation(sceneProg, "uSunDir");
        uSceneSunCol = GLES30.glGetUniformLocation(sceneProg, "uSunColour");
        uSceneSky = GLES30.glGetUniformLocation(sceneProg, "uSkyColour");
        uSceneFogCol = GLES30.glGetUniformLocation(sceneProg, "uFogColour");
        uSceneFogDen = GLES30.glGetUniformLocation(sceneProg, "uFogDensity");
        uSceneWet = GLES30.glGetUniformLocation(sceneProg, "uWetness");
        uSceneAtlas = GLES30.glGetUniformLocation(sceneProg, "uAtlas");
        uSceneMatScale = GLES30.glGetUniformLocation(sceneProg, "uMatScale");
        uSceneNight = GLES30.glGetUniformLocation(sceneProg, "uNight");

        uInstViewProj = GLES30.glGetUniformLocation(instProg, "uViewProj");
        uInstCam = GLES30.glGetUniformLocation(instProg, "uCamera");
        uInstSunDir = GLES30.glGetUniformLocation(instProg, "uSunDir");
        uInstSunCol = GLES30.glGetUniformLocation(instProg, "uSunColour");
        uInstSky = GLES30.glGetUniformLocation(instProg, "uSkyColour");
        uInstFogCol = GLES30.glGetUniformLocation(instProg, "uFogColour");
        uInstFogDen = GLES30.glGetUniformLocation(instProg, "uFogDensity");
        uInstWet = GLES30.glGetUniformLocation(instProg, "uWetness");
        uInstAtlas = GLES30.glGetUniformLocation(instProg, "uAtlas");
        uInstMatScale = GLES30.glGetUniformLocation(instProg, "uMatScale");
        uInstNight = GLES30.glGetUniformLocation(instProg, "uNight");

        atlas = buildAtlas();

        uSeaViewProj = GLES30.glGetUniformLocation(seaProg, "uViewProj");
        uSeaCam = GLES30.glGetUniformLocation(seaProg, "uCamera");
        uSeaTime = GLES30.glGetUniformLocation(seaProg, "uTime");
        uSeaExtent = GLES30.glGetUniformLocation(seaProg, "uExtent");
        uSeaTide = GLES30.glGetUniformLocation(seaProg, "uTide");
        uSeaChop = GLES30.glGetUniformLocation(seaProg, "uChoppiness");
        uSeaSunDir = GLES30.glGetUniformLocation(seaProg, "uSunDir");
        uSeaSunCol = GLES30.glGetUniformLocation(seaProg, "uSunColour");
        uSeaSky = GLES30.glGetUniformLocation(seaProg, "uSkyColour");
        uSeaFogCol = GLES30.glGetUniformLocation(seaProg, "uFogColour");
        uSeaFogDen = GLES30.glGetUniformLocation(seaProg, "uFogDensity");

        uSkySky = GLES30.glGetUniformLocation(skyProg, "uSkyColour");
        uSkyFog = GLES30.glGetUniformLocation(skyProg, "uFogColour");
        uSkyHorizon = GLES30.glGetUniformLocation(skyProg, "uHorizon");
        uRainTime = GLES30.glGetUniformLocation(rainProg, "uTime");
        uRainAmount = GLES30.glGetUniformLocation(rainProg, "uRain");
        uRainAspect = GLES30.glGetUniformLocation(rainProg, "uAspect");
        uRainTint = GLES30.glGetUniformLocation(rainProg, "uTint");

        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glCullFace(GLES30.GL_BACK);
        GLES30.glFrontFace(GLES30.GL_CCW);
        GLES30.glClearColor(0.55f, 0.68f, 0.78f, 1f);   // replaced per frame

        buildSky();
        buildProps();
        Engine e = host.engine();
        if (e != null) {
            mesher = new ChunkMesher(e.world);
            buildSea(e.quality.seaGrid);
            startWorkers(e);
        }
        host.markGlReady();
        Log.i(GLUtil.TAG, "GL ready, programs linked");
    }

    private void startWorkers(Engine e) {
        int n = Math.max(1, Math.min(3,
                Runtime.getRuntime().availableProcessors() / 2));
        workers = new Thread[n];
        for (int i = 0; i < n; i++) {
            // Each worker owns its own mesher: they allocate scratch arrays
            // and sharing one would need a lock on the hot path.
            final ChunkMesher own = new ChunkMesher(e.world);
            Thread t = new Thread(() -> {
                while (running) {
                    WorldStreamer.Tile tile = e.streamer.nextForDecode();
                    if (tile == null) {
                        try { Thread.sleep(4); } catch (InterruptedException ie) { return; }
                        continue;
                    }
                    try {
                        e.streamer.decode(tile, own);
                    } catch (Throwable err) {
                        Log.w(GLUtil.TAG, "decode failed for tile " + tile.id, err);
                    }
                }
            }, "chunk-decode-" + i);
            t.setPriority(Thread.MIN_PRIORITY + 2);
            t.setDaemon(true);
            t.start();
            workers[i] = t;
        }
        Log.i(GLUtil.TAG, n + " decode workers started");
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        aspect = (float) width / (float) height;
        GLES30.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        Engine e = host.engine();
        if (e == null) {
            GLES30.glClearColor(0.04f, 0.06f, 0.09f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            return;
        }
        if (mesher == null) {
            mesher = new ChunkMesher(e.world);
            buildSea(e.quality.seaGrid);
            startWorkers(e);
        }

        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 1.0 / 60.0 : (now - lastNanos) / 1e9;
        lastNanos = now;
        frameCount++;

        host.beforeSimulation(dt);
        e.frame(dt, input);

        Quality q = e.quality;
        if (seaGridBuilt != q.seaGrid) { buildSea(q.seaGrid); }

        // Bounded GPU upload. More than a handful of buffer creations in one
        // frame stalls the driver and shows as a hitch exactly when the
        // player is moving fastest, which is the worst possible moment.
        uploadScratch.clear();
        uploadScratch.addAll(e.streamer.pollReady(q.uploadsPerFrame));
        for (WorldStreamer.Tile t : uploadScratch) {
            try {
                e.streamer.markUploaded(t, uploadTile(t));
            } catch (Throwable err) {
                Log.w(GLUtil.TAG, "upload failed for tile " + t.id, err);
                e.streamer.markUploadFailed(t);
            }
        }
        for (WorldStreamer.Tile t : e.streamer.pollEvictions(6)) {
            releaseTile(t);
            e.streamer.markReleased(t);
        }

        drawScene(e, q);
        host.afterFrame(frameCount, dt);
    }

    // ---------------------------------------------------------------- drawing

    private void drawScene(Engine e, Quality q) {
        e.cameraPosition(camPos);
        target.set(e.renderPosition());

        float far = (float) Math.max(3000, q.farRadius);
        float near = 0.35f;
        GLUtil.perspective(proj, 62f, width / (float) height, near, far);
        // The camera matrix is built in render space, the floating-origin
        // frame. World doubles never reach the GPU.
        float ox = (float) (camPos.x - e.origin.offsetX());
        float oy = (float) camPos.y;
        float oz = (float) (camPos.z - e.origin.offsetZ());
        float tx = (float) (target.x - e.origin.offsetX());
        float ty = (float) (target.y + 1.2);
        float tz = (float) (target.z - e.origin.offsetZ());
        GLUtil.lookAt(view, ox, oy, oz, tx, ty, tz, 0, 1, 0);
        GLUtil.multiply(viewProj, proj, view);
        frustum.set(viewProj);

        // Time of day comes from the simulation, which owns the clock, and
        // the palette from core's Sky, which is tested for continuity across
        // the whole twenty-four hours. The renderer only consumes it.
        float[] sunDir = e.sky.sunDir;
        float[] sky = e.sky.skyColour;
        float[] fog = e.sky.fogColour;
        float[] sun = e.sky.sunColour;
        nightFactor = e.sky.nightFactor;

        // Visibility: clear air in Mumbai is good for tens of kilometres, but
        // the monsoon and the haze cut it hard. Sky supplies the atmospheric
        // part; this floor ties it to the far plane as well, so chunk pop-in
        // always happens inside the fog whatever the weather is doing.
        float fogDensity = Math.max(e.sky.fogDensity,
                (float) (1.35 / Math.max(900, q.farRadius * 0.9)));

        // Clear to the fog colour so anything beyond the far plane
        // dissolves into the same air the near geometry fades into.
        GLES30.glClearColor(fog[0], fog[1], fog[2], 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

        // --- sky, first, with depth writes off -------------------------------
        GLES30.glDepthMask(false);
        GLES30.glUseProgram(skyProg);
        GLES30.glUniform3fv(uSkySky, 1, sky, 0);
        GLES30.glUniform3fv(uSkyFog, 1, fog, 0);
        GLES30.glUniform1f(uSkyHorizon, (float) Math.sin(e.cameraPitch) * 1.2f);
        GLES30.glBindVertexArray(skyVao);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glDepthMask(true);

        // --- terrain and buildings --------------------------------------------
        GLES30.glUseProgram(sceneProg);
        GLES30.glUniformMatrix4fv(uSceneViewProj, 1, false, viewProj, 0);
        GLES30.glUniform3f(uSceneCam, ox, oy, oz);
        GLES30.glUniform3fv(uSceneSunDir, 1, sunDir, 0);
        GLES30.glUniform3fv(uSceneSunCol, 1, sun, 0);
        GLES30.glUniform3fv(uSceneSky, 1, sky, 0);
        GLES30.glUniform3fv(uSceneFogCol, 1, fog, 0);
        GLES30.glUniform1f(uSceneFogDen, fogDensity);
        GLES30.glUniform1f(uSceneWet, (float) e.wetness);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, atlas);
        GLES30.glUniform1i(uSceneAtlas, 0);
        GLES30.glUniform1fv(uSceneMatScale, TextureLibrary.MAT_COUNT,
                TextureLibrary.SCALE_M, 0);
        GLES30.glUniform1f(uSceneNight, nightFactor);

        int drawn = 0;
        long tris = 0;
        for (WorldStreamer.Tile t : e.streamer.residentTiles()) {
            if (!t.isActive() || !(t.gpu instanceof TileGpu)) { continue; }
            TileGpu g = (TileGpu) t.gpu;
            float cx = (float) (t.originX - e.origin.offsetX());
            float cz = (float) (t.originZ - e.origin.offsetZ());
            float half = (float) (e.world.chunkM * 0.5);
            if (!frustum.boxVisible(cx, g.minY - 2, cz,
                    cx + (float) e.world.chunkM, g.maxY + 2,
                    cz + (float) e.world.chunkM)) {
                continue;
            }
            GLES30.glUniform3f(uSceneOrigin, cx, 0f, cz);
            GLES30.glBindVertexArray(g.vao);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, g.indexCount,
                    GLES30.GL_UNSIGNED_INT, 0);
            drawn++;
            tris += g.indexCount / 3;
        }

        // --- the sea ----------------------------------------------------------
        GLES30.glUseProgram(seaProg);
        GLES30.glUniformMatrix4fv(uSeaViewProj, 1, false, viewProj, 0);
        GLES30.glUniform3f(uSeaCam, ox, oy, oz);
        GLES30.glUniform1f(uSeaTime, (float) e.ocean.time());
        GLES30.glUniform1f(uSeaExtent, (float) Math.min(9000, q.farRadius));
        GLES30.glUniform1f(uSeaTide, (float) e.ocean.tide());
        GLES30.glUniform1f(uSeaChop, (float) e.ocean.choppiness());
        GLES30.glUniform3fv(uSeaSunDir, 1, sunDir, 0);
        GLES30.glUniform3fv(uSeaSunCol, 1, sun, 0);
        GLES30.glUniform3fv(uSeaSky, 1, sky, 0);
        GLES30.glUniform3fv(uSeaFogCol, 1, fog, 0);
        GLES30.glUniform1f(uSeaFogDen, fogDensity);
        GLES30.glBindVertexArray(seaVao);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, seaIndexCount,
                GLES30.GL_UNSIGNED_INT, 0);
        drawn++;
        tris += seaIndexCount / 3;

        // --- traffic, instanced per vehicle type --------------------------------
        int instances = uploadInstances(e);
        if (instances > 0) {
            GLES30.glUseProgram(instProg);
            GLES30.glUniformMatrix4fv(uInstViewProj, 1, false, viewProj, 0);
            GLES30.glUniform3f(uInstCam, ox, oy, oz);
            // Locations are cached in onSurfaceCreated. Looking them up by
            // string every frame, as this used to, is a driver-side hash of
            // eight names per frame for a value that never changes.
            GLES30.glUniform3fv(uInstSunDir, 1, sunDir, 0);
            GLES30.glUniform3fv(uInstSunCol, 1, sun, 0);
            GLES30.glUniform3fv(uInstSky, 1, sky, 0);
            GLES30.glUniform3fv(uInstFogCol, 1, fog, 0);
            GLES30.glUniform1f(uInstFogDen, fogDensity);
            GLES30.glUniform1f(uInstWet, (float) e.wetness);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, atlas);
            GLES30.glUniform1i(uInstAtlas, 0);
            GLES30.glUniform1fv(uInstMatScale, TextureLibrary.MAT_COUNT,
                    TextureLibrary.SCALE_M, 0);
            GLES30.glUniform1f(uInstNight, nightFactor);
            for (int ki = 0; ki < vehCount.length; ki++) {
                int n = vehCount[ki];
                if (n <= 0) { continue; }
                VehicleGpu g = vehGpu[ki];
                GLES30.glBindVertexArray(g.vao);
                GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, g.indexCount,
                        GLES30.GL_UNSIGNED_INT, 0, n);
                drawn++;
                tris += (long) (g.indexCount / 3) * n;
            }
        }

        // --- the job checkpoint ------------------------------------------------
        double[] mk = e.missions.markerAt();
        if (mk != null) {
            double mdx = mk[0] - ox, mdz = mk[2] - oz;
            if (mdx * mdx + mdz * mdz < q.farRadius * q.farRadius) {
                if (instances <= 0) {
                    // The instanced program is only set up above when there
                    // was traffic to draw. A marker in an empty stretch of
                    // the city still has to be visible.
                    GLES30.glUseProgram(instProg);
                    GLES30.glUniformMatrix4fv(uInstViewProj, 1, false, viewProj, 0);
                    GLES30.glUniform3f(uInstCam, ox, oy, oz);
                    GLES30.glUniform3fv(uInstSunDir, 1, sunDir, 0);
                    GLES30.glUniform3fv(uInstSunCol, 1, sun, 0);
                    GLES30.glUniform3fv(uInstSky, 1, sky, 0);
                    GLES30.glUniform3fv(uInstFogCol, 1, fog, 0);
                    GLES30.glUniform1f(uInstFogDen, fogDensity);
                    GLES30.glUniform1f(uInstWet, (float) e.wetness);
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, atlas);
                    GLES30.glUniform1i(uInstAtlas, 0);
                    GLES30.glUniform1fv(uInstMatScale, TextureLibrary.MAT_COUNT,
                            TextureLibrary.SCALE_M, 0);
                    GLES30.glUniform1f(uInstNight, nightFactor);
                }
                markerScratch.position(0);
                markerScratch.put((float) (mk[0] - ox));
                markerScratch.put((float) (mk[1] - oy + 0.05));
                markerScratch.put((float) (mk[2] - oz));
                markerScratch.put(0f);
                markerScratch.position(0);
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, markerGpu.instVbo);
                GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 16, markerScratch);
                // Additive, and with depth writes off, so the cylinder glows
                // over whatever is behind it instead of z-fighting the road
                // it is standing on.
                GLES30.glEnable(GLES30.GL_BLEND);
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
                GLES30.glDepthMask(false);
                GLES30.glDisable(GLES30.GL_CULL_FACE);
                GLES30.glBindVertexArray(markerGpu.vao);
                GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES,
                        markerGpu.indexCount, GLES30.GL_UNSIGNED_INT, 0, 1);
                GLES30.glEnable(GLES30.GL_CULL_FACE);
                GLES30.glDepthMask(true);
                GLES30.glDisable(GLES30.GL_BLEND);
                drawn++;
                tris += markerGpu.indexCount / 3;
            }
        }

        // --- monsoon, over the finished frame ---------------------------------
        if (e.weather.rain > 0.02) {
            GLES30.glUseProgram(rainProg);
            // Wrapped to ten seconds: the shader is mediump and each
            // layer's speed times ten is whole, so the wrap lands on every
            // layer's period and cannot be seen.
            GLES30.glUniform1f(uRainTime, (float) (e.ocean.time() % 10.0));
            GLES30.glUniform1f(uRainAmount, (float) e.weather.rain);
            GLES30.glUniform1f(uRainAspect, aspect);
            // Tinted towards the fog so rain reads as part of the weather
            // rather than white scratches laid over it.
            GLES30.glUniform3f(uRainTint,
                    fog[0] * 0.45f + 0.52f,
                    fog[1] * 0.45f + 0.55f,
                    fog[2] * 0.45f + 0.60f);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            GLES30.glDepthMask(false);
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glBindVertexArray(skyVao);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glDepthMask(true);
            GLES30.glDisable(GLES30.GL_BLEND);
            drawn++;
        }

        GLES30.glBindVertexArray(0);
        e.telemetry.drawCalls = drawn;
        e.telemetry.trianglesK = (int) (tris / 1000);
    }

    /**
     * Uploads the procedural atlas as a single {@code GL_TEXTURE_2D_ARRAY}.
     *
     * <p>An array rather than one packed sheet, because a packed atlas bleeds:
     * at a mip level or a grazing angle the sampler reaches past a tile's
     * edge and pulls in its neighbour, and with GL_REPEAT UVs derived from
     * world position there is no sub-rectangle to clamp to. Array layers each
     * wrap independently, which is exactly the behaviour the tiling needs.
     */
    private int buildAtlas() {
        int[] id = new int[1];
        GLES30.glGenTextures(1, id, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, id[0]);

        int size = TextureLibrary.SIZE;
        int levels = 1;
        while ((size >> levels) > 0) { levels++; }
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, levels, GLES30.GL_RGBA8,
                size, size, TextureLibrary.MAT_COUNT);

        ByteBuffer buf = ByteBuffer.allocateDirect(size * size * 4)
                .order(ByteOrder.nativeOrder());
        long t0 = System.nanoTime();
        for (int m = 0; m < TextureLibrary.MAT_COUNT; m++) {
            byte[] tile = TextureLibrary.tile(m);
            buf.clear();
            buf.put(tile);
            buf.position(0);
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, m,
                    size, size, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
        }
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D_ARRAY);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,
                GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,
                GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,
                GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,
                GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT);
        Log.i(GLUtil.TAG, "atlas: " + TextureLibrary.MAT_COUNT + " layers of "
                + size + "x" + size + " in "
                + ((System.nanoTime() - t0) / 1000000L) + " ms");
        return id[0];
    }

    // ---------------------------------------------------------------- buffers

    private static final class TileGpu {
        int vao, vbo, ibo, indexCount;
        float minY, maxY;
    }

    private Object uploadTile(WorldStreamer.Tile t) {
        ChunkMesher.Mesh m = t.mesh;
        if (m == null || m.indexCount == 0) { return null; }
        TileGpu g = new TileGpu();
        g.indexCount = m.indexCount;
        g.minY = m.minY;
        g.maxY = m.maxY;

        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        g.vao = ids[0];
        GLES30.glBindVertexArray(g.vao);

        int[] bufs = new int[2];
        GLES30.glGenBuffers(2, bufs, 0);
        g.vbo = bufs[0];
        g.ibo = bufs[1];

        FloatBuffer vb = ByteBuffer
                .allocateDirect(m.vertexCount * ChunkMesher.STRIDE * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vb.put(m.vertices, 0, m.vertexCount * ChunkMesher.STRIDE).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,
                m.vertexCount * ChunkMesher.STRIDE * 4, vb, GLES30.GL_STATIC_DRAW);

        IntBuffer ib = ByteBuffer.allocateDirect(m.indexCount * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(m.indices, 0, m.indexCount).position(0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.ibo);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indexCount * 4, ib,
                GLES30.GL_STATIC_DRAW);

        int stride = ChunkMesher.STRIDE * 4;
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 36);
        GLES30.glEnableVertexAttribArray(3);
        GLES30.glBindVertexArray(0);
        return g;
    }

    private void releaseTile(WorldStreamer.Tile t) {
        if (!(t.gpu instanceof TileGpu)) { return; }
        TileGpu g = (TileGpu) t.gpu;
        GLES30.glDeleteBuffers(2, new int[]{g.vbo, g.ibo}, 0);
        GLES30.glDeleteVertexArrays(1, new int[]{g.vao}, 0);
        t.gpu = null;
    }

    private void buildSea(int grid) {
        if (seaVao != 0) {
            GLES30.glDeleteBuffers(2, new int[]{seaVbo, seaIbo}, 0);
            GLES30.glDeleteVertexArrays(1, new int[]{seaVao}, 0);
        }
        seaGridBuilt = grid;
        int n = grid + 1;
        float[] verts = new float[n * n * 2];
        int p = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                // Centred on the camera, and biased so that resolution is
                // concentrated near it rather than spread evenly out to the
                // horizon where it buys nothing.
                double u = i / (double) grid * 2 - 1;
                double v = j / (double) grid * 2 - 1;
                verts[p++] = (float) (u * Math.abs(u));
                verts[p++] = (float) (v * Math.abs(v));
            }
        }
        int[] idx = new int[grid * grid * 6];
        int q = 0;
        for (int j = 0; j < grid; j++) {
            for (int i = 0; i < grid; i++) {
                int a = j * n + i, b = a + 1, c = a + n, d = c + 1;
                idx[q++] = a; idx[q++] = c; idx[q++] = b;
                idx[q++] = b; idx[q++] = c; idx[q++] = d;
            }
        }
        seaIndexCount = idx.length;

        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        seaVao = ids[0];
        GLES30.glBindVertexArray(seaVao);
        int[] bufs = new int[2];
        GLES30.glGenBuffers(2, bufs, 0);
        seaVbo = bufs[0];
        seaIbo = bufs[1];

        FloatBuffer vb = GLUtil.floats(verts.length);
        vb.put(verts).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, seaVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.length * 4, vb,
                GLES30.GL_STATIC_DRAW);
        IntBuffer ib = ByteBuffer.allocateDirect(idx.length * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(idx).position(0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, seaIbo);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.length * 4, ib,
                GLES30.GL_STATIC_DRAW);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glBindVertexArray(0);
    }

    private void buildSky() {
        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        skyVao = ids[0];
        GLES30.glBindVertexArray(skyVao);
        GLES30.glGenBuffers(1, ids, 0);
        skyVbo = ids[0];
        // One oversized triangle covers the screen with no seam down the
        // middle and one fewer vertex than a quad.
        float[] tri = {-1, -1, 3, -1, -1, 3};
        FloatBuffer b = GLUtil.floats(6);
        b.put(tri).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, skyVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 24, b, GLES30.GL_STATIC_DRAW);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glBindVertexArray(0);
    }

    /** One mesh and one instance buffer for every vehicle type. */
    private void buildProps() {
        vehScratch = new FloatBuffer[VehicleKind.values().length];
        for (VehicleKind k : VehicleKind.values()) {
            vehGpu[k.ordinal()] = buildVehicleGpu(k);
            vehScratch[k.ordinal()] = GLUtil.floats(MAX_PER_KIND * 4);
        }
        markerGpu = buildInstancedGpu(Meshes.markerVertices(),
                Meshes.markerIndices());
        markerScratch = GLUtil.floats(4);
        buildLegacyProp();
    }

    private VehicleGpu buildVehicleGpu(VehicleKind kind) {
        return buildInstancedGpu(Meshes.vehicleVertices(kind),
                Meshes.vehicleIndices(kind));
    }

    /** One VAO with a per-instance vec4(x, y, z, yaw) stream at attribute 4. */
    private VehicleGpu buildInstancedGpu(float[] v, int[] i) {
        VehicleGpu g = new VehicleGpu();
        g.indexCount = i.length;

        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        g.vao = ids[0];
        GLES30.glBindVertexArray(g.vao);
        int[] bufs = new int[3];
        GLES30.glGenBuffers(3, bufs, 0);
        g.vbo = bufs[0];
        g.ibo = bufs[1];
        g.instVbo = bufs[2];

        FloatBuffer vb = GLUtil.floats(v.length);
        vb.put(v).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.length * 4, vb,
                GLES30.GL_STATIC_DRAW);
        int stride = ChunkMesher.STRIDE * 4;
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 36);
        GLES30.glEnableVertexAttribArray(3);

        IntBuffer ib2 = ByteBuffer.allocateDirect(i.length * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        ib2.put(i).position(0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.ibo);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, i.length * 4, ib2,
                GLES30.GL_STATIC_DRAW);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.instVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MAX_PER_KIND * 16, null,
                GLES30.GL_DYNAMIC_DRAW);
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, 16, 0);
        GLES30.glEnableVertexAttribArray(4);
        GLES30.glVertexAttribDivisor(4, 1);
        GLES30.glBindVertexArray(0);
        return g;
    }

    private void buildLegacyProp() {
        float[] v = Meshes.genericCar();
        int[] i = Meshes.genericCarIndices();
        propIndexCount = i.length;

        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        propVao = ids[0];
        GLES30.glBindVertexArray(propVao);
        int[] bufs = new int[3];
        GLES30.glGenBuffers(3, bufs, 0);
        propVbo = bufs[0];
        propIbo = bufs[1];
        propInstVbo = bufs[2];

        FloatBuffer vb = GLUtil.floats(v.length);
        vb.put(v).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, propVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.length * 4, vb,
                GLES30.GL_STATIC_DRAW);
        int stride = ChunkMesher.STRIDE * 4;
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 36);
        GLES30.glEnableVertexAttribArray(3);

        IntBuffer ib = ByteBuffer.allocateDirect(i.length * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(i).position(0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, propIbo);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, i.length * 4, ib,
                GLES30.GL_STATIC_DRAW);

        // attribute 4: vec4(x, y, z, yaw), one per instance
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, propInstVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 4 * 4 * 512, null,
                GLES30.GL_DYNAMIC_DRAW);
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, 16, 0);
        GLES30.glEnableVertexAttribArray(4);
        GLES30.glVertexAttribDivisor(4, 1);
        GLES30.glBindVertexArray(0);
    }

    /**
     * Sorts visible traffic into one instance buffer per vehicle type.
     *
     * <p>The player's own vehicle is included. With a chase camera it is the
     * single most looked-at object on screen, and leaving it out - which is
     * what happened while this drew one shared mesh - meant driving an
     * invisible car.
     *
     * @return total instances across all kinds
     */
    private int uploadInstances(Engine e) {
        for (int i = 0; i < vehCount.length; i++) {
            vehCount[i] = 0;
            vehScratch[i].position(0);
        }

        Agents.Car[] cars = e.agents.cars();
        int total = 0;
        for (Agents.Car c : cars) {
            if (!c.active) { continue; }
            int ki = c.kindOrdinal;
            if (ki < 0 || ki >= vehCount.length) { ki = VehicleKind.HATCHBACK.ordinal(); }
            if (vehCount[ki] >= MAX_PER_KIND) { continue; }
            float x = (float) (c.x - e.origin.offsetX());
            float z = (float) (c.z - e.origin.offsetZ());
            // Cull against the vehicle's own size, not a single fixed radius:
            // a 10 m bus popped out of view at the screen edge when it was
            // tested against a car's 3.2 m sphere.
            float r = (float) VehicleKind.values()[ki].lengthM * 0.62f;
            if (!frustum.sphereVisible(x, (float) c.y + 0.9f, z, r)) { continue; }
            vehScratch[ki].put(x).put((float) c.y).put(z).put((float) c.heading);
            vehCount[ki]++;
            total++;
        }

        // The player's vehicle, drawn from the physics body rather than from
        // an agent, so it shows the interpolated render pose.
        VehicleKind pk = e.drivingKind();
        if (pk != null) {
            int ki = pk.ordinal();
            if (vehCount[ki] < MAX_PER_KIND) {
                Vec3 rp = e.renderPosition();
                float x = (float) (rp.x - e.origin.offsetX());
                float z = (float) (rp.z - e.origin.offsetZ());
                // The body origin sits at the centre of mass; the mesh origin
                // is on the ground under it.
                float y = (float) (rp.y - pk.comHeightM - 0.22);
                vehScratch[ki].put(x).put(y).put(z)
                        .put((float) e.renderOrientation().heading());
                vehCount[ki]++;
                total++;
            }
        }

        if (total == 0) { return 0; }
        for (int ki = 0; ki < vehCount.length; ki++) {
            int n = vehCount[ki];
            if (n <= 0) { continue; }
            vehScratch[ki].position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vehGpu[ki].instVbo);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 16, vehScratch[ki]);
        }
        return total;
    }

    void shutdown() {
        running = false;
        if (workers != null) {
            for (Thread t : workers) { t.interrupt(); }
        }
    }

    /** What the renderer needs from whatever is hosting it. */
    interface GameHost {
        Engine engine();

        void markGlReady();

        void beforeSimulation(double dt);

        void afterFrame(long frameNumber, double dt);
    }
}
