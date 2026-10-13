package android.graphics;
public class RectF {
  public float left, top, right, bottom;
  public RectF() {}
  public RectF(float a,float b,float c,float d){ set(a,b,c,d); }
  public void set(float a,float b,float c,float d){ left=a; top=b; right=c; bottom=d; }
  public float centerX(){ return (left+right)*0.5f; }
  public float centerY(){ return (top+bottom)*0.5f; }
  public float width(){ return right-left; }
  public float height(){ return bottom-top; }
  public boolean contains(float x,float y){ return x>=left&&x<=right&&y>=top&&y<=bottom; }
}
