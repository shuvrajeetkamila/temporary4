package ai.shuvrajeetkamila.mumbai.sim;

/**
 * A snapshot of the simulation for the HUD.
 *
 * <p>The HUD runs on the Android UI thread and the simulation on the GL
 * thread. Rather than lock anything, the engine writes these volatile fields
 * roughly fifteen times a second and the UI reads whatever is there. A torn
 * read shows one stale number for one frame, which is invisible, and the
 * alternative is a lock on the hot path.
 */
public final class Telemetry {

    public volatile double lat;
    public volatile double lon;
    public volatile double worldX;
    public volatile double worldZ;
    public volatile double altitude;
    public volatile double speedKmh;
    public volatile double headingDeg;
    public volatile String profile = "On foot";
    public volatile String gear = "N";
    public volatile double rpm;
    public volatile String nearestPlace = "";

    public volatile double frameMs;
    public volatile double fps;
    public volatile int substeps;
    public volatile long simSteps;
    public volatile String quality = "BALANCED";
    public volatile int thermalStatus;
    public volatile String thermalLabel = "none";

    public volatile int tilesActive;
    public volatile int tilesResident;
    public volatile int tilesPendingIo;
    public volatile int tilesDecoding;
    public volatile int tilesQueuedGpu;
    public volatile long geometryBytes;
    public volatile int rebases;
    public volatile int drawCalls;
    public volatile int trianglesK;

    public volatile int cars;
    public volatile int pedestrians;

    /** Vehicle-specific readouts, meaning depends on the profile. */
    public volatile double aux1;
    public volatile double aux2;
    public volatile String auxLabel1 = "";
    public volatile String auxLabel2 = "";

    /**
     * Crash damage on the vehicle being driven, 0 pristine to 1 wrecked,
     * or 0 on foot. The HUD only draws it once there is some, so an
     * undamaged car shows no gauge at all.
     */
    public volatile double damage;

    public volatile String warning = "";
    public volatile boolean onWater;
    public volatile double tideM;

    /**
     * "Press ENTER to get in", or empty. Computed in the simulation and
     * published like everything else the HUD shows, so the view layer never
     * reaches into the engine across a thread boundary.
     */
    public volatile String prompt = "";

    /** Hours since midnight, for the clock readout. */
    public volatile double timeOfDay;

    // --- jobs and progression ---------------------------------------------
    /** Rupees in hand. */
    public volatile int money;
    /** What to do next, or a hint when idle. */
    public volatile String objective = "";
    /**
     * The standing contract, e.g. "Fort taxi stand: 2 more fares".
     *
     * <p>Separate from {@link #objective}, which is the current leg of
     * the job in hand. One says where to drive right now, the other
     * says why.
     */
    public volatile String contract = "";
    /** Transient congratulation or failure message. */
    public volatile String banner = "";
    /** Seconds left on a timed job, or 0 when untimed or idle. */
    public volatile double jobTimeLeft;
    /** Metres to the current stop, or -1 when there is no job. */
    public volatile double jobDistance = -1;
    public volatile int jobsDone;

    // --- the law -----------------------------------------------------------
    /** Whole stars, 0 to 5. */
    public volatile int wantedStars;
    /** Police units currently hunting you. */
    public volatile int policeUnits;

    /** Trains, metros and vessels currently running. */
    public volatile int services;

    /** Rain falling now, 0 to 1, and how wet the ground is. */
    public volatile double rain;
    public volatile double wetness;
}
