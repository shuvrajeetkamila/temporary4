package ai.shuvrajeetkamila.mumbai.vehicle;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.physics.Atmosphere;
import ai.shuvrajeetkamila.mumbai.physics.CollisionWorld;
import ai.shuvrajeetkamila.mumbai.physics.RigidBody;

/**
 * Force-based ground vehicle: independent spring/damper suspension per wheel, a magic
 * formula tyre with combined slip, an engine torque curve driving a geared transmission,
 * and wheel spin states integrated from torque.
 *
 * <p>Nothing here assigns a velocity or a transform. Every change of motion comes from a
 * force or a torque applied to the {@link RigidBody}, which is exactly how a kerb strike
 * can lift the inside wheels and how a wet flyover ends in understeer.
 */
public final class CarPhysics {

    public final RigidBody body = new RigidBody();
    public VehicleConfig cfg;

    // --- inputs (set every frame by the player or the AI) ------------------------------
    public float throttle;      // 0..1
    public float brake;         // 0..1
    public float steerInput;    // -1..1, +1 = right
    public float handbrake;     // 0..1
    public boolean ignition = true;

    // --- drivetrain state --------------------------------------------------------------
    public int gear = 2;        // index into cfg.gearRatios; 0 = reverse, 1 = neutral
    public float engineRpm;
    public float clutch = 1f;
    public float shiftTimer;
    public float shiftCooldown;
    public boolean automatic = true;

    // --- per wheel state ----------------------------------------------------------------
    public float[] wheelSpin;         // rad/s
    public float[] suspensionLength;
    public float[] loadN;
    public float[] slipRatio;
    public float[] slipAngle;
    public boolean[] grounded;
    public float[] contactY;
    public float[] skid;              // 0..1 for smoke and sound

    public float steerAngle;          // rad, current road wheel angle
    public boolean inWater;
    public float waterDepth;
    public int lastSurface;

    private final float[] tmp = new float[3];
    private final float[] n = new float[3];
    private final float[] vel = new float[3];
    private final double[] posTmp = new double[3];

    public CarPhysics(VehicleConfig config) {
        setConfig(config);
    }

    public void setConfig(VehicleConfig config) {
        this.cfg = config;
        int w = config.wheelX.length;
        wheelSpin = new float[w];
        suspensionLength = new float[w];
        loadN = new float[w];
        slipRatio = new float[w];
        slipAngle = new float[w];
        grounded = new boolean[w];
        contactY = new float[w];
        skid = new float[w];
        for (int i = 0; i < w; i++) {
            suspensionLength[i] = config.suspensionRest;
        }
        body.setMass(config.mass);
        body.setBoxInertia(config.bodyWidth, config.bodyHeight, config.bodyLength,
                config.inertiaScale);
        engineRpm = config.idleRpm;
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
        body.setOrientationYawPitchRoll(yaw, 0f, 0f);
        for (int i = 0; i < wheelSpin.length; i++) {
            wheelSpin[i] = 0f;
            suspensionLength[i] = cfg.suspensionRest;
        }
        gear = 2;
        engineRpm = cfg.idleRpm;
    }

    public float speedKmh() {
        return body.speed() * 3.6f;
    }

    public float forwardSpeed() {
        return body.forwardSpeed();
    }

    /** Fixed-step update. dt is always the physics step (1/60 s). */
    public void step(float dt, CollisionWorld cw) {
        cw.refreshBoxes(body.px, body.pz, 90f);
        body.clearAccumulators();

        // Gravity ------------------------------------------------------------------
        body.addForce(0f, -cfg.mass * RigidBody.GRAVITY, 0f);

        updateSteering(dt);

        float totalLoad = 0f;
        int wheels = cfg.wheelX.length;

        // Suspension + tyres -------------------------------------------------------
        for (int i = 0; i < wheels; i++) {
            totalLoad += updateWheel(i, dt, cw);
        }

        applyAntiRoll(wheels);
        applyAerodynamics(cw.atmosphere());
        updateDrivetrain(dt, wheels, totalLoad);
        applyWaterInteraction(dt, cw);

        body.limitAngularRate(9f);
        body.integrate(dt);

        resolveBodyCollision(cw, dt);
        clampUnderground(cw);
    }

    private void updateSteering(float dt) {
        float speed = Math.abs(body.forwardSpeed());
        float maxSteer = cfg.maxSteerDeg * Mth.DEG2RAD;
        float reduce = 1f / (1f + speed / cfg.steerSpeedFalloff);
        float target = Mth.clamp(steerInput, -1f, 1f) * maxSteer * Math.max(0.22f, reduce);
        steerAngle = Mth.approach(steerAngle, target, cfg.steerRate * dt);
    }

    /** Returns the vertical load carried by this wheel (N). */
    private float updateWheel(int i, float dt, CollisionWorld cw) {
        float lx = cfg.wheelX[i];
        float lz = cfg.wheelZ[i];
        float ly = 0f;

        // Attachment point in world space.
        body.localToWorld(lx, ly, lz, tmp);
        double ax = body.px + tmp[0];
        double ay = body.py + tmp[1];
        double az = body.pz + tmp[2];

        float upX = body.upX(), upY = body.upY(), upZ = body.upZ();
        float maxLen = cfg.suspensionRest + cfg.suspensionTravel;

        // Probe straight down in world space; the chassis attitude is handled by the
        // projection onto the body up axis below.
        float ground = cw.supportHeight(ax, az, ay, 1.0f);
        contactY[i] = ground;
        float wheelCentreTarget = ground + cfg.wheelRadius;
        float rayLen = (float) (ay - wheelCentreTarget);
        float cosTilt = Math.max(0.35f, upY);
        float length = rayLen / cosTilt;

        boolean hit = length < maxLen;
        grounded[i] = hit;
        if (!hit) {
            suspensionLength[i] = maxLen;
            loadN[i] = 0f;
            slipRatio[i] = 0f;
            slipAngle[i] = 0f;
            skid[i] *= 0.92f;
            // Free wheel: only drive torque and brakes act on it.
            float driveT = driveTorqueForWheel(i);
            float brakeT = brakeTorqueForWheel(i) * Mth.sign(wheelSpin[i]);
            float iw = effectiveWheelInertia(i);
            wheelSpin[i] += (driveT - brakeT) / iw * dt;
            wheelSpin[i] = Mth.clamp(wheelSpin[i], -320f, 320f);
            return 0f;
        }

        float compression = Mth.clamp(maxLen - length, 0f, cfg.suspensionTravel * 2f);
        float prev = suspensionLength[i];
        suspensionLength[i] = length;
        float compressionVel = (prev - length) / Math.max(1e-4f, dt);

        float damper = compressionVel > 0f ? cfg.damperBump : cfg.damperRebound;
        float springForce = cfg.springRate * compression + damper * compressionVel;
        springForce = Mth.clamp(springForce, 0f, cfg.springRate * cfg.suspensionTravel * 4f);

        // Contact normal: blend the terrain normal towards vertical on hard surfaces.
        cw.supportNormal(ax, az, ay, n);
        float along = n[0] * upX + n[1] * upY + n[2] * upZ;
        if (along < 0.1f) {
            n[0] = 0;
            n[1] = 1;
            n[2] = 0;
        }

        // Spring force acts along the suspension (body up) axis.
        body.addForceAtLocalPoint(upX * springForce, upY * springForce, upZ * springForce,
                lx, ly, lz);
        loadN[i] = springForce;

        // --- tyre frame -----------------------------------------------------------
        float steer = cfg.steered[i] ? steerAngle : 0f;
        float cs = (float) Math.cos(steer);
        float sn = (float) Math.sin(steer);
        // Wheel forward/right in body space, then to world.
        body.localToWorld(sn, 0f, cs, tmp);
        float fwdX = tmp[0], fwdY = tmp[1], fwdZ = tmp[2];
        body.localToWorld(cs, 0f, -sn, tmp);
        float rgtX = tmp[0], rgtY = tmp[1], rgtZ = tmp[2];

        // Project onto the contact plane.
        float fd = fwdX * n[0] + fwdY * n[1] + fwdZ * n[2];
        fwdX -= n[0] * fd;
        fwdY -= n[1] * fd;
        fwdZ -= n[2] * fd;
        float fl = (float) Math.sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ);
        if (fl > 1e-4f) {
            fwdX /= fl;
            fwdY /= fl;
            fwdZ /= fl;
        }
        float rd = rgtX * n[0] + rgtY * n[1] + rgtZ * n[2];
        rgtX -= n[0] * rd;
        rgtY -= n[1] * rd;
        rgtZ -= n[2] * rd;
        float rl = (float) Math.sqrt(rgtX * rgtX + rgtY * rgtY + rgtZ * rgtZ);
        if (rl > 1e-4f) {
            rgtX /= rl;
            rgtY /= rl;
            rgtZ /= rl;
        }

        body.velocityAtLocalPoint(lx, ly - cfg.wheelRadius, lz, vel);
        float vForward = vel[0] * fwdX + vel[1] * fwdY + vel[2] * fwdZ;
        float vLateral = vel[0] * rgtX + vel[1] * rgtY + vel[2] * rgtZ;

        float mu = cfg.tyreGrip * cw.grip(ax, az);
        lastSurface = cw.landUse(ax, az);
        float maxFriction = mu * springForce;

        // Slip ratio from the wheel spin.
        float wheelSurfaceSpeed = wheelSpin[i] * cfg.wheelRadius;
        float denom = Math.max(1.2f, Math.abs(vForward));
        float sr = (wheelSurfaceSpeed - vForward) / denom;
        sr = Mth.clamp(sr, -4f, 4f);
        slipRatio[i] = sr;

        float sa = (float) Math.atan2(-vLateral, Math.max(0.9f, Math.abs(vForward)));
        slipAngle[i] = sa;

        float fxN = magic(sr, cfg.longB, cfg.longC, cfg.longE) * maxFriction;
        float fyN = magic(sa * 2.4f, cfg.latB, cfg.latC, cfg.latE) * maxFriction;

        // Friction circle: longitudinal demand steals lateral capability.
        float combined = (float) Math.sqrt(fxN * fxN + fyN * fyN);
        if (combined > maxFriction && combined > 1e-3f) {
            float s = maxFriction / combined;
            fxN *= s;
            fyN *= s;
        }

        // Rolling resistance always opposes motion.
        float rr = cfg.rollingResistance * springForce * Mth.sign(vForward);
        fxN -= rr;

        float forceX = fwdX * fxN + rgtX * fyN;
        float forceY = fwdY * fxN + rgtY * fyN;
        float forceZ = fwdZ * fxN + rgtZ * fyN;
        body.addForceAtLocalPoint(forceX, forceY, forceZ, lx, ly - cfg.wheelRadius, lz);

        // --- wheel spin dynamics --------------------------------------------------
        // A bare wheel has an inertia of well under 1 kg m^2 while the contact patch can
        // produce 700 Nm of reaction torque, which makes an explicit integrator oscillate
        // violently at 60 Hz. Two things fix it honestly rather than by damping the
        // result: the driveline inertia reflected through the gearbox (I * ratio^2) is
        // included for driven wheels, and a wheel that is neither driven nor braked is
        // solved as pure rolling instead of being integrated at all.
        float rollingSpin = vForward / cfg.wheelRadius;
        float driveT = driveTorqueForWheel(i);
        float reactionT = -fxN * cfg.wheelRadius;
        float brakeCap = brakeTorqueForWheel(i);
        boolean powered = Math.abs(driveT) > 1f;
        if (!powered && brakeCap < 1f) {
            wheelSpin[i] = rollingSpin;
        } else {
            float iw = effectiveWheelInertia(i);
            float spinNext = wheelSpin[i] + (driveT + reactionT) / iw * dt;
            if (brakeCap > 0f) {
                float brakeDelta = brakeCap / iw * dt;
                if (Math.abs(spinNext) <= brakeDelta) {
                    spinNext = 0f;
                } else {
                    spinNext -= brakeDelta * Mth.sign(spinNext);
                }
            }
            // Slip speed is physically bounded: a tyre cannot be spinning 40 m/s faster
            // than the road beneath it and still be modelled by this curve.
            float maxSlip = 28f / cfg.wheelRadius;
            spinNext = Mth.clamp(spinNext, rollingSpin - maxSlip, rollingSpin + maxSlip);
            wheelSpin[i] = Mth.clamp(spinNext, -320f, 320f);
        }

        float slipMag = Math.min(1f, Math.abs(sr) * 0.55f + Math.abs(sa) * 1.1f);
        skid[i] = Mth.lerp(skid[i], combined >= maxFriction * 0.97f ? slipMag : 0f,
                1f - Mth.damp(9f, dt));
        return springForce;
    }

    /** Gear ratio including the final drive, zero in neutral or mid shift. */
    private float currentRatio() {
        if (gear == 1 || shiftTimer > 0f) {
            return 0f;
        }
        return cfg.gearRatios[gear] * cfg.finalDrive;
    }

    /**
     * Rotational inertia seen at the wheel: the wheel itself plus, when the clutch is
     * engaged, the engine and gearbox reflected through the square of the gear ratio.
     */
    private float effectiveWheelInertia(int i) {
        float iw = 0.5f * cfg.wheelMass * cfg.wheelRadius * cfg.wheelRadius;
        float ratio = currentRatio();
        if (cfg.driven[i] && ratio != 0f && clutch > 0.02f) {
            int drivenCount = drivenWheelCount();
            iw += cfg.engineInertia * ratio * ratio * clutch / Math.max(1, drivenCount);
        }
        return Math.max(0.35f, iw);
    }

    private int drivenWheelCount() {
        int n = 0;
        for (int k = 0; k < cfg.driven.length; k++) {
            if (cfg.driven[k]) {
                n++;
            }
        }
        return n;
    }

    /** Simplified Pacejka magic formula, normalised to a peak of 1. */
    private static float magic(float slip, float b, float c, float e) {
        float bs = b * slip;
        float inner = bs - e * (bs - (float) Math.atan(bs));
        return (float) Math.sin(c * Math.atan(inner));
    }

    private float driveTorqueForWheel(int i) {
        float ratio = currentRatio();
        if (!cfg.driven[i] || ratio == 0f || !ignition) {
            return 0f;
        }
        int drivenCount = drivenWheelCount();
        if (drivenCount == 0) {
            return 0f;
        }
        return engineTorque(engineRpm) * Mth.clamp01(throttle) * clutch
                * ratio * cfg.driveEfficiency / drivenCount;
    }

    private float brakeTorqueForWheel(int i) {
        float front = cfg.wheelZ[i] > 0f ? cfg.brakeTorqueFront : cfg.brakeTorqueRear;
        float t = front * Mth.clamp01(brake);
        if (cfg.handbraked[i]) {
            t += cfg.handbrakeTorque * Mth.clamp01(handbrake);
        }
        // Engine braking through the drivetrain.
        if (cfg.driven[i] && throttle < 0.05f) {
            t += Math.abs(currentRatio()) * 7f * clutch;
        }
        return t;
    }

    /** Torque curve: rises to a plateau then falls away past the power peak. */
    public float engineTorque(float rpm) {
        if (!ignition) {
            return 0f;
        }
        float r = Mth.clamp(rpm, cfg.idleRpm * 0.5f, cfg.redlineRpm * 1.05f);
        float x = r / cfg.peakTorqueRpm;
        float shape;
        if (x < 1f) {
            shape = 0.55f + 0.45f * (float) Math.sin(Math.PI * 0.5 * x);
        } else {
            float over = (x - 1f) / Math.max(0.3f, cfg.redlineRpm / cfg.peakTorqueRpm - 1f);
            shape = 1f - 0.52f * over * over;
        }
        if (r > cfg.redlineRpm) {
            shape *= 0.25f;          // soft limiter
        }
        return cfg.peakTorque * Math.max(0.05f, shape);
    }

    private void updateDrivetrain(float dt, int wheels, float totalLoad) {
        if (shiftTimer > 0f) {
            shiftTimer -= dt;
            clutch = Mth.approach(clutch, 0f, dt * 8f);
            if (shiftTimer <= 0f) {
                shiftTimer = 0f;
            }
        } else {
            clutch = Mth.approach(clutch, 1f, dt * 5f);
        }

        // Engine speed follows the driven wheels through the gearbox. Using the road
        // speed rather than the instantaneous wheel spin keeps the tachometer and the
        // shift logic steady even when a tyre is momentarily spinning.
        float avgSpin = 0f;
        int count = 0;
        for (int i = 0; i < wheels; i++) {
            if (cfg.driven[i]) {
                avgSpin += wheelSpin[i];
                count++;
            }
        }
        if (count > 0) {
            avgSpin /= count;
        }
        float roadSpin = body.forwardSpeed() / cfg.wheelRadius;
        float linked = Mth.lerp(roadSpin, avgSpin, 0.35f);
        float gearRatio = gear == 1 ? 0f : cfg.gearRatios[gear] * cfg.finalDrive;
        float targetRpm = Math.abs(linked * gearRatio) * 60f / Mth.TAU;
        if (gear == 1 || Math.abs(gearRatio) < 0.01f || clutch < 0.25f) {
            // Declutched: the engine revs against its own inertia only.
            targetRpm = cfg.idleRpm + throttle * (cfg.redlineRpm - cfg.idleRpm) * 0.8f;
        }
        targetRpm = Math.max(cfg.idleRpm, targetRpm);
        float blend = 1f - Mth.damp(clutch > 0.25f ? 12f : 4f, dt);
        engineRpm = Mth.lerp(engineRpm, Math.min(targetRpm, cfg.redlineRpm * 1.04f), blend);
        shiftCooldown = Math.max(0f, shiftCooldown - dt);

        if (automatic && shiftTimer <= 0f && shiftCooldown <= 0f) {
            autoShift();
        }
    }

    private void autoShift() {
        float fwd = body.forwardSpeed();
        // Reverse handling: brake at a standstill selects reverse.
        if (gear >= 2 && fwd < 0.4f && brake > 0.55f && throttle < 0.1f) {
            gear = 0;
            shiftTimer = cfg.shiftTime;
            shiftCooldown = 0.8f;
            return;
        }
        if (gear == 0 && fwd > -0.4f && throttle > 0.3f) {
            gear = 2;
            shiftTimer = cfg.shiftTime;
            shiftCooldown = 0.8f;
            return;
        }
        if (gear < 2) {
            return;
        }
        if (engineRpm > cfg.upshiftRpm && gear < cfg.gearRatios.length - 1 && throttle > 0.05f) {
            gear++;
            shiftTimer = cfg.shiftTime;
            shiftCooldown = 0.9f;
        } else if (engineRpm < cfg.downshiftRpm && gear > 2) {
            gear--;
            shiftTimer = cfg.shiftTime * 0.7f;
            shiftCooldown = 0.9f;
        }
    }

    private void applyAntiRoll(int wheels) {
        if (cfg.antiRollStiffness <= 0f || wheels < 4) {
            return;
        }
        applyAntiRollPair(0, 1);
        applyAntiRollPair(2, 3);
    }

    private void applyAntiRollPair(int a, int b) {
        if (a >= suspensionLength.length || b >= suspensionLength.length) {
            return;
        }
        float travelA = cfg.suspensionRest + cfg.suspensionTravel - suspensionLength[a];
        float travelB = cfg.suspensionRest + cfg.suspensionTravel - suspensionLength[b];
        float force = (travelA - travelB) * cfg.antiRollStiffness;
        if (grounded[a]) {
            body.addForceAtLocalPoint(-body.upX() * force, -body.upY() * force,
                    -body.upZ() * force, cfg.wheelX[a], 0f, cfg.wheelZ[a]);
        }
        if (grounded[b]) {
            body.addForceAtLocalPoint(body.upX() * force, body.upY() * force,
                    body.upZ() * force, cfg.wheelX[b], 0f, cfg.wheelZ[b]);
        }
    }

    private void applyAerodynamics(Atmosphere air) {
        float rho = air.density((float) body.py);
        float sx = body.vx, sy = body.vy, sz = body.vz;
        float v = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (v < 0.05f) {
            return;
        }
        float q = 0.5f * rho * v * v;
        float drag = q * cfg.dragArea;
        body.addForce(-sx / v * drag, -sy / v * drag, -sz / v * drag);
        float down = q * cfg.downforceCoefficient * cfg.bodyWidth * cfg.bodyLength;
        body.addForce(0f, -down, 0f);
    }

    private void applyWaterInteraction(float dt, CollisionWorld cw) {
        float surface = cw.waterSurface(body.px, body.pz);
        if (surface == Float.NEGATIVE_INFINITY) {
            inWater = false;
            waterDepth = 0f;
            return;
        }
        float depth = surface - (float) body.py + cfg.bodyHeight * 0.3f;
        waterDepth = Math.max(0f, depth);
        inWater = waterDepth > 0.05f;
        if (!inWater) {
            return;
        }
        // Cars are not boats: a little buoyancy, a lot of drag, and the engine drowns.
        float submerged = Mth.clamp01(waterDepth / Math.max(0.4f, cfg.bodyHeight));
        float displaced = submerged * cfg.bodyLength * cfg.bodyWidth * cfg.bodyHeight * 0.55f;
        float buoyancy = displaced * 1025f * RigidBody.GRAVITY;
        body.addForce(0f, buoyancy, 0f);
        float dragK = 420f * submerged;
        body.addForce(-body.vx * dragK, -body.vy * dragK * 1.6f, -body.vz * dragK);
        if (waterDepth > cfg.wheelRadius * 2.2f) {
            ignition = false;           // flooded engine, a very Mumbai failure mode
        }
    }

    private void resolveBodyCollision(CollisionWorld cw, float dt) {
        posTmp[0] = body.px;
        posTmp[1] = body.py - cfg.bodyHeight * 0.3f;
        posTmp[2] = body.pz;
        float radius = Math.max(cfg.bodyWidth, cfg.bodyLength) * 0.42f;
        if (cw.resolveCapsule(posTmp, radius, cfg.bodyHeight * 0.8f, cfg.wheelRadius * 1.4f)) {
            double dx = posTmp[0] - body.px;
            double dz = posTmp[2] - body.pz;
            body.px = posTmp[0];
            body.pz = posTmp[2];
            // Kill the velocity component going into the wall and add a small bounce.
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1e-6) {
                float nx = (float) (dx / len);
                float nz = (float) (dz / len);
                float into = body.vx * nx + body.vz * nz;
                if (into < 0f) {
                    body.vx -= into * nx * 1.25f;
                    body.vz -= into * nz * 1.25f;
                    body.wy *= 0.6f;
                }
            }
        }
    }

    private void clampUnderground(CollisionWorld cw) {
        float ground = cw.terrainHeight(body.px, body.pz);
        float minY = ground - 0.6f;
        if (body.py < minY) {
            body.py = minY;
            if (body.vy < 0f) {
                body.vy = 0f;
            }
        }
    }

    /** Average absolute skid across the wheels, for tyre squeal and smoke. */
    public float skidAmount() {
        float s = 0f;
        for (int i = 0; i < skid.length; i++) {
            s += skid[i];
        }
        return s / Math.max(1, skid.length);
    }

    public boolean anyGrounded() {
        for (int i = 0; i < grounded.length; i++) {
            if (grounded[i]) {
                return true;
            }
        }
        return false;
    }

    public String gearLabel() {
        if (gear == 0) {
            return "R";
        }
        if (gear == 1) {
            return "N";
        }
        return Integer.toString(gear - 1);
    }
}
