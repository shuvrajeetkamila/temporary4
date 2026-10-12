package android.widget;
public class FrameLayout extends android.view.ViewGroup {
 public FrameLayout(android.content.Context c){super(c);}
 public static class LayoutParams extends android.view.ViewGroup.LayoutParams {
   public static final int MATCH_PARENT=-1,WRAP_CONTENT=-2;
   public LayoutParams(int w,int h){super(w,h);} } }
