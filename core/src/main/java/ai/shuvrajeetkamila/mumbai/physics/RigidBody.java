package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.core.Mth;

/**
 * Six degree of freedom rigid body in SI units (metres, kilograms, seconds, newtons).
 *
 * <p>Master position is {@code double} so a 65 km world keeps millimetre precision; the
 * floating origin only affects what is handed to the renderer. Orientation is a quaternion
 * with a cached body->world rotation matrix. Angular dynamics use a diagonal body-frame
 * inertia tensor rotated into world space each step.
 *
 * <p>Vehicles never set transforms directly: they accumulate forces and torques and this
 * integrator advances the state at a fixed 60 Hz timestep.
 */
public final class RigidBody {

    public static final float GRAVITY = 9.80665f;

    // --- state -------------------------------------------------------------------
    public double px, py, pz;
    public float vx, vy, vz;
    public float qx, qy, qz, qw = 1f;
    public float wx, wy, wz;              // angular velocity, world frame (rad/s)

    // --- properties --------------------------------------------------------------
    public float mass = 1200f;
    public float invMass = 1f / 1200f;
    public float ixx = 1000f, iyy = 1500f, izz = 1000f;

    // --- accumulators ------------------------------------------------------------
    public float fx, fy, fz;
    public float tx, ty, tz;

    /** Cached rotation matrix, column major body axes in world space. */
    public final float[] rot = new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};

    private final float[] tmp = new float[3];

    public void setMass(float m) {
        mass = Math.max(0.001f, m);
        invMass = 1f / mass;
    }

    /** Box inertia approximation for a body of given extents (metres). */
    public void setBoxInertia(float lengthX, float heightY, float lengthZ, float scale) {
        float m = mass * scale;
        ixx = m * (heightY * heightY + lengthZ * lengthZ) / 12f;
        iyy = m * (lengthX * lengthX + lengthZ * lengthZ) / 12f;
        izz = m * (lengthX * lengthX + heightY * heightY) / 12f;
    }

    public void setOrientationYawPitchRoll(float yaw, float pitch, float roll) {
        float cy = (float) Math.cos(yaw * 0.5);
        float sy = (float) Math.sin(yaw * 0.5);
        float cp = (float) Math.cos(pitch * 0.5);
        float sp = (float) Math.sin(pitch * 0.5);
        float cr = (float) Math.cos(roll * 0.5);
        float sr = (float) Math.sin(roll * 0.5);
        qw = cy * cp * cr + sy * sp * sr;
        qx = cy * sp * cr + sy * cp * sr;
        qy = sy * cp * cr - cy * sp * sr;
        qz = cy * cp * sr - sy * sp * cr;
        normalise();
        updateMatrix();
    }

    public float yaw() {
        float siny = 2f * (qw * qy + qz * qx);
        float cosy = 1f - 2f * (qx * qx + qy * qy);
        return (float) Math.atan2(siny, cosy);
    }

    public float pitch() {
        float s = 2f * (qw * qx - qy * qz);
        s = Mth.clamp(s, -1f, 1f);
        return (float) Math.asin(s);
    }

    public float roll() {
        float sinr = 2f * (qw * qz + qx * qy);
        float cosr = 1f - 2f * (qz * qz + qx * qx);
        return (float) Math.atan2(sinr, cosr);
    }

    public void normalise() {
        float n = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (n < 1e-8f) {
            qx = 0;
            qy = 0;
            qz = 0;
            qw = 1;
            return;
        }
        qx /= n;
        qy /= n;
        qz /= n;
        qw /= n;
    }

    /** Recomputes the cached rotation matrix from the quaternion. */
    public void updateMatrix() {
        float x = qx, y = qy, z = qz, w = qw;
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        float wx_ = w * x, wy_ = w * y, wz_ = w * z;
        rot[0] = 1 - 2 * (yy + zz);
        rot[1] = 2 * (xy + wz_);
        rot[2] = 2 * (xz - wy_);
        rot[3] = 2 * (xy - wz_);
        rot[4] = 1 - 2 * (xx + zz);
        rot[5] = 2 * (yz + wx_);
        rot[6] = 2 * (xz + wy_);
        rot[7] = 2 * (yz - wx_);
        rot[8] = 1 - 2 * (xx + yy);
    }

    /** Body axis directions in world space. */
    public float rightX() {
        return rot[0];
    }

    public float rightY() {
        return rot[1];
    }

    public float rightZ() {
        return rot[2];
    }

    public float upX() {
        return rot[3];
    }

    public float upY() {
        return rot[4];
    }

    public float upZ() {
        return rot[5];
    }

    public float forwardX() {
        return rot[6];
    }

    public float forwardY() {
        return rot[7];
    }

    public float forwardZ() {
        return rot[8];
    }

    public void localToWorld(float lx, float ly, float lz, float[] out) {
        out[0] = rot[0] * lx + rot[3] * ly + rot[6] * lz;
        out[1] = rot[1] * lx + rot[4] * ly + rot[7] * lz;
        out[2] = rot[2] * lx + rot[5] * ly + rot[8] * lz;
    }

    public void worldToLocal(float wxv, float wyv, float wzv, float[] out) {
        out[0] = rot[0] * wxv + rot[1] * wyv + rot[2] * wzv;
        out[1] = rot[3] * wxv + rot[4] * wyv + rot[5] * wzv;
        out[2] = rot[6] * wxv + rot[7] * wyv + rot[8] * wzv;
    }

    public void clearAccumulators() {
        fx = 0;
        fy = 0;
        fz = 0;
        tx = 0;
        ty = 0;
        tz = 0;
    }

    public void addForce(float x, float y, float z) {
        fx += x;
        fy += y;
        fz += z;
    }

    public void addTorque(float x, float y, float z) {
        tx += x;
        ty += y;
        tz += z;
    }

    /** World-space force applied at a body-local offset. */
    public void addForceAtLocalPoint(float wfx, float wfy, float wfz,
                                     float lx, float ly, float lz) {
        localToWorld(lx, ly, lz, tmp);
        float rx = tmp[0], ry = tmp[1], rz = tmp[2];
        fx += wfx;
        fy += wfy;
        fz += wfz;
        tx += ry * wfz - rz * wfy;
        ty += rz * wfx - rx * wfz;
        tz += rx * wfy - ry * wfx;
    }

    /** Body-space force applied at a body-local offset. */
    public void addLocalForceAtLocalPoint(float lfx, float lfy, float lfz,
                                          float lx, float ly, float lz) {
        localToWorld(lfx, lfy, lfz, tmp);
        addForceAtLocalPoint(tmp[0], tmp[1], tmp[2], lx, ly, lz);
    }

    /** World velocity of a body-local point (includes rotation). */
    public void velocityAtLocalPoint(float lx, float ly, float lz, float[] out) {
        localToWorld(lx, ly, lz, tmp);
        float rx = tmp[0], ry = tmp[1], rz = tmp[2];
        out[0] = vx + (wy * rz - wz * ry);
        out[1] = vy + (wz * rx - wx * rz);
        out[2] = vz + (wx * ry - wy * rx);
    }

    /** Semi-implicit Euler integration of the accumulated forces. */
    public void integrate(float dt) {
        if (!finiteState()) {
            recoverFromNaN();
            return;
        }
        vx += fx * invMass * dt;
        vy += fy * invMass * dt;
        vz += fz * invMass * dt;

        // Angular: convert world torque into the body frame, divide by inertia, rotate back.
        worldToLocal(tx, ty, tz, tmp);
        float ax = tmp[0] / Math.max(1e-4f, ixx);
        float ay = tmp[1] / Math.max(1e-4f, iyy);
        float az = tmp[2] / Math.max(1e-4f, izz);
        localToWorld(ax, ay, az, tmp);
        wx += tmp[0] * dt;
        wy += tmp[1] * dt;
        wz += tmp[2] * dt;

        px += vx * dt;
        py += vy * dt;
        pz += vz * dt;

        // Quaternion integration: dq = 0.5 * omega * q
        float hx = wx * 0.5f * dt;
        float hy = wy * 0.5f * dt;
        float hz = wz * 0.5f * dt;
        float nqw = qw - hx * qx - hy * qy - hz * qz;
        float nqx = qx + hx * qw + hy * qz - hz * qy;
        float nqy = qy - hx * qz + hy * qw + hz * qx;
        float nqz = qz + hx * qy - hy * qx + hz * qw;
        qw = nqw;
        qx = nqx;
        qy = nqy;
        qz = nqz;
        normalise();
        updateMatrix();
        clearAccumulators();
        if (!finiteState()) {
            recoverFromNaN();
        }
    }

    public float speed() {
        return (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    /** Signed forward speed along the body +Z axis (m/s). */
    public float forwardSpeed() {
        return vx * forwardX() + vy * forwardY() + vz * forwardZ();
    }

    public boolean finiteState() {
        return Mth.isFinite(px) && Mth.isFinite(py) && Mth.isFinite(pz)
                && Mth.isFinite(vx) && Mth.isFinite(vy) && Mth.isFinite(vz)
                && Mth.isFinite(qx) && Mth.isFinite(qy) && Mth.isFinite(qz) && Mth.isFinite(qw)
                && Mth.isFinite(wx) && Mth.isFinite(wy) && Mth.isFinite(wz);
    }

    /** Never silently ignore NaN: zero the dynamics and keep the last good position. */
    public void recoverFromNaN() {
        if (!Mth.isFinite(px)) {
            px = 0;
        }
        if (!Mth.isFinite(py)) {
            py = 50;
        }
        if (!Mth.isFinite(pz)) {
            pz = 0;
        }
        vx = 0;
        vy = 0;
        vz = 0;
        wx = 0;
        wy = 0;
        wz = 0;
        qx = 0;
        qy = 0;
        qz = 0;
        qw = 1;
        normalise();
        updateMatrix();
        clearAccumulators();
    }

    /** Clamp angular rates so a bad contact cannot explode the solver. */
    public void limitAngularRate(float maxRadPerSec) {
        float m = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (m > maxRadPerSec && m > 1e-5f) {
            float s = maxRadPerSec / m;
            wx *= s;
            wy *= s;
            wz *= s;
        }
    }

    public void limitSpeed(float maxMetresPerSec) {
        float m = speed();
        if (m > maxMetresPerSec && m > 1e-5f) {
            float s = maxMetresPerSec / m;
            vx *= s;
            vy *= s;
            vz *= s;
        }
    }

    /** Floating origin support: shift the master position without touching dynamics. */
    public void shiftOrigin(double dx, double dy, double dz) {
        px += dx;
        py += dy;
        pz += dz;
    }
}
