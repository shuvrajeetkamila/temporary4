package ai.shuvrajeetkamila.mumbai.android;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.game.ControlProfile;
import ai.shuvrajeetkamila.mumbai.game.InputState;

/**
 * Touch + gamepad controller.
 *
 * <p>Widgets are laid out per {@link ControlProfile}; the same widget set is drawn by
 * {@link HudView} so there is a single source of truth for hit boxes and visuals.
 * Multi-touch is tracked per pointer id so steering and throttle work simultaneously.
 */
public class TouchController {

    public static final int W_LEFT_STICK = 0;
    public static final int W_RIGHT_STICK = 1;
    public static final int W_THROTTLE = 2;
    public static final int W_BRAKE = 3;
    public static final int W_ACTION_A = 4;
    public static final int W_ACTION_B = 5;
    public static final int W_ACTION_C = 6;
    public static final int W_ACTION_D = 7;
    public static final int W_PAUSE = 8;

    public static final int TYPE_STICK = 0;
    public static final int TYPE_BUTTON = 1;
    public static final int TYPE_PEDAL = 2;

    /** A single on-screen control. */
    public static final class Widget {
        public int id;
        public int type;
        public float cx, cy, radius;
        public String label = "";
        public boolean pressed;
        public boolean visible = true;
        /** Stick output, -1..1. */
        public float vx, vy;
        /** Pedal output 0..1. */
        public float value;
        public int pointerId = -1;
        public float touchX, touchY;

        public boolean hit(float x, float y) {
            float dx = x - cx;
            float dy = y - cy;
            float r = radius * 1.35f;
            return dx * dx + dy * dy <= r * r;
        }
    }

    private final InputState input;
    private final List<Widget> widgets = new ArrayList<>();
    private ControlProfile profile = ControlProfile.ON_FOOT;
    private int screenW = 1, screenH = 1;
    private boolean paused;

    /** User settings. */
    public float lookSensitivity = 1.0f;
    public float stickDeadZone = 0.08f;
    public boolean invertLookY = false;
    public boolean assists = true;

    public TouchController(InputState input) {
        this.input = input;
        for (int i = 0; i <= W_PAUSE; i++) {
            Widget w = new Widget();
            w.id = i;
            w.type = (i == W_LEFT_STICK || i == W_RIGHT_STICK) ? TYPE_STICK
                    : ((i == W_THROTTLE || i == W_BRAKE) ? TYPE_PEDAL : TYPE_BUTTON);
            widgets.add(w);
        }
    }

    public List<Widget> widgets() {
        return widgets;
    }

    public ControlProfile profile() {
        return profile;
    }

    public Widget widget(int id) {
        return widgets.get(id);
    }

    public void setProfile(ControlProfile p) {
        if (p != profile) {
            profile = p;
            releaseAll();
            layout(screenW, screenH);
        }
    }

    private void releaseAll() {
        for (int i = 0; i < widgets.size(); i++) {
            Widget w = widgets.get(i);
            w.pointerId = -1;
            w.pressed = false;
            w.vx = 0;
            w.vy = 0;
            w.value = 0;
        }
        input.clearMotion();
    }

    /** Position every widget for the current screen size and control profile. */
    public void layout(int w, int h) {
        screenW = Math.max(1, w);
        screenH = Math.max(1, h);
        float unit = Math.min(screenW, screenH);
        float stickR = unit * 0.16f;
        float btnR = unit * 0.085f;
        float margin = unit * 0.07f;

        Widget left = widget(W_LEFT_STICK);
        left.radius = stickR;
        left.cx = margin + stickR;
        left.cy = screenH - margin - stickR;
        left.visible = true;

        Widget right = widget(W_RIGHT_STICK);
        right.radius = stickR;
        right.cx = screenW - margin - stickR;
        right.cy = screenH - margin - stickR;

        Widget throttle = widget(W_THROTTLE);
        throttle.radius = btnR * 1.25f;
        throttle.cx = screenW - margin - btnR * 1.3f;
        throttle.cy = screenH - margin - btnR * 1.2f;

        Widget brake = widget(W_BRAKE);
        brake.radius = btnR * 1.05f;
        brake.cx = screenW - margin - btnR * 3.9f;
        brake.cy = screenH - margin - btnR * 0.9f;

        Widget a = widget(W_ACTION_A);
        a.radius = btnR * 0.8f;
        a.cx = screenW - margin - btnR * 1.3f;
        a.cy = screenH - margin - btnR * 4.0f;

        Widget b = widget(W_ACTION_B);
        b.radius = btnR * 0.8f;
        b.cx = screenW - margin - btnR * 3.6f;
        b.cy = screenH - margin - btnR * 4.6f;

        Widget c = widget(W_ACTION_C);
        c.radius = btnR * 0.72f;
        c.cx = screenW - margin - btnR * 5.6f;
        c.cy = screenH - margin - btnR * 3.2f;

        Widget d = widget(W_ACTION_D);
        d.radius = btnR * 0.72f;
        d.cx = margin + btnR * 0.9f;
        d.cy = margin + btnR * 0.9f + unit * 0.14f;

        Widget pause = widget(W_PAUSE);
        pause.radius = btnR * 0.55f;
        pause.cx = screenW - btnR * 0.9f - unit * 0.02f;
        pause.cy = btnR * 0.9f + unit * 0.02f;

        applyProfileLabels();
    }

    private void applyProfileLabels() {
        Widget left = widget(W_LEFT_STICK);
        Widget right = widget(W_RIGHT_STICK);
        Widget throttle = widget(W_THROTTLE);
        Widget brake = widget(W_BRAKE);
        Widget a = widget(W_ACTION_A);
        Widget b = widget(W_ACTION_B);
        Widget c = widget(W_ACTION_C);
        Widget d = widget(W_ACTION_D);
        Widget pause = widget(W_PAUSE);
        pause.label = "II";
        pause.visible = true;
        d.label = "CAM";
        d.visible = true;

        switch (profile) {
            case ON_FOOT:
                left.label = "MOVE";
                right.label = "LOOK";
                right.visible = true;
                throttle.visible = false;
                brake.visible = false;
                a.label = "JUMP";
                a.visible = true;
                b.label = "USE";
                b.visible = true;
                c.label = "RUN";
                c.visible = true;
                break;
            case CAR:
                left.label = "STEER";
                left.visible = true;
                right.visible = false;
                throttle.visible = true;
                throttle.label = "GAS";
                brake.visible = true;
                brake.label = "BRAKE";
                a.label = "EXIT";
                a.visible = true;
                b.label = "HAND";
                b.visible = true;
                c.label = "HORN";
                c.visible = true;
                break;
            case BOAT:
                left.label = "HELM";
                left.visible = true;
                right.visible = false;
                throttle.visible = true;
                throttle.label = "POWER";
                brake.visible = true;
                brake.label = "REV";
                a.label = "EXIT";
                a.visible = true;
                b.label = "ENG";
                b.visible = true;
                c.label = "ANCH";
                c.visible = true;
                break;
            case HELICOPTER:
                left.label = "COLL/YAW";
                left.visible = true;
                right.label = "CYCLIC";
                right.visible = true;
                throttle.visible = false;
                brake.visible = false;
                a.label = "EXIT";
                a.visible = true;
                b.label = "ENG";
                b.visible = true;
                c.label = "SAS";
                c.visible = true;
                break;
            case AEROPLANE:
                left.label = "RUDDER";
                left.visible = true;
                right.label = "STICK";
                right.visible = true;
                throttle.visible = true;
                throttle.label = "THR";
                brake.visible = true;
                brake.label = "BRK";
                a.label = "EXIT";
                a.visible = true;
                b.label = "ENG";
                b.visible = true;
                c.label = "FLAP";
                c.visible = true;
                break;
            default:
                break;
        }
    }

    /** Route a motion event into the widget set. */
    public void onTouch(MotionEvent ev, int viewW, int viewH) {
        if (screenW != viewW || screenH != viewH) {
            layout(viewW, viewH);
        }
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = ev.getActionIndex();
                press(ev.getPointerId(idx), ev.getX(idx), ev.getY(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    move(ev.getPointerId(i), ev.getX(i), ev.getY(i));
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = ev.getActionIndex();
                release(ev.getPointerId(idx));
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                releaseAll();
                break;
            }
            default:
                break;
        }
        apply();
    }

    private void press(int pointerId, float x, float y) {
        // Widgets first.
        for (int i = 0; i < widgets.size(); i++) {
            Widget w = widgets.get(i);
            if (!w.visible || w.pointerId != -1) {
                continue;
            }
            if (w.hit(x, y)) {
                w.pointerId = pointerId;
                w.pressed = true;
                w.touchX = x;
                w.touchY = y;
                if (w.type == TYPE_STICK) {
                    updateStick(w, x, y);
                } else if (w.type == TYPE_PEDAL) {
                    w.value = 1f;
                }
                if (w.id == W_PAUSE) {
                    paused = !paused;
                    input.pause = true;
                }
                return;
            }
        }
        // Free area on the right half acts as a camera drag zone when no look stick is shown.
        Widget right = widget(W_RIGHT_STICK);
        if (!right.visible && x > screenW * 0.35f && right.pointerId == -1) {
            right.pointerId = pointerId;
            right.touchX = x;
            right.touchY = y;
        }
    }

    private void move(int pointerId, float x, float y) {
        for (int i = 0; i < widgets.size(); i++) {
            Widget w = widgets.get(i);
            if (w.pointerId != pointerId) {
                continue;
            }
            if (w.type == TYPE_STICK) {
                if (w.visible) {
                    updateStick(w, x, y);
                } else {
                    // Drag-look: relative movement.
                    w.vx = Mth.clamp((x - w.touchX) / (screenW * 0.25f), -1f, 1f);
                    w.vy = Mth.clamp((y - w.touchY) / (screenH * 0.25f), -1f, 1f);
                    w.touchX = x;
                    w.touchY = y;
                }
            } else if (w.type == TYPE_PEDAL) {
                w.value = w.hit(x, y) ? 1f : 0f;
            } else {
                w.pressed = w.hit(x, y);
            }
            return;
        }
    }

    private void release(int pointerId) {
        for (int i = 0; i < widgets.size(); i++) {
            Widget w = widgets.get(i);
            if (w.pointerId == pointerId) {
                w.pointerId = -1;
                w.pressed = false;
                w.value = 0f;
                w.vx = 0f;
                w.vy = 0f;
                fireRelease(w);
            }
        }
    }

    private void fireRelease(Widget w) {
        switch (w.id) {
            case W_ACTION_A:
                if (profile == ControlProfile.ON_FOOT) {
                    input.jump = true;
                } else {
                    input.interact = true;
                }
                break;
            case W_ACTION_B:
                if (profile == ControlProfile.ON_FOOT) {
                    input.interact = true;
                } else if (profile == ControlProfile.CAR) {
                    input.handbrake = false;
                } else {
                    input.engineToggle = true;
                }
                break;
            case W_ACTION_C:
                if (profile == ControlProfile.AEROPLANE) {
                    input.flapNotch = (input.flapNotch + 1) % 3;
                }
                break;
            case W_ACTION_D:
                input.cameraCycle = true;
                break;
            default:
                break;
        }
    }

    private void updateStick(Widget w, float x, float y) {
        float dx = (x - w.cx) / w.radius;
        float dy = (y - w.cy) / w.radius;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1f) {
            dx /= len;
            dy /= len;
        }
        w.vx = dx;
        w.vy = dy;
        w.touchX = x;
        w.touchY = y;
    }

    /** Push widget state into the action map for the active profile. */
    public void apply() {
        Widget left = widget(W_LEFT_STICK);
        Widget right = widget(W_RIGHT_STICK);
        Widget throttle = widget(W_THROTTLE);
        Widget brake = widget(W_BRAKE);
        Widget a = widget(W_ACTION_A);
        Widget b = widget(W_ACTION_B);
        Widget c = widget(W_ACTION_C);

        float lx = Mth.deadZone(left.vx, stickDeadZone);
        float ly = Mth.deadZone(left.vy, stickDeadZone);
        float rx = Mth.deadZone(right.vx, stickDeadZone) * lookSensitivity;
        float ry = Mth.deadZone(right.vy, stickDeadZone) * lookSensitivity * (invertLookY ? -1f : 1f);

        switch (profile) {
            case ON_FOOT:
                input.moveX = lx;
                input.moveY = -ly;
                input.lookX = rx;
                input.lookY = ry;
                input.run = c.pressed;
                break;
            case CAR:
                input.steer = lx;
                input.throttle = throttle.value;
                input.brake = brake.value;
                input.handbrake = b.pressed;
                input.lookX = right.visible ? rx : Mth.deadZone(right.vx, 0.02f);
                input.lookY = right.visible ? ry : Mth.deadZone(right.vy, 0.02f);
                break;
            case BOAT:
                input.steer = lx;
                input.throttle = throttle.value;
                input.brake = brake.value;
                input.lookX = rx;
                input.lookY = ry;
                break;
            case HELICOPTER:
                // Left stick: up = collective increase, left/right = pedals.
                input.collective = Mth.clamp01(input.collective - ly * 0.02f);
                input.yaw = lx;
                input.pitch = -ry;
                input.steer = rx;
                break;
            case AEROPLANE:
                input.yaw = lx;
                input.pitch = -ry;
                input.steer = rx;
                input.throttle = throttle.value;
                input.brake = brake.value;
                break;
            default:
                break;
        }
        if (a.pressed && profile == ControlProfile.ON_FOOT) {
            input.run = input.run || false;
        }
    }

    /** Gamepad support through the same action map. */
    public boolean onKey(int keyCode, boolean down, KeyEvent event) {
        if (event != null && (event.getSource() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
                && (event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return false;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                if (!down) {
                    if (profile == ControlProfile.ON_FOOT) {
                        input.jump = true;
                    } else {
                        input.interact = true;
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (!down) {
                    input.engineToggle = true;
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (!down) {
                    input.cameraCycle = true;
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                input.handbrake = down;
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
                if (!down) {
                    input.pause = true;
                }
                return true;
            default:
                return false;
        }
    }

    public boolean onGamepadMotion(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return false;
        }
        float lx = event.getAxisValue(MotionEvent.AXIS_X);
        float ly = event.getAxisValue(MotionEvent.AXIS_Y);
        float rx = event.getAxisValue(MotionEvent.AXIS_Z);
        float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
        float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        Widget left = widget(W_LEFT_STICK);
        Widget right = widget(W_RIGHT_STICK);
        left.vx = lx;
        left.vy = ly;
        right.vx = rx;
        right.vy = ry;
        widget(W_THROTTLE).value = rt;
        widget(W_BRAKE).value = lt;
        apply();
        return true;
    }

    public boolean handleBack() {
        paused = !paused;
        input.pause = true;
        return true;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean p) {
        paused = p;
    }
}
