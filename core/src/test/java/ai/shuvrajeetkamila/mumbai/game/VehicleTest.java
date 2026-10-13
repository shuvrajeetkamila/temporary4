package ai.shuvrajeetkamila.mumbai.game;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.physics.CarPhysics;
import ai.shuvrajeetkamila.mumbai.sim.Agents;
import ai.shuvrajeetkamila.mumbai.sim.Engine;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The traffic mix, the per-vehicle handling, and getting in and out. */
final class VehicleTest {

    @Test
    @DisplayName("the traffic mix is dominated by two- and three-wheelers")
    void trafficMixIsPlausible() {
        int[] counts = new int[VehicleKind.values().length];
        int n = 20000;
        for (int i = 0; i < n; i++) {
            VehicleKind k = VehicleKind.sample(i / (double) n, 0);
            counts[k.ordinal()]++;
        }
        double scooters = counts[VehicleKind.SCOOTER.ordinal()] / (double) n;
        double autos = counts[VehicleKind.AUTORICKSHAW.ordinal()] / (double) n;
        double buses = (counts[VehicleKind.CITY_BUS.ordinal()]
                + counts[VehicleKind.DOUBLE_DECKER.ordinal()]) / (double) n;

        assertTrue(scooters + autos > 0.40,
                "two and three wheelers should dominate, got " + (scooters + autos));
        assertTrue(buses < 0.08, "buses should be a minority, got " + buses);
        assertEquals(0, counts[VehicleKind.POLICE.ordinal()],
                "police are not ambient traffic");
    }

    /** Autorickshaws are not licensed for the southern tip of the island. */
    @Test
    @DisplayName("no autorickshaw is generated south of Mahim")
    void autosAreNotLicensedInTheSouth() {
        for (int i = 0; i < 5000; i++) {
            VehicleKind k = VehicleKind.sample(i / 5000.0, 20000);
            assertTrue(k != VehicleKind.AUTORICKSHAW,
                    "an autorickshaw was generated south of Mahim");
        }
        boolean sawOne = false;
        for (int i = 0; i < 5000 && !sawOne; i++) {
            sawOne = VehicleKind.sample(i / 5000.0, 0) == VehicleKind.AUTORICKSHAW;
        }
        assertTrue(sawOne, "autorickshaws should appear in the north");
    }

    @Test
    @DisplayName("every vehicle has physically sane numbers")
    void dimensionsAndInertiasAreSane() {
        for (VehicleKind k : VehicleKind.values()) {
            assertTrue(k.massKg > 50 && k.massKg < 30000, k + " mass " + k.massKg);
            assertTrue(k.wheelbaseM < k.lengthM,
                    k + " wheelbase exceeds its length");
            assertTrue(k.comHeightM < k.heightM * 0.6,
                    k + " centre of mass is implausibly high");
            assertTrue(k.yawInertia() > k.rollInertia(),
                    k + " should resist yaw more than roll");
            assertTrue(k.peakTorqueNm > 5 && k.peakTorqueNm < 2000,
                    k + " torque " + k.peakTorqueNm);
        }
    }

    /**
     * A bus must not handle like a hatchback. This checks the thing that
     * actually produces that difference - mass, inertia and centre-of-mass
     * height reaching the solver - rather than checking a handling constant.
     */
    @Test
    @DisplayName("configuring a vehicle changes what the solver sees")
    void configureReachesThePhysics() {
        CarPhysics c = new CarPhysics();
        c.configure(VehicleKind.SCOOTER);
        double scooterMass = c.mass;
        double scooterYaw = c.inertia.y;
        assertEquals(VehicleKind.SCOOTER, c.kind);

        c.configure(VehicleKind.DOUBLE_DECKER);
        assertTrue(c.mass > scooterMass * 50,
                "a double-decker should be far heavier than a scooter");
        assertTrue(c.inertia.y > scooterYaw * 100,
                "a double-decker should resist turning far more");
        assertTrue(c.wheelbase > 4.0, "bus wheelbase " + c.wheelbase);

        // Gearbox: reverse, neutral, then exactly the declared gear count,
        // with the ratios falling as the index rises. A loop that walks the
        // gears depends on that ordering and silently picks nothing if it
        // is ever reversed.
        c.configure(VehicleKind.SEDAN);
        assertEquals(VehicleKind.SEDAN.gearCount + 2, c.gearRatios.length);
        assertTrue(c.gearRatios[0] < 0, "slot 0 should be reverse");
        assertEquals(0.0, c.gearRatios[1], 1e-9, "slot 1 should be neutral");
        for (int g = 3; g < c.gearRatios.length; g++) {
            assertTrue(c.gearRatios[g] < c.gearRatios[g - 1],
                    "ratios must fall as the gear rises, broke at " + g);
        }
    }

    @Test
    @DisplayName("a three-wheeler is laid out with one wheel at the front")
    void threeWheelersAreLaidOutCorrectly() {
        CarPhysics c = new CarPhysics();
        c.configure(VehicleKind.AUTORICKSHAW);
        assertEquals(0.0, c.wheels[0].attach.x, 1e-9,
                "front wheel should sit on the centreline");
        assertEquals(0.0, c.wheels[1].attach.x, 1e-9);
        assertTrue(c.wheels[2].attach.x != 0 && c.wheels[3].attach.x != 0,
                "rear wheels should be on a track");
    }

    // ------------------------------------------------------------------ world

    @Test
    @DisplayName("the player can get into a vehicle and out again")
    void enterAndExitAVehicle() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        InputState in = new InputState();

        // Put a parked, enterable vehicle right next to the player.
        double px = e.human.position.x;
        double pz = e.human.position.z;
        Agents.Car[] cars = e.agents.cars();
        Agents.Car target = cars[0];
        target.active = true;
        target.parked = true;
        target.kindOrdinal = VehicleKind.KAALI_PEELI.ordinal();
        target.x = px + 2.0;
        target.z = pz;
        target.y = w.inBounds(target.x, target.z) ? w.height(target.x, target.z) : 0;
        target.heading = 0;
        target.speed = 0;

        assertEquals(ControlProfile.ON_FOOT, e.profile);
        assertTrue(e.nearestEnterableDistance() < Engine.ENTER_RADIUS_M,
                "the taxi should be within reach");

        assertTrue(e.tryEnterVehicle(), "should have got into the taxi");
        assertEquals(ControlProfile.CAR, e.profile);
        assertEquals(VehicleKind.KAALI_PEELI, e.drivingKind());
        assertEquals(VehicleKind.KAALI_PEELI.massKg, e.car.mass, 1e-6,
                "the physics should now be solving a taxi");
        assertTrue(!target.active, "the agent should have left traffic");

        // And back out. The player must end up beside the car, not inside it
        // and not underground.
        assertTrue(e.exitVehicle(), "should have got out again");
        assertEquals(ControlProfile.ON_FOOT, e.profile);
        double dx = e.human.position.x - e.car.position.x;
        double dz = e.human.position.z - e.car.position.z;
        double gap = Math.sqrt(dx * dx + dz * dz);
        assertTrue(gap > 0.6 && gap < 3.0,
                "player should step out beside the car, gap was " + gap);
        double ground = w.height(e.human.position.x, e.human.position.z);
        assertTrue(e.human.position.y > ground - 0.1,
                "player ended up below the ground on exit");
    }

    @Test
    @DisplayName("getting out at speed is refused")
    void cannotStepOutOfAMovingCar() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.profile = ControlProfile.CAR;
        e.car.configure(VehicleKind.SEDAN);
        e.car.velocity.set(0, 0, -22.0);          // about 80 km/h
        assertTrue(!e.exitVehicle(), "should refuse to exit at 80 km/h");
        assertEquals(ControlProfile.CAR, e.profile);

        e.car.velocity.set(0, 0, -1.0);
        assertTrue(e.exitVehicle(), "should allow exit at walking pace");
    }

    @Test
    @DisplayName("nothing enterable nearby means nothing happens")
    void enteringWithNoVehicleNearbyIsANoOp() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.agents.clear();
        assertTrue(!e.tryEnterVehicle(), "there was nothing to get into");
        assertEquals(ControlProfile.ON_FOOT, e.profile);
    }

    /**
     * Taking a car that is already moving should not stop it dead. The
     * handover reads the agent's speed into the rigid body, and this is
     * what checks that it arrives.
     */
    @Test
    @DisplayName("taking a moving vehicle inherits its speed")
    void enteringAMovingVehicleKeepsItsMomentum() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        Agents.Car[] cars = e.agents.cars();
        Agents.Car c = cars[0];
        c.active = true;
        c.kindOrdinal = VehicleKind.SEDAN.ordinal();
        c.x = e.human.position.x + 1.5;
        c.z = e.human.position.z;
        c.y = w.inBounds(c.x, c.z) ? w.height(c.x, c.z) : 0;
        c.heading = 0;
        c.speed = 12.0;

        assertTrue(e.tryEnterVehicle());
        assertTrue(e.car.groundSpeed() > 10.0,
                "speed should carry over, got " + e.car.groundSpeed());
    }

    /** The HUD prompt must only appear when the button would actually work. */
    @Test
    @DisplayName("the prompt matches whether the button would do anything")
    void promptTracksReachability() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        InputState in = new InputState();
        e.agents.clear();
        for (int i = 0; i < 20; i++) { e.frame(1 / 60.0, in); }
        assertEquals("", e.telemetry.prompt, "no vehicle, so no prompt");

        Agents.Car c = e.agents.cars()[0];
        c.active = true;
        c.parked = true;
        c.kindOrdinal = VehicleKind.HATCHBACK.ordinal();
        c.x = e.human.position.x + 1.2;
        c.z = e.human.position.z;
        c.y = w.inBounds(c.x, c.z) ? w.height(c.x, c.z) : 0;
        for (int i = 0; i < 20; i++) { e.frame(1 / 60.0, in); }
        assertNotNull(e.telemetry.prompt);
        assertTrue(e.telemetry.prompt.contains("ENTER"),
                "expected an enter prompt, got '" + e.telemetry.prompt + "'");
    }

    @Test
    @DisplayName("the enter button drives the whole loop through frame()")
    void buttonDrivesTheLoop() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        InputState in = new InputState();

        Agents.Car c = e.agents.cars()[0];
        c.active = true;
        c.parked = true;
        c.kindOrdinal = VehicleKind.AUTORICKSHAW.ordinal();
        c.x = e.human.position.x + 1.4;
        c.z = e.human.position.z;
        c.y = w.inBounds(c.x, c.z) ? w.height(c.x, c.z) : 0;
        c.heading = 0.6;
        c.speed = 0;

        in.enterExit = true;
        e.frame(1 / 60.0, in);
        assertEquals(ControlProfile.CAR, e.profile,
                "pressing enter should have put the player in the auto");
        assertEquals(VehicleKind.AUTORICKSHAW, e.drivingKind());

        // The edge must be consumed, or the next frame immediately exits.
        e.frame(1 / 60.0, in);
        assertEquals(ControlProfile.CAR, e.profile,
                "the button edge was not consumed and the player fell out");
    }
}
