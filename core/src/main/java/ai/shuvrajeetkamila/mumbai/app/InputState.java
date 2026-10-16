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

    /**
     * Where the collective lever is sitting, 0..1.
     *
     * <p>A collective is a lever, not a spring-centred stick: it stays
     * where the pilot leaves it. Modelling it as a stick meant the
     * control sprang back to half travel the moment the thumb came off,
     * and half travel is below a hover - the machine sank at four
     * metres a second whenever nobody was actively holding it up, which
     * is most of the time you are also trying to work the cyclic.
     *
     * <p>Deliberately not cleared by {@link #reset()}: it is the
     * position of a physical control, not a per-frame input. It is set
     * back to the ground position by {@link #resetLevers()} when the
     * player changes seat - a helicopter you have just climbed into is
     * sitting on its skids, and resting the lever at the hover value
     * floated it off the pad untouched.
     */
    public double collectiveLever = ai.shuvrajeetkamila.mumbai.physics
            .HelicopterPhysics.GROUND_COLLECTIVE;
    /** Pedals: yaw or rudder, -1..1. */
    public double pedal;

    public boolean jump;
    public boolean run;
    public boolean sprint;
    public boolean handbrake;
    public boolean flapsUp;
    public boolean flapsDown;
    public boolean gearToggle;
    public boolean engineToggle;
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
    /**
     * Edge-triggered: opens or closes the full-screen map. Works from
     * every profile, including mid-flight.
     */
    public boolean map;
    /**
     * Edge-triggered: teleport to whatever is marked on the map. Set by
     * the map overlay on the UI thread and consumed by the engine on its
     * own, because moving a rigid body and rebasing the floating origin
     * from under the renderer is a race worth not having.
     */
    public boolean mapConfirm;

    public void clearEdges() {
        jump = false;
        flapsUp = false;
        flapsDown = false;
        gearToggle = false;
        engineToggle = false;
        enterExit = false;
        attack = false;
        map = false;
        mapConfirm = false;
    }

    /** Puts the held controls back where a fresh aircraft has them. */
    public void resetLevers() {
        collectiveLever = ai.shuvrajeetkamila.mumbai.physics
                .HelicopterPhysics.GROUND_COLLECTIVE;
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
