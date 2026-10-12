package ai.shuvrajeetkamila.mumbai.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;

/**
 * On-screen controls: two sticks and a column of buttons.
 *
 * <p>The layout changes with what is being driven, because a helicopter's
 * collective is not a car's accelerator and pretending otherwise makes both
 * worse. Touch state is collected here on the UI thread and copied into the
 * shared {@link InputState} once per frame by {@link #pump()}, which the GL
 * thread calls. Copying rather than sharing means a finger lifting halfway
 * through a physics step cannot produce a half-applied input.
 */
final class TouchControls extends View {

    private final InputState out;
    private final Runnable onProfile;
    private final Runnable onCamera;
    private final Runnable onQuality;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private ControlProfile profile = ControlProfile.ON_FOOT;

    private int leftPointer = -1;
    private int rightPointer = -1;
    private float leftCx, leftCy, leftX, leftY;
    private float rightCx, rightCy, rightX, rightY;
    private float stickRadius = 110;

    // Button state, sampled by pump().
    private boolean bA, bB, bC, bD;
    private boolean edgeA, edgeB;

    private final float[] pending = new float[4];

    TouchControls(Context c, InputState out, Runnable onProfile,
                  Runnable onCamera, Runnable onQuality) {
        super(c);
        this.out = out;
        this.onProfile = onProfile;
        this.onCamera = onCamera;
        this.onQuality = onQuality;
        setWillNotDraw(false);

        float d = c.getResources().getDisplayMetrics().density;
        stickRadius = 62 * d;

        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * d);
        ring.setColor(Color.argb(118, 235, 243, 250));

        knob.setColor(Color.argb(150, 240, 246, 252));
        fill.setColor(Color.argb(72, 10, 20, 32));

        label.setColor(Color.argb(225, 240, 246, 252));
        label.setTextSize(13 * d);
        label.setFakeBoldText(true);
        label.setTextAlign(Paint.Align.CENTER);
    }

    void setProfile(ControlProfile p) {
        profile = p;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        leftCx = stickRadius * 1.5f;
        leftCy = h - stickRadius * 1.5f;
        rightCx = w - stickRadius * 1.5f;
        rightCy = h - stickRadius * 1.5f;
        leftX = leftCx;
        leftY = leftCy;
        rightX = rightCx;
        rightY = rightCy;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = ev.getActionIndex();
                float x = ev.getX(i), y = ev.getY(i);
                if (hitButton(x, y)) { return true; }
                if (x < getWidth() * 0.5f && leftPointer < 0) {
                    leftPointer = ev.getPointerId(i);
                    leftCx = x;
                    leftCy = y;
                    leftX = x;
                    leftY = y;
                } else if (x >= getWidth() * 0.5f && rightPointer < 0) {
                    rightPointer = ev.getPointerId(i);
                    rightCx = x;
                    rightCy = y;
                    rightX = x;
                    rightY = y;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int id = ev.getPointerId(i);
                    if (id == leftPointer) {
                        leftX = ev.getX(i);
                        leftY = ev.getY(i);
                    } else if (id == rightPointer) {
                        rightX = ev.getX(i);
                        rightY = ev.getY(i);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int id = ev.getPointerId(ev.getActionIndex());
                if (id == leftPointer) {
                    leftPointer = -1;
                    leftX = leftCx;
                    leftY = leftCy;
                }
                if (id == rightPointer) {
                    rightPointer = -1;
                    rightX = rightCx;
                    rightY = rightCy;
                }
                if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    bA = bB = bC = bD = false;
                }
                break;
            }
            default:
                break;
        }
        invalidate();
        return true;
    }

    private boolean hitButton(float x, float y) {
        float d = getResources().getDisplayMetrics().density;
        float bx = getWidth() - 46 * d;
        float r = 26 * d;
        float[] ys = {90 * d, 150 * d, 210 * d, 270 * d};
        for (int i = 0; i < 4; i++) {
            if (Math.hypot(x - bx, y - ys[i]) < r) {
                switch (i) {
                    case 0: bA = true; edgeA = true; break;
                    case 1: bB = true; edgeB = true; break;
                    case 2: onProfile.run(); break;
                    default: onCamera.run(); break;
                }
                return true;
            }
        }
        // Quality toggle, bottom centre.
        if (Math.hypot(x - getWidth() * 0.5f, y - (getHeight() - 32 * d)) < 24 * d) {
            onQuality.run();
            return true;
        }
        return false;
    }

    /** Copy the current touch state into the engine's input block. */
    void pump() {
        float lx = clampStick(leftX - leftCx);
        float ly = clampStick(leftY - leftCy);
        float rx = clampStick(rightX - rightCx);
        float ry = clampStick(rightY - rightCy);

        out.moveX = lx;
        out.moveY = ly;
        out.lookX = rx;
        out.lookY = ry;

        switch (profile) {
            case CAR:
                // Left stick: up is throttle, down is brake. Right is steering.
                out.throttle = Math.max(0, -ly);
                out.brake = Math.max(0, ly);
                out.steer = rx;
                out.handbrake = bB;
                out.reverse = bA && Math.abs(ly) < 0.2;
                break;
            case BOAT:
                out.throttle = Math.max(0, -ly);
                out.brake = Math.max(0, ly);
                out.steer = rx;
                break;
            case HELICOPTER:
                // Left stick vertical is collective, horizontal is the pedals.
                // That is the real layout and it is worth the learning curve.
                out.collective = (-ly + 1) * 0.5;
                out.pedal = lx;
                break;
            case AEROPLANE:
                out.throttle = (-ly + 1) * 0.5;
                out.pedal = lx;
                out.brake = bB ? 1 : 0;
                out.flapsDown = edgeA;
                out.gearToggle = edgeB;
                break;
            default:
                out.run = bA;
                out.sprint = bB;
                out.jump = edgeA;
                break;
        }
        if (profile == ControlProfile.HELICOPTER) {
            out.engineToggle = edgeB;
        }
        edgeA = false;
        edgeB = false;
    }

    private float clampStick(float v) {
        float n = v / stickRadius;
        if (n > 1) { n = 1; }
        if (n < -1) { n = -1; }
        // A small dead zone: a thumb resting on glass is not an input.
        if (Math.abs(n) < 0.12f) { return 0; }
        return (n - Math.signum(n) * 0.12f) / 0.88f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;

        drawStick(canvas, leftCx, leftCy, leftX, leftY);
        drawStick(canvas, rightCx, rightCy, rightX, rightY);

        float bx = getWidth() - 46 * d;
        float r = 26 * d;
        String[] names = buttonNames();
        float[] ys = {90 * d, 150 * d, 210 * d, 270 * d};
        boolean[] on = {bA, bB, false, false};
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(bx, ys[i], r, on[i] ? knob : fill);
            canvas.drawCircle(bx, ys[i], r, ring);
            canvas.drawText(names[i], bx, ys[i] + 5 * d, label);
        }

        float qx = getWidth() * 0.5f;
        float qy = getHeight() - 32 * d;
        canvas.drawCircle(qx, qy, 24 * d, fill);
        canvas.drawCircle(qx, qy, 24 * d, ring);
        canvas.drawText("GFX", qx, qy + 5 * d, label);

        // Left stick legend, so the collective is not a mystery.
        canvas.drawText(stickLegend(), leftCx, leftCy - stickRadius - 10 * d, label);
    }

    private void drawStick(Canvas canvas, float cx, float cy, float x, float y) {
        canvas.drawCircle(cx, cy, stickRadius, fill);
        canvas.drawCircle(cx, cy, stickRadius, ring);
        float dx = x - cx, dy = y - cy;
        float len = (float) Math.hypot(dx, dy);
        if (len > stickRadius) {
            dx = dx / len * stickRadius;
            dy = dy / len * stickRadius;
        }
        canvas.drawCircle(cx + dx, cy + dy, stickRadius * 0.38f, knob);
    }

    private String[] buttonNames() {
        switch (profile) {
            case CAR: return new String[]{"REV", "HBRK", "SWAP", "CAM"};
            case BOAT: return new String[]{"\u2013", "\u2013", "SWAP", "CAM"};
            case HELICOPTER: return new String[]{"\u2013", "ENG", "SWAP", "CAM"};
            case AEROPLANE: return new String[]{"FLAP", "GEAR", "SWAP", "CAM"};
            default: return new String[]{"RUN", "SPRT", "SWAP", "CAM"};
        }
    }

    private String stickLegend() {
        switch (profile) {
            case CAR: case BOAT: return "throttle / brake";
            case HELICOPTER: return "collective + pedals";
            case AEROPLANE: return "throttle + rudder";
            default: return "move";
        }
    }
}
