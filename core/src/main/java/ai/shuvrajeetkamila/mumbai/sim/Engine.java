package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.app.Quality;
import ai.shuvrajeetkamila.mumbai.audio.AudioDirector;
import ai.shuvrajeetkamila.mumbai.audio.AudioMixer;
import ai.shuvrajeetkamila.mumbai.game.MapView;
import ai.shuvrajeetkamila.mumbai.game.Minimap;
import ai.shuvrajeetkamila.mumbai.game.Progress;
import ai.shuvrajeetkamila.mumbai.game.Missions;
import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.game.Wanted;
import ai.shuvrajeetkamila.mumbai.geo.GeoProjection;
import ai.shuvrajeetkamila.mumbai.math.MathUtil;
import ai.shuvrajeetkamila.mumbai.math.Quat;
import ai.shuvrajeetkamila.mumbai.math.Vec3;
import ai.shuvrajeetkamila.mumbai.physics.AeroplanePhysics;
import ai.shuvrajeetkamila.mumbai.physics.BoatPhysics;
import ai.shuvrajeetkamila.mumbai.physics.CarPhysics;
import ai.shuvrajeetkamila.mumbai.physics.HelicopterPhysics;
import ai.shuvrajeetkamila.mumbai.physics.HumanPhysics;
import ai.shuvrajeetkamila.mumbai.physics.Ocean;
import ai.shuvrajeetkamila.mumbai.physics.RigidBody;
import ai.shuvrajeetkamila.mumbai.render.Sky;
import ai.shuvrajeetkamila.mumbai.render.Weather;
import ai.shuvrajeetkamila.mumbai.world.CityCollision;
import ai.shuvrajeetkamila.mumbai.world.CraftSpawns;
import ai.shuvrajeetkamila.mumbai.world.Places;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldStreamer;

/**
 * The simulation. Owns the world, the five vehicles and the clock.
 *
 * <p>Deliberately has no reference to OpenGL, Android or any window. The
 * renderer reads from it; it never calls out. That is what lets the whole
 * thing be driven from a plain JUnit test: load the real compiled world, feed
 * it inputs, step it for ten thousand frames and assert on where things ended
 * up. Every physics and streaming test in this project does exactly that.
 */
public final class Engine {

    public final WorldData world;
    public final WorldStreamer streamer;
    public final Ocean ocean = new Ocean();
    public final Telemetry telemetry = new Telemetry();
    public final FixedStep clock = new FixedStep();
    public final FloatingOrigin origin = new FloatingOrigin();
    public final Agents agents;

    /** Trains, metros, the monorail and harbour vessels. */
    public final Transit transit;
    /** Radar geometry. Refreshed with the telemetry, not every frame. */
    public final Minimap minimap;

    /** The full-screen map, and the teleport destination picked on it. */
    public final MapView mapView = new MapView();

    /** Jobs, money and progression. */
    public final Missions missions;

    /** How much trouble you are in. */
    public final Wanted wanted = new Wanted();

    /**
     * Everything you can hear. The mixer is read by the audio thread and
     * written here; the director chooses which of the cars around you get
     * one of its handful of voices.
     */
    public final AudioMixer audio = new AudioMixer();
    private final AudioDirector audioDirector = new AudioDirector();

    /** Radius police are called in at, metres. Beyond the spawn suppression. */
    public static final double POLICE_SPAWN_M = 220.0;

    /** Reach of a baton swing, metres, and its cooldown in seconds. */
    public static final double BATON_REACH_M = 2.6;
    public static final double BATON_COOLDOWN = 0.75;
    private double batonTimer;

    public final HumanPhysics human = new HumanPhysics();
    public final CarPhysics car = new CarPhysics();
    public final BoatPhysics boat = BoatPhysics.ferry();
    public final HelicopterPhysics heli = new HelicopterPhysics();
    public final AeroplanePhysics plane = new AeroplanePhysics();

    public ControlProfile profile = ControlProfile.ON_FOOT;

    /**
     * The transit service the player is riding, or -1.
     *
     * <p>Riding is not driving. You do not get a control profile: you
     * are a passenger, the service runs its own timetable, and the only
     * thing you can do is get off at the next station. The player's body
     * is carried along with the train so that everything that works off
     * the player's position - streaming, traffic, the radar - keeps
     * working while aboard.
     */
    public int aboard = -1;
    public Quality quality = Quality.BALANCED;
    public Quality baseQuality = Quality.BALANCED;
    public int thermalStatus;

    /** 0 dry, 1 monsoon downpour. Affects grip and the sea state. */
    public double wetness;
    public double windX;
    public double windZ;

    /** Sun position and air colour for the current {@link #timeOfDay}. */
    public final Sky sky = new Sky();

    /**
     * The monsoon. Driven on real seconds rather than game time: a squall
     * cell is 3.5 minutes of wall clock, which is a human timescale for
     * weather changing. Game time runs sixty times faster, and weather
     * keyed to it would strobe.
     */
    public final Weather weather = new Weather();

    /** Where the save lives. Replaced by the host before the first frame. */
    private Progress.Store store = new Progress.Memory();

    /** What has been earned and done. Written on pause, read on start. */
    public final Progress progress = new Progress();

    /**
     * How far out traffic and pedestrians are populated, metres.
     *
     * <p>Deliberately much smaller than the streaming radius. Density is
     * what makes a street feel alive, and density is the cap divided by
     * the area it is spread over; at 300 m a hundred and forty cars is
     * nearly five hundred per square kilometre and about twenty of them
     * are within a block of you.
     */
    public static final double AGENT_SPAWN_M = 300.0;

    /** How far off an officer on foot appears from, metres. */
    public static final double FOOT_POLICE_SPAWN_M = 70.0;

    /**
     * Where they are given up, metres. Comfortably outside the spawn ring
     * so nothing spawns and is immediately thrown away, and close enough
     * that cars driving away free their slot for the street you are
     * actually standing in.
     */
    public static final double AGENT_DESPAWN_M = 620.0;

    /**
     * Hours since midnight. Advances at one game-minute per real second, so
     * a full day is twenty-four minutes of play - the pace the genre has
     * used since it had a day at all. Slow enough that the light changes
     * while you drive somewhere, fast enough to see a sunset if you wait.
     */
    public double timeOfDay = 8.4;
    public static final double SECONDS_PER_GAME_HOUR = 60.0;

    /** How near the player must be to a vehicle to get into it, metres. */
    public static final double ENTER_RADIUS_M = 4.2;

    /** Index into {@code agents.cars()} of the car being driven, or -1. */
    private int occupiedAgent = -1;
    /** Index into {@code world.craft()} of the craft being flown, or -1. */
    private int occupiedCraft = -1;

    /**
     * Ground clearance of each craft's body origin when it is parked,
     * metres: keel below the waterline for the boat, skids and undercarriage
     * for the aircraft.
     */
    private static final double[] CRAFT_REST_Y = {0.45, 1.35, 1.15};
    /** True on the frame a vehicle was entered or left; the HUD flashes. */
    public boolean vehicleChanged;

    public double cameraYaw;
    public double cameraPitch = -0.12;
    public double cameraDistance = 7.0;
    public boolean firstPerson;

    // Interpolation states, so a 30 Hz display shows smooth 60 Hz motion.
    private final Vec3 prevPos = new Vec3();
    private final Quat prevRot = new Quat();
    private final Vec3 renderPos = new Vec3();
    private final Quat renderRot = new Quat();

    private final Vec3 tmp = new Vec3();
    private final double[] n3 = new double[3];
    private final double[] rebase2 = new double[2];
    private double telemetryTimer;
    private double fpsAccum;
    private int fpsFrames;

    /** Walls and bridge decks. Built lazily around whatever is moving. */
    public final CityCollision city;

    /** Height the player's body is at, for telling a deck from an underpass. */
    private double probeY = 2.0;

    private final CarPhysics.GroundProbe carGround = new CarPhysics.GroundProbe() {
        @Override public double heightAt(double x, double z) {
            if (!world.inBounds(x, z)) { return 0; }
            if (world.isWater(x, z)) {
                // The sea is not a road surface. It used to return the wave
                // height less 35 cm, which is a floor, and a saloon would
                // drive eight kilometres out into the Arabian Sea at 130
                // km/h. Give the wheels the seabed and let buoyancy and
                // drag in CarPhysics decide what floats.
                double deck = city.deckAt(x, z, probeY);
                if (deck != Double.NEGATIVE_INFINITY) { return deck; }
                if (!deepWater(x, z)) { return world.height(x, z); }
                return -Math.max(0.6, world.seaDepth(x, z));
            }
            return city.surfaceAt(x, z, probeY, world.height(x, z));
        }

        @Override public void normalAt(double x, double z, Vec3 out) {
            world.normal(x, z, n3);
            out.set(n3[0], n3[1], n3[2]);
        }

        @Override public boolean isUnpaved(double x, double z) {
            int c = world.cover(x, z);
            // Anything that is not built-up or a road surface behaves like dirt.
            return c != WorldData.COVER_WATER && world.slope(x, z) > 0.055;
        }

        @Override public double waterLevelAt(double x, double z) {
            if (!world.inBounds(x, z) || !world.isWater(x, z)) {
                return Double.NEGATIVE_INFINITY;
            }
            // On a bridge over the harbour you are not in the harbour.
            if (city.deckAt(x, z, probeY) != Double.NEGATIVE_INFINITY) {
                return Double.NEGATIVE_INFINITY;
            }
            if (!deepWater(x, z)) { return Double.NEGATIVE_INFINITY; }
            return ocean.heightAt(x, z);
        }
    };

    // Assigned in the constructor rather than here: a lambda in a field
    // initializer cannot see a blank final field that is set further down.
    private final BoatPhysics.SeabedProbe seabed;
    private final HelicopterPhysics.GroundProbe heliGround;
    private final AeroplanePhysics.GroundProbe planeGround;

    private final HumanPhysics.Terrain humanTerrain = new HumanPhysics.Terrain() {
        @Override public double heightAt(double x, double z) {
            if (!world.inBounds(x, z)) { return 0; }
            return city.surfaceAt(x, z, probeY, world.height(x, z));
        }

        @Override public boolean isWater(double x, double z) {
            if (!world.inBounds(x, z) || !world.isWater(x, z)) { return false; }
            // Standing on a bridge over water is not standing in water.
            return city.deckAt(x, z, probeY) == Double.NEGATIVE_INFINITY;
        }

        @Override public double slopeAt(double x, double z) {
            return world.inBounds(x, z) ? world.slope(x, z) : 0;
        }
    };

    public Engine(WorldData world) {
        this.world = world;
        this.streamer = new WorldStreamer(world);
        this.agents = new Agents(world);
        this.transit = new Transit(world);
        this.minimap = new Minimap(world);
        this.mapView.setWorldBounds(world);
        this.missions = new Missions(world, minimap, 0x5A1_5E77EL);
        this.city = new CityCollision(world);
        // Traffic and pedestrians stand on bridges too. Without this a car
        // crossing the Sea Link is placed on the seabed and drives along
        // underneath its own deck. The reference height is the deck if
        // there is one close above the terrain, which is what an agent
        // following a bridged edge is doing.
        this.agents.setSurface((x, y) -> {
            double g = world.inBounds(x, y) ? world.height(x, y) : 0;
            double deck = city.deckAt(x, y, g + 3.0);
            return deck == Double.NEGATIVE_INFINITY ? g : Math.max(g, deck);
        });
        this.seabed = (x, z) -> world.inBounds(x, z) ? world.height(x, z) : -40;
        this.heliGround = (x, z) -> {
            if (!world.inBounds(x, z)) { return 0; }
            double base = world.isWater(x, z) ? ocean.heightAt(x, z) : world.height(x, z);
            return city.surfaceAt(x, z, probeY, base);
        };
        this.planeGround = (x, z) -> {
            if (!world.inBounds(x, z)) { return 0; }
            double base = world.isWater(x, z) ? ocean.heightAt(x, z) : world.height(x, z);
            return city.surfaceAt(x, z, probeY, base);
        };
        applyQuality(quality);
    }

    /**
     * Attach somewhere to save, and load whatever is already there.
     *
     * <p>Call before {@code spawnAt}: if the save has a position, the
     * caller should use it instead of the default spawn.
     */
    public Progress loadProgress(Progress.Store s) {
        this.store = s == null ? new Progress.Memory() : s;
        Progress saved = Progress.parse(this.store.read());
        progress.money = saved.money;
        progress.faresDone = saved.faresDone;
        progress.deliveriesDone = saved.deliveriesDone;
        progress.racesWon = saved.racesWon;
        progress.timesBusted = saved.timesBusted;
        progress.distanceM = saved.distanceM;
        progress.bestRaceS = saved.bestRaceS;
        progress.x = saved.x;
        progress.z = saved.z;
        progress.hour = saved.hour;
        missions.money = progress.money;
        wanted.timesBusted = progress.timesBusted;
        timeOfDay = progress.hour;
        return progress;
    }

    /**
     * Write the save. Cheap enough to call on every completed job as well
     * as on pause, which matters: a phone can kill the process without
     * ever calling onPause.
     */
    public void saveProgress() {
        RigidBody b = activeBody();
        progress.money = missions.money;
        progress.faresDone = missions.faresDone;
        progress.deliveriesDone = missions.deliveriesDone;
        progress.racesWon = missions.racesWon;
        progress.timesBusted = wanted.timesBusted;
        progress.bestRaceS = missions.bestRaceSec;
        progress.x = b.position.x;
        progress.z = b.position.z;
        progress.hour = timeOfDay;
        store.write(progress.toString());
    }

    /** Drop the player at a latitude and longitude, on the ground. */
    public void spawnAt(double lat, double lon, ControlProfile p) {
        spawnAtWorld(GeoProjection.toX(lat, lon), GeoProjection.toZ(lat), p);
    }

    /** Drop the player at a world position, on the ground. */
    public void spawnAtWorld(double x, double z, ControlProfile p) {
        profile = p;
        RigidBody b = activeBody();
        b.position.set(x, world.inBounds(x, z) ? world.height(x, z) + 1.4 : 2.0, z);
        b.velocity.zero();
        b.angularVelocity.zero();
        b.orientation.identity();
        origin.reset();
        origin.update(x, z);
        origin.consumeRebase(rebase2);
        probeY = b.position.y;
        city.clear();
        // Two snapshots, or the first rendered frame interpolates from wherever
        // the player used to be and the camera flies across the city.
        snapshot();
        snapshot();
    }

    public RigidBody activeBody() {
        switch (profile) {
            case CAR: return car;
            case BOAT: return boat;
            case HELICOPTER: return heli;
            case AEROPLANE: return plane;
            default: return human;
        }
    }

    public void applyQuality(Quality q) {
        quality = q;
        streamer.setRadii(q.highRadius, q.medRadius, q.farRadius);
        agents.setCaps(q.maxCars, q.maxPedestrians);
    }

    public void setThermalStatus(int status) {
        thermalStatus = status;
        Quality want = Quality.applyThermal(baseQuality, status);
        if (want != quality) { applyQuality(want); }
    }

    public void setBaseQuality(Quality q) {
        baseQuality = q;
        setThermalStatus(thermalStatus);
    }

    /** Record the current state so the renderer can interpolate from it. */
    public void snapshot() {
        RigidBody b = activeBody();
        prevPos.set(b.position);
        prevRot.set(b.orientation);
    }

    /**
     * Advance by one frame's worth of wall-clock time. Runs zero to four
     * fixed 60 Hz substeps.
     */
    public void frame(double frameSeconds, InputState in) {
        if (in.map) { toggleMap(); in.map = false; }
        if (mapView.open) {
            if (in.mapConfirm) {
                in.mapConfirm = false;
                teleportToTarget();
            }
            // The world stops while the map is up. Reading a map at
            // 90 km/h is not a feature, and returning before the fixed
            // clock is touched means no time accumulates: the step
            // accumulator does not then have to swallow however long the
            // player spent looking at it.
            in.clearEdges();
            return;
        }
        int steps = clock.beginFrame(frameSeconds);
        for (int i = 0; i < steps; i++) {
            snapshot();
            fixedStep(FixedStep.STEP, in);
        }
        timeOfDay += frameSeconds / SECONDS_PER_GAME_HOUR;
        // Weather runs on the wall clock, and wetness is its output rather
        // than a field anyone else sets.
        weather.update(frameSeconds);
        wetness = weather.wetness;
        if (timeOfDay >= 24.0) { timeOfDay -= 24.0; }
        sky.evaluate(timeOfDay, wetness);

        RigidBody b = activeBody();
        double a = clock.alpha();
        renderPos.set(prevPos.x + (b.position.x - prevPos.x) * a,
                prevPos.y + (b.position.y - prevPos.y) * a,
                prevPos.z + (b.position.z - prevPos.z) * a);
        Quat.slerpShortest(prevRot, b.orientation, a, renderRot);

        origin.update(b.position.x, b.position.z);

        streamer.update(b.position.x, b.position.z, b.velocity.x, b.velocity.z,
                b.position.y - groundHeight(b.position.x, b.position.z));

        // Telemetry at about 15 Hz. The HUD cannot read faster than the eye.
        fpsAccum += frameSeconds;
        fpsFrames++;
        telemetryTimer += frameSeconds;
        if (telemetryTimer >= 1.0 / 15.0) {
            publishTelemetry(frameSeconds);
            // The radar is rebuilt here rather than per frame. It cannot
            // change meaningfully in 16 ms, and the spatial query is the
            // most expensive thing the HUD asks for.
            double hdg = b.orientation.heading();
            minimap.update(b.position.x, b.position.z, hdg);
            missions.blip(b.position.x, b.position.z, hdg);
            craftBlips(b.position.x, b.position.z, hdg);
            telemetryTimer = 0;
        }
        in.clearEdges();
    }

    /**
     * Abandon a sinking car.
     *
     * <p>Not a normal exit: there is nowhere dry to stand, so the player
     * surfaces where the car was and swims. {@link HumanPhysics} already
     * handles being in open water.
     */
    private void bailOutOfTheWater() {
        human.position.set(car.position.x,
                ocean.heightAt(car.position.x, car.position.z) + 0.2,
                car.position.z);
        human.velocity.set(car.velocity.x * 0.3, 0, car.velocity.z * 0.3);
        human.angularVelocity.zero();
        human.orientation.set(Quat.yaw(car.orientation.heading()));
        human.headingRad = car.orientation.heading();
        profile = ControlProfile.ON_FOOT;
        car.reset();

        snapshot();
    }

    /**
     * Water a car can actually drown in, as opposed to the edge of the
     * land mask.
     *
     * <p>The mask is on a 20 m grid, so the cell containing a seafront
     * road is half sea and reads as water - which meant a car driving
     * along Marine Drive drowned on the promenade twenty-eight metres
     * from where it started. Depth here is a function of distance from
     * land, so this is really "more than about twenty metres offshore".
     */
    private boolean deepWater(double x, double z) {
        return world.seaDepth(x, z) > SHALLOWS_DEPTH_M;
    }

    /** Below this the shore is fordable and the land mask is just coarse. */
    private static final double SHALLOWS_DEPTH_M = 1.56;

    private double groundHeight(double x, double z) {
        return world.inBounds(x, z) ? world.height(x, z) : 0;
    }

    /** Scratch for the wall query. Not reentrant, and does not need to be. */
    private final double[] push2 = new double[2];
    private final Vec3 cornerW = new Vec3();
    private final Vec3 cornerF = new Vec3();

    /** How far a wall gives before it is rigid, metres. */
    private static final double WALL_GIVE_M = 0.18;

    /** How fast a wall pushes a body that is already inside it clear, m/s. */
    private static final double WALL_EXTRACT = 1.6;

    /**
     * Push a body out of any wall it is standing in.
     *
     * <p>Force, not teleportation, and the same shape as the ground
     * contact: compliant for the first finger's width, rigid after, with
     * the force capped at what will arrest the approach in one step and
     * ease a body that is already buried back out at walking pace. A
     * plain spring on the full depth would make every building a
     * trampoline the way the ground used to be.
     */
    private boolean pushOutOfWall(RigidBody b, double radius, double atY,
                                  double dt, double x, double z,
                                  boolean atPoint) {
        double depth = city.wallPush(x, z, atY, radius, push2);
        if (depth <= 0) { return false; }
        double len = Math.hypot(push2[0], push2[1]);
        if (len < 1e-9) { return false; }
        double ux = push2[0] / len;
        double uz = push2[1] / len;
        double vn = b.velocity.x * ux + b.velocity.z * uz;   // + is separating
        double soft = Math.min(depth, WALL_GIVE_M);
        double k = b.mass * 240.0;
        double c = b.mass * 20.0;
        double f = Math.max(0, soft * k - vn * c);
        if (depth > WALL_GIVE_M) {
            double cap = b.mass * (WALL_EXTRACT - vn) / dt;
            f = Math.min(f, Math.max(0, cap));
        }
        if (f <= 0) { return false; }
        if (atPoint) {
            cornerF.set(ux * f, 0, uz * f);
            cornerW.set(x, b.position.y, z);
            b.carryForceAtPoint(cornerF, cornerW);
        } else {
            b.carryForce(ux * f, 0, uz * f);
        }
        return true;
    }

    /** True on the step a solid impact happened, for sound and damage. */
    public boolean wallHit;

    /** Closing speed of the last wall impact, m/s. */
    public double wallHitSpeed;

    private void resolveWalls(double dt) {
        wallHit = false;
        switch (profile) {
            case ON_FOOT: {
                double before = Math.hypot(human.velocity.x, human.velocity.z);
                if (pushOutOfWall(human, 0.42,
                        human.position.y - human.height * 0.5 + 0.9, dt,
                        human.position.x, human.position.z, false)) {
                    wallHit = before > 3.0;
                    wallHitSpeed = before;
                }
                break;
            }
            case CAR: {
                VehicleKind k = car.kind == null ? VehicleKind.SEDAN : car.kind;
                double hl = k.lengthM * 0.5;
                double hw = k.widthM * 0.5;
                double yaw = car.orientation.heading();
                // Body +Z is the nose; the heading rotation puts it on the
                // compass bearing. Same mapping Quat.yaw uses.
                double fx = Math.sin(yaw), fz = -Math.cos(yaw);
                double rx = -fz, rz = fx;
                double atY = car.position.y + 0.3;
                double before = Math.hypot(car.velocity.x, car.velocity.z);
                boolean any = false;
                for (int i = 0; i < 4; i++) {
                    double sl = (i < 2 ? 1 : -1) * hl;
                    double sw = (i % 2 == 0 ? 1 : -1) * hw;
                    double cx = car.position.x + fx * sl + rx * sw;
                    double cz = car.position.z + fz * sl + rz * sw;
                    any |= pushOutOfWall(car, 0.30, atY, dt, cx, cz, true);
                }
                if (any) {
                    wallHit = before > 3.0;
                    wallHitSpeed = before;
                }
                break;
            }
            case HELICOPTER:
                pushOutOfWall(heli, 4.2, heli.position.y, dt,
                        heli.position.x, heli.position.z, false);
                break;
            case AEROPLANE:
                pushOutOfWall(plane, 4.0, plane.position.y, dt,
                        plane.position.x, plane.position.z, false);
                break;
            case BOAT:
                pushOutOfWall(boat, 3.0, boat.position.y + 0.8, dt,
                        boat.position.x, boat.position.z, false);
                break;
            default:
                break;
        }
    }

    /** Closing speed of the last vehicle impact, m/s. Zero if none. */
    public double carHitSpeed;

    /**
     * Resolve the player's vehicle against the traffic.
     *
     * <p>Proximity to a traffic car was already detected - it is what
     * raises the wanted level and triggers the impact sound - but nothing
     * was ever done about it, so you drove through other cars and came out
     * the far side faster than you went in. This is a plain two-body
     * impulse along the line of centres, with the struck car knocked off
     * its route for a couple of seconds so the hit is visible.
     */
    private void resolveTraffic(double dt) {
        carHitSpeed = 0;
        if (profile != ControlProfile.CAR) { return; }
        VehicleKind mine = car.kind == null ? VehicleKind.SEDAN : car.kind;
        double rMine = Math.max(mine.lengthM, mine.widthM) * 0.42;
        for (Agents.Car c : agents.cars()) {
            if (!c.active) { continue; }
            VehicleKind k = VehicleKind.values()[
                    MathUtil.clampi(c.kindOrdinal, 0, VehicleKind.values().length - 1)];
            double rOther = Math.max(k.lengthM, k.widthM) * 0.42;
            double dx = car.position.x - c.x;
            double dz = car.position.z - c.z;
            double d = Math.hypot(dx, dz);
            double touch = rMine + rOther;
            if (d >= touch || d < 1e-6) { continue; }

            double ux = dx / d, uz = dz / d;
            // Traffic velocity in the world frame. A parked car is still.
            double ovx = c.shoveTimer > 0 ? c.shoveX : Math.sin(c.heading) * c.speed;
            double ovz = c.shoveTimer > 0 ? c.shoveZ : -Math.cos(c.heading) * c.speed;
            double rel = (car.velocity.x - ovx) * ux + (car.velocity.z - ovz) * uz;
            if (rel > 0) { continue; }          // already separating

            double m1 = car.mass;
            double m2 = Math.max(120.0, k.massKg);
            double e = 0.15;                    // sheet metal does not bounce
            double j = -(1 + e) * rel / (1 / m1 + 1 / m2);

            // The impulse as a force over this step, so nothing is assigned.
            car.carryForce(ux * j / dt, 0, uz * j / dt);

            // Both of them wear the hit. -rel is the closing speed, since
            // rel is negative whenever they are approaching.
            car.takeImpact(-rel * m2 / (m1 + m2));
            Agents.damage(c, -rel * m1 / (m1 + m2));

            // And the other way for the car that was hit.
            double give = j / m2;
            agents.shove(c, ovx - ux * give, ovz - uz * give,
                    (rng() - 0.5) * 2.2 * Math.min(1, -rel / 12.0));

            // Separate the overlap so they do not sit inside each other.
            double depth = touch - d;
            if (depth > 0.02) {
                double push = car.mass * Math.min(depth, 0.4) * 90.0;
                car.carryForce(ux * push, 0, uz * push);
            }
            carHitSpeed = Math.max(carHitSpeed, -rel);
        }
    }

    private long hitSeed = 0x9E3779B97F4A7C15L;

    private double rng() {
        hitSeed ^= hitSeed << 13;
        hitSeed ^= hitSeed >>> 7;
        hitSeed ^= hitSeed << 17;
        return ((hitSeed >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    private void fixedStep(double dt, InputState in) {
        // Every ground query this step needs to know whether the body is on
        // a bridge or under one, and it can only know that from where the
        // body is.
        probeY = activeBody().position.y;
        ocean.advance(dt);
        // The monsoon raises the sea state as well as wetting the roads.
        ocean.setChoppiness(0.8 + wetness * 1.1);

        if (in.attack) { swingBaton(); }
        if (in.enterExit) {
            if (aboard >= 0) { alightTransit(); }
            else if (profile == ControlProfile.ON_FOOT) {
                if (!boardTransit()) { tryEnterVehicle(); }
            } else { exitVehicle(); }
        }

        switch (profile) {
            case CAR: stepCar(dt, in); break;
            case BOAT: stepBoat(dt, in); break;
            case HELICOPTER: stepHeli(dt, in); break;
            case AEROPLANE: stepPlane(dt, in); break;
            default: if (aboard < 0) { stepHuman(dt, in); } break;
        }

        resolveWalls(dt);
        resolveTraffic(dt);
        if (profile == ControlProfile.CAR && car.sinkTime > 3.0) {
            // She is going down. Get out and swim.
            bailOutOfTheWater();
        }

        RigidBody b = activeBody();
        // Traffic lives in its own radius, not the streaming radius. Spread
        // across the chunk-streaming distance the car budget vanished: 140
        // cars uniformly over a 1760 m disc is fourteen per square
        // kilometre, so the expected number within 120 m of the player was
        // less than one, and the busiest road in Mumbai was empty. The
        // number of cars is a budget for what is *around* you; spend it
        // there and let the far field be empty, because you cannot see it.
        agents.update(dt, b.position.x, b.position.z, b.velocity.x, b.velocity.z,
                AGENT_SPAWN_M, AGENT_DESPAWN_M);
        missions.update(dt, b.position.x, b.position.z,
                Math.hypot(b.velocity.x, b.velocity.z), drivingKind());
        transit.update(dt, b.position.x, b.position.z, quality.medRadius);
        rideTransit();
        // Before updateWanted, which clears the collision counters the
        // impact sound is triggered from.
        updateAudio(b, in);
        updateWanted(dt, b);
    }

    /**
     * Points the mixer at whatever the player is currently in and at the
     * traffic around them.
     *
     * <p>The engine note comes from the crankshaft speed the physics is
     * already solving for, not from a timer, so it rises through a gear
     * and drops across a shift without anything extra being arranged.
     */
    private void updateAudio(RigidBody b, InputState in) {
        double speed = Math.hypot(b.velocity.x, b.velocity.z);

        switch (profile) {
            case CAR:
                audio.playerRpm = car.engineRpm;
                audio.playerCylinders = AudioDirector.cylindersFor(drivingKind());
                audio.playerLoad = Math.min(1.0, in.throttle);
                break;
            case HELICOPTER:
                // Twice the blade count puts the mixer's firing frequency
                // exactly on blade pass, which is the thump you hear.
                audio.playerRpm = heli.rotorRpm;
                audio.playerCylinders = 8;
                audio.playerLoad = Math.min(1.0, 0.45 + in.throttle * 0.55);
                break;
            case AEROPLANE:
                audio.playerRpm = plane.engineRpm;
                audio.playerCylinders = 4;
                audio.playerLoad = Math.min(1.0, in.throttle);
                break;
            case BOAT:
                // No crank model on the boat, so infer one from thrust.
                audio.playerRpm = 700 + Math.min(1.0, in.throttle) * 2600;
                audio.playerCylinders = 6;
                audio.playerLoad = Math.min(1.0, in.throttle);
                break;
            default:
                audio.playerRpm = 0;
                audio.playerLoad = 0;
                break;
        }

        audio.rainLevel = weather.rain;

        // Footsteps, from the speed the player is actually making over the
        // ground rather than from the stick: shoved against a wall at full
        // input you stop walking, and so does the sound. A pace is about
        // two steps per metre at a walk, which is why the rate is a speed.
        if (profile == ControlProfile.ON_FOOT && human.grounded
                && !human.swimming) {
            double gs = Math.hypot(human.velocity.x, human.velocity.z);
            audio.stepRate = gs < 0.25 ? 0 : MathUtil.clamp(gs * 0.95, 0.9, 3.4);
        } else {
            audio.stepRate = 0;
        }
        audio.stepWet = MathUtil.clamp(
                Math.max(weather.wetness, human.waterDepth * 3.0), 0, 1);
        // Wind is the only cue that you are going fast in an open cockpit,
        // and on foot there should not be any.
        audio.windLevel = profile == ControlProfile.ON_FOOT
                ? 0 : AudioDirector.windFor(speed);

        if (agents.carsHit > 0 || agents.pedsHit > 0) {
            audio.impactPending = Math.min(1.0,
                    0.25 + Math.min(speed, 30.0) / 30.0 * 0.75);
        }

        audioDirector.update(audio, agents.cars(),
                b.position.x, b.position.z, b.velocity.x, b.velocity.z,
                headingOf(b), wanted.stars());
    }

    /** Listener facing, from the body's orientation. */
    private static double headingOf(RigidBody b) {
        Vec3 f = b.orientation.forward(new Vec3());
        return Math.atan2(f.x, -f.z);
    }

    /**
     * Turns crimes into heat, heat into police cars, and a cornered player
     * into an arrest.
     */
    private void updateWanted(double dt, RigidBody b) {
        double speed = Math.hypot(b.velocity.x, b.velocity.z);
        if (batonTimer > 0) { batonTimer -= dt; }

        for (int i = 0; i < agents.pedsHit; i++) {
            wanted.ranOverPedestrian(speed);
        }
        for (int i = 0; i < agents.carsHit; i++) {
            wanted.rammedVehicle(speed, i < agents.policeHit);
        }
        agents.clearCrimeCounters();

        // Whichever is closer: a patrol car or an officer on foot. On foot
        // down an alley nothing vehicular could ever reach the player, so
        // a wanted level was something you could simply walk away from.
        double near = Math.min(agents.nearestPolice(b.position.x, b.position.z),
                agents.nearestFootPolice(b.position.x, b.position.z));
        wanted.update(dt, near, speed);
        agents.pursuitPace = wanted.pursuitPace();

        if (wanted.bustedThisStep) {
            int fine = wanted.bust(missions.money);
            missions.money -= fine;
            if (missions.money < 0) { missions.money = 0; }
            missions.reset();
            agents.clearPolice();
            agents.clearFootPolice();
            return;
        }

        if (!wanted.active()) {
            if (agents.policeCount() > 0) { agents.clearPolice(); }
            if (agents.footPoliceCount() > 0) { agents.clearFootPolice(); }
            return;
        }

        // Call in units one at a time rather than all at once, so a star
        // arriving feels like a response and not a spawn event.
        policeCallTimer += dt;
        if (policeCallTimer >= 1.4) {
            policeCallTimer = 0;
            if (agents.policeCount() < wanted.desiredUnits()) {
                agents.spawnPolice(b.position.x, b.position.z, POLICE_SPAWN_M);
            }
            // Officers on foot, from one star. They are what makes being
            // on foot a worse idea than being in a car rather than a
            // better one, and they close the alley the cars cannot enter.
            int wantFoot = profile == ControlProfile.ON_FOOT
                    ? Math.min(4, wanted.stars() + 1)
                    : Math.max(0, wanted.stars() - 2);
            if (agents.footPoliceCount() < wantFoot) {
                agents.spawnFootPolice(b.position.x, b.position.z,
                        FOOT_POLICE_SPAWN_M);
            }
        }
    }

    private double policeCallTimer;

    /**
     * Swings the baton. The only weapon in the game, and deliberately so.
     *
     * @return the number of people knocked down
     */
    public int swingBaton() {
        if (profile != ControlProfile.ON_FOOT || batonTimer > 0) { return 0; }
        batonTimer = BATON_COOLDOWN;
        double px = human.position.x, pz = human.position.z;
        double h = human.orientation.heading();
        double fx = Math.sin(h), fz = -Math.cos(h);
        int hit = 0;
        for (Agents.Ped p : agents.peds()) {
            if (!p.active || p.downTimer > 0) { continue; }
            double dx = p.x - px, dz = p.z - pz;
            double d = Math.hypot(dx, dz);
            if (d > BATON_REACH_M || d < 1e-6) { continue; }
            // In front of you, not behind: a swing with a 360 degree reach
            // reads as a magic blast rather than a baton.
            if ((dx / d) * fx + (dz / d) * fz < 0.3) { continue; }
            p.downTimer = 3.2;
            p.speed = 0;
            hit++;
        }
        if (hit > 0) { wanted.swungBaton(); }
        return hit;
    }

    /**
     * Gets into the nearest vehicle, if one is close enough.
     *
     * <p>Nearest by distance, but only ahead of where the player is facing,
     * because reaching backwards into a car you have walked past is the kind
     * of thing that feels broken even though it is more convenient. The
     * agent that was driving it is removed from traffic and its state -
     * position, heading, and the speed it was doing - is handed to the
     * player's rigid body, so taking a moving car does not stop it dead.
     *
     * @return true if a vehicle was entered
     */
    public boolean tryEnterVehicle() {
        Agents.Car[] cs = agents.cars();
        double px = human.position.x, pz = human.position.z;
        double fx = Math.sin(cameraYaw), fz = -Math.cos(cameraYaw);
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < cs.length; i++) {
            Agents.Car c = cs[i];
            if (!c.active) { continue; }
            VehicleKind k = kindOf(c);
            if (!k.enterable) { continue; }
            double dx = c.x - px, dz = c.z - pz;
            double d2 = dx * dx + dz * dz;
            if (d2 > ENTER_RADIUS_M * ENTER_RADIUS_M) { continue; }
            double d = Math.sqrt(Math.max(1e-9, d2));
            // Facing bonus: straight ahead counts as closer than off to the
            // side, so the choice matches where the player is looking.
            double facing = (dx / d) * fx + (dz / d) * fz;
            double score = d - facing * 1.6;
            if (score < bestScore) { bestScore = score; best = i; }
        }
        // Boats, helicopters and aeroplanes are moored on the same terms:
        // close enough to touch, and scored by how directly they are being
        // looked at, so standing between a taxi and a launch gives you the
        // one you are facing.
        int craftI = world.craft().nearest(px, pz, ENTER_RADIUS_M + 3.0, fx, fz, 1.6);
        if (craftI >= 0) {
            CraftSpawns.Spawn sp = world.craft().get(craftI);
            double dx = sp.x - px, dz = sp.z - pz;
            double d = Math.sqrt(Math.max(1e-9, dx * dx + dz * dz));
            double score = d - ((dx / d) * fx + (dz / d) * fz) * 1.6;
            if (best < 0 || score < bestScore) {
                return boardCraft(craftI);
            }
        }
        if (best < 0) { return false; }

        Agents.Car c = cs[best];
        VehicleKind k = kindOf(c);
        car.configure(k);
        car.reset();
        // Damage belongs to the vehicle, not to the driver. Carrying it
        // across means you cannot repair a wreck by stepping out of it
        // and back in, and the dented taxi you abandoned is still dented
        // when you come back for it.
        car.damage = c.damage;
        car.position.set(c.x, c.y + k.comHeightM + 0.22, c.z);
        car.orientation.set(Quat.yaw(c.heading));
        // Inherit the traffic speed rather than snapping to a standstill.
        car.velocity.set(Math.sin(c.heading) * c.speed, 0,
                -Math.cos(c.heading) * c.speed);
        car.angularVelocity.set(0, 0, 0);
        c.active = false;
        occupiedAgent = best;
        profile = ControlProfile.CAR;
        vehicleChanged = true;
        return true;
    }

    /**
     * Gets out, placing the player beside the driver's door.
     *
     * <p>Left-hand traffic, so the driver sits on the right and steps out to
     * the right of the vehicle. The car is handed back to the traffic system
     * stationary rather than deleted, so it is still standing there when you
     * turn round.
     */
    /**
     * Get aboard the train, Metro, monorail or ferry standing here.
     *
     * @return true if there was one to board
     */
    public boolean boardTransit() {
        if (profile != ControlProfile.ON_FOOT || aboard >= 0) { return false; }
        int i = transit.boardableAt(human.position.x, human.position.z);
        if (i < 0) { return false; }
        aboard = i;
        return true;
    }

    /**
     * Get off. Only at a station: stepping off a train at 80 km/h is a
     * ragdoll feature and it is not built.
     *
     * @return true if the player got off
     */
    public boolean alightTransit() {
        if (aboard < 0) { return false; }
        Transit.Service sv = transit.services()[aboard];
        if (sv.active && !sv.atStop) { return false; }
        aboard = -1;
        // Put the body back on the ground beside the train rather than
        // inside it. Across the sleepers is the wrong side on a railway,
        // but every platform in the world is beside the track and this
        // is where the player was standing when they got on.
        double side = 3.4;
        double ex = sv.x + Math.cos(sv.heading) * side;
        double ez = sv.z + Math.sin(sv.heading) * side;
        double gy = world.inBounds(ex, ez) ? world.height(ex, ez) : 0;
        human.reset();
        human.position.set(ex, gy + human.height * 0.5, ez);
        return true;
    }

    /** Carry the player along with the service they are riding. */
    private void rideTransit() {
        if (aboard < 0) { return; }
        Transit.Service sv = transit.services()[aboard];
        if (!sv.active) {
            // The service was recycled out from under the player - it
            // ran out of the simulated radius. Put them down where it
            // last was rather than leaving them attached to a ghost.
            alightTransit();
            return;
        }
        double gy = sv.y;
        human.reset();
        human.position.set(sv.x, gy + human.height * 0.5, sv.z);
    }

    public boolean exitVehicle() {
        if (profile != ControlProfile.CAR) { return leaveCraft(); }
        // Refuse at speed. Stepping out of a moving car is a ragdoll feature,
        // and it is not built yet; pretending otherwise would teleport the
        // player through the road surface.
        if (car.groundSpeed() > 3.0) { return false; }

        VehicleKind k = car.kind;
        double yaw = car.orientation.heading();
        // Body +X is right, so the driver's side in left-hand traffic.
        double side = k.widthM * 0.5 + 0.55;
        double ex = car.position.x + Math.cos(yaw) * side;
        double ez = car.position.z + Math.sin(yaw) * side;
        double gy = world.inBounds(ex, ez) ? world.height(ex, ez) : 0;

        human.reset();
        human.position.set(ex, gy + human.height * 0.5, ez);
        human.headingRad = yaw;
        human.velocity.set(0, 0, 0);

        // Park the vehicle back into traffic exactly where it was left.
        Agents.Car[] cs = agents.cars();
        int slot = occupiedAgent >= 0 && occupiedAgent < cs.length
                ? occupiedAgent : agents.freeCarSlot();
        if (slot >= 0) {
            Agents.Car c = cs[slot];
            c.x = car.position.x;
            c.z = car.position.z;
            c.y = world.inBounds(c.x, c.z) ? world.height(c.x, c.z) : 0;
            c.heading = yaw;
            c.speed = 0;
            c.parked = true;
            c.kindOrdinal = k.ordinal();
            c.damage = car.damage;
            c.active = true;
        }
        occupiedAgent = -1;
        profile = ControlProfile.ON_FOOT;
        vehicleChanged = true;
        return true;
    }

    private static VehicleKind kindOf(Agents.Car c) {
        VehicleKind[] all = VehicleKind.values();
        int o = c.kindOrdinal;
        return (o >= 0 && o < all.length) ? all[o] : VehicleKind.HATCHBACK;
    }

    /** The vehicle being driven, or null on foot. For the HUD and the mesh. */
    public VehicleKind drivingKind() {
        return profile == ControlProfile.CAR ? car.kind : null;
    }

    /**
     * Distance to the nearest vehicle the player could get into, or a large
     * number. The HUD uses it to show the prompt only when it would work.
     */
    public double nearestEnterableDistance() {
        if (profile != ControlProfile.ON_FOOT) { return Double.MAX_VALUE; }
        Agents.Car[] cs = agents.cars();
        double px = human.position.x, pz = human.position.z;
        double best = Double.MAX_VALUE;
        for (Agents.Car c : cs) {
            if (!c.active || !kindOf(c).enterable) { continue; }
            double dx = c.x - px, dz = c.z - pz;
            double d2 = dx * dx + dz * dz;
            if (d2 < best) { best = d2; }
        }
        double d = Math.sqrt(best);
        // A moored craft is a little more forgiving than a car door: you are
        // standing on a pier reaching down at it, not opening it.
        double c = world.craft().nearestDistance(px, pz) - 3.0;
        return Math.min(d, Math.max(0, c));
    }

    /**
     * What the prompt should offer, or null. Naming it matters: "Press E"
     * over a 2800 m runway should say what is about to happen.
     */
    public String nearestEnterableLabel() {
        if (profile != ControlProfile.ON_FOOT) { return null; }
        double px = human.position.x, pz = human.position.z;
        double fx = Math.sin(cameraYaw), fz = -Math.cos(cameraYaw);
        int ci = world.craft().nearest(px, pz, ENTER_RADIUS_M + 3.0, fx, fz, 1.6);
        if (ci >= 0) {
            CraftSpawns.Spawn sp = world.craft().get(ci);
            return sp.name.isEmpty() ? sp.label() : sp.label() + " - " + sp.name;
        }
        Agents.Car[] cs = agents.cars();
        double bd = ENTER_RADIUS_M * ENTER_RADIUS_M;
        VehicleKind bk = null;
        for (Agents.Car c : cs) {
            if (!c.active || !kindOf(c).enterable) { continue; }
            double dx = c.x - px, dz = c.z - pz;
            double d2 = dx * dx + dz * dz;
            if (d2 < bd) { bd = d2; bk = kindOf(c); }
        }
        return bk == null ? null : bk.label;
    }

    /**
     * Get aboard a moored craft.
     *
     * <p>The body is placed on the mooring rather than on the player, so a
     * launch stays in the water and an aeroplane stays on the centreline
     * instead of being dragged onto the pier by whoever walked up to it.
     */
    public boolean boardCraft(int index) {
        CraftSpawns cf = world.craft();
        if (index < 0 || index >= cf.size()) { return false; }
        CraftSpawns.Spawn sp = cf.get(index);
        if (sp.taken) { return false; }
        ControlProfile p;
        switch (sp.kind) {
            case CraftSpawns.BOAT: p = ControlProfile.BOAT; break;
            case CraftSpawns.HELI: p = ControlProfile.HELICOPTER; break;
            default: p = ControlProfile.AEROPLANE; break;
        }
        RigidBody b;
        switch (p) {
            case BOAT: boat.reset(); b = boat; break;
            case HELICOPTER: heli.reset(); b = heli; break;
            default: plane.reset(); b = plane; break;
        }
        double surface = sp.kind == CraftSpawns.BOAT
                ? 0.0 : groundHeight(sp.x, sp.z);
        b.position.set(sp.x, surface + CRAFT_REST_Y[sp.kind], sp.z);
        b.orientation.set(Quat.yaw(sp.heading));
        b.velocity.zero();
        b.angularVelocity.zero();
        sp.taken = true;
        occupiedCraft = index;
        profile = p;
        cameraYaw = sp.heading;
        vehicleChanged = true;
        snapshot();
        snapshot();
        return true;
    }

    /**
     * Step off a boat or out of an aircraft.
     *
     * <p>Refused in the air and refused at speed, for the same reason
     * stepping out of a moving car is refused: there is no ragdoll yet, and
     * pretending otherwise drops the player through the world. Leaving a
     * boat puts the player on the nearest dry land within a short walk, or
     * refuses if there is none - swimming to shore from the middle of the
     * harbour is a worse outcome than being told no.
     */
    public boolean leaveCraft() {
        if (occupiedCraft < 0) { return false; }
        RigidBody b = activeBody();
        if (b.velocity.length() > 2.5) { return false; }
        CraftSpawns.Spawn sp = world.craft().get(occupiedCraft);
        double bx = b.position.x, bz = b.position.z;
        if (sp.kind != CraftSpawns.BOAT) {
            double agl = b.position.y - groundHeight(bx, bz);
            if (agl > CRAFT_REST_Y[sp.kind] + 1.2) { return false; }
        }
        // A boat gets a longer reach than an aircraft: she is moored off a
        // jetty, and the compiler has already guaranteed dry land inside
        // this distance of every mooring it kept.
        double[] spot = dryLandNear(bx, bz, sp.kind == CraftSpawns.BOAT ? 30.0 : 8.0);
        if (spot == null) { return false; }

        // Leave it where it was left, so it is still there to come back to.
        sp.x = bx;
        sp.z = bz;
        sp.y = b.position.y - CRAFT_REST_Y[sp.kind];
        sp.heading = b.orientation.heading();
        sp.taken = false;
        occupiedCraft = -1;

        human.reset();
        human.position.set(spot[0], spot[1] + human.height * 0.5, spot[2]);
        human.headingRad = b.orientation.heading();
        human.velocity.set(0, 0, 0);
        profile = ControlProfile.ON_FOOT;
        vehicleChanged = true;
        snapshot();
        snapshot();
        return true;
    }

    /**
     * Mark the boats, helicopters and aeroplanes within radar range.
     *
     * <p>Not clamped to the rim, unlike a job marker: one of these on the
     * edge of the radar would say "there is a helicopter here" about a
     * machine four kilometres away. The full-screen map is where you go to
     * find a distant one.
     */
    private void craftBlips(double px, double pz, double heading) {
        CraftSpawns cf = world.craft();
        double r = Minimap.RANGE_M;
        for (int i = 0; i < cf.size(); i++) {
            CraftSpawns.Spawn sp = cf.get(i);
            if (sp.taken) { continue; }
            double dx = sp.x - px, dz = sp.z - pz;
            if (dx * dx + dz * dz > r * r) { continue; }
            int kind = sp.kind == CraftSpawns.BOAT ? Minimap.BLIP_BOAT
                    : sp.kind == CraftSpawns.HELI ? Minimap.BLIP_HELI
                    : Minimap.BLIP_PLANE;
            minimap.addBlip(sp.x, sp.z, px, pz, heading, kind, false);
        }
    }

    /**
     * Somewhere to stand within {@code maxM} of a point: {x, groundY, z}, or
     * null. Searched as rings outward so the player steps off the near side.
     */
    private double[] dryLandNear(double x, double z, double maxM) {
        for (double r = 2.0; r <= maxM + 1e-9; r += 2.0) {
            for (int a = 0; a < 16; a++) {
                double th = a * Math.PI / 8.0;
                double px = x + Math.cos(th) * r;
                double pz = z + Math.sin(th) * r;
                if (!world.inBounds(px, pz) || !world.isLand(px, pz)) { continue; }
                return new double[]{px, world.height(px, pz), pz};
            }
        }
        return null;
    }

    private void stepHuman(double dt, InputState in) {
        human.moveForward = -in.moveY;
        human.moveRight = in.moveX;
        human.wantRun = in.run;
        human.wantSprint = in.sprint;
        human.wantJump = in.jump;
        cameraYaw += in.lookX * dt * 2.6;
        cameraPitch = MathUtil.clamp(cameraPitch - in.lookY * dt * 2.0, -1.35, 1.1);
        human.headingRad = cameraYaw;
        human.step(dt, humanTerrain, ocean);
    }

    private void stepCar(double dt, InputState in) {
        car.throttle = in.throttle;
        car.brake = in.brake;
        car.handbrakeOn = in.handbrake;
        car.steerInput = in.steer;
        if (in.reverse) { car.selectReverse(); }
        cameraYaw += in.lookX * dt * 2.2;
        cameraPitch = MathUtil.clamp(cameraPitch - in.lookY * dt * 1.6, -1.1, 0.7);
        car.step(dt, carGround, wetness);
    }

    private void stepBoat(double dt, InputState in) {
        boat.throttle = in.throttle - in.brake;
        boat.rudder = in.steer;
        cameraYaw += in.lookX * dt * 2.0;
        cameraPitch = MathUtil.clamp(cameraPitch - in.lookY * dt * 1.5, -1.0, 0.8);
        boat.step(dt, ocean, seabed);
    }

    private void stepHeli(double dt, InputState in) {
        heli.collective = MathUtil.clamp(in.collective, 0, 1);
        // lookY is a screen axis: negative is the stick pushed forward.
        // Cyclic forward puts the nose DOWN and the machine flies forward,
        // which is the whole of how a helicopter goes anywhere. It used to
        // be negated, so pushing the stick forward pitched the nose up and
        // flew you backwards.
        heli.cyclicPitch = in.lookY;
        heli.cyclicRoll = in.lookX;
        heli.pedal = in.pedal;
        if (in.engineToggle) { heli.engineOn = !heli.engineOn; }
        heli.step(dt, heliGround, windX, windZ);
    }

    private void stepPlane(double dt, InputState in) {
        plane.throttle = in.throttle;
        // Stick back is nose up, as it is in every aeroplane ever built.
        // Back on a touchscreen is down, which is lookY positive, and
        // elevator is positive nose-up: so this is a straight assignment
        // and the negation that used to be here made the stick backwards.
        plane.elevator = in.lookY;
        plane.aileron = in.lookX;
        plane.rudderInput = in.pedal;
        plane.brakes = in.brake;
        if (in.flapsDown) { plane.flapSetting = Math.min(3, plane.flapSetting + 1); }
        if (in.flapsUp) { plane.flapSetting = Math.max(0, plane.flapSetting - 1); }
        if (in.gearToggle) { plane.gearDown = !plane.gearDown; }
        plane.step(dt, planeGround, windX, windZ);
    }

    private void publishTelemetry(double frameSeconds) {
        RigidBody b = activeBody();
        Telemetry t = telemetry;
        t.worldX = b.position.x;
        t.worldZ = b.position.z;
        t.lat = GeoProjection.toLat(b.position.z);
        t.lon = GeoProjection.toLon(b.position.x, b.position.z);
        t.altitude = b.position.y;
        t.speedKmh = b.groundSpeed() * 3.6;
        t.headingDeg = Math.toDegrees(b.orientation.heading());
        if (t.headingDeg < 0) { t.headingDeg += 360; }
        t.profile = profile == ControlProfile.CAR && car.kind != null
                ? car.kind.label : profile.label;
        t.timeOfDay = timeOfDay;
        if (profile == ControlProfile.ON_FOOT) {
            String what = nearestEnterableDistance() <= ENTER_RADIUS_M
                    ? nearestEnterableLabel() : null;
            if (aboard >= 0) {
                Transit.Service riding = transit.services()[aboard];
                t.prompt = riding.atStop
                        ? "EXIT to get off"
                        : "riding the " + transit.labelFor(riding);
            } else {
                int tr = transit.boardableAt(b.position.x, b.position.z);
                if (tr >= 0) {
                    t.prompt = "ENTER - board the "
                            + transit.labelFor(transit.services()[tr]);
                } else {
                    t.prompt = what == null ? "" : "ENTER - " + what;
                }
            }
        } else if (profile == ControlProfile.CAR) {
            t.prompt = car.groundSpeed() <= 3.0 ? "EXIT to get out" : "";
        } else if (occupiedCraft >= 0) {
            // Say why it is refusing rather than silently doing nothing,
            // which is what an empty prompt in a hovering helicopter reads
            // as.
            RigidBody cb = activeBody();
            CraftSpawns.Spawn sp = world.craft().get(occupiedCraft);
            boolean slow = cb.velocity.length() <= 2.5;
            boolean down = sp.kind == CraftSpawns.BOAT
                    || cb.position.y - groundHeight(cb.position.x, cb.position.z)
                       <= CRAFT_REST_Y[sp.kind] + 1.2;
            t.prompt = !down ? "Land before getting out"
                    : !slow ? "Too fast to get out"
                    : "EXIT to get out";
        } else {
            t.prompt = "";
        }
        t.frameMs = frameSeconds * 1000.0;
        if (fpsFrames > 0 && fpsAccum > 0) {
            t.fps = fpsFrames / fpsAccum;
            fpsAccum = 0;
            fpsFrames = 0;
        }
        t.substeps = clock.lastSubsteps();
        t.simSteps = clock.totalSteps();
        t.quality = quality.name();
        t.thermalStatus = thermalStatus;
        t.thermalLabel = Quality.thermalLabel(thermalStatus);
        t.tilesActive = streamer.activeCount();
        t.tilesResident = streamer.residentCount();
        t.tilesPendingIo = streamer.pendingIo();
        t.tilesDecoding = streamer.countInState(WorldStreamer.S_DECODING);
        t.tilesQueuedGpu = streamer.readyCpuCount();
        t.geometryBytes = streamer.residentGeometryBytes();
        t.rebases = origin.rebaseCount();
        t.cars = agents.activeCars();
        t.pedestrians = agents.activePeds();
        t.onWater = world.inBounds(b.position.x, b.position.z)
                && world.isWater(b.position.x, b.position.z);
        t.tideM = ocean.tide();

        Places.Place p = world.places().nearest(b.position.x, b.position.z, 2500);
        t.nearestPlace = p == null ? "" : p.name;

        t.damage = profile == ControlProfile.CAR ? car.damage : 0;

        switch (profile) {
            case CAR:
                t.gear = car.gearLabel();
                t.rpm = car.engineRpm;
                t.auxLabel1 = "grip";
                t.aux1 = CarPhysics.surfaceGrip(false, wetness);
                t.auxLabel2 = "wet";
                t.aux2 = wetness;
                break;
            case BOAT:
                t.gear = "-";
                t.rpm = 0;
                t.auxLabel1 = "knots";
                t.aux1 = boat.speedKnots();
                t.auxLabel2 = "heel";
                t.aux2 = boat.heelDegrees();
                break;
            case HELICOPTER:
                t.gear = heli.engineOn ? "ENG" : "AUTO";
                t.rpm = heli.rotorRpm;
                t.auxLabel1 = "rotor%";
                t.aux1 = heli.rotorPercent();
                t.auxLabel2 = "T/W";
                t.aux2 = heli.thrustToWeight();
                t.warning = heli.rotorPercent() < 80 ? "ROTOR RPM LOW" : "";
                break;
            case AEROPLANE:
                t.gear = plane.gearDown ? "GEAR DN" : "GEAR UP";
                t.rpm = plane.engineRpm;
                t.auxLabel1 = "kt";
                t.aux1 = plane.airspeedKnots();
                t.auxLabel2 = "AoA";
                t.aux2 = Math.toDegrees(plane.angleOfAttack);
                t.warning = plane.stalled ? "STALL" : "";
                break;
            default:
                t.gear = human.swimming ? "SWIM" : (human.ragdoll ? "DOWN" : "-");
                t.rpm = 0;
                t.auxLabel1 = "stam";
                t.aux1 = human.stamina;
                t.auxLabel2 = "depth";
                t.aux2 = human.waterDepth;
                break;
        }
        telemetry.money = missions.money;
        telemetry.objective = missions.objective();
        telemetry.banner = missions.banner;
        telemetry.jobsDone = missions.jobsDone;
        telemetry.wantedStars = wanted.stars();
        telemetry.policeUnits = agents.policeCount();
        telemetry.services = transit.activeCount();
        telemetry.rain = weather.rain;
        telemetry.wetness = weather.wetness;
        telemetry.jobTimeLeft = missions.active != null && missions.active.timed()
                ? Math.max(0, missions.active.timeLeft) : 0;
        telemetry.jobDistance = missions.distanceToTarget(
                activeBody().position.x, activeBody().position.z);

    }

    public Vec3 renderPosition() { return renderPos; }

    /** Interpolated orientation for the current frame. */
    public Quat renderOrientation() { return renderRot; }

    public Quat renderRotation() { return renderRot; }

    /** Where the camera should be this frame, in world metres. */
    /** How far clear of a wall the camera is kept, metres. */
    private static final double CAMERA_CLEARANCE_M = 0.55;

    public Vec3 cameraPosition(Vec3 out) {
        if (firstPerson && profile == ControlProfile.ON_FOOT) {
            return human.eyePosition(out);
        }
        double d = cameraDistance * (profile == ControlProfile.AEROPLANE ? 3.2
                : profile == ControlProfile.HELICOPTER ? 2.4
                : profile == ControlProfile.BOAT ? 2.0 : 1.0);
        double cy = Math.cos(cameraPitch);
        double bx = -Math.sin(cameraYaw) * cy;
        double by = -Math.sin(cameraPitch);
        double bz = Math.cos(cameraYaw) * cy;
        double hx = renderPos.x;
        double hy = renderPos.y + 1.9;
        double hz = renderPos.z;

        // March out from the player's head and stop at the first wall. The
        // camera used to be protected against exactly one thing - sinking
        // into a hill - and against nothing else, so in any dense street it
        // spent its time inside the building behind you, looking at the
        // back of a facade.
        double hit = d;
        for (double t = 1.0; t <= d; t += 1.0) {
            double px = hx + bx * t;
            double py = hy + by * t;
            double pz = hz + bz * t;
            if (city.blocked(px, pz, py, CAMERA_CLEARANCE_M)) {
                hit = Math.max(0.9, t - 1.0);
                break;
            }
        }
        out.set(hx + bx * hit, hy + by * hit, hz + bz * hit);
        // And still do not let it sink into a hill behind the player.
        double g = groundHeight(out.x, out.z) + 1.1;
        if (out.y < g) { out.y = g; }
        return out;
    }

    /**
     * Puts the player, and whatever they are driving, somewhere else.
     *
     * <p>Picked off the full-screen map. There are four things that have
     * to happen together or the arrival is broken:
     *
     * <ul>
     *   <li>the body has to land on the ground at the destination, not at
     *       the altitude it left from, or you arrive underground or in
     *       the air;
     *   <li>velocity and spin have to go, or you arrive at 140 km/h
     *       sideways and the first thing the new neighbourhood does is
     *       kill you;
     *   <li>the car's driveline has to be reset, because wheel speeds
     *       and a slipping clutch carried across a jump describe a
     *       vehicle that no longer exists;
     *   <li>the floating origin has to rebase, because the whole point
     *       of a 52 by 72 km world in double precision is that the
     *       render path never sees a large coordinate. Teleporting 40 km
     *       without rebasing is exactly the case the origin exists for.
     * </ul>
     *
     * @return true if the destination was usable
     */
    public boolean teleportTo(double x, double z) {
        if (!world.inBounds(x, z)) { return false; }

        RigidBody b = activeBody();
        boolean water = world.isWater(x, z);
        double ground = water ? ocean.heightAt(x, z) : world.height(x, z);

        // Clearance enough that the body is not intersecting the ground on
        // the first step, and small enough that it is not a drop.
        double clearance;
        switch (profile) {
            case BOAT: clearance = 0.0; break;
            case HELICOPTER: clearance = 2.2; break;
            case AEROPLANE: clearance = 2.0; break;
            case CAR: clearance = 0.9; break;
            default: clearance = 1.2; break;
        }

        b.position.set(x, ground + clearance, z);
        b.velocity.zero();
        b.angularVelocity.zero();
        // Keep the heading, lose the attitude: arriving upside down
        // because you were mid-barrel-roll is not useful.
        b.orientation.set(Quat.yaw(b.orientation.heading()));

        if (profile == ControlProfile.CAR) {
            for (CarPhysics.Wheel w : car.wheels) {
                w.spin = 0;
                w.slipRatio = 0;
                w.slipAngle = 0;
                w.compression = 0;
                w.lastCompression = 0;
                w.longForce = 0;
                w.latForce = 0;
            }
            car.engineRpm = car.idleRpm;
            car.gear = 1;
            car.shiftTimer = 0;
            car.clutchSlipping = true;
            car.clutchEngagement = 0;
        }

        origin.update(x, z);
        origin.consumeRebase(rebase2);

        // The radar's spatial query is keyed on where the player is, and
        // the HUD would otherwise show the old neighbourhood until the
        // next telemetry tick.
        minimap.update(x, z, b.orientation.heading());

        // Twice, or the first rendered frame interpolates from the old
        // position and the camera flies the length of the island.
        snapshot();
        snapshot();
        mapView.close();
        return true;
    }

    /**
     * Teleports to whatever is currently selected on the map, if it is a
     * place this profile can be put down.
     */
    public boolean teleportToTarget() {
        if (!mapView.hasTarget || !mapView.targetSuits(profile)) { return false; }
        return teleportTo(mapView.targetX, mapView.targetZ);
    }

    public void toggleMap() {
        if (mapView.open) {
            mapView.close();
        } else {
            RigidBody b = activeBody();
            mapView.openAt(b.position.x, b.position.z);
        }
    }

    public void switchProfile(ControlProfile p) {
        if (p == profile) { return; }
        RigidBody from = activeBody();
        profile = p;
        RigidBody to = activeBody();
        to.position.set(from.position);
        to.velocity.zero();
        to.angularVelocity.zero();
        to.orientation.set(from.orientation);
        double g = groundHeight(to.position.x, to.position.z);
        if (to.position.y < g + 1.2) { to.position.y = g + 1.2; }
        snapshot();
        snapshot();
    }
}
