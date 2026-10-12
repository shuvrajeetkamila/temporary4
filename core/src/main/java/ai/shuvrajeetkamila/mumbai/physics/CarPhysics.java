package ai.shuvrajeetkamila.mumbai.physics;

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
     * Naturally aspirated torque curve, N m at the crank. A broad plateau from
     * about 2,600 to 4,800 rpm, falling away either side, with a hard cut at
     * the limiter.
     */
    public double engineTorque(double rpm, double throttlePos) {
        if (rpm > maxRpm) { return -40; }
        double r = MathUtil.clamp(rpm, 500, maxRpm);
        double peak = 196.0;
        double t;
        if (r < 2600) {
            t = peak * (0.44 + 0.56 * (r - 500) / 2100.0);
        } else if (r < 4800) {
            t = peak * (1.0 - 0.04 * Math.abs(r - 3600) / 1200.0);
        } else {
            t = peak * (0.96 - 0.34 * (r - 4800) / (maxRpm - 4800));
        }
        if (r > redlineRpm) {
            t *= MathUtil.clamp(1.0 - (r - redlineRpm) / (maxRpm - redlineRpm), 0, 1);
        }
        // Engine braking when closed, proportional to speed.
        double drag = 16.0 + 0.0075 * r;
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
            if (w.steered) { w.steerAngle = steerState * maxSteerAngle; }
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
        w.spin -= fLong * w.radius / Math.max(0.35, effectiveWheelInertia(w)) * dt;
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
        double crankTorque = engineTorque(engineRpm, MathUtil.clamp(throttle, 0, 1));
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
                w.spin += wheelTorque / inertiaEff * dt;
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
        if (mayGoUp && gear >= 2 && engineRpm > redlineRpm - 250
                && gear < gearRatios.length - 1) {
            gear++;
            shiftTimer = shiftDuration;
        } else if (gear > 2 && (engineRpm < 1500 || Math.abs(fwd) < 2.0)) {
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
                if (rpm > 1800) { want = g; } else { break; }
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

        void normalAt(double x, double z, Vec3 out);

        boolean isUnpaved(double x, double z);
    }
}
