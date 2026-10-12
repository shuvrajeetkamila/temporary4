package ai.shuvrajeetkamila.mumbai.core;

/**
 * Small, allocation free math helpers shared by the simulation, the world compiler
 * readers and the renderer. Everything here is pure and unit testable on the JVM.
 */
public final class Mth {

    public static final double EPS = 1e-9;
    public static final float PI = (float) Math.PI;
    public static final float TAU = (float) (Math.PI * 2.0);
    public static final float DEG2RAD = (float) (Math.PI / 180.0);
    public static final float RAD2DEG = (float) (180.0 / Math.PI);

    private Mth() {
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static float invLerp(float a, float b, float v) {
        if (Math.abs(b - a) < 1e-12f) {
            return 0f;
        }
        return (v - a) / (b - a);
    }

    public static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01(invLerp(edge0, edge1, x));
        return t * t * (3f - 2f * t);
    }

    /** Frame rate independent exponential smoothing factor. */
    public static float damp(float rateHz, float dt) {
        return 1f - (float) Math.exp(-rateHz * dt);
    }

    public static float approach(float current, float target, float maxDelta) {
        float d = target - current;
        if (d > maxDelta) {
            d = maxDelta;
        }
        if (d < -maxDelta) {
            d = -maxDelta;
        }
        return current + d;
    }

    /** Wrap an angle into [-PI, PI]. */
    public static float wrapPi(float a) {
        while (a > PI) {
            a -= TAU;
        }
        while (a < -PI) {
            a += TAU;
        }
        return a;
    }

    /** Wrap a compass bearing into [0, 360). */
    public static float wrap360(float deg) {
        deg = deg % 360f;
        if (deg < 0f) {
            deg += 360f;
        }
        return deg;
    }

    public static float sign(float v) {
        return v > 0f ? 1f : (v < 0f ? -1f : 0f);
    }

    /** Signed value with a dead zone, rescaled so the output stays continuous. */
    public static float deadZone(float v, float dz) {
        float a = Math.abs(v);
        if (a <= dz) {
            return 0f;
        }
        return sign(v) * (a - dz) / (1f - dz);
    }

    public static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    public static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /** Deterministic 32 bit integer hash (used by every procedural generator). */
    public static int hash(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    public static int hash2(int x, int y) {
        return hash(x * 0x27d4eb2d ^ (y + 0x165667b1));
    }

    public static int hash3(int x, int y, int z) {
        return hash(hash2(x, y) ^ (z * 0x9e3779b1));
    }

    /** Deterministic float in [0,1) from a 2D cell. */
    public static float rand01(int x, int y) {
        return (hash2(x, y) >>> 8) / 16777216f;
    }

    public static float rand01(int x, int y, int z) {
        return (hash3(x, y, z) >>> 8) / 16777216f;
    }

    /** Deterministic float in [lo,hi) from a 3D cell. */
    public static float randRange(int x, int y, int z, float lo, float hi) {
        return lo + rand01(x, y, z) * (hi - lo);
    }

    /** Classic 2D value noise with smooth interpolation; deterministic for a seed. */
    public static float valueNoise(float x, float y, int seed) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        float xf = x - xi;
        float yf = y - yi;
        float u = xf * xf * (3f - 2f * xf);
        float v = yf * yf * (3f - 2f * yf);
        float a = rand01(xi, yi, seed);
        float b = rand01(xi + 1, yi, seed);
        float c = rand01(xi, yi + 1, seed);
        float d = rand01(xi + 1, yi + 1, seed);
        return lerp(lerp(a, b, u), lerp(c, d, u), v);
    }

    /** Fractal value noise, returns roughly [0,1]. */
    public static float fbm(float x, float y, int octaves, float lacunarity, float gain, int seed) {
        float amp = 0.5f;
        float freq = 1f;
        float sum = 0f;
        float norm = 0f;
        for (int i = 0; i < octaves; i++) {
            sum += amp * valueNoise(x * freq, y * freq, seed + i * 1013);
            norm += amp;
            amp *= gain;
            freq *= lacunarity;
        }
        return norm > 0f ? sum / norm : 0f;
    }

    /** Distance from point p to segment ab, in the XZ plane. */
    public static double distancePointSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double len2 = dx * dx + dz * dz;
        if (len2 < EPS) {
            double ex = px - ax;
            double ez = pz - az;
            return Math.sqrt(ex * ex + ez * ez);
        }
        double t = ((px - ax) * dx + (pz - az) * dz) / len2;
        t = clamp(t, 0.0, 1.0);
        double cx = ax + t * dx;
        double cz = az + t * dz;
        double ex = px - cx;
        double ez = pz - cz;
        return Math.sqrt(ex * ex + ez * ez);
    }
}
