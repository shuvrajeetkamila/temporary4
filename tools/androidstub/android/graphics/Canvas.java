package android.graphics;
public class Canvas {
  public void drawText(String s,float x,float y,Paint p){}
  public void drawCircle(float x,float y,float r,Paint p){}
  public void drawRoundRect(RectF r,float rx,float ry,Paint p){}
  public void drawRect(float l,float t,float r,float b,Paint p){}
  public void drawRect(RectF r,Paint p){}
  public void drawLine(float x0,float y0,float x1,float y1,Paint p){}
  public void drawLines(float[] pts,int offset,int count,Paint p){}
  public void drawLines(float[] pts,Paint p){}
  public void drawPath(Path path,Paint p){}
  public void drawColor(int c){}
  public int save(){ return 0; }
  public void restoreToCount(int c){}
  public void clipRect(float l,float t,float r,float b){}
  public int getWidth(){ return 0; }
  public int getHeight(){ return 0; }
}
