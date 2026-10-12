package ai.shuvrajeetkamila.mumbai.game;

/**
 * Graphics / simulation quality tiers.
 *
 * <p>Frame pacing is deliberately capped. An uncapped 60-120 FPS loop on a 1:1 scale open
 * world cooks a phone within minutes, so BALANCED targets 30 FPS, ECO 20 FPS and
 * ECO_SEVERE 15 FPS, and each tier also reduces real work (streaming rings, agent caps,
 * shadow sampling, particle budget, render scale) rather than only sleeping longer.
 */
public enum QualityProfile {

    /** Weak / old devices: short rings, no dynamic shadows, few agents. */
    LOW(30, 300f, 1200f, 9000f, false, 0.75f, 40, 30, 180, 1),

    /** Default tier. */
    BALANCED(30, 520f, 2200f, 14000f, true, 0.9f, 90, 70, 320, 1),

    /** Headroom tier for flagships (still thermally overridable). */
    HIGH(30, 800f, 3200f, 22000f, true, 1.0f, 140, 120, 520, 2),

    /** Moderate thermal pressure or battery saver. */
    ECO(20, 280f, 1100f, 8000f, false, 0.7f, 35, 20, 120, 1),

    /** Severe thermal pressure: the game stays playable, the phone stops cooking. */
    ECO_SEVERE(15, 220f, 800f, 6000f, false, 0.6f, 18, 10, 60, 1);

    /** Target frames per second for the pacing loop. */
    public final int targetFps;
    /** Radius (m) of the full detail + collision ring around the focus. */
    public final float highRing;
    /** Radius (m) of the medium detail city ring. */
    public final float midRing;
    /** Radius (m) of the coarse skyline / terrain ring. */
    public final float farRing;
    /** Whether the directional shadow pass runs at all. */
    public final boolean shadows;
    /** Render target scale (dynamic resolution). */
    public final float renderScale;
    /** Maximum number of microscopic traffic agents. */
    public final int trafficCap;
    /** Maximum number of pedestrian agents. */
    public final int pedestrianCap;
    /** Maximum live particles. */
    public final int particleCap;
    /** Vegetation / prop instancing density step. */
    public final int propDensity;

    QualityProfile(int targetFps, float highRing, float midRing, float farRing, boolean shadows,
                   float renderScale, int trafficCap, int pedestrianCap, int particleCap, int propDensity) {
        this.targetFps = targetFps;
        this.highRing = highRing;
        this.midRing = midRing;
        this.farRing = farRing;
        this.shadows = shadows;
        this.renderScale = renderScale;
        this.trafficCap = trafficCap;
        this.pedestrianCap = pedestrianCap;
        this.particleCap = particleCap;
        this.propDensity = propDensity;
    }

    public long frameNanos() {
        return 1_000_000_000L / targetFps;
    }

    public boolean isEco() {
        return this == ECO || this == ECO_SEVERE;
    }

    /**
     * Map an Android {@code PowerManager} thermal status onto a forced profile.
     *
     * @param thermalStatus 0=NONE .. 6=SHUTDOWN
     * @param preferred     the user selected profile
     * @return the profile that must actually be used
     */
    public static QualityProfile applyThermal(QualityProfile preferred, int thermalStatus) {
        if (thermalStatus >= 4) {          // SEVERE, CRITICAL, EMERGENCY, SHUTDOWN
            return ECO_SEVERE;
        }
        if (thermalStatus >= 2) {          // MODERATE / LIGHT+ pressure
            return ECO;
        }
        return preferred;
    }

    public String displayName() {
        switch (this) {
            case LOW: return "LOW";
            case BALANCED: return "BALANCED";
            case HIGH: return "HIGH";
            case ECO: return "ECO 20";
            case ECO_SEVERE: return "ECO 15";
            default: return name();
        }
    }
}
