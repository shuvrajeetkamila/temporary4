package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.math.MathUtil;
import ai.shuvrajeetkamila.mumbai.math.Vec3;

/**
 * A single main rotor helicopter with a tail rotor.
 *
 * <p>Thrust is not a number multiplied by the collective. It comes from blade
 * element momentum: for a given collective pitch and climb rate, solve for the
 * induced inflow through the disc, and the thrust follows. That fixed point is
 * mandatory, not an embellishment. Computing the thrust coefficient from
 * collective and climb rate alone, without letting the air the rotor is
 * already pulling down reduce the angle of attack, overestimates hover thrust
 * by roughly three and a half times, and the machine leaps off the pad at
 * about 2 g on half collective.
 *
 * <p>Everything else falls out of that:
 *
 * <p><b>Ground effect</b> — within about one rotor diameter of the surface the
 * induced velocity is suppressed, so the same collective makes more thrust.
 * This is why a heavy helicopter can hover in the cushion and sink when it
 * leaves it.
 *
 * <p><b>Translational lift</b> — in forward flight the rotor works on a larger
 * mass of undisturbed air each second, so induced inflow falls and thrust
 * rises. It arrives around 15 to 20 knots as a distinct lightening.
 *
 * <p><b>Torque</b> — the main rotor's reaction tries to yaw the fuselage the
 * other way, and the tail rotor cancels it. Lose the tail rotor's authority
 * and the machine spins.
 *
 * <p><b>Autorotation</b> — with the engine out, descending fast enough drives
 * the rotor from below and keeps rotor RPM up. Below that descent rate the
 * rotor decays and will not come back.
 */
public final class HelicopterPhysics extends RigidBody {

    public double rotorRadius = 5.35;
    public double bladeCount = 4;
    public double bladeChord = 0.27;
    /** Lift curve slope per radian for the blade section. */
    public double liftSlope = 5.73;
    public double bladeDragCoefficient = 0.011;
    public double nominalRotorRpm = 394;
    public double rotorInertia = 680;            // kg m^2
    /**
     * Torque the engine can deliver at the rotor shaft, N m.
     *
     * <p>Momentum theory for this machine: 2450 kg is 24.0 kN of thrust,
     * a 5.35 m rotor gives 90 m2 of disc, so induced velocity is 10.4 m/s
     * and ideal induced power 251 kW. At a figure of merit of 0.75 that is
     * 335 kW, and at 394 rpm (41.3 rad/s) about 8100 N m just to hover.
     *
     * <p>This was 3400, which is 140 kW - 42 per cent of what hovering
     * needs. The governor saturated immediately, rotor RPM drooped from
     * 394 to under 200, and the machine sank at 3 m/s with the collective
     * against the stop. It could not take off at all. 10200 N m is 421 kW,
     * a 1.25 margin over hover, which is the sort of power a 2.4 tonne
     * turbine single actually has.
     */
    public double maxEngineTorque = 10200;       // N m at the rotor shaft

    public double tailRotorRadius = 1.0;
    public double tailArm = 6.3;

    public double fuselageDragArea = 2.1;        // Cd * A

    // ---- inputs, all -1..1 except collective which is 0..1 ---------------
    public double collective;
    public double cyclicPitch;                    // + nose up
    public double cyclicRoll;                     // + roll right
    public double pedal;                          // + nose right
    public boolean engineOn = true;

    // ---- state / outputs -------------------------------------------------
    public double rotorRpm;
    public double inducedVelocity;
    public double thrust;
    public double groundEffectFactor = 1.0;
    public double translationalLift = 1.0;
    public boolean inAutorotation;
    public double torqueRequired;

    /** Blade pitch limits. The low stop sits slightly positive, not at zero. */
    public double minCollectivePitchDeg = 0.4;
    public double maxCollectivePitchDeg = 14.5;

    private final Vec3 tmp1 = new Vec3();
    private final Vec3 tmp2 = new Vec3();
    private final Vec3 tmp3 = new Vec3();

    public HelicopterPhysics() {
        mass = 2450;
        inertia.set(5200, 6400, 2900);
        rotorRpm = nominalRotorRpm;
    }

    public double discArea() { return Math.PI * rotorRadius * rotorRadius; }

    /** Rotor solidity: blade area over disc area. */
    public double solidity() {
        return bladeCount * bladeChord * rotorRadius / discArea();
    }

    /**
     * Solve the induced inflow fixed point and return thrust in newtons.
     *
     * <p>Blade element momentum theory. The thrust coefficient depends on the
     * inflow ratio, the inflow ratio depends on the thrust, so iterate until
     * they agree. Twenty passes of damped iteration converges comfortably for
     * every flight condition this machine can reach, including the vortex ring
     * state where the simple momentum solution misbehaves.
     */
    public double solveThrust(double collectivePitchRad, double climbRate,
                              double forwardSpeed, double rho, double omega) {
        double r = rotorRadius;
        double tipSpeed = omega * r;
        if (tipSpeed < 1.0) { return 0; }
        double sigma = solidity();
        double mu = forwardSpeed / tipSpeed;                 // advance ratio
        double lambdaC = climbRate / tipSpeed;               // climb inflow
        double lambda = Math.max(0.001, Math.sqrt(Math.max(1e-6,
                sigma * liftSlope / 16.0)));                 // first guess

        double ct = 0;
        for (int i = 0; i < 20; i++) {
            // Thrust coefficient for this inflow.
            ct = sigma * liftSlope / 2.0
                    * (collectivePitchRad / 3.0 * (1 + 1.5 * mu * mu)
                    - (lambda) / 2.0);
            if (ct < 0) { ct = 0; }
            // Momentum theory inflow for that thrust, including forward flight.
            double next;
            double vh2 = ct / 2.0;
            if (mu < 0.05) {
                next = -lambdaC / 2.0 + Math.sqrt(Math.max(0,
                        lambdaC * lambdaC / 4.0 + vh2));
            } else {
                next = vh2 / Math.sqrt(Math.max(1e-6, mu * mu
                        + (lambda + lambdaC) * (lambda + lambdaC)));
            }
            // Damped update, or the iteration oscillates near hover.
            lambda = lambda + 0.45 * (next - lambda);
            if (lambda < 0) { lambda = 0; }
        }
        inducedVelocity = lambda * tipSpeed;
        return ct * rho * discArea() * tipSpeed * tipSpeed;
    }

    public void step(double dt, GroundProbe ground, double windX, double windZ) {
        clearAccumulators();
        addForce(0, -mass * MathUtil.G, 0);

        double rho = MathUtil.airDensity(position.y);
        double omega = rotorRpm * 2 * Math.PI / 60.0;

        orientation.up(tmp1);
        orientation.forward(tmp2);
        orientation.right(tmp3);

        double groundY = ground.heightAt(position.x, position.z);
        double agl = position.y - groundY;

        // --- ground effect ----------------------------------------------------
        // Within a rotor diameter the ground stops the induced flow developing,
        // so the same pitch produces more thrust.
        double zR = MathUtil.clamp(agl / (2 * rotorRadius), 0.08, 2.0);
        groundEffectFactor = 1.0 + 0.38 * Math.exp(-3.2 * zR);

        // --- air-relative motion ----------------------------------------------
        double relX = velocity.x - windX;
        double relZ = velocity.z - windZ;
        double horizontal = Math.hypot(relX, relZ);
        double climb = velocity.y;

        // --- translational lift -----------------------------------------------
        // Above roughly 8 m/s the rotor meets enough new air that induced
        // losses fall away. Felt as a distinct lightening through 15-20 knots.
        translationalLift = 1.0 + 0.26 * MathUtil.smoothstep(2.0, 14.0, horizontal);

        double pitchRad = Math.toRadians(MathUtil.lerp(minCollectivePitchDeg,
                maxCollectivePitchDeg, MathUtil.clamp(collective, 0, 1)));

        thrust = solveThrust(pitchRad, climb, horizontal, rho, omega)
                * groundEffectFactor * translationalLift;
        if (!Double.isFinite(thrust) || thrust < 0) { thrust = 0; }

        // Thrust acts along the rotor shaft, which is the body's up axis
        // tilted by the cyclic. Tilting the disc is what moves a helicopter:
        // there is no separate forward force.
        double tiltF = MathUtil.clamp(cyclicPitch, -1, 1) * Math.toRadians(9.0);
        double tiltR = MathUtil.clamp(cyclicRoll, -1, 1) * Math.toRadians(9.0);
        tmp1.scaleSelf(Math.cos(tiltF) * Math.cos(tiltR));
        tmp1.addScaledSelf(tmp2, Math.sin(tiltF));
        tmp1.addScaledSelf(tmp3, Math.sin(tiltR));
        tmp1.normalizeSelf().scaleSelf(thrust);
        addForce(tmp1);

        // The disc is above the centre of mass, so a tilted disc also produces
        // the pitching and rolling moment that follows the stick.
        orientation.rotate(new Vec3(0, 1.4, 0), tmp1);
        tmp1.addSelf(position);
        orientation.forward(tmp2);
        orientation.right(tmp3);
        addTorque(tmp3.x * -cyclicPitch * thrust * 0.55,
                tmp3.y * -cyclicPitch * thrust * 0.55,
                tmp3.z * -cyclicPitch * thrust * 0.55);
        // Positive roll is right wing down, which is a positive moment
        // about the forward axis in this frame.
        addTorque(tmp2.x * cyclicRoll * thrust * 0.42,
                tmp2.y * cyclicRoll * thrust * 0.42,
                tmp2.z * cyclicRoll * thrust * 0.42);

        // --- rotor torque and the tail rotor ------------------------------------
        double ct = thrust / Math.max(1e-6, rho * discArea()
                * Math.pow(omega * rotorRadius, 2));
        double cq = solidity() * bladeDragCoefficient / 8.0
                + ct * inducedVelocity / Math.max(1e-6, omega * rotorRadius);
        torqueRequired = cq * rho * discArea() * Math.pow(omega * rotorRadius, 2)
                * rotorRadius;

        // The fuselage feels the reaction. Positive main rotor torque yaws the
        // nose one way and the tail rotor has to hold it.
        orientation.up(tmp1);
        addTorque(tmp1.x * -torqueRequired, tmp1.y * -torqueRequired,
                tmp1.z * -torqueRequired);

        // Neutral pedal must cancel the main rotor reaction, not 45 per
        // cent of it. With the engine too weak to hover this never showed,
        // because torqueRequired stayed small; with an engine that can
        // actually lift the machine, feet off the pedals span it up.
        double tailThrust = (1.0 + MathUtil.clamp(pedal, -1, 1) * 0.55)
                * torqueRequired / tailArm;
        // Tail rotor authority falls off in a strong crosswind from the wrong
        // side, which is the loss-of-tail-rotor-effectiveness pilots train for.
        orientation.right(tmp2);
        double sideWind = (windX - velocity.x) * tmp2.x + (windZ - velocity.z) * tmp2.z;
        double ltrFactor = 1.0 - 0.3 * MathUtil.smoothstep(4, 11, Math.abs(sideWind));
        double tailYaw = tailThrust * tailArm * ltrFactor;
        addTorque(tmp1.x * tailYaw, tmp1.y * tailYaw, tmp1.z * tailYaw);

        // --- rotor speed --------------------------------------------------------
        double engineTorque = 0;
        if (engineOn) {
            // Governor: holds the nominal RPM, within the engine's limits.
            double err = (nominalRotorRpm - rotorRpm) / nominalRotorRpm;
            engineTorque = MathUtil.clamp(torqueRequired + err * maxEngineTorque * 2.4,
                    0, maxEngineTorque);
        }
        // Autorotation: a fast enough descent drives the rotor from underneath.
        double autoTorque = 0;
        inAutorotation = !engineOn;
        if (inAutorotation) {
            // Below about 35 m/s of descent the rotor cannot sustain itself.
            double drive = MathUtil.remap(-climb, 8.0, 35.0, 0.0, 1.25);
            autoTorque = drive * torqueRequired
                    * MathUtil.clamp(1.4 - collective, 0.2, 1.4);
        }
        double netTorque = engineTorque + autoTorque - torqueRequired;
        rotorRpm += netTorque / rotorInertia * dt * 60.0 / (2 * Math.PI);
        rotorRpm = MathUtil.clamp(rotorRpm, 0, nominalRotorRpm * 1.22);

        // --- fuselage drag --------------------------------------------------------
        double v = Math.sqrt(relX * relX + velocity.y * velocity.y + relZ * relZ);
        if (v > 0.1) {
            double q = 0.5 * rho * v;
            addForce(-relX * q * fuselageDragArea, -velocity.y * q * fuselageDragArea * 1.6,
                    -relZ * q * fuselageDragArea);
        }
        addTorque(-angularVelocity.x * 2400, -angularVelocity.y * 1500,
                -angularVelocity.z * 2100);

        // --- skids ---------------------------------------------------------------
        if (agl < 1.25) {
            double pen = 1.25 - agl;
            double k = mass * 26.0;
            double c = mass * 5.2;
            double n = Math.max(0, pen * k - velocity.y * c);
            addForce(0, n, 0);
            // Friction so it does not slide off the pad.
            addForce(-velocity.x * mass * 1.7, 0, -velocity.z * mass * 1.7);
            // And level it out as it settles.
            orientation.up(tmp1);
            addTorque(-tmp1.z * n * 0.55, 0, tmp1.x * n * 0.55);
        }

        integrate(dt);
        sanitise(groundY);
    }

    public double rotorPercent() { return 100.0 * rotorRpm / nominalRotorRpm; }

    /** Thrust as a multiple of weight. Useful to tests and to the HUD. */
    public double thrustToWeight() { return thrust / (mass * MathUtil.G); }

    public interface GroundProbe {
        double heightAt(double x, double z);
    }
}
