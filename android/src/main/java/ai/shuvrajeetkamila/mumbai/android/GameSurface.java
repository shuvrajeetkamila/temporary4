package ai.shuvrajeetkamila.mumbai.android;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.KeyEvent;
import android.view.MotionEvent;

import ai.shuvrajeetkamila.mumbai.game.InputState;

/**
 * GLES 3.0 surface hosting the world renderer and routing touch / gamepad input into the
 * action map. Rendering, physics and streaming all stop when the surface pauses.
 */
public class GameSurface extends GLSurfaceView {

    private final MumbaiRenderer renderer;
    private final InputState input = new InputState();
    private final TouchController touch;
    private final HudView hud;

    public GameSurface(Context context, HudView hud) {
        super(context);
        this.hud = hud;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 0, 24, 0);
        setPreserveEGLContextOnPause(true);
        touch = new TouchController(input);
        renderer = new MumbaiRenderer(context, input, hud, touch);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        hud.attach(touch, renderer.telemetry());
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        touch.onTouch(event, getWidth(), getHeight());
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (touch.onKey(keyCode, true, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (touch.onKey(keyCode, false, event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (touch.onGamepadMotion(event)) {
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    void onThermalStatus(int status) {
        renderer.setThermalStatus(status);
    }

    void onLowMemory(int level) {
        renderer.requestMemoryTrim(level);
    }

    boolean handleBack() {
        return touch.handleBack();
    }

    void shutdown() {
        renderer.shutdown();
    }
}
