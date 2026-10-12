package android.opengl;
public class GLSurfaceView extends android.view.View {
 public interface Renderer {
  void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 g, javax.microedition.khronos.egl.EGLConfig c);
  void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 g,int w,int h);
  void onDrawFrame(javax.microedition.khronos.opengles.GL10 g); }
 public static final int RENDERMODE_CONTINUOUSLY=1,RENDERMODE_WHEN_DIRTY=0;
 public GLSurfaceView(android.content.Context c){super(c);}
 public void setEGLContextClientVersion(int v){} public void setRenderer(Renderer r){}
 public void setRenderMode(int m){} public void onResume(){} public void onPause(){}
 public void setPreserveEGLContextOnPause(boolean b){} public void queueEvent(Runnable r){}
 public void setEGLConfigChooser(int r,int g,int b,int a,int d,int s){} }
