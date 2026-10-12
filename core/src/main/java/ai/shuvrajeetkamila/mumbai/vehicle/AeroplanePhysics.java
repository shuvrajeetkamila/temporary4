package ai.shuvrajeetkamila.mumbai.vehicle;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.physics.Atmosphere;
import ai.shuvrajeetkamila.mumbai.physics.CollisionWorld;
import ai.shuvrajeetkamila.mumbai.physics.RigidBody;

/**
 * Fixed-wing aircraft with a real lift curve.
 *
 * <p>Forces come from dynamic pressure q = 0.5*rho*V^2 acting on surfaces with their own
 * Cl/Cd curves: wing (with flaps and a genuine stall past the critical angle), horizontal
 * tail (pitch stability and elevator), vertical tail (yaw stability and rudder) and
 * ailerons. There is no hidden "take off now" impulse anywhere: if you rotate below Vr
 * the aircraft settles back onto the runway, and if you run out of tarmac you run into
 * whatever is past the end of it.
 */
public final class AeroplanePhysics {

    public static final class Config {
        public String name = "Light Twin";
        public float mass = 2100f;              // kg at take-off
        public float wingArea = 16.8f;          // m^2
        public float wingSpan = 11.6f;
        public float chord = 1.5f;
        public float aspectRatio = 8.0f;
        public float cl0 = 0.22f;               // lift at zero angle of attack
        public float clAlpha = 5.2f;            // per radian
        public float clMax = 1.45f;
        public float stallAngleDeg = 15.5f;
        public float cd0 = 0.031f;
        public float oswald = 0.78f;
        public float flapsClBonus = 0.62f;
        public float flapsCdBonus = 0.055f;
        public float tailArea = 3.4f;
        public float tailArm = 4.8f;
        public float finArea = 1.9f;
        public float elevatorAuthority = 1.5f;
        public float rudderAuthority = 1.1f;
        public float aileronAuthority = 0.34f;
        public float maxThrust = 9600f;         // N, both engines, sea level static
        public float thrustAltFactor = 1.0f;
        public float bodyLength = 10.6f;
        public float bodyWidth = 1.5f;
        public float bodyHeight = 2.9f;
        public float gearHeight = -1.35f;
        public float gearTrack = 3.2f;
        public float gearWheelbase = 3.0f;
        public float brakeForce = 9000f;
        public float rollingDrag = 0.022f;
        public float vne = 110f;                // m/s never exceed
        public float colourR = 0.92f, colourG = 0.92f, colourB = 0.95f;

        /** A larger narrow-body-style airliner, still an entirely fictional type. */
        public static Config airliner() {
            Config c = new Config();
            c.name = "Narrow Body";
            c.mass = 62000f;
            c.wingArea = 124f;
            c.wingSpan = 35.8f;
            c.chord = 3.8f;
            c.aspectRatio = 9.4f;
            c.cl0 = 0.2f;
            c.clAlpha = 5.6f;
            c.clMax = 1.55f;
            c.cd0 = 0.022f;
            c.flapsClBonus = 0.85f;
            c.flapsCdBonus = 0.09f;
            c.tailArea = 31f;
            c.tailArm = 15.5f;
            c.finArea = 21f;
            c.maxThrust = 220000f;
            c.bodyLength = 37.6f;
            c.bodyWidth = 3.8f;
            c.bodyHeight = 4.2f;
            c.gearHeight = -3.1f;
            c.gearTrack = 5.8f;
            c.gearWheelbase = 13.5f;
            c.brakeForce = 160000f;
            c.vne = 160f;
            c.elevatorAuthority = 1.1f;
            c.aileronAuthority = 0.22f;
            return c;
        }

        /** Slow single-engine trainer: easy to fly, lands in 400 m. */
        public static Config trainer() {
            Config c = new Config();
            c.name = "Trainer";
            c.mass = 1050f;
            c.wingArea = 16.2f;
            c.wingSpan = 11.0f;
            c.chord = 1.48f;
            c.aspectRatio = 7.5f;
            c.clMax = 1.52f;
            c.cd0 = 0.036f;
            c.maxThrust = 2700f;
            c.bodyLength = 8.3f;
            c.bodyWidth = 1.2f;
            c.bodyHeight = 2.5f;
            c.gearHeight = -1.1f;
            c.gearTrack = 2.6f;
            c.gearWheelbase = 2.0f;
            c.brakeForce = 4200f;
            c.vne = 78f;
            return c;
        }
    }

    public final RigidBody body = new RigidBody();
    public Config cfg;

    // Inputs
    public float throttle;       // 0..1
    public float elevator;       // -1..1
    public float aileron;        // -1..1
    public float rudder;         // -1..1
    public float brakes;         // 0..1
    public float flaps;          // 0..1 in detents
    public boolean gearDown = true;

    // State
    public float angleOfAttack;
    public float sideslip;
    public float indicatedAirspeed;
    public boolean stalled;
    public boolean onGround;
    public float gearLoad;
    public float verticalSpeed;
    public float lastLift;
    public float lastDrag;
    public float lastThrust;

    private final float[] tmp = new float[3];
    private final float[] wind = new float[3];
    private final float[] gearVel = new float[3];
    private final float[] gearX = new float[3];
    private final float[] gearZ = new float[3];
    private final double[] posTmp = new double[3];

    public AeroplanePhysics(Config config) {
        setConfig(config);
    }

    public void setConfig(Config config) {
        this.cfg = config;
        body.setMass(config.mass);
        body.setBoxInertia(config.wingSpan * 0.62f, config.bodyHeight,
                config.bodyLength, 1.0f);
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
    }

    /** Stall speed in the current configuration (m/s). */
    public float stallSpeed(float rho) {
        float clMax = cfg.clMax + cfg.flapsClBonus * flaps;
        return (float) Math.sqrt(2f * cfg.mass * RigidBody.GRAVITY
                / (rho * cfg.wingArea * clMax));
    }

    public void step(float dt, CollisionWorld cw) {
        cw.refreshBoxes(body.px, body.pz, 200f);
        body.clearAccumulators();
        Atmosphere air = cw.atmosphere();
        float rho = air.density((float) body.py);

        body.addForce(0f, -cfg.mass * RigidBody.GRAVITY, 0f);

        cw.windAt(body.px, body.py, body.pz, wind);
        float relX = body.vx - wind[0];
        float relY = body.vy - wind[1];
        float relZ = body.vz - wind[2];
        float v = (float) Math.sqrt(relX * relX + relY * relY + relZ * relZ);
        indicatedAirspeed = v * (float) Math.sqrt(rho / Atmosphere.RHO_SEA_LEVEL);
        verticalSpeed = body.vy;

        // Relative wind in body axes: +Z forward, +Y up, +X right.
        body.worldToLocal(relX, relY, relZ, tmp);
        float u = tmp[2];          // forward
        float w = tmp[1];          // up
        float sideV = tmp[0];

        angleOfAttack = (float) Math.atan2(-w, Math.max(0.5f, u));
        sideslip = (float) Math.atan2(sideV, Math.max(0.5f, u));

        float q = 0.5f * rho * v * v;

        applyWing(q, v, u, rho);
        applyTail(q, v);
        applyFin(q, v);
        applyThrust(rho);
        applyParasiteDrag(q);

        if (gearDown) {
            applyGear(dt, cw);
        } else {
            onGround = false;
            gearLoad = 0f;
        }

        // Rate damping so the airframe is not a perpetual oscillator.
        body.worldToLocal(body.wx, body.wy, body.wz, tmp);
        float dampP = -tmp[2] * q * cfg.wingArea * cfg.wingSpan * 0.06f;
        float dampQ = -tmp[0] * q * cfg.tailArea * cfg.tailArm * 0.55f;
        float dampR = -tmp[1] * q * cfg.finArea * cfg.tailArm * 0.5f;
        body.localToWorld(dampQ, dampR, dampP, tmp);
        body.addTorque(tmp[0], tmp[1], tmp[2]);

        body.limitAngularRate(3.0f);
        body.integrate(dt);
        resolveImpacts(cw);
    }

    private void applyWing(float q, float v, float u, float rho) {
        float alpha = angleOfAttack;
        float stallRad = cfg.stallAngleDeg * Mth.DEG2RAD;
        float clMax = cfg.clMax + cfg.flapsClBonus * flaps;
        float cl;
        float absA = Math.abs(alpha);
        if (absA <= stallRad) {
            cl = cfg.cl0 + cfg.clAlpha * alpha + cfg.flapsClBonus * flaps;
            cl = Mth.clamp(cl, -clMax, clMax);
            stalled = false;
        } else {
            // Post-stall: lift collapses towards a flat-plate value and drag explodes.
            float over = (absA - stallRad) / (Mth.PI * 0.5f - stallRad);
            over = Mth.clamp01(over);
            float peak = cfg.cl0 + cfg.clAlpha * stallRad + cfg.flapsClBonus * flaps;
            float flat = 1.05f * (float) Math.sin(2.0 * absA);
            float mag = Mth.lerp(peak, flat, Mth.smoothstep(0f, 0.55f, over));
            cl = Math.signum(alpha) * Math.abs(mag) * (1f - 0.35f * over);
            stalled = true;
        }

        float cdi = cl * cl / (Mth.PI * cfg.aspectRatio * cfg.oswald);
        float cd = cfg.cd0 + cdi + cfg.flapsCdBonus * flaps;
        if (stalled) {
            cd += 0.9f * (Math.abs(alpha) - cfg.stallAngleDeg * Mth.DEG2RAD);
        }
        if (gearDown) {
            cd += 0.019f;
        }

        float lift = q * cfg.wingArea * cl;
        float drag = q * cfg.wingArea * cd;
        lastLift = lift;
        lastDrag = drag;

        // Lift is perpendicular to the relative wind in the body XZ plane.
        float ca = (float) Math.cos(angleOfAttack);
        float sa = (float) Math.sin(angleOfAttack);
        float fz = -drag * ca + lift * sa;
        float fy = drag * sa + lift * ca;
        // Applied at the aerodynamic centre, slightly ahead of the centre of mass.
        body.addLocalForceAtLocalPoint(0f, fy, fz, 0f, 0f, cfg.chord * 0.08f);

        // Roll from ailerons plus roll damping from the wing.
        float roll = -Mth.clamp(aileron, -1f, 1f) * cfg.aileronAuthority
                * q * cfg.wingArea * cfg.wingSpan;
        // Dihedral effect: in a right slip the windward wing gains lift and the
        // aircraft rolls left, which is a positive torque about the body +Z axis.
        roll += sideslip * q * cfg.wingArea * cfg.wingSpan * 0.16f;
        body.localToWorld(0f, 0f, roll, tmp);
        body.addTorque(tmp[0], tmp[1], tmp[2]);

        // Adverse yaw: the rising wing's extra induced drag pulls the nose away from
        // the turn until the pilot adds rudder.
        float adverse = -Mth.clamp(aileron, -1f, 1f) * q * cfg.wingArea * cfg.wingSpan
                * 0.012f;
        body.localToWorld(0f, adverse, 0f, tmp);
        body.addTorque(tmp[0], tmp[1], tmp[2]);
    }

    private void applyTail(float q, float v) {
        // Horizontal stabiliser sees a downwashed flow; its lift gives pitch stability.
        //
        // Axis convention: body +X right, +Y up, +Z forward (right handed), so a torque
        // about +X pitches the NOSE DOWN. Pulling the stick back (elevator = +1) has to
        // push the tailplane DOWN to raise the nose, hence the negative sign. The
        // angle-of-attack term keeps its positive sign: that is the natural longitudinal
        // stability of the tail, which pitches the nose down as the wing loads up.
        float tailAlpha = angleOfAttack * 0.62f
                - Mth.clamp(elevator, -1f, 1f) * 0.32f;
        float clTail = Mth.clamp(4.6f * tailAlpha, -1.3f, 1.3f);
        float force = q * cfg.tailArea * clTail * cfg.elevatorAuthority;
        body.addLocalForceAtLocalPoint(0f, force, 0f, 0f, 0.4f, -cfg.tailArm);
    }

    private void applyFin(float q, float v) {
        // A force to the right at the tail yaws the nose left, so right rudder needs a
        // negative side force. The -sideslip term is the weathervane stability that
        // swings the nose back into the relative wind.
        float finAlpha = -sideslip - Mth.clamp(rudder, -1f, 1f) * 0.30f;
        float clFin = Mth.clamp(4.2f * finAlpha, -1.2f, 1.2f);
        float force = q * cfg.finArea * clFin * cfg.rudderAuthority;
        body.addLocalForceAtLocalPoint(force, 0f, 0f, 0f, 0.8f, -cfg.tailArm);
    }

    private void applyThrust(float rho) {
        float densityRatio = rho / Atmosphere.RHO_SEA_LEVEL;
        float t = Mth.clamp01(throttle) * cfg.maxThrust
                * Mth.lerp(1f, densityRatio, cfg.thrustAltFactor);
        // Thrust decays at high speed as the propeller or fan unloads.
        float v = Math.max(0f, body.forwardSpeed());
        t *= Mth.clamp(1f - 0.45f * (v / Math.max(20f, cfg.vne)), 0.3f, 1f);
        lastThrust = t;
        body.addLocalForceAtLocalPoint(0f, 0f, t, 0f, -0.2f, 0f);
    }

    private void applyParasiteDrag(float q) {
        body.worldToLocal(body.vx, body.vy, body.vz, tmp);
        float side = tmp[0];
        float vert = tmp[1];
        float fx = -Mth.sign(side) * q * Math.abs(side) / Math.max(1f, Math.abs(tmp[2]) + 1f)
                * cfg.finArea * 1.8f;
        float fy = -Mth.sign(vert) * q * Math.abs(vert) / Math.max(1f, Math.abs(tmp[2]) + 1f)
                * cfg.wingArea * 0.22f;
        body.addLocalForceAtLocalPoint(fx, fy, 0f, 0f, 0f, 0f);
    }

    private void applyGear(float dt, CollisionWorld cw) {
        final float[] gx = gearX;
        final float[] gz = gearZ;
        gx[0] = 0f; gx[1] = -cfg.gearTrack * 0.5f; gx[2] = cfg.gearTrack * 0.5f;
        gz[0] = cfg.gearWheelbase * 0.62f;
        gz[1] = -cfg.gearWheelbase * 0.38f;
        gz[2] = -cfg.gearWheelbase * 0.38f;
        onGround = false;
        gearLoad = 0f;
        float k = cfg.mass * 34f;
        float c = cfg.mass * 5.6f;
        final float[] vel = gearVel;
        for (int i = 0; i < 3; i++) {
            body.localToWorld(gx[i], cfg.gearHeight, gz[i], tmp);
            double wx = body.px + tmp[0];
            double wy = body.py + tmp[1];
            double wz = body.pz + tmp[2];
            float ground = cw.supportHeight(wx, wz, wy + 1.2, 1.2f);
            float pen = (float) (ground - wy);
            if (pen <= 0f) {
                continue;
            }
            onGround = true;
            body.velocityAtLocalPoint(gx[i], cfg.gearHeight, gz[i], vel);
            float force = k * pen - c * vel[1];
            force = Mth.clamp(force, 0f, cfg.mass * RigidBody.GRAVITY * 8f);
            gearLoad += force;
            body.addForceAtLocalPoint(0f, force, 0f, gx[i], cfg.gearHeight, gz[i]);

            // Wheel friction in the contact plane: free to roll forward, resists sideways.
            body.worldToLocal(vel[0], vel[1], vel[2], tmp);
            float vSide = tmp[0];
            float vFwd = tmp[2];
            float mu = cw.grip(wx, wz);
            float sideForce = -Mth.clamp(vSide * 2.2f, -1f, 1f) * force * 0.85f * mu;
            float rollDrag = -Mth.sign(vFwd) * force * cfg.rollingDrag;
            float brakeF = -Mth.sign(vFwd) * Math.min(cfg.brakeForce * Mth.clamp01(brakes)
                    / 3f, Math.abs(vFwd) * cfg.mass * 2f);
            // Nose wheel steering follows the rudder at low speed.
            if (i == 0 && Math.abs(vFwd) < 30f) {
                float steer = Mth.clamp(rudder, -1f, 1f)
                        * (1f - Mth.clamp01(Math.abs(vFwd) / 30f));
                // Right rudder steers the nose wheel right: the tyre pushes the nose
                // leg to the right, which is a positive body-X force at the front.
                sideForce += steer * force * 0.55f * mu;
            }
            body.addLocalForceAtLocalPoint(sideForce, 0f, rollDrag + brakeF,
                    gx[i], cfg.gearHeight, gz[i]);
        }
    }

    private void resolveImpacts(CollisionWorld cw) {
        posTmp[0] = body.px;
        posTmp[1] = body.py - 1.0;
        posTmp[2] = body.pz;
        if (cw.resolveCapsule(posTmp, cfg.bodyWidth * 1.2f, cfg.bodyHeight, 0.6f)) {
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
                    body.vx -= into * nx * 1.5f;
                    body.vz -= into * nz * 1.5f;
                    body.wy *= 0.4f;
                }
            }
        }
        float ground = cw.terrainHeight(body.px, body.pz);
        float floor = ground + (gearDown ? 0f : -0.4f);
        if (body.py < floor - 0.5) {
            body.py = floor - 0.5;
            if (body.vy < 0f) {
                body.vy = 0f;
            }
        }
    }

    public float altitudeAgl(CollisionWorld cw) {
        return (float) body.py - cw.supportHeight(body.px, body.pz, body.py, 0.5f);
    }

    /** Load factor in g, for the HUD. */
    public float loadFactor() {
        float lift = lastLift;
        return lift / Math.max(1f, cfg.mass * RigidBody.GRAVITY);
    }
}
