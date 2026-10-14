package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.math.Quat;
import ai.shuvrajeetkamila.mumbai.math.Vec3;

/**
 * A six degree-of-freedom rigid body, shared by all five vehicle models.
 *
 * <p>The contract every model obeys: during a step you may only call
 * {@link #addForce}, {@link #addForceAtPoint} and {@link #addTorque}. Nothing
 * writes velocity or position directly. That rule is what makes the five
 * systems behave consistently when they interact with the same world — a car
 * that sets its own velocity to climb a kerb will happily drive through a
 * wall, and a helicopter that assigns its altitude cannot be made to fall out
 * of the sky when the engine quits.
 *
 * <p>Position is double precision because the world is seventy kilometres
 * across and a float loses millimetre resolution well before the far corner.
 * Inertia is a diagonal tensor in the body frame, which is accurate enough for
 * vehicles whose mass is roughly symmetric about their long axis and far
 * cheaper than a full tensor rotation each step.
 */
public class RigidBody {

    public final Vec3 position = new Vec3();
    public final Vec3 velocity = new Vec3();
    public final Quat orientation = new Quat();
    /** Angular velocity in the world frame, rad/s. */
    public final Vec3 angularVelocity = new Vec3();

    public double mass = 1000.0;
    /** Diagonal of the inertia tensor in the body frame, kg m^2. */
    public final Vec3 inertia = new Vec3(1000, 1000, 1000);

    protected final Vec3 force = new Vec3();
    protected final Vec3 torque = new Vec3();

    private final Vec3 tmpA = new Vec3();
    private final Vec3 tmpB = new Vec3();

    /** Set to false for a body resting on the ground, to damp jitter. */
    public boolean active = true;

    /**
     * Force and torque carried into the next step.
     *
     * <p>Contacts with the city are resolved outside the body's own
     * {@code step}, because the collision index belongs to the world and
     * not to the physics. A force added after {@code step} returns would
     * be thrown away by the next {@code clearAccumulators}, so it is
     * parked here instead and seeded into the accumulator at the top of
     * the following step. One step of lag, sixteen milliseconds, which is
     * shorter than the contact itself.
     */
    public final Vec3 carriedForce = new Vec3();
    public final Vec3 carriedTorque = new Vec3();

    public void clearAccumulators() {
        force.set(carriedForce);
        torque.set(carriedTorque);
        carriedForce.zero();
        carriedTorque.zero();
    }

    /** A force for the next step rather than this one. */
    public void carryForce(double fx, double fy, double fz) {
        carriedForce.addSelf(fx, fy, fz);
    }

    /** As {@link #addForceAtPoint}, but for the next step. */
    public void carryForceAtPoint(Vec3 f, Vec3 worldPoint) {
        carriedForce.addSelf(f);
        tmpA.set(worldPoint).subSelf(position);
        tmpA.crossInto(f, tmpB);
        carriedTorque.addSelf(tmpB);
    }

    /** World-frame force through the centre of mass, newtons. */
    public void addForce(Vec3 f) {
        force.addSelf(f);
    }

    public void addForce(double fx, double fy, double fz) {
        force.addSelf(fx, fy, fz);
    }

    /** World-frame torque, newton metres. */
    public void addTorque(Vec3 t) {
        torque.addSelf(t);
    }

    public void addTorque(double tx, double ty, double tz) {
        torque.addSelf(tx, ty, tz);
    }

    /**
     * World-frame force applied at a world-frame point, which also produces the
     * torque about the centre of mass. This is how wheels, buoyancy samples,
     * rotor discs and control surfaces all talk to the body.
     */
    public void addForceAtPoint(Vec3 f, Vec3 worldPoint) {
        force.addSelf(f);
        tmpA.set(worldPoint).subSelf(position);
        tmpA.crossInto(f, tmpB);
        torque.addSelf(tmpB);
    }

    /** Body-frame point to world frame. */
    public Vec3 pointToWorld(Vec3 bodyPoint, Vec3 out) {
        orientation.rotate(bodyPoint, out);
        return out.addSelf(position);
    }

    /** Velocity of a world-frame point on this body, including rotation. */
    public Vec3 pointVelocity(Vec3 worldPoint, Vec3 out) {
        tmpA.set(worldPoint).subSelf(position);
        angularVelocity.crossInto(tmpA, out);
        return out.addSelf(velocity);
    }

    /**
     * Semi-implicit Euler. Velocity is updated from the accumulated force
     * first and position from the new velocity, which is stable for the spring
     * and damper systems in the suspension and buoyancy models where explicit
     * Euler is not.
     */
    public void integrate(double dt) {
        if (mass <= 0) { return; }
        velocity.addScaledSelf(force, dt / mass);

        // Angular acceleration: rotate torque into the body frame, divide by
        // the diagonal inertia, rotate back.
        orientation.inverseRotate(torque, tmpA);
        tmpA.x /= Math.max(1e-6, inertia.x);
        tmpA.y /= Math.max(1e-6, inertia.y);
        tmpA.z /= Math.max(1e-6, inertia.z);
        orientation.rotate(tmpA, tmpB);
        angularVelocity.addScaledSelf(tmpB, dt);

        position.addScaledSelf(velocity, dt);
        orientation.integrateSelf(angularVelocity, dt);
    }

    /** Speed over the ground, m/s. */
    public double groundSpeed() { return velocity.planarLength(); }

    public double speed() { return velocity.length(); }

    /** Forward component of velocity in the body frame, m/s. */
    public double forwardSpeed() {
        orientation.forward(tmpA);
        return velocity.dot(tmpA);
    }

    /** Sideways component of velocity in the body frame, m/s. */
    public double lateralSpeed() {
        orientation.right(tmpA);
        return velocity.dot(tmpA);
    }

    public boolean isFinite() {
        return position.isFinite() && velocity.isFinite()
                && orientation.isFinite() && angularVelocity.isFinite();
    }

    /**
     * Last-resort guard. If a model ever produces a non-finite state the whole
     * session is lost, so the body is parked rather than propagating NaN into
     * the camera, the streamer and the save state.
     */
    public boolean sanitise(double groundY) {
        if (isFinite()) { return false; }
        velocity.zero();
        angularVelocity.zero();
        if (!position.isFinite()) { position.set(0, groundY + 2, 0); }
        if (!orientation.isFinite()) { orientation.identity(); }
        return true;
    }

    public void placeAt(double x, double y, double z, double headingRad) {
        position.set(x, y, z);
        velocity.zero();
        angularVelocity.zero();
        orientation.set(Quat.yaw(headingRad));
    }
}
