package ai.shuvrajeetkamila.mumbai.game;

/**
 * Device independent action map.
 *
 * <p>Touch controls, the on-screen HUD widgets and Android gamepads all write into this
 * single struct; the simulation never reads raw pointers. Values are buffered by the input
 * thread and sampled once per fixed physics step.
 */
public final class InputState {

    /** Left stick: -1..1, +X right, +Y forward. */
    public volatile float moveX;
    public volatile float moveY;

    /** Right stick / look: -1..1. */
    public volatile float lookX;
    public volatile float lookY;

    /** Normalised throttle 0..1 (car accelerator, boat/heli/plane power). */
    public volatile float throttle;
    /** Normalised brake / reverse 0..1. */
    public volatile float brake;
    /** Steering -1..1 (also aileron / rudder-by-wire for aircraft). */
    public volatile float steer;
    /** Aircraft pitch -1..1 (pull back = +1). */
    public volatile float pitch;
    /** Aircraft / helicopter yaw pedals -1..1. */
    public volatile float yaw;
    /** Helicopter collective 0..1. */
    public volatile float collective = 0.0f;
    /** Flap notch 0..2 for the aeroplane. */
    public volatile int flapNotch;

    public volatile boolean handbrake;
    public volatile boolean run;
    public volatile boolean jump;
    public volatile boolean interact;
    public volatile boolean engineToggle;
    public volatile boolean gearToggle;
    public volatile boolean cameraCycle;
    public volatile boolean recover;
    public volatile boolean pause;

    /** Consume-once helpers keep edge-triggered actions from firing every step. */
    public boolean consumeJump() {
        boolean v = jump;
        jump = false;
        return v;
    }

    public boolean consumeInteract() {
        boolean v = interact;
        interact = false;
        return v;
    }

    public boolean consumeEngineToggle() {
        boolean v = engineToggle;
        engineToggle = false;
        return v;
    }

    public boolean consumeGearToggle() {
        boolean v = gearToggle;
        gearToggle = false;
        return v;
    }

    public boolean consumeCameraCycle() {
        boolean v = cameraCycle;
        cameraCycle = false;
        return v;
    }

    public boolean consumeRecover() {
        boolean v = recover;
        recover = false;
        return v;
    }

    public boolean consumePause() {
        boolean v = pause;
        pause = false;
        return v;
    }

    public void clearMotion() {
        moveX = 0;
        moveY = 0;
        lookX = 0;
        lookY = 0;
        throttle = 0;
        brake = 0;
        steer = 0;
        pitch = 0;
        yaw = 0;
        handbrake = false;
    }
}
