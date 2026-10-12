package android.graphics;
public class Path {
    public void reset() {}
    public void moveTo(float x, float y) {}
    public void lineTo(float x, float y) {}
    public void close() {}
    public void addCircle(float x, float y, float r, Direction d) {}
    public enum Direction { CW, CCW }
}
