package ai.shuvrajeetkamila.mumbai.app;

/**
 * Everything the player is doing this frame, as plain numbers.
 *
 * <p>The engine never sees a touch event. The Android layer turns sticks,
 * pedals and buttons into these fields, which means the whole simulation can
 * be driven from a test with no window, no GL context and no device.
 */
public final class InputState {

    /** Left stick: -1..1. Meaning depends on the profile. */
    public double moveX;
    public double moveY;
    /** Right stick, usually look or cyclic. */
    public double lookX;
    public double lookY;

    public double throttle;
    public double brake;
    public double steer;

    /** Helicopter collective, 0..1. */
    public double collective;
    /** Pedals: yaw or rudder, -1..1. */
    public double pedal;

    public boolean jump;
    public boolean run;
    public boolean sprint;
    public boolean handbrake;
    public boolean action;
    public boolean flapsUp;
    public boolean flapsDown;
    public boolean gearToggle;
    public boolean engineToggle;
    public boolean cameraToggle;
    /**
     * Edge-triggered. On foot it enters the nearest vehicle; in a vehicle it
     * gets out. One button for both, as the genre has always done it.
     */
    public boolean enterExit;
    /**
     * Edge-triggered baton swing, on foot only. The only weapon in the
     * game: there are no firearms anywhere in it, by design.
     */
    public boolean attack;
    public boolean reverse;

    public void clearEdges() {
        jump = false;
        action = false;
        flapsUp = false;
        flapsDown = false;
        gearToggle = false;
        engineToggle = false;
        cameraToggle = false;
        enterExit = false;
        attack = false;
    }

    public void reset() {
        moveX = moveY = lookX = lookY = 0;
        throttle = brake = steer = 0;
        collective = 0;
        pedal = 0;
        run = sprint = handbrake = reverse = false;
        clearEdges();
    }
}
