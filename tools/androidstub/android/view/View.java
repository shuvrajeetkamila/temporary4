package android.view;
public class View { public static final int SYSTEM_UI_FLAG_IMMERSIVE_STICKY=1,SYSTEM_UI_FLAG_LAYOUT_STABLE=2,
 SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION=4,SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN=8,SYSTEM_UI_FLAG_HIDE_NAVIGATION=16,
 SYSTEM_UI_FLAG_FULLSCREEN=32, GONE=8, VISIBLE=0;
 public View(android.content.Context c){}
 public void setWillNotDraw(boolean b){} public void invalidate(){} public void postInvalidate(){} public int getWidth(){return 0;}
 public int getHeight(){return 0;} public android.content.res.Resources getResources(){return null;}
 protected void onDraw(android.graphics.Canvas c){} protected void onSizeChanged(int w,int h,int ow,int oh){}
 public boolean onTouchEvent(MotionEvent e){return false;}
 public void setSystemUiVisibility(int v){} public void setVisibility(int v){}
 public static final int INVISIBLE=4;
 public int getVisibility(){return 0;} }
