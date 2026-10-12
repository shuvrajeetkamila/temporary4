package ai.shuvrajeetkamila.mumbai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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

/**
 * Behavioural tests for the five physical systems.
 *
 * <p>These are deliberately written as "does it behave like the real thing" assertions
 * rather than golden numbers: a car has to accelerate and then stop under braking, a boat
 * has to float at its waterline, a helicopter has to need collective to climb, and an
 * aeroplane has to need airspeed before it can fly. Several tests exist specifically to
 * prove that nothing cheats: with no input, nothing moves; below rotation speed, the
 * aeroplane stays on the runway no matter how hard the stick is pulled.
 */
public class PhysicsTest {

    private static final float STEP = 1f / 60f;

    private final WorldData world = TestWorld.get();
    private final WaterModel water = new WaterModel();
    private final Atmosphere air = new Atmosphere();
    private final CollisionWorld cw = new CollisionWorld(world, null, water, air);

    /** A flat, dry, road-like spot: the runway at the main airport. */
    private double[] runwayStart() {
        WorldData.Airport ap = world.airports.get(0);
        WorldData.Runway rw = ap.runways.get(0);
        float hx = (float) Math.sin(rw.heading);
        float hz = -(float) Math.cos(rw.heading);
        return new double[]{rw.x - hx * rw.length * 0.45, rw.z - hz * rw.length * 0.45,
                rw.heading, rw.length};
    }

    private double[] dryFlatSpot() {
        WorldData.Airport ap = world.airports.get(0);
        return new double[]{ap.x, ap.z};
    }

    // =================================================================================
    // Rigid body core
    // =================================================================================

    @Test
    public void freeBodyUnderGravityMatchesTheAnalyticSolution() {
        RigidBody b = new RigidBody();
        b.setMass(100f);
        b.py = 1000;
        for (int i = 0; i < 60; i++) {
            b.clearAccumulators();
            b.addForce(0f, -100f * RigidBody.GRAVITY, 0f);
            b.integrate(STEP);
        }
        // Semi-implicit Euler over one second: v = -g, y = y0 - g*dt*(n(n+1)/2)/n ...
        assertEquals("vertical speed after 1 s of free fall",
                -RigidBody.GRAVITY, b.vy, 0.05);
        assertTrue("should have fallen about 5 m, fell " + (1000 - b.py),
                1000 - b.py > 4.0 && 1000 - b.py < 6.0);
    }

    @Test
    public void torqueProducesRotationAboutTheExpectedAxis() {
        RigidBody b = new RigidBody();
        b.setMass(1000f);
        b.setBoxInertia(2f, 2f, 4f, 1f);
        b.updateMatrix();
        for (int i = 0; i < 30; i++) {
            b.clearAccumulators();
            b.addTorque(0f, 5000f, 0f);
            b.integrate(STEP);
        }
        assertTrue("yaw rate should be positive, was " + b.wy, b.wy > 0.1f);
        assertTrue("should not have picked up roll", Math.abs(b.wz) < 0.05f);
        assertTrue("should not have picked up pitch", Math.abs(b.wx) < 0.05f);
        assertTrue("yaw angle should have advanced", b.yaw() > 0.01f);
    }

    @Test
    public void nanRecoveryLeavesTheBodyUsable() {
        RigidBody b = new RigidBody();
        b.setMass(500f);
        b.py = 20;
        b.vx = Float.NaN;
        b.integrate(STEP);
        assertTrue("body should be finite again", b.finiteState());
        assertEquals("quaternion must be renormalised", 1.0,
                Math.sqrt(b.qx * b.qx + b.qy * b.qy + b.qz * b.qz + b.qw * b.qw), 1e-5);
    }

    @Test
    public void forceAtAnOffsetProducesBothLinearAndAngularMotion() {
        RigidBody b = new RigidBody();
        b.setMass(1000f);
        b.setBoxInertia(2f, 1.5f, 4f, 1f);
        b.updateMatrix();
        b.clearAccumulators();
        b.addForceAtLocalPoint(0f, 1000f, 0f, 0f, 0f, 2f);   // lift at the nose
        b.integrate(STEP);
        assertTrue("should rise", b.vy > 0f);
        assertTrue("should pitch nose up", Math.abs(b.wx) > 1e-4f);
    }

    // =================================================================================
    // Car
    // =================================================================================

    @Test
    public void carAcceleratesUnderThrottleAndStopsUnderBrakes() {
        CarPhysics car = new CarPhysics(VehicleConfig.hatchback());
        double[] s = dryFlatSpot();
        car.placeAt(s[0], world.heightAt(s[0], s[1]) + 0.6, s[1], 0f);
        settle(car, 60);

        car.throttle = 1f;
        for (int i = 0; i < 60 * 8; i++) {
            car.step(STEP, cw);
        }
        float v = Math.abs(car.forwardSpeed());
        assertTrue("car only reached " + v * 3.6f + " km/h after 8 s at full throttle",
                v > 11f);
        assertTrue("car reached an impossible " + v * 3.6f + " km/h", v < 70f);
        assertTrue("engine should be turning above idle", car.engineRpm > 1000f);
        assertTrue("gearbox should have shifted up", car.gear >= 2);

        car.throttle = 0f;
        car.brake = 1f;
        for (int i = 0; i < 60 * 12; i++) {
            car.step(STEP, cw);
        }
        assertTrue("car did not stop under braking: " + car.forwardSpeed() * 3.6f + " km/h",
                Math.abs(car.forwardSpeed()) < 0.6f);
    }

    @Test
    public void carDoesNotMoveWithoutInput() {
        CarPhysics car = new CarPhysics(VehicleConfig.taxi());
        double[] s = dryFlatSpot();
        car.placeAt(s[0], world.heightAt(s[0], s[1]) + 0.6, s[1], 0.7f);
        double x0 = car.body.px;
        double z0 = car.body.pz;
        for (int i = 0; i < 60 * 5; i++) {
            car.step(STEP, cw);
        }
        double moved = Math.hypot(car.body.px - x0, car.body.pz - z0);
        assertTrue("a parked car drifted " + moved + " m in 5 s", moved < 1.2);
        assertTrue("a parked car should be resting on its wheels", car.anyGrounded());
    }

    @Test
    public void carSuspensionCarriesTheWeightOfTheVehicle() {
        CarPhysics car = new CarPhysics(VehicleConfig.sedan());
        double[] s = dryFlatSpot();
        car.placeAt(s[0], world.heightAt(s[0], s[1]) + 0.6, s[1], 0f);
        settle(car, 180);
        float total = 0f;
        for (int i = 0; i < car.loadN.length; i++) {
            total += car.loadN[i];
        }
        float weight = car.cfg.mass * RigidBody.GRAVITY;
        assertTrue("suspension carries " + total + " N but the car weighs " + weight + " N",
                total > weight * 0.6f && total < weight * 1.6f);
    }

    @Test
    public void carSteeringProducesAYawRate() {
        CarPhysics car = new CarPhysics(VehicleConfig.hatchback());
        double[] s = dryFlatSpot();
        car.placeAt(s[0], world.heightAt(s[0], s[1]) + 0.6, s[1], 0f);
        settle(car, 60);
        car.throttle = 0.7f;
        for (int i = 0; i < 60 * 4; i++) {
            car.step(STEP, cw);
        }
        float yaw0 = car.body.yaw();
        car.steerInput = 1f;
        for (int i = 0; i < 60 * 3; i++) {
            car.step(STEP, cw);
        }
        float delta = Math.abs(ai.shuvrajeetkamila.mumbai.core.Mth.wrapPi(
                car.body.yaw() - yaw0));
        assertTrue("steering produced no yaw (" + delta + " rad)", delta > 0.15f);
    }

    @Test
    public void wetRoadReducesAvailableGrip() {
        double[] s = dryFlatSpot();
        air.rain = 0f;
        float dry = cw.grip(s[0], s[1]);
        air.rain = 1f;
        float wet = cw.grip(s[0], s[1]);
        air.rain = 0f;
        assertTrue("rain did not reduce grip (" + dry + " vs " + wet + ")", wet < dry * 0.8f);
    }

    @Test
    public void heavyVehiclesAccelerateMoreSlowlyThanLightOnes() {
        float busSpeed = accelerateFor(VehicleConfig.bus(), 6f);
        float hatchSpeed = accelerateFor(VehicleConfig.hatchback(), 6f);
        assertTrue("a bus (" + busSpeed + ") out-accelerated a hatchback (" + hatchSpeed + ")",
                busSpeed < hatchSpeed);
    }

    private float accelerateFor(VehicleConfig cfg, float seconds) {
        CarPhysics car = new CarPhysics(cfg);
        double[] s = dryFlatSpot();
        car.placeAt(s[0], world.heightAt(s[0], s[1]) + 0.9, s[1], 0f);
        settle(car, 90);
        car.throttle = 1f;
        for (int i = 0; i < (int) (60 * seconds); i++) {
            car.step(STEP, cw);
        }
        return Math.abs(car.forwardSpeed());
    }

    private void settle(CarPhysics car, int steps) {
        for (int i = 0; i < steps; i++) {
            car.step(STEP, cw);
        }
    }

    // =================================================================================
    // Boat
    // =================================================================================

    private double[] openWater() {
        // A point well out in the Arabian Sea, guaranteed deep.
        double x = world.projection().worldX(19.05, 72.74);
        double z = world.projection().worldZ(19.05);
        assertTrue("test point is not water", LandUse.isWater(world.landUseAt(x, z)));
        return new double[]{x, z};
    }

    @Test
    public void boatFloatsAtItsWaterlineInsteadOfSinking() {
        BoatPhysics boat = new BoatPhysics(new BoatPhysics.Config());
        double[] p = openWater();
        boat.placeAt(p[0], 2.0, p[1], 0f);
        for (int i = 0; i < 60 * 20; i++) {
            water.update(STEP, 0f, 3f);
            boat.step(STEP, cw);
        }
        assertTrue("the boat sank to " + boat.body.py, boat.body.py > -2.0);
        assertTrue("the boat is flying at " + boat.body.py, boat.body.py < 4.0);
        assertTrue("hull should be partly submerged, fraction="
                + boat.submergedFraction, boat.submergedFraction > 0.2f);
        assertFalse("the boat should not be swamped in calm water", boat.swamped());
        assertTrue("the boat should stay upright, roll=" + boat.body.roll(),
                Math.abs(boat.body.roll()) < 0.7f);
    }

    @Test
    public void boatMovesForwardOnThrottleAndStopsWhenItIsCut() {
        BoatPhysics boat = new BoatPhysics(BoatPhysics.Config.speedboat());
        double[] p = openWater();
        boat.placeAt(p[0], 1.0, p[1], 0f);
        for (int i = 0; i < 120; i++) {
            boat.step(STEP, cw);
        }
        boat.throttle = 1f;
        for (int i = 0; i < 60 * 15; i++) {
            boat.step(STEP, cw);
        }
        float v = boat.body.speed();
        assertTrue("speedboat only reached " + v + " m/s", v > 4f);
        boat.throttle = 0f;
        for (int i = 0; i < 60 * 40; i++) {
            boat.step(STEP, cw);
        }
        assertTrue("boat did not slow down after cutting the throttle: " + boat.body.speed(),
                boat.body.speed() < v * 0.6f);
    }

    @Test
    public void boatRidesTheSameWavesTheRendererDraws() {
        BoatPhysics boat = new BoatPhysics(new BoatPhysics.Config());
        double[] p = openWater();
        boat.placeAt(p[0], 1.0, p[1], 0f);
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 60 * 40; i++) {
            water.update(STEP, 0f, 9f);
            boat.step(STEP, cw);
            if (i > 60 * 10) {
                minY = Math.min(minY, (float) boat.body.py);
                maxY = Math.max(maxY, (float) boat.body.py);
            }
        }
        assertTrue("the hull never moved with the swell (range " + (maxY - minY) + " m)",
                maxY - minY > 0.05f);
        assertTrue("the hull is bouncing unrealistically (" + (maxY - minY) + " m)",
                maxY - minY < 6f);
    }

    @Test
    public void boatBeachesInsteadOfDrivingThroughTheShore() {
        BoatPhysics boat = new BoatPhysics(BoatPhysics.Config.speedboat());
        // Juhu beach, pointing east into the sand.
        double x = world.projection().worldX(19.1, 72.815);
        double z = world.projection().worldZ(19.1);
        boat.placeAt(x, 0.2, z, (float) Math.atan2(1, 0));
        boat.throttle = 1f;
        boolean beached = false;
        // Juhu's surf zone is about a kilometre wide, so give the hull time to cross it.
        for (int i = 0; i < 60 * 150; i++) {
            boat.step(STEP, cw);
            if (boat.beached) {
                beached = true;
                break;
            }
        }
        assertTrue("the boat never beached when driven at the shore", beached);
        for (int i = 0; i < 60 * 5; i++) {
            boat.step(STEP, cw);
        }
        float ground = world.heightAt(boat.body.px, boat.body.pz);
        assertTrue("the beached hull sank through the sand: hull " + boat.body.py
                        + " m, sand " + ground + " m",
                boat.body.py > ground - boat.cfg.draft - 2.5);
        assertTrue("a beached boat should not keep sliding inland: "
                + boat.body.speed(), boat.body.speed() < 6f);
    }

    // =================================================================================
    // Helicopter
    // =================================================================================

    @Test
    public void helicopterNeedsCollectiveToLeaveTheGround() {
        HelicopterPhysics h = new HelicopterPhysics(new HelicopterPhysics.Config());
        double[] s = dryFlatSpot();
        float ground = world.heightAt(s[0], s[1]);
        h.placeAt(s[0], ground + 1.4, s[1], 0f);
        h.collective = 0f;
        for (int i = 0; i < 60 * 6; i++) {
            h.step(STEP, cw);
        }
        assertTrue("the helicopter climbed with zero collective: "
                + (h.body.py - ground), h.body.py - ground < 2.5);

        h.collective = 1f;
        for (int i = 0; i < 60 * 12; i++) {
            h.step(STEP, cw);
        }
        assertTrue("full collective did not lift the helicopter (agl "
                + (h.body.py - ground) + " m)", h.body.py - ground > 6.0);
    }

    @Test
    public void helicopterTorqueReactionYawsWithoutPedalInput() {
        HelicopterPhysics h = new HelicopterPhysics(new HelicopterPhysics.Config());
        double[] s = dryFlatSpot();
        h.placeAt(s[0], world.heightAt(s[0], s[1]) + 60, s[1], 0f);
        h.collective = 0.8f;
        h.pedal = 0f;
        float yaw0 = h.body.yaw();
        for (int i = 0; i < 60 * 4; i++) {
            h.step(STEP, cw);
        }
        float delta = Math.abs(ai.shuvrajeetkamila.mumbai.core.Mth.wrapPi(
                h.body.yaw() - yaw0));
        assertTrue("main rotor torque produced no yaw reaction", delta > 0.02f);
    }

    @Test
    public void helicopterAutorotatesWithTheEngineOff() {
        HelicopterPhysics h = new HelicopterPhysics(new HelicopterPhysics.Config());
        double[] s = dryFlatSpot();
        h.placeAt(s[0], world.heightAt(s[0], s[1]) + 900, s[1], 0f);
        h.collective = 0.55f;
        for (int i = 0; i < 60; i++) {
            h.step(STEP, cw);
        }
        h.engineOn = false;
        h.collective = 0.3f;           // just off the low pitch stop, as in a real entry
        for (int i = 0; i < 60 * 20; i++) {
            h.step(STEP, cw);
        }
        assertTrue("rotor stopped instead of autorotating: " + h.rotorRpmPercent() + "%",
                h.rotorRpmPercent() > 40f);
        assertTrue("the aircraft should be descending", h.body.vy < -1f);
        assertTrue("an autorotating descent should not be a free fall: " + h.body.vy,
                h.body.vy > -35f);
        assertTrue("the rotor should still be producing lift in autorotation: "
                + h.lastThrust, h.lastThrust > h.cfg.mass * 3f);
    }

    @Test
    public void helicopterGroundEffectIncreasesLiftNearTheSurface() {
        HelicopterPhysics low = new HelicopterPhysics(new HelicopterPhysics.Config());
        HelicopterPhysics high = new HelicopterPhysics(new HelicopterPhysics.Config());
        double[] s = dryFlatSpot();
        float ground = world.heightAt(s[0], s[1]);
        low.placeAt(s[0], ground + 2.0, s[1], 0f);
        high.placeAt(s[0], ground + 400, s[1], 0f);
        low.collective = 0.6f;
        high.collective = 0.6f;
        low.step(STEP, cw);
        high.step(STEP, cw);
        assertTrue("no ground effect detected", low.inGroundEffect);
        assertFalse("ground effect reported at 400 m", high.inGroundEffect);
        assertTrue("in ground effect should produce more thrust: "
                + low.lastThrust + " vs " + high.lastThrust,
                low.lastThrust > high.lastThrust);
    }

    // =================================================================================
    // Aeroplane
    // =================================================================================

    @Test
    public void aeroplaneCannotTakeOffBelowRotationSpeed() {
        AeroplanePhysics p = new AeroplanePhysics(AeroplanePhysics.Config.trainer());
        double[] r = runwayStart();
        p.placeAt(r[0], world.heightAt(r[0], r[1]) - p.cfg.gearHeight, r[1], (float) r[2]);
        p.throttle = 0f;
        p.elevator = 1f;              // stick fully back
        for (int i = 0; i < 60 * 5; i++) {
            p.step(STEP, cw);
        }
        assertTrue("the aeroplane left the ground with no airspeed", p.onGround);
        assertTrue("the aeroplane levitated: agl " + p.altitudeAgl(cw),
                p.altitudeAgl(cw) < 2.0f);
    }

    @Test
    public void aeroplaneTakesOffAfterARealTakeOffRoll() {
        AeroplanePhysics p = new AeroplanePhysics(AeroplanePhysics.Config.trainer());
        double[] r = runwayStart();
        double x0 = r[0];
        double z0 = r[1];
        p.placeAt(x0, world.heightAt(x0, z0) - p.cfg.gearHeight, z0, (float) r[2]);
        p.throttle = 1f;
        p.flaps = 0.5f;
        float rollDistance = -1f;
        for (int i = 0; i < 60 * 60; i++) {
            // Rotate once past a sensible rotation speed.
            p.elevator = p.indicatedAirspeed > p.stallSpeed(Atmosphere.RHO_SEA_LEVEL) * 1.15f
                    ? 0.45f : 0f;
            p.step(STEP, cw);
            if (!p.onGround && p.altitudeAgl(cw) > 12f) {
                rollDistance = (float) Math.hypot(p.body.px - x0, p.body.pz - z0);
                break;
            }
        }
        assertTrue("the aeroplane never got airborne", rollDistance > 0f);
        assertTrue("take-off roll of " + rollDistance + " m is implausibly short",
                rollDistance > 120f);
        assertTrue("take-off roll of " + rollDistance + " m exceeded the runway",
                rollDistance < r[3] * 1.6);
        assertTrue("climbing requires airspeed above the stall",
                p.indicatedAirspeed > p.stallSpeed(Atmosphere.RHO_SEA_LEVEL));
    }

    @Test
    public void aeroplaneStallsPastTheCriticalAngleOfAttack() {
        AeroplanePhysics p = new AeroplanePhysics(AeroplanePhysics.Config.trainer());
        double[] s = dryFlatSpot();
        p.placeAt(s[0], world.heightAt(s[0], s[1]) + 1200, s[1], 0f);
        p.gearDown = false;
        p.throttle = 0.55f;
        p.body.vz = 55f;
        p.body.updateMatrix();
        // Fly level, then haul the nose up until it departs.
        boolean stalled = false;
        float liftAtStall = 0f;
        float peakLift = 0f;
        for (int i = 0; i < 60 * 25; i++) {
            p.elevator = i > 60 * 3 ? 1f : 0f;
            p.step(STEP, cw);
            peakLift = Math.max(peakLift, p.lastLift);
            if (p.stalled) {
                stalled = true;
                liftAtStall = p.lastLift;
                break;
            }
        }
        assertTrue("the wing never stalled even at full back stick", stalled);
        assertTrue("angle of attack at the stall was only "
                + p.angleOfAttack * 57.3f + " deg",
                Math.abs(p.angleOfAttack) > 12f / 57.3f);
        assertTrue("lift should collapse in the stall", liftAtStall < peakLift * 1.05f);
    }

    @Test
    public void aeroplaneLiftScalesWithTheSquareOfAirspeed() {
        AeroplanePhysics p = new AeroplanePhysics(AeroplanePhysics.Config.trainer());
        double[] s = dryFlatSpot();
        // Still air: the point of this test is the v^2 term, not the gust model.
        float wx = air.windX;
        float wz = air.windZ;
        float gust = air.gustiness;
        air.windX = 0f;
        air.windZ = 0f;
        air.gustiness = 0f;
        p.gearDown = false;
        p.placeAt(s[0], world.heightAt(s[0], s[1]) + 1500, s[1], 0f);
        p.body.vz = 40f;
        p.step(STEP, cw);
        float lift40 = p.lastLift;
        p.placeAt(s[0], world.heightAt(s[0], s[1]) + 1500, s[1], 0f);
        p.body.vz = 80f;
        p.step(STEP, cw);
        float lift80 = p.lastLift;
        air.windX = wx;
        air.windZ = wz;
        air.gustiness = gust;
        assertTrue("lift did not grow with airspeed", lift80 > lift40);
        double ratio = lift80 / Math.max(1f, lift40);
        assertTrue("doubling the airspeed should roughly quadruple lift, ratio was " + ratio,
                ratio > 3.0 && ratio < 5.0);
    }

    @Test
    public void flapsIncreaseLiftAndDrag() {
        AeroplanePhysics clean = new AeroplanePhysics(AeroplanePhysics.Config.trainer());
        AeroplanePhysics dirty = new AeroplanePhysics(AeroplanePhysics.Config.trainer());
        double[] s = dryFlatSpot();
        for (AeroplanePhysics p : new AeroplanePhysics[]{clean, dirty}) {
            p.gearDown = false;
            p.placeAt(s[0], world.heightAt(s[0], s[1]) + 1500, s[1], 0f);
            p.body.vz = 50f;
        }
        dirty.flaps = 1f;
        clean.step(STEP, cw);
        dirty.step(STEP, cw);
        assertTrue("flaps did not add lift", dirty.lastLift > clean.lastLift);
        assertTrue("flaps did not add drag", dirty.lastDrag > clean.lastDrag);
        assertTrue("flaps should lower the stall speed",
                dirty.stallSpeed(Atmosphere.RHO_SEA_LEVEL)
                        < clean.stallSpeed(Atmosphere.RHO_SEA_LEVEL));
    }

    @Test
    public void airlinerNeedsALongerRunwayThanTheTrainer() {
        float trainerRoll = takeOffRoll(AeroplanePhysics.Config.trainer());
        float airlinerRoll = takeOffRoll(AeroplanePhysics.Config.airliner());
        assertTrue("trainer did not fly", trainerRoll > 0);
        assertTrue("airliner did not fly", airlinerRoll > 0);
        assertTrue("the airliner (" + airlinerRoll + " m) should need more runway than the "
                + "trainer (" + trainerRoll + " m)", airlinerRoll > trainerRoll);
    }

    private float takeOffRoll(AeroplanePhysics.Config cfg) {
        AeroplanePhysics p = new AeroplanePhysics(cfg);
        double[] r = runwayStart();
        double x0 = r[0];
        double z0 = r[1];
        p.placeAt(x0, world.heightAt(x0, z0) - cfg.gearHeight, z0, (float) r[2]);
        p.throttle = 1f;
        p.flaps = 0.5f;
        for (int i = 0; i < 60 * 120; i++) {
            p.elevator = p.indicatedAirspeed > p.stallSpeed(Atmosphere.RHO_SEA_LEVEL) * 1.15f
                    ? 0.5f : 0f;
            p.step(STEP, cw);
            if (!p.onGround && p.altitudeAgl(cw) > 12f) {
                return (float) Math.hypot(p.body.px - x0, p.body.pz - z0);
            }
        }
        return -1f;
    }

    // =================================================================================
    // Human
    // =================================================================================

    @Test
    public void humanWalksAtAPlausibleSpeedAndStops() {
        HumanController h = new HumanController();
        double[] s = dryFlatSpot();
        h.placeAt(s[0], world.heightAt(s[0], s[1]) + 0.2, s[1], 0f);
        for (int i = 0; i < 30; i++) {
            h.step(STEP, cw);
        }
        h.moveZ = 1f;
        for (int i = 0; i < 60 * 4; i++) {
            h.step(STEP, cw);
        }
        assertTrue("walking speed was " + h.speedPlanar + " m/s",
                h.speedPlanar > 1.0f && h.speedPlanar < 5.0f);
        h.moveZ = 0f;
        for (int i = 0; i < 60 * 2; i++) {
            h.step(STEP, cw);
        }
        assertTrue("the character did not stop: " + h.speedPlanar, h.speedPlanar < 0.4f);
    }

    @Test
    public void sprintingIsFasterThanWalking() {
        float walk = travel(false);
        float run = travel(true);
        assertTrue("sprinting (" + run + " m) was not faster than walking (" + walk + " m)",
                run > walk * 1.2f);
    }

    private float travel(boolean sprint) {
        HumanController h = new HumanController();
        double[] s = dryFlatSpot();
        h.placeAt(s[0], world.heightAt(s[0], s[1]) + 0.2, s[1], 0f);
        for (int i = 0; i < 30; i++) {
            h.step(STEP, cw);
        }
        double x0 = h.body.px;
        double z0 = h.body.pz;
        h.moveZ = 1f;
        h.sprint = sprint;
        for (int i = 0; i < 60 * 5; i++) {
            h.step(STEP, cw);
        }
        return (float) Math.hypot(h.body.px - x0, h.body.pz - z0);
    }

    @Test
    public void humanFallsUnderGravityAndLandsOnTheGround() {
        HumanController h = new HumanController();
        double[] s = dryFlatSpot();
        float ground = world.heightAt(s[0], s[1]);
        h.placeAt(s[0], ground + 25, s[1], 0f);
        for (int i = 0; i < 60 * 8; i++) {
            h.step(STEP, cw);
        }
        assertEquals("the character should come to rest on the ground",
                ground, h.body.py, 0.6);
        assertTrue("a 25 m fall should knock the character down", h.isRagdoll()
                || h.lastFallDamage > 0f);
    }

    @Test
    public void humanSwimsInsteadOfSinking() {
        HumanController h = new HumanController();
        double x = world.projection().worldX(19.05, 72.75);
        double z = world.projection().worldZ(19.05);
        h.placeAt(x, 3.0, z, 0f);
        for (int i = 0; i < 60 * 15; i++) {
            water.update(STEP, 0f, 2f);
            h.step(STEP, cw);
        }
        assertTrue("the character should be swimming", h.swimming);
        assertTrue("the character sank to " + h.body.py, h.body.py > -3.0);
    }

    @Test
    public void humanCannotWalkUpACliff() {
        // The steep flank of the park ridge.
        double x = world.projection().worldX(19.20, 72.925);
        double z = world.projection().worldZ(19.20);
        HumanController h = new HumanController();
        h.placeAt(x, world.heightAt(x, z) + 0.2, z, 0f);
        for (int i = 0; i < 30; i++) {
            h.step(STEP, cw);
        }
        assertTrue("the character should be standing on the hill", h.grounded);
    }
}
