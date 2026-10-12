package android.graphics;
public class Canvas {
    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    public void drawColor(int c) {}
    public void drawCircle(float x, float y, float r, Paint p) {}
    public void drawRect(float l, float t, float r, float b, Paint p) {}
    public void drawRect(RectF rect, Paint p) {}
    public void drawRoundRect(RectF rect, float rx, float ry, Paint p) {}
    public void drawLine(float x0, float y0, float x1, float y1, Paint p) {}
    public void drawLines(float[] pts, Paint p) {}
    public void drawLines(float[] pts, int off, int count, Paint p) {}
    public void drawText(String t, float x, float y, Paint p) {}
    public void drawPath(Path path, Paint p) {}
    public void drawArc(RectF oval, float start, float sweep, boolean center, Paint p) {}
    public int save() { return 0; }
    public void restore() {}
    public void restoreToCount(int c) {}
    public void clipPath(Path p) {}
    public boolean quickReject(float l, float t, float r, float b) { return false; }
    public void translate(float dx, float dy) {}
    public void rotate(float deg) {}
    public void rotate(float deg, float px, float py) {}
    public void scale(float sx, float sy) {}
    public void clipRect(float l, float t, float r, float b) {}
}
