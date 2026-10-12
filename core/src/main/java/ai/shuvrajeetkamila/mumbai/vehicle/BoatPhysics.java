package ai.shuvrajeetkamila.mumbai.vehicle;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.physics.CollisionWorld;
import ai.shuvrajeetkamila.mumbai.physics.RigidBody;
import ai.shuvrajeetkamila.mumbai.world.WaterModel;

/**
 * Buoyancy-driven watercraft.
 *
 * <p>The hull is sampled at a grid of points; each submerged point contributes a buoyant
 * force proportional to its depth below the <em>same analytic wave surface the water
 * shader draws</em>, so a swell you can see is a swell that pitches the bow. Added mass,
 * viscous and form drag, planing lift, propeller thrust, rudder side force and beaching
 * on the shore are all modelled as forces.
 */
public final class BoatPhysics {

    public static final class Config {
        public String name = "Harbour Launch";
        public float mass = 2400f;
        public float length = 9.2f;
        public float beam = 2.9f;
        public float draft = 0.75f;
        public float hullHeight = 1.9f;
        /** Volume of the hull below the deck (m^3): sets how much it can float. */
        public float displacement = 4.6f;
        public float maxThrust = 9800f;        // N at full throttle
        public float reverseFraction = 0.42f;
        public float rudderArea = 0.42f;       // m^2
        public float maxRudderDeg = 32f;
        public float planingSpeed = 7.5f;      // m/s where the bow lifts
        public float planingLift = 0.55f;
        public float dragLongitudinal = 110f;
        public float dragLateral = 1400f;
        public float dragVertical = 900f;
        public float angularDrag = 5200f;
        /** Metacentric height (m): how hard the hull fights being rolled over. */
        public float metacentricHeight = 1.15f;
        public float propDepth = 0.55f;        // below the centre of mass
        public int buoyancyRows = 4;
        public int buoyancyCols = 3;
        public float colourR = 0.86f, colourG = 0.86f, colourB = 0.82f;
        public boolean fishing;

        public Config copy() {
            Config c = new Config();
            c.name = name;
            c.mass = mass;
            c.length = length;
            c.beam = beam;
            c.draft = draft;
            c.hullHeight = hullHeight;
            c.displacement = displacement;
            c.maxThrust = maxThrust;
            c.reverseFraction = reverseFraction;
            c.rudderArea = rudderArea;
            c.maxRudderDeg = maxRudderDeg;
            c.planingSpeed = planingSpeed;
            c.planingLift = planingLift;
            c.dragLongitudinal = dragLongitudinal;
            c.dragLateral = dragLateral;
            c.dragVertical = dragVertical;
            c.angularDrag = angularDrag;
            c.metacentricHeight = metacentricHeight;
            c.propDepth = propDepth;
            c.buoyancyRows = buoyancyRows;
            c.buoyancyCols = buoyancyCols;
            c.colourR = colourR;
            c.colourG = colourG;
            c.colourB = colourB;
            c.fishing = fishing;
            return c;
        }

        /** Wooden fishing boat of the kind moored at Sassoon Dock and Versova. */
        public static Config fishingBoat() {
            Config c = new Config();
            c.name = "Fishing Boat";
            c.mass = 5200f;
            c.length = 13.5f;
            c.beam = 3.8f;
            c.draft = 1.15f;
            c.hullHeight = 2.6f;
            c.displacement = 9.5f;
            c.maxThrust = 14000f;
            c.planingSpeed = 99f;        // displacement hull, never planes
            c.dragLongitudinal = 240f;
            c.angularDrag = 12000f;
            c.metacentricHeight = 1.4f;
            c.colourR = 0.2f;
            c.colourG = 0.35f;
            c.colourB = 0.6f;
            c.fishing = true;
            return c;
        }

        /** Light speedboat: planes hard, slides in turns. */
        public static Config speedboat() {
            Config c = new Config();
            c.name = "Speedboat";
            c.mass = 1150f;
            c.length = 6.4f;
            c.beam = 2.2f;
            c.draft = 0.45f;
            c.hullHeight = 1.3f;
            c.displacement = 2.6f;
            c.maxThrust = 11500f;
            c.planingSpeed = 5.5f;
            c.planingLift = 0.95f;
            c.dragLongitudinal = 62f;
            c.dragLateral = 900f;
            c.angularDrag = 2600f;
            c.metacentricHeight = 0.95f;
            c.colourR = 0.9f;
            c.colourG = 0.3f;
            c.colourB = 0.2f;
            return c;
        }

        /** Harbour ferry to Elephanta-style destinations: slow, heavy, stable. */
        public static Config ferry() {
            Config c = new Config();
            c.name = "Harbour Ferry";
            c.mass = 42000f;
            c.length = 24f;
            c.beam = 6.4f;
            c.draft = 1.8f;
            c.hullHeight = 4.2f;
            c.displacement = 72f;
            c.maxThrust = 52000f;
            c.planingSpeed = 99f;
            c.dragLongitudinal = 900f;
            c.dragLateral = 9000f;
            c.angularDrag = 180000f;
            c.metacentricHeight = 2.4f;
            c.buoyancyRows = 5;
            c.buoyancyCols = 3;
            c.colourR = 0.9f;
            c.colourG = 0.88f;
            c.colourB = 0.8f;
            return c;
        }
    }

    public final RigidBody body = new RigidBody();
    public Config cfg;

    public float throttle;       // -1..1
    public float rudderInput;    // -1..1
    public float rudderAngle;

    public float submergedFraction;
    public boolean planing;
    public boolean beached;
    public float wakeStrength;
    public int waterClass = WaterModel.CLASS_SEA;

    private final float[] tmp = new float[3];
    private final float[] vel = new float[3];
    private float[] pointX;
    private float[] pointZ;
    private float[] pointDepth;

    public BoatPhysics(Config config) {
        setConfig(config);
    }

    public void setConfig(Config config) {
        this.cfg = config;
        body.setMass(config.mass);
        body.setBoxInertia(config.beam, config.hullHeight, config.length, 1.1f);
        buildSamplePoints();
    }

    private void buildSamplePoints() {
        int rows = Math.max(3, cfg.buoyancyRows);
        int cols = Math.max(2, cfg.buoyancyCols);
        int n = rows * cols;
        pointX = new float[n];
        pointZ = new float[n];
        pointDepth = new float[n];
        int k = 0;
        for (int r = 0; r < rows; r++) {
            float tz = rows == 1 ? 0.5f : r / (float) (rows - 1);
            float z = (tz - 0.5f) * cfg.length * 0.92f;
            // Hull narrows towards the bow.
            float widthScale = 1f - 0.45f * Mth.clamp01((tz - 0.55f) / 0.45f);
            for (int c = 0; c < cols; c++) {
                float tx = cols == 1 ? 0.5f : c / (float) (cols - 1);
                pointX[k] = (tx - 0.5f) * cfg.beam * widthScale;
                pointZ[k] = z;
                k++;
            }
        }
    }

    public int samplePointCount() {
        return pointX.length;
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

    public float speedKnots() {
        return body.speed() * 1.94384f;
    }

    public void step(float dt, CollisionWorld cw) {
        body.clearAccumulators();
        body.addForce(0f, -cfg.mass * RigidBody.GRAVITY, 0f);

        rudderAngle = Mth.approach(rudderAngle,
                Mth.clamp(rudderInput, -1f, 1f) * cfg.maxRudderDeg * Mth.DEG2RAD, 2.4f * dt);

        int lu = cw.landUse(body.px, body.pz);
        if (ai.shuvrajeetkamila.mumbai.world.LandUse.isWater(lu)) {
            waterClass = WaterModel.classOf(lu);
        }

        float volumePerPoint = cfg.displacement / pointX.length;
        int submergedCount = 0;
        float deepest = 0f;

        for (int i = 0; i < pointX.length; i++) {
            float lx = pointX[i];
            float lz = pointZ[i];
            float ly = -cfg.draft;
            body.localToWorld(lx, ly, lz, tmp);
            double wx = body.px + tmp[0];
            double wy = body.py + tmp[1];
            double wz = body.pz + tmp[2];

            float surface = cw.waterSurface(wx, wz);
            if (surface == Float.NEGATIVE_INFINITY) {
                pointDepth[i] = 0f;
                continue;
            }
            float depth = (float) (surface - wy);
            pointDepth[i] = Math.max(0f, depth);
            if (depth <= 0f) {
                continue;
            }
            submergedCount++;
            deepest = Math.max(deepest, depth);

            // Buoyancy: rho * g * displaced volume, saturating once the point is a
            // draft's depth under. Because each point integrates its own depth, a hull
            // that leans puts the low side deeper and gets pushed back up: the righting
            // moment is emergent, not scripted.
            float fill = Mth.clamp01(depth / Math.max(0.25f, cfg.draft * 1.7f));
            float force = 1025f * RigidBody.GRAVITY * volumePerPoint * fill;
            body.addForceAtLocalPoint(0f, force, 0f, lx, ly, lz);

            // Local damping at the point gives roll and pitch damping for free. It is
            // capped so that slamming into the water from a height cannot inject an
            // impulse larger than the hull could physically absorb in one step.
            body.velocityAtLocalPoint(lx, ly, lz, vel);
            float d = cfg.dragVertical / pointX.length * fill;
            float fy = Mth.clamp(-vel[1] * d, -cfg.mass * 4f, cfg.mass * 4f);
            body.addForceAtLocalPoint(-vel[0] * d * 0.25f, fy,
                    -vel[2] * d * 0.25f, lx, ly, lz);
        }

        submergedFraction = submergedCount / (float) pointX.length;

        if (submergedFraction > 0.02f) {
            applyHydrodynamics(dt, cw);
            applyPropulsion(dt);
        } else {
            // Airborne after a wave jump: gravity plus genuine aerodynamic drag on a
            // very unaerodynamic object.
            float v = body.speed();
            float q = 0.5f * 1.164f * v;
            float area = cfg.beam * cfg.hullHeight;
            body.addForce(-body.vx * q * area, -body.vy * q * area * 1.4f,
                    -body.vz * q * area);
        }

        checkBeaching(cw);

        body.limitAngularRate(3f);
        body.limitSpeed(60f);
        body.integrate(dt);

        float speed = body.speed();
        wakeStrength = Mth.clamp01(speed / 12f) * submergedFraction;
    }

    private void applyHydrodynamics(float dt, CollisionWorld cw) {
        // Current pushes the hull.
        cw.water().currentAt(body.px, body.pz, waterClass, tmp);
        float relX = body.vx - tmp[0];
        float relY = body.vy;
        float relZ = body.vz - tmp[2];

        body.worldToLocal(relX, relY, relZ, tmp);
        float vLong = tmp[2];
        float vLat = tmp[0];
        float vVert = tmp[1];

        float sub = Mth.clamp01(submergedFraction);
        float dragLong = -Mth.sign(vLong) * cfg.dragLongitudinal * vLong * vLong * sub;
        float dragLat = -Mth.sign(vLat) * cfg.dragLateral * vLat * vLat * sub;
        float dragVert = -Mth.sign(vVert) * cfg.dragVertical * vVert * Math.abs(vVert) * 0.2f * sub;
        body.addLocalForceAtLocalPoint(dragLat, dragVert, dragLong, 0f, -cfg.draft * 0.4f, 0f);

        // Planing: above a threshold the hull climbs onto its own bow wave.
        float fwd = Math.abs(vLong);
        planing = fwd > cfg.planingSpeed && sub > 0.2f;
        if (planing) {
            float over = Mth.clamp((fwd - cfg.planingSpeed)
                    / Math.max(1f, cfg.planingSpeed), 0f, 1f);
            // Dynamic lift from the planing surface, limited to a fraction of the
            // weight and scaled by how much hull is actually in the water, then paid
            // for with induced drag. Without the drag term the lift is a free energy
            // source and the hull porpoises itself into orbit.
            float lift = cfg.planingLift * cfg.mass * RigidBody.GRAVITY * over * sub;
            lift = Math.min(lift, cfg.mass * RigidBody.GRAVITY * 0.75f);
            body.addForceAtLocalPoint(0f, lift, 0f, 0f, 0f, cfg.length * 0.10f);
            body.addLocalForceAtLocalPoint(0f, 0f, -lift * 0.26f * Mth.sign(vLong),
                    0f, 0f, 0f);
        }

        // Yaw damping and directional stability (a keel wants to point forwards).
        float yawRateLocal;
        body.worldToLocal(body.wx, body.wy, body.wz, tmp);
        yawRateLocal = tmp[1];
        float yawDamp = -yawRateLocal * cfg.angularDrag * sub;
        float rollDamp = -tmp[2] * cfg.angularDrag * 2.2f * sub;
        float pitchDamp = -tmp[0] * cfg.angularDrag * 2.6f * sub;
        body.localToWorld(pitchDamp, yawDamp, rollDamp, tmp);
        body.addTorque(tmp[0], tmp[1], tmp[2]);

        // Weathervaning: lateral slip generates a restoring yaw moment.
        float restoring = -vLat * Math.abs(vLong) * cfg.dragLateral * 0.08f * sub;
        body.addLocalForceAtLocalPoint(restoring, 0f, 0f, 0f, 0f, -cfg.length * 0.3f);

        // Righting moment from the metacentre: M = -m g GM sin(heel). Using sin rather
        // than the raw angle means the hull recovers from any heel short of exactly
        // upside down, which is the behaviour of a real small craft with reserve
        // buoyancy in its superstructure.
        float roll = body.roll();
        float righting = -(float) Math.sin(roll) * cfg.mass * RigidBody.GRAVITY
                * cfg.metacentricHeight * sub;
        float pitch = body.pitch();
        float pitchRight = -(float) Math.sin(pitch) * cfg.mass * RigidBody.GRAVITY
                * cfg.metacentricHeight * 1.9f * sub;
        body.localToWorld(pitchRight, 0f, righting, tmp);
        body.addTorque(tmp[0], tmp[1], tmp[2]);
    }

    private void applyPropulsion(float dt) {
        float t = Mth.clamp(throttle, -1f, 1f);
        float thrust = t >= 0f ? t * cfg.maxThrust
                : t * cfg.maxThrust * cfg.reverseFraction;
        thrust *= Mth.clamp01(submergedFraction * 2f);
        if (submergedFraction < 0.08f) {
            thrust = 0f;        // propeller out of the water
        }
        // Thrust acts at the propeller, below and behind the centre of mass, which is why
        // opening the throttle makes the bow rise.
        body.addLocalForceAtLocalPoint(0f, 0f, thrust, 0f, -cfg.propDepth, -cfg.length * 0.45f);

        // Rudder: deflects the propeller race, so it only bites with water flowing past.
        float flow = Math.abs(body.forwardSpeed()) + Math.abs(thrust) / (cfg.mass * 0.35f);
        float side = 0.5f * 1025f * flow * flow * cfg.rudderArea
                * (float) Math.sin(rudderAngle);
        side = Mth.clamp(side, -cfg.maxThrust * 1.2f, cfg.maxThrust * 1.2f);
        float dir = body.forwardSpeed() >= 0f || throttle >= 0f ? 1f : -1f;
        body.addLocalForceAtLocalPoint(-side * dir, 0f, 0f,
                0f, -cfg.propDepth, -cfg.length * 0.46f);
    }

    private void checkBeaching(CollisionWorld cw) {
        float ground = cw.terrainHeight(body.px, body.pz);
        float keel = (float) body.py - cfg.draft;
        beached = keel < ground + 0.05f;
        if (!beached) {
            return;
        }
        float penetration = ground + 0.05f - keel;
        // Sand pushes back and scrubs off speed instead of letting the hull sink.
        float push = penetration * cfg.mass * 42f;
        body.addForce(0f, Math.min(push, cfg.mass * RigidBody.GRAVITY * 3f), 0f);
        float friction = Math.min(1f, penetration * 2.2f);
        body.addForce(-body.vx * cfg.mass * friction * 2.4f, 0f,
                -body.vz * cfg.mass * friction * 2.4f);
        body.wy *= 0.9f;
    }

    /** Deepest submerged point, used for the HUD and for swamping. */
    public float maxDepth() {
        float d = 0f;
        for (int i = 0; i < pointDepth.length; i++) {
            d = Math.max(d, pointDepth[i]);
        }
        return d;
    }

    /** True when the deck is awash and the boat is taking water. */
    public boolean swamped() {
        return maxDepth() > cfg.hullHeight * 1.05f && submergedFraction > 0.9f;
    }
}
