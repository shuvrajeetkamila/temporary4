package ai.shuvrajeetkamila.mumbai.android;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.shuvrajeetkamila.mumbai.game.ControlProfile;
import ai.shuvrajeetkamila.mumbai.game.Engine;
import ai.shuvrajeetkamila.mumbai.game.InputState;
import ai.shuvrajeetkamila.mumbai.game.QualityProfile;
import ai.shuvrajeetkamila.mumbai.game.Telemetry;
import ai.shuvrajeetkamila.mumbai.physics.RigidBody;
import ai.shuvrajeetkamila.mumbai.world.MeshBuilder;
import ai.shuvrajeetkamila.mumbai.world.ProtoMeshes;
import ai.shuvrajeetkamila.mumbai.world.WaterModel;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldStreamer;

/**
 * Owns everything that lives inside the OpenGL context and drives the simulation.
 *
 * <p>Threading contract: every method here except {@link #onTileReady} runs on the GL
 * thread. The world is decoded on {@code mumbai-stream-*} workers which only ever hand
 * back plain float arrays; the actual buffer creation happens here, on the GL thread,
 * with a hard per-frame budget so a burst of newly streamed city cannot stall a frame.
 */
public final class GameHost implements WorldStreamer.TileSink {

    private static final String TAG = "MumbaiHost";

    /** Vertex buffer objects for one streamed tile. */
    private static final class TileGpu {
        int vao;
        int vbo;
        int ibo;
        int indexCount;
        int bytes;
    }

    /** A static mesh uploaded once: vehicles, the player, the water grid. */
    private static final class StaticMesh {
        int vao;
        int vbo;
        int ibo;
        int indexCount;
        float radius;
    }

    private final Context context;
    private final Telemetry telemetry;
    private final InputState input;
    private final TouchController touch;
    private final HudView hud;

    private final Engine engine;
    private Thread loader;
    private volatile boolean loadFailed;

    // --- GL objects ---------------------------------------------------------
    private int worldProgram;
    private int waterProgram;
    private int skyProgram;
    private int instanceProgram;

    private int uWorldViewProj, uWorldOrigin, uWorldSun, uWorldFog, uWorldFogColour;
    private int uWorldCamera, uWorldAmbient, uWorldTime;
    private int uWaterViewProj, uWaterCamera, uWaterSun, uWaterFog, uWaterFogColour;
    private int uWaterLevel, uWaterOrigin, uWaterAmp, uWaterPhase, uWaterTint;
    private int uSkyHorizon, uSkyZenith, uSkySun;
    private int uInstViewProj, uInstSun, uInstFog, uInstFogColour, uInstCamera, uInstAmbient;

    private final Map<Long, TileGpu> tileGpu = new HashMap<>();
    private final List<WorldStreamer.Tile> renderTiles = new ArrayList<>();
    private final List<WorldStreamer.Tile> readyScratch = new ArrayList<>();

    private StaticMesh waterGrid;
    private StaticMesh[] vehicleMeshes;
    private StaticMesh playerCar, playerHuman, playerLaunch, playerSpeedboat, playerFerry;
    private StaticMesh playerHeli, playerTrainer, playerAirliner;
    private int instanceVbo;
    private FloatBuffer instanceBuffer;

    private int skyVao;

    // --- frame state --------------------------------------------------------
    private final float[] model = new float[16];
    private final float[] mvp = new float[16];
    private final float[] quat = new float[4];
    private final double[] renderPos = new double[3];
    private final float[] waveAmp = new float[16];
    private final float[] wavePhase = new float[4];
    private final int[] countOut = new int[1];

    private int viewW = 1;
    private int viewH = 1;
    private int gpuBytes;
    private int drawCalls;
    private int triangles;
    private long frameIndex;

    public GameHost(Context context, Telemetry telemetry, InputState input,
                    TouchController touch, HudView hud) {
        this.context = context.getApplicationContext();
        this.telemetry = telemetry;
        this.input = input;
        this.touch = touch;
        this.hud = hud;
        this.engine = new Engine(telemetry, input);
    }

    public Engine engine() {
        return engine;
    }

    /**
     * Vector minimap feed. The master brief allows a vector minimap but forbids using
     * imagery as the world, so this draws from the same packaged road and coastline
     * geometry the 3D world is built from: no tiles, no imagery, no network.
     */
    public HudView.MinimapSource minimapSource() {
        return new HudView.MinimapSource() {
            @Override
            public int minimapSegments(float[] out, float radiusMetres) {
                if (!engine.ready) {
                    return 0;
                }
                double px = engine.focusX();
                double pz = engine.focusZ();
                float r2 = radiusMetres * radiusMetres;
                int n = 0;
                List<WorldData.Polyline> roads = engine.world.roads;
                for (int i = 0; i < roads.size() && n + 4 <= out.length; i++) {
                    WorldData.Polyline road = roads.get(i);
                    for (int k = 0; k + 3 < road.pts.length && n + 4 <= out.length; k += 2) {
                        float ax = (float) (road.pts[k] - px);
                        float az = (float) (road.pts[k + 1] - pz);
                        float bx = (float) (road.pts[k + 2] - px);
                        float bz = (float) (road.pts[k + 3] - pz);
                        if ((ax * ax + az * az) > r2 && (bx * bx + bz * bz) > r2) {
                            continue;
                        }
                        out[n++] = ax;
                        out[n++] = az;
                        out[n++] = bx;
                        out[n++] = bz;
                    }
                }
                return n;
            }

            @Override
            public int minimapWater(float[] out, float radiusMetres) {
                if (!engine.ready) {
                    return 0;
                }
                // A coarse land/water sample grid: cheap, and always agrees with the
                // land use the simulation itself uses.
                double px = engine.focusX();
                double pz = engine.focusZ();
                int cells = 24;
                float step = radiusMetres * 2f / cells;
                int n = 0;
                for (int j = 0; j < cells && n + 4 <= out.length; j++) {
                    for (int i = 0; i < cells && n + 4 <= out.length; i++) {
                        double wx = px - radiusMetres + (i + 0.5) * step;
                        double wz = pz - radiusMetres + (j + 0.5) * step;
                        if (!ai.shuvrajeetkamila.mumbai.world.LandUse.isWater(
                                engine.world.landUseAt(wx, wz))) {
                            continue;
                        }
                        // Corner coordinates: the HUD draws these as a rectangle.
                        out[n++] = (float) (wx - px) - step * 0.5f;
                        out[n++] = (float) (wz - pz) - step * 0.5f;
                        out[n++] = (float) (wx - px) + step * 0.5f;
                        out[n++] = (float) (wz - pz) + step * 0.5f;
                    }
                }
                return n;
            }
        };
    }

    // ------------------------------------------------------------------ setup

    public void onGlContextCreated() {
        releaseGlObjects();
        buildPrograms();
        buildStaticMeshes();
        // A fresh context means every streamed buffer is gone; ask for them again.
        tileGpu.clear();
        gpuBytes = 0;
        if (engine.ready) {
            engine.streamer.clear();
        }
        if (loader == null && !engine.ready && !loadFailed) {
            startLoading();
        }
    }

    public void onViewport(int width, int height) {
        viewW = Math.max(1, width);
        viewH = Math.max(1, height);
    }

    private void startLoading() {
        telemetry.loading = true;
        telemetry.loadProgress = 0.02f;
        telemetry.loadStage = "Reading packaged world";
        loader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    WorldData.AssetSource src = new WorldData.AssetSource() {
                        @Override
                        public InputStream open(String name) throws IOException {
                            return context.getAssets().open(name);
                        }
                    };
                    telemetry.loadProgress = 0.15f;
                    int workers = Math.max(1, Math.min(3,
                            Runtime.getRuntime().availableProcessors() - 1));
                    engine.load(src, GameHost.this, workers);
                    telemetry.loadProgress = 0.95f;
                    telemetry.loadStage = "Streaming " + engine.world.spawnName;
                    telemetry.loading = false;
                    telemetry.loadProgress = 1f;
                } catch (Throwable t) {
                    loadFailed = true;
                    telemetry.fatalError = true;
                    telemetry.fatalMessage = "World load failed: " + t;
                    telemetry.loading = false;
                    Log.e(TAG, "world load failed", t);
                }
            }
        }, "mumbai-load");
        loader.setDaemon(true);
        loader.start();
    }

    // ------------------------------------------------------------------ frame

    public void frame(float dt, QualityProfile profile, float[] projection, float[] view,
                      float[] viewProj, int width, int height) {
        frameIndex++;
        viewW = Math.max(1, width);
        viewH = Math.max(1, height);

        if (!engine.ready) {
            drawLoadingScreen();
            return;
        }

        engine.applyQuality(profile);
        touch.setProfile(engine.mode);
        engine.update(dt);

        uploadReadyTiles(profile);

        // --- camera -------------------------------------------------------------
        float aspect = viewW / (float) viewH;
        float near = engine.camera.near;
        float far = Math.max(2200f, Math.min(engine.camera.far, profile.farRing * 1.35f));
        Matrix.perspectiveM(projection, 0, engine.camera.fovDeg, aspect, near, far);

        float camX = engine.origin.localX(engine.camera.px);
        float camY = engine.origin.localY(engine.camera.py);
        float camZ = engine.origin.localZ(engine.camera.pz);
        float cy = (float) Math.cos(engine.camera.yaw);
        float sy = (float) Math.sin(engine.camera.yaw);
        float cp = (float) Math.cos(engine.camera.pitch);
        float sp = (float) Math.sin(engine.camera.pitch);
        // Heading 0 looks north, which is -Z in the world frame.
        float fx = sy * cp;
        float fy = sp;
        float fz = -cy * cp;
        Matrix.setLookAtM(view, 0, camX, camY, camZ,
                camX + fx, camY + fy, camZ + fz, 0f, 1f, 0f);
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0);

        // --- environment --------------------------------------------------------
        float hours = telemetry.timeOfDayHours;
        float sunAltitude = (float) Math.sin((hours - 6.3f) / 12f * Math.PI);
        float sunX = (float) Math.cos((hours - 12f) / 12f * Math.PI) * 0.75f;
        float sl = (float) Math.sqrt(sunX * sunX + sunAltitude * sunAltitude + 0.09f);
        float sunDirX = sunX / sl;
        float sunDirY = Math.max(0.06f, sunAltitude) / sl;
        float sunDirZ = 0.3f / sl;
        float day = Math.max(0f, Math.min(1f, sunAltitude * 1.6f + 0.25f));
        float haze = 0.55f + 0.35f * telemetry.rainIntensity;

        float skyR = 0.30f + 0.33f * day;
        float skyG = 0.42f + 0.33f * day;
        float skyB = 0.56f + 0.28f * day;
        float fogR = skyR * (0.92f - 0.1f * telemetry.rainIntensity);
        float fogG = skyG * (0.93f - 0.1f * telemetry.rainIntensity);
        float fogB = skyB * 0.98f;
        // Monsoon air in Mumbai is genuinely thick; visibility shrinks with rain.
        float fogDensity = (1.0f / Math.max(900f, far * 0.72f)) * (1f + 2.2f * telemetry.rainIntensity);
        float ambient = 0.30f + 0.26f * day;

        GLES30.glClearColor(fogR, fogG, fogB, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        drawCalls = 0;
        triangles = 0;

        drawSky(skyR, skyG, skyB, sunDirX, sunDirY, sunDirZ, day);

        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthMask(true);
        GLES30.glEnable(GLES30.GL_CULL_FACE);

        drawWorld(viewProj, camX, camY, camZ, sunDirX, sunDirY, sunDirZ,
                fogDensity, fogR, fogG, fogB, ambient, haze);
        drawSea(viewProj, camX, camY, camZ, sunDirX, sunDirY, sunDirZ,
                fogDensity, fogR, fogG, fogB, day);
        drawAgents(viewProj, camX, camY, camZ, sunDirX, sunDirY, sunDirZ,
                fogDensity, fogR, fogG, fogB, ambient);
        drawPlayer(viewProj, camX, camY, camZ, sunDirX, sunDirY, sunDirZ,
                fogDensity, fogR, fogG, fogB, ambient);

        telemetry.drawCalls = drawCalls;
        telemetry.triangles = triangles;
        telemetry.gpuBytesMb = gpuBytes / (1024 * 1024);
    }

    private void drawLoadingScreen() {
        float p = telemetry.loadProgress;
        GLES30.glClearColor(0.07f + 0.05f * p, 0.09f + 0.07f * p, 0.13f + 0.09f * p, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
    }

    // ------------------------------------------------------------------ uploads

    /**
     * Moves freshly decoded tiles onto the GPU under a per-frame budget. Thermal and
     * eco profiles upload fewer tiles per frame, which is one of the ways eco mode
     * does less work rather than merely drawing less often.
     */
    /** Marker for a resident tile that has no geometry to draw. */
    private static final Object EMPTY_TILE = new Object();

    private void uploadReadyTiles(QualityProfile profile) {
        int budget = profile.isEco() ? 1 : (profile.targetFps >= 30 ? 3 : 2);
        readyScratch.clear();
        readyScratch.addAll(engine.streamer.pollReady(budget));
        for (int i = 0; i < readyScratch.size(); i++) {
            WorldStreamer.Tile t = readyScratch.get(i);
            if (t.geometry == null || t.geometry.triangles == 0) {
                // Legitimately empty - open water, usually. Still mark it resident so
                // the ring logic does not queue it again every frame.
                engine.streamer.markUploaded(t, EMPTY_TILE);
                continue;
            }
            TileGpu gpu = uploadTile(t);
            if (gpu == null) {
                engine.streamer.markUploadFailed(t);
                continue;
            }
            tileGpu.put(t.key, gpu);
            engine.streamer.markUploaded(t, gpu);
        }
        telemetry.chunksActive = engine.streamer.activeCount();
        telemetry.chunksPending = engine.streamer.pendingCount();
        telemetry.chunksCpuReady = engine.streamer.readyCount();
    }

    private TileGpu uploadTile(WorldStreamer.Tile t) {
        float[] verts = t.geometry.verts;
        int[] indices = t.geometry.indices;
        if (verts.length == 0 || indices.length == 0) {
            return null;
        }
        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        TileGpu gpu = new TileGpu();
        gpu.vao = ids[0];
        GLES30.glBindVertexArray(gpu.vao);

        FloatBuffer vb = ByteBuffer.allocateDirect(verts.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vb.put(verts).position(0);
        gpu.vbo = GlUtil.createBuffer(GLES30.GL_ARRAY_BUFFER, vb, verts.length * 4,
                GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gpu.vbo);
        bindWorldAttributes();

        IntBuffer ib = ByteBuffer.allocateDirect(indices.length * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(indices).position(0);
        gpu.ibo = GlUtil.createBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib, indices.length * 4,
                GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, gpu.ibo);

        GLES30.glBindVertexArray(0);
        gpu.indexCount = indices.length;
        gpu.bytes = verts.length * 4 + indices.length * 4;
        gpuBytes += gpu.bytes;
        return gpu;
    }

    private void bindWorldAttributes() {
        int stride = MeshBuilder.STRIDE * 4;
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24);
        GLES30.glEnableVertexAttribArray(3);
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 36);
    }

    @Override
    public void onTileReady(WorldStreamer.Tile tile) {
        // Worker thread. Nothing to do here: the GL thread drains pollReady() with a
        // budget. Touching GL from this callback would crash on most drivers.
    }

    @Override
    public void onTileEvicted(WorldStreamer.Tile tile) {
        TileGpu gpu = tileGpu.remove(tile.key);
        if (gpu == null) {
            return;
        }
        gpuBytes -= gpu.bytes;
        GlUtil.deleteBuffer(gpu.vbo);
        GlUtil.deleteBuffer(gpu.ibo);
        GlUtil.deleteVertexArray(gpu.vao);
        tile.gpu = null;
    }

    // ------------------------------------------------------------------ passes

    private void drawSky(float r, float g, float b, float sx, float sy, float sz, float day) {
        if (skyProgram == 0) {
            return;
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthMask(false);
        GLES30.glUseProgram(skyProgram);
        GLES30.glUniform3f(uSkyHorizon, r * 1.08f, g * 1.04f, b);
        GLES30.glUniform3f(uSkyZenith, r * 0.42f, g * 0.60f, b * 1.05f);
        GLES30.glUniform3f(uSkySun, sx, sy, sz);
        GLES30.glBindVertexArray(skyVao);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glBindVertexArray(0);
        drawCalls++;
        GLES30.glDepthMask(true);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
    }

    private void drawWorld(float[] viewProj, float camX, float camY, float camZ,
                           float sx, float sy, float sz, float fogDensity,
                           float fr, float fg, float fb, float ambient, float haze) {
        if (worldProgram == 0) {
            return;
        }
        GLES30.glUseProgram(worldProgram);
        GLES30.glUniformMatrix4fv(uWorldViewProj, 1, false, viewProj, 0);
        GLES30.glUniform3f(uWorldSun, sx, sy, sz);
        GLES30.glUniform1f(uWorldFog, fogDensity);
        GLES30.glUniform3f(uWorldFogColour, fr, fg, fb);
        GLES30.glUniform3f(uWorldCamera, camX, camY, camZ);
        GLES30.glUniform1f(uWorldAmbient, ambient);
        GLES30.glUniform1f(uWorldTime, engine.water.time());

        engine.renderTiles(renderTiles);
        for (int i = 0; i < renderTiles.size(); i++) {
            WorldStreamer.Tile t = renderTiles.get(i);
            if (!(t.gpu instanceof TileGpu)) {
                continue;
            }
            TileGpu gpu = (TileGpu) t.gpu;
            float ox = engine.origin.localX(t.centreX - t.radius / 0.75f * 0.5f);
            float oz = engine.origin.localZ(t.centreZ - t.radius / 0.75f * 0.5f);
            GLES30.glUniform3f(uWorldOrigin, ox, 0f, oz);
            GLES30.glBindVertexArray(gpu.vao);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, gpu.indexCount,
                    GLES30.GL_UNSIGNED_INT, 0);
            drawCalls++;
            triangles += gpu.indexCount / 3;
        }
        GLES30.glBindVertexArray(0);
    }

    private void drawSea(float[] viewProj, float camX, float camY, float camZ,
                         float sx, float sy, float sz, float fogDensity,
                         float fr, float fg, float fb, float day) {
        if (waterProgram == 0 || waterGrid == null) {
            return;
        }
        // The wave trains come straight from the physics model, with the phase offset
        // of the current floating origin folded in at double precision, so the surface
        // the player sees is numerically the same one the buoyancy solver samples.
        float[][] trains = WaterModel.trains(WaterModel.CLASS_SEA);
        float windScale = engine.water.windScale();
        float time = engine.water.time();
        int n = Math.min(4, trains.length);
        for (int i = 0; i < n; i++) {
            float amp = trains[i][0] * windScale;
            float k = (float) (2.0 * Math.PI / trains[i][1]);
            float dirX = (float) Math.cos(trains[i][2]);
            float dirZ = (float) Math.sin(trains[i][2]);
            waveAmp[i * 4] = amp;
            waveAmp[i * 4 + 1] = k;
            waveAmp[i * 4 + 2] = dirX;
            waveAmp[i * 4 + 3] = dirZ;
            double phase = k * (engine.origin.x() * dirX + engine.origin.z() * dirZ)
                    - trains[i][3] * k * time;
            wavePhase[i] = (float) (phase % (2.0 * Math.PI));
        }
        for (int i = n; i < 4; i++) {
            waveAmp[i * 4] = 0f;
            waveAmp[i * 4 + 1] = 1f;
            waveAmp[i * 4 + 2] = 1f;
            waveAmp[i * 4 + 3] = 0f;
            wavePhase[i] = 0f;
        }

        GLES30.glUseProgram(waterProgram);
        GLES30.glUniformMatrix4fv(uWaterViewProj, 1, false, viewProj, 0);
        GLES30.glUniform3f(uWaterCamera, camX, camY, camZ);
        GLES30.glUniform3f(uWaterSun, sx, sy, sz);
        GLES30.glUniform1f(uWaterFog, fogDensity);
        GLES30.glUniform3f(uWaterFogColour, fr, fg, fb);
        GLES30.glUniform1f(uWaterLevel, engine.origin.localY(engine.water.tidalLevel()));
        GLES30.glUniform2f(uWaterOrigin, camX, camZ);
        GLES30.glUniform4fv(uWaterAmp, 4, waveAmp, 0);
        GLES30.glUniform1fv(uWaterPhase, 4, wavePhase, 0);
        GLES30.glUniform3f(uWaterTint, 0.055f + 0.05f * day, 0.13f + 0.09f * day,
                0.16f + 0.10f * day);
        GLES30.glBindVertexArray(waterGrid.vao);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, waterGrid.indexCount,
                GLES30.GL_UNSIGNED_INT, 0);
        GLES30.glBindVertexArray(0);
        drawCalls++;
        triangles += waterGrid.indexCount / 3;
    }

    private void drawAgents(float[] viewProj, float camX, float camY, float camZ,
                            float sx, float sy, float sz, float fogDensity,
                            float fr, float fg, float fb, float ambient) {
        if (instanceProgram == 0 || engine.agents == null || vehicleMeshes == null) {
            return;
        }
        float[] data = engine.agents.vehicleInstances(engine.origin, countOut);
        int count = countOut[0];
        if (count <= 0) {
            telemetry.trafficAgents = 0;
            return;
        }
        telemetry.trafficAgents = count;

        GLES30.glUseProgram(instanceProgram);
        GLES30.glUniformMatrix4fv(uInstViewProj, 1, false, viewProj, 0);
        GLES30.glUniform3f(uInstSun, sx, sy, sz);
        GLES30.glUniform1f(uInstFog, fogDensity);
        GLES30.glUniform3f(uInstFogColour, fr, fg, fb);
        GLES30.glUniform3f(uInstCamera, camX, camY, camZ);
        GLES30.glUniform1f(uInstAmbient, ambient);

        // Bucket by kind so each mesh is drawn in a single instanced call.
        for (int kind = 0; kind < vehicleMeshes.length; kind++) {
            StaticMesh mesh = vehicleMeshes[kind];
            if (mesh == null) {
                continue;
            }
            int written = 0;
            instanceBuffer.position(0);
            for (int i = 0; i < count; i++) {
                int o = i * ai.shuvrajeetkamila.mumbai.game.AgentSystem.VEHICLE_STRIDE;
                if ((int) data[o + 4] != kind) {
                    continue;
                }
                if (written >= instanceBuffer.capacity() / 4) {
                    break;
                }
                instanceBuffer.put(data[o]).put(data[o + 1]).put(data[o + 2]).put(data[o + 3]);
                written++;
            }
            if (written == 0) {
                continue;
            }
            instanceBuffer.position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, written * 16, instanceBuffer);
            GLES30.glBindVertexArray(mesh.vao);
            GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, mesh.indexCount,
                    GLES30.GL_UNSIGNED_INT, 0, written);
            drawCalls++;
            triangles += (mesh.indexCount / 3) * written;
        }
        GLES30.glBindVertexArray(0);
        telemetry.pedestrians = engine.agents.pedestrianCount();
    }

    private void drawPlayer(float[] viewProj, float camX, float camY, float camZ,
                            float sx, float sy, float sz, float fogDensity,
                            float fr, float fg, float fb, float ambient) {
        if (worldProgram == 0) {
            return;
        }
        StaticMesh mesh = meshForMode();
        if (mesh == null) {
            return;
        }
        // In cockpit view the player's own body would fill the screen.
        if (engine.camera.mode == ai.shuvrajeetkamila.mumbai.game.Camera.MODE_COCKPIT) {
            return;
        }

        engine.renderPosition(renderPos);
        engine.renderOrientation(quat);
        quaternionToMatrix(quat, model);
        model[12] = engine.origin.localX(renderPos[0]);
        model[13] = engine.origin.localY(renderPos[1]);
        model[14] = engine.origin.localZ(renderPos[2]);
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0);

        GLES30.glUseProgram(worldProgram);
        GLES30.glUniformMatrix4fv(uWorldViewProj, 1, false, mvp, 0);
        GLES30.glUniform3f(uWorldOrigin, 0f, 0f, 0f);
        GLES30.glUniform3f(uWorldSun, sx, sy, sz);
        GLES30.glUniform1f(uWorldFog, fogDensity);
        GLES30.glUniform3f(uWorldFogColour, fr, fg, fb);
        GLES30.glUniform3f(uWorldCamera, camX, camY, camZ);
        GLES30.glUniform1f(uWorldAmbient, ambient);
        GLES30.glBindVertexArray(mesh.vao);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indexCount, GLES30.GL_UNSIGNED_INT, 0);
        GLES30.glBindVertexArray(0);
        drawCalls++;
        triangles += mesh.indexCount / 3;
    }

    private StaticMesh meshForMode() {
        switch (engine.mode) {
            case CAR:
                return playerCar;
            case BOAT:
                return engine.boat != null && engine.boat.cfg.length > 20f ? playerFerry
                        : (engine.boat != null && engine.boat.cfg.planingSpeed < 9f
                        ? playerLaunch : playerSpeedboat);
            case HELICOPTER:
                return playerHeli;
            case AEROPLANE:
                return engine.plane != null && engine.plane.cfg.wingSpan > 20f
                        ? playerAirliner : playerTrainer;
            default:
                return playerHuman;
        }
    }

    // ------------------------------------------------------------------ assets

    private void buildStaticMeshes() {
        vehicleMeshes = new StaticMesh[ProtoMeshes.VEHICLE_KINDS];
        for (int k = 0; k < ProtoMeshes.VEHICLE_KINDS; k++) {
            vehicleMeshes[k] = uploadStatic(ProtoMeshes.vehicle(k), true);
        }
        playerCar = uploadStatic(ProtoMeshes.playerCar(), false);
        playerHuman = uploadStatic(ProtoMeshes.human(), false);
        playerLaunch = uploadStatic(ProtoMeshes.boat(7.8f, 2.6f, 1.6f, 0x2E5C8A, true), false);
        playerSpeedboat = uploadStatic(ProtoMeshes.boat(6.4f, 2.2f, 1.3f, 0xE4E4DC, false), false);
        playerFerry = uploadStatic(ProtoMeshes.boat(34f, 9f, 5.2f, 0x1F4E5F, true), false);
        playerHeli = uploadStatic(ProtoMeshes.helicopter(), false);
        playerTrainer = uploadStatic(ProtoMeshes.aeroplane(11f, 8.3f, false), false);
        playerAirliner = uploadStatic(ProtoMeshes.aeroplane(34.1f, 37.6f, true), false);

        instanceBuffer = ByteBuffer.allocateDirect(2048 * 16)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        int[] ids = new int[1];
        GLES30.glGenBuffers(1, ids, 0);
        instanceVbo = ids[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 2048 * 16, null, GLES30.GL_DYNAMIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);

        // Instance attributes have to be wired after the buffer exists.
        for (int k = 0; k < vehicleMeshes.length; k++) {
            attachInstanceAttributes(vehicleMeshes[k]);
        }

        waterGrid = uploadWaterGrid();

        GLES30.glGenVertexArrays(1, ids, 0);
        skyVao = ids[0];
    }

    private void attachInstanceAttributes(StaticMesh mesh) {
        if (mesh == null) {
            return;
        }
        GLES30.glBindVertexArray(mesh.vao);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo);
        GLES30.glEnableVertexAttribArray(4);
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, 16, 0);
        GLES30.glVertexAttribDivisor(4, 1);
        GLES30.glBindVertexArray(0);
    }

    private StaticMesh uploadStatic(ProtoMeshes.Mesh src, boolean instanced) {
        StaticMesh m = new StaticMesh();
        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        m.vao = ids[0];
        GLES30.glBindVertexArray(m.vao);

        FloatBuffer vb = ByteBuffer.allocateDirect(src.verts.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vb.put(src.verts).position(0);
        m.vbo = GlUtil.createBuffer(GLES30.GL_ARRAY_BUFFER, vb, src.verts.length * 4,
                GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, m.vbo);
        bindWorldAttributes();

        IntBuffer ib = ByteBuffer.allocateDirect(src.indices.length * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(src.indices).position(0);
        m.ibo = GlUtil.createBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib, src.indices.length * 4,
                GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.ibo);
        GLES30.glBindVertexArray(0);

        m.indexCount = src.indices.length;
        m.radius = src.radius;
        gpuBytes += src.verts.length * 4 + src.indices.length * 4;
        return m;
    }

    /**
     * A camera-following sea grid. Spacing grows with the square of the distance from
     * the camera so a few thousand vertices cover the horizon while still resolving
     * individual swells near the boat.
     */
    private StaticMesh uploadWaterGrid() {
        final int n = 128;
        final float radius = 7000f;
        float[] verts = new float[(n + 1) * (n + 1) * 2];
        int[] indices = new int[n * n * 6];
        int p = 0;
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                float u = i / (float) n * 2f - 1f;
                float v = j / (float) n * 2f - 1f;
                verts[p++] = Math.signum(u) * u * u * radius;
                verts[p++] = Math.signum(v) * v * v * radius;
            }
        }
        int k = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int a = j * (n + 1) + i;
                int b = a + 1;
                int c = a + n + 2;
                int d = a + n + 1;
                indices[k++] = a;
                indices[k++] = d;
                indices[k++] = c;
                indices[k++] = a;
                indices[k++] = c;
                indices[k++] = b;
            }
        }
        StaticMesh m = new StaticMesh();
        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        m.vao = ids[0];
        GLES30.glBindVertexArray(m.vao);
        FloatBuffer vb = ByteBuffer.allocateDirect(verts.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vb.put(verts).position(0);
        m.vbo = GlUtil.createBuffer(GLES30.GL_ARRAY_BUFFER, vb, verts.length * 4,
                GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, m.vbo);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0);
        IntBuffer ib = ByteBuffer.allocateDirect(indices.length * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(indices).position(0);
        m.ibo = GlUtil.createBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib, indices.length * 4,
                GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.ibo);
        GLES30.glBindVertexArray(0);
        m.indexCount = indices.length;
        gpuBytes += verts.length * 4 + indices.length * 4;
        return m;
    }

    // ------------------------------------------------------------------ shaders

    private void buildPrograms() {
        worldProgram = GlUtil.linkProgram(Shaders.WORLD_VS, Shaders.WORLD_FS);
        waterProgram = GlUtil.linkProgram(Shaders.WATER_VS, Shaders.WATER_FS);
        skyProgram = GlUtil.linkProgram(Shaders.SKY_VS, Shaders.SKY_FS);
        instanceProgram = GlUtil.linkProgram(Shaders.INSTANCE_VS, Shaders.WORLD_FS);

        if (worldProgram == 0 || waterProgram == 0 || skyProgram == 0 || instanceProgram == 0) {
            telemetry.fatalError = true;
            telemetry.fatalMessage = "Shader build failed: " + GlUtil.lastError();
            Log.e(TAG, telemetry.fatalMessage);
            return;
        }

        uWorldViewProj = GLES30.glGetUniformLocation(worldProgram, "uViewProj");
        uWorldOrigin = GLES30.glGetUniformLocation(worldProgram, "uOrigin");
        uWorldSun = GLES30.glGetUniformLocation(worldProgram, "uSun");
        uWorldFog = GLES30.glGetUniformLocation(worldProgram, "uFogDensity");
        uWorldFogColour = GLES30.glGetUniformLocation(worldProgram, "uFogColour");
        uWorldCamera = GLES30.glGetUniformLocation(worldProgram, "uCamera");
        uWorldAmbient = GLES30.glGetUniformLocation(worldProgram, "uAmbient");
        uWorldTime = GLES30.glGetUniformLocation(worldProgram, "uTime");

        uInstViewProj = GLES30.glGetUniformLocation(instanceProgram, "uViewProj");
        uInstSun = GLES30.glGetUniformLocation(instanceProgram, "uSun");
        uInstFog = GLES30.glGetUniformLocation(instanceProgram, "uFogDensity");
        uInstFogColour = GLES30.glGetUniformLocation(instanceProgram, "uFogColour");
        uInstCamera = GLES30.glGetUniformLocation(instanceProgram, "uCamera");
        uInstAmbient = GLES30.glGetUniformLocation(instanceProgram, "uAmbient");

        uWaterViewProj = GLES30.glGetUniformLocation(waterProgram, "uViewProj");
        uWaterCamera = GLES30.glGetUniformLocation(waterProgram, "uCamera");
        uWaterSun = GLES30.glGetUniformLocation(waterProgram, "uSun");
        uWaterFog = GLES30.glGetUniformLocation(waterProgram, "uFogDensity");
        uWaterFogColour = GLES30.glGetUniformLocation(waterProgram, "uFogColour");
        uWaterLevel = GLES30.glGetUniformLocation(waterProgram, "uLevel");
        uWaterOrigin = GLES30.glGetUniformLocation(waterProgram, "uGridCentre");
        uWaterAmp = GLES30.glGetUniformLocation(waterProgram, "uWave");
        uWaterPhase = GLES30.glGetUniformLocation(waterProgram, "uPhase");
        uWaterTint = GLES30.glGetUniformLocation(waterProgram, "uTint");

        uSkyHorizon = GLES30.glGetUniformLocation(skyProgram, "uHorizon");
        uSkyZenith = GLES30.glGetUniformLocation(skyProgram, "uZenith");
        uSkySun = GLES30.glGetUniformLocation(skyProgram, "uSun");
    }

    // ------------------------------------------------------------------ lifecycle

    public void onMemoryTrim(int level) {
        if (level >= 60 && engine.ready) {
            // Drop the far rings; they rebuild in a few seconds.
            engine.streamer.clear();
            for (Map.Entry<Long, TileGpu> e : tileGpu.entrySet()) {
                TileGpu gpu = e.getValue();
                GlUtil.deleteBuffer(gpu.vbo);
                GlUtil.deleteBuffer(gpu.ibo);
                GlUtil.deleteVertexArray(gpu.vao);
            }
            tileGpu.clear();
            gpuBytes = 0;
        }
    }

    public void shutdown() {
        engine.shutdown();
        releaseGlObjects();
    }

    private void releaseGlObjects() {
        for (Map.Entry<Long, TileGpu> e : tileGpu.entrySet()) {
            TileGpu gpu = e.getValue();
            GlUtil.deleteBuffer(gpu.vbo);
            GlUtil.deleteBuffer(gpu.ibo);
            GlUtil.deleteVertexArray(gpu.vao);
        }
        tileGpu.clear();
        gpuBytes = 0;
    }

    // ------------------------------------------------------------------ maths

    static void quaternionToMatrix(float[] q, float[] out) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        float wx = w * x, wy = w * y, wz = w * z;
        out[0] = 1f - 2f * (yy + zz);
        out[1] = 2f * (xy + wz);
        out[2] = 2f * (xz - wy);
        out[3] = 0f;
        out[4] = 2f * (xy - wz);
        out[5] = 1f - 2f * (xx + zz);
        out[6] = 2f * (yz + wx);
        out[7] = 0f;
        out[8] = 2f * (xz + wy);
        out[9] = 2f * (yz - wx);
        out[10] = 1f - 2f * (xx + yy);
        out[11] = 0f;
        out[12] = 0f;
        out[13] = 0f;
        out[14] = 0f;
        out[15] = 1f;
    }
}
