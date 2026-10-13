package ai.shuvrajeetkamila.mumbai.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.game.MapView;
import ai.shuvrajeetkamila.mumbai.geo.GeoProjection;
import ai.shuvrajeetkamila.mumbai.physics.RigidBody;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Teleporting from the full-screen map, end to end.
 *
 * <p>A teleport touches more than a position. It has to land on the
 * ground at the far end, kill the velocity it arrived with, reset the
 * driveline of whatever is being driven, and rebase the floating origin
 * - which is the whole reason a 52 by 72 km world can be rendered in
 * single precision at all. Any one of those being missed produces a bug
 * that only shows up 40 km from the origin.
 */
final class TeleportTest {

    private static WorldData world() {
        Assumptions.assumeTrue(WorldFixture.present(), "compiled world absent");
        return WorldFixture.world();
    }

    private static Engine onFoot() {
        Engine e = new Engine(world());
        e.spawnAt(18.9430, 72.8230, ControlProfile.ON_FOOT);   // Marine Drive
        return e;
    }

    @Test
    @DisplayName("teleporting puts the player on the ground at the far end")
    void landsOnTheGround() {
        Engine e = onFoot();
        WorldData w = e.world;
        // Somewhere a long way north: Borivali rather than Colaba.
        double tx = GeoProjection
                .toX(19.2307, 72.8567);
        double tz = GeoProjection.toZ(19.2307);
        Assumptions.assumeTrue(w.inBounds(tx, tz), "target out of bounds");
        Assumptions.assumeFalse(w.isWater(tx, tz), "target is in the sea");

        assertTrue(e.teleportTo(tx, tz), "the teleport was refused");
        RigidBody b = e.activeBody();
        double ground = w.height(tx, tz);
        assertTrue(Math.abs(b.position.x - tx) < 1e-6, "x did not arrive");
        assertTrue(Math.abs(b.position.z - tz) < 1e-6, "z did not arrive");
        assertTrue(b.position.y > ground - 0.1 && b.position.y < ground + 4,
                "arrived at y=" + b.position.y + " with the ground at "
                        + ground);
    }

    @Test
    @DisplayName("you do not arrive carrying the speed you left with")
    void velocityIsCleared() {
        Engine e = onFoot();
        WorldData w = e.world;
        RigidBody b = e.activeBody();
        b.velocity.set(18, -4, 33);
        b.angularVelocity.set(1.2, -0.8, 2.1);

        double tx = GeoProjection
                .toX(19.1136, 72.8697);                        // Dharavi-ish
        double tz = GeoProjection.toZ(19.1136);
        Assumptions.assumeTrue(w.inBounds(tx, tz), "target out of bounds");
        assertTrue(e.teleportTo(tx, tz));

        RigidBody after = e.activeBody();
        assertTrue(after.velocity.length() < 1e-9,
                "arrived at " + after.velocity.length() + " m/s");
        assertTrue(after.angularVelocity.length() < 1e-9,
                "arrived spinning at " + after.angularVelocity.length());
    }

    @Test
    @DisplayName("a long teleport rebases the floating origin")
    void originRebases() {
        // This is the case the floating origin exists for. Crossing 40 km
        // in one step without rebasing leaves the render path handling
        // coordinates it cannot hold in a float.
        Engine e = onFoot();
        WorldData w = e.world;
        int before = e.origin.rebaseCount();
        double southZ = GeoProjection
                .toZ(18.9100);
        double northZ = GeoProjection
                .toZ(19.4300);
        double x = GeoProjection
                .toX(19.4300, 72.8100);
        Assumptions.assumeTrue(w.inBounds(x, northZ), "north target missing");
        assertTrue(Math.abs(northZ - southZ) > 30000,
                "the test is not actually jumping far");

        assertTrue(e.teleportTo(x, northZ));
        assertTrue(e.origin.rebaseCount() > before,
                "a 50 km jump did not rebase the origin");
    }

    @Test
    @DisplayName("a teleport outside the world is refused")
    void outOfBoundsIsRefused() {
        Engine e = onFoot();
        assertFalse(e.teleportTo(1e7, 1e7),
                "a destination outside the world was accepted");
    }

    @Test
    @DisplayName("the map opens centred on the player and closes on arrival")
    void mapLifecycle() {
        Engine e = onFoot();
        RigidBody b = e.activeBody();
        assertFalse(e.mapView.open);
        e.toggleMap();
        assertTrue(e.mapView.open, "the map did not open");
        assertTrue(Math.abs(e.mapView.centreX - b.position.x) < 1,
                "the map did not open on the player");
        assertTrue(Math.abs(e.mapView.centreZ - b.position.z) < 1);
        assertTrue(Math.abs(e.mapView.spanM - MapView.DEFAULT_SPAN_M) < 1e-9,
                "the map did not open at the default zoom");
        e.toggleMap();
        assertFalse(e.mapView.open, "the map did not close");
    }

    @Test
    @DisplayName("the world does not advance while the map is up")
    void theMapPausesTheWorld() {
        Engine e = onFoot();
        InputState in = new InputState();
        // Fall for a moment so there is some motion to freeze.
        for (int i = 0; i < 30; i++) { e.frame(1.0 / 60, in); }
        in.map = true;
        e.frame(1.0 / 60, in);
        assertTrue(e.mapView.open, "the map edge did not open it");

        RigidBody b = e.activeBody();
        double x = b.position.x;
        double y = b.position.y;
        double z = b.position.z;
        for (int i = 0; i < 120; i++) { e.frame(1.0 / 60, in); }
        assertTrue(Math.abs(b.position.x - x) < 1e-12
                        && Math.abs(b.position.y - y) < 1e-12
                        && Math.abs(b.position.z - z) < 1e-12,
                "the world kept running while the map was open");
    }

    @Test
    @DisplayName("confirming on the map teleports, and only where it can")
    void confirmTeleports() {
        Engine e = onFoot();
        WorldData w = e.world;
        InputState in = new InputState();
        e.toggleMap();

        double tx = GeoProjection
                .toX(19.0176, 72.8561);                        // Dadar-ish
        double tz = GeoProjection.toZ(19.0176);
        Assumptions.assumeTrue(w.inBounds(tx, tz), "target out of bounds");
        Assumptions.assumeTrue(e.mapView.pickWorld(tx, tz, e.minimap, w),
                "the pick found nothing");
        Assumptions.assumeFalse(e.mapView.targetInWater, "picked water");

        double targetX = e.mapView.targetX;
        double targetZ = e.mapView.targetZ;
        in.mapConfirm = true;
        e.frame(1.0 / 60, in);

        RigidBody b = e.activeBody();
        assertTrue(Math.hypot(b.position.x - targetX, b.position.z - targetZ)
                        < 1.0,
                "confirming did not move the player to the marker");
        assertFalse(e.mapView.open,
                "the map stayed open after the teleport");
        assertFalse(in.mapConfirm, "the confirm edge was not consumed");
    }

    @Test
    @DisplayName("a car's driveline is reset rather than carried across")
    void carArrivesInAKnownState() {
        Engine e = new Engine(world());
        e.spawnAt(19.0760, 72.8777, ControlProfile.CAR);
        WorldData w = e.world;
        InputState in = new InputState();
        in.throttle = 1;
        for (int i = 0; i < 240; i++) { e.frame(1.0 / 60, in); }
        Assumptions.assumeTrue(Math.abs(e.car.wheels[2].spin) > 1,
                "the car never got moving, so there is nothing to carry");

        double tx = GeoProjection
                .toX(19.1197, 72.8464);
        double tz = GeoProjection.toZ(19.1197);
        Assumptions.assumeTrue(w.inBounds(tx, tz), "target out of bounds");
        assertTrue(e.teleportTo(tx, tz));

        for (ai.shuvrajeetkamila.mumbai.physics.CarPhysics.Wheel wh
                : e.car.wheels) {
            assertTrue(Math.abs(wh.spin) < 1e-9,
                    "a wheel arrived still turning at " + wh.spin);
            assertTrue(Math.abs(wh.slipRatio) < 1e-9,
                    "a wheel arrived mid-slip");
        }
        assertTrue(e.car.gear == 1, "the car did not arrive in neutral");
    }
}
