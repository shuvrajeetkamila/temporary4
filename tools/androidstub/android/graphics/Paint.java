package android.graphics;
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    public enum Align { LEFT, CENTER, RIGHT }
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Join { MITER, ROUND, BEVEL }
    public Paint() {}
    public Paint(int flags) {}
    public void setColor(int c) {}
    public void setAlpha(int a) {}
    public void setStyle(Style s) {}
    public void setStrokeWidth(float w) {}
    public void setStrokeCap(Cap c) {}
    public void setStrokeJoin(Join j) {}
    public void setTextSize(float s) {}
    public void setTextAlign(Align a) {}
    public void setTypeface(Typeface t) {}
    public void setShader(Shader s) {}
    public float measureText(String t) { return 0; }
    public float getTextSize() { return 0; }
    public void setAntiAlias(boolean b) {}
    public void setFakeBoldText(boolean b) {}
}
