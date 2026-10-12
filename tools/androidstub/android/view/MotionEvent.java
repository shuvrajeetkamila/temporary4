package android.view;
public class MotionEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2,
        ACTION_CANCEL = 3, ACTION_POINTER_DOWN = 5, ACTION_POINTER_UP = 6;
    public static final int AXIS_X = 0, AXIS_Y = 1, AXIS_Z = 11, AXIS_RZ = 14,
        AXIS_LTRIGGER = 17, AXIS_RTRIGGER = 18, AXIS_HAT_X = 15, AXIS_HAT_Y = 16,
        AXIS_BRAKE = 23, AXIS_GAS = 22;
    public int getActionMasked() { return 0; }
    public int getActionIndex() { return 0; }
    public int getPointerId(int i) { return 0; }
    public int getPointerCount() { return 0; }
    public float getX(int i) { return 0; }
    public float getY(int i) { return 0; }
    public float getX() { return 0; }
    public float getY() { return 0; }
    public float getAxisValue(int axis) { return 0; }
    public int getSource() { return 0; }
}
