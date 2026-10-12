package android.view;
public class View {
    public static final int LAYER_TYPE_HARDWARE = 2;
    public static final int LAYER_TYPE_SOFTWARE = 1;
    public static final int LAYER_TYPE_NONE = 0;
    public static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;
    public static final int SYSTEM_UI_FLAG_FULLSCREEN = 4;
    public static final int SYSTEM_UI_FLAG_HIDE_NAVIGATION = 2;
    public static final int SYSTEM_UI_FLAG_IMMERSIVE_STICKY = 4096;
    public static final int SYSTEM_UI_FLAG_LAYOUT_STABLE = 256;
    public static final int SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN = 1024;
    public static final int SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION = 512;
    public View(android.content.Context c) {}
    public void setWillNotDraw(boolean b) {}
    public void setLayerType(int t, android.graphics.Paint p) {}
    public void invalidate() {}
    public void postInvalidateOnAnimation() {}
    public void postInvalidate() {}
    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    public void setKeepScreenOn(boolean b) {}
    public void setSystemUiVisibility(int v) {}
    public void setFocusable(boolean b) {}
    public void setFocusableInTouchMode(boolean b) {}
    public boolean requestFocus() { return true; }
    public android.content.Context getContext() { return null; }
    protected void onDraw(android.graphics.Canvas canvas) {}
    public boolean onTouchEvent(MotionEvent e) { return false; }
    public boolean onKeyDown(int k, KeyEvent e) { return false; }
    public boolean onKeyUp(int k, KeyEvent e) { return false; }
    public boolean onGenericMotionEvent(MotionEvent e) { return false; }
    protected void onSizeChanged(int w, int h, int ow, int oh) {}
    public void setVisibility(int v) {}
    public float getX() { return 0; }
    public float getY() { return 0; }
}
