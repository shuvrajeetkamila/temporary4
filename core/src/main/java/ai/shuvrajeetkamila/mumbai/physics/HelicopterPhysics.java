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
    /**
     * Shaft torque available, N m at the rotor.
     *
     * <p>At the nominal 394 rpm this is about 560 kW, or 750 shp, which
     * is the right order for a 2.45 t machine. Hovering costs roughly
     * 290 kW here - 250 of it induced - so the surplus buys a climb of
     * about 11 m/s, which is what a light helicopter actually does.
     * 10200 N m looked ample until the rotor torque started charging for
     * climb power, at which point it could only just hold a hover.
     */
    public double maxEngineTorque = 13600;       // N m at the rotor shaft

    public double tailRotorRadius = 1.0;
    public double tailArm = 6.3;

    /**
     * Yawing moment at full pedal, N m.
     *
     * <p>Sized against the yaw inertia and the yaw damping so that full
     * pedal settles at roughly sixty degrees a second, which is what a
     * light single will give you. This used to be 55 per cent of
     * whatever the main rotor happened to be drawing, which at hover
     * power is nearly three times as much.
     */
    public static final double PEDAL_AUTHORITY_NM = 2750;

    /**
     * Yaw damping, N m per rad/s.
     *
     * <p>Sets how quickly the nose settles after a pedal input. At 1500
     * the yaw time constant was nearly two seconds, so the machine kept
     * swinging long after the pedals were centred.
     */
    public static final double YAW_DAMPING = 2600;

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

    /**
     * Axial flow through the disc the solver actually used, m/s.
     *
     * <p>Equal to the real axial airspeed in normal flight, and
     * saturated at the edge of momentum theory in a steep descent.
     */
    public double axialInflow;
    public double thrust;
    public double groundEffectFactor = 1.0;
    public double translationalLift = 1.0;
    public boolean inAutorotation;
    public double torqueRequired;

    /**
     * True while the skids are taking weight.
     *
     * <p>Published because the control mapping needs it: the collective
     * springs back to the ground setting on the pad and to the hover
     * setting in the air, and those are different numbers.
     */
    public boolean onSkids = true;

    /** Blade pitch limits. The low stop sits slightly positive, not at zero. */
    /**
     * Collective that holds a hover at sea level, out of ground effect.
     *
     * <p>The control rests here when the lever is untouched, so letting
     * go of the stick holds the altitude instead of starting a descent.
     * {@code HelicopterTrimTest} flies the machine and fails if this
     * number stops being the hover.
     */
    public static final double HOVER_COLLECTIVE = 0.636;

    /**
     * Where the lever sits in a helicopter parked on its skids.
     *
     * <p>A machine sitting on a pad is not at the hover setting. It is
     * in ground effect, where the same blade pitch makes about 27 per
     * cent more thrust, so resting the lever at the hover value floated
     * the aircraft off the ground and up to seventeen metres before it
     * climbed out of the cushion and settled - with the player's hands
     * nowhere near the controls. You now have to raise the lever to go
     * flying, which is also how it works.
     */
    public static final double GROUND_COLLECTIVE = 0.40;

    /**
     * Disc flapback per metre per second of airspeed, as a fraction of
     * the full cyclic moment.
     *
     * <p>In forward flight the advancing blade meets more airspeed than
     * the retreating one. The difference flaps the disc backwards, which
     * pitches the nose up and is the whole of a helicopter's speed
     * stability. Without it a cyclic input is an acceleration that never
     * stops: a third of forward stick held for one second left this
     * machine doing 114 m/s five seconds after the stick was released,
     * because nothing ever brought the disc back. With it, a stick
     * position corresponds to a trim speed - full forward cyclic trims
     * at about {@code 0.55 / FLAPBACK} m/s - and centring the stick
     * pitches the nose up and slows down, which is how the aircraft is
     * actually flown.
     */
    public static final double FLAPBACK = 0.0076;

    /** The same effect sideways, where there is much less of it. */
    public static final double FLAPBACK_LATERAL = 0.014;

    /**
     * Rotor damping in pitch and roll, as a moment per radian per
     * second per newton of thrust.
     *
     * <p>A rotor disc lags the shaft it is driven by: rotate the
     * fuselage and the disc takes a moment to follow, and while it is
     * behind, the thrust vector opposes the rotation. It is the
     * dominant source of pitch and roll damping on a helicopter and it
     * scales with how hard the rotor is pulling, which plain angular
     * drag does not. Without it, adding flapback turned the runaway
     * into a phugoid that would not settle - half a radian a second of
     * pitch rate still swinging five seconds after the stick was
     * centred.
     */
    public static final double FLAP_DAMPING = 0.46;

    /**
     * How far the rotor hub sits above the centre of mass, metres.
     *
     * <p>This is the pendulum, and leaving it out was why the aircraft
     * could not be flown. Flapback alone gives the pitch loop feedback
     * from the integral of attitude - tilt makes speed, speed makes a
     * restoring moment - with no term proportional to attitude itself.
     * A third-order loop missing its first-order term is unstable for
     * any gain, which is exactly what the machine did: it tumbled
     * between seventy degrees nose down and seventy nose up at any
     * steady stick position, and no amount of damping helped because
     * damping is not the term that was missing.
     *
     * <p>A fuselage hung below the hub supplies it. The thrust acts at
     * the hub and the weight at the centre of mass, so a tilt away from
     * level is a restoring moment of {@code T * h * sin(tilt)}. With
     * the hub 1.4 m up, full cyclic trims at about 23 degrees of
     * attitude, which is roughly what a light helicopter will give you.
     */
    public static final double HUB_ABOVE_COM = 1.4;

    /**
     * How far the cyclic can tilt the disc off the shaft, radians.
     *
     * <p>A real swashplate gets a few degrees, and the fuselage then
     * follows the disc. Nine degrees of free tilt let the machine fly
     * at 230 km/h while sitting almost level - 2.6 degrees nose down at
     * full stick - which is not what a helicopter looks like. Five
     * degrees puts the work back on the airframe, so going fast means
     * putting the nose down, as it does in the real thing.
     */
    public static final double DISC_TILT_RAD = Math.toRadians(5.0);

    /**
     * Blade pitch at the bottom of the lever, degrees.
     *
     * <p>Not zero. Flat pitch on a real rotor still has enough angle on
     * it to keep the blades turning in an autorotation; at 0.4 degrees
     * this one made no thrust, the autorotative drive went with it, the
     * rotor decayed to 58 per cent and an engine failure with the lever
     * down was a 95 m/s free fall.
     */
    public double minCollectivePitchDeg = 2.0;

    /**
     * Blade pitch at the top of the lever, degrees.
     *
     * <p>Sized so that full lever asks for about what the engine can
     * actually deliver. At 14.5 degrees the top third of the travel was
     * past the torque limit: the governor saturated, rotor RPM drooped
     * to 82 per cent, the low-RPM warning sat on permanently and the
     * climb rate got WORSE the harder you pulled - 9.7 m/s at the stop
     * against 12.1 m/s a little below it.
     */
    public double maxCollectivePitchDeg = 12.2;

    private final Vec3 tmp1 = new Vec3();
    private final Vec3 tmp2 = new Vec3();
    private final Vec3 tmp3 = new Vec3();

    public HelicopterPhysics() {
        mass = 2450;
        inertia.set(5200, 6400, 2900);
        rotorRpm = nominalRotorRpm;
    }

    /** Back to a cold machine sitting on its skids, rotor at idle speed. */
    public void reset() {
        velocity.zero();
        angularVelocity.zero();
        orientation.identity();
        clearAccumulators();
        rotorRpm = nominalRotorRpm;
        onSkids = true;
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
        // Momentum theory is only valid down to about twice the hover
        // induced velocity in descent. Past that the rotor is in the
        // vortex-ring and then the windmill-brake state, where the simple
        // square root has no physical solution and the solver collapses
        // thrust to nothing. Left alone, an engine-off descent became a
        // free fall at 105 m/s instead of an autorotation at twelve.
        // Clamping the inflow at the edge of validity holds thrust at
        // roughly its hover value through the descent, which is both what
        // the empirical curves show and what makes autorotation flyable.
        // The real hover inflow for this collective, not sqrt(sigma a / 16)
        // - which is the coefficient in front of that expression, not the
        // answer, and came out 3.2 times too big. With it wrong the
        // windmill-brake clamp sat at a 67 m/s descent and never engaged.
        double k = solidity() * liftSlope / 16.0;
        double lambdaHover = k * (Math.sqrt(Math.max(0, 1.0
                + 24.0 * Math.max(0, collectivePitchRad) / Math.max(1e-9,
                        solidity() * liftSlope))) - 1.0);
        lambdaHover = Math.max(1e-4, lambdaHover);
        if (lambdaC < -2.0 * lambdaHover) { lambdaC = -2.0 * lambdaHover; }
        // What the solver settled on, for the torque equation. Charging
        // the rotor for the full descent rate while the thrust was
        // computed from the clamped one is a free energy source: in a
        // 70 m/s drop it drove the rotor to its 122 per cent limiter and
        // held it there. Past the edge of momentum theory the wake is
        // turbulent and the rotor is not being driven harder; the drive
        // saturates with the inflow.
        axialInflow = lambdaC * tipSpeed;
        double lambda = Math.max(0.001, Math.sqrt(Math.max(1e-6,
                sigma * liftSlope / 16.0)));                 // first guess

        double ct = 0;
        for (int i = 0; i < 20; i++) {
            // Thrust coefficient for this inflow.
            // Blade element theory wants the TOTAL inflow through the
            // disc, induced plus climb, because that is what sets the
            // angle the blade actually meets the air at. Using the
            // induced part alone is invisible in a hover - where the
            // climb inflow is zero and the two are identical - and wrong
            // everywhere else: too much thrust in a climb, and in a
            // descent the induced term grows while the climb term that
            // should cancel it is absent, so thrust collapses to nothing.
            // That is what turned an engine-off descent into a 105 m/s
            // free fall instead of an autorotation.
            ct = sigma * liftSlope / 2.0
                    * (collectivePitchRad / 3.0 * (1 + 1.5 * mu * mu)
                    - (lambda + lambdaC) / 2.0);
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

        double pitchRad = Math.toRadians(MathUtil.lerp(minCollectivePitchDeg,
                maxCollectivePitchDeg, MathUtil.clamp(collective, 0, 1)));

        // --- where the disc is pointing -----------------------------------------
        // Thrust acts along the rotor shaft, which is the body's up axis
        // tilted by the cyclic. Tilting the disc is what moves a helicopter:
        // there is no separate forward force.
        // Negated: cyclicPitch is the screen axis, where negative is the
        // stick pushed forward. Forward stick tilts the disc forward, so
        // the thrust vector has to gain a component along the nose.
        double tiltF = -MathUtil.clamp(cyclicPitch, -1, 1) * DISC_TILT_RAD;
        double tiltR = MathUtil.clamp(cyclicRoll, -1, 1) * DISC_TILT_RAD;
        tmp1.scaleSelf(Math.cos(tiltF) * Math.cos(tiltR));
        tmp1.addScaledSelf(tmp2, Math.sin(tiltF));
        tmp1.addScaledSelf(tmp3, Math.sin(tiltR));
        tmp1.normalizeSelf();
        double dnx = tmp1.x, dny = tmp1.y, dnz = tmp1.z;

        // --- flow through the disc ----------------------------------------------
        // The axial flow is the air-relative velocity resolved along the
        // DISC NORMAL, not the world vertical. That distinction is the
        // whole of a helicopter's speed behaviour and it was missing.
        //
        // Flying forward, the disc is tilted forward to pull the machine
        // along, so the oncoming air arrives from slightly above the disc
        // plane and blows down through it. That is axial flow in the same
        // sense as a climb, it cuts the blade's angle of attack, and it is
        // what stops thrust running away as speed builds. Taking only
        // velocity.y, the term vanished in level flight: at 40 m/s the
        // rotor made 163 per cent of the aircraft's weight, so pushing the
        // stick forward was a climb at 12 m/s and the machine could not be
        // flown level at any speed.
        //
        // In a hover, or in a pure vertical climb with the disc level,
        // this reduces exactly to velocity.y, which is what it used to be.
        double climb = relX * dnx + velocity.y * dny + relZ * dnz;
        // And the in-plane component is what the advance ratio is built
        // from - the speed the blades see sweeping through the air,
        // which is the airspeed with the axial part taken out.
        double planeX = relX - climb * dnx;
        double planeY = velocity.y - climb * dny;
        double planeZ = relZ - climb * dnz;
        double inPlane = Math.sqrt(planeX * planeX + planeY * planeY
                + planeZ * planeZ);

        // --- thrust -------------------------------------------------------------
        // Translational lift is NOT applied as a multiplier here. It is
        // already in the solver: forward speed raises the mass flow
        // through the disc, the induced inflow collapses, the blades meet
        // the air at a bigger angle and the thrust goes up. Multiplying
        // the solved thrust by a second 1.26 on top of that counted the
        // same effect twice.
        thrust = solveThrust(pitchRad, climb, inPlane, rho, omega)
                * groundEffectFactor;
        if (!Double.isFinite(thrust) || thrust < 0) { thrust = 0; }
        double vi = inducedVelocity;

        // Reported, not applied: how much of the thrust the rotor owes to
        // having flown out of its own downwash. The solver is called a
        // second time at zero airspeed for the comparison, so the induced
        // velocity it leaves behind has to be put back.
        double still = solveThrust(pitchRad, climb, 0, rho, omega)
                * groundEffectFactor;
        inducedVelocity = vi;
        translationalLift = still <= 1e-6 ? 1.0 : thrust / still;

        addForce(dnx * thrust, dny * thrust, dnz * thrust);

        // The disc is above the centre of mass, so a tilted disc also produces
        // the pitching and rolling moment that follows the stick.
        orientation.forward(tmp2);
        orientation.right(tmp3);
        // Measured, not assumed: a NEGATIVE moment about the right axis
        // raises the nose in this frame, and a negative one about the
        // forward axis drops the right wing. cyclicPitch is the screen
        // axis, so it is negative when the stick is forward, and forward
        // stick must drop the nose.
        double pitchMoment = -cyclicPitch * thrust * 0.55;
        // Roll was inverted: stick right banked the machine left. The
        // translation still went right, because the disc tilt below
        // dominates in the first half second, so it felt like a sloppy
        // control rather than a wrong one - and then the bank fought the
        // turn the whole way round.
        double rollMoment = -cyclicRoll * thrust * 0.42;

        // Flapback, which is the speed stability. The advancing blade
        // meets more airspeed than the retreating one and the disc
        // flaps back from the difference, pitching the nose up in
        // proportion to how fast the machine is going. Leaving it out
        // made every cyclic input an acceleration with no end: a third
        // of forward stick for one second left this aircraft doing
        // 114 m/s five seconds after the stick was centred, because
        // nothing ever brought the disc back to where it started. With
        // it, a stick position is a trim speed, and centring the stick
        // raises the nose and slows down.
        // The airspeed in the plane of the disc, which means the
        // vertical component counts too. Taking only the horizontal part
        // made the restoring moment fade out exactly when it was needed:
        // once the nose was down past about 45 degrees the forward axis
        // is mostly vertical, the dot product collapsed towards zero and
        // nothing was left to stop the pitch - so the machine tumbled
        // between 70 degrees nose down and 70 nose up and never
        // recovered.
        double fwdAir = relX * tmp2.x + velocity.y * tmp2.y + relZ * tmp2.z;
        double latAir = relX * tmp3.x + velocity.y * tmp3.y + relZ * tmp3.z;
        pitchMoment -= FLAPBACK * thrust * fwdAir;
        rollMoment += FLAPBACK_LATERAL * thrust * latAir;

        // The pendulum. tmp2.y is how far the nose is above level and
        // tmp3.y how far the right door is; a negative pitch moment
        // raises the nose and a negative roll moment drops the right
        // wing, so these are the signs that bring both back to level.
        pitchMoment += thrust * HUB_ABOVE_COM * tmp2.y;
        rollMoment -= thrust * HUB_ABOVE_COM * tmp3.y;

        // And the disc's lag behind the shaft, which damps the rate.
        double pitchRate = angularVelocity.x * tmp3.x
                + angularVelocity.y * tmp3.y + angularVelocity.z * tmp3.z;
        double rollRate = angularVelocity.x * tmp2.x
                + angularVelocity.y * tmp2.y + angularVelocity.z * tmp2.z;
        pitchMoment -= FLAP_DAMPING * thrust * pitchRate;
        rollMoment -= FLAP_DAMPING * thrust * rollRate;

        addTorque(tmp3.x * pitchMoment, tmp3.y * pitchMoment,
                tmp3.z * pitchMoment);
        // Positive roll is right wing down, which is a positive moment
        // about the forward axis in this frame.
        addTorque(tmp2.x * rollMoment, tmp2.y * rollMoment,
                tmp2.z * rollMoment);

        // --- rotor torque and the tail rotor ------------------------------------
        double ct = thrust / Math.max(1e-6, rho * discArea()
                * Math.pow(omega * rotorRadius, 2));
        // Induced + profile + CLIMB power. The climb term was missing, and
        // it is not a detail: the rotor must do work against gravity to
        // lift the machine, so leaving T Vc out of the torque is a free
        // energy source. The helicopter climbed at 148 m/s - about 7 MW of
        // work - on an engine that can supply 421 kW, and the governor
        // never drooped the rotor because it was never asked for the
        // torque. The term is signed on purpose: in a descent the airflow
        // drives the rotor instead, which is precisely what keeps the
        // blades turning in autorotation.
        // C_P = lambda * C_T + sigma Cd0 / 8, with lambda the TOTAL
        // inflow ratio and signed. In a climb it charges for the work
        // against gravity; in a descent it goes negative and the air
        // drives the rotor, which is autorotation falling out of the
        // same line that pays for a climb. Feeding it the clamped
        // inflow instead removed the drive altogether and every
        // engine-off descent became a 91 m/s free fall.
        double cq = solidity() * bladeDragCoefficient / 8.0
                + ct * (inducedVelocity + climb)
                        / Math.max(1e-6, omega * rotorRadius);
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
        //
        // The pedal's own authority is an absolute moment rather than a
        // fraction of the main rotor torque. As a fraction it scaled
        // with how hard the rotor was working - and it was far too much
        // of one: full pedal gave 134 degrees a second, so the machine
        // span like a top and a touch of pedal to line up a landing
        // threw it a quarter turn. A light helicopter does about sixty.
        double tailThrust = torqueRequired / tailArm
                + MathUtil.clamp(pedal, -1, 1) * PEDAL_AUTHORITY_NM / tailArm;
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
        // Autorotation needs no term of its own, and adding one was the
        // bug. The climb power in cq above is signed: in a descent the
        // air comes up through the disc, ct * (vi + climb) goes
        // negative, torqueRequired goes negative, and the rotor is being
        // driven by the airflow. That IS autorotation, straight out of
        // the same equation that charges for a climb.
        //
        // Bolting an explicit drive on top of it counted the same energy
        // twice: the rotor pinned at its 122 per cent limiter and the
        // machine still fell at 25 m/s, because overspeeding the rotor
        // does not hold an aircraft up. Removing it lets the rotor find
        // the speed at which the upflow and the blade drag balance,
        // which is what sets the real descent rate.
        inAutorotation = !engineOn;
        double netTorque = engineTorque - torqueRequired;
        rotorRpm += netTorque / rotorInertia * dt * 60.0 / (2 * Math.PI);
        rotorRpm = MathUtil.clamp(rotorRpm, 0, nominalRotorRpm * 1.22);

        // --- fuselage drag --------------------------------------------------------
        double v = Math.sqrt(relX * relX + velocity.y * velocity.y + relZ * relZ);
        if (v > 0.1) {
            double q = 0.5 * rho * v;
            addForce(-relX * q * fuselageDragArea, -velocity.y * q * fuselageDragArea * 1.6,
                    -relZ * q * fuselageDragArea);
        }
        addTorque(-angularVelocity.x * 2400, -angularVelocity.y * YAW_DAMPING,
                -angularVelocity.z * 2100);

        // --- skids ---------------------------------------------------------------
        // A Schmitt trigger, not a threshold. A machine resting on its
        // springs sits within millimetres of the contact height, so a
        // bare comparison flickers, and the control mapping hangs the
        // collective's rest position off this: one flickering frame was
        // enough to spring the lever to the hover setting and float a
        // parked aircraft off the pad untouched.
        if (agl < 1.25) { onSkids = true; } else if (agl > 2.5) { onSkids = false; }
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
