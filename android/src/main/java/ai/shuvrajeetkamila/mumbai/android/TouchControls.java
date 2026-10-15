package ai.shuvrajeetkamila.mumbai.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import ai.shuvrajeetkamila.mumbai.app.ControlMapping;
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
    private final Runnable onWeather;

    /** Drawn filled when the monsoon is switched on. */
    volatile boolean weatherOn = true;

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

    /** Held state and edge state, indexed by action. */
    private final boolean[] held = new boolean[ControlMapping.ACT_COUNT];
    private final boolean[] edge = new boolean[ControlMapping.ACT_COUNT];

    /** Scratch for the current profile's layout. */
    private final float[] slotX = new float[ControlMapping.MAX_SLOTS];
    private final float[] slotY = new float[ControlMapping.MAX_SLOTS];
    private float slotR;
    private int slotCount;

    /** Which pointer is holding which slot, so two can be held at once. */
    private final int[] slotPointer = new int[ControlMapping.MAX_SLOTS];

    TouchControls(Context c, InputState out, Runnable onProfile,
                  Runnable onCamera, Runnable onQuality,
                  Runnable onWeather) {
        super(c);
        this.out = out;
        this.onProfile = onProfile;
        this.onCamera = onCamera;
        this.onQuality = onQuality;
        this.onWeather = onWeather;
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

    /** Clock for the collective lever's travel rate. */
    private long lastPumpMs;

    void setProfile(ControlProfile p) {
        if (p != profile) {
            // Carrying a throttle or a collective across a profile change
            // leaves a control applied that the new vehicle never set.
            out.reset();
            // And the collective lever goes back to the hover, so a
            // fresh helicopter is not inheriting where the last one's
            // lever happened to be left.
            out.resetLevers();
            for (int i = 0; i < held.length; i++) { held[i] = false; edge[i] = false; }
            for (int i = 0; i < ControlMapping.MAX_SLOTS; i++) { slotPointer[i] = -1; }
        }
        profile = p;
        invalidate();
    }

    /**
     * Lays the buttons out from the real view size.
     *
     * <p>They used to sit at fixed offsets of 90 to 390 dp down from the
     * top, which assumed a view taller than 416 dp. A phone in landscape
     * is 320 to 412 dp tall, so the sixth button was off the bottom of
     * the screen on every handset and only fitted on a tablet.
     */
    private void layoutButtons() {
        int[] acts = ControlMapping.actionsFor(profile);
        slotCount = Math.min(acts.length, ControlMapping.MAX_SLOTS);
        float d = getResources().getDisplayMetrics().density;
        float h = getHeight(), w = getWidth();
        slotR = Math.min(27 * d, h * 0.085f);
        float gap = slotR * 2.30f;
        float cx0 = w - (slotR + 14 * d);
        float cx1 = cx0 - gap;
        float cy = h * 0.47f;
        for (int i = 0; i < slotCount; i++) {
            slotX[i] = (i % 2 == 0) ? cx0 : cx1;
            slotY[i] = cy + (1.5f - (i / 2)) * gap;
        }
    }

    /** Left edge of the button cluster, so the stick can keep clear of it. */
    private float clusterLeft() {
        float d = getResources().getDisplayMetrics().density;
        float r = Math.min(27 * d, getHeight() * 0.085f);
        return getWidth() - (r + 14 * d) - r * 2.30f - r;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        stickRadius = Math.min(62 * getResources().getDisplayMetrics().density,
                h * 0.17f);
        leftCx = stickRadius * 1.5f;
        leftCy = h - stickRadius * 1.5f;
        // Clear of the button cluster, which owns the right edge. The old
        // default put the stick directly underneath it.
        layoutButtons();
        rightCx = Math.min(clusterLeft() - stickRadius * 1.15f,
                w - stickRadius * 1.5f);
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
                if (hitButton(x, y, ev.getPointerId(i))) { return true; }
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
                releaseButton(id);
                if (action == MotionEvent.ACTION_CANCEL) {
                    // Everything off: a cancel means the gesture was taken
                    // away from us and no UP is coming for the rest.
                    for (int k = 0; k < held.length; k++) { held[k] = false; }
                    for (int k = 0; k < ControlMapping.MAX_SLOTS; k++) { slotPointer[k] = -1; }
                }
                break;
            }
            default:
                break;
        }
        invalidate();
        return true;
    }

    private boolean hitButton(float x, float y, int pointerId) {
        layoutButtons();
        int[] acts = ControlMapping.actionsFor(profile);
        for (int i = 0; i < slotCount; i++) {
            if (Math.hypot(x - slotX[i], y - slotY[i]) >= slotR * 1.18f) {
                continue;
            }
            int a = acts[i];
            held[a] = true;
            edge[a] = true;
            slotPointer[i] = pointerId;
            // These two act immediately rather than through InputState,
            // because they change the host's state, not the vehicle's.
            if (a == ControlMapping.ACT_SWAP) { onProfile.run(); }
            if (a == ControlMapping.ACT_CAM) { onCamera.run(); }
            return true;
        }
        // Quality and weather, bottom centre.
        float d = getResources().getDisplayMetrics().density;
        float by = getHeight() - 32 * d;
        if (Math.hypot(x - (getWidth() * 0.5f - 30 * d), y - by) < 24 * d) {
            onQuality.run();
            return true;
        }
        if (Math.hypot(x - (getWidth() * 0.5f + 30 * d), y - by) < 24 * d) {
            onWeather.run();
            return true;
        }
        return false;
    }

    /** Releases whatever slot this pointer was holding. */
    private void releaseButton(int pointerId) {
        int[] acts = ControlMapping.actionsFor(profile);
        for (int i = 0; i < slotCount; i++) {
            if (slotPointer[i] == pointerId) {
                slotPointer[i] = -1;
                if (i < acts.length) { held[acts[i]] = false; }
            }
        }
    }

    void pump() {
        float lx = clampStick(leftX - leftCx);
        float ly = clampStick(leftY - leftCy);
        float rx = clampStick(rightX - rightCx);
        float ry = clampStick(rightY - rightCy);
        // The collective is a lever that integrates the stick, so this
        // needs to know how long it has been since the last pump. Taken
        // from the clock rather than assumed, and clamped, because the
        // UI thread is not the simulation thread and a stall here must
        // not slam the lever to a stop.
        long now = android.os.SystemClock.uptimeMillis();
        double dt = lastPumpMs == 0 ? 1.0 / 60.0
                : Math.min(0.1, (now - lastPumpMs) / 1000.0);
        lastPumpMs = now;
        ControlMapping.apply(profile, out, lx, ly, rx, ry, held, edge, dt);
        for (int i = 0; i < edge.length; i++) { edge[i] = false; }
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

        layoutButtons();
        int[] acts = ControlMapping.actionsFor(profile);
        for (int i = 0; i < slotCount; i++) {
            boolean on = held[acts[i]];
            canvas.drawCircle(slotX[i], slotY[i], slotR, on ? knob : fill);
            canvas.drawCircle(slotX[i], slotY[i], slotR, ring);
            canvas.drawText(ControlMapping.labelFor(acts[i]), slotX[i], slotY[i] + 5 * d, label);
        }

        float qx = getWidth() * 0.5f - 30 * d;
        float qy = getHeight() - 32 * d;
        canvas.drawCircle(qx, qy, 24 * d, fill);
        canvas.drawCircle(qx, qy, 24 * d, ring);
        canvas.drawText("GFX", qx, qy + 5 * d, label);

        // The monsoon switch. Filled when it is on, so the state is
        // readable without having to wait for a squall to find out.
        float wx = getWidth() * 0.5f + 30 * d;
        canvas.drawCircle(wx, qy, 24 * d, weatherOn ? knob : fill);
        canvas.drawCircle(wx, qy, 24 * d, ring);
        canvas.drawText("RAIN", wx, qy + 5 * d, label);

        // Left stick legend, so the collective is not a mystery.
        canvas.drawText(ControlMapping.stickLegend(profile), leftCx, leftCy - stickRadius - 10 * d, label);
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

}
