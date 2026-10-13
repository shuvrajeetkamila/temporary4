package ai.shuvrajeetkamila.mumbai.app;

/**
 * Which on-screen control does what, for each thing you can be driving.
 *
 * <p>This lives in core rather than in the Android view so it can be
 * tested without a device. It used to be inlined in the touch handler,
 * keyed off the button's position on screen, and two mappings collided
 * because of it: on foot, slot 0 set both the held RUN flag and the
 * edge-triggered JUMP, so setting off at a run always launched you into
 * the air; and in the aeroplane slot 1 was both the gear toggle and the
 * wheel brakes. Addressing buttons by what they do instead of where they
 * are makes that class of mistake impossible, and lets a test assert that
 * no two controls on one profile share a source.
 */
public final class ControlMapping {

    private ControlMapping() { }

    // Buttons are identified by action, never by index.
    public static final int ACT_NONE = 0;
    public static final int ACT_SPRINT = 1;
    public static final int ACT_JUMP = 2;
    public static final int ACT_ENTER = 3;
    public static final int ACT_BATON = 4;
    public static final int ACT_SWAP = 5;
    public static final int ACT_CAM = 6;
    public static final int ACT_REVERSE = 7;
    public static final int ACT_HANDBRAKE = 8;
    public static final int ACT_ENGINE = 9;
    public static final int ACT_FLAP_DOWN = 10;
    public static final int ACT_FLAP_UP = 11;
    public static final int ACT_GEAR = 12;
    public static final int ACT_WHEEL_BRAKE = 13;
    public static final int ACT_MAP = 14;

    public static final int ACT_COUNT = 15;

    /** Most buttons that can be shown at once: two columns of four. */
    public static final int MAX_SLOTS = 8;

    /** Push beyond this fraction of stick travel and you break into a run. */
    public static final double RUN_THRESHOLD = 0.62;

    /**
     * Buttons for a profile, nearest the thumb first.
     *
     * <p>Slot 0 is bottom right and the grid fills upward in two columns,
     * so the first entry is the easiest to reach while steering.
     */
    public static int[] actionsFor(ControlProfile p) {
        switch (p) {
            case CAR:
                return new int[]{ACT_HANDBRAKE, ACT_REVERSE, ACT_ENTER,
                        ACT_CAM, ACT_SWAP, ACT_MAP};
            case BOAT:
                return new int[]{ACT_ENTER, ACT_CAM, ACT_SWAP, ACT_MAP};
            case HELICOPTER:
                return new int[]{ACT_ENGINE, ACT_ENTER, ACT_CAM, ACT_SWAP, ACT_MAP};
            case AEROPLANE:
                return new int[]{ACT_WHEEL_BRAKE, ACT_FLAP_DOWN, ACT_FLAP_UP,
                        ACT_GEAR, ACT_ENTER, ACT_CAM, ACT_SWAP, ACT_MAP};
            default:
                return new int[]{ACT_SPRINT, ACT_JUMP, ACT_ENTER, ACT_BATON,
                        ACT_CAM, ACT_SWAP, ACT_MAP};
        }
    }

    /** What to print on a button. */
    public static String labelFor(int action) {
        switch (action) {
            case ACT_SPRINT: return "SPRT";
            case ACT_JUMP: return "JUMP";
            case ACT_ENTER: return "ENTER";
            case ACT_BATON: return "BATON";
            case ACT_SWAP: return "SWAP";
            case ACT_CAM: return "CAM";
            case ACT_REVERSE: return "REV";
            case ACT_HANDBRAKE: return "HBRK";
            case ACT_ENGINE: return "ENG";
            case ACT_FLAP_DOWN: return "FLAP+";
            case ACT_FLAP_UP: return "FLAP-";
            case ACT_GEAR: return "GEAR";
            case ACT_WHEEL_BRAKE: return "BRAKE";
            case ACT_MAP: return "MAP";
            default: return "";
        }
    }

    /** What the left stick is for, shown above it. */
    public static String stickLegend(ControlProfile p) {
        switch (p) {
            case CAR: return "throttle / brake";
            case BOAT: return "ahead / astern";
            case HELICOPTER: return "collective + pedals";
            case AEROPLANE: return "throttle + rudder";
            default: return "move";
        }
    }

    /**
     * Turns stick deflections and button states into engine inputs.
     *
     * <p>Sticks are already dead-zoned and normalised to -1..1, with +Y
     * down the screen as Android reports it.
     *
     * @param held  per-action, true while the button is down
     * @param edge  per-action, true only on the frame it went down
     */
    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static double clamp11(double v) {
        return v < -1 ? -1 : (v > 1 ? 1 : v);
    }

    public static void apply(ControlProfile profile, InputState out,
                             double lx, double ly, double rx, double ry,
                             boolean[] held, boolean[] edge) {
        // Clamp at the boundary rather than trusting the caller. The two
        // half-range controls are (1 - ly) * 0.5, which lands a few parts
        // in 10^16 below zero for a stick reading exactly 1, and a
        // negative throttle is the sort of thing that reads fine until
        // something downstream takes a square root of it.
        lx = clamp11(lx);
        ly = clamp11(ly);
        rx = clamp11(rx);
        ry = clamp11(ry);
        out.moveX = lx;
        out.moveY = ly;
        out.lookX = rx;
        out.lookY = ry;

        // The map opens from every profile, so it is handled before
        // the per-profile switch rather than repeated in each branch.
        // OR rather than assign. The map overlay's own CLOSE button sets
        // this same edge from the UI thread, and the control pump runs
        // every frame whether or not anything is being touched - a plain
        // assignment wiped the request before the engine ever saw it, so
        // CLOSE did nothing. The engine clears it when it consumes it.
        out.map = out.map || edge[ACT_MAP];

        switch (profile) {
            case CAR:
                // Left stick: up is throttle, down is brake. Right steers.
                out.throttle = clamp01(-ly);
                out.brake = clamp01(ly);
                out.steer = rx;
                out.handbrake = held[ACT_HANDBRAKE];
                // Only from a standstill, so a flick of the stick at speed
                // cannot drop it into reverse.
                out.reverse = held[ACT_REVERSE] && Math.abs(ly) < 0.2;
                break;
            case BOAT:
                // A boat has no brake: pulling back drives the screw astern.
                out.throttle = clamp01(-ly);
                out.brake = clamp01(ly);
                out.steer = rx;
                break;
            case HELICOPTER:
                // Left stick vertical is collective, horizontal is the
                // pedals. That is the real layout and worth the learning.
                out.collective = clamp01((1 - ly) * 0.5);
                out.pedal = lx;
                out.engineToggle = edge[ACT_ENGINE];
                break;
            case AEROPLANE:
                out.throttle = clamp01((1 - ly) * 0.5);
                out.pedal = lx;
                out.brake = held[ACT_WHEEL_BRAKE] ? 1 : 0;
                out.flapsDown = edge[ACT_FLAP_DOWN];
                out.flapsUp = edge[ACT_FLAP_UP];
                out.gearToggle = edge[ACT_GEAR];
                break;
            default:
                // Running comes from how far the stick is pushed rather
                // than from a button, which frees the button for jump and
                // stops the two firing together.
                out.run = Math.hypot(lx, ly) > RUN_THRESHOLD;
                out.sprint = held[ACT_SPRINT];
                out.jump = edge[ACT_JUMP];
                break;
        }
        // One button both ways, whatever is being driven: on foot it gets
        // you in, in a vehicle it gets you out.
        out.enterExit = edge[ACT_ENTER];
        out.attack = edge[ACT_BATON];
    }
}
