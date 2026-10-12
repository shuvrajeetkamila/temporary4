package android.opengl;
public class GLSurfaceView extends android.view.SurfaceView {
    public static final int RENDERMODE_CONTINUOUSLY = 1;
    public static final int RENDERMODE_WHEN_DIRTY = 0;
    public interface Renderer {
        void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config);
        void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height);
        void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl);
    }
    public GLSurfaceView(android.content.Context c) { super(c); }
    public void setEGLContextClientVersion(int v) {}
    public void setEGLConfigChooser(int r,int g,int b,int a,int depth,int stencil) {}
    public void setPreserveEGLContextOnPause(boolean p) {}
    public void setRenderer(Renderer r) {}
    public void setRenderMode(int m) {}
    public void requestRender() {}
    public void queueEvent(Runnable r) {}
    public void onPause() {}
    public void onResume() {}
}
