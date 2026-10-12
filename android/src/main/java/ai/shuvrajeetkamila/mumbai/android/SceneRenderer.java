package ai.shuvrajeetkamila.mumbai.android;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.app.Quality;
import ai.shuvrajeetkamila.mumbai.math.Vec3;
import ai.shuvrajeetkamila.mumbai.sim.Agents;
import ai.shuvrajeetkamila.mumbai.sim.Engine;
import ai.shuvrajeetkamila.mumbai.world.ChunkMesher;
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

    private int uSceneViewProj, uSceneOrigin, uSceneCam, uSceneSunDir;
    private int uSceneSunCol, uSceneSky, uSceneFogCol, uSceneFogDen, uSceneWet;
    private int uInstViewProj, uInstCam;
    private int uSeaViewProj, uSeaCam, uSeaTime, uSeaExtent, uSeaTide;
    private int uSeaChop, uSeaSunDir, uSeaSunCol, uSeaSky, uSeaFogCol, uSeaFogDen;
    private int uSkySky, uSkyFog, uSkyHorizon;

    private int seaVao, seaVbo, seaIbo, seaIndexCount, seaGridBuilt;
    private int skyVao, skyVbo;
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

        uSceneViewProj = GLES30.glGetUniformLocation(sceneProg, "uViewProj");
        uSceneOrigin = GLES30.glGetUniformLocation(sceneProg, "uChunkOrigin");
        uSceneCam = GLES30.glGetUniformLocation(sceneProg, "uCamera");
        uSceneSunDir = GLES30.glGetUniformLocation(sceneProg, "uSunDir");
        uSceneSunCol = GLES30.glGetUniformLocation(sceneProg, "uSunColour");
        uSceneSky = GLES30.glGetUniformLocation(sceneProg, "uSkyColour");
        uSceneFogCol = GLES30.glGetUniformLocation(sceneProg, "uFogColour");
        uSceneFogDen = GLES30.glGetUniformLocation(sceneProg, "uFogDensity");
        uSceneWet = GLES30.glGetUniformLocation(sceneProg, "uWetness");

        uInstViewProj = GLES30.glGetUniformLocation(instProg, "uViewProj");
        uInstCam = GLES30.glGetUniformLocation(instProg, "uCamera");

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

        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glCullFace(GLES30.GL_BACK);
        GLES30.glFrontFace(GLES30.GL_CCW);
        GLES30.glClearColor(0.55f, 0.68f, 0.78f, 1f);

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

        double hour = 8.0 + (e.ocean.time() / 300.0) % 12.0;
        float sunHeight = (float) Math.sin(Math.PI * (hour - 6) / 12.0);
        float[] sunDir = {0.42f, Math.max(0.12f, sunHeight), -0.58f};
        float sl = (float) Math.sqrt(sunDir[0] * sunDir[0] + sunDir[1] * sunDir[1]
                + sunDir[2] * sunDir[2]);
        sunDir[0] /= sl; sunDir[1] /= sl; sunDir[2] /= sl;

        float haze = (float) (0.25 + e.wetness * 0.55);
        float[] sky = {0.50f - haze * 0.14f, 0.64f - haze * 0.10f, 0.79f - haze * 0.05f};
        float[] fog = {0.66f - haze * 0.16f, 0.71f - haze * 0.13f, 0.76f - haze * 0.08f};
        float[] sun = {1.02f, 0.96f, 0.86f};
        // Visibility: clear air in Mumbai is good for tens of kilometres, but
        // the monsoon and the haze cut it hard. Tied to the far plane so the
        // chunk pop-in always happens inside the fog.
        float fogDensity = (float) (1.6 / Math.max(900, q.farRadius * 0.85))
                * (1f + haze * 2.2f);

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

        // --- traffic and pedestrians, instanced ---------------------------------
        int instances = uploadInstances(e);
        if (instances > 0) {
            GLES30.glUseProgram(instProg);
            GLES30.glUniformMatrix4fv(uInstViewProj, 1, false, viewProj, 0);
            GLES30.glUniform3f(uInstCam, ox, oy, oz);
            GLES30.glUniform3fv(
                    GLES30.glGetUniformLocation(instProg, "uSunDir"), 1, sunDir, 0);
            GLES30.glUniform3fv(
                    GLES30.glGetUniformLocation(instProg, "uSunColour"), 1, sun, 0);
            GLES30.glUniform3fv(
                    GLES30.glGetUniformLocation(instProg, "uSkyColour"), 1, sky, 0);
            GLES30.glUniform3fv(
                    GLES30.glGetUniformLocation(instProg, "uFogColour"), 1, fog, 0);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(instProg, "uFogDensity"), fogDensity);
            GLES30.glUniform1f(
                    GLES30.glGetUniformLocation(instProg, "uWetness"), (float) e.wetness);
            GLES30.glBindVertexArray(propVao);
            GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, propIndexCount,
                    GLES30.GL_UNSIGNED_INT, 0, instances);
            drawn++;
            tris += (long) (propIndexCount / 3) * instances;
        }

        GLES30.glBindVertexArray(0);
        e.telemetry.drawCalls = drawn;
        e.telemetry.trianglesK = (int) (tris / 1000);
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

    /** A single generic car body, instanced for every ambient vehicle. */
    private void buildProps() {
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

    private int uploadInstances(Engine e) {
        Agents.Car[] cars = e.agents.cars();
        if (instanceScratch == null || instanceScratch.capacity() < 512 * 4) {
            instanceScratch = GLUtil.floats(512 * 4);
        }
        instanceScratch.position(0);
        int n = 0;
        for (Agents.Car c : cars) {
            if (!c.active || n >= 512) { continue; }
            float x = (float) (c.x - e.origin.offsetX());
            float z = (float) (c.z - e.origin.offsetZ());
            if (!frustum.sphereVisible(x, (float) c.y + 0.8f, z, 3.2f)) { continue; }
            instanceScratch.put(x).put((float) c.y).put(z).put((float) c.heading);
            n++;
        }
        if (n == 0) { return 0; }
        instanceScratch.position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, propInstVbo);
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 16, instanceScratch);
        return n;
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
