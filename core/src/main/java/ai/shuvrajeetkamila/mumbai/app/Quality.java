package ai.shuvrajeetkamila.mumbai.app;

/**
 * Quality tiers, and what each one actually gives up.
 *
 * <p>The thermal tiers are the point. Capping the frame rate alone does not
 * cool a phone down: the GPU simply finishes each frame sooner and idles, and
 * the SoC keeps drawing the same power per frame. To actually reduce the heat
 * the work has to shrink, so the eco tiers cut render resolution, shorten the
 * streaming rings, coarsen the sea tessellation and reduce the agent caps as
 * well as the frame cap. {@code EcoReducesWorkTest} asserts every one of those
 * numbers goes down, because a tier that only lowers the frame cap would be
 * dishonest.
 */
public enum Quality {
    //          fps  high   med    far   scale cars peds seaGrid uploads resident
    LOW(         30,  300,  1200,  9000, 0.75f,  40,  30,  64, 2, 160),
    BALANCED(    30,  520,  2200, 14000, 0.90f,  90,  70,  96, 3, 280),
    HIGH(        30,  800,  3200, 22000, 1.00f, 140, 120, 128, 4, 420),
    ECO(         20,  280,  1100,  8000, 0.70f,  35,  20,  64, 1, 130),
    ECO_SEVERE(  15,  220,   800,  6000, 0.60f,  18,  10,  48, 1,  96);

    public final int targetFps;
    public final double highRadius;
    public final double medRadius;
    public final double farRadius;
    public final float renderScale;
    public final int maxCars;
    public final int maxPedestrians;
    public final int seaGrid;
    public final int uploadsPerFrame;

    /**
     * How many chunks may be on the GPU at once.
     *
     * <p>Per tier, and actually applied, which it was not: every tier
     * used the streamer's own 420 and nothing ever called
     * setMaxResident. That went unnoticed because a bug in the request
     * queue stranded tiles in QUEUED_IO and residency never climbed
     * past about forty, so the budget was never reached by anything.
     * With the queue fixed the budget is real, and a 420-chunk working
     * set is far too much for the tier that asked for LOW.
     *
     * <p>It also sets the draw distance: the streamer clamps its far
     * radius to the radius this many tiles can actually cover, because
     * streaming further than the budget is how you get holes.
     *
     * <p>These are deliberately modest. Measured, the ground under the
     * player is loaded on 100 per cent of samples at every speed and
     * altitude tested with as few as 120 chunks: the near rings are
     * what you stand on, and the rest of the budget only buys distance.
     * A larger number was tried and the emulator did not survive it.
     */
    public final int maxResident;

    Quality(int fps, double high, double med, double far, float scale,
            int cars, int peds, int seaGrid, int uploads, int resident) {
        this.targetFps = fps;
        this.highRadius = high;
        this.medRadius = med;
        this.farRadius = far;
        this.renderScale = scale;
        this.maxCars = cars;
        this.maxPedestrians = peds;
        this.seaGrid = seaGrid;
        this.uploadsPerFrame = uploads;
        this.maxResident = resident;
    }

    public boolean isEco() { return this == ECO || this == ECO_SEVERE; }

    /**
     * Pick a tier for a thermal status. The values match Android's
     * {@code PowerManager} thermal headroom levels: 0 none, 1 light,
     * 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown.
     */
    public static Quality applyThermal(Quality base, int thermalStatus) {
        if (thermalStatus >= 4) { return ECO_SEVERE; }
        if (thermalStatus >= 2) { return ECO; }
        return base;
    }

    /** Shadow of the above, so the HUD can explain itself. */
    public static String thermalLabel(int status) {
        switch (status) {
            case 0: return "none";
            case 1: return "light";
            case 2: return "moderate";
            case 3: return "severe";
            case 4: return "critical";
            case 5: return "emergency";
            default: return status >= 6 ? "shutdown" : "unknown";
        }
    }
}
