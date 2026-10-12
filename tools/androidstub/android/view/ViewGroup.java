package android.view;
public class ViewGroup extends View { public ViewGroup(android.content.Context c){super(c);}
 public static class LayoutParams { public static final int MATCH_PARENT=-1,WRAP_CONTENT=-2;
   public LayoutParams(int w,int h){} }
 public void addView(View v){} public void addView(View v,LayoutParams p){} }
