package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.math.MathUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoatAndHumanTest {

    private static final BoatPhysics.SeabedProbe DEEP = (x, z) -> -30;

    private static Ocean calm() {
        Ocean o = new Ocean();
        o.setChoppiness(0);
        return o;
    }

    @Test
    @DisplayName("a boat floats at a sensible draft and stays there")
    void floats() {
        BoatPhysics b = BoatPhysics.ferry();
        Ocean sea = calm();
        b.position.set(0, 3, 0);
        for (int i = 0; i < 60 * 40; i++) { b.step(1.0 / 60, sea, DEEP); }
        double waterline = sea.heightAt(b.position.x, b.position.z);
        double sink = waterline - b.position.y;
        assertTrue(Math.abs(sink) < 2.5,
                "it should settle near the waterline, offset " + sink + " m");
        assertTrue(Math.abs(b.velocity.y) < 0.25,
                "it should stop bobbing, dy/dt = " + b.velocity.y);
        assertFalse(b.capsized(), "a ferry at rest must not capsize");
    }

    @Test
    @DisplayName("buoyancy saturates: pushing it under does not launch it to orbit")
    void buoyancySaturates() {
        BoatPhysics b = BoatPhysics.ferry();
        Ocean sea = calm();
        b.position.set(0, -14, 0);           // rammed well under
        double maxUp = 0;
        for (int i = 0; i < 60 * 30; i++) {
            b.step(1.0 / 60, sea, DEEP);
            maxUp = Math.max(maxUp, b.velocity.y);
            assertTrue(b.position.isFinite(), "diverged at step " + i);
        }
        assertTrue(maxUp < 22,
                "surfacing at " + maxUp + " m/s means buoyancy is unbounded");
    }

    @Test
    @DisplayName("righting moment recovers a knockdown, which a linear term cannot")
    void rightsItself() {
        BoatPhysics b = BoatPhysics.ferry();
        Ocean sea = calm();
        b.position.set(0, 0, 0);
        for (int i = 0; i < 60 * 10; i++) { b.step(1.0 / 60, sea, DEEP); }
        // Lay it over 50 degrees, which is a knockdown rather than a capsize.
        b.orientation.set(ai.shuvrajeetkamila.mumbai.math.Quat.axisAngle(
                0, 0, 1, Math.toRadians(50)));
        b.angularVelocity.zero();
        double worst = 0;
        for (int i = 0; i < 60 * 45; i++) {
            b.step(1.0 / 60, sea, DEEP);
            worst = Math.max(worst, b.heelDegrees());
        }
        assertTrue(b.heelDegrees() < 22,
                "it should come back up, still heeled " + b.heelDegrees() + " deg");
    }

    @Test
    @DisplayName("throttle drives it forward and drag caps the speed")
    void makesWay() {
        BoatPhysics b = BoatPhysics.speedboat();
        Ocean sea = calm();
        b.position.set(0, 0, 0);
        for (int i = 0; i < 60 * 8; i++) { b.step(1.0 / 60, sea, DEEP); }
        b.throttle = 1.0;
        for (int i = 0; i < 60 * 90; i++) { b.step(1.0 / 60, sea, DEEP); }
        double kt = b.speedKnots();
        assertTrue(kt > 8, "a speedboat at full throttle should move, got " + kt + " kt");
        assertTrue(kt < 90, "and not at " + kt + " kt");
    }

    @Test
    @DisplayName("planing does not create energy: it costs induced drag")
    void planingIsNotFree() {
        BoatPhysics fast = BoatPhysics.speedboat();
        Ocean sea = calm();
        fast.position.set(0, 0, 0);
        fast.throttle = 1.0;
        double prev = 0;
        boolean sawSteadyState = false;
        for (int i = 0; i < 60 * 120; i++) {
            fast.step(1.0 / 60, sea, DEEP);
            if (i > 60 * 60) {
                double d = Math.abs(fast.groundSpeed() - prev);
                if (d < 0.02) { sawSteadyState = true; }
            }
            prev = fast.groundSpeed();
        }
        assertTrue(sawSteadyState,
                "it must settle at a terminal speed; still accelerating at "
                        + fast.speedKnots() + " kt means planing lift has no drag cost");
    }

    @Test
    @DisplayName("it grounds in the shallows instead of sailing over the beach")
    void beaches() {
        BoatPhysics b = BoatPhysics.ferry();
        Ocean sea = calm();
        b.position.set(0, 0, 0);
        b.throttle = 1.0;
        // A beach shelving up ahead of it. Identity orientation means
        // forward is +Z in this frame, so the bank has to be on that side or
        // the boat simply motors away from it.
        BoatPhysics.SeabedProbe beach = (x, z) -> -14 + Math.max(0, z) * 0.05;
        for (int i = 0; i < 60 * 240; i++) { b.step(1.0 / 60, sea, beach); }
        double bed = beach.bedHeight(b.position.x, b.position.z);
        assertTrue(bed < 0.5,
                "it drove up onto dry land to " + bed + " m - grounding is not working");
        assertTrue(b.groundSpeed() < 4.0,
                "aground but still doing " + b.speedKnots() + " kt");
    }

    // ------------------------------------------------------------------- human

    private static final HumanPhysics.Terrain FLAT = new HumanPhysics.Terrain() {
        @Override public double heightAt(double x, double z) { return 0; }
        @Override public boolean isWater(double x, double z) { return false; }
        @Override public double slopeAt(double x, double z) { return 0; }
    };

    @Test
    @DisplayName("walking and running reach their stated speeds")
    void locomotion() {
        HumanPhysics h = new HumanPhysics();
        Ocean sea = calm();
        h.position.set(0, h.height * 0.5, 0);
        for (int i = 0; i < 60; i++) { h.step(1.0 / 60, FLAT, sea); }
        h.moveForward = 1;
        for (int i = 0; i < 60 * 6; i++) { h.step(1.0 / 60, FLAT, sea); }
        double walk = h.velocity.planarLength();
        assertEquals(h.walkSpeed, walk, 0.35, "walk speed was " + walk);
        h.wantRun = true;
        for (int i = 0; i < 60 * 6; i++) { h.step(1.0 / 60, FLAT, sea); }
        double run = h.velocity.planarLength();
        assertTrue(run > walk + 1.5, "running should be faster: " + walk + " -> " + run);
        assertEquals(h.runSpeed, run, 0.6, "run speed was " + run);
    }

    @Test
    @DisplayName("the legs are force limited, so a wall still stops you")
    void legForceIsBounded() {
        HumanPhysics h = new HumanPhysics();
        assertTrue(h.legForceMax < h.mass * MathUtil.G * 3,
                "a person cannot push harder than about 3 g");
    }

    @Test
    @DisplayName("jumping leaves the ground and gravity brings it back")
    void jumps() {
        HumanPhysics h = new HumanPhysics();
        Ocean sea = calm();
        h.position.set(0, h.height * 0.5, 0);
        for (int i = 0; i < 120; i++) { h.step(1.0 / 60, FLAT, sea); }
        double rest = h.position.y;
        h.wantJump = true;
        h.step(1.0 / 60, FLAT, sea);
        h.wantJump = false;
        double apex = rest;
        for (int i = 0; i < 60 * 3; i++) {
            h.step(1.0 / 60, FLAT, sea);
            apex = Math.max(apex, h.position.y);
        }
        double height = apex - rest;
        assertTrue(height > 0.35 && height < 1.6,
                "jump height was " + height + " m");
        assertTrue(h.grounded, "it should have landed again");
    }

    @Test
    @DisplayName("deep water means swimming, and swimming is slow")
    void swims() {
        Ocean sea = calm();
        HumanPhysics.Terrain lagoon = new HumanPhysics.Terrain() {
            @Override public double heightAt(double x, double z) { return -8; }
            @Override public boolean isWater(double x, double z) { return true; }
            @Override public double slopeAt(double x, double z) { return 0; }
        };
        HumanPhysics h = new HumanPhysics();
        h.position.set(0, 0, 0);
        for (int i = 0; i < 60 * 12; i++) { h.step(1.0 / 60, lagoon, sea); }
        assertTrue(h.swimming, "it should be swimming, depth " + h.waterDepth);
        assertTrue(h.position.y > -2.0,
                "a person floats rather than sinking, y = " + h.position.y);
        h.moveForward = 1;
        h.wantSprint = true;
        for (int i = 0; i < 60 * 12; i++) { h.step(1.0 / 60, lagoon, sea); }
        double v = h.velocity.planarLength();
        assertTrue(v < 2.2, "swimming at " + v + " m/s is faster than a person swims");
        assertTrue(v > 0.3, "it should make some headway, got " + v);
    }

    @Test
    @DisplayName("ragdoll drops the locomotion force and getting up restores it")
    void ragdoll() {
        HumanPhysics h = new HumanPhysics();
        Ocean sea = calm();
        h.position.set(0, h.height * 0.5, 0);
        for (int i = 0; i < 60; i++) { h.step(1.0 / 60, FLAT, sea); }
        h.ragdoll = true;
        h.moveForward = 1;
        for (int i = 0; i < 60 * 4; i++) { h.step(1.0 / 60, FLAT, sea); }
        assertTrue(h.velocity.planarLength() < 0.4,
                "a ragdoll cannot walk, it was doing " + h.velocity.planarLength());
        h.getUp();
        for (int i = 0; i < 60 * 4; i++) { h.step(1.0 / 60, FLAT, sea); }
        assertTrue(h.velocity.planarLength() > 1.0, "it should walk again once up");
    }

    @Test
    @DisplayName("not every boat in the harbour is the same boat")
    void mooringsCarryDifferentHulls() {
        // The engine held one BoatPhysics built by ferry(), and boarding
        // anything reset that, so every vessel in the city was a 34
        // tonne harbour ferry. Fourteen knots is right for a ferry and
        // wrong for everything else afloat: no boat could plane and the
        // planing model never ran outside this file.
        java.util.Map<String, Integer> mix = new java.util.HashMap<>();
        for (int i = 0; i < 400; i++) {
            double x = -4000 + i * 37.5, z = 9000 - i * 19.25;
            mix.merge(BoatPhysics.forMooring(x, z).hullName, 1, Integer::sum);
        }
        assertTrue(mix.size() >= 3,
                "the whole harbour is the same hull: " + mix);
        for (java.util.Map.Entry<String, Integer> e : mix.entrySet()) {
            assertTrue(e.getValue() > 400 / 12,
                    "hull " + e.getKey() + " barely appears: " + mix);
        }

        // And the same mooring must give the same boat every time, or
        // the launch you left tied up is a lighter when you come back.
        for (int i = 0; i < 50; i++) {
            double x = 1234.5 + i, z = -876.25 - i * 3;
            assertEquals(BoatPhysics.forMooring(x, z).hullName,
                    BoatPhysics.forMooring(x, z).hullName,
                    "the hull at a mooring is not stable");
        }
    }

    @Test
    @DisplayName("the launch gets up on the plane and outruns the ferry")
    void theLaunchPlanes() {
        BoatPhysics fast = BoatPhysics.launch();
        BoatPhysics slow = BoatPhysics.ferry();
        ai.shuvrajeetkamila.mumbai.physics.Ocean sea =
                new ai.shuvrajeetkamila.mumbai.physics.Ocean();
        fast.throttle = 1.0;
        slow.throttle = 1.0;
        boolean planedWithin10s = false;
        for (int i = 0; i < 60 * 90; i++) {
            sea.advance(1.0 / 60);
            fast.step(1.0 / 60, sea, DEEP);
            slow.step(1.0 / 60, sea, DEEP);
            if (i < 60 * 10 && fast.planing) { planedWithin10s = true; }
        }
        assertTrue(planedWithin10s,
                "the launch took more than ten seconds to get on the plane");
        assertTrue(fast.planing, "it fell off the plane at its own top speed");
        double kn = fast.groundSpeed() * 1.94384;
        assertTrue(kn > 22 && kn < 45,
                "a harbour launch should do twenty-odd knots, got " + kn);
        assertTrue(fast.groundSpeed() > slow.groundSpeed() * 1.5,
                "the launch (" + kn + " kn) barely beat the ferry ("
                        + slow.groundSpeed() * 1.94384 + " kn)");
        assertFalse(slow.planing, "a 34 tonne harbour ferry is planing");
    }
}
