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
import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.world.CraftSpawns;
import ai.shuvrajeetkamila.mumbai.physics.Ocean;
import ai.shuvrajeetkamila.mumbai.render.SeaMask;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.sim.Transit;
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
    private int uSceneAtlas, uSceneMatScale, uSceneNight, uSceneSignal;
    // The coastal strip is lifted onto the sea by the terrain's vertex
    // stage, so the terrain program carries the same wave set the sea does.
    private int uSceneWaveAmp, uSceneWaveK, uSceneWaveDirX, uSceneWaveDirZ;
    private int uSceneWavePhase, uSceneWorldOffset, uSceneTide;
    private int uInstViewProj, uInstCam, uInstSunDir, uInstSunCol, uInstSky;
    private int uInstFogCol, uInstFogDen, uInstWet, uInstAtlas, uInstMatScale, uInstNight;
    private int uInstSiren;

    /** The procedural texture array. One GL_TEXTURE_2D_ARRAY, built once. */
    private int atlas;
    /** 0 in daylight, 1 after dark. Set from the sun elevation each frame. */
    private float nightFactor;
    private int uSeaViewProj, uSeaCam, uSeaExtent, uSeaTide;
    private int uSeaLandXform, uSeaLandMask;
    private int uSeaWaveAmp, uSeaWaveK, uSeaWaveDirX, uSeaWaveDirZ;
    private int uSeaWavePhase, uSeaWorldOffset;
    /** Wave set, refilled from Ocean every frame. Five groups of four. */
    private final float[] waveParams = new float[Ocean.WAVES * 5];
    /** The land mask on the GPU, so the sea knows where the coast is. */
    private int landMaskTex;
    private final float[] landXform = new float[4];
    private int uSeaSunDir, uSeaSunCol, uSeaSky, uSeaFogCol, uSeaFogDen;
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
        int instStride = 16;
        /**
         * How many instances the buffer was allocated for.
         *
         * <p>Drawing more instances than the buffer holds is a read past
         * the end of a vertex buffer. A hardware driver usually returns
         * rubbish; SwiftShader segfaults, and because it lives inside
         * the emulator process the whole device vanishes with no crash
         * log and no Java exception - which is precisely the failure
         * being chased here.
         */
        int maxInstances;
        /** Name, for the breadcrumb log. */
        String what = "?";
    }

    private final VehicleGpu[] vehGpu = new VehicleGpu[VehicleKind.values().length];
    private VehicleGpu markerGpu;

    /** Pedestrians, one buffer per outfit. */
    private final VehicleGpu[] pedGpu = new VehicleGpu[Meshes.PED_OUTFITS];
    private final int[] pedCount = new int[Meshes.PED_OUTFITS];
    private final FloatBuffer[] pedScratch = new FloatBuffer[Meshes.PED_OUTFITS];
    private static final int MAX_PEDS = 140;

    /** Blob shadows, one buffer per size. */
    private final VehicleGpu[] shadowGpu = new VehicleGpu[Meshes.SHADOW_COUNT];
    private final int[] shadowCount = new int[Meshes.SHADOW_COUNT];
    private final FloatBuffer[] shadowScratch = new FloatBuffer[Meshes.SHADOW_COUNT];
    private static final int MAX_SHADOWS = 180;
    /** One VAO per transit body: suburban, metro, monorail, vessel. */
    private final VehicleGpu[] transitGpu = new VehicleGpu[4];
    /** Boats, helicopters and aeroplanes: moored ones and the one flown. */
    private final VehicleGpu[] craftGpu = new VehicleGpu[Meshes.CRAFT_COUNT];
    private final int[] craftCount = new int[Meshes.CRAFT_COUNT];
    private FloatBuffer[] craftScratch;
    private static final int MAX_CRAFT = 48;
    private final Vec3 craftCam = new Vec3();
    private final FloatBuffer[] transitScratch = new FloatBuffer[4];
    private final int[] transitCount = new int[4];
    /** Carriages of one kind that can be on screen at once. */
    private static final int MAX_CARRIAGES = 160;

    /** Squared distance of the furthest instance uploaded this frame. */
    private double maxInstSq;

    /** Records one uploaded instance for the render-space tripwire. */
    private void noteInstance(double x, double y, double z) {
        double d = x * x + y * y + z * z;
        if (d > maxInstSq) { maxInstSq = d; }
    }
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
        uSceneSignal = GLES30.glGetUniformLocation(sceneProg, "uSignal");

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
        uInstSiren = GLES30.glGetUniformLocation(instProg, "uSiren");

        atlas = buildAtlas();

        uSeaViewProj = GLES30.glGetUniformLocation(seaProg, "uViewProj");
        uSeaCam = GLES30.glGetUniformLocation(seaProg, "uCamera");
        uSeaExtent = GLES30.glGetUniformLocation(seaProg, "uExtent");
        uSeaTide = GLES30.glGetUniformLocation(seaProg, "uTide");
        uSeaLandXform = GLES30.glGetUniformLocation(seaProg, "uLandXform");
        uSeaWaveAmp = GLES30.glGetUniformLocation(seaProg, "uWaveAmp");
        uSeaWaveK = GLES30.glGetUniformLocation(seaProg, "uWaveK");
        uSeaWaveDirX = GLES30.glGetUniformLocation(seaProg, "uWaveDirX");
        uSeaWaveDirZ = GLES30.glGetUniformLocation(seaProg, "uWaveDirZ");
        uSeaWavePhase = GLES30.glGetUniformLocation(seaProg, "uWavePhase");
        uSeaWorldOffset = GLES30.glGetUniformLocation(seaProg, "uWorldOffset");
        uSeaLandMask = GLES30.glGetUniformLocation(seaProg, "uLandMask");
        uSeaSunDir = GLES30.glGetUniformLocation(seaProg, "uSunDir");
        uSeaSunCol = GLES30.glGetUniformLocation(seaProg, "uSunColour");
        uSeaSky = GLES30.glGetUniformLocation(seaProg, "uSkyColour");
        uSeaFogCol = GLES30.glGetUniformLocation(seaProg, "uFogColour");
        uSeaFogDen = GLES30.glGetUniformLocation(seaProg, "uFogDensity");

        uSkySky = GLES30.glGetUniformLocation(skyProg, "uSkyColour");
        uSkyFog = GLES30.glGetUniformLocation(skyProg, "uFogColour");
        uSkyHorizon = GLES30.glGetUniformLocation(skyProg, "uHorizon");
        uSceneWaveAmp = GLES30.glGetUniformLocation(sceneProg, "uWaveAmp");
        uSceneWaveK = GLES30.glGetUniformLocation(sceneProg, "uWaveK");
        uSceneWaveDirX = GLES30.glGetUniformLocation(sceneProg, "uWaveDirX");
        uSceneWaveDirZ = GLES30.glGetUniformLocation(sceneProg, "uWaveDirZ");
        uSceneWavePhase = GLES30.glGetUniformLocation(sceneProg, "uWavePhase");
        uSceneWorldOffset = GLES30.glGetUniformLocation(sceneProg, "uWorldOffset");
        uSceneTide = GLES30.glGetUniformLocation(sceneProg, "uTide");
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
        // Everything built against the old context died with it. The
        // handles did not: they are small integers, and the driver hands
        // the same numbers out again for entirely different objects, so
        // anything still holding one draws whatever owns that name now.
        // The programs, the atlas and the props above are rebuilt
        // unconditionally; these two were not, and this is what came back
        // after the app had been in the background - a land mask that was
        // no longer a land mask, so the sea stopped knowing where the
        // coast was, and chunk meshes pointing at nothing.
        landMaskTex = 0;
        seaGridBuilt = 0;

        Engine e = host.engine();
        if (e != null) {
            mesher = new ChunkMesher(e.world);
            buildSea(e.quality.seaGrid);
            e.streamer.forgetGpu();
            // The decode workers touch no GL and survive a lost context.
            // Starting a second set would leave the first running for
            // ever, both pulling from the same queue.
            if (workers == null) { startWorkers(e); }
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
        // Meshes replaced by a detail-level rebuild. Every one of these
        // was previously overwritten and never deleted: driving across
        // the city leaked a tile's worth of GPU memory per LOD change,
        // and a driver with no memory left starts refusing uploads.
        for (Object handle : e.streamer.pollDisplaced(12)) {
            releaseHandle(handle);
        }

        drawScene(e, q);
        host.afterFrame(frameCount, dt);
    }

    // ---------------------------------------------------------------- drawing

    private void drawScene(Engine e, Quality q) {
        e.cameraPosition(camPos);
        target.set(e.renderPosition());

        // Where the ground really stops, which is not the configured far
        // radius: the streamer clamps its outer ring to the tile budget.
        // The fog and the far plane are both set from this so the edge of
        // the streamed world is always inside the haze rather than a line
        // of bare sky across the city.
        double reach = Math.min(q.farRadius, e.streamer.effectiveRadius());
        float far = (float) Math.max(3000, reach);
        // Tied to ChunkMesher.ROAD_LIFT_M: a 24-bit depth buffer resolves
        // about d*d/(near * 2^24) metres at distance d, so the near plane
        // is what decides how far away road surfaces can be drawn before
        // they start fighting the ground they lie on. At 0.35 that was
        // 700 m, which is well inside the city.
        float near = (float) ChunkMesher.NEAR_PLANE_M;
        GLUtil.perspective(proj, 62f, width / (float) height, near, far);
        // The camera matrix is built in render space, the floating-origin
        // frame. World doubles never reach the GPU.
        // The camera, in render space. This is what the view matrix and
        // the shaders' uCamera want. It is NOT a world-to-render origin,
        // and four of the upload paths used to treat it as one - see the
        // uploadX calls below, which take e.origin.offsetX/Z.
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
                (float) (1.35 / Math.max(900, reach * 0.9)));

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
        // The signal clock. Wrapped to a whole number of cycles so
        // a long session cannot lose precision in the fraction, and
        // taken from the same simulated seconds the traffic uses.
        GLES30.glUniform1f(uSceneSignal, (float)
                (e.clock.simulatedSeconds()
                        % ai.shuvrajeetkamila.mumbai.world.Junctions.CYCLE_S));
        // The coastal strip is lifted onto the sea surface by the vertex
        // stage, so the terrain needs the same wave set the sea has.
        e.ocean.waveParameters(waveParams);
        GLES30.glUniform4fv(uSceneWaveAmp, 1, waveParams, 0);
        GLES30.glUniform4fv(uSceneWaveK, 1, waveParams, Ocean.WAVES);
        GLES30.glUniform4fv(uSceneWaveDirX, 1, waveParams, Ocean.WAVES * 2);
        GLES30.glUniform4fv(uSceneWaveDirZ, 1, waveParams, Ocean.WAVES * 3);
        GLES30.glUniform4fv(uSceneWavePhase, 1, waveParams, Ocean.WAVES * 4);
        GLES30.glUniform2f(uSceneWorldOffset, (float) e.origin.offsetX(),
                (float) e.origin.offsetZ());
        GLES30.glUniform1f(uSceneTide, (float) e.ocean.tide());

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
        // Out to the far plane, not to a fixed nine kilometres. The
        // terrain used to draw its own flat water beyond the sea grid and
        // hide the edge; it no longer draws water at all, so the grid has
        // to reach the fog on its own or the sea visibly stops - which
        // from a helicopter at five hundred metres is most of the view.
        // The grid is spaced quadratically, so widening it costs nothing
        // near the camera.
        GLES30.glUniform1f(uSeaExtent, (float) reach);
        GLES30.glUniform1f(uSeaTide, (float) e.ocean.tide());
        // The wave set, straight from the model the boats float on. There
        // is no copy of these numbers in the shader any more.
        e.ocean.waveParameters(waveParams);
        GLES30.glUniform4fv(uSeaWaveAmp, 1, waveParams, 0);
        GLES30.glUniform4fv(uSeaWaveK, 1, waveParams, Ocean.WAVES);
        GLES30.glUniform4fv(uSeaWaveDirX, 1, waveParams, Ocean.WAVES * 2);
        GLES30.glUniform4fv(uSeaWaveDirZ, 1, waveParams, Ocean.WAVES * 3);
        GLES30.glUniform4fv(uSeaWavePhase, 1, waveParams, Ocean.WAVES * 4);
        GLES30.glUniform2f(uSeaWorldOffset, (float) e.origin.offsetX(),
                (float) e.origin.offsetZ());
        GLES30.glUniform3fv(uSeaSunDir, 1, sunDir, 0);
        GLES30.glUniform3fv(uSeaSunCol, 1, sun, 0);
        GLES30.glUniform3fv(uSeaSky, 1, sky, 0);
        GLES30.glUniform3fv(uSeaFogCol, 1, fog, 0);
        GLES30.glUniform1f(uSeaFogDen, fogDensity);
        if (landMaskTex == 0) { landMaskTex = buildLandMask(e.world); }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, landMaskTex);
        GLES30.glUniform1i(uSeaLandMask, 1);
        // Recomputed every frame: the sea shader works in render space,
        // so the mask's world origin has to be rebased along with
        // everything else or the coastline slides out from under it.
        SeaMask.transform(e.world, e.origin.offsetX(), e.origin.offsetZ(),
                landXform);
        GLES30.glUniform4fv(uSeaLandXform, 1, landXform, 0);
        GLES30.glBindVertexArray(seaVao);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, seaIndexCount,
                GLES30.GL_UNSIGNED_INT, 0);
        drawn++;
        tris += seaIndexCount / 3;

        // --- blob shadows, under everything that moves --------------------------
        // Not a shadow map: a dark disc on the ground under each body,
        // which is what the games this is modelled on did and what the
        // frame budget here allows. Drawn before the traffic so the
        // vehicles sit on top of their own shadows.
        int shadows = uploadShadows(e);
        if (shadows > 0) {
            setupInstanced(e, viewProj, ox, oy, oz, sunDir, sun, sky, fog,
                    fogDensity, nightFactor);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            GLES30.glDepthMask(false);
            for (int i = 0; i < shadowGpu.length; i++) {
                int n = shadowCount[i];
                if (n <= 0) { continue; }
                if (!drawInstanced(shadowGpu[i], n, "shadow" + i)) { continue; }
                drawn++;
                tris += n * shadowGpu[i].indexCount / 3;
            }
            GLES30.glDepthMask(true);
            GLES30.glDisable(GLES30.GL_BLEND);
        }

        // --- people on the pavement ---------------------------------------------
        int peds = uploadPeds(e);
        if (peds > 0) {
            setupInstanced(e, viewProj, ox, oy, oz, sunDir, sun, sky, fog,
                    fogDensity, nightFactor);
            for (int i = 0; i < pedGpu.length; i++) {
                int n = pedCount[i];
                if (n <= 0) { continue; }
                if (!drawInstanced(pedGpu[i], n, "ped" + i)) { continue; }
                drawn++;
                tris += n * pedGpu[i].indexCount / 3;
            }
        }

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
            GLES30.glUniform1f(uInstSiren, sirenPhase(e));
            for (int ki = 0; ki < vehCount.length; ki++) {
                int n = vehCount[ki];
                if (n <= 0) { continue; }
                VehicleGpu g = vehGpu[ki];
                if (!drawInstanced(g, n, "traffic" + ki)) { continue; }
                drawn++;
                tris += (long) (g.indexCount / 3) * n;
            }
        }

        // --- moored boats, parked helicopters and aeroplanes -------------------
        int craftN = uploadCraft(e);
        if (craftN > 0) {
            if (instances <= 0) { setupInstanced(e, viewProj, ox, oy, oz,
                    sunDir, sun, sky, fog, fogDensity, nightFactor); }
            for (int k = 0; k < craftGpu.length; k++) {
                int n = craftCount[k];
                if (n <= 0) { continue; }
                VehicleGpu g = craftGpu[k];
                if (!drawInstanced(g, n, "craft" + k)) { continue; }
                drawn++;
                tris += (long) (g.indexCount / 3) * n;
            }
        }

        // --- trains, metros and vessels ----------------------------------------
        int carriages = uploadTransit(e);
        if (carriages > 0) {
            if (instances <= 0) { setupInstanced(e, viewProj, ox, oy, oz,
                    sunDir, sun, sky, fog, fogDensity, nightFactor); }
            for (int k = 0; k < transitGpu.length; k++) {
                int n = transitCount[k];
                if (n <= 0) { continue; }
                VehicleGpu g = transitGpu[k];
                if (!drawInstanced(g, n, "transit" + k)) { continue; }
                drawn++;
                tris += (long) (g.indexCount / 3) * n;
            }
        }

        // --- the job checkpoint ------------------------------------------------
        double[] mk = e.missions.markerAt();
        if (mk != null) {
            double mdx = mk[0] - ox, mdz = mk[2] - oz;
            if (mdx * mdx + mdz * mdz < q.farRadius * q.farRadius) {
                if (instances <= 0 && carriages <= 0) {
                    setupInstanced(e, viewProj, ox, oy, oz, sunDir, sun, sky,
                            fog, fogDensity, nightFactor);
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
        if (traceFrames > 0) { traceFrames--; }
        e.telemetry.drawCalls = drawn;
        e.telemetry.maxInstanceM = Math.sqrt(maxInstSq);
        maxInstSq = 0;
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

    /**
     * Uploads the land mask so the sea fragment shader can refuse to draw
     * over land.
     *
     * <p>Forty metres a texel rather than the mask's own twenty: the
     * coastline is already drawn by the terrain, this only has to stop
     * the water, and a quarter of the memory matters more than a sharper
     * edge nobody can see. Nearest filtering, because an interpolated
     * mask would put a half-land half-sea band along every shore.
     */
    private int buildLandMask(WorldData w) {
        int[] size = new int[2];
        byte[] texels = w.landMaskTexels(SeaMask.TEXEL_M, size);
        int tw = size[0], th = size[1];
        int[] id = new int[1];
        GLES30.glGenTextures(1, id, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0]);
        ByteBuffer buf = ByteBuffer.allocateDirect(texels.length)
                .order(ByteOrder.nativeOrder());
        buf.put(texels).position(0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, tw, th, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        // Clamp: off the end of the mask is open sea, and that is what
        // the edge texel says.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        Log.i(GLUtil.TAG, "MUMBAI_LANDMASK_OK " + tw + "x" + th);
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
        // Height up the wall, so a facade's windows start at the pavement
        // instead of at whatever multiple of the tile height the ground
        // happens to sit on. Attribute 4 belongs to instancing.
        GLES30.glVertexAttribPointer(5, 1, GLES30.GL_FLOAT, false, stride, 40);
        GLES30.glEnableVertexAttribArray(5);
        GLES30.glBindVertexArray(0);
        return g;
    }

    private void releaseTile(WorldStreamer.Tile t) {
        if (releaseHandle(t.gpu)) { t.gpu = null; }
    }

    /** Frees one tile mesh, whether it is still attached to a tile or not. */
    private boolean releaseHandle(Object handle) {
        if (!(handle instanceof TileGpu)) { return false; }
        TileGpu g = (TileGpu) handle;
        GLES30.glDeleteBuffers(2, new int[]{g.vbo, g.ibo}, 0);
        GLES30.glDeleteVertexArrays(1, new int[]{g.vao}, 0);
        return true;
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
        for (int i = 0; i < Meshes.PED_OUTFITS; i++) {
            pedGpu[i] = buildInstancedGpu(Meshes.pedVertices(i),
                    Meshes.pedIndices(i), MAX_PEDS, true);
            pedScratch[i] = GLUtil.floats(MAX_PEDS * GAIT_FLOATS);
        }
        for (int i = 0; i < Meshes.SHADOW_COUNT; i++) {
            shadowGpu[i] = buildInstancedGpu(Meshes.shadowVertices(i),
                    Meshes.shadowIndices(i), MAX_SHADOWS);
            shadowScratch[i] = GLUtil.floats(MAX_SHADOWS * 4);
        }
        markerGpu = buildInstancedGpu(Meshes.markerVertices(),
                Meshes.markerIndices());
        craftScratch = new FloatBuffer[Meshes.CRAFT_COUNT];
        for (int i = 0; i < craftGpu.length; i++) {
            craftGpu[i] = buildInstancedGpu(Meshes.craftVertices(i),
                    Meshes.craftIndices(i), MAX_CRAFT);
            craftScratch[i] = GLUtil.floats(MAX_CRAFT * 4);
        }
        for (int i = 0; i < transitGpu.length; i++) {
            transitGpu[i] = buildInstancedGpu(Meshes.transitVertices(i),
                    Meshes.transitIndices(i), MAX_CARRIAGES);
            transitScratch[i] = GLUtil.floats(MAX_CARRIAGES * 4);
        }
        markerScratch = GLUtil.floats(4);
        buildLegacyProp();
    }

    private VehicleGpu buildVehicleGpu(VehicleKind kind) {
        return buildInstancedGpu(Meshes.vehicleVertices(kind),
                Meshes.vehicleIndices(kind));
    }

    /** One VAO with a per-instance vec4(x, y, z, yaw) stream at attribute 4. */
    private VehicleGpu buildInstancedGpu(float[] v, int[] i) {
        return buildInstancedGpu(v, i, MAX_PER_KIND);
    }

    /** Floats per instance for a body that walks: x, y, z, yaw, phase, stride. */
    private static final int GAIT_FLOATS = 6;

    private VehicleGpu buildInstancedGpu(float[] v, int[] i, int maxInstances) {
        return buildInstancedGpu(v, i, maxInstances, false);
    }

    private VehicleGpu buildInstancedGpu(float[] v, int[] i, int maxInstances,
                                         boolean walks) {
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
        if (walks) {
            // The limb tag, per vertex, in the slot props use for a
            // texture coordinate. Left disabled on every other mesh,
            // where it reads zero and nothing swings.
            GLES30.glVertexAttribPointer(5, 1, GLES30.GL_FLOAT, false,
                    stride, 40);
            GLES30.glEnableVertexAttribArray(5);
        }

        IntBuffer ib2 = ByteBuffer.allocateDirect(i.length * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        ib2.put(i).position(0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.ibo);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, i.length * 4, ib2,
                GLES30.GL_STATIC_DRAW);

        int istride = walks ? GAIT_FLOATS * 4 : 16;
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.instVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxInstances * istride, null,
                GLES30.GL_DYNAMIC_DRAW);
        g.maxInstances = maxInstances;
        g.instStride = istride;
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, istride, 0);
        GLES30.glEnableVertexAttribArray(4);
        GLES30.glVertexAttribDivisor(4, 1);
        if (walks) {
            GLES30.glVertexAttribPointer(6, 2, GLES30.GL_FLOAT, false, istride, 16);
            GLES30.glEnableVertexAttribArray(6);
            GLES30.glVertexAttribDivisor(6, 1);
        }
        g.instStride = istride;
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
    /**
     * Binds the instanced program and its per-frame uniforms.
     *
     * <p>Shared by traffic, transit and the job marker. It used to be
     * inlined three times with the later copies guarded on whether the
     * earlier one had run, which is how a marker in an empty street ended
     * up drawn with whatever uniforms were last left bound.
     */
    private void setupInstanced(Engine e, float[] viewProj,
                                float ox, float oy, float oz,
                                float[] sunDir, float[] sun, float[] sky,
                                float[] fog, float fogDensity, float night) {
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
        GLES30.glUniform1f(uInstNight, night);
        GLES30.glUniform1f(uInstSiren, sirenPhase(e));
    }

    /**
     * Where the light bars are in their cycle, or negative when nobody is
     * responding. Two and a half flashes a second, which is about what a
     * real bar does, and derived from the simulation clock rather than the
     * frame counter so it does not speed up on a fast device.
     */
    private static float sirenPhase(Engine e) {
        if (e.wanted.stars() <= 0) { return -1f; }
        return (float) ((e.clock.simulatedSeconds() * 2.5) % 1.0);
    }

    /**
     * Packs every pedestrian in sight into its outfit's instance buffer.
     *
     * <p>Frustum-culled and range-limited: a person is 1.7 m of geometry
     * and there is no point paying for one behind the camera.
     */
    /**
     * {@code ox}/{@code oz} are the FLOATING-ORIGIN offset, never the
     * camera. Taking them as parameters is how this went wrong: the
     * caller had an {@code ox} in scope - the camera in render space,
     * which the view matrix and uCamera want - and passed that. The
     * origin snaps to a 512 m grid near the player, so the camera in
     * render space is a few hundred metres at most while the world
     * coordinate being converted is tens of kilometres. Everything
     * these four uploaded was drawn most of a city away and was never
     * once visible: no pedestrians, no blob shadows, no moored boats or
     * parked aircraft, and no trains, Metro or monorail.
     */
    private int uploadPeds(Engine e) {
        final double ox = e.origin.offsetX();
        final double oy = 0;
        final double oz = e.origin.offsetZ();
        for (int i = 0; i < pedCount.length; i++) {
            pedCount[i] = 0;
            pedScratch[i].position(0);
        }
        int total = 0;
        for (Agents.Ped p : e.agents.peds()) {
            if (!p.active) { continue; }
            float x = (float) (p.x - ox);
            float z = (float) (p.z - oz);
            if (x * x + z * z > PED_RANGE_M * PED_RANGE_M) { continue; }
            if (!frustum.sphereVisible(x, (float) (p.y - oy) + 1.0f, z, 1.6f)) {
                continue;
            }
            int slot = Math.max(0, Math.min(Meshes.PED_OUTFITS - 1, p.outfit));
            if (pedCount[slot] >= MAX_PEDS) { continue; }
            FloatBuffer b = pedScratch[slot];
            b.position(pedCount[slot] * GAIT_FLOATS);
            noteInstance(x, p.y - oy, z);
            b.put(x);
            b.put((float) (p.y - oy));
            b.put(z);
            b.put((float) p.heading);
            // Stride grows with speed and stops when they do, so somebody
            // standing at a corner stands still instead of marching on
            // the spot.
            b.put((float) p.phase);
            // The second gait component is the stride amplitude when it
            // is positive. Negative means the body has gone over, by
            // that many radians - there was no room in the instance for
            // another channel and a walking stride is never negative,
            // so the sign carries it.
            b.put(p.topple > 1e-3 ? (float) -p.topple
                    : (float) Math.min(1.0, p.speed / 1.6));
            pedCount[slot]++;
            total++;
        }
        for (int i = 0; i < pedGpu.length; i++) {
            int n = pedCount[i];
            if (n <= 0) { continue; }
            pedScratch[i].position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pedGpu[i].instVbo);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0,
                    n * pedGpu[i].instStride, pedScratch[i]);
        }
        return total;
    }

    /** How far out a pedestrian is worth drawing, metres. */
    private static final float PED_RANGE_M = 150f;

    /** Which blob size a vehicle kind gets. */
    private static int shadowSlot(VehicleKind k) {
        if (k == null) { return 1; }
        if (k.isTwoWheeler()) { return 0; }
        return k.lengthM > 7.0 ? 2 : 1;
    }

    /**
     * One dark disc per body, on the ground beneath it.
     *
     * <p>Placed on the terrain rather than at the body's own height, so a
     * car on a flyover throws its shadow onto the road below, which is
     * both cheaper and more nearly right than projecting onto the deck.
     */
    private int uploadShadows(Engine e) {
        final double ox = e.origin.offsetX();
        final double oy = 0;
        final double oz = e.origin.offsetZ();
        for (int i = 0; i < shadowCount.length; i++) {
            shadowCount[i] = 0;
            shadowScratch[i].position(0);
        }
        int total = 0;
        double px = e.renderPosition().x;
        double pz = e.renderPosition().z;
        for (Agents.Car c : e.agents.cars()) {
            if (!c.active) { continue; }
            if (Math.hypot(c.x - px, c.z - pz) > 170) { continue; }
            int slot = shadowSlot(VehicleKind.values()[
                    Math.max(0, Math.min(VehicleKind.values().length - 1,
                            c.kindOrdinal))]);
            total += putShadow(slot, c.x, c.z, ox, oy, oz, e);
        }
        for (Agents.Ped p : e.agents.peds()) {
            if (!p.active) { continue; }
            if (Math.hypot(p.x - px, p.z - pz) > 110) { continue; }
            total += putShadow(0, p.x, p.z, ox, oy, oz, e);
        }
        // And the player, whatever they are in.
        int own = e.profile == ControlProfile.ON_FOOT ? 0
                : shadowSlot(e.drivingKind());
        total += putShadow(own, e.renderPosition().x, e.renderPosition().z,
                ox, oy, oz, e);

        for (int i = 0; i < shadowGpu.length; i++) {
            int n = shadowCount[i];
            if (n <= 0) { continue; }
            shadowScratch[i].position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, shadowGpu[i].instVbo);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 16,
                    shadowScratch[i]);
        }
        return total;
    }

    private int putShadow(int slot, double wx, double wz,
                          double ox, double oy, double oz, Engine e) {
        if (shadowCount[slot] >= MAX_SHADOWS) { return 0; }
        double g = e.world.inBounds(wx, wz) ? e.world.height(wx, wz) : 0;
        FloatBuffer b = shadowScratch[slot];
        b.position(shadowCount[slot] * 4);
        b.put((float) (wx - ox));
        b.put((float) (g - oy + 0.06));
        b.put((float) (wz - oz));
        b.put(0f);
        shadowCount[slot]++;
        return 1;
    }

    /** Packs every carriage and hull into its per-kind instance buffer. */
    private int uploadTransit(Engine e) {
        final double ox = e.origin.offsetX();
        final double oy = 0;
        final double oz = e.origin.offsetZ();
        for (int i = 0; i < transitCount.length; i++) { transitCount[i] = 0; }
        int total = 0;
        for (Transit.Service sv : e.transit.services()) {
            if (!sv.active) { continue; }
            int slot = meshSlot(sv.cls);
            FloatBuffer buf = transitScratch[slot];
            int at = transitCount[slot];
            if (at + sv.carriages > MAX_CARRIAGES) { continue; }
            float[] tmp = new float[sv.carriages * 4];
            int wrote = e.transit.carriagePoses(sv, tmp, 0, ox, oy, oz);
            buf.position(at * 4);
            buf.put(tmp, 0, wrote * 4);
            for (int c = 0; c < wrote; c++) {
                noteInstance(tmp[c * 4], tmp[c * 4 + 1], tmp[c * 4 + 2]);
            }
            transitCount[slot] = at + wrote;
            total += wrote;
        }
        for (int k = 0; k < transitGpu.length; k++) {
            int n = transitCount[k];
            if (n <= 0) { continue; }
            FloatBuffer buf = transitScratch[k];
            buf.position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, transitGpu[k].instVbo);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 16, buf);
        }
        return total;
    }

    /** Which transit body a route class is drawn with. */
    private static int meshSlot(int cls) {
        switch (cls) {
            case WorldData.CLS_LIGHT_RAIL:
            case WorldData.CLS_SUBWAY: return Meshes.TR_METRO;
            case WorldData.CLS_MONORAIL: return Meshes.TR_MONO;
            case WorldData.CLS_FERRY: return Meshes.TR_SHIP;
            default: return Meshes.TR_RAIL;
        }
    }


    /** How many frames still log a breadcrumb before each instanced group. */
    private int traceFrames = 3;

    /**
     * One instanced draw, clamped to what the buffer actually holds.
     *
     * <p>Every instanced draw in the renderer goes through here. Asking
     * for more instances than were allocated reads past the end of a
     * vertex buffer: a real driver returns rubbish, SwiftShader
     * segfaults, and since it runs inside the emulator process the
     * device disappears with no crash log, no Java exception and no
     * logcat - the failure this was written to find.
     *
     * <p>For the first few frames it also says what it is about to
     * draw, so that if something does take the process down the last
     * line in the log names the group that did it.
     */
    private boolean drawInstanced(VehicleGpu g, int n, String what) {
        if (g == null || n <= 0 || g.indexCount <= 0) { return false; }
        if (n > g.maxInstances) {
            Log.w(GLUtil.TAG, "clamped " + what + " draw from " + n + " to "
                    + g.maxInstances + " instances");
            n = g.maxInstances;
        }
        if (traceFrames > 0) {
            Log.i(GLUtil.TAG, "MUMBAI_DRAWSTEP " + what + " n=" + n
                    + " idx=" + g.indexCount + " cap=" + g.maxInstances);
        }
        GLES30.glBindVertexArray(g.vao);
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, g.indexCount,
                GLES30.GL_UNSIGNED_INT, 0, n);
        return true;
    }

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

    /**
     * Moored craft near the camera, plus the one the player is aboard.
     *
     * <p>Only the ones within a couple of kilometres are considered: there
     * are a hundred-odd on the island and walking the whole list every
     * frame to reject ninety of them is work the frustum would do anyway.
     */
    private int uploadCraft(Engine e) {
        final double ox = e.origin.offsetX();
        final double oy = 0;
        final double oz = e.origin.offsetZ();
        for (int i = 0; i < craftCount.length; i++) {
            craftCount[i] = 0;
            craftScratch[i].position(0);
        }
        CraftSpawns cs = e.world.craft();
        e.cameraPosition(craftCam);
        double range = 2200.0;
        int total = 0;
        for (int i = 0; i < cs.size(); i++) {
            CraftSpawns.Spawn sp = cs.get(i);
            if (sp.taken) { continue; }
            double dx = sp.x - craftCam.x, dz = sp.z - craftCam.z;
            if (dx * dx + dz * dz > range * range) { continue; }
            int slot = craftSlot(sp.kind);
            if (craftCount[slot] >= MAX_CRAFT) { continue; }
            float x = (float) (sp.x - ox);
            float z = (float) (sp.z - oz);
            float y = (float) sp.y;
            if (!frustum.sphereVisible(x, y + 2.0f, z, 8.0f)) { continue; }
            noteInstance(x, y, z);
            craftScratch[slot].put(x).put(y).put(z).put((float) sp.heading);
            craftCount[slot]++;
            total++;
        }
        // The one being flown, from the physics body so it interpolates.
        int mine = craftSlotFor(e.profile);
        if (mine >= 0 && craftCount[mine] < MAX_CRAFT) {
            Vec3 rp = e.renderPosition();
            float x = (float) (rp.x - ox);
            float z = (float) (rp.z - oz);
            float y = (float) (rp.y - CRAFT_MESH_DROP[mine]);
            noteInstance(x, y, z);
            craftScratch[mine].put(x).put(y).put(z)
                    .put((float) e.renderOrientation().heading());
            craftCount[mine]++;
            total++;
        }
        if (total == 0) { return 0; }
        for (int i = 0; i < craftCount.length; i++) {
            int n = craftCount[i];
            if (n <= 0) { continue; }
            craftScratch[i].position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, craftGpu[i].instVbo);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 16, craftScratch[i]);
        }
        return total;
    }

    /**
     * Distance from each body origin down to where the mesh's own origin
     * sits: the waterline on the boat, the bottom of the skids and the
     * wheels on the aircraft.
     */
    private static final float[] CRAFT_MESH_DROP = {0.45f, 1.35f, 1.15f};

    private static int craftSlot(int kind) {
        switch (kind) {
            case CraftSpawns.HELI: return Meshes.CRAFT_HELI;
            case CraftSpawns.PLANE: return Meshes.CRAFT_PLANE;
            default: return Meshes.CRAFT_BOAT;
        }
    }

    private static int craftSlotFor(ControlProfile p) {
        switch (p) {
            case BOAT: return Meshes.CRAFT_BOAT;
            case HELICOPTER: return Meshes.CRAFT_HELI;
            case AEROPLANE: return Meshes.CRAFT_PLANE;
            default: return -1;
        }
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
