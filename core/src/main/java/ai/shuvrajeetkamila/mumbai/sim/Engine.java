package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.app.Quality;
import ai.shuvrajeetkamila.mumbai.game.Minimap;
import ai.shuvrajeetkamila.mumbai.game.Missions;
import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
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
    /** Radar geometry. Refreshed with the telemetry, not every frame. */
    public final Minimap minimap;

    /** Jobs, money and progression. */
    public final Missions missions;

    public final HumanPhysics human = new HumanPhysics();
    public final CarPhysics car = new CarPhysics();
    public final BoatPhysics boat = BoatPhysics.ferry();
    public final HelicopterPhysics heli = new HelicopterPhysics();
    public final AeroplanePhysics plane = new AeroplanePhysics();

    public ControlProfile profile = ControlProfile.ON_FOOT;
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

    private final CarPhysics.GroundProbe carGround = new CarPhysics.GroundProbe() {
        @Override public double heightAt(double x, double z) {
            if (!world.inBounds(x, z)) { return 0; }
            if (world.isWater(x, z)) { return ocean.heightAt(x, z) - 0.35; }
            return world.height(x, z);
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
    };

    // Assigned in the constructor rather than here: a lambda in a field
    // initializer cannot see a blank final field that is set further down.
    private final BoatPhysics.SeabedProbe seabed;
    private final HelicopterPhysics.GroundProbe heliGround;
    private final AeroplanePhysics.GroundProbe planeGround;

    private final HumanPhysics.Terrain humanTerrain = new HumanPhysics.Terrain() {
        @Override public double heightAt(double x, double z) {
            return world.inBounds(x, z) ? world.height(x, z) : 0;
        }

        @Override public boolean isWater(double x, double z) {
            return world.inBounds(x, z) && world.isWater(x, z);
        }

        @Override public double slopeAt(double x, double z) {
            return world.inBounds(x, z) ? world.slope(x, z) : 0;
        }
    };

    public Engine(WorldData world) {
        this.world = world;
        this.streamer = new WorldStreamer(world);
        this.agents = new Agents(world);
        this.minimap = new Minimap(world);
        this.missions = new Missions(world, minimap, 0x5A1_5E77EL);
        this.seabed = (x, z) -> world.inBounds(x, z) ? world.height(x, z) : -40;
        this.heliGround = (x, z) -> {
            if (!world.inBounds(x, z)) { return 0; }
            return world.isWater(x, z) ? ocean.heightAt(x, z) : world.height(x, z);
        };
        this.planeGround = (x, z) -> {
            if (!world.inBounds(x, z)) { return 0; }
            return world.isWater(x, z) ? ocean.heightAt(x, z) : world.height(x, z);
        };
        applyQuality(quality);
    }

    /** Drop the player at a latitude and longitude, on the ground. */
    public void spawnAt(double lat, double lon, ControlProfile p) {
        double x = GeoProjection.toX(lat, lon);
        double z = GeoProjection.toZ(lat);
        profile = p;
        RigidBody b = activeBody();
        b.position.set(x, world.inBounds(x, z) ? world.height(x, z) + 1.4 : 2.0, z);
        b.velocity.zero();
        b.angularVelocity.zero();
        b.orientation.identity();
        origin.reset();
        origin.update(x, z);
        origin.consumeRebase(rebase2);
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
        int steps = clock.beginFrame(frameSeconds);
        for (int i = 0; i < steps; i++) {
            snapshot();
            fixedStep(FixedStep.STEP, in);
        }
        timeOfDay += frameSeconds / SECONDS_PER_GAME_HOUR;
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
            telemetryTimer = 0;
        }
        in.clearEdges();
    }

    private double groundHeight(double x, double z) {
        return world.inBounds(x, z) ? world.height(x, z) : 0;
    }

    private void fixedStep(double dt, InputState in) {
        ocean.advance(dt);
        // The monsoon raises the sea state as well as wetting the roads.
        ocean.setChoppiness(0.8 + wetness * 1.1);

        if (in.enterExit) {
            if (profile == ControlProfile.ON_FOOT) { tryEnterVehicle(); }
            else if (profile == ControlProfile.CAR) { exitVehicle(); }
        }

        switch (profile) {
            case CAR: stepCar(dt, in); break;
            case BOAT: stepBoat(dt, in); break;
            case HELICOPTER: stepHeli(dt, in); break;
            case AEROPLANE: stepPlane(dt, in); break;
            default: stepHuman(dt, in); break;
        }

        RigidBody b = activeBody();
        agents.update(dt, b.position.x, b.position.z, b.velocity.x, b.velocity.z,
                quality.medRadius * 0.55, quality.medRadius);
        missions.update(dt, b.position.x, b.position.z,
                Math.hypot(b.velocity.x, b.velocity.z), drivingKind());
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
        if (best < 0) { return false; }

        Agents.Car c = cs[best];
        VehicleKind k = kindOf(c);
        car.configure(k);
        car.reset();
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
    public boolean exitVehicle() {
        if (profile != ControlProfile.CAR) { return false; }
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
        return Math.sqrt(best);
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
        heli.cyclicPitch = -in.lookY;
        heli.cyclicRoll = in.lookX;
        heli.pedal = in.pedal;
        if (in.engineToggle) { heli.engineOn = !heli.engineOn; }
        heli.step(dt, heliGround, windX, windZ);
    }

    private void stepPlane(double dt, InputState in) {
        plane.throttle = in.throttle;
        plane.elevator = -in.lookY;
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
            t.prompt = nearestEnterableDistance() <= ENTER_RADIUS_M
                    ? "ENTER to get in" : "";
        } else if (profile == ControlProfile.CAR) {
            t.prompt = car.groundSpeed() <= 3.0 ? "EXIT to get out" : "";
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
    public Vec3 cameraPosition(Vec3 out) {
        if (firstPerson && profile == ControlProfile.ON_FOOT) {
            return human.eyePosition(out);
        }
        double d = cameraDistance * (profile == ControlProfile.AEROPLANE ? 3.2
                : profile == ControlProfile.HELICOPTER ? 2.4
                : profile == ControlProfile.BOAT ? 2.0 : 1.0);
        double cy = Math.cos(cameraPitch);
        out.set(renderPos.x - Math.sin(cameraYaw) * cy * d,
                renderPos.y + 1.9 - Math.sin(cameraPitch) * d,
                renderPos.z + Math.cos(cameraYaw) * cy * d);
        // Do not let the camera sink into a hill behind the player.
        double g = groundHeight(out.x, out.z) + 1.1;
        if (out.y < g) { out.y = g; }
        return out;
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
