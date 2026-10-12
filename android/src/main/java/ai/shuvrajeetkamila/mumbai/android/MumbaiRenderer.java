package ai.shuvrajeetkamila.mumbai.android;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import ai.shuvrajeetkamila.mumbai.game.InputState;
import ai.shuvrajeetkamila.mumbai.game.QualityProfile;
import ai.shuvrajeetkamila.mumbai.game.Telemetry;

/**
 * Top level renderer: owns the GL context resources, drives the fixed-step simulation and
 * paces frames to the active {@link QualityProfile}.
 */
public class MumbaiRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "MumbaiRenderer";

    private final Context context;
    private final InputState input;
    private final HudView hud;
    private final TouchController touch;
    private final Telemetry telemetry = new Telemetry();

    private GameHost host;
    private QualityProfile preferredProfile = QualityProfile.BALANCED;
    private volatile int thermalStatus = 0;
    private QualityProfile activeProfile = QualityProfile.BALANCED;

    private int viewW = 1;
    private int viewH = 1;

    private long lastFrameNanos;
    private long lastHudInvalidate;
    private float fpsAccum;
    private int fpsFrames;

    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] viewProj = new float[16];

    public MumbaiRenderer(Context context, InputState input, HudView hud, TouchController touch) {
        this.context = context.getApplicationContext();
        this.input = input;
        this.hud = hud;
        this.touch = touch;
        telemetry.loading = true;
        telemetry.loadStage = "Booting";
    }

    public Telemetry telemetry() {
        return telemetry;
    }

    public void setThermalStatus(int status) {
        this.thermalStatus = status;
    }

    public void requestMemoryTrim(int level) {
        if (host != null) {
            host.onMemoryTrim(level);
        }
    }

    public void shutdown() {
        if (host != null) {
            host.shutdown();
        }
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        String vendor = GLES30.glGetString(GLES30.GL_VENDOR);
        String renderer = GLES30.glGetString(GLES30.GL_RENDERER);
        String version = GLES30.glGetString(GLES30.GL_VERSION);
        Log.i(TAG, "GL vendor=" + vendor + " renderer=" + renderer + " version=" + version);

        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glCullFace(GLES30.GL_BACK);
        GLES30.glFrontFace(GLES30.GL_CCW);
        GLES30.glClearColor(0.42f, 0.58f, 0.72f, 1f);

        if (host == null) {
            host = new GameHost(context, telemetry, input, touch, hud);
        }
        host.onGlContextCreated();
        lastFrameNanos = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        viewW = Math.max(1, width);
        viewH = Math.max(1, height);
        GLES30.glViewport(0, 0, viewW, viewH);
        touch.layout(viewW, viewH);
        if (host != null) {
            host.onViewport(viewW, viewH);
        }
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        float dt = (float) ((now - lastFrameNanos) / 1_000_000_000.0);
        lastFrameNanos = now;
        if (dt > 0.25f) {
            dt = 0.25f;
        }
        if (dt < 0f) {
            dt = 0f;
        }

        activeProfile = QualityProfile.applyThermal(preferredProfile, thermalStatus);
        telemetry.profileName = activeProfile.displayName();
        telemetry.thermalEco = activeProfile.isEco() && thermalStatus >= 2;

        long simStart = System.nanoTime();
        if (host != null) {
            host.frame(dt, activeProfile, projection, view, viewProj, viewW, viewH);
        } else {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        }
        float simMs = (float) ((System.nanoTime() - simStart) / 1_000_000.0);
        telemetry.cpuFrameMs = simMs;

        // --- frame pacing: never run free at 60-120 FPS on a 1:1 open world -------------
        long frameBudget = activeProfile.frameNanos();
        long elapsed = System.nanoTime() - now;
        long sleep = frameBudget - elapsed;
        if (sleep > 1_000_000L) {
            try {
                Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        fpsAccum += dt;
        fpsFrames++;
        if (fpsAccum >= 0.5f) {
            telemetry.fps = fpsFrames / fpsAccum;
            fpsAccum = 0f;
            fpsFrames = 0;
            telemetry.heapUsedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    / (1024L * 1024L);
        }

        long ms = System.currentTimeMillis();
        if (ms - lastHudInvalidate > 66) {
            lastHudInvalidate = ms;
            hud.postInvalidateOnAnimation();
        }
    }

    /** Read a packaged asset fully into memory (world layers are small and compact). */
    static byte[] readAsset(Context ctx, String path) throws IOException {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, in.available()));
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    static void perspective(float[] out, float fovYDeg, float aspect, float near, float far) {
        Matrix.perspectiveM(out, 0, fovYDeg, aspect, near, far);
    }
}
