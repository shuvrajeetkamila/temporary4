package ai.shuvrajeetkamila.mumbai.android;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Single activity host for Mumbai: Island City.
 *
 * <p>Layout is a {@link FrameLayout} with the GLES3 world surface at the bottom and a
 * Canvas-drawn HUD view on top. The HUD is intentionally not drawn through OpenGL: it keeps
 * text crisp at any density and keeps the GL pipeline focused on the world.
 */
public class MainActivity extends Activity {

    private GameSurface surface;
    private HudView hud;
    private ThermalWatcher thermalWatcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        hud = new HudView(this);
        surface = new GameSurface(this, hud);

        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(hud, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        thermalWatcher = new ThermalWatcher(this);
        thermalWatcher.start(surface);
        goFullscreen();
    }

    private void goFullscreen() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            goFullscreen();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surface != null) {
            surface.onResume();
        }
        if (thermalWatcher != null) {
            thermalWatcher.start(surface);
        }
    }

    @Override
    protected void onPause() {
        // Stop rendering, physics and streaming completely while backgrounded.
        if (surface != null) {
            surface.onPause();
        }
        if (thermalWatcher != null) {
            thermalWatcher.stop();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (surface != null) {
            surface.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (surface != null && surface.handleBack()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (surface != null && level >= TRIM_MEMORY_RUNNING_LOW) {
            surface.onLowMemory(level);
        }
    }

    static boolean isApi(int level) {
        return Build.VERSION.SDK_INT >= level;
    }
}
