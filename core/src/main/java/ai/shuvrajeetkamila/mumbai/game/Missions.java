package ai.shuvrajeetkamila.mumbai.game;

import ai.shuvrajeetkamila.mumbai.world.Places;
import ai.shuvrajeetkamila.mumbai.world.WorldData;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Jobs, money and progression: the reason to drive somewhere rather than
 * merely being able to.
 *
 * <p>Three kinds of work, each tied to the vehicle you are sitting in, so
 * choosing a vehicle is a choice about what to do rather than cosmetic:
 *
 * <ul>
 *   <li><b>Fares</b> in a kaali-peeli taxi. A passenger flags you down, you
 *       take them where they are going, you get paid by the kilometre.</li>
 *   <li><b>Deliveries</b> in a tempo or van. Timed, and the clock is the
 *       whole difficulty.</li>
 *   <li><b>Street races</b> in anything else with four wheels. A circuit of
 *       checkpoints against a target time.</li>
 * </ul>
 *
 * <p>Jobs are offered automatically rather than behind an accept prompt.
 * Driving up to a marker and having work begin keeps the player in the
 * world; a menu does the opposite.
 *
 * <p>Every stop is snapped onto the road graph before it is used. A
 * destination taken straight from the OpenStreetMap place table routinely
 * sits inside a building or in the middle of a block, and a checkpoint you
 * cannot physically reach is a mission that can only be failed.
 *
 * <p>All distances are metres and all times seconds, as everywhere else.
 * Money is whole rupees, held as an int: fares are rounded once, at the
 * point of payment, so the total can never drift.
 */
public final class Missions {

    /** What kind of work a job is. */
    public enum Kind { TAXI, DELIVERY, RACE }

    /** How close you must get to a stop, in metres. */
    public static final double ARRIVE_M = 11.0;

    /** And how slowly, so you have to actually stop rather than clip it. */
    public static final double ARRIVE_SPEED = 9.0;

    /** Race checkpoints are driven through, not stopped at. */
    public static final double RACE_GATE_M = 16.0;

    /** Fare structure, rupees. Plausible for Mumbai, not any firm's tariff. */
    public static final int TAXI_BASE = 28;
    public static final double TAXI_PER_KM = 18.0;
    public static final int DELIVERY_BASE = 60;
    public static final double DELIVERY_PER_KM = 24.0;

    /** Straight-line to road-distance fudge. Streets are not direct. */
    public static final double ROAD_FACTOR = 1.32;

    /** One stop on a job. */
    public static final class Stop {
        public final double x, z;
        public final String label;

        public Stop(double x, double z, String label) {
            this.x = x; this.z = z; this.label = label;
        }
    }

    /** A job in progress. */
    public static final class Job {
        public Kind kind;
        public String title = "";
        public final List<Stop> stops = new ArrayList<>();
        public int stage;
        /** Seconds left, or 0 for untimed. */
        public double timeLeft;
        public double timeLimit;
        public double elapsed;
        public int reward;
        /** Metres of job driven, summed between stops. */
        public double distanceM;

        public Stop target() {
            return stage < stops.size() ? stops.get(stage) : null;
        }

        public boolean timed() { return timeLimit > 0; }
    }

    private final WorldData world;
    private final Minimap map;
    private final Random rng;

    /** Rupees in hand. Starts at nothing; everything is earned. */
    public int money;
    public int jobsDone;
    public int jobsFailed;
    public int racesWon;
    /** Finished jobs by kind, so progression can be saved and shown. */
    public int faresDone;
    public int deliveriesDone;
    /** Best race time in whole seconds, 0 if none finished. */
    public int bestRaceSec;

    /** The ladder of contracts, and where the player is on it. */
    public final Career career = new Career();

    public Job active;

    /** Transient on-screen message, e.g. "Fare complete  +Rs 212". */
    public String banner = "";
    public double bannerTimer;

    /** Standing hint when there is no job, e.g. which vehicle to find. */
    public String hint = "";

    private double lastX, lastZ;
    private boolean havePrev;
    /** Stops a new job being offered the instant the last one ended. */
    private double cooldown;

    public Missions(WorldData world, Minimap map, long seed) {
        this.world = world;
        this.map = map;
        this.rng = new Random(seed);
    }

    /**
     * The fare for a completed taxi job.
     *
     * <p>Base plus distance, with a bonus for beating the expected time.
     * Rounded once, here, so repeated accumulation cannot drift.
     */
    public static int taxiFare(double metres, double parSeconds, double took) {
        double fare = TAXI_BASE + TAXI_PER_KM * (metres / 1000.0);
        if (parSeconds > 0 && took < parSeconds) {
            // Up to a third extra for a quick run, scaled by how early.
            double early = (parSeconds - took) / parSeconds;
            fare *= 1.0 + 0.33 * Math.min(1.0, early);
        }
        return (int) Math.round(fare);
    }

    /** The fee for a delivery. Paid only if it arrives in time. */
    public static int deliveryFee(double metres) {
        return (int) Math.round(DELIVERY_BASE + DELIVERY_PER_KM * (metres / 1000.0));
    }

    /**
     * Seconds allowed for a distance, at a speed you can actually hold in
     * Mumbai traffic. Deliberately not generous, or the clock means nothing.
     */
    public static double parTime(double metres, double kmh) {
        return metres / (kmh / 3.6);
    }

    /** Which job kind a vehicle is for, or null if it is not for work. */
    public static Kind workFor(VehicleKind k) {
        if (k == null) { return null; }
        switch (k) {
            case KAALI_PEELI: return Kind.TAXI;
            case TEMPO: case AUTORICKSHAW: return Kind.DELIVERY;
            case HATCHBACK: case SEDAN: case SUV: return Kind.RACE;
            default: return null;   // buses, scooters, police
        }
    }

    /**
     * Advances the job clock and checks arrivals.
     *
     * @param driving  the vehicle being driven, or null if on foot
     */
    public void update(double dt, double px, double pz, double speed,
                       VehicleKind driving) {
        if (bannerTimer > 0) {
            bannerTimer -= dt;
            if (bannerTimer <= 0) { banner = ""; }
        }
        career.update(dt);
        if (cooldown > 0) { cooldown -= dt; }

        if (havePrev && active != null) {
            active.distanceM += Math.hypot(px - lastX, pz - lastZ);
        }
        lastX = px;
        lastZ = pz;
        havePrev = true;

        Kind want = workFor(driving);

        if (active != null) {
            // Getting out of the vehicle abandons the job. A fare that
            // follows you around on foot is not a fare.
            if (want != active.kind) {
                abandon();
                return;
            }
            active.elapsed += dt;
            if (active.timed()) {
                active.timeLeft -= dt;
                if (active.timeLeft <= 0) {
                    fail(active.kind == Kind.RACE ? "Race lost" : "Too late");
                    return;
                }
            }
            checkArrival(px, pz, speed);
            return;
        }

        hint = want == null ? needHint() : "";
        if (want != null && cooldown <= 0) {
            Job j = generate(want, px, pz);
            if (j != null) {
                active = j;
                say(j.title, 4.0);
            } else {
                // Nothing suitable nearby: say so rather than silently
                // offering nothing, which reads as the system being broken.
                hint = "No work around here - drive towards the city";
                cooldown = 3.0;
            }
        }
    }

    /**
     * What to go and find, given what the ladder is asking for.
     *
     * <p>A generic "find a taxi, a tempo or a fast car" is no help when
     * the contract in hand only counts fares.
     */
    private String needHint() {
        Career.Chapter c = career.current();
        if (c == null || c.anyKind()) {
            return "Find a taxi, a tempo or a fast car to start earning";
        }
        switch (c.kind) {
            case TAXI: return "Find a kaali-peeli - " + career.objective();
            case DELIVERY: return "Find a tempo or an auto - "
                    + career.objective();
            default: return "Find something quick - " + career.objective();
        }
    }

    private void checkArrival(double px, double pz, double speed) {
        Stop s = active.target();
        if (s == null) { return; }
        double d = Math.hypot(px - s.x, pz - s.z);
        boolean race = active.kind == Kind.RACE;
        double radius = race ? RACE_GATE_M : ARRIVE_M;
        if (d > radius) { return; }
        if (!race && speed > ARRIVE_SPEED) { return; }

        active.stage++;
        if (active.stage < active.stops.size()) {
            if (race) {
                say("Checkpoint " + active.stage + "/"
                        + (active.stops.size() - 1), 1.6);
            } else {
                say("Passenger aboard - " + active.target().label, 3.2);
            }
            return;
        }
        complete();
    }

    private void complete() {
        int paid;
        switch (active.kind) {
            case TAXI:
                paid = taxiFare(active.distanceM,
                        parTime(active.distanceM, 26), active.elapsed);
                say("Fare paid   +Rs " + paid, 4.0);
                break;
            case DELIVERY:
                paid = active.reward;
                say("Delivered   +Rs " + paid, 4.0);
                break;
            default:
                paid = active.reward;
                int sec = (int) Math.round(active.elapsed);
                if (bestRaceSec == 0 || sec < bestRaceSec) { bestRaceSec = sec; }
                racesWon++;
                say("Race won in " + sec + "s   +Rs " + paid, 4.5);
                break;
        }
        money += paid;
        jobsDone++;
        // And the ladder. Paid separately and announced separately, so
        // finishing a chapter reads as an event rather than as a
        // slightly larger fare.
        int bonus = career.record(active.kind);
        if (bonus > 0) { money += bonus; }
        switch (active.kind) {
            case TAXI: faresDone++; break;
            case DELIVERY: deliveriesDone++; break;
            default: break;
        }
        active = null;
        cooldown = 6.0;
    }

    private void fail(String why) {
        say(why, 3.5);
        jobsFailed++;
        active = null;
        cooldown = 6.0;
    }

    private void abandon() {
        say(active.kind == Kind.TAXI ? "Fare cancelled" : "Job abandoned", 2.5);
        jobsFailed++;
        active = null;
        cooldown = 2.0;
    }

    private void say(String s, double seconds) {
        banner = s;
        bannerTimer = seconds;
    }

    /** Clears the current job without counting it as a failure. */
    public void reset() {
        active = null;
        banner = "";
        bannerTimer = 0;
        cooldown = 0;
        havePrev = false;
    }

    // ------------------------------------------------------------ generation

    /**
     * Builds a job of the given kind starting from the player's position,
     * or null if the surroundings cannot support one.
     */
    public Job generate(Kind kind, double px, double pz) {
        return kind == Kind.RACE ? race(px, pz) : haul(kind, px, pz);
    }

    /** A two-stop job: go there, then go there. */
    private Job haul(Kind kind, double px, double pz) {
        Places.Place pick = placeNear(px, pz, 120, 900);
        if (pick == null) { return null; }
        double[] a = map.nearestRoad(pick.x, pick.z, 180);
        if (a == null) { return null; }

        Places.Place drop = placeNear(a[0], a[1], 900, 4200);
        if (drop == null) { return null; }
        double[] b = map.nearestRoad(drop.x, drop.z, 180);
        if (b == null) { return null; }

        Job j = new Job();
        j.kind = kind;
        j.stops.add(new Stop(a[0], a[1], pick.name));
        j.stops.add(new Stop(b[0], b[1], drop.name));

        double legs = Math.hypot(a[0] - px, a[1] - pz)
                + Math.hypot(b[0] - a[0], b[1] - a[1]);
        double est = legs * ROAD_FACTOR;

        if (kind == Kind.TAXI) {
            j.title = "Fare waiting at " + pick.name;
            // Untimed. A taxi you can be sacked from for traffic is no fun;
            // the clock only ever adds to the fare.
        } else {
            j.title = "Pick up at " + pick.name;
            j.reward = deliveryFee(est);
            j.timeLimit = parTime(est, 30) + 45;
            j.timeLeft = j.timeLimit;
        }
        return j;
    }

    /**
     * A circuit of checkpoints.
     *
     * <p>Points are taken at a fixed radius around the player at even
     * bearings and snapped to the nearest road, which gives a rough loop
     * that is reachable by road because every gate is on one. Walking the
     * graph would give a prettier route and needs an adjacency structure
     * over 352,000 edges to be rebuilt or kept; this is good enough for a
     * street race and costs nothing.
     */
    private Job race(double px, double pz) {
        int gates = 4 + rng.nextInt(2);
        double radius = 420 + rng.nextDouble() * 380;
        double phase = rng.nextDouble() * Math.PI * 2;

        Job j = new Job();
        j.kind = Kind.RACE;
        double prevX = px, prevZ = pz, total = 0;
        for (int i = 0; i < gates; i++) {
            double ang = phase + (Math.PI * 2 * i) / gates;
            double tx = px + Math.cos(ang) * radius;
            double tz = pz + Math.sin(ang) * radius;
            double[] on = map.nearestRoad(tx, tz, 320);
            if (on == null) { continue; }
            // Skip gates that landed on top of the previous one.
            if (Math.hypot(on[0] - prevX, on[1] - prevZ) < 120) { continue; }
            j.stops.add(new Stop(on[0], on[1], "Checkpoint " + (j.stops.size() + 1)));
            total += Math.hypot(on[0] - prevX, on[1] - prevZ);
            prevX = on[0];
            prevZ = on[1];
        }
        if (j.stops.size() < 3) { return null; }

        // Finish back where it started.
        double[] home = map.nearestRoad(px, pz, 240);
        if (home == null) { return null; }
        j.stops.add(new Stop(home[0], home[1], "Finish"));
        total += Math.hypot(home[0] - prevX, home[1] - prevZ);

        double est = total * ROAD_FACTOR;
        j.title = "Street race - " + (j.stops.size() - 1) + " checkpoints";
        j.reward = (int) Math.round(240 + est * 0.42);
        j.timeLimit = parTime(est, 46);
        j.timeLeft = j.timeLimit;
        return j;
    }

    /** A named place in a distance band, chosen at random among candidates. */
    private Places.Place placeNear(double x, double z, double minM, double maxM) {
        List<Places.Place> hits = new ArrayList<>();
        double lo = minM * minM, hi = maxM * maxM;
        for (Places.Place p : world.places().all()) {
            double dx = p.x - x, dz = p.z - z;
            double d2 = dx * dx + dz * dz;
            if (d2 >= lo && d2 <= hi) { hits.add(p); }
        }
        if (hits.isEmpty()) { return null; }
        return hits.get(rng.nextInt(hits.size()));
    }

    // ----------------------------------------------------------------- views

    /** Metres to the current stop, or -1 when there is no job. */
    public double distanceToTarget(double px, double pz) {
        if (active == null) { return -1; }
        Stop s = active.target();
        if (s == null) { return -1; }
        return Math.hypot(px - s.x, pz - s.z);
    }

    /** One-line description of what to do next. */
    public String objective() {
        if (active == null) { return hint; }
        Stop s = active.target();
        if (s == null) { return ""; }
        switch (active.kind) {
            case TAXI:
                return active.stage == 0
                        ? "Collect the fare at " + s.label
                        : "Drop off at " + s.label;
            case DELIVERY:
                return active.stage == 0
                        ? "Load up at " + s.label
                        : "Deliver to " + s.label;
            default:
                return s.label;
        }
    }

    /** Fills the radar with the current target. */
    public void blip(double px, double pz, double heading) {
        if (active == null) { return; }
        Stop s = active.target();
        if (s == null) { return; }
        map.addBlip(s.x, s.z, px, pz, heading, Minimap.BLIP_NEXT);
        // The one after it, dimmer, so a race reads as a route.
        if (active.stage + 1 < active.stops.size()) {
            Stop n = active.stops.get(active.stage + 1);
            map.addBlip(n.x, n.z, px, pz, heading, Minimap.BLIP_STOP);
        }
    }

    /**
     * The stops still to come, in world metres, as x,z pairs.
     *
     * <p>The radar's own blips are no use to the full-screen map: they are
     * already rotated into heading-up local space and clipped to the radar's
     * range. This hands over the raw positions so the map can place them
     * itself, which is why the route finally shows up on it.
     *
     * @return how many pairs were written
     */
    public int remainingStops(double[] out) {
        if (active == null) { return 0; }
        int n = 0;
        for (int i = active.stage; i < active.stops.size(); i++) {
            if (n * 2 + 2 > out.length) { break; }
            Stop s = active.stops.get(i);
            out[n * 2] = s.x;
            out[n * 2 + 1] = s.z;
            n++;
        }
        return n;
    }

    /**
     * World position and height of the marker to draw, or null.
     * Returns {@code {x, y, z}} with y already on the ground.
     */
    public double[] markerAt() {
        if (active == null) { return null; }
        Stop s = active.target();
        if (s == null) { return null; }
        return new double[]{s.x, world.height(s.x, s.z), s.z};
    }
}
