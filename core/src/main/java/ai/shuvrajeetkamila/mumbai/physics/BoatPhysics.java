package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.math.MathUtil;
import ai.shuvrajeetkamila.mumbai.math.Vec3;

/**
 * A displacement or planing hull, floated on the same analytic sea the shader
 * draws.
 *
 * <p>Buoyancy comes from eight sample points distributed over the hull. Each
 * finds its own depth below the wave surface at its own position, so the boat
 * pitches into a swell and rolls when a wave passes under one side — none of
 * which happens if you float the hull on a single point at the centre.
 *
 * <p>Three things here were got wrong first and are worth stating plainly:
 *
 * <p>The righting moment is {@code -sin(heel) * m * g * GM}, not
 * {@code -heel * k}. A linear spring in roll angle keeps growing without bound
 * as the boat goes over, so a capsized boat is pushed upright from 150 degrees
 * of heel as if nothing had happened. The sine vanishes at 180 degrees, which
 * is why a real boat that goes fully over stays over.
 *
 * <p>Planing lift is charged induced drag. Lift without the drag that produces
 * it is a free energy source: the hull rises, wetted area drops, drag drops,
 * speed rises, lift rises, and the boat accelerates to the clamp.
 *
 * <p>Buoyancy saturates once a sample is deeper than about 1.7 draughts.
 * Without that, a hull pushed under by a wave generates enormous upward force
 * and is fired into the air.
 */
public final class BoatPhysics extends RigidBody {

    /** Hull sample points in the body frame. */
    public final Vec3[] samples;
    public final double[] sampleVolume;

    public double draft = 0.95;
    public double hullLength = 9.4;
    public double hullBeam = 3.1;
    /** Metacentric height. Bigger is stiffer and harder to capsize. */
    public double metacentricHeight = 1.15;

    public double maxThrust = 14500;             // N at full throttle
    public double rudderAuthority = 0.9;
    /**
     * Total resistance coefficient referred to the wetted surface. Real hull
     * resistance is skin friction plus form and wave making, which together
     * land somewhere near one percent of dynamic pressure over the wetted
     * area. Referring it to the plan area instead, as a first cut did here,
     * overstates the drag by more than an order of magnitude and leaves a
     * speedboat doing seven knots.
     */
    public double hullDragCoefficient = 0.011;
    public double lateralDragCoefficient = 5.4;
    public double planingThreshold = 7.5;        // m/s at which the hull lifts

    // ---- inputs ----------------------------------------------------------
    public double throttle;                       // -1 astern .. +1 ahead
    public double rudder;                         // -1 port .. +1 starboard

    // ---- outputs ---------------------------------------------------------
    public double submergedFraction;
    public boolean planing;
    public boolean aground;
    public double engineRpm;
    public double tideHeight;

    private final Vec3 tmp1 = new Vec3();
    private final Vec3 tmp2 = new Vec3();
    private final Vec3 tmp3 = new Vec3();
    private final double[] nrm = new double[3];
    private final double[] cur = new double[2];
    private double rudderState;

    public BoatPhysics() {
        this(9.4, 3.1, 2600);
    }

    public BoatPhysics(double length, double beam, double massKg) {
        hullLength = length;
        hullBeam = beam;
        mass = massKg;
        // Slender body: much easier to pitch than to roll about the long axis.
        inertia.set(mass * (length * length) / 11.0,
                mass * (length * length + beam * beam) / 12.0,
                mass * (beam * beam) / 7.0);
        samples = new Vec3[8];
        sampleVolume = new double[8];
        double hl = length * 0.5, hb = beam * 0.5;
        double[][] pts = {
            {-hb * 0.75, 0, hl * 0.88}, {hb * 0.75, 0, hl * 0.88},
            {-hb * 0.95, 0, hl * 0.30}, {hb * 0.95, 0, hl * 0.30},
            {-hb * 0.95, 0, -hl * 0.30}, {hb * 0.95, 0, -hl * 0.30},
            {-hb * 0.80, 0, -hl * 0.88}, {hb * 0.80, 0, -hl * 0.88},
        };
        // Displacement must float the boat at its design draught, so share the
        // total displaced volume out across the samples.
        double totalVolume = mass / MathUtil.RHO_SEA * 1.9;
        for (int i = 0; i < 8; i++) {
            samples[i] = new Vec3(pts[i][0], -draft * 0.5, pts[i][2]);
            sampleVolume[i] = totalVolume / 8.0;
        }
        recomputeHeaveDamping();
    }

    /**
     * Set the heave damping as a fraction of critical for this hull.
     *
     * <p>A fixed coefficient cannot work across a 1.2 tonne speedboat and a
     * 240 tonne coaster: on the heavy hull it comes to about three percent of
     * critical and the boat bobs for a minute after it is launched. The
     * stiffness here is the rate of change of buoyancy with immersion, which
     * for the saturating model is the full displacement spread over
     * {@code 1.7 * draft}.
     */
    public void recomputeHeaveDamping() {
        double volume = 0;
        for (double v : sampleVolume) { volume += v; }
        double k = MathUtil.RHO_SEA * MathUtil.G * volume / Math.max(0.2, draft * 1.7);
        double critical = 2.0 * Math.sqrt(k * Math.max(1, mass));
        heaveDamping = 0.62 * critical / samples.length;
    }

    /** Per-sample heave damping, N s/m. Derived, not guessed. */
    public double heaveDamping = 820;

    /** Preset: a Mumbai harbour ferry. Beamy, stiff, slow. */
    public static BoatPhysics ferry() {
        BoatPhysics b = new BoatPhysics(22.0, 6.4, 34000);
        b.draft = 1.6;
        b.metacentricHeight = 1.4;
        b.maxThrust = 62000;
        b.planingThreshold = 99;                 // never planes
        b.hullDragCoefficient = 0.012;
        b.recomputeHeaveDamping();
        return b;
    }

    /** Preset: a light speedboat. */
    public static BoatPhysics speedboat() {
        BoatPhysics b = new BoatPhysics(6.8, 2.4, 1250);
        b.draft = 0.52;
        b.metacentricHeight = 0.95;
        b.maxThrust = 9800;
        b.planingThreshold = 6.2;
        b.hullDragCoefficient = 0.009;
        b.recomputeHeaveDamping();
        return b;
    }

    /** Preset: a small cargo/fishing vessel. */
    public static BoatPhysics cargo() {
        BoatPhysics b = new BoatPhysics(34.0, 9.2, 240000);
        b.draft = 3.1;
        b.metacentricHeight = 2.4;
        b.maxThrust = 310000;
        b.planingThreshold = 99;
        b.hullDragCoefficient = 0.015;
        b.recomputeHeaveDamping();
        return b;
    }

    /**
     * One fixed step.
     *
     * @param sea    the shared wave model
     * @param seabed bed height, used for grounding; pass the terrain height
     */
    public void step(double dt, Ocean sea, SeabedProbe seabed) {
        clearAccumulators();
        addForce(0, -mass * MathUtil.G, 0);
        tideHeight = sea.tide();

        rudderState = MathUtil.approach(rudderState, MathUtil.clamp(rudder, -1, 1),
                5.0, dt);

        // --- buoyancy, one sample at a time ---------------------------------
        int submerged = 0;
        double totalBuoy = 0;
        for (int i = 0; i < samples.length; i++) {
            pointToWorld(samples[i], tmp1);
            double surface = sea.heightAt(tmp1.x, tmp1.z);
            double depth = surface - tmp1.y;
            if (depth <= 0) { continue; }
            submerged++;
            // Saturate: past about 1.7 draughts this sample is fully immersed
            // and cannot displace any more water.
            double fill = MathUtil.clamp(depth / (draft * 1.7), 0, 1);
            double buoy = MathUtil.RHO_SEA * MathUtil.G * sampleVolume[i] * fill;
            totalBuoy += buoy;
            sea.normalAt(tmp1.x, tmp1.z, nrm);
            // Push along the wave normal, not straight up, so a boat on a
            // sloping wave face is accelerated along it the way it should be.
            tmp2.set(nrm[0] * buoy * 0.35, buoy, nrm[2] * buoy * 0.35);
            addForceAtPoint(tmp2, tmp1);

            // Vertical damping against the water's own motion, which is what
            // stops the hull resonating with the swell.
            pointVelocity(tmp1, tmp3);
            double rel = tmp3.y - sea.verticalVelocityAt(tmp1.x, tmp1.z);
            tmp2.set(0, -rel * heaveDamping * fill, 0);
            addForceAtPoint(tmp2, tmp1);
        }
        submergedFraction = submerged / (double) samples.length;

        // --- righting moment -------------------------------------------------
        // sin, not the angle. A linear term cannot let a boat stay capsized.
        orientation.up(tmp1);
        double heel = Math.acos(MathUtil.clamp(tmp1.y, -1, 1));
        if (heel > 1e-4 && submerged > 0) {
            // Axis to rotate about: cross of the body up with world up.
            tmp2.set(tmp1.z * 0 - tmp1.y * 0, 0, 0);
            double ax = -tmp1.z;
            double az = tmp1.x;
            double al = Math.hypot(ax, az);
            if (al > 1e-9) {
                double m = Math.sin(heel) * mass * MathUtil.G * metacentricHeight
                        * submergedFraction;
                addTorque(ax / al * m, 0, az / al * m);
            }
        }
        // Angular damping in the water.
        addTorque(-angularVelocity.x * mass * 1.4,
                -angularVelocity.y * mass * 0.55,
                -angularVelocity.z * mass * 1.1);

        if (submerged == 0) {
            integrate(dt);
            sanitise(0);
            return;
        }

        // --- hydrodynamics ---------------------------------------------------
        orientation.forward(tmp1);
        orientation.right(tmp2);
        sea.currentAt(position.x, position.z, cur);
        tmp3.set(velocity.x - cur[0], velocity.y, velocity.z - cur[1]);
        double vFwd = tmp3.dot(tmp1);
        double vLat = tmp3.dot(tmp2);
        // Wetted surface, not plan area: the bottom plus both sides.
        double wetted = hullLength * (hullBeam + 2 * draft) * submergedFraction;

        // Planing: above the threshold the hull climbs onto its own bow wave.
        planing = vFwd > planingThreshold;
        double planingLift = 0;
        if (planing) {
            double over = (vFwd - planingThreshold) / planingThreshold;
            // Capped at 0.75 g, which is enough to lift the hull most of the
            // way out without letting it fly.
            planingLift = MathUtil.clamp(over * 0.55, 0, 0.75) * mass * MathUtil.G;
            addForce(0, planingLift, 0);
            // And the induced drag that lift costs. Without this the boat
            // accelerates for free.
            double induced = planingLift * 0.26;
            tmp3.set(tmp1).scaleSelf(-induced);
            addForce(tmp3);
            // Bow rises.
            addTorque(orientation.rotate(new Vec3(-planingLift * hullLength * 0.06,
                    0, 0)));
        }

        double rho = MathUtil.RHO_SEA;
        // Planing lifts the hull, so there is less of it in the water to drag.
        // That reduction is most of why planing is worth doing at all.
        double wettedNow = wetted * (planing
                ? 1.0 - 0.55 * MathUtil.clamp(planingLift / (mass * MathUtil.G) / 0.75, 0, 1)
                : 1.0);
        double fwdDrag = -0.5 * rho * hullDragCoefficient * wettedNow
                * vFwd * Math.abs(vFwd);
        double latDrag = -0.5 * rho * lateralDragCoefficient * wetted * 0.5
                * vLat * Math.abs(vLat);
        tmp3.set(tmp1.x * fwdDrag + tmp2.x * latDrag,
                tmp1.y * fwdDrag + tmp2.y * latDrag,
                tmp1.z * fwdDrag + tmp2.z * latDrag);
        addForce(tmp3);

        // --- propulsion -------------------------------------------------------
        double th = MathUtil.clamp(throttle, -1, 1);
        engineRpm = 700 + Math.abs(th) * 4600;
        double thrust = th * maxThrust * (planing ? 0.92 : 1.0) * submergedFraction;
        // The screw is at the stern and below the waterline, so thrust also
        // pitches the bow.
        tmp3.set(tmp1).scaleSelf(thrust);
        pointToWorld(new Vec3(0, -draft * 0.5, -hullLength * 0.46), tmp1);
        addForceAtPoint(tmp3, tmp1);

        // --- rudder ------------------------------------------------------------
        // A rudder only works with water flowing past it, which is why a boat
        // at rest cannot be steered and why backing into a berth is awkward.
        double flow = Math.abs(vFwd) + Math.abs(thrust) / Math.max(mass, 1) * 0.6;
        double rudderForce = -rudderState * rudderAuthority * flow * mass * 0.085
                * Math.signum(vFwd >= 0 ? 1 : -1);
        orientation.right(tmp2);
        tmp3.set(tmp2).scaleSelf(rudderForce);
        addForceAtPoint(tmp3, tmp1);

        // --- grounding ---------------------------------------------------------
        double bed = seabed.bedHeight(position.x, position.z);
        double keel = position.y - draft;
        aground = keel < bed;
        if (aground) {
            double pen = bed - keel;
            // Soft but firm: a sandbank, not a wall. Speed bleeds off fast.
            double n = pen * mass * 42.0;
            addForce(0, n, 0);
            // Sand under the keel is friction against the supported weight,
            // not a token drag term. A ferry under full power must not be
            // able to drive itself up a beach.
            double speed = velocity.planarLength();
            if (speed > 1e-3) {
                double mu = 0.75;
                double f = Math.min(mu * n, speed * mass * 8.0);
                addForce(-velocity.x / speed * f, 0, -velocity.z / speed * f);
            }
            tmp3.set(0, -velocity.y * mass * 2.4, 0);
            addForce(tmp3);
        }

        integrate(dt);
        sanitise(0);
    }

    /** Speed over the ground in knots, for the instruments. */
    public double speedKnots() { return groundSpeed() * 1.94384; }

    /** Heel angle in degrees; positive is starboard down. */
    public double heelDegrees() {
        orientation.right(tmp1);
        return Math.toDegrees(Math.asin(MathUtil.clamp(-tmp1.y, -1, 1)));
    }

    public boolean capsized() {
        orientation.up(tmp1);
        return tmp1.y < 0.1;
    }

    /** Where the bottom is. */
    public interface SeabedProbe {
        double bedHeight(double x, double z);
    }
}
