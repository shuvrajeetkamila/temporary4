package ai.shuvrajeetkamila.mumbai.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.game.Progress;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.app.Quality;
import ai.shuvrajeetkamila.mumbai.sim.Engine;
import ai.shuvrajeetkamila.mumbai.sim.Telemetry;
import ai.shuvrajeetkamila.mumbai.world.WorldData;

import java.io.IOException;

/**
 * The only activity.
 *
 * <p>Loads the packaged world off the main thread, hands it to the engine,
 * and then gets out of the way: the GL thread drives everything from there.
 * The UI thread's remaining job is the HUD, the touch controls and the
 * thermal listener.
 */
public final class MainActivity extends Activity
        implements SceneRenderer.GameHost {

    private static final String TAG = "Mumbai";

    private GLSurfaceView surface;
    private SceneRenderer renderer;
    private Hud hud;
    private MapOverlay mapOverlay;
    private TouchControls controls;
    private TextView loading;

    private final InputState input = new InputState();
    private volatile Engine engine;
    /** The audio pump. Created once the world (and so the mixer) exists. */
    private AudioOutput audioOut;
    private volatile String loadError;

    private PowerManager powerManager;
    private PowerManager.OnThermalStatusChangedListener thermalListener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private long frameCapNanos = 1_000_000_000L / 30;
    private long lastFrameEnd;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goFullscreen();

        FrameLayout root = new FrameLayout(this);

        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(3);
        surface.setEGLConfigChooser(8, 8, 8, 0, 24, 0);
        surface.setPreserveEGLContextOnPause(true);
        renderer = new SceneRenderer(this, input);
        surface.setRenderer(renderer);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(surface);

        controls = new TouchControls(this, input, this::onProfileCycle,
                this::onCameraToggle, this::onQualityCycle,
                this::onWeatherToggle);
        root.addView(controls);

        hud = new Hud(this);
        root.addView(hud);

        // Above the HUD, because when it is up it is the whole interface.
        // It starts GONE so it is not in the touch path at all while
        // closed, rather than relying on it declining every event.
        mapOverlay = new MapOverlay(this, input);
        root.addView(mapOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        loading = new TextView(this);
        loading.setText(getString(R.string.loading));
        loading.setTextColor(Color.WHITE);
        loading.setTextSize(20);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(Color.argb(235, 8, 14, 22));
        root.addView(loading, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        startThermalWatch();

        // Loading 15 MB of world and parsing the road graph takes a couple of
        // seconds. Doing it on the main thread is an ANR waiting to happen.
        new Thread(this::loadWorld, "world-load").start();
    }

    private void loadWorld() {
        long t0 = System.nanoTime();
        try {
            WorldData world = WorldData.load(path -> getAssets().open(path));
            Engine e = new Engine(world);
            e.setBaseQuality(pickStartingQuality());
            // Whatever was earned last time, before anything is spawned.
            // Android destroys and recreates this activity routinely - on a
            // rotation, under memory pressure - so "saved on exit" is not
            // good enough and never was: nothing was saved at all.
            Progress p = e.loadProgress(new PrefsStore(
                    getSharedPreferences("mumbai", MODE_PRIVATE)));
            // The monsoon switch is part of the save, so the button has
            // to come up showing what was actually restored.
            controls.weatherOn = p.weather;
            controls.postInvalidate();
            if (!Double.isNaN(p.x) && !Double.isNaN(p.z)
                    && world.inBounds(p.x, p.z)) {
                e.spawnAtWorld(p.x, p.z, ControlProfile.ON_FOOT);
            } else {
                // Marine Drive, facing the bay. A recognisable place to
                // start, and a good test of the streamer because the sea
                // is right there.
                e.spawnAt(18.9430, 72.8230, ControlProfile.ON_FOOT);
            }
            engine = e;
            // Sound can only start once there is a mixer to pull from.
            // It is started on the UI thread so start/stop stay ordered
            // against the lifecycle callbacks.
            ui.post(() -> {
                audioOut = new AudioOutput(e.audio);
                audioOut.start();
            });
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Log.i(TAG, "world loaded in " + ms + " ms: "
                    + world.chunkCount() + " chunks, "
                    + world.places().size() + " places, build "
                    + world.buildId());
            // This marker is what the emulator test in CI greps for. If the
            // world fails to load the marker never appears and the job fails,
            // which is the only reason it is a log line and not a toast.
            Log.i(TAG, "MUMBAI_WORLD_READY chunks=" + world.chunkCount()
                    + " places=" + world.places().size()
                    + " extentX=" + (int) (world.maxWorldX() - world.minWorldX())
                    + " extentZ=" + (int) (world.maxWorldZ() - world.minWorldZ()));
            ui.post(() -> {
                loading.setVisibility(View.GONE);
                controls.setProfile(ControlProfile.ON_FOOT);
                mapOverlay.setProfile(ControlProfile.ON_FOOT);
            });
        } catch (IOException | RuntimeException err) {
            Log.e(TAG, "world load failed", err);
            loadError = String.valueOf(err);
            ui.post(() -> loading.setText("Could not load the world.\n\n" + loadError));
        }
    }

    /** A coarse first guess, refined the moment thermal status says otherwise. */
    private Quality pickStartingQuality() {
        int cores = Runtime.getRuntime().availableProcessors();
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        if (cores >= 8 && heapMb >= 384) { return Quality.HIGH; }
        if (cores >= 6 && heapMb >= 256) { return Quality.BALANCED; }
        return Quality.LOW;
    }

    private void startThermalWatch() {
        if (powerManager == null) { return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener = status -> {
                Engine e = engine;
                Log.i(TAG, "thermal status " + status + " ("
                        + Quality.thermalLabel(status) + ")");
                if (e != null) {
                    // Read on the GL thread so quality changes do not race a
                    // frame that is already using the old radii.
                    surface.queueEvent(() -> {
                        e.setThermalStatus(status);
                        applyFrameCap(e.quality);
                    });
                }
            };
            powerManager.addThermalStatusListener(thermalListener);
            int now = powerManager.getCurrentThermalStatus();
            Engine e = engine;
            if (e != null) { e.setThermalStatus(now); }
        }
    }

    private void applyFrameCap(Quality q) {
        frameCapNanos = 1_000_000_000L / Math.max(10, q.targetFps);
    }

    private void onProfileCycle() {
        Engine e = engine;
        if (e == null) { return; }
        surface.queueEvent(() -> {
            ControlProfile next = e.profile.next();
            e.switchProfile(next);
            ui.post(() -> {
                controls.setProfile(next);
                mapOverlay.setProfile(next);
            });
        });
    }

    private void onCameraToggle() {
        Engine e = engine;
        if (e == null) { return; }
        surface.queueEvent(() -> e.firstPerson = !e.firstPerson);
    }

    private void onQualityCycle() {
        Engine e = engine;
        if (e == null) { return; }
        surface.queueEvent(() -> {
            Quality[] order = {Quality.LOW, Quality.BALANCED, Quality.HIGH,
                    Quality.ECO};
            int i = 0;
            for (int k = 0; k < order.length; k++) {
                if (order[k] == e.baseQuality) { i = k; }
            }
            e.setBaseQuality(order[(i + 1) % order.length]);
            applyFrameCap(e.quality);
        });
    }

    /**
     * Turns the monsoon on and off.
     *
     * <p>Queued onto the GL thread like the quality cycle, because the
     * weather is read by the renderer every frame and the switch is
     * thrown from the UI thread. Saved immediately rather than at the
     * next pause: a phone can kill the process without ever calling
     * onPause, and a setting that forgets itself is worse than no
     * setting.
     */
    private void onWeatherToggle() {
        Engine e = engine;
        if (e == null) { return; }
        surface.queueEvent(() -> {
            e.weather.enabled = !e.weather.enabled;
            controls.weatherOn = e.weather.enabled;
            controls.postInvalidate();
            e.saveProgress();
        });
    }

    // ------------------------------------------------------- renderer host

    @Override public Engine engine() { return engine; }

    @Override public void markGlReady() { }

    @Override
    public void beforeSimulation(double dt) {
        controls.pump();
    }

    @Override
    public void afterFrame(long frameNumber, double dt) {
        Engine e = engine;
        if (e == null) { return; }

        // The frame cap. Sleeping here rather than spinning is the difference
        // between a phone that stays cool and one that does not.
        long now = System.nanoTime();
        if (lastFrameEnd != 0) {
            long spent = now - lastFrameEnd;
            long remaining = frameCapNanos - spent;
            if (remaining > 1_000_000L) {
                try {
                    Thread.sleep(remaining / 1_000_000L,
                            (int) (remaining % 1_000_000L));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        lastFrameEnd = System.nanoTime();

        if (frameNumber % 8 == 0) {
            Telemetry t = e.telemetry;
            ai.shuvrajeetkamila.mumbai.game.Minimap m = e.minimap;
            ui.post(() -> hud.update(t, m));
            ai.shuvrajeetkamila.mumbai.game.MapView mv = e.mapView;
            ai.shuvrajeetkamila.mumbai.physics.RigidBody b = e.activeBody();
            double px = b.position.x;
            double pz = b.position.z;
            double hdg = b.orientation.heading();
            ai.shuvrajeetkamila.mumbai.world.WorldData wd = e.world;
            ai.shuvrajeetkamila.mumbai.game.Missions jobs = e.missions;
            ui.post(() -> mapOverlay.update(mv, m, wd, jobs, px, pz, hdg));
        }
        if (frameNumber == 30) {
            Log.i(TAG, "MUMBAI_RENDER_OK frames=30 quality=" + e.quality
                    + " resident=" + e.streamer.residentCount()
                    + " draws=" + e.telemetry.drawCalls);
        }
    }

    // --------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        goFullscreen();
        surface.onResume();
        if (audioOut != null) { audioOut.start(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Engine e = engine;
        if (e != null) { e.saveProgress(); }
        surface.onPause();
        // Hand the output device back while backgrounded, so the game is
        // not still droning over whatever the player switched to.
        if (audioOut != null) { audioOut.stop(); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (audioOut != null) { audioOut.stop(); }
        if (renderer != null) { renderer.shutdown(); }
        if (powerManager != null && thermalListener != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.removeThermalStatusListener(thermalListener);
        }
    }

    private void goFullscreen() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }
}
