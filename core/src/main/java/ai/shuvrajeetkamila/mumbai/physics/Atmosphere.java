package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.core.Mth;

/**
 * Shared environment model: tropical coastal air, wind and sea state.
 * Used by the aeroplane, the helicopter, the boat, vegetation and the renderer, so a gust
 * that rocks the trees is the same gust that moves the aircraft.
 */
public final class Atmosphere {

    /** Sea level density for a humid 30 C Mumbai day (kg/m^3). */
    public static final float RHO_SEA_LEVEL = 1.164f;
    public static final float WATER_DENSITY = 1025.0f;   // sea water
    public static final float TEMP_SEA_LEVEL_C = 30.0f;

    /** Mean wind: the south-westerly sea breeze, in m/s. */
    public float windX = 2.6f;
    public float windZ = -1.4f;
    public float gustiness = 0.35f;
    public float rain;                 // 0..1 monsoon intensity
    public float timeOfDay = 8.0f;     // hours
    public float tide;                 // metres relative to mean sea level

    private float t;

    public void update(float dt) {
        t += dt;
        // Tide: simplified semi-diurnal, +-1.8 m which is representative of Mumbai harbour.
        tide = 1.8f * (float) Math.sin(t * 0.00012f + 0.7) + 0.25f
                * (float) Math.sin(t * 0.00031f);
        timeOfDay += dt / 180f;        // a full day every 30 real minutes
        if (timeOfDay >= 24f) {
            timeOfDay -= 24f;
        }
    }

    /** Air density at an altitude (ISA-like lapse, adequate below 5 km). */
    public float density(float altitudeM) {
        float h = Math.max(0f, altitudeM);
        return RHO_SEA_LEVEL * (float) Math.exp(-h / 8600.0);
    }

    /** Wind including gusts and a crude urban boundary layer. */
    public void windAt(double x, double y, double z, float[] out) {
        float heightFactor = Mth.clamp((float) y / 120f, 0.25f, 1.8f);
        // fbm returns 0..1, so the zero mean has to be taken before scaling by the
        // gust strength; scaling first leaves a constant headwind even in dead calm.
        float n = Mth.fbm((float) (x * 0.0012 + t * 0.07),
                (float) (z * 0.0012 - t * 0.05), 3, 2.0f, 0.5f, 404);
        float gust = (n - 0.5f) * 6.0f * gustiness;
        out[0] = (windX + gust) * heightFactor;
        out[1] = (n - 0.5f) * 1.2f * gustiness * heightFactor;
        out[2] = (windZ - gust * 0.6f) * heightFactor;
    }

    /** Building-wake turbulence factor near tall structures (0..1). */
    public float turbulence(float groundClearance, float buildingDensity) {
        float low = Mth.clamp(1f - groundClearance / 180f, 0f, 1f);
        return low * Mth.clamp(buildingDensity, 0f, 1f);
    }

    public boolean isNight() {
        return timeOfDay < 6.2f || timeOfDay > 18.8f;
    }

    /** Sun elevation in degrees, approximate for Mumbai's latitude. */
    public float sunElevationDeg() {
        float hourAngle = (timeOfDay - 12f) * 15f;
        return 70f * (float) Math.cos(Math.toRadians(hourAngle)) - 2f;
    }

    public float sunAzimuthDeg() {
        return 90f + (timeOfDay - 12f) * 15f;
    }
}
