package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.math.MathUtil;
import ai.shuvrajeetkamila.mumbai.math.Vec3;

/**
 * A four-wheel road vehicle: independent suspension, a real driveline, and
 * tyres that make their forces out of slip.
 *
 * <p>Every force on the body comes from a wheel contact patch or from the air.
 * There is no "drive force" applied to the chassis, no velocity assignment, and
 * no steering that rotates the car directly. The car turns because the front
 * tyres are at a slip angle and generate lateral force; if you lock the brakes
 * mid-corner it understeers into the kerb, because that is what the slip
 * circle says should happen.
 *
 * <p>Two details are worth knowing because they are easy to get wrong and
 * expensive to debug:
 *
 * <p>Wheel spin is integrated against the <em>reflected</em> driveline inertia,
 * engine and gearbox rotating mass divided down through the square of the total
 * ratio, not the bare inertia of the wheel. With a wheel's own 1.2 kg m^2
 * against 700 N m of tyre torque, a 60 Hz step overshoots, the slip ratio
 * flips sign, and the whole thing oscillates to infinity inside a second. In
 * first gear the reflected inertia is forty times larger, which is both
 * physically right and numerically stable.
 *
 * <p>Longitudinal and lateral tyre forces are combined through a friction
 * ellipse. Treating them independently lets a tyre deliver full braking and
 * full cornering at once, which is how you get a car that can brake from
 * 100 km/h while turning at 1.4 g.
 *
 * <p>Traffic drives on the left, as it does in India.
 */
public final class CarPhysics extends RigidBody {

    /** Suspension and tyre state for one corner. */
    public static final class Wheel {
        public final Vec3 attach = new Vec3();     // body frame, top of travel
        public boolean steered;
        public boolean driven;
        public boolean handbrake;

        public double radius = 0.32;
        public double width = 0.21;
        public double inertia = 1.2;               // kg m^2, wheel alone

        public double restLength = 0.38;
        public double travel = 0.22;
        public double stiffness = 42000;           // N/m
        public double damperBump = 4200;           // N s/m
        public double damperRebound = 5400;

        // --- state ---
        public double compression;                 // 0 free, 1 fully compressed
        public double lastCompression;
        public double spin;                        // rad/s
        public double steerAngle;
        public double slipRatio;
        public double slipAngle;
        public boolean grounded;
        public double load;                        // N
        public double longForce;
        public double latForce;
        public double surfaceGrip = 1.0;

        public final Vec3 contact = new Vec3();
        public final Vec3 contactNormal = new Vec3(0, 1, 0);
    }

    public final Wheel[] wheels = new Wheel[4];

    // ---- driveline -------------------------------------------------------
    /** Gear ratios; index 0 is reverse, 1 is neutral. */
    public double[] gearRatios = {-3.40, 0.0, 3.55, 2.05, 1.38, 1.03, 0.82, 0.68};
    public double finalDrive = 4.06;
    public int gear = 1;
    public double engineRpm = 850;
    public double idleRpm = 850;
    public double maxRpm = 6600;
    public double redlineRpm = 6200;
    /** Peak crank torque, N m. Set by {@link #configure}. */
    public double peakTorqueNm = 196.0;

    /**
     * Accumulated crash damage, 0 pristine to 1 wrecked.
     *
     * <p>Costs engine torque and nothing else. A car that steers worse
     * the more you hit things is a car you cannot drive to a garage,
     * and there is no garage; losing power is a penalty you can still
     * finish a mission with.
     */
    public double damage;

    /** Damage at which the engine stops making useful power. */
    public static final double DAMAGE_POWER_LOSS = 0.55;

    /**
     * Take an impact at the given closing speed, m/s.
     *
     * <p>Below walking pace nothing happens, so kerbing the car or
     * nudging through a queue does not wear it out. The scale is set so
     * that a 50 km/h shunt into a stationary car is most of a wreck.
     */
    public void takeImpact(double closingMs) {
        if (closingMs <= 1.5) { return; }
        damage = MathUtil.clamp(damage + (closingMs - 1.5) / 26.0, 0, 1);
    }

    /** Fraction of engine torque still available, 1 pristine. */
    public double powerFraction() {
        return 1.0 - DAMAGE_POWER_LOSS * MathUtil.clamp(damage, 0, 1);
    }
    /** Which vehicle this currently is. Drives the mesh the renderer picks. */
    public VehicleKind kind = VehicleKind.SEDAN;
    /** Engine plus flywheel plus gearbox, kg m^2 at the crank. */
    public double engineInertia = 0.26;
    public double drivelineEfficiency = 0.88;
    public double shiftTimer;
    public double shiftDuration = 0.28;
    public boolean automatic = true;

    // ---- chassis ---------------------------------------------------------
    public double dragArea = 0.72;                 // Cd * A, m^2
    public double rollingResistance = 0.014;
    public double maxSteerAngle = Math.toRadians(33);
    public double brakeTorqueMax = 2600;           // N m per axle pair
    public double handbrakeTorque = 1800;
    public double wheelbase = 2.62;
    public double trackWidth = 1.52;
    /** Centre of mass height above the axle line; drives load transfer. */
    public double comHeight = 0.52;

    // ---- inputs ----------------------------------------------------------
    public double throttle;
    public double brake;
    public double steerInput;
    public boolean handbrakeOn;

    /** What the clutch can hold before it slips, N m at the crank. */
    public double clutchCapacity = 320;
    public double clutchEngagement;
    public boolean clutchSlipping;

    // ---- outputs ---------------------------------------------------------
    public double speedKph;
    public double wheelSlipMax;
    public boolean anyWheelGrounded;

    private final Vec3 tmp1 = new Vec3();
    private final Vec3 tmp2 = new Vec3();
    private final Vec3 tmp3 = new Vec3();
    private final Vec3 tmp4 = new Vec3();
    private double steerState;

    public CarPhysics() {
        mass = 1340;
        inertia.set(1750, 2100, 580);
        for (int i = 0; i < 4; i++) { wheels[i] = new Wheel(); }
        layoutWheels();
    }

    /**
     * Sizes springs, dampers, wheels and brakes for the vehicle's mass.
     *
     * <p>This used to be missing entirely, and it was the single worst
     * fault in the car model. Every vehicle inherited a mid-size saloon's
     * running gear: 42 kN/m springs, 0.32 m wheels and 2.6 kN m brakes.
     * A 12.4 t double-decker needs about 30 kN per corner, but a 42 kN/m
     * spring over 0.22 m of travel can only push back 9.2 kN, so the bus
     * bottomed out, sank through the road surface and could not exceed
     * 2.5 km/h. At the other end a 142 kg scooter sat on springs eight
     * times too stiff for it and was flung into the air by its own
     * suspension before it had moved.
     *
     * <p>Everything below is derived rather than tuned. Spring rate comes
     * from a target ride frequency, which is the parameter vehicle
     * engineers actually use: k = m (2 pi f)^2 per corner. Damping is
     * expressed as a fraction of critical, 2 sqrt(k m), so it means the
     * same thing on a scooter as on a bus. Brake torque is whatever locks
     * the wheels at about 1 g. The saloon's existing numbers fall out of
     * these formulae unchanged, which is how they were calibrated.
     */
    private void sizeRunningGear(VehicleKind k) {
        double corner = mass / 4.0;
        // Ride frequency, Hz. Soft and long-travel on something heavy,
        // stiff and short on two wheels. 1.78 Hz is what the saloon
        // already had.
        double rideHz = k.massKg > 5000 ? 1.15
                : k.isTwoWheeler() ? 2.55
                : k.isThreeWheeler() ? 2.15
                : 1.78;
        double w0 = 2 * Math.PI * rideHz;
        double kSpring = corner * w0 * w0;
        double cCritical = 2 * Math.sqrt(kSpring * corner);
        double travelM = k.massKg > 5000 ? 0.30
                : k.isTwoWheeler() ? 0.14
                : k.isThreeWheeler() ? 0.17
                : 0.22;
        double radius = k.wheelRadiusM();
        // Static deflection under the vehicle's own weight. The spring's
        // free length has to clear this, or it sits on the bump stop.
        double sag = 9.81 / (w0 * w0);
        // A wheel is closer to a ring than a disc once the tyre is on it,
        // so 0.65 m r^2 rather than 0.5. Too little rotational inertia is
        // what makes a loaded wheel numerically fragile against tyre
        // forces of tens of kilonewtons.
        double wheelMass = MathUtil.clamp(0.0134 * mass, 8, 150);

        for (Wheel w : wheels) {
            w.stiffness = kSpring;
            // Fractions of critical. Rebound is always the firmer of the
            // two, which is what stops a car floating after a crest.
            w.damperBump = 0.56 * cCritical;
            w.damperRebound = 0.72 * cCritical;
            w.travel = travelM;
            w.restLength = sag + 0.943 * radius;
            w.radius = radius;
            w.inertia = 0.65 * wheelMass * radius * radius;
        }

        // Enough torque to lock a wheel at roughly 1 g, per axle pair.
        brakeTorqueMax = 0.62 * mass * 9.81 * radius;
        handbrakeTorque = 0.69 * brakeTorqueMax;
    }

    /** Standard front-engine layout: front pair steered, rear pair driven. */
    public void layoutWheels() {
        double hw = trackWidth * 0.5;
        double hl = wheelbase * 0.5;
        wheels[0].attach.set(-hw, 0.08, hl);     // front left
        wheels[1].attach.set(hw, 0.08, hl);      // front right
        wheels[2].attach.set(-hw, 0.08, -hl);    // rear left
        wheels[3].attach.set(hw, 0.08, -hl);     // rear right
        wheels[0].steered = wheels[1].steered = true;
        wheels[2].driven = wheels[3].driven = true;
        wheels[2].handbrake = wheels[3].handbrake = true;
    }

    /**
     * Clears all transient state. Called when the player takes a vehicle
     * over, so the wheel speeds, clutch and gearbox of whatever was driven
     * last do not carry across into a different machine.
     */
    public void reset() {
        velocity.set(0, 0, 0);
        angularVelocity.set(0, 0, 0);
        orientation.identity();
        for (Wheel w : wheels) {
            w.spin = 0;
            w.compression = 0;
            w.load = 0;
            w.grounded = false;
        }
        engineRpm = idleRpm;
        gear = 2;
        shiftTimer = 0;
        clutchEngagement = 0;
        clutchSlipping = false;
        steerState = 0;
        throttle = brake = steerInput = 0;
        handbrakeOn = false;
    }

    /**
     * Re-specifies this as a different vehicle.
     *
     * <p>Everything that distinguishes a scooter from a double-decker is set
     * here, and nothing is faked afterwards: an autorickshaw tips into
     * corners because 340 kg carried 0.60 m up on a 1.40 m track genuinely
     * does tip, and the bus understeers because 12.4 tonnes on a 5.3 m
     * wheelbase genuinely does.
     *
     * <p>The inertia scalings are measured-vehicle corrections to the
     * uniform-box estimate. A real car's mass sits low and central, so it
     * pitches and yaws more readily than a solid block of the same size;
     * roll is close enough to the box value to leave alone.
     */
    public void configure(VehicleKind k) {
        kind = k;
        mass = k.massKg;
        inertia.set(k.pitchInertia() * 0.70, k.yawInertia() * 0.82, k.rollInertia());
        wheelbase = k.wheelbaseM;
        trackWidth = Math.max(0.52, k.widthM - 0.22);
        comHeight = k.comHeightM;
        peakTorqueNm = k.peakTorqueNm;
        redlineRpm = k.redlineRpm;
        maxRpm = k.redlineRpm * 1.09;
        dragArea = k.dragArea;

        // Reverse, neutral, then the forward gears spread geometrically from
        // a first tall enough to pull away to a top that reaches the
        // vehicle's terminal speed just under the limiter.
        int n = Math.max(3, k.gearCount);
        double[] g = new double[n + 2];
        g[0] = -3.40;
        g[1] = 0.0;
        double first = k.isTwoWheeler() ? 2.85 : (k.massKg > 5000 ? 5.60 : 3.55);
        double top = k.massKg > 5000 ? 0.92 : 0.68;
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0 : i / (double) (n - 1);
            g[i + 2] = first * Math.pow(top / first, t);
        }
        gearRatios = g;
        // Choose the final drive so the engine arrives at its limiter in
        // top gear at the vehicle's design speed, which is how real
        // gearing is picked. A single shared 4.06 geared a rickshaw for
        // 95 km/h and a bus for nearly 100.
        double topGear = g[g.length - 1];
        double vTop = k.topSpeedKph() / 3.6;
        // The ceiling has to leave room for a small wheel. A scooter on
        // 250 mm rims needs 12:1 to reach 85 km/h at its limiter, and
        // clamping it to 9 geared the machine for 63 km/h and then made it
        // crawl there in half a minute.
        finalDrive = MathUtil.clamp(
                k.redlineRpm * (Math.PI / 30.0) * k.wheelRadiusM()
                        / Math.max(1e-6, vTop * topGear), 2.2, 14.0);
        gear = 2;
        idleRpm = MathUtil.clamp(k.redlineRpm * 0.137, 550, 1500);
        engineRpm = idleRpm;
        // A clutch is sized to hold rather more than the engine can make.
        // Leaving this at a saloon's 320 N m meant a 620 N m bus engine
        // could never transmit its own torque: the clutch slipped for ever
        // and the bus would not pull away.
        clutchCapacity = 1.60 * peakTorqueNm;
        // Crank, flywheel and clutch, and it has to scale with the engine
        // rather than sit on a car-sized floor. The old 0.10 minimum gave a
        // 125 cc scooter a saloon's flywheel; reflected through its 35:1
        // first gear that was 132 kg m2 against the machine's own 8.9, so
        // nine tenths of a very small engine went into spinning itself up.
        engineInertia = MathUtil.clamp(0.004 + peakTorqueNm * 0.00112, 0.012, 1.4);

        // A bus cannot crank its wheels to a saloon's angle, and a
        // rickshaw turns inside anything on the road.
        maxSteerAngle = Math.toRadians(
                k.massKg > 5000 ? 26 : k.isThreeWheeler() ? 38 : 33);

        sizeRunningGear(k);
        layoutWheels();
        if (k.isThreeWheeler()) {
            // One wheel at the front, on the centreline. It steers and, on an
            // autorickshaw, it also drives.
            wheels[0].attach.set(0, 0.08, wheelbase * 0.5);
            wheels[1].attach.set(0, 0.08, wheelbase * 0.5);
            wheels[0].driven = wheels[1].driven = (k == VehicleKind.AUTORICKSHAW);
        } else if (k.isTwoWheeler()) {
            // Both "pairs" collapse onto the centreline. A scooter is not a
            // motorcycle simulator here: it is a narrow, light, twitchy car
            // that cannot be rolled over, which is the honest simplification.
            wheels[0].attach.set(0, 0.08, wheelbase * 0.5);
            wheels[1].attach.set(0, 0.08, wheelbase * 0.5);
            wheels[2].attach.set(0, 0.08, -wheelbase * 0.5);
            wheels[3].attach.set(0, 0.08, -wheelbase * 0.5);
        }
    }

    /**
     * Naturally aspirated torque curve, N m at the crank. A broad plateau from
     * about 2,600 to 4,800 rpm, falling away either side, with a hard cut at
     * the limiter.
     */
    public double engineTorque(double rpm, double throttlePos) {
        if (rpm > maxRpm) { return -0.2 * peakTorqueNm; }
        // The shape of a torque curve belongs to the engine, not to a
        // fixed set of rpm numbers. These breakpoints used to be absolute
        // - peak at 2600, falling past 4800 - which is a petrol saloon's
        // curve. A diesel bus that redlines at 2300 never reached the
        // plateau at all, and a scooter spinning to 7500 spent its whole
        // range past the drop-off. Everything is a fraction of redline
        // now, so each engine gets the same shape over its own range.
        double idleRef = Math.min(500, redlineRpm * 0.12);
        double r = MathUtil.clamp(rpm, idleRef, maxRpm);
        // A damaged engine makes less of it. Braking torque above the
        // limiter is deliberately not scaled: a wrecked car should not
        // also rev higher than a healthy one.
        double peak = peakTorqueNm * powerFraction();
        double rise = 0.42 * redlineRpm;      // peak torque arrives
        double mid = 0.58 * redlineRpm;
        double fall = 0.77 * redlineRpm;      // and starts to go away
        double halfWidth = 0.19 * redlineRpm;
        double t;
        if (r < rise) {
            t = peak * (0.44 + 0.56 * (r - idleRef) / Math.max(1, rise - idleRef));
        } else if (r < fall) {
            t = peak * (1.0 - 0.04 * Math.abs(r - mid) / Math.max(1, halfWidth));
        } else {
            t = peak * (0.96 - 0.34 * (r - fall) / Math.max(1, maxRpm - fall));
        }
        if (r > redlineRpm) {
            t *= MathUtil.clamp(1.0 - (r - redlineRpm) / (maxRpm - redlineRpm), 0, 1);
        }
        // Engine braking when closed. Also scaled by the engine's size:
        // a flat 16 + 0.0075 rpm is six times a 125 cc scooter's peak
        // torque at its redline, which braked it to a standstill.
        double drag = peak * (0.081 + 0.259 * r / maxRpm);
        return t * throttlePos - drag * (1.0 - throttlePos);
    }

    public double totalRatio() {
        return gearRatios[gear] * finalDrive;
    }

    /**
     * Magic-formula style tyre. Returns normalised force for a given
     * normalised slip, peaking a little past the linear region and settling to
     * a lower plateau once the tyre is sliding, which is what gives a slide its
     * characteristic feel of losing bite and then stabilising.
     */
    static double tyreCurve(double slip, double peak, double shape) {
        double s = Math.abs(slip);
        double f;
        if (s < peak) {
            f = Math.sin(Math.PI * 0.5 * s / peak);
        } else {
            double over = (s - peak) / (peak * 4.0 + 1e-6);
            f = 1.0 - shape * (1.0 - Math.exp(-over * 2.2));
        }
        return Math.signum(slip) * MathUtil.clamp(f, 0, 1);
    }

    /**
     * Grip multiplier for a surface and a wetness. Monsoon Mumbai is a real
     * driving condition, not a cosmetic filter.
     */
    public static double surfaceGrip(boolean unpaved, double wetness) {
        double base = unpaved ? 0.62 : 1.0;
        return base * (1.0 - 0.32 * MathUtil.clamp(wetness, 0, 1));
    }

    /** How far over a rider will go before she runs out of nerve and tyre. */
    private static final double MAX_LEAN = Math.toRadians(40);

    /**
     * A rider leans; a car body rolls. They are not the same thing.
     *
     * <p>Both of a two-wheeler's axles sit on the centreline here, which is
     * the honest way to model a single track - but it also means the
     * suspension supplies no roll stiffness whatever. Left to itself the
     * scooter flopped twenty-seven degrees to the <em>outside</em> of the
     * first corner, tilting its contact patch until it was cornering at a
     * quarter of a gravity: less than a loaded double-decker, and nothing
     * like a machine that spends its life filtering through traffic.
     *
     * <p>What actually holds a two-wheeler up is the rider. She leans in
     * until the resultant of weight and cornering force runs straight down
     * through the tyres, which is at {@code tan(lean) = a_lat / g}. So that
     * is what this is: a control loop with a rider's bandwidth, commanding
     * the lean the corner asks for and nothing more. Below walking pace she
     * gives up and puts a foot down, so the target fades out rather than
     * leaving a parked scooter lying on its side.
     */
    private void leanWithTheRider() {
        orientation.forward(tmp1);                // the roll axis, nose to tail
        double v = groundSpeed();
        // The lateral acceleration actually being pulled. Yaw rate is
        // negative in a right-hand turn in this frame, and a right-hand
        // turn wants the right side down, so the sign flips once here.
        double want = Math.atan2(-v * angularVelocity.y, MathUtil.G);
        want = MathUtil.clamp(want, -MAX_LEAN, MAX_LEAN)
                * MathUtil.remap(v, 0.8, 3.2, 0.0, 1.0);
        double err = MathUtil.wrapPi(want - orientation.roll());
        double rate = angularVelocity.dot(tmp1);

        // Angular acceleration first, torque second. The integrator divides
        // torque by a diagonal inertia in world axes, so the inertia about
        // a tilted roll axis is 1 / sum(n^2 / I) - without that the same
        // gains would behave like a scooter heading north and like a bus
        // heading east.
        double wn = 2 * Math.PI * 1.5;
        double accel = wn * wn * err - 2 * 0.95 * wn * rate;
        double invEff = tmp1.x * tmp1.x / inertia.x
                + tmp1.y * tmp1.y / inertia.y
                + tmp1.z * tmp1.z / inertia.z;
        // Feed the known disturbance forward. The cornering force acts at
        // the road and the mass is up at the saddle, so there is a steady
        // overturning moment the whole time the corner lasts; a plain PD
        // can only answer it with a standing error, which pinned the lean
        // at 26 degrees when the corner was asking for 44. The rider knows
        // perfectly well how hard she is cornering.
        double ff = mass * (-v * angularVelocity.y) * comHeight;
        double tq = accel / Math.max(1e-9, invEff) + ff;
        double limit = mass * MathUtil.G * wheelbase;
        tq = MathUtil.clamp(tq, -limit, limit);
        addTorque(tmp1.x * tq, tmp1.y * tq, tmp1.z * tq);
    }

    /** 0 dry, 1 roof under. Drives buoyancy, drag and the drowned engine. */
    public double submerged;

    /** True once the intake is under and the engine has stopped. */
    public boolean drowned;

    /** Seconds the body has been more than half under. */
    public double sinkTime;

    /**
     * What happens when you drive into the sea.
     *
     * <p>A car floats, badly, for a few seconds, and then it does not. It
     * is close to the density of water with the doors shut, so the
     * displaced volume here is sized to put it just on the wrong side of
     * floating; the drag is what actually stops it, and the engine dies
     * when the intake goes under whatever the wheels think they are
     * resting on.
     */
    private void stepWater(double dt, GroundProbe ground) {
        double sea = ground.waterLevelAt(position.x, position.z);
        if (sea == Double.NEGATIVE_INFINITY) {
            submerged = 0;
            drowned = false;
            sinkTime = 0;
            return;
        }
        double hull = Math.max(1.0, kind == null ? 1.45 : kind.heightM);
        double floorY = position.y - hull * 0.35;
        submerged = MathUtil.clamp((sea - floorY) / hull, 0, 1);
        if (submerged <= 0) {
            drowned = false;
            sinkTime = 0;
            return;
        }
        sinkTime += submerged > 0.5 ? dt : -dt;
        if (sinkTime < 0) { sinkTime = 0; }

        // Buoyancy on the submerged fraction. 1040 rather than 1000 for
        // seawater, and a displacement that leaves her slightly heavy, so
        // she wallows and then goes down instead of bobbing for ever.
        double displaced = (mass / 1040.0) * 0.92 * submerged;
        addForce(0, MathUtil.RHO_SEA * MathUtil.G * displaced, 0);

        // Water drag. This is the thing that actually stops the car.
        double cd = 3.4 * submerged;
        tmp1.set(velocity).scaleSelf(-mass * cd);
        addForce(tmp1);
        addTorque(-angularVelocity.x * mass * 0.9 * submerged,
                -angularVelocity.y * mass * 0.9 * submerged,
                -angularVelocity.z * mass * 0.9 * submerged);

        // Intake under: the engine stops and does not restart.
        if (submerged > 0.42) { drowned = true; }
    }

    /** One fixed-step update. {@code ground} supplies height and normal. */
    public void step(double dt, GroundProbe ground, double wetness) {
        clearAccumulators();

        // --- gravity --------------------------------------------------------
        addForce(0, -mass * MathUtil.G, 0);

        // --- steering, rate limited so a tap does not snap the wheel over ---
        double target = MathUtil.clamp(steerInput, -1, 1);
        // Less lock at speed, or the car is undriveable above 60 km/h.
        double speedFactor = MathUtil.remap(groundSpeed(), 6, 42, 1.0, 0.36);
        steerState = MathUtil.approach(steerState, target * speedFactor, 9.0, dt);
        for (Wheel w : wheels) {
            // Negated because body +X is the car's left in this frame, so
            // a positive angle about it points the wheels to the driver's
            // left. Pushing the stick right used to turn the car left.
            if (w.steered) { w.steerAngle = -steerState * maxSteerAngle; }
        }

        // --- suspension and tyres -------------------------------------------
        anyWheelGrounded = false;
        wheelSlipMax = 0;
        double drivenLoad = 0;
        for (Wheel w : wheels) {
            stepWheel(w, dt, ground, wetness);
            if (w.grounded) { anyWheelGrounded = true; }
            if (w.driven && w.grounded) { drivenLoad += w.load; }
            wheelSlipMax = Math.max(wheelSlipMax, Math.abs(w.slipRatio));
        }

        // --- driveline ------------------------------------------------------
        stepDriveline(dt);

        // --- aerodynamic drag and lift --------------------------------------
        double v = speed();
        if (v > 0.1) {
            double rho = MathUtil.airDensity(position.y);
            double q = 0.5 * rho * v * v;
            tmp1.set(velocity).normalizeSelf().scaleSelf(-q * dragArea);
            addForce(tmp1);
        }

        // --- water ------------------------------------------------------------
        stepWater(dt, ground);

        // --- the rider leans --------------------------------------------------
        if (kind != null && kind.isTwoWheeler()) { leanWithTheRider(); }

        // --- yaw damping ------------------------------------------------------
        // A real car's tyres resist yaw rate through their own slip; the four
        // contact patches here do most of that, but a small explicit term keeps
        // the integrator calm at low speed where slip angles are ill-conditioned.
        addTorque(-angularVelocity.x * 900, -angularVelocity.y * 520,
                -angularVelocity.z * 900);

        integrate(dt);
        speedKph = groundSpeed() * 3.6;
        sanitise(position.y);
    }

    private void stepWheel(Wheel w, double dt, GroundProbe ground, double wetness) {
        pointToWorld(w.attach, tmp1);
        orientation.up(tmp2);

        // Cast straight down from the attachment through the full travel.
        double rayLength = w.restLength + w.travel + w.radius;
        double gy = ground.heightAt(tmp1.x, tmp1.z);
        ground.normalAt(tmp1.x, tmp1.z, tmp3);
        double bottom = tmp1.y - rayLength;

        if (gy <= bottom) {
            w.grounded = false;
            w.load = 0;
            w.longForce = 0;
            w.latForce = 0;
            w.lastCompression = w.compression;
            w.compression = MathUtil.approach(w.compression, 0, 12, dt);
            // Free wheel: only driveline torque and a little bearing drag.
            w.spin *= Math.exp(-0.6 * dt);
            return;
        }

        w.grounded = true;
        double penetration = gy - bottom;
        double springLength = MathUtil.clamp(rayLength - w.radius - penetration,
                w.restLength - w.travel, w.restLength);
        w.lastCompression = w.compression;
        w.compression = MathUtil.clamp((w.restLength - springLength) / w.travel, 0, 1.6);

        w.contact.set(tmp1.x, gy, tmp1.z);
        w.contactNormal.set(tmp3);

        // Spring force along the contact normal, damper against the normal
        // component of the contact-point velocity.
        double compressionM = (w.restLength - springLength);
        double springF = w.stiffness * compressionM;
        pointVelocity(w.contact, tmp4);
        double normalVel = tmp4.dot(w.contactNormal);
        double damp = normalVel < 0 ? w.damperBump : w.damperRebound;
        double damperF = -damp * normalVel;
        // Bump stop: the last two centimetres get very stiff indeed.
        if (compressionM > w.travel) {
            springF += (compressionM - w.travel) * w.stiffness * 14.0;
        }
        double normalF = Math.max(0, springF + damperF);
        w.load = normalF;

        tmp2.set(w.contactNormal).scaleSelf(normalF);
        addForceAtPoint(tmp2, w.contact);

        // --- tyre frame ------------------------------------------------------
        orientation.forward(tmp1);
        orientation.right(tmp2);
        double ca = Math.cos(w.steerAngle), sa = Math.sin(w.steerAngle);
        double fx = tmp1.x * ca + tmp2.x * sa;
        double fy = tmp1.y * ca + tmp2.y * sa;
        double fz = tmp1.z * ca + tmp2.z * sa;
        double rx = tmp2.x * ca - tmp1.x * sa;
        double ry = tmp2.y * ca - tmp1.y * sa;
        double rz = tmp2.z * ca - tmp1.z * sa;

        double vLong = tmp4.x * fx + tmp4.y * fy + tmp4.z * fz;
        double vLat = tmp4.x * rx + tmp4.y * ry + tmp4.z * rz;

        // Slip ratio against the larger of the two speeds, so it stays
        // well-conditioned from standstill through to motorway speed.
        double wheelSurfaceSpeed = w.spin * w.radius;
        double denom = Math.max(1.6, Math.max(Math.abs(vLong), Math.abs(wheelSurfaceSpeed)));
        w.slipRatio = (wheelSurfaceSpeed - vLong) / denom;
        w.slipAngle = Math.atan2(-vLat, Math.max(1.2, Math.abs(vLong)));

        boolean unpaved = ground.isUnpaved(w.contact.x, w.contact.z);
        w.surfaceGrip = surfaceGrip(unpaved, wetness);
        double mu = 1.18 * w.surfaceGrip;
        if (kind != null && kind.isTwoWheeler()) {
            // A leaning machine cannot use more grip than its lean angle
            // supports: pull harder than tan(lean) and the resultant walks
            // outside the contact patch and you are on the floor. With the
            // rider's limit at forty degrees that caps her at about 0.84 g,
            // which is also roughly what two narrow contact patches can do.
            // Without it the scooter out-cornered every car in the game.
            mu = Math.min(mu, Math.tan(MAX_LEAN) * 0.99);
        }

        double fLong = tyreCurve(w.slipRatio, 0.14, 0.26) * mu * normalF;
        double fLat = tyreCurve(w.slipAngle / Math.toRadians(9.0), 1.0, 0.22)
                * mu * normalF;

        // Friction ellipse: one patch cannot do both jobs at full strength.
        double total = Math.hypot(fLong, fLat);
        double limit = mu * normalF;
        if (total > limit && total > 1e-6) {
            double k = limit / total;
            fLong *= k;
            fLat *= k;
        }
        w.longForce = fLong;
        w.latForce = fLat;

        tmp3.set(fx * fLong + rx * fLat, fy * fLong + ry * fLat, fz * fLong + rz * fLat);
        addForceAtPoint(tmp3, w.contact);

        // Rolling resistance, always opposing motion.
        if (Math.abs(vLong) > 0.05) {
            double rr = -Math.signum(vLong) * rollingResistance * normalF;
            tmp3.set(fx * rr, fy * rr, fz * rr);
            addForceAtPoint(tmp3, w.contact);
        }

        // React the tyre's longitudinal force back onto the wheel's spin.
        //
        // Friction can bring the wheel to the free-rolling speed and no
        // further. Letting it overshoot is what made every heavy vehicle
        // undriveable: a 12 t bus loads its driven wheels to 30 kN, which
        // against a slipping clutch's bare 13 kg m^2 is a 16 rad/s
        // correction in one 60 Hz step. That jumps clean over the rolling
        // point, the slip ratio flips sign, and the next step pulls just
        // as hard the other way. The bus sat at 0.1 m/s with its tyres
        // alternately driving at +25 kN and braking at -31 kN. Landing
        // exactly on free rolling is the standard Coulomb treatment.
        double inertiaW = Math.max(0.35, effectiveWheelInertia(w));
        double dSpin = -fLong * w.radius / inertiaW * dt;
        // Semi-implicit, because this term is stiff. The tyre's chord
        // stiffness d(force)/d(slip) can reach 25 kN per unit slip under a
        // loaded bus wheel, and an explicit step against a bare wheel
        // inertia jumps clean over the rolling point: the slip ratio flips
        // sign and the next step pulls as hard the other way. The bus sat
        // at 0.1 m/s with its tyres alternately driving at +25 kN and
        // braking at -31 kN.
        //
        // Dividing by (1 + B) is one Newton step of a backward-Euler
        // solve on the same update. It is deliberately a chord rather than
        // a tangent: B collapses to zero whenever the wheel is near
        // rolling, so a well-conditioned wheel is left exactly as it was
        // and only a genuinely stiff one is damped. An earlier attempt
        // clamped the spin to the rolling point instead, which also killed
        // the small steady-state slip a tyre needs to make any force at
        // all, and left every light car unable to pass 7 km/h.
        double chordStiffness = Math.abs(fLong) / Math.max(0.02, Math.abs(w.slipRatio));
        double b = w.radius * w.radius * dt * chordStiffness
                / Math.max(1e-6, inertiaW * denom);
        w.spin += dSpin / (1.0 + Math.max(0, b));
    }

    /**
     * Wheel inertia including whatever driveline is attached to it.
     *
     * <p>Reflected through the square of the total ratio, which is the whole
     * point: in first gear the engine's 0.26 kg m^2 looks like 53 kg m^2 at the
     * wheel, and that is what stops a 60 Hz explicit step from blowing up.
     */
    private double effectiveWheelInertia(Wheel w) {
        if (!w.driven || gear == 1 || shiftTimer > 0) { return w.inertia; }
        // Reflecting the engine's inertia through the square of the ratio is
        // what keeps the wheel spin stable: against roughly 700 N m the bare
        // 1.2 kg m^2 of a wheel diverges at 60 Hz. But it only applies when
        // the clutch is locked. While it slips the engine is not rigidly
        // attached to the wheel and its inertia must not be counted.
        if (clutchSlipping) { return w.inertia + engineInertia * 2.5; }
        double ratio = totalRatio();
        return w.inertia + engineInertia * ratio * ratio;
    }

    private void stepDriveline(double dt) {
        if (shiftTimer > 0) {
            shiftTimer -= dt;
        }

        // Average driven-wheel spin defines engine speed through the ratios.
        double spinSum = 0;
        int n = 0;
        for (Wheel w : wheels) {
            if (w.driven) { spinSum += w.spin; n++; }
        }
        double drivenSpin = n > 0 ? spinSum / n : 0;

        double ratio = totalRatio();
        boolean engaged = gear != 1 && shiftTimer <= 0;

        // --- the clutch -------------------------------------------------------
        // Without one, pulling away is a mess. Holding the engine at idle while
        // feeding its full torque through a 14:1 first gear drives the wheels
        // faster than the car is going, the tyre saturates, the slip ratio
        // flips sign every step and the launch chatters away a second and a
        // half. A real clutch slips instead: it transmits what it can hold and
        // the engine revs on the difference, which is what makes a standing
        // start smooth.
        // A drowned engine makes no torque, whatever the pedal says.
        double crankTorque = drowned ? -14.0
                : engineTorque(engineRpm, MathUtil.clamp(throttle, 0, 1));
        double transmitted = 0;
        if (engaged) {
            double inputRpm = Math.abs(drivenSpin * ratio) * 60.0 / (2 * Math.PI);
            // The driver lets the pedal up as the input speed comes up to idle.
            // The driver lets the pedal all the way up by about 15 km/h.
            clutchEngagement = MathUtil.clamp(Math.abs(forwardSpeed()) / 4.2, 0.26, 1.0);
            clutchSlipping = inputRpm < idleRpm * 0.98 || engineRpm > inputRpm + 60;
            if (clutchSlipping) {
                double capacity = clutchCapacity * clutchEngagement;
                transmitted = MathUtil.clamp(crankTorque, -capacity, capacity);
                // The surplus spins the engine up, which is the launch flare.
                double net = crankTorque - transmitted;
                engineRpm += net / engineInertia * dt * 60.0 / (2 * Math.PI);
                // It cannot be dragged below idle: that is the point of slipping.
                engineRpm = MathUtil.clamp(engineRpm, Math.max(idleRpm, inputRpm * 0.6),
                        maxRpm);
            } else {
                // Locked up: the engine simply follows the gearbox.
                clutchEngagement = 1.0;
                engineRpm = MathUtil.lerp(engineRpm, Math.max(idleRpm, inputRpm), 0.45);
                transmitted = crankTorque;
            }
        } else if (shiftTimer > 0) {
            // Mid-shift the clutch is out, but the throttle is cut with it.
            // Letting the engine rev freely here is what produced a shift
            // cascade: it hit the redline during the change, the gearbox saw
            // the redline and changed up again, and the car walked from second
            // to top in half a second while slowing down.
            clutchSlipping = true;
            clutchEngagement = 0;
            double nextInput = Math.abs(drivenSpin * ratio) * 60.0 / (2 * Math.PI);
            engineRpm = MathUtil.lerp(engineRpm, Math.max(idleRpm, nextInput),
                    MathUtil.clamp(dt / Math.max(dt, shiftTimer), 0.08, 1.0));
        } else {
            clutchSlipping = true;
            clutchEngagement = 0;
            double free = engineRpm + (throttle * 5200 - 900) * dt * 1.8;
            engineRpm = MathUtil.clamp(free, idleRpm, maxRpm);
        }
        engineRpm = MathUtil.clamp(engineRpm, idleRpm * 0.6, maxRpm);

        double wheelTorque = engaged
                ? transmitted * ratio * drivelineEfficiency / Math.max(1, n) : 0;

        for (Wheel w : wheels) {
            double inertiaEff = effectiveWheelInertia(w);
            if (w.driven && engaged) {
                // A slipping clutch is a friction element. It can drag the
                // wheel up to the speed the engine is turning and no
                // further - once the two match it locks, by definition.
                // Without that limit the full clutch capacity multiplied by
                // a 29:1 first gear lands on a bare wheel inertia and spins
                // the wheel by 15 rad/s in a single 60 Hz step. The tyre
                // saturates, the slip ratio flips sign every step, and
                // every heavy vehicle sits in a permanent standing burnout
                // at walking pace: the double-decker could not pass
                // 3.5 km/h. Clamping to the synchronisation torque is the
                // standard semi-implicit treatment and costs nothing.
                double applied = wheelTorque;

                // Engine braking is a brake, not a reverse gear. A closed
                // throttle produces negative crank torque, and feeding
                // that straight through the ratios applied it in a fixed
                // direction regardless of which way the wheel was
                // actually turning. From rest it spun the wheels
                // backwards, so every car idled its way into reverse and
                // kept accelerating - the saloon reached 4.1 m/s
                // backwards with the throttle shut and showed no sign of
                // stopping. Overrun torque may only oppose rotation, and
                // may not reverse it within a step.
                boolean overrun = wheelTorque * w.spin < 0
                        || (w.spin == 0 && wheelTorque < 0 && throttle < 0.02);
                if (overrun) {
                    double toStop = Math.abs(w.spin) * inertiaEff / dt;
                    applied = -Math.signum(w.spin)
                            * Math.min(Math.abs(wheelTorque), toStop);
                }

                if (clutchSlipping && Math.abs(ratio) > 1e-6) {
                    double sgn = w.spin != 0 ? Math.signum(w.spin)
                            : Math.signum(wheelTorque);
                    double syncOmega = sgn * Math.abs(engineRpm)
                            * (Math.PI / 30.0) / Math.abs(ratio);
                    double syncTorque = (syncOmega - w.spin) * inertiaEff / dt;
                    if (applied * syncTorque > 0
                            && Math.abs(applied) > Math.abs(syncTorque)) {
                        applied = syncTorque;
                    }
                }
                w.spin += applied / inertiaEff * dt;
            }
            // Brakes. Torque opposes spin and cannot reverse it within a step.
            double bt = brake * brakeTorqueMax * (w.steered ? 0.62 : 0.38);
            if (w.handbrake && handbrakeOn) { bt += handbrakeTorque; }
            if (bt > 0) {
                double dw = bt / inertiaEff * dt;
                if (Math.abs(w.spin) <= dw) {
                    w.spin = 0;
                } else {
                    w.spin -= Math.signum(w.spin) * dw;
                }
            }
            if (!Double.isFinite(w.spin)) { w.spin = 0; }
            w.spin = MathUtil.clamp(w.spin, -400, 400);
        }

        if (automatic) { autoShift(); }
    }

    private void autoShift() {
        if (shiftTimer > 0) { return; }
        double fwd = forwardSpeed();
        if (brake > 0.6 && Math.abs(fwd) < 0.6 && throttle < 0.05) {
            gear = 1;
            return;
        }
        if (throttle > 0.1 && gear == 1 && fwd > -0.5) {
            gear = 2;
            shiftTimer = shiftDuration;
            return;
        }
        // Never change up on engine speed alone. With the clutch slipping at
        // a standing start the engine is at the redline while the car has not
        // moved, and an rpm-only rule walks it to top gear on the spot.
        if (shiftTimer > 0) { return; }
        // The slipping-clutch guard must only block changes UP. Blocking
        // changes down as well leaves the car coasting to a halt in top.
        boolean mayGoUp = !(clutchSlipping && Math.abs(fwd) < 6.0);
        // Shift points are fractions of redline for the same reason the
        // torque curve is: a fixed 1500 rpm downshift is a quarter of a
        // saloon's range but two thirds of a bus's, which had the bus
        // hunting between gears continuously.
        if (mayGoUp && gear >= 2 && engineRpm > redlineRpm * 0.96
                && gear < gearRatios.length - 1) {
            gear++;
            shiftTimer = shiftDuration;
        } else if (gear > 2
                && (engineRpm < redlineRpm * 0.242 || Math.abs(fwd) < 2.0)) {
            // Drop straight to the gear that suits the road speed rather than
            // walking down one at a time. Stepping down singly means a hard
            // stop from the motorway ends with the car stationary in fifth,
            // because each change costs the shift interlock and the car runs
            // out of road first. A real automatic kicks down several at once.
            // Walk up from first and keep the tallest gear that still turns
            // the engine fast enough to pull. Index 2 is first, and the ratio
            // falls as the index rises, so first is always a valid answer and
            // at a crawl it is the only one.
            int want = 2;
            for (int g = 3; g < gearRatios.length; g++) {
                double rpm = Math.abs(fwd) / wheels[0].radius
                        * gearRatios[g] * finalDrive * 60.0 / (2 * Math.PI);
                if (rpm > redlineRpm * 0.29) { want = g; } else { break; }
            }
            gear = MathUtil.clampi(Math.min(want, gear - 1), 2, gearRatios.length - 1);
            shiftTimer = shiftDuration;
        }
    }

    /** Engage reverse. */
    public void selectReverse() {
        if (Math.abs(forwardSpeed()) < 1.5) {
            gear = 0;
            shiftTimer = shiftDuration;
        }
    }

    public String gearLabel() {
        if (gear == 0) { return "R"; }
        if (gear == 1) { return "N"; }
        return Integer.toString(gear - 1);
    }

    /** What the ground looks like to a wheel. */
    public interface GroundProbe {
        double heightAt(double x, double z);

        /**
         * Water surface here, or {@link Double#NEGATIVE_INFINITY} on dry
         * land. A car is not a boat, and the ground probe used to hand it
         * the wave height as a <em>floor</em>, so a saloon could drive
         * eight kilometres out into the Arabian Sea at 130 km/h.
         */
        default double waterLevelAt(double x, double z) {
            return Double.NEGATIVE_INFINITY;
        }

        void normalAt(double x, double z, Vec3 out);

        boolean isUnpaved(double x, double z);
    }
}
