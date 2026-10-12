package ai.shuvrajeetkamila.mumbai.vehicle;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.physics.Atmosphere;
import ai.shuvrajeetkamila.mumbai.physics.CollisionWorld;
import ai.shuvrajeetkamila.mumbai.physics.RigidBody;

/**
 * Single main rotor helicopter.
 *
 * <p>Thrust is produced along the rotor disc axis from collective pitch and rotor RPM;
 * cyclic tilts the disc, which is what actually moves the aircraft, so forward flight
 * requires a nose-down attitude. Torque reaction from the main rotor is cancelled by the
 * tail rotor (pedals), translational lift appears around 15 knots, ground effect adds
 * thrust within a rotor diameter of the surface, and if the engine fails the rotor keeps
 * turning on airflow so autorotation works.
 */
public final class HelicopterPhysics {

    public static final class Config {
        public String name = "Light Utility Helicopter";
        public float mass = 1450f;
        public float rotorRadius = 5.3f;
        public float rotorRpm = 394f;
        public float bladeCount = 3f;
        public float bladeChord = 0.27f;
        public float maxCollectiveDeg = 14f;
        public float minCollectiveDeg = 0.4f;   // low pitch stop
        public float cyclicAuthority = 9.5f;       // degrees of disc tilt
        public float tailRotorAuthority = 14000f;  // Nm
        public float fuselageDragArea = 1.9f;
        public float verticalDragArea = 6.5f;
        public float bodyLength = 11.6f;
        public float bodyWidth = 2.5f;
        public float bodyHeight = 3.0f;
        public float skidHalfWidth = 1.2f;
        public float skidLength = 2.6f;
        public float skidHeight = -1.35f;
        public float rotorInertia = 1400f;
        public float enginePower = 480000f;        // W
        public float maxSpeed = 72f;               // m/s, never exceed
        public float colourR = 0.15f, colourG = 0.35f, colourB = 0.6f;

        public static Config heavy() {
            Config c = new Config();
            c.name = "Medium Twin";
            c.mass = 4800f;
            c.rotorRadius = 7.9f;
            c.rotorRpm = 324f;
            c.bladeCount = 4f;
            c.bladeChord = 0.38f;
            c.bodyLength = 15.6f;
            c.bodyWidth = 3.2f;
            c.bodyHeight = 4.2f;
            c.enginePower = 1400000f;
            c.tailRotorAuthority = 38000f;
            c.fuselageDragArea = 3.4f;
            c.verticalDragArea = 12f;
            c.maxSpeed = 78f;
            c.colourR = 0.8f;
            c.colourG = 0.3f;
            c.colourB = 0.1f;
            return c;
        }
    }

    public final RigidBody body = new RigidBody();
    public Config cfg;

    // Inputs
    public float collective;     // 0..1
    public float cyclicPitch;    // -1..1 (forward negative = nose down)
    public float cyclicRoll;     // -1..1
    public float pedal;          // -1..1
    public boolean engineOn = true;

    // State
    public float rotorRpm;
    public float rotorAngle;
    public float inducedVelocity;
    public boolean groundContact;
    public boolean inGroundEffect;
    public float translationalLift;
    public boolean autorotating;
    public float torqueReaction;
    public float lastThrust;

    private final float[] tmp = new float[3];
    private final float[] wind = new float[3];
    private final float[] contactVel = new float[3];
    private final float[] skidX = new float[4];
    private final float[] skidZ = new float[4];
    private final double[] posTmp = new double[3];

    public HelicopterPhysics(Config config) {
        setConfig(config);
    }

    public void setConfig(Config config) {
        this.cfg = config;
        body.setMass(config.mass);
        body.setBoxInertia(config.bodyWidth, config.bodyHeight, config.bodyLength, 1.4f);
        rotorRpm = config.rotorRpm;
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
        rotorRpm = engineOn ? cfg.rotorRpm : 0f;
    }

    public float rotorDiscArea() {
        return Mth.PI * cfg.rotorRadius * cfg.rotorRadius;
    }

    public void step(float dt, CollisionWorld cw) {
        cw.refreshBoxes(body.px, body.pz, 140f);
        body.clearAccumulators();
        Atmosphere air = cw.atmosphere();
        float rho = air.density((float) body.py);

        body.addForce(0f, -cfg.mass * RigidBody.GRAVITY, 0f);

        float ground = cw.supportHeight(body.px, body.pz, body.py, 0.5f);
        float agl = (float) body.py - ground;

        updateRotorSpeed(dt, agl);
        rotorAngle = (rotorAngle + rotorRpm / 60f * Mth.TAU * dt) % Mth.TAU;

        // --- collective to blade pitch to thrust -------------------------------------
        // Combined blade element / momentum theory. The induced inflow is not a free
        // parameter: it is the fixed point of
        //     Ct     = sigma * a * (theta/3 - lambda/2)
        //     lambda = lambda_c/2 + sqrt((lambda_c/2)^2 + Ct/2)
        // Solving it properly is what makes hover sit near the middle of the collective
        // range instead of the aircraft leaping off the pad at half stick.
        float pitchDeg = Mth.lerp(cfg.minCollectiveDeg, cfg.maxCollectiveDeg,
                Mth.clamp01(collective));
        float omega = rotorRpm / 60f * Mth.TAU;
        float tipSpeed = Math.max(1f, omega * cfg.rotorRadius);
        float solidity = cfg.bladeCount * cfg.bladeChord / (Mth.PI * cfg.rotorRadius);
        float climbRate = inflowVelocity();
        float lambdaC = climbRate / tipSpeed;
        float theta = pitchDeg * Mth.DEG2RAD;
        float lambda = Math.abs(lambdaC) * 0.5f + 0.05f;
        float ct = 0f;
        for (int iter = 0; iter < 4; iter++) {
            ct = solidity * 5.73f * (theta / 3f - lambda * 0.5f);
            if (ct < 0f) {
                ct = 0f;
            }
            float half = lambdaC * 0.5f;
            lambda = half + (float) Math.sqrt(half * half + ct * 0.5f);
        }
        inducedVelocity = Math.max(0.5f, (lambda - lambdaC) * tipSpeed);
        float thrust = ct * rho * rotorDiscArea() * tipSpeed * tipSpeed;

        // --- translational lift -------------------------------------------------------
        cw.windAt(body.px, body.py, body.pz, wind);
        float relX = body.vx - wind[0];
        float relY = body.vy - wind[1];
        float relZ = body.vz - wind[2];
        float horizontal = (float) Math.sqrt(relX * relX + relZ * relZ);
        translationalLift = 1f + 0.28f * Mth.smoothstep(4f, 18f, horizontal)
                - 0.07f * Mth.smoothstep(40f, 70f, horizontal);
        thrust *= translationalLift;

        // --- ground effect ------------------------------------------------------------
        inGroundEffect = agl < cfg.rotorRadius * 2f;
        if (inGroundEffect && agl > 0f) {
            float ratio = agl / (cfg.rotorRadius * 2f);
            thrust *= 1f + 0.22f * (1f - ratio) * (1f - ratio);
        }

        lastThrust = thrust;

        // --- cyclic: tilt the thrust vector relative to the airframe --------------------
        float tiltF = Mth.clamp(cyclicPitch, -1f, 1f) * cfg.cyclicAuthority * Mth.DEG2RAD;
        float tiltR = Mth.clamp(cyclicRoll, -1f, 1f) * cfg.cyclicAuthority * Mth.DEG2RAD;
        float tx = (float) Math.sin(tiltR);
        float tz = (float) Math.sin(tiltF);
        float ty = (float) Math.sqrt(Math.max(0.05f, 1f - tx * tx - tz * tz));
        // Thrust acts above the centre of mass: tilting it produces the control moment.
        body.addLocalForceAtLocalPoint(tx * thrust, ty * thrust, tz * thrust,
                0f, 1.5f, 0f);

        // --- main rotor torque reaction and tail rotor ----------------------------------
        float power = thrust * Math.max(0.5f, inducedVelocity) + profilePower(rho, omega);
        if (!engineOn) {
            power = 0f;
        }
        torqueReaction = engineOn ? power / Math.max(1f, omega) : 0f;
        float tail = Mth.clamp(pedal, -1f, 1f) * cfg.tailRotorAuthority
                + torqueReaction;
        body.localToWorld(0f, tail, 0f, tmp);
        body.addTorque(tmp[0], tmp[1], tmp[2]);

        // --- fuselage aerodynamics -------------------------------------------------------
        applyFuselageDrag(rho, relX, relY, relZ);

        // --- stability: the airframe hangs below the rotor --------------------------------
        float pendulum = cfg.mass * 0.9f;
        body.addTorque(-body.wx * pendulum * 1.5f, -body.wy * pendulum * 2.2f,
                -body.wz * pendulum * 1.5f);
        float roll = body.roll();
        float pitch = body.pitch();
        body.localToWorld(-pitch * cfg.mass * 2.4f, 0f, -roll * cfg.mass * 2.4f, tmp);
        body.addTorque(tmp[0], tmp[1], tmp[2]);

        handleGroundContact(dt, cw, ground, agl);

        body.limitAngularRate(3.5f);
        body.limitSpeed(cfg.maxSpeed * 1.35f);
        body.integrate(dt);
        resolveImpacts(cw);
    }

    private float profilePower(float rho, float omega) {
        float solidity = cfg.bladeCount * cfg.bladeChord / (Mth.PI * cfg.rotorRadius);
        float cd0 = 0.011f;
        float tip = omega * cfg.rotorRadius;
        return rho * rotorDiscArea() * tip * tip * tip * solidity * cd0 / 8f;
    }

    /**
     * Climb velocity along the rotor axis (positive climbing). Returned as the inflow
     * ratio numerator used by the momentum solution above.
     */
    private float inflowVelocity() {
        return body.vx * body.upX() + body.vy * body.upY() + body.vz * body.upZ();
    }

    private void updateRotorSpeed(float dt, float agl) {
        float target = cfg.rotorRpm;
        if (engineOn) {
            autorotating = false;
            rotorRpm = Mth.approach(rotorRpm, target, 70f * dt);
        } else {
            // Autorotation: upward airflow through the disc drives the rotor.
            float descent = -(body.vx * body.upX() + body.vy * body.upY()
                    + body.vz * body.upZ());
            float driven = Mth.clamp(descent / 22f, 0f, 1.25f);
            float equilibrium = cfg.rotorRpm * (0.55f + 0.5f * driven)
                    * (1.15f - 0.5f * Mth.clamp01(collective));
            autorotating = rotorRpm > cfg.rotorRpm * 0.4f;
            rotorRpm = Mth.approach(rotorRpm, Math.min(equilibrium, cfg.rotorRpm * 1.08f),
                    38f * dt);
        }
        rotorRpm = Math.max(0f, rotorRpm);
    }

    private void applyFuselageDrag(float rho, float relX, float relY, float relZ) {
        body.worldToLocal(relX, relY, relZ, tmp);
        float lx = tmp[0], ly = tmp[1], lz = tmp[2];
        float q = 0.5f * rho;
        float dx = -Mth.sign(lx) * q * lx * lx * cfg.fuselageDragArea * 2.4f;
        float dy = -Mth.sign(ly) * q * ly * ly * cfg.verticalDragArea;
        float dz = -Mth.sign(lz) * q * lz * lz * cfg.fuselageDragArea;
        body.addLocalForceAtLocalPoint(dx, dy, dz, 0f, 0f, 0f);
        // Tail fin weathervanes the nose into the relative wind in forward flight.
        float fin = -lx * Math.abs(lz) * rho * 1.6f;
        body.addLocalForceAtLocalPoint(fin, 0f, 0f, 0f, 0.4f, -cfg.bodyLength * 0.42f);
    }

    private void handleGroundContact(float dt, CollisionWorld cw, float ground, float agl) {
        groundContact = false;
        final float[] sx = skidX;
        final float[] sz = skidZ;
        sx[0] = -cfg.skidHalfWidth; sx[1] = cfg.skidHalfWidth;
        sx[2] = -cfg.skidHalfWidth; sx[3] = cfg.skidHalfWidth;
        sz[0] = cfg.skidLength * 0.5f; sz[1] = cfg.skidLength * 0.5f;
        sz[2] = -cfg.skidLength * 0.5f; sz[3] = -cfg.skidLength * 0.5f;
        for (int i = 0; i < 4; i++) {
            body.localToWorld(sx[i], cfg.skidHeight, sz[i], tmp);
            double wx = body.px + tmp[0];
            double wy = body.py + tmp[1];
            double wz = body.pz + tmp[2];
            float g = cw.supportHeight(wx, wz, wy + 1.0, 1.0f);
            float waterSurf = cw.waterSurface(wx, wz);
            if (waterSurf != Float.NEGATIVE_INFINITY) {
                g = Math.max(g, waterSurf);
            }
            float pen = (float) (g - wy);
            if (pen <= 0f) {
                continue;
            }
            groundContact = true;
            float k = cfg.mass * 32f;
            float c = cfg.mass * 6.5f;
            float[] v = contactVel;
            body.velocityAtLocalPoint(sx[i], cfg.skidHeight, sz[i], v);
            float force = k * pen - c * v[1];
            force = Mth.clamp(force, 0f, cfg.mass * RigidBody.GRAVITY * 6f);
            body.addForceAtLocalPoint(0f, force, 0f, sx[i], cfg.skidHeight, sz[i]);
            // Skid friction: strong enough to stop sliding, weak enough to allow taxi.
            float fr = Math.min(force * 0.9f, cfg.mass * 9f);
            body.addForceAtLocalPoint(-Mth.clamp(v[0], -4f, 4f) * fr * 0.35f, 0f,
                    -Mth.clamp(v[2], -4f, 4f) * fr * 0.35f, sx[i], cfg.skidHeight, sz[i]);
        }
    }

    private void resolveImpacts(CollisionWorld cw) {
        posTmp[0] = body.px;
        posTmp[1] = body.py - 1.0;
        posTmp[2] = body.pz;
        float r = cfg.bodyLength * 0.4f;
        if (cw.resolveCapsule(posTmp, r, cfg.bodyHeight, 0.4f)) {
            double dx = posTmp[0] - body.px;
            double dz = posTmp[2] - body.pz;
            body.px = posTmp[0];
            body.pz = posTmp[2];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1e-6) {
                float nx = (float) (dx / len);
                float nz = (float) (dz / len);
                float into = body.vx * nx + body.vz * nz;
                if (into < 0f) {
                    body.vx -= into * nx * 1.4f;
                    body.vz -= into * nz * 1.4f;
                    body.wx *= 0.5f;
                    body.wz *= 0.5f;
                }
            }
        }
        float ground = cw.terrainHeight(body.px, body.pz);
        if (body.py < ground - 1.0) {
            body.py = ground - 1.0;
            if (body.vy < 0f) {
                body.vy = 0f;
            }
        }
    }

    public float altitudeAgl(CollisionWorld cw) {
        return (float) body.py - cw.supportHeight(body.px, body.pz, body.py, 0.5f);
    }

    public float rotorRpmPercent() {
        return rotorRpm / Math.max(1f, cfg.rotorRpm) * 100f;
    }
}
