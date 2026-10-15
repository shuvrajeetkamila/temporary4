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

    /** How long a service holds at an intermediate stop, seconds. */
    public static final double STOP_DWELL_S = 16.0;

    /** How close you have to be standing to get aboard, metres. */
    public static final double BOARD_M = 9.0;

    /**
     * Distance between stops, metres, by mode.
     *
     * <p>Real spacings. Churchgate to Marine Lines is 1.2 km and the
     * suburban average over the Western line is about 1.6; the Metro
     * runs a little tighter and the monorail tighter still. A ferry has
     * no intermediate calls - it runs terminal to terminal - so it gets
     * no stops beyond the two ends of its route.
     */
    static double stopSpacing(int cls) {
        switch (cls) {
            case WorldData.CLS_RAIL: return 1600.0;
            case WorldData.CLS_LIGHT_RAIL:
            case WorldData.CLS_SUBWAY: return 1150.0;
            case WorldData.CLS_MONORAIL: return 900.0;
            default: return 0.0;
        }
    }

    /**
     * Headway for a class of line, seconds.
     *
     * <p>A timetable the player can read: trains leave at a fixed
     * interval from a fixed phase, so the board at a station can say
     * when the next one is and be right when it turns up. Roughly the
     * real peak intervals - the suburban network is famously every
     * three minutes and the monorail famously is not.
     */
    public static double headway(int cls) {
        switch (cls) {
            case WorldData.CLS_RAIL: return 210.0;
            case WorldData.CLS_LIGHT_RAIL:
            case WorldData.CLS_SUBWAY: return 300.0;
            case WorldData.CLS_MONORAIL: return 600.0;
            default: return 1200.0;
        }
    }

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
        /** Which stop on the line the service is running towards. */
        public int nextStop = -1;
        /** True while held at a stop with the doors open. */
        public boolean atStop;
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

    /** Where the stations are on each route, as distance along it. */
    private final double[][] stopS;

    private static final double[] EMPTY = new double[0];

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
            // Long enough for the longest consist to sit clear of both
            // ends, whichever way round it is.
            double need = 2 * (RAIL_CARS * (CAR_LEN + CAR_GAP)) + 40;
            if (routeLength[r] > Math.max(900, need)
                    && world.transitPointCount(r) >= 2) {
                ok[m++] = r;
            }
        }
        usable = ok;
        usableCount = m;

        // Stops. Evenly spaced along each alignment at the real spacing
        // for the mode, kept clear of both termini by a train length so
        // a rake holding at the first stop is not hanging off the end of
        // its own line.
        stopS = new double[n][];
        for (int i = 0; i < m; i++) {
            int r = ok[i];
            double spacing = stopSpacing(world.transitClass(r));
            double len = routeLength[r];
            double clear = RAIL_CARS * (CAR_LEN + CAR_GAP) + 10;
            if (spacing <= 0 || len < 2 * clear + spacing) {
                stopS[r] = new double[0];
                continue;
            }
            int count = (int) Math.floor((len - 2 * clear) / spacing);
            if (count < 1) { stopS[r] = new double[0]; continue; }
            double[] st = new double[count];
            double first = (len - (count - 1) * spacing) * 0.5;
            for (int k = 0; k < count; k++) { st[k] = first + k * spacing; }
            stopS[r] = st;
        }
        services = new Service[24];
        for (int i = 0; i < services.length; i++) { services[i] = new Service(); }
    }

    public Service[] services() { return services; }

    /** The first stop ahead of {@code s} in the direction of travel. */
    private static int nearestStopAhead(double[] stops, double s, int dir) {
        int best = -1;
        double bestGap = Double.MAX_VALUE;
        for (int i = 0; i < stops.length; i++) {
            double gap = (stops[i] - s) * dir;
            if (gap > 0.5 && gap < bestGap) { bestGap = gap; best = i; }
        }
        return best;
    }

    /** How many stations are on a route. */
    public int stopCount(int route) {
        return route < 0 || route >= stopS.length || stopS[route] == null
                ? 0 : stopS[route].length;
    }

    /** Where a station is, in world metres. */
    public double[] stopPoint(int route, int i) {
        return pointAt(route, stopS[route][i]);
    }

    /**
     * The service a player standing here could board, or -1.
     *
     * <p>Only while it is actually held at a station with its doors
     * open. You cannot flag down a running train, and a train that has
     * reversed at a terminus is not boarding either - it is turning
     * round.
     */
    public int boardableAt(double px, double pz) {
        int best = -1;
        double bestD2 = BOARD_M * BOARD_M;
        for (int i = 0; i < services.length; i++) {
            Service sv = services[i];
            if (!sv.active || !sv.atStop || sv.dwell <= 0) { continue; }
            // Anywhere along the train, not just at the nose: a nine car
            // rake is 195 m long and you board the door in front of you.
            double trainLen = sv.carriages * (CAR_LEN + CAR_GAP);
            for (double back = 0; back <= trainLen; back += 12.0) {
                double[] q = pointAt(sv.route, sv.s - back * sv.dir);
                double dx = q[0] - px, dz = q[1] - pz;
                double d2 = dx * dx + dz * dz;
                if (d2 < bestD2) { bestD2 = d2; best = i; }
            }
        }
        return best;
    }

    /** Cached line names, built on first use. */
    private String[] nameCache;

    /**
     * A name for a line, built from where it actually goes.
     *
     * <p>"Local train" told you the mode and nothing else, and there is
     * no route name in the data - OSM splits railways at every signal
     * and the compiler chains the fragments, so whatever name a
     * fragment carried is long gone by the time there is a line. The
     * terminals are the honest thing to name it after, and they are
     * derived rather than invented: the nearest named place to each
     * end of the alignment.
     */
    public String routeName(int route) {
        if (route < 0 || route >= world.transitCount()) { return ""; }
        if (nameCache == null) { nameCache = new String[world.transitCount()]; }
        String cached = nameCache[route];
        if (cached != null) { return cached; }

        int cls = world.transitClass(route);
        String mode;
        switch (cls) {
            case WorldData.CLS_RAIL: mode = "Local"; break;
            case WorldData.CLS_LIGHT_RAIL:
            case WorldData.CLS_SUBWAY: mode = "Metro"; break;
            case WorldData.CLS_MONORAIL: mode = "Monorail"; break;
            default: mode = "Ferry"; break;
        }
        int n = world.transitPointCount(route);
        String out = mode;
        if (n >= 2) {
            String a = placeNear(world.transitX(route, 0),
                    world.transitZ(route, 0));
            String b = placeNear(world.transitX(route, n - 1),
                    world.transitZ(route, n - 1));
            if (a != null && b != null && !a.equals(b)) {
                out = mode + " - " + a + " / " + b;
            } else if (a != null) {
                out = mode + " - " + a;
            } else if (b != null) {
                out = mode + " - " + b;
            }
        }
        nameCache[route] = out;
        return out;
    }

    private String placeNear(double x, double z) {
        ai.shuvrajeetkamila.mumbai.world.Places p = world.places();
        if (p == null) { return null; }
        ai.shuvrajeetkamila.mumbai.world.Places.Place q =
                p.nearest(x, z, 1500);
        return q == null ? null : q.name;
    }

    /**
     * Seconds until the next departure from a station on this line.
     *
     * <p>A pure function of the clock, like the traffic lights: the
     * board at a platform nobody is standing on is still right. The
     * phase is scattered by the route index so the whole network does
     * not leave at once.
     */
    public static double nextDeparture(int route, int cls, double clock) {
        double h = headway(cls);
        double phase = (route * 0.6180339887) % 1.0;
        double t = clock / h + phase;
        return (1.0 - (t - Math.floor(t))) * h;
    }

    /** Human-readable mode, for the boarding prompt. */
    public String labelFor(Service sv) {
        switch (sv.cls) {
            case WorldData.CLS_RAIL: return "local train";
            case WorldData.CLS_LIGHT_RAIL:
            case WorldData.CLS_SUBWAY: return "Metro";
            case WorldData.CLS_MONORAIL: return "monorail";
            default: return "ferry";
        }
    }

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
            sv.nextStop = -1;
            sv.atStop = false;
            // Start somewhere the whole consist fits whichever way it is
            // pointing: running forwards it occupies [s - trainLen, s],
            // backwards [s, s + trainLen], so the safe band for either is
            // trainLen in from both ends.
            double trainLen = sv.carriages * (CAR_LEN + CAR_GAP);
            double lo = trainLen + 2.0;
            double hi = routeLength[r] - trainLen - 2.0;
            if (hi <= lo) { continue; }
            sv.s = lo + rng.nextDouble() * (hi - lo);
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
        sv.atStop = false;

        // Slow for the stop ahead, and hold there when it arrives. A
        // service that only ever stopped at the ends of its line was not
        // a railway, it was a shuttle, and there was nowhere to get on.
        double[] stops = stopS[sv.route] == null
                ? EMPTY : stopS[sv.route];
        if (stops.length > 0) {
            if (sv.nextStop < 0 || sv.nextStop >= stops.length) {
                sv.nextStop = nearestStopAhead(stops, sv.s, sv.dir);
            }
            if (sv.nextStop >= 0) {
                double togo = (stops[sv.nextStop] - sv.s) * sv.dir;
                if (togo <= 0.5) {
                    sv.s = stops[sv.nextStop];
                    sv.speed = 0;
                    sv.dwell = STOP_DWELL_S;
                    sv.atStop = true;
                    sv.nextStop += sv.dir;
                    if (sv.nextStop < 0 || sv.nextStop >= stops.length) {
                        sv.nextStop = -1;
                    }
                    place(sv);
                    return;
                }
                // Brake at a comfortable rate rather than arriving at
                // line speed and stopping dead.
                double brakeable = Math.sqrt(Math.max(0, 2 * 0.8 * togo));
                if (brakeable < sv.targetSpeed) {
                    sv.speed = Math.min(sv.speed, Math.max(1.5, brakeable));
                }
            }
        }

        double rate = sv.vessel ? 0.35 : 0.9;
        sv.speed += Math.max(-rate * dt * 2.2,
                Math.min(rate * dt, sv.targetSpeed - sv.speed));
        if (sv.speed < 0) { sv.speed = 0; }
        sv.s += sv.speed * dt * sv.dir;

        double len = routeLength[sv.route];
        double trainLen = sv.carriages * (CAR_LEN + CAR_GAP);

        // Reversing at a terminus swaps which end of the consist leads.
        // The train does not move: the nose jumps to the far end of it.
        //
        // This matters because carriages are placed at s - back * dir, so
        // running forwards they occupy [s - trainLen, s] and running
        // backwards they occupy [s, s + trainLen]. Without the jump, a
        // train that reversed at the far end kept its nose at the
        // terminus and asked for carriage positions beyond the end of the
        // line, where pointAt clamps - so the whole rake collapsed onto
        // the last point and the carriages sat 6 m apart instead of 21.
        if (sv.dir > 0 && sv.s >= len - 2.0) {
            sv.s = len - 2.0 - trainLen;
            sv.dir = -1;
            sv.nextStop = -1;
            sv.dwell = TURNAROUND_S;
            sv.speed = 0;
        } else if (sv.dir < 0 && sv.s <= 2.0) {
            sv.s = 2.0 + trainLen;
            sv.dir = 1;
            sv.nextStop = -1;
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
            // One table, in TransitStops, because the platforms are
            // built from it too. Mumbai's Metro is a viaduct, not a
            // street tram - Line 1 runs above the Andheri-Ghatkopar
            // road the whole way - and it was falling through to the
            // surface case and ploughing along the carriageway.
            default: return ground
                    + ai.shuvrajeetkamila.mumbai.world.TransitStops
                            .railHeight(sv.cls);
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
