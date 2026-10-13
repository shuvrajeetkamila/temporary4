package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.world.CraftSpawns;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walking up to a boat, a helicopter or an aeroplane and getting in.
 *
 * <p>Until this existed the only route into the boat, helicopter and
 * aeroplane models was a debug button that swapped the player's body in
 * place, which meant the flight models were shipped but unreachable. These
 * tests are about the reachability, not the flying: that is what
 * AircraftPhysicsTest is for.
 */
final class BoardingTest {

    private static Engine engineAt(CraftSpawns.Spawn s, double offsetM) {
        WorldData w = WorldFixture.world();
        w.craft().reset();
        Engine e = new Engine(w);
        // Stand just off the mooring, looking at it.
        e.human.position.set(s.x - offsetM, 0, s.z);
        e.human.position.y = w.inBounds(s.x - offsetM, s.z)
                ? w.height(s.x - offsetM, s.z) + e.human.height * 0.5 : 2;
        e.cameraYaw = Math.PI / 2;   // +X is east; heading = atan2(x, -z)
        e.profile = ControlProfile.ON_FOOT;
        return e;
    }

    private static CraftSpawns.Spawn firstOf(int kind) {
        CraftSpawns c = WorldFixture.world().craft();
        c.reset();
        for (CraftSpawns.Spawn s : c.all()) {
            if (s.kind == kind) { return s; }
        }
        return null;
    }

    @Test
    @DisplayName("standing beside a moored boat and pressing enter sails it")
    void boardingABoatGivesTheBoatProfile() {
        CraftSpawns.Spawn s = firstOf(CraftSpawns.BOAT);
        assertNotNull(s);
        Engine e = engineAt(s, 3.0);
        assertTrue(e.tryEnterVehicle(), "could not board a boat 3 m away");
        assertEquals(ControlProfile.BOAT, e.profile);
        assertTrue(s.taken, "the boat was not marked as taken");
        assertEquals(s.x, e.boat.position.x, 0.5);
        assertEquals(s.z, e.boat.position.z, 0.5);
    }

    @Test
    @DisplayName("boarding a helicopter puts you in the helicopter")
    void boardingAHelicopter() {
        CraftSpawns.Spawn s = firstOf(CraftSpawns.HELI);
        assertNotNull(s);
        Engine e = engineAt(s, 3.0);
        assertTrue(e.tryEnterVehicle());
        assertEquals(ControlProfile.HELICOPTER, e.profile);
        // On its skids, not buried and not floating.
        double agl = e.heli.position.y - e.world.height(s.x, s.z);
        assertTrue(agl > 0.5 && agl < 3.0, "skids at " + agl + " m");
        assertTrue(e.heli.rotorRpm > 0, "rotor stopped on boarding");
    }

    @Test
    @DisplayName("boarding an aeroplane lines it up with its runway")
    void boardingAnAeroplaneKeepsTheRunwayHeading() {
        CraftSpawns.Spawn s = firstOf(CraftSpawns.PLANE);
        assertNotNull(s);
        Engine e = engineAt(s, 3.0);
        assertTrue(e.tryEnterVehicle());
        assertEquals(ControlProfile.AEROPLANE, e.profile);
        double got = e.plane.orientation.heading();
        double d = Math.abs(Math.atan2(Math.sin(got - s.heading),
                Math.cos(got - s.heading)));
        assertTrue(d < 0.05,
                "parked across the runway: " + Math.toDegrees(d) + " deg off");
        assertTrue(e.plane.gearDown, "gear retracted on the ground");
    }

    @Test
    @DisplayName("a craft out of reach is not boarded")
    void outOfReachIsNotBoarded() {
        CraftSpawns.Spawn s = firstOf(CraftSpawns.BOAT);
        assertNotNull(s);
        Engine e = engineAt(s, 60.0);
        assertFalse(e.tryEnterVehicle(), "boarded a boat 60 m away");
        assertEquals(ControlProfile.ON_FOOT, e.profile);
        assertFalse(s.taken);
    }

    @Test
    @DisplayName("the prompt names what is about to be boarded")
    void thePromptNamesTheCraft() {
        CraftSpawns.Spawn s = firstOf(CraftSpawns.HELI);
        assertNotNull(s);
        Engine e = engineAt(s, 3.0);
        String label = e.nearestEnterableLabel();
        assertNotNull(label, "no prompt beside a helicopter");
        assertTrue(label.startsWith("Helicopter"), label);
        assertTrue(e.nearestEnterableDistance() <= Engine.ENTER_RADIUS_M,
                "prompt distance " + e.nearestEnterableDistance());
    }

    @Test
    @DisplayName("you cannot step out of a helicopter in the air")
    void noSteppingOutInFlight() {
        CraftSpawns.Spawn s = firstOf(CraftSpawns.HELI);
        assertNotNull(s);
        Engine e = engineAt(s, 3.0);
        assertTrue(e.tryEnterVehicle());
        e.heli.position.y += 180;
        assertFalse(e.exitVehicle(), "got out of a helicopter at 180 m");
        assertEquals(ControlProfile.HELICOPTER, e.profile);
    }

    @Test
    @DisplayName("you cannot step out of a boat doing fifteen knots")
    void noSteppingOutAtSpeed() {
        CraftSpawns.Spawn s = firstOf(CraftSpawns.BOAT);
        assertNotNull(s);
        Engine e = engineAt(s, 3.0);
        assertTrue(e.tryEnterVehicle());
        e.boat.velocity.set(0, 0, 8.0);
        assertFalse(e.exitVehicle());
        assertEquals(ControlProfile.BOAT, e.profile);
    }

    @Test
    @DisplayName("stepping off a boat puts you on dry land")
    void steppingOffABoatLandsOnLand() {
        CraftSpawns.Spawn s = firstOf(CraftSpawns.BOAT);
        assertNotNull(s);
        Engine e = engineAt(s, 3.0);
        assertTrue(e.tryEnterVehicle());
        assertTrue(e.exitVehicle(), "could not step off at the mooring");
        assertEquals(ControlProfile.ON_FOOT, e.profile);
        assertTrue(e.world.isLand(e.human.position.x, e.human.position.z),
                "stepped off into the sea");
        assertFalse(s.taken, "the boat was not released");
    }

    @Test
    @DisplayName("a craft stays where it was left, not where it was moored")
    void aCraftStaysWhereItWasLeft() {
        CraftSpawns.Spawn s = firstOf(CraftSpawns.HELI);
        assertNotNull(s);
        double homeX = s.homeX, homeZ = s.homeZ;
        Engine e = engineAt(s, 3.0);
        assertTrue(e.tryEnterVehicle());
        // Fly it a short way and set it down on the ground again.
        double nx = homeX + 140, nz = homeZ + 90;
        e.heli.position.set(nx, e.world.height(nx, nz) + 1.35, nz);
        e.heli.velocity.set(0, 0, 0);
        assertTrue(e.exitVehicle(), "could not get out after landing");
        assertEquals(nx, s.x, 1.0);
        assertEquals(nz, s.z, 1.0);
        assertTrue(Math.hypot(s.x - homeX, s.z - homeZ) > 100,
                "the helicopter teleported back to its pad");
        WorldFixture.world().craft().reset();
    }

    @Test
    @DisplayName("getting out and back in again works")
    void boardLeaveBoardAgain() {
        CraftSpawns.Spawn s = firstOf(CraftSpawns.BOAT);
        assertNotNull(s);
        Engine e = engineAt(s, 3.0);
        assertTrue(e.tryEnterVehicle());
        assertTrue(e.exitVehicle());
        assertEquals(ControlProfile.ON_FOOT, e.profile);
        // The player is now standing on the shore beside her.
        assertTrue(e.nearestEnterableDistance() < 20,
                "the boat vanished after stepping off");
        e.cameraYaw = Math.atan2(s.x - e.human.position.x,
                -(s.z - e.human.position.z));
        assertTrue(e.tryEnterVehicle(), "could not get back aboard");
        assertEquals(ControlProfile.BOAT, e.profile);
        WorldFixture.world().craft().reset();
    }

    @Test
    @DisplayName("a craft nearby beats a car further away")
    void theNearerThingWins() {
        CraftSpawns.Spawn s = firstOf(CraftSpawns.HELI);
        assertNotNull(s);
        Engine e = engineAt(s, 2.0);
        // Whatever traffic happens to be around, the helicopter is two
        // metres away and dead ahead.
        assertTrue(e.tryEnterVehicle());
        assertEquals(ControlProfile.HELICOPTER, e.profile);
    }

    @Test
    @DisplayName("boarding does not leave the camera pointing backwards")
    void boardingFacesTheWayTheCraftFaces() {
        CraftSpawns.Spawn s = firstOf(CraftSpawns.PLANE);
        assertNotNull(s);
        Engine e = engineAt(s, 3.0);
        assertTrue(e.tryEnterVehicle());
        double d = Math.abs(Math.atan2(Math.sin(e.cameraYaw - s.heading),
                Math.cos(e.cameraYaw - s.heading)));
        assertTrue(d < 0.05, "camera " + Math.toDegrees(d) + " deg off the nose");
    }

    @Test
    @DisplayName("every moored craft in the world can be boarded from beside it")
    void allOfThemCanBeBoarded() {
        WorldData w = WorldFixture.world();
        CraftSpawns c = w.craft();
        c.reset();
        int failed = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.size(); i++) {
            CraftSpawns.Spawn s = c.get(i);
            Engine e = engineAt(s, 2.5);
            if (!e.tryEnterVehicle()) {
                failed++;
                sb.append(' ').append(s.label()).append('@')
                        .append((int) s.x).append(',').append((int) s.z);
            }
            c.reset();
        }
        assertEquals(0, failed, failed + " craft could not be boarded:" + sb);
    }
}
