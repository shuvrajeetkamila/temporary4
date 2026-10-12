package ai.shuvrajeetkamila.mumbai.sim;

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
    }

    public static final class Ped {
        public double x, z, y;
        public double heading;
        public double speed;
        public double phase;
        public int outfit;
        public boolean active;
        public double wanderTimer;
    }

    private final WorldData world;
    private final Random rng = new Random(0x4D554D42L);

    private Car[] cars = new Car[0];
    private Ped[] peds = new Ped[0];
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
            if (world.graphAttr[e * 5] <= 0) { continue; }
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
    private int pickEdgeNear(double x, double z, double radius) {
        int rings = Math.max(1, (int) Math.ceil(radius / CELL));
        long cx = (long) Math.floor(x / CELL);
        long cz = (long) Math.floor(z / CELL);
        int tries = 0;
        while (tries < 24) {
            tries++;
            long gx = cx + rng.nextInt(rings * 2 + 1) - rings;
            long gz = cz + rng.nextInt(rings * 2 + 1) - rings;
            int[] arr = edgeGrid.get((gx << 32) ^ (gz & 0xFFFFFFFFL));
            if (arr != null && arr.length > 0) {
                return arr[rng.nextInt(arr.length)];
            }
        }
        return -1;
    }

    public void update(double dt, double px, double pz, double spawnRadius,
                       double despawnRadius) {
        for (int i = 0; i < carCap && i < cars.length; i++) {
            Car c = cars[i];
            if (!c.active) {
                if (rng.nextDouble() < 0.35) { spawnCar(c, px, pz, spawnRadius); }
                continue;
            }
            stepCar(c, dt);
            double dx = c.x - px, dz = c.z - pz;
            if (dx * dx + dz * dz > despawnRadius * despawnRadius) { c.active = false; }
        }
        for (int i = 0; i < pedCap && i < peds.length; i++) {
            Ped p = peds[i];
            if (!p.active) {
                if (rng.nextDouble() < 0.3) { spawnPed(p, px, pz, spawnRadius * 0.35); }
                continue;
            }
            stepPed(p, dt);
            double dx = p.x - px, dz = p.z - pz;
            if (dx * dx + dz * dz > (despawnRadius * 0.4) * (despawnRadius * 0.4)) {
                p.active = false;
            }
        }
    }

    private void spawnCar(Car c, double px, double pz, double radius) {
        int e = pickEdgeNear(px, pz, radius);
        if (e < 0) { return; }
        c.edge = e;
        c.t = rng.nextDouble();
        c.forward = rng.nextBoolean();
        c.colour = rng.nextInt(10);
        c.body = rng.nextInt(5);
        double limit = world.graphAttr[e * 5];
        c.speed = limit / 3.6 * (0.72 + rng.nextDouble() * 0.3);
        placeOnEdge(c);
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
        // traffic drives on the left, so that is where the car belongs.
        c.x = x + dz * LANE_OFFSET;
        c.z = z - dx * LANE_OFFSET;
        c.y = world.height(c.x, c.z);
        c.heading = Math.atan2(dx, -dz);
    }

    private void stepCar(Car c, double dt) {
        int a = world.graphEdge[c.edge * 2];
        int b = world.graphEdge[c.edge * 2 + 1];
        double ax = world.graphNodeXZ[a * 2], az = world.graphNodeXZ[a * 2 + 1];
        double bx = world.graphNodeXZ[b * 2], bz = world.graphNodeXZ[b * 2 + 1];
        double len = Math.hypot(bx - ax, bz - az);
        if (len < 1e-3) { c.active = false; return; }
        c.t += c.speed * dt / len;
        if (c.t >= 1.0) {
            // Hand off to a connected edge, or retire.
            int next = pickEdgeNear(c.x, c.z, 90);
            if (next < 0) { c.active = false; return; }
            c.edge = next;
            c.t = 0;
            c.forward = rng.nextBoolean();
            double limit = world.graphAttr[next * 5];
            c.speed = limit / 3.6 * (0.72 + rng.nextDouble() * 0.3);
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
        p.y = world.height(x, z);
        p.heading = rng.nextDouble() * Math.PI * 2;
        p.speed = 0.9 + rng.nextDouble() * 0.7;
        p.outfit = rng.nextInt(8);
        p.phase = rng.nextDouble() * 6.28;
        p.wanderTimer = 2 + rng.nextDouble() * 6;
        p.active = true;
    }

    private void stepPed(Ped p, double dt) {
        p.wanderTimer -= dt;
        if (p.wanderTimer <= 0) {
            p.heading += (rng.nextDouble() - 0.5) * 1.4;
            p.wanderTimer = 2 + rng.nextDouble() * 6;
        }
        double nx = p.x + Math.sin(p.heading) * p.speed * dt;
        double nz = p.z - Math.cos(p.heading) * p.speed * dt;
        if (!world.inBounds(nx, nz) || !world.isLand(nx, nz)) {
            p.heading += Math.PI * 0.75;
            return;
        }
        p.x = nx;
        p.z = nz;
        p.y = world.height(nx, nz);
        p.phase += dt * p.speed * 2.1;
    }

    public void clear() {
        for (Car c : cars) { c.active = false; }
        for (Ped p : peds) { p.active = false; }
    }
}
