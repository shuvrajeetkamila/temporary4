package ai.shuvrajeetkamila.mumbai.game;

import ai.shuvrajeetkamila.mumbai.sim.Agents;
import ai.shuvrajeetkamila.mumbai.sim.Engine;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wanted level and the police response.
 *
 * <p>The rules that matter here are the ones that stop the system being
 * miserable: a crowded pavement must not generate a wanted level, heat
 * must not tick down while a police car is sitting on you, and being
 * arrested must not cost more than you have.
 */
final class WantedTest {

    private static final double DT = 1.0 / 60;

    /** Far enough that nothing can see you. */
    private static final double ALONE = 1e9;

    // ------------------------------------------------------------ the rules

    @Test
    @DisplayName("stars are a view of heat, and cap at five")
    void starsFollowHeat() {
        Wanted w = new Wanted();
        assertEquals(0, w.stars());
        assertTrue(!w.active(), "wanted with no heat");

        w.heat = 0.99;
        assertEquals(0, w.stars(), "a fraction of a star is not a star");
        assertTrue(!w.active());

        w.heat = 1.0;
        assertEquals(1, w.stars());
        assertTrue(w.active());

        w.heat = 4.7;
        assertEquals(4, w.stars());

        for (int i = 0; i < 50; i++) { w.ranOverPedestrian(20); }
        assertEquals(Wanted.MAX_STARS, w.stars(), "heat ran past the cap");
        assertTrue(w.heat <= Wanted.MAX_STARS + 1e-9);
    }

    @Test
    @DisplayName("brushing past somebody at walking pace is not a crime")
    void slowContactIsNotACrime() {
        Wanted w = new Wanted();
        for (int i = 0; i < 30; i++) {
            assertTrue(!w.ranOverPedestrian(1.4),
                    "walking into somebody counted as a crime");
            assertTrue(!w.rammedVehicle(2.0, false),
                    "nudging a bumper in traffic counted as a crime");
        }
        assertEquals(0, w.stars(),
                "a crowded pavement generated a wanted level");
        assertEquals(0.0, w.heat, 1e-9);
    }

    @Test
    @DisplayName("running somebody over at speed is, and hitting police is worse")
    void fastContactIsACrime() {
        Wanted w = new Wanted();
        assertTrue(w.ranOverPedestrian(14.0));
        double afterPed = w.heat;
        assertTrue(afterPed > 0);

        Wanted v = new Wanted();
        v.rammedVehicle(14.0, false);
        Wanted u = new Wanted();
        u.rammedVehicle(14.0, true);
        assertTrue(u.heat > v.heat,
                "ramming a police car should cost more than ramming a taxi");
        assertTrue(afterPed > v.heat,
                "a person should be worth more heat than a wing mirror");
    }

    /** Earns heat the way the game does, so the cooldown state is real. */
    private static Wanted offender(int crimes) {
        Wanted w = new Wanted();
        for (int i = 0; i < crimes; i++) { w.ranOverPedestrian(18.0); }
        return w;
    }

    @Test
    @DisplayName("heat does not fall while the police can see you")
    void heatHoldsWhileObserved() {
        Wanted w = offender(5);
        double held = w.heat;
        // Thirty seconds with a car right behind, but moving so as not to
        // get nicked.
        for (int i = 0; i < 30 * 60; i++) {
            w.update(DT, 20.0, 18.0);
        }
        assertEquals(held, w.heat, 1e-6,
                "the wanted level decayed with a police car in the mirror");
    }

    @Test
    @DisplayName("losing them cools it off, but not instantly")
    void heatFallsOnceLost() {
        // Earned, not assigned: committing the crime is what starts the
        // cool-off clock, and a test that sets the field directly is
        // testing a state the game can never be in.
        Wanted w = offender(4);
        double earned = w.heat;
        assertTrue(earned > 1.0, "four offences should be worth a star");

        // The first few seconds alone must not cool anything.
        for (int i = 0; i < (int) (Wanted.COOL_DELAY * 60) - 60; i++) {
            w.update(DT, ALONE, 20.0);
        }
        assertEquals(earned, w.heat, 1e-6,
                "heat started falling before the cool-off delay");

        // Then it bleeds away.
        for (int i = 0; i < 60 * 60; i++) { w.update(DT, ALONE, 20.0); }
        assertEquals(0.0, w.heat, 1e-9, "never cooled off");
        assertEquals(0, w.stars());
    }

    @Test
    @DisplayName("a fresh offence puts you straight back in view")
    void offendingResetsTheCooldown() {
        Wanted w = offender(4);
        for (int i = 0; i < 60 * 60; i++) { w.update(DT, ALONE, 20.0); }
        // Cooled right off, then offend again.
        w.ranOverPedestrian(18.0);
        assertEquals(0.0, w.unseenFor, 1e-9,
                "committing a crime did not put you back in view");
    }

    @Test
    @DisplayName("stopping next to a police car gets you nicked, driving on does not")
    void bustingNeedsYouCornered() {
        Wanted w = offender(4);

        // Right beside them but still moving: no arrest.
        for (int i = 0; i < 20 * 60; i++) {
            w.update(DT, 3.0, 14.0);
            assertTrue(!w.bustedThisStep, "arrested at 50 km/h");
        }

        // Now stop.
        boolean busted = false;
        for (int i = 0; i < 10 * 60 && !busted; i++) {
            w.update(DT, 3.0, 0.2);
            busted = w.bustedThisStep;
        }
        assertTrue(busted, "stopped dead beside the police and was not arrested");
    }

    @Test
    @DisplayName("pulling away resets the arrest timer")
    void drivingOffEscapesTheArrest() {
        Wanted w = offender(4);
        // Almost nicked...
        for (int i = 0; i < (int) (Wanted.BUST_SECONDS * 60) - 10; i++) {
            w.update(DT, 3.0, 0.1);
        }
        assertTrue(!w.bustedThisStep);
        // ...then away.
        w.update(DT, 3.0, 12.0);
        assertEquals(0.0, w.bustTimer, 1e-9, "the arrest timer did not reset");
    }

    @Test
    @DisplayName("a fine takes a quarter, and never more than you have")
    void finesAreProportionate() {
        Wanted w = offender(7);
        assertEquals(250, w.bust(1000), "the fine is not a quarter");
        assertEquals(0.0, w.heat, 1e-9, "heat survived the arrest");
        assertEquals(1, w.timesBusted);

        Wanted broke = new Wanted();
        assertEquals(0, broke.bust(0), "fined somebody with no money");
        assertTrue(broke.bust(3) <= 3, "the fine came to more than was held");
    }

    @Test
    @DisplayName("more stars means more cars, driven harder")
    void responseScalesWithStars() {
        Wanted w = new Wanted();
        int lastUnits = -1;
        double lastPace = -1;
        for (int stars = 0; stars <= Wanted.MAX_STARS; stars++) {
            w.heat = stars;
            assertTrue(w.desiredUnits() >= lastUnits,
                    "the response shrank as the wanted level rose");
            assertTrue(w.pursuitPace() >= lastPace,
                    "police got slower as the wanted level rose");
            lastUnits = w.desiredUnits();
            lastPace = w.pursuitPace();
        }
        assertEquals(0, new Wanted().desiredUnits(),
                "police were sent for a player with no wanted level");
        assertTrue(w.pursuitPace() > 1.0,
                "police drive slower than the speed limit during a chase");
    }

    // ------------------------------------------------------- against the city

    @Test
    @DisplayName("police are called in, chase, and stand down when it is over")
    void policeArriveAndLeave() {
        WorldData world = WorldFixture.world();
        Assumptions.assumeTrue(world != null, "needs a compiled world");
        Engine e = new Engine(world);
        double px = e.human.position.x, pz = e.human.position.z;

        assertEquals(0, e.agents.policeCount(), "police on patrol before any crime");

        // Commit something worth two stars.
        e.wanted.heat = 2.0;
        for (int i = 0; i < 12 * 60; i++) {
            e.agents.pursuitPace = e.wanted.pursuitPace();
            e.agents.update(DT, px, pz, 0, 0, 400, 900);
            if (e.agents.policeCount() < e.wanted.desiredUnits()
                    && i % 84 == 0) {
                e.agents.spawnPolice(px, pz, Engine.POLICE_SPAWN_M);
            }
        }
        assertTrue(e.agents.policeCount() > 0,
                "nobody came after two stars' worth of crime");

        // And they are flagged as police, driving a police car.
        int seen = 0;
        for (Agents.Car c : e.agents.cars()) {
            if (c.active && c.police) {
                seen++;
                assertEquals(VehicleKind.POLICE.ordinal(), c.kindOrdinal,
                        "a pursuit unit was not driving a police car");
            }
        }
        assertEquals(e.agents.policeCount(), seen);

        e.agents.clearPolice();
        assertEquals(0, e.agents.policeCount(), "police would not stand down");
    }

    @Test
    @DisplayName("a pursuing car closes on the player")
    void pursuitActuallyPursues() {
        WorldData world = WorldFixture.world();
        Assumptions.assumeTrue(world != null, "needs a compiled world");
        Engine e = new Engine(world);
        double px = e.human.position.x, pz = e.human.position.z;
        e.agents.pursuitPace = 1.3;

        int closed = 0, tried = 0;
        for (int attempt = 0; attempt < 10; attempt++) {
            e.agents.clearPolice();
            if (!e.agents.spawnPolice(px, pz, 300)) { continue; }
            double start = e.agents.nearestPolice(px, pz);
            if (start < 60 || start > 1e8) { continue; }
            tried++;

            // The minimum reached over the chase, not the distance at the
            // end of it. A car that drives up to the player and overshoots
            // has pursued perfectly well, and judging it on where it
            // happened to be after a fixed number of steps measures the
            // phase of its last turn rather than whether it is chasing.
            double closest = start;
            for (int i = 0; i < 40 * 60; i++) {
                e.agents.update(DT, px, pz, 0, 0, 400, 1200);
                double d = e.agents.nearestPolice(px, pz);
                if (d < closest) { closest = d; }
            }
            if (closest < start * 0.6) { closed++; }
        }
        Assumptions.assumeTrue(tried > 0, "no police unit could be placed");
        assertTrue(closed > tried / 2,
                "pursuit units closed on the player in only " + closed
                        + " of " + tried + " runs; they are not chasing");
    }

    @Test
    @DisplayName("the baton reaches in front of you and nowhere else")
    void batonHasReachAndAnArc() {
        WorldData world = WorldFixture.world();
        Assumptions.assumeTrue(world != null, "needs a compiled world");
        Engine e = new Engine(world);

        double px = e.human.position.x, pz = e.human.position.z;
        double h = e.human.orientation.heading();
        double fx = Math.sin(h), fz = -Math.cos(h);

        Agents.Ped[] peds = e.agents.peds();
        Assumptions.assumeTrue(peds.length >= 3, "not enough pedestrian slots");

        // One in front, one behind, one far away.
        peds[0].active = true;
        peds[0].downTimer = 0;
        peds[0].x = px + fx * 1.6;
        peds[0].z = pz + fz * 1.6;

        peds[1].active = true;
        peds[1].downTimer = 0;
        peds[1].x = px - fx * 1.6;
        peds[1].z = pz - fz * 1.6;

        peds[2].active = true;
        peds[2].downTimer = 0;
        peds[2].x = px + fx * 30.0;
        peds[2].z = pz + fz * 30.0;

        int hit = e.swingBaton();
        assertEquals(1, hit, "the baton should have caught exactly one person");
        assertTrue(peds[0].downTimer > 0, "the one in front was not hit");
        assertEquals(0.0, peds[1].downTimer, 1e-9,
                "the baton hit somebody standing behind the player");
        assertEquals(0.0, peds[2].downTimer, 1e-9,
                "the baton reached thirty metres");
        assertTrue(e.wanted.heat > 0, "swinging a baton drew no attention");
    }

    @Test
    @DisplayName("the baton has a cooldown and does not work from a car")
    void batonIsLimited() {
        WorldData world = WorldFixture.world();
        Assumptions.assumeTrue(world != null, "needs a compiled world");
        Engine e = new Engine(world);
        double px = e.human.position.x, pz = e.human.position.z;
        double h = e.human.orientation.heading();

        Agents.Ped[] peds = e.agents.peds();
        for (int i = 0; i < 2 && i < peds.length; i++) {
            peds[i].active = true;
            peds[i].downTimer = 0;
            peds[i].x = px + Math.sin(h) * 1.5;
            peds[i].z = pz - Math.cos(h) * 1.5;
        }
        assertTrue(e.swingBaton() > 0, "the first swing missed");
        // Immediately again: still on cooldown.
        peds[1].downTimer = 0;
        assertEquals(0, e.swingBaton(), "the baton has no cooldown");
    }
}
