package ai.shuvrajeetkamila.mumbai.game;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.sim.Engine;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Taking a job and finishing it.
 *
 * <p>Every other mission test checks a piece: that a fare is priced right,
 * that a checkpoint is the right size, that abandoning works. None of them
 * ran a job from being offered to being paid, which is the only sequence a
 * player ever performs.
 */
final class MissionRunTest {

    private static final double SPAWN_LAT = 18.9430;
    private static final double SPAWN_LON = 72.8230;

    private static Engine driving(VehicleKind k) {
        Engine e = new Engine(WorldFixture.world());
        e.spawnAt(SPAWN_LAT, SPAWN_LON, ControlProfile.CAR);
        e.car.configure(k);
        return e;
    }

    /** Park the player's vehicle on a stop and let the arrival register. */
    private static void arriveAt(Engine e, Missions.Stop s) {
        WorldData w = WorldFixture.world();
        e.car.placeAt(s.x, w.height(s.x, s.z) + 0.6, s.z, 0);
        e.car.velocity.zero();
        InputState in = new InputState();
        for (int i = 0; i < 30; i++) { e.frame(1.0 / 60, in); }
    }

    private static Missions.Job waitForWork(Engine e) {
        InputState in = new InputState();
        for (int i = 0; i < 60 * 20 && e.missions.active == null; i++) {
            e.frame(1.0 / 60, in);
        }
        return e.missions.active;
    }

    @Test
    @DisplayName("a taxi is offered a fare, drives it, and is paid")
    void aTaxiFareCanBeCompleted() {
        Engine e = driving(VehicleKind.KAALI_PEELI);
        Missions.Job j = waitForWork(e);
        assertNotNull(j, "no fare was ever offered to a kaali-peeli");
        assertEquals(Missions.Kind.TAXI, j.kind);
        assertTrue(j.stops.size() >= 2,
                "a fare with " + j.stops.size() + " stops is not a fare");
        assertTrue(j.title != null && !j.title.isEmpty(), "the job has no title");

        int before = e.missions.money;
        for (int guard = 0; guard < 8 && e.missions.active != null; guard++) {
            Missions.Stop s = e.missions.active.target();
            assertNotNull(s, "a live job with nowhere to go");
            arriveAt(e, s);
        }
        assertNull(e.missions.active, "the fare never finished");
        assertTrue(e.missions.money > before,
                "finished the fare and was paid " + (e.missions.money - before));
        assertTrue(e.missions.banner.contains("Rs"),
                "no payment was announced: \"" + e.missions.banner + "\"");
    }

    @Test
    @DisplayName("the fare is priced by the distance actually covered")
    void theFareIsPricedByDistance() {
        Engine e = driving(VehicleKind.KAALI_PEELI);
        Missions.Job j = waitForWork(e);
        assertNotNull(j);
        // Pick up, which is where the meter starts.
        arriveAt(e, j.stops.get(0));
        Missions.Job live = e.missions.active;
        assertNotNull(live, "the job ended at the pick-up");
        double legKm = Math.hypot(live.target().x - e.car.position.x,
                live.target().z - e.car.position.z) / 1000.0;

        int before = e.missions.money;
        arriveAt(e, live.target());
        int paid = e.missions.money - before;
        assertTrue(paid >= Missions.TAXI_BASE,
                "paid " + paid + " for a " + legKm + " km fare");
        // A straight-line lower bound: the road is never shorter than this.
        assertTrue(paid < Missions.TAXI_BASE + Missions.TAXI_PER_KM * legKm * 6 + 40,
                "paid " + paid + " for a " + legKm + " km fare");
    }

    @Test
    @DisplayName("a tempo is offered deliveries, not fares")
    void aTempoDelivers() {
        Engine e = driving(VehicleKind.TEMPO);
        Missions.Job j = waitForWork(e);
        assertNotNull(j, "a tempo was never offered any work");
        assertEquals(Missions.Kind.DELIVERY, j.kind);
    }

    @Test
    @DisplayName("a fast car is offered a race, and the race is against a clock")
    void aFastCarRaces() {
        Engine e = driving(VehicleKind.SEDAN);
        Missions.Job j = waitForWork(e);
        assertNotNull(j, "a sedan was never offered a race");
        assertEquals(Missions.Kind.RACE, j.kind);
        assertTrue(j.timed(), "the race has no clock");
        assertTrue(j.timeLimit > 10,
                "a race with a " + j.timeLimit + " second limit is unwinnable");
    }

    @Test
    @DisplayName("getting out of the cab loses the fare")
    void leavingTheVehicleAbandonsTheJob() {
        Engine e = driving(VehicleKind.KAALI_PEELI);
        assertNotNull(waitForWork(e));
        e.profile = ControlProfile.ON_FOOT;
        InputState in = new InputState();
        for (int i = 0; i < 30; i++) { e.frame(1.0 / 60, in); }
        assertNull(e.missions.active, "the fare followed the player out of the cab");
    }

    @Test
    @DisplayName("a bus driver is told where to find work")
    void thereIsAHintWhenNoWorkIsAvailable() {
        Engine e = driving(VehicleKind.CITY_BUS);
        InputState in = new InputState();
        for (int i = 0; i < 120; i++) { e.frame(1.0 / 60, in); }
        assertNull(e.missions.active, "a bus was given a job");
        assertTrue(e.missions.hint != null && !e.missions.hint.isEmpty(),
                "no work and no hint about where to find any");
    }

    @Test
    @DisplayName("money adds up across jobs")
    void earningsAccumulate() {
        Engine e = driving(VehicleKind.KAALI_PEELI);
        int paidJobs = 0;
        int money = 0;
        for (int job = 0; job < 3; job++) {
            Missions.Job j = waitForWork(e);
            if (j == null) { break; }
            for (int g = 0; g < 8 && e.missions.active != null; g++) {
                arriveAt(e, e.missions.active.target());
            }
            if (e.missions.money > money) { paidJobs++; }
            assertTrue(e.missions.money >= money, "money went down");
            money = e.missions.money;
        }
        assertTrue(paidJobs >= 2,
                "only " + paidJobs + " of three fares paid anything");
    }
}
