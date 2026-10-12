package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.core.Mth;

/**
 * Analytic water surface shared by the renderer and the boat.
 *
 * <p>The same sum-of-sines evaluated here is evaluated in the water vertex shader, so the
 * hull floats on the waves you can see. Open sea, harbour, creek and lake use different
 * amplitudes: the Arabian Sea has long swell, Thane Creek is short and choppy, lakes are
 * nearly flat.
 */
public final class WaterModel {

    public static final int CLASS_SEA = 0;
    public static final int CLASS_HARBOUR = 1;
    public static final int CLASS_CREEK = 2;
    public static final int CLASS_LAKE = 3;
    public static final int CLASS_RIVER = 4;

    /**
     * Inland water convention shared with the world compiler: for a lake or a river the
     * packaged height grid stores the <em>bed</em>, and the surface is the bed plus this
     * depth. Powai lake sits 50 m above sea level and the park lakes several hundred
     * metres higher, so inland water cannot use the tidal sea level.
     */
    public static final float LAKE_DEPTH = 5.0f;
    public static final float RIVER_DEPTH = 2.5f;

    /** Wave trains: amplitude (m), wavelength (m), direction angle (rad), speed (m/s). */
    private static final float[][] SEA = {
            {0.75f, 62f, 0.45f, 7.2f},
            {0.42f, 31f, 0.95f, 5.1f},
            {0.20f, 14f, -0.35f, 3.4f},
            {0.09f, 6.5f, 1.8f, 2.1f},
    };
    private static final float[][] HARBOUR = {
            {0.30f, 26f, 0.6f, 4.0f},
            {0.16f, 11f, -0.2f, 2.6f},
            {0.07f, 4.5f, 1.3f, 1.6f},
    };
    private static final float[][] CREEK = {
            {0.16f, 12f, 0.2f, 2.4f},
            {0.09f, 5f, 1.1f, 1.5f},
    };
    private static final float[][] LAKE = {
            {0.05f, 7f, 0.8f, 1.1f},
    };

    private float time;
    private float tide;
    private float windScale = 1f;

    public void update(float dt, float tideMetres, float windSpeed) {
        time += dt;
        this.tide = tideMetres;
        this.windScale = Mth.clamp(0.45f + windSpeed / 14f, 0.35f, 2.2f);
    }

    public float time() {
        return time;
    }

    public float tide() {
        return tide;
    }

    public static int classOf(int landUse) {
        switch (landUse) {
            case LandUse.DEEP_SEA:
            case LandUse.SHALLOW_SEA:
                return CLASS_SEA;
            case LandUse.HARBOUR:
                return CLASS_HARBOUR;
            case LandUse.CREEK:
                return CLASS_CREEK;
            case LandUse.RIVER:
                return CLASS_RIVER;
            default:
                return CLASS_LAKE;
        }
    }

    private static float[][] trainsFor(int waterClass) {
        switch (waterClass) {
            case CLASS_SEA: return SEA;
            case CLASS_HARBOUR: return HARBOUR;
            case CLASS_CREEK: return CREEK;
            default: return LAKE;
        }
    }

    /** True when this water class rises and falls with the tide. */
    public static boolean isTidal(int waterClass) {
        return waterClass == CLASS_SEA || waterClass == CLASS_HARBOUR
                || waterClass == CLASS_CREEK;
    }

    /** Water depth above the stored bed for inland water. */
    public static float inlandDepth(int waterClass) {
        return waterClass == CLASS_RIVER ? RIVER_DEPTH : LAKE_DEPTH;
    }

    /**
     * Mean water level for a class.
     *
     * @param bedHeight packaged terrain height at the sample, used for inland water
     */
    public float level(int waterClass, float bedHeight) {
        return isTidal(waterClass) ? tide : bedHeight + inlandDepth(waterClass);
    }

    /** Mean tidal level; only valid for sea, harbour and creek. */
    public float tidalLevel() {
        return tide;
    }

    /** Wave displacement above the mean level at a world position. */
    public float waveHeight(double x, double z, int waterClass) {
        float[][] trains = trainsFor(waterClass);
        float sum = 0f;
        for (int i = 0; i < trains.length; i++) {
            float amp = trains[i][0] * windScale;
            float k = (float) (2.0 * Math.PI / trains[i][1]);
            float dirX = (float) Math.cos(trains[i][2]);
            float dirZ = (float) Math.sin(trains[i][2]);
            float phase = (float) (k * (x * dirX + z * dirZ) - trains[i][3] * k * time);
            sum += amp * (float) Math.sin(phase);
        }
        return sum;
    }

    /** Absolute water surface height (mean level + waves). */
    public float surfaceHeight(double x, double z, int waterClass, float bedHeight) {
        return level(waterClass, bedHeight) + waveHeight(x, z, waterClass);
    }

    /** Surface normal from analytic derivatives, used for buoyancy torque and shading. */
    public void surfaceNormal(double x, double z, int waterClass, float[] out) {
        float d = 1.5f;
        float hl = waveHeight(x - d, z, waterClass);
        float hr = waveHeight(x + d, z, waterClass);
        float hd = waveHeight(x, z - d, waterClass);
        float hu = waveHeight(x, z + d, waterClass);
        float nx = hl - hr;
        float nz = hd - hu;
        float ny = 2f * d;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        out[0] = nx / len;
        out[1] = ny / len;
        out[2] = nz / len;
    }

    /** Tidal current (m/s) - ebb and flood through the creeks is strong in Mumbai. */
    public void currentAt(double x, double z, int waterClass, float[] out) {
        float phase = (float) Math.cos(time * 0.00012f + 0.7);
        float strength = waterClass == CLASS_CREEK ? 1.25f
                : (waterClass == CLASS_HARBOUR ? 0.75f : 0.35f);
        float dir = Mth.fbm((float) x * 0.00008f, (float) z * 0.00008f, 2, 2f, 0.5f, 909)
                * Mth.TAU;
        out[0] = (float) Math.cos(dir) * strength * phase;
        out[1] = 0f;
        out[2] = (float) Math.sin(dir) * strength * phase;
    }
}
