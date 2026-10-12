package ai.shuvrajeetkamila.mumbai.math;

/** Small numeric helpers shared across the simulation. */
public final class MathUtil {
    private MathUtil() { }

    public static final double G = 9.80665;
    /** Sea-level air density at 15 C, kg/m^3. */
    public static final double RHO_AIR_SL = 1.225;
    /** Seawater density, kg/m^3. */
    public static final double RHO_SEA = 1025.0;

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static int clampi(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** Linear map from one range to another, clamped at both ends. */
    public static double remap(double v, double a0, double a1, double b0, double b1) {
        if (Math.abs(a1 - a0) < 1e-12) { return b0; }
        return clamp(b0 + (v - a0) * (b1 - b0) / (a1 - a0),
                Math.min(b0, b1), Math.max(b0, b1));
    }

    /**
     * Frame-rate independent exponential approach. {@code rate} is the
     * reciprocal of the time constant, so the result is identical whether the
     * caller steps at 60 Hz or 20 Hz.
     */
    public static double approach(double current, double target, double rate, double dt) {
        return target + (current - target) * Math.exp(-rate * dt);
    }

    /** Move towards a target at no more than {@code maxDelta}. */
    public static double moveTowards(double current, double target, double maxDelta) {
        double d = target - current;
        if (Math.abs(d) <= maxDelta) { return target; }
        return current + Math.signum(d) * maxDelta;
    }

    /** Wrap an angle to (-pi, pi]. */
    public static double wrapPi(double a) {
        a = (a + Math.PI) % (2 * Math.PI);
        if (a < 0) { a += 2 * Math.PI; }
        return a - Math.PI;
    }

    /** Smooth Hermite step between two edges. */
    public static double smoothstep(double e0, double e1, double v) {
        double t = clamp((v - e0) / (e1 - e0 + 1e-12), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /**
     * International Standard Atmosphere density at a geometric altitude,
     * troposphere only, which covers everything this game can fly to.
     */
    public static double airDensity(double altitudeM) {
        double h = clamp(altitudeM, -500, 11000);
        double t = 288.15 - 0.0065 * h;
        double p = 101325.0 * Math.pow(t / 288.15, 5.2558797);
        return p / (287.058 * t);
    }

    /** Deadzone with a smooth re-expansion so the stick still reaches +-1. */
    public static double deadzone(double v, double dz) {
        double a = Math.abs(v);
        if (a <= dz) { return 0; }
        return Math.signum(v) * (a - dz) / (1 - dz);
    }

    public static boolean finite(double... vs) {
        for (double v : vs) { if (!Double.isFinite(v)) { return false; } }
        return true;
    }
}
