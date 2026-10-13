package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.math.MathUtil;
import ai.shuvrajeetkamila.mumbai.math.Vec3;

/**
 * The player on foot: a capsule that walks, runs, jumps, swims and falls over.
 *
 * <p>Locomotion is force based like everything else, but with one honest
 * concession that every character controller makes: a walking human is a
 * self-propelled system whose legs apply ground reaction forces no external
 * observer can predict, so the desired velocity is reached by applying a
 * bounded acceleration force toward it rather than by assigning it. The force
 * is capped, so running into a wall still stops you, pushing uphill is slower,
 * and being hit by a car still sends you flying.
 *
 * <p>Ragdoll is the same body with the locomotion force switched off and the
 * upright torque removed. Nothing is swapped out, so there is no discontinuity
 * when it triggers.
 */
public final class HumanPhysics extends RigidBody {

    public double height = 1.72;
    public double radius = 0.31;
    public double eyeHeight = 1.60;

    public double walkSpeed = 1.55;
    public double runSpeed = 4.35;
    public double sprintSpeed = 6.1;
    public double swimSpeed = 1.25;
    public double jumpSpeed = 4.3;
    /** Cap on the force the legs can apply, newtons. */
    public double legForceMax = 1750;

    // ---- inputs ----------------------------------------------------------
    public double moveForward;
    public double moveRight;
    public double headingRad;
    public boolean wantRun;
    public boolean wantSprint;
    public boolean wantJump;

    // ---- state -----------------------------------------------------------
    public boolean grounded;
    public boolean swimming;
    public boolean ragdoll;
    public double stamina = 1.0;

    /** Clears transient state when the player is placed somewhere new. */
    public void reset() {
        velocity.set(0, 0, 0);
        angularVelocity.set(0, 0, 0);
        orientation.identity();
        ragdoll = false;
        swimming = false;
        grounded = true;
        stamina = 1.0;
        moveForward = moveRight = 0;
        wantRun = wantSprint = wantJump = false;
    }
    public double groundSlope;
    public double waterDepth;
    public double stepPhase;
    private double jumpCooldown;
    private double airborneTime;

    private final Vec3 tmp1 = new Vec3();
    private final Vec3 tmp2 = new Vec3();
    private final Vec3 wish = new Vec3();

    public HumanPhysics() {
        mass = 78;
        // A standing person is far easier to spin about the vertical than to
        // topple end over end.
        inertia.set(12.0, 1.9, 12.0);
    }

    public void step(double dt, Terrain terrain, Ocean sea) {
        clearAccumulators();
        addForce(0, -mass * MathUtil.G, 0);

        double groundY = terrain.heightAt(position.x, position.z);
        boolean overWater = terrain.isWater(position.x, position.z);
        double seaY = overWater ? sea.heightAt(position.x, position.z)
                : Double.NEGATIVE_INFINITY;
        double feet = position.y - height * 0.5;
        waterDepth = overWater ? Math.max(0, seaY - feet) : 0;
        swimming = waterDepth > height * 0.72;
        groundSlope = terrain.slopeAt(position.x, position.z);

        if (jumpCooldown > 0) { jumpCooldown -= dt; }

        // --- ground contact ----------------------------------------------------
        double pen = groundY - feet;
        grounded = !swimming && pen > -0.06;
        if (grounded) {
            double k = mass * 180.0;
            double c = mass * 16.0;
            double n = Math.max(0, pen * k - velocity.y * c);
            addForce(0, n, 0);
            airborneTime = 0;
        } else {
            airborneTime += dt;
        }

        // --- buoyancy ----------------------------------------------------------
        if (waterDepth > 0.02) {
            double sub = MathUtil.clamp(waterDepth / height, 0, 1);
            // A person is very slightly less dense than fresh water and rather
            // less dense than seawater, so they float with the head out.
            double displaced = sub * (mass / 985.0) * 1.08;
            addForce(0, MathUtil.RHO_SEA * MathUtil.G * displaced, 0);
            // Water drag, which is what makes swimming slow and falling in
            // from a height survivable.
            tmp1.set(velocity).scaleSelf(-mass * 2.6 * sub);
            addForce(tmp1);
        }

        if (ragdoll) {
            // No legs, no balance. Just a body and whatever it hits.
            addTorque(-angularVelocity.x * 24, -angularVelocity.y * 18,
                    -angularVelocity.z * 24);
            if (grounded) {
                addForce(-velocity.x * mass * 4.5, 0, -velocity.z * mass * 4.5);
            }
            integrate(dt);
            sanitise(groundY);
            return;
        }

        // --- what the player asked for ------------------------------------------
        double fx = Math.sin(headingRad), fz = -Math.cos(headingRad);
        double rx = -fz, rz = fx;
        wish.set(fx * moveForward + rx * moveRight, 0,
                fz * moveForward + rz * moveRight);
        double wishLen = wish.planarLength();
        if (wishLen > 1.0) { wish.scaleSelf(1.0 / wishLen); wishLen = 1.0; }

        double target;
        if (swimming) {
            target = swimSpeed;
        } else if (wantSprint && stamina > 0.02) {
            target = sprintSpeed;
        } else if (wantRun) {
            target = runSpeed;
        } else {
            target = walkSpeed;
        }
        // Slopes cost speed, steep ones a great deal.
        target *= MathUtil.remap(groundSlope, 0.18, 0.78, 1.0, 0.25);
        if (waterDepth > 0.25 && !swimming) {
            target *= MathUtil.remap(waterDepth, 0.25, height * 0.7, 0.85, 0.35);
        }

        if (wantSprint && wishLen > 0.1 && !swimming) {
            stamina = Math.max(0, stamina - dt * 0.19);
        } else {
            stamina = Math.min(1, stamina + dt * (grounded ? 0.12 : 0.04));
        }

        // --- locomotion force ----------------------------------------------------
        if ((grounded || swimming) && wishLen > 0.001) {
            wish.scaleSelf(target);
            tmp1.set(wish.x - velocity.x, 0, wish.z - velocity.z);
            double need = tmp1.planarLength();
            if (need > 1e-4) {
                // Bounded: this is a person's legs, not a rocket. Running into
                // a wall therefore stops you, and a car still knocks you down.
                double f = Math.min(legForceMax * (swimming ? 0.22 : 1.0),
                        need * mass * 9.0);
                tmp1.scaleSelf(f / need);
                addForce(tmp1);
            }
            stepPhase += dt * velocity.planarLength() * 1.65;
        } else if (grounded && wishLen <= 0.001) {
            // Standing still: friction that actually holds on a slope.
            addForce(-velocity.x * mass * 11.0, 0, -velocity.z * mass * 11.0);
        } else if (!grounded && !swimming && wishLen > 0.001) {
            // A little air control, far less than on the ground.
            wish.scaleSelf(target);
            addForce((wish.x - velocity.x) * mass * 0.85, 0,
                    (wish.z - velocity.z) * mass * 0.85);
        }

        // --- jump ------------------------------------------------------------------
        if (wantJump && grounded && jumpCooldown <= 0 && !swimming) {
            // An impulse over one step, which is what a jump is.
            addForce(0, mass * jumpSpeed / dt, 0);
            jumpCooldown = 0.32;
        }
        if (wantJump && swimming) {
            addForce(0, mass * 3.4, 0);
        }

        // --- stay upright -------------------------------------------------------------
        orientation.up(tmp1);
        double lean = Math.hypot(tmp1.x, tmp1.z);
        if (lean > 1e-4) {
            double m = Math.sin(Math.asin(MathUtil.clamp(lean, -1, 1))) * mass * 42.0;
            addTorque(-tmp1.z / lean * m, 0, tmp1.x / lean * m);
        }
        // Face where we are going.
        double yawErr = MathUtil.wrapPi(headingRad - orientation.heading());
        addTorque(0, yawErr * 46.0 - angularVelocity.y * 14.0, 0);
        addTorque(-angularVelocity.x * 38, 0, -angularVelocity.z * 38);

        // A long fall onto hard ground knocks you over.
        if (grounded && airborneTime > 0.95 && velocity.y < -11.0) {
            ragdoll = true;
        }

        integrate(dt);
        sanitise(groundY);
    }

    /** World position of the eyes, for the first-person camera. */
    public Vec3 eyePosition(Vec3 out) {
        return out.set(position.x, position.y - height * 0.5 + eyeHeight, position.z);
    }

    public void getUp() {
        ragdoll = false;
        orientation.identity();
        angularVelocity.zero();
    }

    /** What the ground is doing here. */
    public interface Terrain {
        double heightAt(double x, double z);

        boolean isWater(double x, double z);

        double slopeAt(double x, double z);
    }
}
