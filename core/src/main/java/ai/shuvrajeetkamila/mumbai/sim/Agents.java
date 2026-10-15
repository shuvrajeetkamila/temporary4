package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.game.Wanted;
import ai.shuvrajeetkamila.mumbai.math.MathUtil;
import ai.shuvrajeetkamila.mumbai.world.WorldData;

import java.util.Random;

/**
 * Ambient traffic and pedestrians.
 *
 * <p>These are deliberately kinematic: they follow the road graph at a sensible
 * speed, keep left as Indian traffic does, slow for the car in front and stop
 * at the edge of their world. They are not rigid bodies. Simulating a hundred
 * and forty full vehicle models on a phone alongside the player's own is not
 * affordable, and pretending otherwise would be the dishonest option. This is
 * recorded in the delivery notes as a known limit.
 *
 * <p>What they do get right is placement: they spawn on real OSM road
 * centrelines using the compiled graph, keep to the correct side of the road
 * for left-hand traffic, and despawn when the player leaves. Selection uses a
 * spatial hash over edge endpoints, because scanning three hundred thousand
 * nodes per spawn is not affordable either.
 */
public final class Agents {

    /** Left-hand traffic: vehicles keep to the left of the centreline. */
    public static final double LANE_OFFSET = 2.6;

    public static final class Car {
        public double x, z, y;
        public double heading;
        public double speed;
        public int edge;
        public double t;              // 0..1 along the edge
        public boolean forward;
        public int colour;
        public int body;              // which mesh variant
        public boolean active;
        /** {@link ai.shuvrajeetkamila.mumbai.game.VehicleKind} ordinal. */
        public int kindOrdinal;
        /** Standing still at the kerb rather than driving a route. */
        public boolean parked;
        /** Cruise speed for this edge. {@code speed} is braked away from it. */
        public double targetSpeed;
        /** Seconds left on the horn. Drives the HUD and, later, audio. */
        public double hornTimer;
        /** True while yielding to the player. */
        public boolean braking;
        /** Debounce for the ramming test. */
        public boolean hitRecently;
        /** A police unit hunting the player. Does not yield, does not despawn. */
        public boolean police;
        /** Siren phase, seconds. Drives the flashing light in the renderer. */
        public double siren;
        /** Velocity while knocked off its route by an impact, m/s. */
        public double shoveX, shoveZ;
        /** Seconds left free-rolling before it rejoins the graph. */
        public double shoveTimer;
        /** Yaw rate while shoved, rad/s. */
        public double spin;
        /** Accumulated crash damage, 0 pristine to 1 wrecked. */
        public double damage;
    }

    public static final class Ped {
        public double x, z, y;
        public double heading;
        public double speed;
        public double phase;
        public int outfit;
        public boolean active;
        public double wanderTimer;
        /** Seconds left of fleeing. Non-zero means startled by the player. */
        public double fleeTimer;
        /** Seconds left on the ground after being hit. */
        public double downTimer;
        /** An officer on foot, closing on the player rather than wandering. */
        public boolean cop;
    }

    /** Pedestrians run over since the counters were last read. */
    public int pedsHit;
    /** Vehicles rammed since the counters were last read. */
    public int carsHit;
    /** Whether the last vehicle rammed was a police unit. */
    public int policeHit;

    /** Contact distances for the crime test, metres. */
    /** Civilian pedestrian outfits. Keep in step with Meshes.PED_OUTFITS. */
    public static final int OUTFITS = 4;

    /** The khaki. One more mesh than the civilian outfits. */
    public static final int OUTFIT_POLICE = OUTFITS;

    /** How fast an officer on foot runs, m/s. */
    private static final double COP_SPEED = 4.3;

    /** Officers give up outside this, so a chase has an end. */
    private static final double COP_LEASH_M = 230.0;

    /**
     * How high the ground is for an agent.
     *
     * <p>Defaults to the elevation grid, which is wrong on a bridge: a
     * car crossing the Sea Link was placed at sea level and drove along
     * under its own deck. The {@code Engine} replaces this with one that
     * knows about decks.
     */
    public interface Surface {
        double heightAt(double x, double z);
    }

    private Surface surface;

    public void setSurface(Surface s) { this.surface = s; }

    private double groundAt(double x, double z) {
        Surface s = surface;
        return s != null ? s.heightAt(x, z) : world.height(x, z);
    }

    private static final double HIT_PED_M = 2.3;
    private static final double HIT_CAR_M = 3.4;

    /** Multiplier police apply to the speed limit. Set from the wanted level. */
    public double pursuitPace = 1.1;

    private final WorldData world;
    private final Random rng = new Random(0x4D554D42L);

    private Car[] cars = new Car[0];
    private Ped[] peds = new Ped[0];
    private double playerX, playerZ, playerVx, playerVz, playerSpeed;

    private int carCap;
    private int pedCap;

    // Spatial hash over graph edges so spawning is not a linear scan.
    private static final double CELL = 256.0;
    private final java.util.HashMap<Long, int[]> edgeGrid = new java.util.HashMap<>();

    public Agents(WorldData world) {
        this.world = world;
        buildEdgeIndex();
    }

    private void buildEdgeIndex() {
        java.util.HashMap<Long, java.util.ArrayList<Integer>> acc = new java.util.HashMap<>();
        int n = world.graphEdge.length / 2;
        for (int e = 0; e < n; e++) {
            // Only drivable edges carry a positive speed limit.
            if (world.edgeSpeedKph(e) <= 0) { continue; }
            int a = world.graphEdge[e * 2];
            double x = world.graphNodeXZ[a * 2];
            double z = world.graphNodeXZ[a * 2 + 1];
            long key = cellKey(x, z);
            acc.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(e);
        }
        for (java.util.Map.Entry<Long, java.util.ArrayList<Integer>> en : acc.entrySet()) {
            java.util.ArrayList<Integer> v = en.getValue();
            int[] arr = new int[v.size()];
            for (int i = 0; i < arr.length; i++) { arr[i] = v.get(i); }
            edgeGrid.put(en.getKey(), arr);
        }
    }

    private static long cellKey(double x, double z) {
        long cx = (long) Math.floor(x / CELL);
        long cz = (long) Math.floor(z / CELL);
        return (cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public void setCaps(int carCap, int pedCap) {
        this.carCap = carCap;
        this.pedCap = pedCap;
        if (cars.length < carCap) {
            Car[] nc = new Car[carCap];
            System.arraycopy(cars, 0, nc, 0, cars.length);
            for (int i = cars.length; i < nc.length; i++) { nc[i] = new Car(); }
            cars = nc;
        }
        if (peds.length < pedCap) {
            Ped[] np = new Ped[pedCap];
            System.arraycopy(peds, 0, np, 0, peds.length);
            for (int i = peds.length; i < np.length; i++) { np[i] = new Ped(); }
            peds = np;
        }
        // Retire anything above the new cap so eco mode really does less work.
        for (int i = carCap; i < cars.length; i++) { cars[i].active = false; }
        for (int i = pedCap; i < peds.length; i++) { peds[i].active = false; }
    }

    public Car[] cars() { return cars; }

    /** An unused car slot, or -1 if every one is taken. */
    /**
     * Puts a police unit on the road near the player.
     *
     * <p>Spawned further out than ambient traffic and never in view range
     * of the spawn suppression zone, so a car does not simply appear in
     * the mirror. They do not despawn on distance the way traffic does;
     * they are removed when the wanted level drops.
     *
     * @return true if one was placed
     */
    public boolean spawnPolice(double px, double pz, double radius) {
        int slot = freeCarSlot();
        if (slot < 0) { return false; }
        Car c = cars[slot];
        int e = pickEdgeNear(px, pz, radius);
        if (e < 0) { return false; }
        c.edge = e;
        c.t = rng.nextDouble();
        c.forward = rng.nextBoolean();
        c.colour = 0;
        c.parked = false;
        c.police = true;
        c.siren = rng.nextDouble() * 2.0;
        c.kindOrdinal = VehicleKind.POLICE.ordinal();
        c.body = VehicleKind.POLICE.ordinal();
        c.targetSpeed = world.edgeSpeedKph(e) / 3.6 * pursuitPace;
        c.speed = c.targetSpeed * 0.6;
        c.active = true;
        placeOnEdge(c);
        return true;
    }

    /** How many police units are on the road. */
    public int policeCount() {
        int n = 0;
        for (Car c : cars) { if (c.active && c.police) { n++; } }
        return n;
    }

    /** Metres to the nearest active police unit, or a large number. */
    public double nearestPolice(double px, double pz) {
        double best = Double.MAX_VALUE;
        for (Car c : cars) {
            if (!c.active || !c.police) { continue; }
            double d = Math.hypot(c.x - px, c.z - pz);
            if (d < best) { best = d; }
        }
        return best;
    }

    /** Stands every police unit down, after an arrest or a clean getaway. */
    public void clearPolice() {
        for (Car c : cars) {
            if (c.police) { c.active = false; c.police = false; }
        }
    }

    /** Reads and resets the crime counters. */
    public void clearCrimeCounters() {
        pedsHit = 0;
        carsHit = 0;
        policeHit = 0;
    }

    public int freeCarSlot() {
        for (int i = 0; i < cars.length; i++) {
            if (!cars[i].active) { return i; }
        }
        return -1;
    }

    public Ped[] peds() { return peds; }

    public int activeCars() {
        int n = 0;
        for (int i = 0; i < carCap && i < cars.length; i++) {
            if (cars[i].active) { n++; }
        }
        return n;
    }

    public int activePeds() {
        int n = 0;
        for (int i = 0; i < pedCap && i < peds.length; i++) {
            if (peds[i].active) { n++; }
        }
        return n;
    }

    /** Pick a drivable edge whose start node is within radius of the point. */
    /**
     * A random road edge within {@code radius} of a point, or -1.
     *
     * <p>The distance test at the end is not a detail. The spatial index
     * is on a 256 m grid, so asking for edges within 300 m used to hand
     * back anything in the surrounding five-by-five block - a 1.6 km
     * square, eighteen times the area actually asked for. Traffic spread
     * itself over all of it and the street the player was standing in
     * stayed empty however much the radius was tightened.
     */
    private int pickEdgeNear(double x, double z, double radius) {
        int rings = Math.max(1, (int) Math.ceil(radius / CELL));
        long cx = (long) Math.floor(x / CELL);
        long cz = (long) Math.floor(z / CELL);
        double r2 = radius * radius;
        for (int tries = 0; tries < 40; tries++) {
            long gx = cx + rng.nextInt(rings * 2 + 1) - rings;
            long gz = cz + rng.nextInt(rings * 2 + 1) - rings;
            int[] arr = edgeGrid.get((gx << 32) ^ (gz & 0xFFFFFFFFL));
            if (arr == null || arr.length == 0) { continue; }
            int e = arr[rng.nextInt(arr.length)];
            int a = world.graphEdge[e * 2];
            int b = world.graphEdge[e * 2 + 1];
            double mx = (world.graphNodeXZ[a * 2] + world.graphNodeXZ[b * 2]) * 0.5;
            double mz = (world.graphNodeXZ[a * 2 + 1]
                    + world.graphNodeXZ[b * 2 + 1]) * 0.5;
            double dx = mx - x, dz = mz - z;
            if (dx * dx + dz * dz <= r2) { return e; }
        }
        return -1;
    }

    /**
     * Advance the ambient city.
     *
     * <p>The player's position and velocity are passed in because traffic
     * and pedestrians react to them. Without that this is a cellular
     * automaton you happen to be standing in: cars drive through you at a
     * constant speed and nobody looks up. Reacting is most of what makes a
     * street feel inhabited rather than animated.
     */
    public void update(double dt, double px, double pz, double spawnRadius,
                       double despawnRadius) {
        update(dt, px, pz, 0, 0, spawnRadius, despawnRadius);
    }

    public void update(double dt, double px, double pz,
                       double pvx, double pvz,
                       double spawnRadius, double despawnRadius) {
        this.playerX = px;
        this.playerZ = pz;
        this.playerVx = pvx;
        this.playerVz = pvz;
        this.playerSpeed = Math.hypot(pvx, pvz);
        for (int i = 0; i < carCap && i < cars.length; i++) {
            Car c = cars[i];
            if (!c.active) {
                if (rng.nextDouble() < 0.35) { spawnCar(c, px, pz, spawnRadius); }
                continue;
            }
            // A parked car stays where it was put. This is what lets the
            // vehicle you got out of still be standing there when you turn
            // round, instead of driving itself away down the nearest edge.
            if (!c.parked || c.shoveTimer > 0) { stepCar(c, dt); }
            if (c.hornTimer > 0) { c.hornTimer -= dt; }
            if (c.police) { c.siren += dt; }
            double dx = c.x - px, dz = c.z - pz;
            double cd2 = dx * dx + dz * dz;

            // Ramming. Counted once per contact rather than once per step,
            // or sitting against a bumper would rack up heat forever.
            if (cd2 < HIT_CAR_M * HIT_CAR_M) {
                if (!c.hitRecently) {
                    c.hitRecently = true;
                    carsHit++;
                    if (c.police) { policeHit++; }
                }
            } else if (cd2 > (HIT_CAR_M * 2.2) * (HIT_CAR_M * 2.2)) {
                c.hitRecently = false;
            }

            // Police stay until they are stood down; traffic drifts away.
            if (!c.police && cd2 > despawnRadius * despawnRadius) {
                c.active = false;
            }
        }

        // Once every car has moved, settle the ones that moved into each
        // other. Done after the whole loop rather than inside it so the
        // result does not depend on the order the cars happen to sit in
        // the array.
        resolveCarCollisions(cars, Math.min(carCap, cars.length), dt);
        for (int i = 0; i < pedCap && i < peds.length; i++) {
            Ped p = peds[i];
            if (!p.active) {
                if (rng.nextDouble() < 0.3) { spawnPed(p, px, pz, spawnRadius * 0.35); }
                continue;
            }
            if (p.downTimer > 0) {
                // Knocked down: stays put, then gets up and is removed. An
                // officer gets back up and carries on, because an arrest
                // you can end by driving at the constable is not an arrest.
                p.downTimer -= dt;
                if (p.downTimer <= 0 && !p.cop) { p.active = false; }
            } else if (p.cop) {
                stepCop(p, dt);
            } else {
                stepPed(p, dt);
            }
            double dx = p.x - px, dz = p.z - pz;
            // One threshold, shared with the wanted system, so there is no
            // band where you can knock somebody down for free.
            if (p.downTimer <= 0 && dx * dx + dz * dz < HIT_PED_M * HIT_PED_M
                    && playerSpeed >= Wanted.CRIME_SPEED) {
                pedsHit++;
                p.downTimer = 4.0;
                p.speed = 0;
            }
            if (p.cop) { continue; }
            if (dx * dx + dz * dz > (despawnRadius * 0.4) * (despawnRadius * 0.4)) {
                p.active = false;
            }
        }
    }

    /**
     * How much of the car budget stands at the kerb rather than driving.
     *
     * <p>Traffic alone is not a city you can play in. The whole first move
     * of this kind of game - walk up to something, get in, drive off -
     * needs there to be something standing still to walk up to, and until
     * this existed the only way into a car was to step in front of moving
     * traffic and hope.
     */
    private static final double PARKED_SHARE = 0.42;

    /** Kerb clearance beyond the running lane, metres. */
    private static final double PARK_OFFSET = 2.35;

    private void spawnCar(Car c, double px, double pz, double radius) {
        int e = pickEdgeNear(px, pz, radius);
        if (e < 0) { return; }
        c.edge = e;
        c.t = rng.nextDouble();
        c.forward = rng.nextBoolean();
        c.colour = rng.nextInt(10);
        // Nothing parks on a motorway, and nothing parks on a slip road
        // either: both would put a stationary car in a live lane with no
        // kerb to stand on.
        c.parked = world.edgeClass(e) > 1 && rng.nextDouble() < PARKED_SHARE;
        placeOnEdge(c);
        // Weighted by the real mix of the city's traffic, and filtered by
        // where each type is actually licensed to go: no autorickshaw turns
        // up south of Mahim, because none is allowed to.
        VehicleKind kind = VehicleKind.sample(rng.nextDouble(), c.z);
        c.kindOrdinal = kind.ordinal();
        c.body = kind.ordinal();
        double limit = world.edgeSpeedKph(e);
        // Each type runs at its own fraction of the limit. A loaded tempo
        // does not keep up with a scooter, and the difference in how traffic
        // bunches is most of what makes a street look busy rather than
        // marching in step.
        double pace;
        switch (kind) {
            case SCOOTER: pace = 0.92; break;
            case AUTORICKSHAW: pace = 0.70; break;
            case TEMPO: pace = 0.62; break;
            case DOUBLE_DECKER: case CITY_BUS: pace = 0.58; break;
            case KAALI_PEELI: pace = 0.78; break;
            default: pace = 0.85; break;
        }
        c.targetSpeed = limit / 3.6 * pace * (0.86 + rng.nextDouble() * 0.26);
        c.speed = c.parked ? 0 : c.targetSpeed;
        if (c.parked) {
            // A parked car is not a bus or a tempo waiting at a stop; those
            // are the two things you cannot get into, and leaving them
            // standing empty at the kerb would be a tease.
            if (!kind.enterable) {
                c.kindOrdinal = VehicleKind.HATCHBACK.ordinal();
                c.body = c.kindOrdinal;
            }
            // Some of them face the other way. A street where every car
            // points the same way looks like a car park, not a street.
            if (rng.nextDouble() < 0.34) {
                c.heading = MathUtil.wrapPi(c.heading + Math.PI);
            }
        }
        // Do not pop a car into existence right in front of the player.
        double dx = c.x - px, dz = c.z - pz;
        if (dx * dx + dz * dz < 45 * 45) { return; }
        c.active = true;
    }

    private void placeOnEdge(Car c) {
        int a = world.graphEdge[c.edge * 2];
        int b = world.graphEdge[c.edge * 2 + 1];
        double ax = world.graphNodeXZ[a * 2], az = world.graphNodeXZ[a * 2 + 1];
        double bx = world.graphNodeXZ[b * 2], bz = world.graphNodeXZ[b * 2 + 1];
        double t = c.forward ? c.t : 1 - c.t;
        double x = ax + (bx - ax) * t;
        double z = az + (bz - az) * t;
        double dx = bx - ax, dz = bz - az;
        double len = Math.hypot(dx, dz);
        if (len < 1e-4) { c.active = false; return; }
        dx /= len; dz /= len;
        if (!c.forward) { dx = -dx; dz = -dz; }
        // Left of travel in an X-east, Z-south frame is (dz, -dx). Indian
        // traffic drives on the left, so that is where the car belongs -
        // and a parked car belongs further left again, against the kerb,
        // out of the lane the moving traffic is using.
        double off = LANE_OFFSET + (c.parked ? PARK_OFFSET : 0);
        c.x = x + dz * off;
        c.z = z - dx * off;
        c.y = groundAt(c.x, c.z);
        c.heading = Math.atan2(dx, -dz);
    }

    /**
     * How close the player has to be before traffic takes any notice, m.
     * Beyond this nothing is evaluated at all, which keeps the whole
     * reaction system off the profile for the hundred-odd cars that are not
     * near anybody.
     */
    private static final double NOTICE_M = 26.0;

    /**
     * The speed a car should be doing with the player nearby. Pure, static
     * and side-effect free, so the geometry can be tested on its own.
     *
     * <p>Only yields for a player genuinely in the way: within a short
     * distance, ahead rather than behind, and within about a lane's width of
     * the car's centreline. A car that brakes for something it has already
     * passed looks broken, and one that brakes for the pavement beside it
     * makes the whole road crawl.
     *
     * @return the speed it should be doing, between 0 and {@code cruise}
     */
    public static double desiredSpeed(double carX, double carZ, double heading,
                                      double px, double pz, double cruise) {
        double dx = px - carX, dz = pz - carZ;
        double d2 = dx * dx + dz * dz;
        if (d2 > NOTICE_M * NOTICE_M) { return cruise; }
        double d = Math.sqrt(Math.max(1e-6, d2));

        // Heading is a bearing, so forward is (sin, -cos) in the X-east,
        // Z-south world frame.
        double fx = Math.sin(heading), fz = -Math.cos(heading);
        double ahead = (dx / d) * fx + (dz / d) * fz;

        // Perpendicular distance from the centreline: the magnitude of the
        // 2D cross product with the unit forward vector. It has to be the
        // cross product and not a sum of products - the first version was
        // the latter, which is right whenever the heading is axis-aligned
        // and wrong everywhere else. A player dead ahead of a car on a 45
        // degree heading measured as 7 m off to the side, so nothing on a
        // diagonal road ever braked.
        double lateral = Math.abs(dx * fz - dz * fx);

        if (ahead < 0.25 || lateral > 3.4) { return cruise; }

        double clear = 5.5;
        if (d <= clear) { return 0; }
        // Squared rather than linear, so a car two lanes back barely lifts
        // off while one about to hit you stops hard.
        double k = (d - clear) / (NOTICE_M - clear);
        return cruise * k * k;
    }

    /**
     * Whether a pedestrian should start running, given the player's speed
     * and how far away they are. Static for the same reason as above.
     *
     * <p>Triggered by a vehicle moving fast and close, not by the player
     * merely existing: someone walking past on the pavement is not a reason
     * to bolt, and a crowd that scatters from a stationary person makes a
     * city feel hostile rather than alive.
     */
    public static boolean shouldFlee(double playerSpeed, double distance) {
        return playerSpeed > 5.0 && distance < 14.0;
    }

    /** Bearing that points a pedestrian directly away from the player. */
    public static double fleeHeading(double pedX, double pedZ,
                                     double px, double pz) {
        double dx = pedX - px, dz = pedZ - pz;
        double d = Math.hypot(dx, dz);
        if (d < 1e-6) { return 0; }
        return Math.atan2(dx / d, -(dz / d));
    }

    /** Applies {@link #desiredSpeed} and records the flags the HUD reads. */
    private double yieldFor(Car c, double cruise) {
        double want = desiredSpeed(c.x, c.z, c.heading, playerX, playerZ, cruise);
        c.braking = want < cruise * 0.9;
        if (want <= 0 && c.hornTimer <= 0 && playerSpeed < 2.0) { c.hornTimer = 1.1; }
        return want;
    }

    /**
     * The connected edge that gets a pursuing car closest to the player.
     *
     * <p>Not a route: it is a greedy choice at each junction, which is what
     * makes police feel like they are chasing rather than following an
     * optimal path. It also means they can be lost by doubling back, which
     * is the whole point of a chase. A proper search over 352,000 edges
     * every time a car reached a junction would cost more than the rest of
     * the simulation together and would be impossible to escape.
     */
    private int pickEdgeToward(double x, double z, double tx, double tz,
                               double radius) {
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        int tries = 0;
        for (int attempt = 0; attempt < 24; attempt++) {
            int e = pickEdgeNear(x, z, radius);
            if (e < 0) { continue; }
            tries++;
            int a = world.graphEdge[e * 2], b = world.graphEdge[e * 2 + 1];
            double ax = world.graphNodeXZ[a * 2], az = world.graphNodeXZ[a * 2 + 1];
            double bx = world.graphNodeXZ[b * 2], bz = world.graphNodeXZ[b * 2 + 1];
            // Whichever end of the candidate is nearer the target.
            double da = Math.hypot(ax - tx, az - tz);
            double db = Math.hypot(bx - tx, bz - tz);
            double score = Math.min(da, db);
            if (score < bestScore) { bestScore = score; best = e; }
        }
        return tries == 0 ? -1 : best;
    }

    /** How long a struck car stays off its route before rejoining it. */
    private static final double SHOVE_S = 2.6;

    /**
     * A car that has been hit, moving under the impact rather than along
     * its edge.
     *
     * <p>Without this the collision is invisible: {@code stepCar} finishes
     * with {@code placeOnEdge}, which overwrites x and z from the graph,
     * so a struck car was back in its lane on the next frame as though
     * nothing had happened.
     */
    private void stepShoved(Car c, double dt) {
        c.x += c.shoveX * dt;
        c.z += c.shoveZ * dt;
        c.heading = MathUtil.wrapPi(c.heading + c.spin * dt);
        c.y = groundAt(c.x, c.z);
        double decay = Math.exp(-2.4 * dt);
        c.shoveX *= decay;
        c.shoveZ *= decay;
        c.spin *= decay;
        c.speed = Math.hypot(c.shoveX, c.shoveZ);
        c.shoveTimer -= dt;
        if (c.shoveTimer <= 0) {
            // Rejoin the network at whatever edge is nearest to where it
            // has ended up, facing the way it was pushed.
            int e = pickEdgeNear(c.x, c.z, 70);
            if (e < 0) { c.active = false; return; }
            c.edge = e;
            c.t = rng.nextDouble();
            c.speed = 0;
            placeOnEdge(c);
        }
    }

    // ---- traffic against traffic -----------------------------------
    //
    // Until now a car yielded to the player and to nothing else, so
    // traffic drove straight through itself. At a junction a dozen cars
    // would converge on the same two metres of tarmac and sit inside
    // one another, which reads as a single flickering car, and a queue
    // never formed anywhere because nothing was ever in anyone's way.

    /** Half the length of a car for the overlap test, m. */
    static double halfLength(Car c) {
        return VehicleKind.values()[c.kindOrdinal].lengthM * 0.5;
    }

    /** Half the width of a car for the overlap test, m. */
    static double halfWidth(Car c) {
        return VehicleKind.values()[c.kindOrdinal].widthM * 0.5;
    }

    /**
     * Whether two cars are touching.
     *
     * <p>A circle round each car cannot do this: a circle big enough to
     * cover a five metre taxi end to end is two and a half metres wide,
     * and traffic in the oncoming lane passes within two. Every car
     * would have spent the whole drive shoving the cars going the other
     * way off the road.
     *
     * <p>So each car's box is tested in its own frame and both have to
     * agree. That is the separating-axis test over the four face axes,
     * which for two boxes of roughly the same size is exact enough that
     * nothing visible gets through it.
     */
    static boolean touching(Car a, Car b) {
        return inBox(a, b.x, b.z, halfLength(b), halfWidth(b))
                && inBox(b, a.x, a.z, halfLength(a), halfWidth(a));
    }

    /** Whether a point, grown by a margin, falls inside a car's box. */
    private static boolean inBox(Car c, double px, double pz,
                                 double growLen, double growWid) {
        double fx = Math.sin(c.heading), fz = -Math.cos(c.heading);
        double dx = px - c.x, dz = pz - c.z;
        double along = dx * fx + dz * fz;
        double side = dx * fz - dz * fx;
        return Math.abs(along) <= halfLength(c) + growLen
                && Math.abs(side) <= halfWidth(c) + growWid;
    }

    /** World velocity of a car, shoved or driving. */
    private static double velX(Car c) {
        return c.shoveTimer > 0 ? c.shoveX : Math.sin(c.heading) * c.speed;
    }

    private static double velZ(Car c) {
        return c.shoveTimer > 0 ? c.shoveZ : -Math.cos(c.heading) * c.speed;
    }

    /**
     * The speed this car can hold given the car in front of it.
     *
     * <p>Reuses the same function that brakes for the player, because a
     * car ahead and a player ahead are the same problem, and having one
     * definition means the diagonal-road bug that was fixed in one is
     * fixed in both.
     */
    private double headway(Car c, double cruise) {
        double want = cruise;
        for (int i = 0; i < carCap && i < cars.length; i++) {
            Car o = cars[i];
            if (o == c || !o.active) { continue; }
            double dx = o.x - c.x, dz = o.z - c.z;
            if (Math.abs(dx) > NOTICE_M || Math.abs(dz) > NOTICE_M) { continue; }
            want = Math.min(want,
                    desiredSpeed(c.x, c.z, c.heading, o.x, o.z, cruise));
            if (want <= 0) { return 0; }
        }
        return want;
    }

    /**
     * Separate and bounce every pair of cars that ended the step inside
     * one another.
     *
     * <p>This is every pair against every other, which is the thing one
     * is told not to write. At the cap of {@value #MAX_CARS} that is
     * about four thousand rejections of two subtractions each, once per
     * frame, and a grid to avoid it would cost more to rebuild than it
     * saves. If the cap ever rises past a few hundred this wants the
     * same spatial index the road lookup uses.
     */
    static void resolveCarCollisions(Car[] cars, int n, double dt) {
        for (int i = 0; i < n && i < cars.length; i++) {
            Car a = cars[i];
            if (!a.active || a.parked) { continue; }
            for (int j = i + 1; j < n && j < cars.length; j++) {
                Car b = cars[j];
                if (!b.active) { continue; }
                double dx = b.x - a.x, dz = b.z - a.z;
                if (Math.abs(dx) > 12 || Math.abs(dz) > 12) { continue; }
                if (!touching(a, b)) { continue; }

                double d = Math.hypot(dx, dz);
                if (d < 1e-6) { dx = 1; dz = 0; d = 1; }
                double ux = dx / d, uz = dz / d;

                double avx = velX(a), avz = velZ(a);
                double bvx = velX(b), bvz = velZ(b);
                // Closing speed along the line of centres. Positive means
                // a is driving into b.
                double rel = (avx - bvx) * ux + (avz - bvz) * uz;

                double ma = Math.max(120.0,
                        VehicleKind.values()[a.kindOrdinal].massKg);
                double mb = Math.max(120.0,
                        VehicleKind.values()[b.kindOrdinal].massKg);

                if (rel > 0) {
                    double e = 0.12;            // sheet metal does not bounce
                    double jj = (1 + e) * rel / (1 / ma + 1 / mb);
                    double ga = jj / ma, gb = jj / mb;
                    // A glancing blow spins the lighter car more than a
                    // square one does, which is what the sideways part of
                    // the impulse means.
                    double spin = (dx * avz - dz * avx) / Math.max(4.0, d * d);
                    shove(a, avx - ux * ga, avz - uz * ga, -spin * 0.5);
                    shove(b, bvx + ux * gb, bvz + uz * gb, spin * 0.5);
                    damage(a, rel * mb / (ma + mb));
                    damage(b, rel * ma / (ma + mb));
                }

                // Push them apart whether or not they were closing, or a
                // pair that arrived overlapping stays overlapping: the
                // impulse is zero when they are already separating and
                // nothing else would ever move them.
                double overlap = Math.max(0.0, halfLength(a) + halfLength(b) - d);
                if (overlap > 0) {
                    double push = Math.min(overlap, 6.0 * dt) * 0.5;
                    a.x -= ux * push; a.z -= uz * push;
                    b.x += ux * push; b.z += uz * push;
                    if (a.shoveTimer <= 0) { a.shoveTimer = 0.25; }
                    if (b.shoveTimer <= 0) { b.shoveTimer = 0.25; }
                }
            }
        }
    }

    /**
     * Add crash damage from an impact at the given closing speed.
     *
     * <p>Below walking pace nothing happens, so nudging through a queue
     * does not slowly destroy the car. Above that it is linear in the
     * speed: a 50 km/h shunt is most of a wreck.
     */
    public static void damage(Car c, double closingMs) {
        if (closingMs <= 1.5) { return; }
        c.damage = Math.min(1.0, c.damage + (closingMs - 1.5) / 26.0);
    }

    /** Knock a car off its route. Velocity in m/s, spin in rad/s. */
    public static void shove(Car c, double vx, double vz, double spin) {
        if (c.parked) { c.parked = false; }
        c.shoveX = vx;
        c.shoveZ = vz;
        c.spin = spin;
        c.shoveTimer = SHOVE_S;
    }

    /**
     * Put an officer on the pavement.
     *
     * <p>The response to a wanted level used to be entirely vehicular,
     * which meant that on foot down an alley nothing could ever reach
     * you. An officer is an ordinary pedestrian agent with somewhere to
     * be: the arrest itself is the same proximity test that a patrol car
     * uses, so there is one definition of being caught.
     */
    public boolean spawnFootPolice(double px, double pz, double radius) {
        int slot = -1;
        for (int i = 0; i < pedCap && i < peds.length; i++) {
            if (!peds[i].active) { slot = i; break; }
        }
        // A free civilian slot will do if the crowd is at its cap: an
        // officer who cannot be sent because the pavement is busy is worse
        // than one passer-by fewer.
        if (slot < 0) {
            for (int i = 0; i < pedCap && i < peds.length; i++) {
                if (!peds[i].cop) { slot = i; break; }
            }
        }
        if (slot < 0) { return false; }
        {
            Ped p = peds[slot];
            // Several candidate spots, not one. At the seafront a third of
            // the ring around the player is water, so a single draw sent no
            // officer at all better than a third of the time.
            double x = 0, z = 0;
            boolean found = false;
            for (int attempt = 0; attempt < 14 && !found; attempt++) {
                double a = rng.nextDouble() * Math.PI * 2;
                double r = radius * (0.55 + rng.nextDouble() * 0.45);
                x = px + Math.cos(a) * r;
                z = pz + Math.sin(a) * r;
                found = world.inBounds(x, z) && world.isLand(x, z);
            }
            if (!found) { return false; }
            p.x = x;
            p.z = z;
            p.y = groundAt(x, z);
            p.heading = Math.atan2(px - x, -(pz - z));
            p.speed = COP_SPEED;
            p.phase = rng.nextDouble() * 6.28;
            p.outfit = OUTFIT_POLICE;
            p.cop = true;
            p.fleeTimer = 0;
            p.downTimer = 0;
            p.wanderTimer = 0;
            p.active = true;
            return true;
        }
    }

    /** Officers on foot currently chasing. */
    public int footPoliceCount() {
        int n = 0;
        for (Ped p : peds) { if (p.active && p.cop) { n++; } }
        return n;
    }

    /** Stand them all down. */
    public void clearFootPolice() {
        for (Ped p : peds) { if (p.cop) { p.active = false; p.cop = false; } }
    }

    /** Distance to the closest officer on foot, or a large number. */
    public double nearestFootPolice(double px, double pz) {
        double best = Double.MAX_VALUE;
        for (Ped p : peds) {
            if (!p.active || !p.cop || p.downTimer > 0) { continue; }
            best = Math.min(best, Math.hypot(p.x - px, p.z - pz));
        }
        return best;
    }

    /** One officer's step: run at the player until out of range. */
    private void stepCop(Ped p, double dt) {
        double dx = playerX - p.x;
        double dz = playerZ - p.z;
        double d = Math.hypot(dx, dz);
        if (d > COP_LEASH_M) { p.active = false; p.cop = false; return; }
        p.heading = Math.atan2(dx, -dz);
        // Close to arm's length and then stop, rather than shouldering
        // the player across the pavement for ever.
        double want = d < 2.0 ? 0.0 : COP_SPEED;
        p.speed += MathUtil.clamp(want - p.speed, -8 * dt, 6 * dt);
        p.x += Math.sin(p.heading) * p.speed * dt;
        p.z += -Math.cos(p.heading) * p.speed * dt;
        p.y = groundAt(p.x, p.z);
        p.phase += dt * p.speed * 2.1;
    }

    private void stepCar(Car c, double dt) {
        if (c.shoveTimer > 0) { stepShoved(c, dt); return; }
        int a = world.graphEdge[c.edge * 2];
        int b = world.graphEdge[c.edge * 2 + 1];
        double ax = world.graphNodeXZ[a * 2], az = world.graphNodeXZ[a * 2 + 1];
        double bx = world.graphNodeXZ[b * 2], bz = world.graphNodeXZ[b * 2 + 1];
        double len = Math.hypot(bx - ax, bz - az);
        if (len < 1e-3) { c.active = false; return; }

        // Approach the speed this edge wants, braking far harder than it
        // accelerates. Real traffic does; symmetric rates make a queue
        // breathe in and out like a concertina.
        // Police do not give way. Everything else does.
        double want = c.police ? c.targetSpeed : yieldFor(c, c.targetSpeed);
        // Police do not give way to the player, but they do not drive
        // through the traffic jam they caused either.
        want = Math.min(want, headway(c, c.targetSpeed));
        double rate = want < c.speed ? 7.0 : 2.1;
        c.speed += Math.max(-rate * dt, Math.min(rate * dt, want - c.speed));
        if (c.speed < 0) { c.speed = 0; }

        c.t += c.speed * dt / len;
        if (c.t >= 1.0) {
            // Hand off to a connected edge, or retire.
            int next = c.police
                    ? pickEdgeToward(c.x, c.z, playerX, playerZ, 110)
                    : pickEdgeNear(c.x, c.z, 90);
            if (next < 0) { c.active = false; return; }
            c.edge = next;
            c.t = 0;
            double limit = world.edgeSpeedKph(next);
            if (c.police) {
                // Head down the new edge from whichever end is further from
                // the player, so the car drives towards them rather than
                // away and then back.
                int a2 = world.graphEdge[next * 2], b2 = world.graphEdge[next * 2 + 1];
                double axx = world.graphNodeXZ[a2 * 2], azz = world.graphNodeXZ[a2 * 2 + 1];
                double bxx = world.graphNodeXZ[b2 * 2], bzz = world.graphNodeXZ[b2 * 2 + 1];
                c.forward = Math.hypot(axx - playerX, azz - playerZ)
                        > Math.hypot(bxx - playerX, bzz - playerZ);
                c.targetSpeed = limit / 3.6 * pursuitPace;
            } else {
                c.forward = rng.nextBoolean();
                c.targetSpeed = limit / 3.6 * (0.72 + rng.nextDouble() * 0.3);
            }
        }
        placeOnEdge(c);
    }

    private void spawnPed(Ped p, double px, double pz, double radius) {
        double ang = rng.nextDouble() * Math.PI * 2;
        double r = 30 + rng.nextDouble() * radius;
        double x = px + Math.cos(ang) * r;
        double z = pz + Math.sin(ang) * r;
        if (!world.inBounds(x, z) || !world.isLand(x, z)) { return; }
        if (world.slope(x, z) > 0.45) { return; }
        p.x = x;
        p.z = z;
        p.y = groundAt(x, z);
        p.heading = rng.nextDouble() * Math.PI * 2;
        p.speed = 0.9 + rng.nextDouble() * 0.7;
        // Must match the number of pedestrian meshes the renderer has.
        // It was eight against four models, so five eighths of the city
        // wore the same clothes.
        p.outfit = rng.nextInt(OUTFITS);
        p.phase = rng.nextDouble() * 6.28;
        p.wanderTimer = 2 + rng.nextDouble() * 6;
        p.fleeTimer = 0;
        p.active = true;
    }

    /**
     * Pedestrian step, including getting out of the way.
     *
     * <p>What triggers it is a vehicle moving fast and close, not merely the
     * player existing. Someone walking past you on the pavement has no
     * reason to bolt, and a crowd that scatters from a stationary person is
     * a well-known way to make a city feel hostile rather than alive.
     */
    private void stepPed(Ped p, double dt) {
        double dx = p.x - playerX, dz = p.z - playerZ;
        double d2 = dx * dx + dz * dz;

        if (p.fleeTimer > 0) {
            p.fleeTimer -= dt;
        } else if (shouldFlee(playerSpeed, Math.sqrt(d2))) {
            // Directly away from the threat, with a little scatter so a
            // group does not move off as one block.
            p.heading = fleeHeading(p.x, p.z, playerX, playerZ)
                    + (rng.nextDouble() - 0.5) * 0.9;
            p.fleeTimer = 1.6 + rng.nextDouble() * 1.8;
        }

        double pace = p.speed;
        if (p.fleeTimer > 0) {
            pace = p.speed * 2.6;          // a run, not a brisk walk
        } else {
            p.wanderTimer -= dt;
            if (p.wanderTimer <= 0) {
                p.heading += (rng.nextDouble() - 0.5) * 1.4;
                p.wanderTimer = 2 + rng.nextDouble() * 6;
            }
        }

        double nx = p.x + Math.sin(p.heading) * pace * dt;
        double nz = p.z - Math.cos(p.heading) * pace * dt;
        if (!world.inBounds(nx, nz) || !world.isLand(nx, nz)) {
            p.heading += Math.PI * 0.75;
            return;
        }
        p.x = nx;
        p.z = nz;
        p.y = groundAt(nx, nz);
        p.phase += dt * pace * 2.1;
    }

    public void clear() {
        for (Car c : cars) { c.active = false; }
        for (Ped p : peds) { p.active = false; }
    }
}
