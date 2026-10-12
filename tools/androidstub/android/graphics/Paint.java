package android.graphics;
public class Paint { public static final int ANTI_ALIAS_FLAG=1;
 public enum Style { STROKE, FILL } public enum Align { CENTER, LEFT, RIGHT }
 public Paint(){} public Paint(int f){}
 public void setColor(int c){} public void setTextSize(float s){} public void setFakeBoldText(boolean b){}
 public void setStyle(Style s){} public void setStrokeWidth(float w){} public void setTextAlign(Align a){}
 public float measureText(String s){return 0;} }
