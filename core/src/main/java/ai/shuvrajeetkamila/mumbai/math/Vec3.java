package ai.shuvrajeetkamila.mumbai.math;

/**
 * A mutable double-precision 3-vector.
 *
 * <p>Mutable on purpose. The fixed-step integrator runs sixty times a second
 * over a few hundred bodies and the five vehicle models each accumulate a dozen
 * force terms per step; allocating an immutable vector for every intermediate
 * would hand the garbage collector a steady megabyte a second and show up as
 * frame stutter on exactly the mid-range phones this is meant to run on. The
 * convention is that methods ending in {@code Self} mutate and return
 * {@code this}, and everything else returns a fresh vector.
 *
 * <p>World frame: x east, y up, z south.
 */
public final class Vec3 {
    public double x, y, z;

    public Vec3() { }

    public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }

    public Vec3(Vec3 o) { this(o.x, o.y, o.z); }

    public static Vec3 of(double x, double y, double z) { return new Vec3(x, y, z); }

    public Vec3 set(double nx, double ny, double nz) { x = nx; y = ny; z = nz; return this; }

    public Vec3 set(Vec3 o) { return set(o.x, o.y, o.z); }

    public Vec3 zero() { return set(0, 0, 0); }

    public Vec3 addSelf(Vec3 o) { x += o.x; y += o.y; z += o.z; return this; }

    public Vec3 addSelf(double ax, double ay, double az) {
        x += ax; y += ay; z += az; return this;
    }

    public Vec3 addScaledSelf(Vec3 o, double s) {
        x += o.x * s; y += o.y * s; z += o.z * s; return this;
    }

    public Vec3 subSelf(Vec3 o) { x -= o.x; y -= o.y; z -= o.z; return this; }

    public Vec3 scaleSelf(double s) { x *= s; y *= s; z *= s; return this; }

    public Vec3 add(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }

    public Vec3 sub(Vec3 o) { return new Vec3(x - o.x, y - o.y, z - o.z); }

    public Vec3 scale(double s) { return new Vec3(x * s, y * s, z * s); }

    public double dot(Vec3 o) { return x * o.x + y * o.y + z * o.z; }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public Vec3 crossInto(Vec3 o, Vec3 out) {
        double cx = y * o.z - z * o.y;
        double cy = z * o.x - x * o.z;
        double cz = x * o.y - y * o.x;
        return out.set(cx, cy, cz);
    }

    public double length() { return Math.sqrt(x * x + y * y + z * z); }

    public double lengthSq() { return x * x + y * y + z * z; }

    /** Horizontal speed, ignoring the vertical component. */
    public double planarLength() { return Math.sqrt(x * x + z * z); }

    public double distanceTo(Vec3 o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public Vec3 normalizeSelf() {
        double l = length();
        if (l > 1e-12) { x /= l; y /= l; z /= l; }
        return this;
    }

    public Vec3 normalized() { return new Vec3(this).normalizeSelf(); }

    /** Clamp the magnitude, leaving the direction alone. */
    public Vec3 clampLengthSelf(double max) {
        double l = length();
        if (l > max && l > 1e-12) { scaleSelf(max / l); }
        return this;
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    @Override public String toString() {
        return String.format("(%.3f, %.3f, %.3f)", x, y, z);
    }
}
