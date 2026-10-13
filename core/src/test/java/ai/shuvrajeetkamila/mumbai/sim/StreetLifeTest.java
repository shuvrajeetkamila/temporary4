package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether there is actually a city around the player.
 *
 * <p>Traffic used to be spread over the chunk-streaming radius, which is a
 * kilometre and three quarters at full detail. A hundred and forty cars
 * over nine square kilometres is fourteen per square kilometre, so the
 * expected number within a block of the player was <em>less than one</em>:
 * standing on Marine Drive at midday there was no traffic in sight, and the
 * nearest thing you could get into was two hundred metres away. Nothing in
 * the test suite noticed, because every traffic test placed its own cars.
 *
 * <p>Worse, nothing was ever parked. The first move of this kind of game is
 * to walk up to a car and get in, and the only cars in the world were
 * moving ones.
 */
final class StreetLifeTest {

    /** Marine Drive, where the game starts. */
    private static final double SPAWN_LAT = 18.9430;
    private static final double SPAWN_LON = 72.8230;

    private static Engine spawned() {
        Engine e = new Engine(WorldFixture.world());
        e.spawnAt(SPAWN_LAT, SPAWN_LON, ControlProfile.ON_FOOT);
        InputState in = new InputState();
        // Two seconds of standing still is enough for the spawner to fill.
        for (int i = 0; i < 120; i++) { e.frame(1.0 / 60, in); }
        return e;
    }

    private static int carsWithin(Engine e, double r) {
        int n = 0;
        for (Agents.Car c : e.agents.cars()) {
            if (c.active && Math.hypot(c.x - e.human.position.x,
                    c.z - e.human.position.z) < r) { n++; }
        }
        return n;
    }

    @Test
    @DisplayName("there is traffic on the street you are standing in")
    void thereIsTrafficAroundThePlayer() {
        Engine e = spawned();
        int near = carsWithin(e, 120);
        assertTrue(near >= 5,
                "only " + near + " cars within 120 m of the spawn, out of "
                        + e.agents.activeCars() + " in the world");
    }

    @Test
    @DisplayName("and people on the pavement")
    void thereArePedestriansAroundThePlayer() {
        Engine e = spawned();
        int near = 0;
        for (Agents.Ped p : e.agents.peds()) {
            if (p.active && Math.hypot(p.x - e.human.position.x,
                    p.z - e.human.position.z) < 120) { near++; }
        }
        assertTrue(near >= 8, "only " + near + " pedestrians within 120 m");
    }

    @Test
    @DisplayName("the car budget is spent near the player, not thrown at the horizon")
    void theBudgetIsSpentWhereItCanBeSeen() {
        Engine e = spawned();
        int all = e.agents.activeCars();
        int near = carsWithin(e, Engine.AGENT_SPAWN_M + 40);
        assertTrue(all > 10, "only " + all + " cars active at all");
        assertTrue(near > all * 0.75,
                near + " of " + all + " cars are inside the spawn radius");
    }

    @Test
    @DisplayName("something is parked within a short walk")
    void thereIsSomethingParkedNearby() {
        Engine e = spawned();
        double best = Double.MAX_VALUE;
        for (Agents.Car c : e.agents.cars()) {
            if (!c.active || !c.parked) { continue; }
            if (!VehicleKind.values()[c.kindOrdinal].enterable) { continue; }
            best = Math.min(best, Math.hypot(c.x - e.human.position.x,
                    c.z - e.human.position.z));
        }
        assertTrue(best < 90,
                "the nearest parked car you could get into is "
                        + (best > 1e9 ? "nowhere" : String.format("%.0f m", best))
                        + " away");
    }

    @Test
    @DisplayName("a parked car stays parked")
    void parkedCarsDoNotDriveOff() {
        Engine e = spawned();
        Agents.Car parked = null;
        for (Agents.Car c : e.agents.cars()) {
            if (c.active && c.parked) { parked = c; break; }
        }
        assertNotNull(parked, "nothing is parked anywhere");
        double x = parked.x;
        double z = parked.z;
        InputState in = new InputState();
        for (int i = 0; i < 60 * 8; i++) { e.frame(1.0 / 60, in); }
        if (parked.active && parked.parked) {
            assertEquals(x, parked.x, 0.01, "a parked car drifted");
            assertEquals(z, parked.z, 0.01, "a parked car drifted");
            assertEquals(0.0, parked.speed, 1e-9, "a parked car has speed");
        }
    }

    @Test
    @DisplayName("nothing is parked in a live lane of a motorway")
    void nothingParksOnAMotorway() {
        Engine e = spawned();
        WorldData w = WorldFixture.world();
        for (int round = 0; round < 6; round++) {
            for (Agents.Car c : e.agents.cars()) {
                if (!c.active || !c.parked) { continue; }
                assertTrue(w.edgeClass(c.edge) > 1,
                        "a car is parked on a class " + w.edgeClass(c.edge)
                                + " road");
            }
            InputState in = new InputState();
            for (int i = 0; i < 60; i++) { e.frame(1.0 / 60, in); }
        }
    }

    @Test
    @DisplayName("a parked car is one you can actually get into")
    void everythingParkedCanBeEntered() {
        Engine e = spawned();
        int bad = 0;
        for (Agents.Car c : e.agents.cars()) {
            if (!c.active || !c.parked) { continue; }
            if (!VehicleKind.values()[c.kindOrdinal].enterable) { bad++; }
        }
        assertEquals(0, bad, bad + " parked vehicles cannot be entered");
    }

    @Test
    @DisplayName("you can walk to a car and get in")
    void theCoreLoopWorks() {
        Engine e = spawned();
        InputState in = new InputState();
        Agents.Car target = null;
        double best = Double.MAX_VALUE;
        for (Agents.Car c : e.agents.cars()) {
            if (!c.active || !c.parked) { continue; }
            if (!VehicleKind.values()[c.kindOrdinal].enterable) { continue; }
            double d = Math.hypot(c.x - e.human.position.x,
                    c.z - e.human.position.z);
            if (d < best) { best = d; target = c; }
        }
        assertNotNull(target, "nothing to walk to");

        boolean in_ = false;
        for (int step = 0; step < 300 && !in_; step++) {
            double hx = e.human.position.x;
            double hz = e.human.position.z;
            double d = Math.hypot(target.x - hx, target.z - hz);
            if (d < Engine.ENTER_RADIUS_M) {
                assertNotNull(e.nearestEnterableLabel(),
                        "standing " + d + " m away and there is no prompt");
                in_ = e.tryEnterVehicle();
                break;
            }
            // moveY is a screen axis: negative is forward.
            e.cameraYaw = Math.atan2(target.x - hx, -(target.z - hz));
            in.moveY = -1.0;
            in.run = true;
            for (int i = 0; i < 10; i++) { e.frame(1.0 / 60, in); }
            in.moveY = 0;
            in.run = false;
        }
        assertTrue(in_, "could not reach and board a car " + (int) best + " m away");
        assertEquals(ControlProfile.CAR, e.profile);

        // And drive it.
        in.throttle = 1.0;
        double x0 = e.car.position.x;
        double z0 = e.car.position.z;
        for (int i = 0; i < 60 * 8; i++) { e.frame(1.0 / 60, in); }
        double moved = Math.hypot(e.car.position.x - x0, e.car.position.z - z0);
        assertTrue(moved > 25, "only moved " + moved + " m in eight seconds");
    }

    @Test
    @DisplayName("the streets do not empty out as you drive")
    void trafficKeepsUpWithThePlayer() {
        Engine e = spawned();
        InputState in = new InputState();
        // Put the player in a car and drive for half a minute.
        boolean got = false;
        for (int t = 0; t < 400 && !got; t++) {
            if (e.nearestEnterableDistance() < Engine.ENTER_RADIUS_M) {
                got = e.tryEnterVehicle();
            }
            if (!got) {
                e.cameraYaw += 0.3;
                in.moveY = -1.0;
                for (int i = 0; i < 6; i++) { e.frame(1.0 / 60, in); }
                in.moveY = 0;
            }
        }
        if (!got) { return; }       // covered by theCoreLoopWorks
        in.throttle = 1.0;
        int worst = Integer.MAX_VALUE;
        for (int s = 0; s < 30; s++) {
            for (int i = 0; i < 60; i++) { e.frame(1.0 / 60, in); }
            int near = 0;
            for (Agents.Car c : e.agents.cars()) {
                if (c.active && Math.hypot(c.x - e.car.position.x,
                        c.z - e.car.position.z) < 200) { near++; }
            }
            worst = Math.min(worst, near);
        }
        assertTrue(worst >= 2,
                "the road emptied to " + worst + " cars within 200 m while driving");
    }
}
