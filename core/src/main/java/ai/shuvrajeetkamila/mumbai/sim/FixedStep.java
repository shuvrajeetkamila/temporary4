package ai.shuvrajeetkamila.mumbai.sim;

/**
 * A fixed-rate accumulator so the physics never sees a variable timestep.
 *
 * <p>Everything integrates at exactly 60 Hz regardless of what the display is
 * doing. That matters for more than determinism: the suspension springs, the
 * rotor inflow solve and the tyre slip model are all tuned at a specific step,
 * and feeding them a 40 ms frame because the phone throttled would change the
 * handling of the car.
 *
 * <p>Two guards. A frame longer than 100 ms is clamped, so a garbage collection
 * pause or an app switch cannot hand the simulation half a second to catch up
 * at once. And no more than four substeps run per frame, so if the device
 * genuinely cannot keep up the simulation runs slightly slow rather than
 * entering a spiral where each frame takes longer and queues more work.
 *
 * <p>{@link #alpha()} is the leftover fraction, which the renderer uses to
 * interpolate between the last two states. Without it, rendering at 30 Hz from
 * a 60 Hz simulation shows every other state and judders.
 */
public final class FixedStep {

    public static final double STEP = 1.0 / 60.0;
    public static final int MAX_SUBSTEPS = 4;
    public static final double MAX_FRAME = 0.1;

    private double accumulator;
    private double alpha;
    private long totalSteps;
    private int lastSubsteps;
    private boolean sawOverrun;
    private int overrunCount;

    /**
     * Add a frame's elapsed time and return how many fixed steps to run.
     * Call {@link #alpha()} afterwards for the render interpolation factor.
     */
    public int beginFrame(double frameSeconds) {
        if (!Double.isFinite(frameSeconds) || frameSeconds < 0) {
            frameSeconds = STEP;
        }
        if (frameSeconds > MAX_FRAME) {
            frameSeconds = MAX_FRAME;
        }
        accumulator += frameSeconds;
        int n = 0;
        while (accumulator >= STEP && n < MAX_SUBSTEPS) {
            accumulator -= STEP;
            n++;
        }
        if (accumulator >= STEP) {
            // The device cannot keep up. Drop the backlog rather than queue it,
            // which keeps latency bounded at the cost of running slightly slow.
            sawOverrun = true;
            overrunCount++;
            accumulator = STEP * 0.5;
        }
        alpha = accumulator / STEP;
        totalSteps += n;
        lastSubsteps = n;
        return n;
    }

    /** Interpolation factor in [0, 1) between the previous and current state. */
    public double alpha() { return alpha; }

    public long totalSteps() { return totalSteps; }

    public int lastSubsteps() { return lastSubsteps; }

    public boolean overran() { return sawOverrun; }

    public int overrunCount() { return overrunCount; }

    public double simulatedSeconds() { return totalSteps * STEP; }

    public void reset() {
        accumulator = 0;
        alpha = 0;
        sawOverrun = false;
    }
}
