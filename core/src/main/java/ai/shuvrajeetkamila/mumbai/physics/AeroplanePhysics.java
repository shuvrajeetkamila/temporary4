package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.math.MathUtil;
import ai.shuvrajeetkamila.mumbai.math.Vec3;

/**
 * A light twin, flown off real runway geometry.
 *
 * <p>Lift and drag are computed from dynamic pressure, {@code q = rho v^2 / 2},
 * a lift curve that stalls, and a drag polar with an induced term that grows
 * as the square of the lift coefficient. There is no hidden impulse at
 * rotation: the aeroplane leaves the ground when and only when the wing is
 * making more lift than the aeroplane weighs, which means it needs the runway
 * length it needs. On the short runway at Juhu it will not get airborne at
 * maximum weight, and that is the correct answer rather than a bug.
 *
 * <p>The stall is modelled as a real loss of lift past the critical angle,
 * with the drag continuing to climb. Recovery is the real recovery: lower the
 * nose, let the angle of attack fall below critical, accept the height loss.
 */
public final class AeroplanePhysics extends RigidBody {

    public double wingArea = 16.2;               // m^2
    public double wingSpan = 11.4;
    public double aspectRatio = 8.02;
    public double oswaldEfficiency = 0.78;
    public double cd0 = 0.027;                   // parasite drag, clean
    public double clAlpha = 5.1;                 // per radian
    public double cl0 = 0.22;
    public double stallAngleDeg = 15.5;
    public double clMax = 1.52;

    public double maxThrust = 5600;              // N, both engines, sea level
    public double elevatorPower = 1.95;
    public double aileronPower = 1.35;
    public double rudderPower = 0.72;

    /** Flap detents in degrees and the lift and drag they add. */
    public double[] flapDetents = {0, 10, 25, 40};
    public int flapSetting;
    public boolean gearDown = true;
    public double gearDragArea = 0.42;

    // ---- inputs ----------------------------------------------------------
    public double throttle;
    public double elevator;                      // + nose up
    public double aileron;                       // + roll right
    public double rudderInput;                   // + nose right
    public double brakes;

    // ---- outputs ---------------------------------------------------------
    public double angleOfAttack;
    public double sideslip;
    public double indicatedAirspeed;
    public double liftN;
    public double dragN;
    public boolean stalled;
    public double stallMarginDeg;
    public double loadFactor = 1.0;
    public boolean onGround;
    public double engineRpm;

    private final Vec3 tmp1 = new Vec3();
    private final Vec3 tmp2 = new Vec3();
    private final Vec3 tmp3 = new Vec3();
    private final Vec3 air = new Vec3();

    public AeroplanePhysics() {
        mass = 1950;
        inertia.set(4200, 6800, 2600);
    }

    public double flapDeg() {
        return flapDetents[MathUtil.clampi(flapSetting, 0, flapDetents.length - 1)];
    }

    /** Lift coefficient including the stall. */
    public double liftCoefficient(double alphaRad, double flapDegrees) {
        double stall = Math.toRadians(stallAngleDeg) - Math.toRadians(flapDegrees) * 0.055;
        double dClFlap = flapDegrees * 0.0175;
        double a = alphaRad;
        if (Math.abs(a) <= stall) {
            return MathUtil.clamp(cl0 + dClFlap + clAlpha * a,
                    -clMax - dClFlap, clMax + dClFlap);
        }
        // Past critical the wing sheds lift rather than holding it.
        double over = Math.abs(a) - stall;
        double peak = cl0 + dClFlap + clAlpha * stall;
        double fall = peak * Math.exp(-over * 5.2) * 0.86
                + 0.42 * Math.sin(2 * MathUtil.clamp(Math.abs(a), 0, Math.PI / 2));
        return Math.signum(a) * Math.max(0.12, fall);
    }

    public double dragCoefficient(double cl, double flapDegrees, boolean gear) {
        double parasite = cd0 + flapDegrees * 0.00085 + (gear ? gearDragArea / wingArea : 0);
        double induced = cl * cl / (Math.PI * aspectRatio * oswaldEfficiency);
        return parasite + induced;
    }

    public void step(double dt, GroundProbe ground, double windX, double windZ) {
        clearAccumulators();
        addForce(0, -mass * MathUtil.G, 0);

        double rho = MathUtil.airDensity(position.y);
        air.set(velocity.x - windX, velocity.y, velocity.z - windZ);
        double v = air.length();
        indicatedAirspeed = v * Math.sqrt(rho / MathUtil.RHO_AIR_SL);

        orientation.forward(tmp1);
        orientation.up(tmp2);
        orientation.right(tmp3);

        double groundY = ground.heightAt(position.x, position.z);
        onGround = position.y - groundY < 1.15;

        if (v > 1.0) {
            // Angle of attack is the angle between the airflow and the wing
            // chord, measured in the aeroplane's own plane of symmetry.
            double vf = air.dot(tmp1);
            double vu = air.dot(tmp2);
            double vr = air.dot(tmp3);
            angleOfAttack = Math.atan2(-vu, Math.max(0.5, vf));
            sideslip = Math.atan2(vr, Math.max(0.5, vf));

            double q = 0.5 * rho * v * v;
            double fd = flapDeg();
            double cl = liftCoefficient(angleOfAttack, fd);
            double cd = dragCoefficient(cl, fd, gearDown);
            double stallA = Math.toRadians(stallAngleDeg) - Math.toRadians(fd) * 0.055;
            stalled = Math.abs(angleOfAttack) > stallA;
            stallMarginDeg = Math.toDegrees(stallA - Math.abs(angleOfAttack));

            liftN = q * wingArea * cl;
            dragN = q * wingArea * cd;

            // Lift acts perpendicular to the airflow, in the symmetry plane.
            air.normalizeSelf();
            tmp1.crossInto(air, tmp3);          // span-wise axis
            if (tmp3.lengthSq() < 1e-9) { orientation.right(tmp3); }
            tmp3.normalizeSelf();
            Vec3 liftDir = tmp3.cross(air).normalizeSelf();
            addForce(liftDir.x * liftN, liftDir.y * liftN, liftDir.z * liftN);
            addForce(-air.x * dragN, -air.y * dragN, -air.z * dragN);

            loadFactor = liftN / Math.max(1, mass * MathUtil.G);

            // --- control surfaces. Their authority scales with q, which is why
            // the controls go slack on the approach and stiffen in the cruise.
            double auth = q * wingArea;
            orientation.right(tmp3);
            orientation.forward(tmp1);
            orientation.up(tmp2);
            double pitchM = -elevator * elevatorPower * auth * 0.42;
            double rollM = aileron * aileronPower * auth * 0.30;
            double yawM = rudderInput * rudderPower * auth * 0.26;
            addTorque(tmp3.x * pitchM, tmp3.y * pitchM, tmp3.z * pitchM);
            addTorque(tmp1.x * -rollM, tmp1.y * -rollM, tmp1.z * -rollM);
            addTorque(tmp2.x * yawM, tmp2.y * yawM, tmp2.z * yawM);

            // --- static stability. A real aeroplane weathercocks into the
            // airflow and tends to return to level; without these terms it
            // flies like a brick with no tail.
            addTorque(tmp2.x * -sideslip * auth * 0.55,
                    tmp2.y * -sideslip * auth * 0.55,
                    tmp2.z * -sideslip * auth * 0.55);
            double pitchStab = -(angleOfAttack - 0.035) * auth * 0.62;
            addTorque(tmp3.x * pitchStab, tmp3.y * pitchStab, tmp3.z * pitchStab);
            // Dihedral: sideslip produces a roll away from it.
            addTorque(tmp1.x * sideslip * auth * 0.20,
                    tmp1.y * sideslip * auth * 0.20,
                    tmp1.z * sideslip * auth * 0.20);
        } else {
            angleOfAttack = 0;
            sideslip = 0;
            liftN = 0;
            dragN = 0;
            stalled = false;
        }

        // --- thrust. Falls off with density and a little with speed.
        orientation.forward(tmp1);
        double th = MathUtil.clamp(throttle, 0, 1);
        engineRpm = 700 + th * 2000;
        double thrust = th * maxThrust * (rho / MathUtil.RHO_AIR_SL)
                * MathUtil.clamp(1.0 - v / 180.0, 0.25, 1.0);
        addForce(tmp1.x * thrust, tmp1.y * thrust, tmp1.z * thrust);

        addTorque(-angularVelocity.x * 4200, -angularVelocity.y * 3100,
                -angularVelocity.z * 2600);

        // --- undercarriage -----------------------------------------------------
        if (gearDown && position.y - groundY < 1.6) {
            double pen = 1.6 - (position.y - groundY);
            double n = Math.max(0, pen * mass * 30.0 - velocity.y * mass * 6.5);
            addForce(0, n, 0);
            // Rolling and braking along the runway.
            orientation.forward(tmp1);
            double vf = velocity.dot(tmp1);
            double roll = -(0.02 + brakes * 0.55) * n * Math.signum(vf);
            if (Math.abs(vf) < 0.4) { roll = -vf * mass * 2.0; }
            addForce(tmp1.x * roll, tmp1.y * roll, tmp1.z * roll);
            // Tyres resist sideways motion, which is what keeps it tracking.
            orientation.right(tmp3);
            double vr = velocity.dot(tmp3);
            addForce(tmp3.x * -vr * mass * 2.4, 0, tmp3.z * -vr * mass * 2.4);
            // Nosewheel steering at taxi speed.
            if (Math.abs(vf) < 22) {
                orientation.up(tmp2);
                double st = rudderInput * n * 0.35 * MathUtil.clamp(Math.abs(vf) / 6, 0, 1);
                addTorque(tmp2.x * st, tmp2.y * st, tmp2.z * st);
            }
            // Keep it on its wheels.
            orientation.up(tmp2);
            addTorque(-tmp2.z * n * 0.9, 0, tmp2.x * n * 0.9);
        } else if (!gearDown && position.y - groundY < 0.9) {
            double pen = 0.9 - (position.y - groundY);
            addForce(0, Math.max(0, pen * mass * 24.0 - velocity.y * mass * 5.0), 0);
            addForce(-velocity.x * mass * 3.4, 0, -velocity.z * mass * 3.4);
        }

        integrate(dt);
        sanitise(groundY);
    }

    /** Stall speed for the current configuration, m/s true. */
    public double stallSpeed(double rho) {
        double cl = clMax + flapDeg() * 0.0175;
        return Math.sqrt(2 * mass * MathUtil.G / (rho * wingArea * cl));
    }

    public double airspeedKnots() { return indicatedAirspeed * 1.94384; }

    public interface GroundProbe {
        double heightAt(double x, double z);
    }
}
