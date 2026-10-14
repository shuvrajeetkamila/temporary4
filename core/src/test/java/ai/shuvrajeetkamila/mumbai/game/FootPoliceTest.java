package ai.shuvrajeetkamila.mumbai.game;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.sim.Agents;
import ai.shuvrajeetkamila.mumbai.sim.Engine;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Officers on foot.
 *
 * <p>Until now the entire police response was vehicular, which had a plain
 * consequence: on foot, off the road network, nothing could ever reach the
 * player. A wanted level was something you walked away from down the first
 * alley. These tests are about the chase existing at all, and about it
 * ending — a pursuit with no leash is a pursuit you have to quit the game
 * to escape.
 */
final class FootPoliceTest {

    private static final double SPAWN_LAT = 18.9430;
    private static final double SPAWN_LON = 72.8230;

    private static Engine onFoot() {
        Engine e = new Engine(WorldFixture.world());
        e.spawnAt(SPAWN_LAT, SPAWN_LON, ControlProfile.ON_FOOT);
        // One frame, which is what sets the agent caps from the quality
        // level. Before it there is nowhere to put anybody.
        e.frame(1.0 / 60, new InputState());
        return e;
    }

    @Test
    @DisplayName("an officer on foot appears and closes on the player")
    void anOfficerClosesIn() {
        Engine e = onFoot();
        double px = e.human.position.x, pz = e.human.position.z;
        assertTrue(e.agents.spawnFootPolice(px, pz, 40),
                "no officer could be placed on the pavement");

        Agents.Ped cop = null;
        for (Agents.Ped p : e.agents.peds()) {
            if (p.active && p.cop) { cop = p; }
        }
        assertTrue(cop != null, "spawnFootPolice reported success and did nothing");

        double before = Math.hypot(cop.x - px, cop.z - pz);
        assertTrue(before > 10, "the officer started on top of the player");

        for (int i = 0; i < 120; i++) {
            e.agents.update(1.0 / 60, px, pz, Engine.AGENT_SPAWN_M,
                    Engine.AGENT_DESPAWN_M);
        }
        double after = Math.hypot(cop.x - px, cop.z - pz);
        assertTrue(after < before - 3,
                "two seconds of chase closed " + (before - after)
                        + " m. The officer is not running at the player.");
    }

    @Test
    @DisplayName("an officer gives up rather than following for ever")
    void thereIsALeash() {
        Engine e = onFoot();
        double px = e.human.position.x, pz = e.human.position.z;
        e.agents.spawnFootPolice(px, pz, 40);
        assertEquals(1, e.agents.footPoliceCount());

        // Sprint 400 m away, further than any officer will follow.
        double fx = px + 400, fz = pz;
        for (int i = 0; i < 20; i++) {
            e.agents.update(1.0 / 60, fx, fz, Engine.AGENT_SPAWN_M,
                    Engine.AGENT_DESPAWN_M);
        }
        assertEquals(0, e.agents.footPoliceCount(),
                "the officer is still chasing from outside the leash");
    }

    @Test
    @DisplayName("an officer does not wander off like a civilian")
    void officersAreNotDespawnedByRange() {
        // Civilians outside the ring are recycled. An officer who vanished
        // the moment the player jogged a block would make the chase a
        // formality, so the leash - and only the leash - removes them.
        Engine e = onFoot();
        double px = e.human.position.x, pz = e.human.position.z;
        e.agents.spawnFootPolice(px, pz, 60);
        double fx = px + 150, fz = pz;
        // A despawn ring deliberately tighter than the distance walked.
        for (int i = 0; i < 10; i++) {
            e.agents.update(1.0 / 60, fx, fz, 120, 200);
        }
        assertEquals(1, e.agents.footPoliceCount(),
                "an officer 150 m away was recycled as ordinary foot traffic");
    }

    @Test
    @DisplayName("an officer counts as police for the arrest and the heat")
    void theArrestUsesTheSameTest() {
        Engine e = onFoot();
        double px = e.human.position.x, pz = e.human.position.z;
        assertTrue(e.agents.nearestFootPolice(px, pz) > 1e6,
                "nearestFootPolice found somebody before anybody was spawned");
        e.agents.spawnFootPolice(px, pz, 30);
        double d = e.agents.nearestFootPolice(px, pz);
        assertTrue(d > 0 && d < 60, "officer reported at " + d + " m");

        // The arrest itself is Wanted's proximity test, which is what makes
        // being caught mean the same thing on foot as in a car.
        Wanted w = new Wanted();
        w.heat = 2.0;
        for (int i = 0; i < 400; i++) {
            w.update(1.0 / 60, 2.0, 0.0);
            if (w.bustedThisStep) { break; }
        }
        assertTrue(w.bustedThisStep,
                "standing still next to an officer never led to an arrest");
    }

    @Test
    @DisplayName("knocking an officer down buys time but does not end it")
    void anOfficerGetsBackUp() {
        Engine e = onFoot();
        double px = e.human.position.x, pz = e.human.position.z;
        e.agents.spawnFootPolice(px, pz, 25);
        Agents.Ped cop = null;
        for (Agents.Ped p : e.agents.peds()) { if (p.active && p.cop) { cop = p; } }
        cop.downTimer = 0.5;
        for (int i = 0; i < 60; i++) {
            e.agents.update(1.0 / 60, px, pz, Engine.AGENT_SPAWN_M,
                    Engine.AGENT_DESPAWN_M);
        }
        assertEquals(1, e.agents.footPoliceCount(),
                "an officer who was knocked down disappeared. A baton would"
                        + " be a way of deleting the police force.");
        assertTrue(cop.active && cop.downTimer <= 0, "the officer never got up");
    }

    @Test
    @DisplayName("they are stood down when the heat is gone")
    void clearingPoliceClearsTheFootPatrol() {
        Engine e = onFoot();
        double px = e.human.position.x, pz = e.human.position.z;
        e.agents.spawnFootPolice(px, pz, 30);
        e.agents.spawnFootPolice(px, pz, 30);
        assertTrue(e.agents.footPoliceCount() >= 1);
        e.agents.clearFootPolice();
        assertEquals(0, e.agents.footPoliceCount());
        assertTrue(e.agents.nearestFootPolice(px, pz) > 1e6);
    }

    @Test
    @DisplayName("a wanted player on foot is actually chased on foot")
    void heatPutsOfficersOnThePavement() {
        Engine e = onFoot();
        e.wanted.heat = 2.4;
        InputState in = new InputState();
        // Thirty seconds: units are called in one at a time, on purpose.
        for (int i = 0; i < 60 * 30 && e.agents.footPoliceCount() == 0; i++) {
            e.frame(1.0 / 60, in);
        }
        assertTrue(e.agents.footPoliceCount() > 0,
                "two stars, on foot, for thirty seconds, and not one officer"
                        + " on foot was sent");
    }

    @Test
    @DisplayName("the khaki is a distinct outfit, not a recoloured civilian")
    void theOfficerHasHisOwnOutfit() {
        assertEquals(Agents.OUTFITS, Agents.OUTFIT_POLICE,
                "the officer outfit overlaps a civilian one, so constables"
                        + " and passers-by would be indistinguishable");
        Engine e = onFoot();
        double px = e.human.position.x, pz = e.human.position.z;
        e.agents.spawnFootPolice(px, pz, 30);
        for (Agents.Ped p : e.agents.peds()) {
            if (!p.active) { continue; }
            if (p.cop) {
                assertEquals(Agents.OUTFIT_POLICE, p.outfit);
            } else {
                assertTrue(p.outfit < Agents.OUTFITS,
                        "a civilian is wearing the khaki");
            }
        }
    }
}
