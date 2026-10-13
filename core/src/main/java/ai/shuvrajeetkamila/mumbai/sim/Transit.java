package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.world.WorldData;

import java.util.Random;

/**
 * Trains and ships: the suburban rail, the metro, the monorail and the
 * vessels working the harbour.
 *
 * <p>These run on their own network rather than the road graph, which is
 * filtered to driveable classes. Alignments are real OSM geometry,
 * stitched into continuous routes by the compiler, so a local on the
 * Western line follows the Western line.
 *
 * <p>A train is one point on a route plus a length. It is drawn as a set
 * of carriages placed back along the polyline from the nose, so the
 * train bends through curves instead of being a rigid box. That costs a
 * little arc-length walking per carriage per frame and is the single
 * thing that makes rail read as rail at a distance.
 *
 * <p>Services run to and fro along their route rather than looping: a
 * line is a line, and a train that vanishes off one end and reappears at
 * the other is more jarring than one that waits and comes back.
 */
public final class Transit {

    /** Carriages per service, by kind. */
    public static final int RAIL_CARS = 9;
    public static final int METRO_CARS = 6;
    public static final int MONO_CARS = 4;

    /** Carriage dimensions, metres. */
    public static final double CAR_LEN = 20.5;
    public static final double CAR_GAP = 1.2;

    /** Dwell at a terminus before setting off the other way, seconds. */
    public static final double TURNAROUND_S = 14.0;

    /** One train or vessel. */
    public static final class Service {
        public boolean active;
        /** Index into the world's transit routes. */
        public int route;
        /** Road class of that route: tells the renderer what to draw. */
        public int cls;
        /** Metres travelled from the start of the route. */
        public double s;
        public double speed;
        public double targetSpeed;
        /** +1 towards the end of the route, -1 back towards the start. */
        public int dir = 1;
        public double dwell;
        public int carriages = 1;
        /** Nose position and heading, refreshed each step. */
        public double x, y, z, heading;
        /** True for a ferry or cargo vessel rather than a train. */
        public boolean vessel;
    }

    private final WorldData world;
    private final Random rng = new Random(0x7A11_5E7L);
    private final Service[] services;

    /** Cached route lengths, so a step does not re-walk the polyline. */
    private final double[] routeLength;

    /** Routes worth running a service on, by index into the world's list. */
    private final int[] usable;
    private final int usableCount;

    /** How many services may run at once. Tied to the quality tier. */
    public int cap = 14;

    public Transit(WorldData world) {
        this.world = world;
        int n = world.transitCount();
        routeLength = new double[n];
        int[] ok = new int[n];
        int m = 0;
        for (int r = 0; r < n; r++) {
            routeLength[r] = world.transitLength(r);
            // A service needs room for the whole train plus somewhere to go.
            if (routeLength[r] > 900 && world.transitPointCount(r) >= 2) {
                ok[m++] = r;
            }
        }
        usable = ok;
        usableCount = m;
        services = new Service[24];
        for (int i = 0; i < services.length; i++) { services[i] = new Service(); }
    }

    public Service[] services() { return services; }

    public int routeCount() { return usableCount; }

    /** Active services, for telemetry. */
    public int activeCount() {
        int n = 0;
        for (Service s : services) { if (s.active) { n++; } }
        return n;
    }

    /**
     * Steps every service and spawns new ones near the player.
     *
     * @param radius how far away a service may still be worth simulating
     */
    public void update(double dt, double px, double pz, double radius) {
        if (usableCount == 0) { return; }
        int live = 0;
        for (int i = 0; i < services.length; i++) {
            Service sv = services[i];
            if (!sv.active) { continue; }
            step(sv, dt);
            double d = Math.hypot(sv.x - px, sv.z - pz);
            // Generous: a train is long and visible from much further than
            // a car, and one blinking out mid-platform is worse than the
            // cost of keeping it.
            if (d > radius * 1.8) { sv.active = false; } else { live++; }
        }
        if (live >= cap) { return; }
        for (int i = 0; i < services.length && live < cap; i++) {
            if (services[i].active) { continue; }
            if (rng.nextDouble() > 0.22) { continue; }
            if (spawn(services[i], px, pz, radius)) { live++; }
        }
    }

    /** Puts a service on a route that passes near the player. */
    private boolean spawn(Service sv, double px, double pz, double radius) {
        for (int attempt = 0; attempt < 10; attempt++) {
            int r = usable[rng.nextInt(usableCount)];
            // Walk the route for a point in range, and start there.
            int n = world.transitPointCount(r);
            int probe = rng.nextInt(n);
            double qx = world.transitX(r, probe), qz = world.transitZ(r, probe);
            double d = Math.hypot(qx - px, qz - pz);
            if (d > radius) { continue; }

            int cls = world.transitClass(r);
            sv.route = r;
            sv.cls = cls;
            sv.vessel = WorldData.isWaterRoute(cls);
            sv.carriages = carriagesFor(cls);
            sv.dir = rng.nextBoolean() ? 1 : -1;
            sv.dwell = 0;
            // Start far enough along that the whole train is on the route.
            double trainLen = sv.carriages * (CAR_LEN + CAR_GAP);
            double room = routeLength[r] - trainLen - 10;
            if (room <= 10) { continue; }
            sv.s = trainLen + rng.nextDouble() * room;
            sv.targetSpeed = cruiseFor(cls);
            sv.speed = sv.targetSpeed * (0.4 + rng.nextDouble() * 0.6);
            sv.active = true;
            place(sv);
            return true;
        }
        return false;
    }

    static int carriagesFor(int cls) {
        switch (cls) {
            case WorldData.CLS_RAIL: return RAIL_CARS;
            case WorldData.CLS_LIGHT_RAIL:
            case WorldData.CLS_SUBWAY: return METRO_CARS;
            case WorldData.CLS_MONORAIL: return MONO_CARS;
            default: return 1;      // a vessel is one hull
        }
    }

    /** Cruise speed in m/s. Suburban rail is the fast one. */
    static double cruiseFor(int cls) {
        switch (cls) {
            case WorldData.CLS_RAIL: return 22.0;          // ~80 km/h
            case WorldData.CLS_LIGHT_RAIL:
            case WorldData.CLS_SUBWAY: return 17.0;        // ~61 km/h
            case WorldData.CLS_MONORAIL: return 13.5;      // ~49 km/h
            default: return 6.2;                            // a ship, ~12 kt
        }
    }

    private void step(Service sv, double dt) {
        if (sv.dwell > 0) {
            sv.dwell -= dt;
            sv.speed = 0;
            place(sv);
            return;
        }

        double rate = sv.vessel ? 0.35 : 0.9;
        sv.speed += Math.max(-rate * dt * 2.2,
                Math.min(rate * dt, sv.targetSpeed - sv.speed));
        if (sv.speed < 0) { sv.speed = 0; }
        sv.s += sv.speed * dt * sv.dir;

        double len = routeLength[sv.route];
        double trainLen = sv.carriages * (CAR_LEN + CAR_GAP);
        // Reverse at the ends. The nose must stay on the route and there
        // must be room behind it for the carriages.
        if (sv.dir > 0 && sv.s >= len - 2.0) {
            sv.s = len - 2.0;
            sv.dir = -1;
            sv.dwell = TURNAROUND_S;
            sv.speed = 0;
        } else if (sv.dir < 0 && sv.s <= trainLen + 2.0) {
            sv.s = trainLen + 2.0;
            sv.dir = 1;
            sv.dwell = TURNAROUND_S;
            sv.speed = 0;
        }
        place(sv);
    }

    private void place(Service sv) {
        double[] p = pointAt(sv.route, sv.s);
        sv.x = p[0];
        sv.z = p[1];
        // Heading along the direction of travel.
        double ahead = sv.s + 6.0 * sv.dir;
        double[] q = pointAt(sv.route, ahead);
        double dx = q[0] - p[0], dz = q[1] - p[1];
        if (Math.hypot(dx, dz) > 1e-6) {
            sv.heading = Math.atan2(dx, -dz);
        }
        sv.y = heightFor(sv);
    }

    /**
     * Where a service sits vertically.
     *
     * <p>A subway is underground and must not be drawn ploughing through
     * the street; a monorail runs on a viaduct well above it; a ship sits
     * at sea level whatever the terrain says. Only surface rail follows
     * the ground.
     */
    private double heightFor(Service sv) {
        if (sv.vessel) { return 0.0; }
        double ground = world.height(sv.x, sv.z);
        switch (sv.cls) {
            case WorldData.CLS_SUBWAY: return ground - 12.0;
            case WorldData.CLS_MONORAIL: return ground + 8.5;
            default: return ground + 0.9;
        }
    }

    /**
     * Point at a given arc length along a route.
     *
     * <p>Linear walk from the start. Routes are a few hundred points and
     * this runs once per carriage per service per frame, which at the
     * caps here is a few thousand segment steps: cheaper than the
     * cumulative-length table it would otherwise need, and with no table
     * to keep in step with the geometry.
     */
    public double[] pointAt(int route, double s) {
        int n = world.transitPointCount(route);
        if (n < 2) { return new double[]{0, 0}; }
        if (s <= 0) {
            return new double[]{world.transitX(route, 0), world.transitZ(route, 0)};
        }
        double acc = 0;
        for (int i = 1; i < n; i++) {
            double ax = world.transitX(route, i - 1), az = world.transitZ(route, i - 1);
            double bx = world.transitX(route, i), bz = world.transitZ(route, i);
            double seg = Math.hypot(bx - ax, bz - az);
            if (acc + seg >= s) {
                double t = seg < 1e-9 ? 0 : (s - acc) / seg;
                return new double[]{ax + (bx - ax) * t, az + (bz - az) * t};
            }
            acc += seg;
        }
        return new double[]{world.transitX(route, n - 1), world.transitZ(route, n - 1)};
    }

    /**
     * Fills {@code out} with the pose of each carriage, four floats each:
     * x, y, z, heading. Carriages trail back along the route from the
     * nose, so the train follows curves.
     *
     * @return the number of carriages written
     */
    public int carriagePoses(Service sv, float[] out, int offset, double ox,
                             double oy, double oz) {
        int wrote = 0;
        for (int c = 0; c < sv.carriages; c++) {
            if ((offset + wrote) * 4 + 4 > out.length) { break; }
            double back = c * (CAR_LEN + CAR_GAP) + CAR_LEN * 0.5;
            // Always measured back along the route against the direction of
            // travel, so reversing swaps which end leads without the
            // carriages jumping through each other.
            double at = sv.s - back * sv.dir;
            double[] p = pointAt(sv.route, at);
            double[] q = pointAt(sv.route, at + 4.0 * sv.dir);
            double dx = q[0] - p[0], dz = q[1] - p[1];
            double h = Math.hypot(dx, dz) > 1e-6 ? Math.atan2(dx, -dz) : sv.heading;
            int o = (offset + wrote) * 4;
            out[o] = (float) (p[0] - ox);
            out[o + 1] = (float) (sv.y - oy);
            out[o + 2] = (float) (p[1] - oz);
            out[o + 3] = (float) h;
            wrote++;
        }
        return wrote;
    }
}
