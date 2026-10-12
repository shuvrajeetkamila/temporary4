package ai.shuvrajeetkamila.mumbai.game;

import java.io.IOException;
import java.util.List;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.geo.GeoProjection;
import ai.shuvrajeetkamila.mumbai.human.HumanController;
import ai.shuvrajeetkamila.mumbai.physics.Atmosphere;
import ai.shuvrajeetkamila.mumbai.physics.CollisionWorld;
import ai.shuvrajeetkamila.mumbai.physics.RigidBody;
import ai.shuvrajeetkamila.mumbai.vehicle.AeroplanePhysics;
import ai.shuvrajeetkamila.mumbai.vehicle.BoatPhysics;
import ai.shuvrajeetkamila.mumbai.vehicle.CarPhysics;
import ai.shuvrajeetkamila.mumbai.vehicle.HelicopterPhysics;
import ai.shuvrajeetkamila.mumbai.vehicle.VehicleConfig;
import ai.shuvrajeetkamila.mumbai.world.LandUse;
import ai.shuvrajeetkamila.mumbai.world.WaterModel;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldStreamer;

/**
 * Simulation owner: world data, streaming, the five physical systems, the camera and the
 * fixed timestep loop.
 *
 * <p>Physics runs at a fixed 60 Hz regardless of the rendered frame rate. A frame delta is
 * clamped to 0.1 s and at most four substeps are consumed per frame, so a stall cannot
 * cause a spiral of death; the leftover fraction becomes the interpolation alpha used to
 * draw the player between two physics states.
 *
 * <p>This class is pure Java with no Android dependency so the whole simulation can be
 * exercised from JUnit on a desktop JVM.
 */
public final class Engine {

    public static final float STEP = 1f / 60f;
    public static final int MAX_SUBSTEPS = 4;
    public static final float MAX_FRAME_DT = 0.1f;

    public final Telemetry telemetry;
    public final InputState input;

    public WorldData world;
    public WorldStreamer streamer;
    public WaterModel water;
    public Atmosphere atmosphere;
    public CollisionWorld collision;
    public FloatingOrigin origin = new FloatingOrigin();
    public Camera camera = new Camera();
    public AgentSystem agents;

    public HumanController human = new HumanController();
    public CarPhysics car;
    public BoatPhysics boat;
    public HelicopterPhysics heli;
    public AeroplanePhysics plane;

    public ControlProfile mode = ControlProfile.ON_FOOT;
    public QualityProfile quality = QualityProfile.BALANCED;
    public boolean paused;
    public boolean ready;

    private float accumulator;
    private int lastSubsteps;
    private float alpha;
    private long physicsSteps;
    private float simMsAccum;

    // Interpolation snapshots of the controlled actor.
    private double prevX, prevY, prevZ;
    private double curX, curY, curZ;
    private float prevQx, prevQy, prevQz, prevQw = 1f;
    private float curQx, curQy, curQz, curQw = 1f;

    private float lookYaw;
    private float lookPitch;
    private float flapTarget;
    private float sinceLocationCheck;
    private String districtName = "Mumbai";
    private final int[] roadIdx = new int[1];

    public Engine(Telemetry telemetry, InputState input) {
        this.telemetry = telemetry;
        this.input = input;
    }

    // =====================================================================================
    // Loading
    // =====================================================================================

    /** Loads the packaged world and prepares every subsystem. Safe to call once. */
    public void load(WorldData.AssetSource source, WorldStreamer.TileSink sink, int workerThreads)
            throws IOException, ai.shuvrajeetkamila.mumbai.world.MwlReader.WorldFormatException {
        telemetry.loadStage = "Reading world data";
        telemetry.loadProgress = 0.05f;
        world = WorldData.load(source);

        telemetry.loadStage = "Preparing simulation";
        telemetry.loadProgress = 0.45f;
        water = new WaterModel();
        atmosphere = new Atmosphere();
        streamer = new WorldStreamer(world, workerThreads);
        streamer.setSink(sink);
        collision = new CollisionWorld(world, streamer, water, atmosphere);
        agents = new AgentSystem(world, collision);

        car = new CarPhysics(VehicleConfig.taxi());
        boat = new BoatPhysics(new BoatPhysics.Config());
        heli = new HelicopterPhysics(new HelicopterPhysics.Config());
        plane = new AeroplanePhysics(AeroplanePhysics.Config.trainer());

        telemetry.loadStage = "Placing player";
        telemetry.loadProgress = 0.7f;
        spawnPlayer();

        telemetry.loadStage = "Streaming first tiles";
        telemetry.loadProgress = 0.85f;
        applyQuality(quality);
        streamer.update(human.body.px, human.body.pz, 0f, 0f, 0f, quality);

        telemetry.loadProgress = 1f;
        telemetry.loadStage = "Ready";
        telemetry.loading = false;
        ready = true;
    }

    private void spawnPlayer() {
        double sx = world.spawnX;
        double sz = world.spawnZ;
        float h = world.heightAt(sx, sz);
        if (LandUse.isWater(world.landUseAt(sx, sz))) {
            // Never start the player in the sea: walk inland until there is dry land.
            for (int r = 40; r < 2000; r += 40) {
                boolean found = false;
                for (int a = 0; a < 16; a++) {
                    double ang = a / 16.0 * Math.PI * 2.0;
                    double tx = sx + Math.cos(ang) * r;
                    double tz = sz + Math.sin(ang) * r;
                    if (!LandUse.isWater(world.landUseAt(tx, tz))) {
                        sx = tx;
                        sz = tz;
                        h = world.heightAt(sx, sz);
                        found = true;
                        break;
                    }
                }
                if (found) {
                    break;
                }
            }
        }
        human.placeAt(sx, h + 0.1, sz, world.spawnHeading);
        lookYaw = world.spawnHeading;
        origin.reset(sx, h, sz);
        camera.snapTo(sx, h + 20, sz + 12, world.spawnHeading);
        snapshot();
        snapshot();
    }

    // =====================================================================================
    // Frame
    // =====================================================================================

    /**
     * Advances the simulation. {@code frameDt} is wall clock seconds since the previous
     * frame; the physics itself always advances in {@link #STEP} increments.
     */
    public void update(float frameDt) {
        if (!ready) {
            return;
        }
        long t0 = System.nanoTime();
        float dt = Mth.clamp(frameDt, 0f, MAX_FRAME_DT);

        handleModeActions();

        if (paused) {
            accumulator = 0f;
            updateCamera(dt);
            publishTelemetry(0f);
            return;
        }

        accumulator += dt;
        int steps = 0;
        while (accumulator >= STEP && steps < MAX_SUBSTEPS) {
            snapshot();
            fixedStep(STEP);
            accumulator -= STEP;
            steps++;
            physicsSteps++;
        }
        if (steps == MAX_SUBSTEPS && accumulator > STEP) {
            // Too far behind to catch up: drop the backlog instead of spiralling.
            accumulator = 0f;
        }
        lastSubsteps = steps;
        alpha = Mth.clamp01(accumulator / STEP);

        updateStreaming();
        updateCamera(dt);

        simMsAccum = Mth.lerp(simMsAccum, (System.nanoTime() - t0) / 1e6f, 0.12f);
        publishTelemetry(simMsAccum);
    }

    private void fixedStep(float dt) {
        atmosphere.update(dt);
        float windSpeed = (float) Math.sqrt(atmosphere.windX * atmosphere.windX
                + atmosphere.windZ * atmosphere.windZ);
        water.update(dt, atmosphere.tide, windSpeed);

        applyLook(dt);

        switch (mode) {
            case CAR:
                driveCar(dt);
                break;
            case BOAT:
                driveBoat(dt);
                break;
            case HELICOPTER:
                driveHeli(dt);
                break;
            case AEROPLANE:
                drivePlane(dt);
                break;
            default:
                driveHuman(dt);
                break;
        }

        agents.update(dt, focusX(), focusZ(), quality);
    }

    private void applyLook(float dt) {
        float sens = 1.9f;
        lookYaw = Mth.wrapPi(lookYaw + input.lookX * dt * sens);
        lookPitch = Mth.clamp(lookPitch - input.lookY * dt * sens * 0.7f, -1.25f, 1.1f);
    }

    // --- per mode ------------------------------------------------------------------------

    private void driveHuman(float dt) {
        human.heading = lookYaw;
        human.moveX = input.moveX;
        human.moveZ = input.moveY;
        human.sprint = input.run;
        human.crouch = input.handbrake;
        if (input.jump) {
            human.jumpRequested = true;
            input.jump = false;
        }
        human.step(dt, collision);
        clampToWorld(human.body);
    }

    private void driveCar(float dt) {
        car.throttle = input.throttle;
        car.brake = input.brake;
        car.steerInput = input.steer;
        car.handbrake = input.handbrake ? 1f : 0f;
        car.step(dt, collision);
        clampToWorld(car.body);
        carryPassenger(car.body, 0.0f, 0.2f, 0.0f);
    }

    private void driveBoat(float dt) {
        boat.throttle = input.throttle - input.brake;
        boat.rudderInput = input.steer;
        boat.step(dt, collision);
        clampToWorld(boat.body);
        carryPassenger(boat.body, 0f, 0.9f, 0f);
    }

    private void driveHeli(float dt) {
        heli.collective = input.collective;
        heli.cyclicPitch = input.pitch;
        heli.cyclicRoll = input.steer;
        heli.pedal = input.yaw;
        heli.step(dt, collision);
        clampToWorld(heli.body);
        carryPassenger(heli.body, 0f, 0f, 0f);
    }

    private void drivePlane(float dt) {
        plane.throttle = input.throttle;
        plane.brakes = input.brake;
        plane.elevator = input.pitch;
        plane.aileron = input.steer;
        plane.rudder = input.yaw;
        flapTarget = Mth.clamp(input.flapNotch / 2f, 0f, 1f);
        plane.flaps = Mth.approach(plane.flaps, flapTarget, dt * 0.35f);
        plane.step(dt, collision);
        clampToWorld(plane.body);
        carryPassenger(plane.body, 0f, 0f, 0f);
    }

    /** Keeps the seated character glued to the vehicle so exiting lands somewhere sane. */
    private void carryPassenger(RigidBody b, float lx, float ly, float lz) {
        human.body.px = b.px;
        human.body.py = b.py + ly;
        human.body.pz = b.pz;
        human.body.vx = b.vx;
        human.body.vy = b.vy;
        human.body.vz = b.vz;
        human.heading = b.yaw();
    }

    private void clampToWorld(RigidBody b) {
        double margin = 150.0;
        boolean clamped = false;
        if (b.px < world.minX + margin) {
            b.px = world.minX + margin;
            clamped = true;
        } else if (b.px > world.maxX - margin) {
            b.px = world.maxX - margin;
            clamped = true;
        }
        if (b.pz < world.minZ + margin) {
            b.pz = world.minZ + margin;
            clamped = true;
        } else if (b.pz > world.maxZ - margin) {
            b.pz = world.maxZ - margin;
            clamped = true;
        }
        if (clamped) {
            b.vx *= 0.1f;
            b.vz *= 0.1f;
        }
        if (b.py > 12000) {
            b.py = 12000;
            if (b.vy > 0) {
                b.vy = 0;
            }
        }
        if (!b.finiteState()) {
            b.recoverFromNaN();
        }
    }

    // --- actions ---------------------------------------------------------------------------

    private void handleModeActions() {
        if (input.consumePause()) {
            paused = !paused;
        }
        if (input.consumeCameraCycle()) {
            camera.cycleMode();
        }
        if (input.consumeRecover()) {
            recover();
        }
        if (input.consumeEngineToggle()) {
            switch (mode) {
                case CAR:
                    car.ignition = !car.ignition;
                    toast(car.ignition ? "Engine on" : "Engine off");
                    break;
                case HELICOPTER:
                    heli.engineOn = !heli.engineOn;
                    toast(heli.engineOn ? "Turbine running" : "Engine cut: autorotate");
                    break;
                case AEROPLANE:
                    plane.gearDown = !plane.gearDown;
                    toast(plane.gearDown ? "Gear down" : "Gear up");
                    break;
                default:
                    break;
            }
        }
        if (input.consumeGearToggle() && mode == ControlProfile.AEROPLANE) {
            input.flapNotch = (input.flapNotch + 1) % 3;
            toast("Flaps " + input.flapNotch);
        }
    }

    private void toast(String msg) {
        telemetry.showToast(msg, System.currentTimeMillis(), 1800);
    }

    /** Teleports the player into the requested system at the nearest sensible location. */
    public void switchMode(ControlProfile target) {
        if (!ready || target == mode) {
            return;
        }
        double px = focusX();
        double pz = focusZ();
        switch (target) {
            case ON_FOOT: {
                double[] spot = safeFootSpot(px, pz);
                human.placeAt(spot[0], world.heightAt(spot[0], spot[1]) + 0.05, spot[1], lookYaw);
                toast("On foot");
                break;
            }
            case CAR: {
                double[] spot = nearestRoadPoint(px, pz);
                car.placeAt(spot[0], world.heightAt(spot[0], spot[1]) + 0.6, spot[1],
                        (float) spot[2]);
                car.ignition = true;
                toast(car.cfg.name + " ready");
                break;
            }
            case BOAT: {
                WorldData.Spot launch = world.nearestSpot(world.boatLaunches, px, pz);
                double bx = launch != null ? launch.x : px;
                double bz = launch != null ? launch.z : pz;
                double[] wet = nearestWater(bx, bz);
                float surf = collision.waterSurface(wet[0], wet[1]);
                if (surf == Float.NEGATIVE_INFINITY) {
                    surf = water.tidalLevel();
                }
                boat.placeAt(wet[0], surf + boat.cfg.draft * 0.2, wet[1], lookYaw);
                toast((launch != null ? launch.name + ": " : "") + boat.cfg.name);
                break;
            }
            case HELICOPTER: {
                WorldData.Spot pad = world.nearestSpot(world.helipads, px, pz);
                double hx = pad != null ? pad.x : px;
                double hz = pad != null ? pad.z : pz;
                float padY = pad != null ? Math.max(world.heightAt(hx, hz), pad.value)
                        : world.heightAt(hx, hz);
                heli.placeAt(hx, padY + 1.4, hz, lookYaw);
                heli.engineOn = true;
                toast((pad != null ? pad.name : "Helipad") + " - collective to lift");
                break;
            }
            case AEROPLANE: {
                WorldData.Airport ap = world.nearestAirport(px, pz);
                if (ap != null && !ap.runways.isEmpty()) {
                    WorldData.Runway rw = ap.runways.get(0);
                    float hx = (float) Math.sin(rw.heading);
                    float hz = -(float) Math.cos(rw.heading);
                    double sx = rw.x - hx * rw.length * 0.44;
                    double sz = rw.z - hz * rw.length * 0.44;
                    plane.placeAt(sx, Math.max(world.heightAt(sx, sz), ap.elevation)
                            - plane.cfg.gearHeight, sz, rw.heading);
                    toast(ap.name + " runway " + rw.designator + " - "
                            + Math.round(rw.length) + " m");
                } else {
                    plane.placeAt(px, world.heightAt(px, pz) - plane.cfg.gearHeight, pz, lookYaw);
                }
                plane.gearDown = true;
                input.flapNotch = 1;
                break;
            }
            default:
                break;
        }
        mode = target;
        telemetry.controlProfile = target.displayName();
        collision.invalidateBoxes();
        snapshot();
        snapshot();
        camera.snapTo(focusX(), focusY() + 6, focusZ() + 10, lookYaw);
    }

    /** Swaps the ground vehicle without changing mode. */
    public void setCarConfig(VehicleConfig cfg) {
        double x = car.body.px;
        double y = car.body.py;
        double z = car.body.pz;
        float yaw = car.body.yaw();
        car.setConfig(cfg);
        car.placeAt(x, y + 0.4, z, yaw);
        toast(cfg.name);
    }

    public void setBoatConfig(BoatPhysics.Config cfg) {
        double x = boat.body.px;
        double z = boat.body.pz;
        float yaw = boat.body.yaw();
        boat.setConfig(cfg);
        float surf = collision.waterSurface(x, z);
        boat.placeAt(x, surf == Float.NEGATIVE_INFINITY ? water.tidalLevel() : surf, z, yaw);
        toast(cfg.name);
    }

    public void setHeliConfig(HelicopterPhysics.Config cfg) {
        double x = heli.body.px;
        double y = heli.body.py;
        double z = heli.body.pz;
        float yaw = heli.body.yaw();
        heli.setConfig(cfg);
        heli.placeAt(x, y, z, yaw);
        toast(cfg.name);
    }

    public void setPlaneConfig(AeroplanePhysics.Config cfg) {
        plane.setConfig(cfg);
        switchModeRespawnPlane();
        toast(cfg.name);
    }

    private void switchModeRespawnPlane() {
        WorldData.Airport ap = world.nearestAirport(focusX(), focusZ());
        if (ap != null && !ap.runways.isEmpty()) {
            WorldData.Runway rw = ap.runways.get(0);
            float hx = (float) Math.sin(rw.heading);
            float hz = -(float) Math.cos(rw.heading);
            double sx = rw.x - hx * rw.length * 0.44;
            double sz = rw.z - hz * rw.length * 0.44;
            plane.placeAt(sx, Math.max(world.heightAt(sx, sz), ap.elevation)
                    - plane.cfg.gearHeight, sz, rw.heading);
        }
    }

    /** Unsticks the player: upright, on the nearest valid surface, stopped. */
    public void recover() {
        double x = focusX();
        double z = focusZ();
        switch (mode) {
            case CAR: {
                double[] spot = nearestRoadPoint(x, z);
                car.placeAt(spot[0], world.heightAt(spot[0], spot[1]) + 0.8, spot[1],
                        (float) spot[2]);
                car.ignition = true;
                break;
            }
            case BOAT: {
                double[] wet = nearestWater(x, z);
                float surf = collision.waterSurface(wet[0], wet[1]);
                boat.placeAt(wet[0], surf == Float.NEGATIVE_INFINITY ? water.tidalLevel() : surf,
                        wet[1], boat.body.yaw());
                break;
            }
            case HELICOPTER:
                heli.placeAt(x, world.heightAt(x, z) + 1.6, z, heli.body.yaw());
                break;
            case AEROPLANE:
                switchModeRespawnPlane();
                break;
            default: {
                double[] spot = safeFootSpot(x, z);
                human.placeAt(spot[0], world.heightAt(spot[0], spot[1]) + 0.05, spot[1], lookYaw);
                break;
            }
        }
        collision.invalidateBoxes();
        snapshot();
        snapshot();
        toast("Recovered");
    }

    private double[] safeFootSpot(double x, double z) {
        if (!LandUse.isWater(world.landUseAt(x, z))) {
            return new double[]{x, z};
        }
        for (int r = 25; r < 4000; r += 25) {
            for (int a = 0; a < 12; a++) {
                double ang = a / 12.0 * Math.PI * 2.0;
                double tx = x + Math.cos(ang) * r;
                double tz = z + Math.sin(ang) * r;
                if (!LandUse.isWater(world.landUseAt(tx, tz))) {
                    return new double[]{tx, tz};
                }
            }
        }
        return new double[]{world.spawnX, world.spawnZ};
    }

    private double[] nearestWater(double x, double z) {
        if (collision.waterDepth(x, z) > 1.2f) {
            return new double[]{x, z};
        }
        for (int r = 30; r < 6000; r += 30) {
            for (int a = 0; a < 16; a++) {
                double ang = a / 16.0 * Math.PI * 2.0;
                double tx = x + Math.cos(ang) * r;
                double tz = z + Math.sin(ang) * r;
                if (collision.waterDepth(tx, tz) > 1.5f) {
                    return new double[]{tx, tz};
                }
            }
        }
        return new double[]{x, z};
    }

    /** Nearest point on a road plus its heading, used to put a car back on tarmac. */
    private double[] nearestRoadPoint(double x, double z) {
        double bestD = Double.MAX_VALUE;
        double bx = x, bz = z, bh = lookYaw;
        for (int i = 0; i < world.roads.size(); i++) {
            WorldData.Polyline p = world.roads.get(i);
            for (int k = 1; k < p.pointCount(); k++) {
                double ax = p.x(k - 1), az = p.z(k - 1);
                double cx = p.x(k), cz = p.z(k);
                double vx = cx - ax, vz = cz - az;
                double len2 = vx * vx + vz * vz;
                if (len2 < 1e-6) {
                    continue;
                }
                double t = Mth.clamp(((x - ax) * vx + (z - az) * vz) / len2, 0.0, 1.0);
                double qx = ax + vx * t;
                double qz = az + vz * t;
                double d = (qx - x) * (qx - x) + (qz - z) * (qz - z);
                if (d < bestD) {
                    bestD = d;
                    // Left-hand traffic: sit on the left side of the centre line.
                    double inv = 1.0 / Math.sqrt(len2);
                    double nx = -vz * inv;
                    double nz = vx * inv;
                    double offset = Math.max(2.6, p.lanes * 1.6);
                    bx = qx - nx * offset;
                    bz = qz - nz * offset;
                    bh = Math.atan2(vx, -vz);
                }
            }
        }
        return new double[]{bx, bz, bh};
    }

    // --- streaming / camera / telemetry ------------------------------------------------------

    private void updateStreaming() {
        RigidBody b = activeBody();
        float altitude = (float) (b.py - world.heightAt(b.px, b.pz));
        streamer.update(b.px, b.pz, altitude, b.vx, b.vz, quality);
    }

    private void updateCamera(float dt) {
        RigidBody b = activeBody();
        double ix = Mth.lerp(prevX, curX, alpha);
        double iy = Mth.lerp(prevY, curY, alpha);
        double iz = Mth.lerp(prevZ, curZ, alpha);

        float follow;
        float eye;
        switch (mode) {
            case ON_FOOT:
                follow = lookYaw;
                eye = human.eyeHeight();
                camera.distance = 4.2f;
                camera.heightOffset = 1.5f;
                break;
            case CAR:
                follow = b.yaw();
                eye = car.cfg.bodyHeight * 0.55f;
                camera.distance = 6.5f + car.cfg.bodyLength * 0.55f;
                camera.heightOffset = 2.1f + car.cfg.bodyHeight * 0.5f;
                break;
            case BOAT:
                follow = b.yaw();
                eye = boat.cfg.hullHeight * 0.6f;
                camera.distance = 8f + boat.cfg.length * 0.7f;
                camera.heightOffset = 3.4f + boat.cfg.hullHeight * 0.6f;
                break;
            case HELICOPTER:
                follow = b.yaw();
                eye = 1.2f;
                camera.distance = 16f + heli.cfg.bodyLength * 0.7f;
                camera.heightOffset = 5.0f;
                break;
            default:
                follow = b.yaw();
                eye = 1.4f;
                camera.distance = 22f + plane.cfg.bodyLength * 0.8f;
                camera.heightOffset = 6.5f;
                break;
        }
        if (camera.mode == Camera.MODE_CHASE && mode != ControlProfile.ON_FOOT) {
            // Let the player swing the chase camera with the look stick.
            follow += lookYaw * 0.0f;
        }
        if (camera.mode == Camera.MODE_ORBIT) {
            camera.orbitYaw = lookYaw;
            camera.orbitPitch = lookPitch;
        }
        camera.update(dt, ix, iy + eye, iz, follow, b.speed(), collision, 62f);
        origin.update(camera.px, camera.py, camera.pz);
    }

    private void publishTelemetry(float simMs) {
        RigidBody b = activeBody();
        telemetry.simMs = simMs;
        telemetry.worldX = b.px;
        telemetry.worldY = b.py;
        telemetry.worldZ = b.pz;
        GeoProjection proj = world.projection();
        telemetry.latitude = proj.toLat(b.pz);
        telemetry.longitude = proj.toLon(b.px, b.pz);
        telemetry.headingDeg = Mth.wrap360(b.yaw() * Mth.RAD2DEG);
        telemetry.speedKmh = b.speed() * 3.6f;
        telemetry.speedKnots = b.speed() * 1.94384f;
        telemetry.altitudeM = (float) b.py;
        telemetry.verticalSpeed = b.vy;
        telemetry.timeOfDayHours = atmosphere.timeOfDay;
        telemetry.rainIntensity = atmosphere.rain;
        telemetry.tideMetres = atmosphere.tide;
        telemetry.profileName = quality.displayName();
        telemetry.chunksActive = streamer.activeCount();
        telemetry.chunksPending = streamer.pendingCount();
        telemetry.chunksCpuReady = streamer.readyCount();
        telemetry.trafficAgents = agents.vehicleCount();
        telemetry.pedestrians = agents.pedestrianCount();
        telemetry.paused = paused;
        telemetry.controlProfile = mode.displayName();

        switch (mode) {
            case CAR:
                telemetry.engineRpm = car.engineRpm;
                telemetry.gear = car.gear - 1;
                telemetry.engineOn = car.ignition;
                break;
            case HELICOPTER:
                telemetry.rotorRpm = heli.rotorRpmPercent();
                telemetry.collective = heli.collective;
                telemetry.engineOn = heli.engineOn;
                telemetry.altitudeM = heli.altitudeAgl(collision);
                break;
            case AEROPLANE:
                telemetry.airspeedKts = plane.indicatedAirspeed * 1.94384f;
                telemetry.stallWarning = plane.stalled
                        || (!plane.onGround && plane.indicatedAirspeed
                        < plane.stallSpeed(Atmosphere.RHO_SEA_LEVEL) * 1.12f);
                telemetry.flapNotch = Math.round(plane.flaps * 2f);
                telemetry.gearDown = plane.gearDown;
                telemetry.altitudeM = plane.altitudeAgl(collision);
                // The aeroplane has no ignition switch in this build: the powerplant is
                // live whenever the throttle is, which is what the HUD should say.
                telemetry.engineOn = plane.throttle > 0.01f;
                break;
            case BOAT:
                telemetry.engineOn = true;
                break;
            default:
                break;
        }

        sinceLocationCheck += STEP;
        if (sinceLocationCheck > 0.75f) {
            sinceLocationCheck = 0f;
            districtName = world.districtAt(b.px, b.pz);
            telemetry.locationName = districtName;
            telemetry.contextHint = contextHint();
        }
    }

    private String contextHint() {
        switch (mode) {
            case ON_FOOT:
                return human.swimming ? "Swimming" : (human.isRagdoll() ? "Getting up" : "");
            case CAR:
                return car.inWater ? "Water on the road" : (car.skidAmount() > 0.5f
                        ? "Losing grip" : "");
            case BOAT:
                return boat.beached ? "Beached" : (boat.planing ? "On the plane" : "");
            case HELICOPTER:
                return heli.autorotating ? "AUTOROTATION" : (heli.inGroundEffect
                        ? "In ground effect" : "");
            case AEROPLANE:
                return plane.stalled ? "STALL" : (plane.onGround ? "On the ground" : "");
            default:
                return "";
        }
    }

    // --- interpolation + accessors ------------------------------------------------------------

    private void snapshot() {
        RigidBody b = activeBody();
        prevX = curX;
        prevY = curY;
        prevZ = curZ;
        prevQx = curQx;
        prevQy = curQy;
        prevQz = curQz;
        prevQw = curQw;
        curX = b.px;
        curY = b.py;
        curZ = b.pz;
        curQx = b.qx;
        curQy = b.qy;
        curQz = b.qz;
        curQw = b.qw;
    }

    public RigidBody activeBody() {
        switch (mode) {
            case CAR:
                return car.body;
            case BOAT:
                return boat.body;
            case HELICOPTER:
                return heli.body;
            case AEROPLANE:
                return plane.body;
            default:
                return human.body;
        }
    }

    public double focusX() {
        return activeBody().px;
    }

    public double focusY() {
        return activeBody().py;
    }

    public double focusZ() {
        return activeBody().pz;
    }

    public float interpolationAlpha() {
        return alpha;
    }

    public int lastSubstepCount() {
        return lastSubsteps;
    }

    public long physicsStepCount() {
        return physicsSteps;
    }

    /** Interpolated render position of the controlled actor. */
    public void renderPosition(double[] out) {
        out[0] = Mth.lerp(prevX, curX, alpha);
        out[1] = Mth.lerp(prevY, curY, alpha);
        out[2] = Mth.lerp(prevZ, curZ, alpha);
    }

    /** Interpolated render orientation (shortest-arc quaternion lerp). */
    public void renderOrientation(float[] out) {
        float dot = prevQx * curQx + prevQy * curQy + prevQz * curQz + prevQw * curQw;
        float sign = dot < 0f ? -1f : 1f;
        float x = Mth.lerp(prevQx, curQx * sign, alpha);
        float y = Mth.lerp(prevQy, curQy * sign, alpha);
        float z = Mth.lerp(prevQz, curQz * sign, alpha);
        float w = Mth.lerp(prevQw, curQw * sign, alpha);
        float n = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (n < 1e-6f) {
            out[0] = 0;
            out[1] = 0;
            out[2] = 0;
            out[3] = 1;
            return;
        }
        out[0] = x / n;
        out[1] = y / n;
        out[2] = z / n;
        out[3] = w / n;
    }

    public void applyQuality(QualityProfile p) {
        quality = p;
        if (agents != null) {
            agents.setCaps(p.trafficCap, p.pedestrianCap);
        }
        telemetry.profileName = p.displayName();
        telemetry.thermalEco = p.isEco();
    }

    public List<WorldStreamer.Tile> renderTiles(List<WorldStreamer.Tile> out) {
        streamer.collectRenderable(out);
        return out;
    }

    public void shutdown() {
        if (streamer != null) {
            streamer.shutdown();
        }
        ready = false;
    }
}
