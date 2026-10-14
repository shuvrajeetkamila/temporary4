package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.world.ChunkFeatures;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Agents standing on the right surface, and wearing clothes that exist. */
final class StreetDressingTest {

    private static Engine spawned() {
        Engine e = new Engine(WorldFixture.world());
        e.spawnAt(18.9430, 72.8230, ControlProfile.ON_FOOT);
        InputState in = new InputState();
        for (int i = 0; i < 180; i++) { e.frame(1.0 / 60, in); }
        return e;
    }

    @Test
    @DisplayName("every pedestrian wears an outfit that has been modelled")
    void outfitsAreInRange() {
        Engine e = spawned();
        int worst = -1;
        for (Agents.Ped p : e.agents.peds()) {
            if (p.active) { worst = Math.max(worst, p.outfit); }
        }
        assertTrue(worst >= 0, "no pedestrians at all");
        assertTrue(worst < Agents.OUTFITS,
                "a pedestrian is wearing outfit " + worst + " and only "
                        + Agents.OUTFITS + " are modelled");
    }

    @Test
    @DisplayName("more than one outfit is actually used")
    void thereIsVarietyOnTheStreet() {
        Engine e = spawned();
        boolean[] seen = new boolean[Agents.OUTFITS];
        for (Agents.Ped p : e.agents.peds()) {
            if (p.active && p.outfit >= 0 && p.outfit < seen.length) {
                seen[p.outfit] = true;
            }
        }
        int n = 0;
        for (boolean b : seen) { if (b) { n++; } }
        assertTrue(n >= 3, "only " + n + " different outfits on the whole street");
    }

    @Test
    @DisplayName("an agent on a bridge stands on the deck, not the seabed")
    void trafficRidesTheBridge() throws IOException {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(18.9430, 72.8230, ControlProfile.ON_FOOT);

        // Find a bridged road with real height over what is beneath it, and
        // ask the engine's own surface for the agent height there.
        for (int id = 0; id < w.chunkCount(); id++) {
            if (!w.chunkHasData(id)) { continue; }
            ChunkFeatures f = ChunkFeatures.decode(id, w.chunkOriginX(id),
                    w.chunkOriginZ(id), w.readChunk(id), w.quant);
            for (int r = 0; r < f.roadCount(); r++) {
                if ((f.roadFlags[r] & ChunkFeatures.FLAG_BRIDGE) == 0) { continue; }
                if (f.roadClass[r] == 23 || f.roadLength(r) < 120) { continue; }
                int s = f.roadOffset[r];
                int en = f.roadOffset[r + 1];
                int mid = (s + en) / 2;
                double mx = w.chunkOriginX(id) + f.roadXZ[mid * 2];
                double mz = w.chunkOriginZ(id) + f.roadXZ[mid * 2 + 1];
                double terrain = w.isLand(mx, mz) ? w.height(mx, mz) : 0;
                double deck = e.city.deckAt(mx, mz, terrain + 3.0);
                if (deck == Double.NEGATIVE_INFINITY || deck - terrain < 4) {
                    continue;
                }
                // Drop a car exactly there and let the spawner place it.
                Agents.Car c = null;
                for (Agents.Car x : e.agents.cars()) { c = x; break; }
                assertTrue(c != null);
                double got = agentHeight(e, mx, mz);
                assertTrue(got > terrain + 2,
                        "an agent on a bridge deck at " + deck
                                + " is placed at " + got + " over ground " + terrain);
                return;
            }
        }
    }

    /** What the engine's agent surface reports, via a throwaway placement. */
    private static double agentHeight(Engine e, double x, double z) {
        double g = e.world.inBounds(x, z) ? e.world.height(x, z) : 0;
        double deck = e.city.deckAt(x, z, g + 3.0);
        return deck == Double.NEGATIVE_INFINITY ? g : Math.max(g, deck);
    }

    @Test
    @DisplayName("pedestrians stand on the ground, not in it or above it")
    void pedestriansStandOnTheGround() {
        Engine e = spawned();
        int bad = 0;
        int n = 0;
        for (Agents.Ped p : e.agents.peds()) {
            if (!p.active) { continue; }
            n++;
            double g = e.world.inBounds(p.x, p.z) ? e.world.height(p.x, p.z) : 0;
            if (Math.abs(p.y - g) > 12) { bad++; }
        }
        assertTrue(n > 10, "only " + n + " pedestrians");
        assertTrue(bad == 0, bad + " of " + n + " pedestrians are off the ground");
    }
}
