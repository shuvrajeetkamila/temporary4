package ai.shuvrajeetkamila.mumbai.math;

/**
 * Unit quaternion orientation, {@code w + xi + yj + zk}.
 *
 * <p>Body frame: +x right, +y up, +z forward. So a torque about +x pitches the
 * nose down, about +y yaws right, and about +z rolls left. Those signs are
 * load-bearing — the aeroplane and helicopter models are written against them
 * and flipping one silently inverts a control axis.
 *
 * <p>Rotations are stored as quaternions rather than Euler angles so that a
 * helicopter can be inverted during autorotation practice, or a boat rolled
 * past ninety degrees by a wave, without gimbal lock quietly destroying the
 * orientation.
 */
public final class Quat {
    public double w = 1, x, y, z;

    public Quat() { }

    public Quat(double w, double x, double y, double z) {
        this.w = w; this.x = x; this.y = y; this.z = z;
    }

    public Quat(Quat o) { this(o.w, o.x, o.y, o.z); }

    public Quat set(double nw, double nx, double ny, double nz) {
        w = nw; x = nx; y = ny; z = nz; return this;
    }

    public Quat set(Quat o) { return set(o.w, o.x, o.y, o.z); }

    public Quat identity() { return set(1, 0, 0, 0); }

    public static Quat axisAngle(double ax, double ay, double az, double angle) {
        double l = Math.sqrt(ax * ax + ay * ay + az * az);
        if (l < 1e-12) { return new Quat(); }
        double h = angle * 0.5, s = Math.sin(h) / l;
        return new Quat(Math.cos(h), ax * s, ay * s, az * s);
    }

    /** Heading about world up, in radians clockwise from north. */
    public static Quat yaw(double headingRad) {
        return axisAngle(0, 1, 0, headingRad);
    }

    public Quat normalizeSelf() {
        double l = Math.sqrt(w * w + x * x + y * y + z * z);
        if (l > 1e-12) { w /= l; x /= l; y /= l; z /= l; } else { identity(); }
        return this;
    }

    public Quat mulSelf(Quat o) {
        double nw = w * o.w - x * o.x - y * o.y - z * o.z;
        double nx = w * o.x + x * o.w + y * o.z - z * o.y;
        double ny = w * o.y - x * o.z + y * o.w + z * o.x;
        double nz = w * o.z + x * o.y - y * o.x + z * o.w;
        return set(nw, nx, ny, nz);
    }

    public Quat mul(Quat o) { return new Quat(this).mulSelf(o); }

    /**
     * Integrate an angular velocity, expressed in the world frame, over dt.
     * Exact for a constant rate rather than the usual first-order approximation,
     * which drifts noticeably at helicopter rotor-induced yaw rates.
     */
    public Quat integrateSelf(Vec3 omegaWorld, double dt) {
        double len = omegaWorld.length();
        if (len < 1e-9) { return this; }
        Quat dq = axisAngle(omegaWorld.x, omegaWorld.y, omegaWorld.z, len * dt);
        double nw = dq.w * w - dq.x * x - dq.y * y - dq.z * z;
        double nx = dq.w * x + dq.x * w + dq.y * z - dq.z * y;
        double ny = dq.w * y - dq.x * z + dq.y * w + dq.z * x;
        double nz = dq.w * z + dq.x * y - dq.y * x + dq.z * w;
        return set(nw, nx, ny, nz).normalizeSelf();
    }

    /** Rotate a body-frame vector into the world frame. */
    public Vec3 rotate(Vec3 v, Vec3 out) {
        double tx = 2 * (y * v.z - z * v.y);
        double ty = 2 * (z * v.x - x * v.z);
        double tz = 2 * (x * v.y - y * v.x);
        return out.set(v.x + w * tx + (y * tz - z * ty),
                       v.y + w * ty + (z * tx - x * tz),
                       v.z + w * tz + (x * ty - y * tx));
    }

    public Vec3 rotate(Vec3 v) { return rotate(v, new Vec3()); }

    /** Rotate a world-frame vector into the body frame. */
    public Vec3 inverseRotate(Vec3 v, Vec3 out) {
        double tx = 2 * (-y * v.z + z * v.y);
        double ty = 2 * (-z * v.x + x * v.z);
        double tz = 2 * (-x * v.y + y * v.x);
        return out.set(v.x + w * tx + (-y * tz + z * ty),
                       v.y + w * ty + (-z * tx + x * tz),
                       v.z + w * tz + (-x * ty + y * tx));
    }

    public Vec3 inverseRotate(Vec3 v) { return inverseRotate(v, new Vec3()); }

    public Vec3 right(Vec3 out) { return rotate(AXIS_X, out); }

    public Vec3 up(Vec3 out) { return rotate(AXIS_Y, out); }

    public Vec3 forward(Vec3 out) { return rotate(AXIS_Z, out); }

    private static final Vec3 AXIS_X = new Vec3(1, 0, 0);
    private static final Vec3 AXIS_Y = new Vec3(0, 1, 0);
    private static final Vec3 AXIS_Z = new Vec3(0, 0, 1);

    /** Compass heading in radians, clockwise from north. */
    public double heading() {
        Vec3 f = forward(new Vec3());
        return Math.atan2(f.x, -f.z);
    }

    /** Pitch in radians; positive is nose up. */
    public double pitch() {
        Vec3 f = forward(new Vec3());
        return Math.asin(Math.max(-1, Math.min(1, f.y)));
    }

    /**
     * Roll in radians; positive is right wing down.
     *
     * <p>{@link #right()} returns body +X. In this world frame - X east,
     * Y up, Z south, forward +Z - the physical right of something facing
     * +Z is west, which is -X. So body +X is the machine's LEFT side, and
     * the right wing being down means body +X is pointing UP. This
     * reported the opposite of its own documentation for a long time; it
     * is only read by tests and the artificial horizon, so nothing
     * downstream was compensating for it.
     */
    public double roll() {
        Vec3 r = right(new Vec3());
        return Math.atan2(r.y, Math.sqrt(Math.max(1e-12, 1 - r.y * r.y)) + 1e-12);
    }

    /**
     * Shortest-arc interpolation, used only for render smoothing between the
     * two most recent physics states. Taking the long way round shows up as a
     * vehicle spinning through a whole revolution in one frame.
     */
    public static Quat slerpShortest(Quat a, Quat b, double t, Quat out) {
        double d = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z;
        double bw = b.w, bx = b.x, by = b.y, bz = b.z;
        if (d < 0) { d = -d; bw = -bw; bx = -bx; by = -by; bz = -bz; }
        double s0, s1;
        if (d > 0.9995) {
            s0 = 1 - t; s1 = t;
        } else {
            double theta = Math.acos(Math.max(-1, Math.min(1, d)));
            double sin = Math.sin(theta);
            s0 = Math.sin((1 - t) * theta) / sin;
            s1 = Math.sin(t * theta) / sin;
        }
        return out.set(s0 * a.w + s1 * bw, s0 * a.x + s1 * bx,
                       s0 * a.y + s1 * by, s0 * a.z + s1 * bz).normalizeSelf();
    }

    public boolean isFinite() {
        return Double.isFinite(w) && Double.isFinite(x)
                && Double.isFinite(y) && Double.isFinite(z);
    }

    @Override public String toString() {
        return String.format("[%.4f, (%.4f, %.4f, %.4f)]", w, x, y, z);
    }
}
