package android.app;
public class Activity extends android.content.Context {
    public static final int TRIM_MEMORY_RUNNING_LOW = 10;
    public static final int TRIM_MEMORY_RUNNING_MODERATE = 5;
    public static final int TRIM_MEMORY_RUNNING_CRITICAL = 15;
    public static final int TRIM_MEMORY_UI_HIDDEN = 20;
    public static final int TRIM_MEMORY_BACKGROUND = 40;
    public static final int TRIM_MEMORY_MODERATE = 60;
    public static final int TRIM_MEMORY_COMPLETE = 80;
    protected void onCreate(android.os.Bundle b) {}
    protected void onStart() {}
    protected void onResume() {}
    protected void onPause() {}
    protected void onStop() {}
    protected void onDestroy() {}
    public void onTrimMemory(int level) {}
    public void onLowMemory() {}
    public void onBackPressed() {}
    public void onWindowFocusChanged(boolean hasFocus) {}
    public void setContentView(android.view.View v) {}
    public android.view.Window getWindow() { return null; }
    public void finish() {}
    public boolean isFinishing() { return false; }
}
