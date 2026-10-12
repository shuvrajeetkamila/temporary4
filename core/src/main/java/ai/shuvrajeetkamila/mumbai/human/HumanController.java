package ai.shuvrajeetkamila.mumbai.human;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.physics.CollisionWorld;
import ai.shuvrajeetkamila.mumbai.physics.RigidBody;
import ai.shuvrajeetkamila.mumbai.world.LandUse;

/**
 * On-foot character: a vertical capsule driven by forces, with ground friction, step-up,
 * slope limits, swimming, falling damage and a simple ragdoll when knocked over.
 *
 * <p>Motion is produced by applying a horizontal force toward the desired velocity rather
 * than writing the velocity directly, which keeps momentum, slope behaviour and collision
 * response consistent with the vehicles.
 */
public final class HumanController {

    public static final float HEIGHT = 1.74f;
    public static final float RADIUS = 0.32f;
    public static final float EYE_HEIGHT = 1.62f;
    public static final float MASS = 72f;

    public static final float WALK_SPEED = 1.5f;
    public static final float JOG_SPEED = 3.6f;
    public static final float SPRINT_SPEED = 6.1f;
    public static final float CROUCH_SPEED = 0.95f;
    public static final float SWIM_SPEED = 1.5f;
    public static final float JUMP_VELOCITY = 4.4f;
    public static final float MAX_SLOPE_COS = 0.62f;     // about 52 degrees
    public static final float STEP_UP = 0.42f;

    public final RigidBody body = new RigidBody();

    // Inputs
    public float moveX;          // strafe, -1..1
    public float moveZ;          // forward, -1..1
    public float heading;        // radians, where the character faces
    public boolean sprint;
    public boolean crouch;
    public boolean jumpRequested;

    // State
    public boolean grounded;
    public boolean swimming;
    public boolean inWaterShallow;
    public float groundHeight;
    public float stamina = 1f;
    public float ragdollTimer;
    public float fallStartY;
    public boolean falling;
    public float lastFallDamage;
    public float speedPlanar;
    public float stepCycle;
    public float breath = 1f;
    public int surfaceType;

    private final float[] n = new float[3];
    private final double[] posTmp = new double[3];
    private float crouchBlend;

    public HumanController() {
        body.setMass(MASS);
        body.ixx = 12f;
        body.iyy = 6f;
        body.izz = 12f;
    }

    public float height() {
        return Mth.lerp(HEIGHT, HEIGHT * 0.62f, crouchBlend);
    }

    public float eyeHeight() {
        return Mth.lerp(EYE_HEIGHT, EYE_HEIGHT * 0.58f, crouchBlend);
    }

    public void placeAt(double x, double y, double z, float yaw) {
        body.px = x;
        body.py = y;
        body.pz = z;
        body.vx = 0;
        body.vy = 0;
        body.vz = 0;
        body.wx = 0;
        body.wy = 0;
        body.wz = 0;
        heading = yaw;
        body.setOrientationYawPitchRoll(yaw, 0f, 0f);
        ragdollTimer = 0f;
        falling = false;
    }

    public void step(float dt, CollisionWorld cw) {
        cw.refreshBoxes(body.px, body.pz, 40f);
        body.clearAccumulators();

        crouchBlend = Mth.approach(crouchBlend, crouch && !swimming ? 1f : 0f, dt * 6f);

        body.addForce(0f, -MASS * RigidBody.GRAVITY, 0f);

        float waterSurface = cw.waterSurface(body.px, body.pz);
        float feetY = (float) body.py;
        float submerged = waterSurface == Float.NEGATIVE_INFINITY ? 0f
                : Mth.clamp01((waterSurface - feetY) / height());
        swimming = submerged > 0.72f;
        inWaterShallow = submerged > 0.08f && !swimming;
        surfaceType = cw.landUse(body.px, body.pz);

        groundHeight = cw.supportHeight(body.px, body.pz, body.py + 0.4, STEP_UP + 0.1f);
        float clearance = feetY - groundHeight;
        grounded = !swimming && clearance <= 0.08f && body.vy <= 1.2f;

        if (ragdollTimer > 0f) {
            stepRagdoll(dt, cw);
            return;
        }

        if (swimming) {
            stepSwim(dt, cw, waterSurface, submerged);
        } else {
            stepWalk(dt, cw, clearance, submerged);
        }

        body.integrate(dt);
        resolveCollision(cw);
        settleToGround(cw);

        speedPlanar = (float) Math.sqrt(body.vx * body.vx + body.vz * body.vz);
        if (grounded && speedPlanar > 0.2f) {
            stepCycle += speedPlanar * dt * 1.5f;
            if (stepCycle > Mth.TAU) {
                stepCycle -= Mth.TAU;
            }
        }
        body.setOrientationYawPitchRoll(heading, 0f, 0f);
    }

    private void stepWalk(float dt, CollisionWorld cw, float clearance, float submerged) {
        float target = crouch ? CROUCH_SPEED : (sprint && stamina > 0.05f ? SPRINT_SPEED
                : (Math.abs(moveX) + Math.abs(moveZ) > 0.8f ? JOG_SPEED : WALK_SPEED));
        target *= LandUse.walkScale(surfaceType);
        target *= 1f - 0.45f * submerged;

        // Desired direction in world space from the facing heading.
        float fx = (float) Math.sin(heading);
        float fz = -(float) Math.cos(heading);
        float rx = -fz;
        float rz = fx;
        float inX = Mth.clamp(moveX, -1f, 1f);
        float inZ = Mth.clamp(moveZ, -1f, 1f);
        float mag = (float) Math.sqrt(inX * inX + inZ * inZ);
        if (mag > 1f) {
            inX /= mag;
            inZ /= mag;
            mag = 1f;
        }
        float wishX = (fx * inZ + rx * inX) * target;
        float wishZ = (fz * inZ + rz * inX) * target;

        if (grounded) {
            cw.supportNormal(body.px, body.pz, body.py, n);
            boolean tooSteep = n[1] < MAX_SLOPE_COS;
            float accel = tooSteep ? 2f : (mag > 0.01f ? 26f : 18f);
            float dvx = wishX - body.vx;
            float dvz = wishZ - body.vz;
            body.addForce(dvx * MASS * accel * 0.25f, 0f, dvz * MASS * accel * 0.25f);

            if (tooSteep) {
                // Slide down slopes that are too steep to stand on.
                body.addForce(n[0] * MASS * 9.0f, 0f, n[2] * MASS * 9.0f);
            }

            if (jumpRequested && !crouch) {
                body.vy = JUMP_VELOCITY * (sprint ? 1.06f : 1f);
                grounded = false;
                falling = true;
                fallStartY = (float) body.py;
            }
        } else {
            // Air control is deliberately weak.
            float dvx = wishX * 0.8f - body.vx;
            float dvz = wishZ * 0.8f - body.vz;
            body.addForce(dvx * MASS * 1.6f, 0f, dvz * MASS * 1.6f);
            if (!falling && body.vy < -0.5f) {
                falling = true;
                fallStartY = (float) body.py;
            }
        }
        jumpRequested = false;

        if (submerged > 0.02f) {
            body.addForce(-body.vx * 42f * submerged, 0f, -body.vz * 42f * submerged);
        }

        stamina = Mth.clamp01(stamina + (sprint && speedPlanar > 4f ? -0.14f : 0.09f) * dt);
        breath = Mth.clamp01(breath + dt * 0.4f);
    }

    private void stepSwim(float dt, CollisionWorld cw, float waterSurface, float submerged) {
        falling = false;
        float fx = (float) Math.sin(heading);
        float fz = -(float) Math.cos(heading);
        float rx = -fz;
        float rz = fx;
        float wishX = (fx * moveZ + rx * moveX) * SWIM_SPEED;
        float wishZ = (fz * moveZ + rz * moveX) * SWIM_SPEED;

        // Buoyancy holds the head near the surface.
        float targetY = waterSurface - height() * 0.78f;
        float dy = targetY - (float) body.py;
        float buoy = MASS * RigidBody.GRAVITY + dy * MASS * 10f - body.vy * MASS * 2.6f;
        body.addForce(0f, Mth.clamp(buoy, -MASS * 4f, MASS * 26f), 0f);

        body.addForce((wishX - body.vx) * MASS * 2.2f, 0f, (wishZ - body.vz) * MASS * 2.2f);
        body.addForce(-body.vx * 26f, -body.vy * 48f, -body.vz * 26f);

        if (crouch) {
            body.addForce(0f, -MASS * 7f, 0f);        // dive
            breath = Mth.clamp01(breath - dt * 0.07f);
        } else {
            breath = Mth.clamp01(breath + dt * 0.3f);
        }
        if (jumpRequested) {
            body.addForce(0f, MASS * 9f, 0f);
            jumpRequested = false;
        }

        // Tidal current carries the swimmer.
        cw.water().currentAt(body.px, body.pz, 0, n);
        body.addForce(n[0] * MASS * 0.8f, 0f, n[2] * MASS * 0.8f);

        stamina = Mth.clamp01(stamina - dt * 0.05f);
        body.integrate(dt);
        resolveCollision(cw);
    }

    private void stepRagdoll(float dt, CollisionWorld cw) {
        ragdollTimer -= dt;
        body.addForce(-body.vx * 30f, 0f, -body.vz * 30f);
        body.integrate(dt);
        resolveCollision(cw);
        float ground = cw.supportHeight(body.px, body.pz, body.py + 0.3, 0.5f);
        if (body.py < ground) {
            body.py = ground;
            if (body.vy < 0f) {
                body.vy = -body.vy * 0.15f;
            }
            body.vx *= 0.7f;
            body.vz *= 0.7f;
        }
        if (ragdollTimer <= 0f) {
            ragdollTimer = 0f;
            body.vx *= 0.2f;
            body.vz *= 0.2f;
        }
    }

    private void resolveCollision(CollisionWorld cw) {
        posTmp[0] = body.px;
        posTmp[1] = body.py;
        posTmp[2] = body.pz;
        if (cw.resolveCapsule(posTmp, RADIUS, height(), STEP_UP)) {
            double dx = posTmp[0] - body.px;
            double dz = posTmp[2] - body.pz;
            body.px = posTmp[0];
            body.pz = posTmp[2];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1e-6) {
                float nx = (float) (dx / len);
                float nz = (float) (dz / len);
                float into = body.vx * nx + body.vz * nz;
                if (into < 0f) {
                    body.vx -= into * nx;
                    body.vz -= into * nz;
                }
            }
        }
    }

    private void settleToGround(CollisionWorld cw) {
        float ground = cw.supportHeight(body.px, body.pz, body.py + STEP_UP, STEP_UP + 0.1f);
        groundHeight = ground;
        if (body.py <= ground) {
            if (falling) {
                float drop = fallStartY - (float) body.py;
                if (drop > 4.5f) {
                    lastFallDamage = Mth.clamp01((drop - 4.5f) / 16f);
                    if (drop > 9f) {
                        ragdollTimer = Mth.clamp(drop / 14f, 0.8f, 3.2f);
                    }
                } else {
                    lastFallDamage = 0f;
                }
                falling = false;
            }
            body.py = ground;
            if (body.vy < 0f) {
                body.vy = 0f;
            }
            grounded = true;
        }
    }

    public void knockDown(float seconds, float impulseX, float impulseY, float impulseZ) {
        ragdollTimer = Math.max(ragdollTimer, seconds);
        body.vx += impulseX / MASS;
        body.vy += impulseY / MASS;
        body.vz += impulseZ / MASS;
    }

    public boolean isRagdoll() {
        return ragdollTimer > 0f;
    }

    /** Animation phase helper: 0..1 through the current stride. */
    public float stridePhase() {
        return stepCycle / Mth.TAU;
    }
}
