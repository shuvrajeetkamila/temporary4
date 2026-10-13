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
    /**
     * Control authority, balanced against the stability derivatives below.
     *
     * <p>These are not free parameters. The angle of attack the aeroplane
     * settles at is where the elevator moment equals the tailplane's
     * restoring moment, so {@code elevatorPower} and the 0.62 pitch stability
     * coefficient together decide what full aft stick actually buys you. At
     * the first values tried here that equilibrium sat at 32 degrees: the
     * aeroplane over-rotated on every takeoff and struck its tail before the
     * wing was flying. These give about 18 degrees at full deflection, which
     * is just past the stall - a real aeroplane can be stalled with the stick
     * on the back stop, and this one can too.
     */
    public double elevatorPower = 0.42;
    public double aileronPower = 0.95;
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
            // airflow x span, in that order. The other way round puts lift
            // on the wrong side of the wing: the aeroplane is pressed into
            // the runway and the harder you pull the firmer it is held down.
            Vec3 liftDir = air.cross(tmp3).normalizeSelf();
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
            // Signs follow from body +X being the port side and +Y giving a
            // left yaw for a positive moment in this frame. Pitch was right;
            // roll and yaw were both inverted, so the stick rolled and the
            // pedals yawed the opposite way to the one asked for.
            addTorque(tmp3.x * pitchM, tmp3.y * pitchM, tmp3.z * pitchM);
            addTorque(tmp1.x * rollM, tmp1.y * rollM, tmp1.z * rollM);
            addTorque(tmp2.x * -yawM, tmp2.y * -yawM, tmp2.z * -yawM);

            // --- static stability. A real aeroplane weathercocks into the
            // airflow and tends to return to its trimmed angle of attack.
            // Both of these terms must oppose the disturbance. Getting either
            // sign backwards does not make the aeroplane merely twitchy, it
            // makes it divergent: it rotates, the moment grows with the angle
            // rather than resisting it, and it flips within a second.
            //
            // Fin: the airflow strikes the side it is slipping towards, the
            // fin pushes the tail the other way and the nose swings into
            // the wind.
            // Note the sign of sideslip: it is atan2(air . right, ...) and
            // right is body +X, which is the PORT side in this frame. So a
            // positive sideslip is a slip to the aeroplane's left, and the
            // fin restores it by yawing the nose left, which is a positive
            // moment about +Y here. Checked by letting go of a 11.5 degree
            // slip and watching it wash out rather than grow.
            double fin = sideslip * auth * 0.55;
            addTorque(tmp2.x * fin, tmp2.y * fin, tmp2.z * fin);
            // Tailplane: above the trim angle it pushes the nose back down.
            double pitchStab = (angleOfAttack - 0.035) * auth * 0.62;
            addTorque(tmp3.x * pitchStab, tmp3.y * pitchStab, tmp3.z * pitchStab);
            // Dihedral: the windward wing meets the air at a greater angle,
            // so the aeroplane rolls away from the slip. Same sign note as
            // the fin above.
            double dihedral = sideslip * auth * 0.20;
            addTorque(tmp1.x * dihedral, tmp1.y * dihedral, tmp1.z * dihedral);
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

        // --- undercarriage -------------------------------------------------------
        // Three contact points, not one. A single contact with a levelling
        // torque cannot be rotated: the levelling term scales with the normal
        // load, which is the whole weight of the aeroplane, so it swamps the
        // elevator and the nose never comes up. With a nosewheel ahead of the
        // centre of gravity and mains just behind it, rotation is what it
        // actually is - the elevator unloads the nosewheel, the aeroplane
        // pivots about the mains, and the wing reaches its flying angle.
        onGround = false;
        if (gearDown) {
            for (int g = 0; g < 3; g++) {
                // nose, left main, right main, in body coordinates
                double bx = g == 0 ? 0 : (g == 1 ? -1.9 : 1.9);
                double bz = g == 0 ? 2.35 : -0.42;
                tmp1.set(bx, -1.05, bz);
                pointToWorld(tmp1, tmp2);
                double gy = ground.heightAt(tmp2.x, tmp2.z);
                double pen = gy - tmp2.y;
                if (pen <= 0) { continue; }
                onGround = true;
                pointVelocity(tmp2, tmp3);
                double share = g == 0 ? 0.22 : 0.39;
                double n = Math.max(0, pen * mass * 46.0 * share
                        - tmp3.y * mass * 7.0 * share);
                tmp1.set(0, n, 0);
                addForceAtPoint(tmp1, tmp2);

                orientation.forward(tmp1);
                double vf = tmp3.dot(tmp1);
                double roll = -(0.019 + brakes * (g == 0 ? 0.0 : 0.62)) * n;
                if (Math.abs(vf) < 0.35) {
                    roll = -vf * mass * 2.2 * share;
                } else {
                    roll *= Math.signum(vf);
                }
                tmp1.scaleSelf(roll);
                addForceAtPoint(tmp1, tmp2);

                // Tyres resist sideways scrub, which is what keeps it tracking
                // down the centreline instead of weathercocking off the side.
                orientation.right(tmp1);
                double vr = tmp3.dot(tmp1);
                double side = -vr * mass * 2.6 * share;
                // The nosewheel steers at taxi speed.
                if (g == 0 && Math.abs(vf) < 24) {
                    side += rudderInput * n * 0.42
                            * MathUtil.clamp(Math.abs(vf) / 5.0, 0, 1);
                }
                tmp1.scaleSelf(side);
                addForceAtPoint(tmp1, tmp2);
            }
            // The tailcone. Over-rotate on the runway and it strikes, which
            // is the real limit on how hard you can haul back at rotation.
            tmp1.set(0, -0.35, -3.6);
            pointToWorld(tmp1, tmp2);
            double tailPen = ground.heightAt(tmp2.x, tmp2.z) - tmp2.y;
            if (tailPen > 0) {
                onGround = true;
                pointVelocity(tmp2, tmp3);
                double n = Math.max(0, tailPen * mass * 30.0 - tmp3.y * mass * 5.0);
                tmp1.set(0, n, 0);
                addForceAtPoint(tmp1, tmp2);
                addForce(-velocity.x * mass * 1.8, 0, -velocity.z * mass * 1.8);
            }
        } else {
            // Gear up on the surface is a belly landing, and it stops quickly.
            double pen = groundY + 0.85 - position.y;
            if (pen > 0) {
                onGround = true;
                addForce(0, Math.max(0, pen * mass * 24.0 - velocity.y * mass * 5.0), 0);
                addForce(-velocity.x * mass * 3.4, 0, -velocity.z * mass * 3.4);
            }
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
