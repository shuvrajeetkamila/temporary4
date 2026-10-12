package ai.shuvrajeetkamila.mumbai.game;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.physics.CollisionWorld;

/**
 * Chase / cockpit / free camera with spring smoothing, collision pull-in and a
 * speed-sensitive field of view. Position is kept in double precision because the camera
 * is what drives the floating origin and the streaming rings.
 */
public final class Camera {

    public static final int MODE_CHASE = 0;
    public static final int MODE_COCKPIT = 1;
    public static final int MODE_ORBIT = 2;
    public static final int MODE_FREE = 3;

    public int mode = MODE_CHASE;

    public double px, py, pz;
    public float yaw;
    public float pitch;
    public float fovDeg = 62f;
    public float near = 0.22f;
    public float far = 16000f;

    /** Desired offsets behind/above the target, in metres. */
    public float distance = 7.5f;
    public float heightOffset = 2.6f;
    public float lookAhead = 3.0f;

    public float orbitYaw;
    public float orbitPitch = -0.22f;

    private double targetX, targetY, targetZ;
    private float smoothedDistance;
    private float fovSmoothed = 62f;
    private double curX, curY, curZ;
    private boolean initialised;

    public void snapTo(double x, double y, double z, float yawRad) {
        px = x;
        py = y;
        pz = z;
        curX = x;
        curY = y;
        curZ = z;
        yaw = yawRad;
        orbitYaw = yawRad;
        initialised = true;
    }

    /**
     * @param tx     target position (metres)
     * @param speed  target speed (m/s), widens the field of view
     * @param follow heading of the target (radians)
     */
    public void update(float dt, double tx, double ty, double tz, float follow,
                       float speed, CollisionWorld cw, float baseFov) {
        targetX = tx;
        targetY = ty;
        targetZ = tz;
        if (!initialised) {
            snapTo(tx, ty + heightOffset, tz, follow);
        }

        switch (mode) {
            case MODE_COCKPIT:
                updateCockpit(dt, follow);
                break;
            case MODE_ORBIT:
                updateOrbit(dt);
                break;
            case MODE_FREE:
                updateFree(dt);
                break;
            default:
                updateChase(dt, follow, speed, cw);
                break;
        }

        float targetFov = baseFov + Mth.clamp(speed * 0.33f, 0f, 22f);
        fovSmoothed = Mth.lerp(fovSmoothed, targetFov, 1f - Mth.damp(2.5f, dt));
        fovDeg = fovSmoothed;
    }

    private void updateChase(float dt, float follow, float speed, CollisionWorld cw) {
        float desired = distance + Mth.clamp(speed * 0.14f, 0f, 6f);
        smoothedDistance = Mth.lerp(smoothedDistance <= 0f ? desired : smoothedDistance,
                desired, 1f - Mth.damp(3.2f, dt));

        float cy = Mth.lerp(yaw, follow, 1f - Mth.damp(4.5f, dt));
        // Shortest-arc interpolation so the camera does not unwind at +-pi.
        float delta = Mth.wrapPi(follow - yaw);
        cy = yaw + delta * (1f - Mth.damp(4.5f, dt));
        yaw = Mth.wrapPi(cy);

        float sinY = (float) Math.sin(yaw);
        float cosY = (float) Math.cos(yaw);
        double wantX = targetX - sinY * smoothedDistance;
        double wantZ = targetZ + cosY * smoothedDistance;
        double wantY = targetY + heightOffset;

        if (cw != null) {
            // Never let the camera sink into the ground or a building.
            float ground = cw.supportHeight(wantX, wantZ, wantY + 4.0, 4.0f) + 1.1f;
            if (wantY < ground) {
                wantY = ground;
            }
        }

        float k = 1f - Mth.damp(7.0f, dt);
        curX = Mth.lerp(curX, wantX, k);
        curY = Mth.lerp(curY, wantY, k * 0.85f);
        curZ = Mth.lerp(curZ, wantZ, k);
        px = curX;
        py = curY;
        pz = curZ;

        double dx = targetX - px;
        double dy = (targetY + 1.1) - py;
        double dz = targetZ - pz;
        double flat = Math.sqrt(dx * dx + dz * dz);
        pitch = (float) Math.atan2(dy, Math.max(0.3, flat));
        yaw = (float) Math.atan2(dx, -dz);
    }

    private void updateCockpit(float dt, float follow) {
        px = targetX;
        py = targetY;
        pz = targetZ;
        curX = px;
        curY = py;
        curZ = pz;
        yaw = follow;
    }

    private void updateOrbit(float dt) {
        float r = Math.max(4f, distance * 1.4f);
        px = targetX - Math.sin(orbitYaw) * Math.cos(orbitPitch) * r;
        py = targetY - Math.sin(orbitPitch) * r + heightOffset;
        pz = targetZ + Math.cos(orbitYaw) * Math.cos(orbitPitch) * r;
        yaw = orbitYaw;
        pitch = orbitPitch;
        curX = px;
        curY = py;
        curZ = pz;
    }

    private void updateFree(float dt) {
        // Position is driven externally by the debug controls; nothing to smooth.
        curX = px;
        curY = py;
        curZ = pz;
    }

    /** Forward unit vector. */
    public float forwardX() {
        return (float) (Math.sin(yaw) * Math.cos(pitch));
    }

    public float forwardY() {
        return (float) Math.sin(pitch);
    }

    public float forwardZ() {
        return (float) (-Math.cos(yaw) * Math.cos(pitch));
    }

    public void cycleMode() {
        mode = (mode + 1) % 3;      // free camera is debug only
    }
}
