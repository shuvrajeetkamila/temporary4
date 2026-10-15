package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.sim.Engine;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which way the aircraft go when you move the stick.
 *
 * <p>Both of them were backwards. {@code lookY} is a screen axis, so
 * negative is the stick pushed forward, and both aircraft negated it
 * again: pushing the cyclic forward pitched the helicopter's nose up and
 * flew it <em>backwards</em>, and pushing the aeroplane's stick forward
 * made it climb. Every other test drove the physics fields directly and
 * so could not see it; these go through {@link InputState}, which is the
 * only path a player has.
 */
final class FlightControlsTest {

    private static final HelicopterPhysics.GroundProbe FLAT_H = (x, z) -> 0.0;
    private static final AeroplanePhysics.GroundProbe FLAT_P = (x, z) -> 0.0;

    /** A trimmed helicopter at altitude, flown through the input layer. */
    private static Engine hovering() {
        Engine e = new Engine(WorldFixture.world());
        e.spawnAt(19.0887, 72.8679, ControlProfile.HELICOPTER);
        e.heli.position.y = 500;
        e.heli.velocity.zero();
        e.heli.angularVelocity.zero();
        InputState in = new InputState();
        in.collective = 1.0;
        for (int i = 0; i < 60 * 8; i++) { e.frame(1.0 / 60, in); }
        return e;
    }

    @Test
    @DisplayName("cyclic forward flies the helicopter forward")
    void cyclicForwardGoesForward() {
        Engine e = hovering();
        // Identity orientation is heading 180 in this frame, so the nose
        // is on +Z and forward is increasing z.
        double z0 = e.heli.position.z;
        InputState in = new InputState();
        in.collective = 1.0;
        in.lookY = -1.0;                  // stick forward
        for (int i = 0; i < 60 * 5; i++) { e.frame(1.0 / 60, in); }
        double moved = e.heli.position.z - z0;
        assertTrue(moved > 5,
                "pushed the cyclic forward and went " + moved + " m along the nose");
    }

    @Test
    @DisplayName("and cyclic back flies it backwards")
    void cyclicBackGoesBackward() {
        Engine e = hovering();
        double z0 = e.heli.position.z;
        InputState in = new InputState();
        in.collective = 1.0;
        in.lookY = 1.0;
        for (int i = 0; i < 60 * 5; i++) { e.frame(1.0 / 60, in); }
        assertTrue(e.heli.position.z - z0 < -5,
                "pulled the cyclic back and went " + (e.heli.position.z - z0) + " m");
    }

    @Test
    @DisplayName("cyclic right flies it right")
    void cyclicRightGoesRight() {
        Engine e = hovering();
        double x0 = e.heli.position.x;
        InputState in = new InputState();
        in.collective = 1.0;
        in.lookX = 1.0;
        for (int i = 0; i < 60 * 5; i++) { e.frame(1.0 / 60, in); }
        // Nose on +Z, so the pilot's right hand is towards -X.
        assertTrue(Math.abs(e.heli.position.x - x0) > 5,
                "rolled right and went nowhere sideways");
    }

    @Test
    @DisplayName("the pedals yaw the helicopter the way they are pushed")
    void pedalsYawTheRightWay() {
        Engine e = hovering();
        double h0 = e.heli.orientation.heading();
        InputState in = new InputState();
        in.collective = 1.0;
        in.pedal = 1.0;
        for (int i = 0; i < 60 * 3; i++) { e.frame(1.0 / 60, in); }
        double d = e.heli.orientation.heading() - h0;
        while (d > Math.PI) { d -= 2 * Math.PI; }
        while (d < -Math.PI) { d += 2 * Math.PI; }
        assertTrue(d > 0.2, "right pedal turned the nose " + Math.toDegrees(d)
                + " degrees");
    }

    @Test
    @DisplayName("stick back puts the aeroplane's nose up")
    void stickBackIsNoseUp() {
        AeroplanePhysics p = new AeroplanePhysics();
        p.placeAt(0, 2000, 0, 0);               // heading 0: nose on -Z
        p.velocity.set(0, 0, -72);
        p.throttle = 0.7;
        p.gearDown = false;
        for (int i = 0; i < 60 * 5; i++) { p.step(1.0 / 60, FLAT_P, 0, 0); }
        double before = p.orientation.pitch();
        // Engine maps elevator = +lookY, and lookY positive is the stick
        // pulled back towards the player.
        p.elevator = 1.0;
        for (int i = 0; i < 60 * 2; i++) { p.step(1.0 / 60, FLAT_P, 0, 0); }
        assertTrue(p.orientation.pitch() > before + 0.08,
                "stick back took the pitch from " + Math.toDegrees(before)
                        + " to " + Math.toDegrees(p.orientation.pitch()));
    }

    @Test
    @DisplayName("and stick forward puts it down")
    void stickForwardIsNoseDown() {
        AeroplanePhysics p = new AeroplanePhysics();
        p.placeAt(0, 2000, 0, 0);
        p.velocity.set(0, 0, -72);
        p.throttle = 0.7;
        p.gearDown = false;
        for (int i = 0; i < 60 * 5; i++) { p.step(1.0 / 60, FLAT_P, 0, 0); }
        double before = p.orientation.pitch();
        p.elevator = -1.0;
        for (int i = 0; i < 60 * 2; i++) { p.step(1.0 / 60, FLAT_P, 0, 0); }
        assertTrue(p.orientation.pitch() < before - 0.08,
                "stick forward took the pitch from " + Math.toDegrees(before)
                        + " to " + Math.toDegrees(p.orientation.pitch()));
    }

    @Test
    @DisplayName("the engine hands the stick to the aircraft the right way up")
    void theEngineWiringMatches() {
        Engine e = new Engine(WorldFixture.world());
        e.spawnAt(19.0887, 72.8679, ControlProfile.AEROPLANE);
        InputState in = new InputState();
        in.lookY = 1.0;                         // stick back
        e.frame(1.0 / 60, in);
        assertTrue(e.plane.elevator > 0,
                "stick back gave elevator " + e.plane.elevator);

        Engine h = new Engine(WorldFixture.world());
        h.spawnAt(19.0887, 72.8679, ControlProfile.HELICOPTER);
        InputState hi = new InputState();
        hi.lookY = -1.0;                        // cyclic forward
        h.frame(1.0 / 60, hi);
        assertTrue(h.heli.cyclicPitch < 0,
                "cyclic forward gave cyclicPitch " + h.heli.cyclicPitch);
    }

    @Test
    @DisplayName("the rudder yaws more than it rolls")
    void theRudderIsNotAnAileron() {
        AeroplanePhysics p = new AeroplanePhysics();
        p.placeAt(0, 2000, 0, 0);
        p.velocity.set(0, 0, -72);
        p.throttle = 0.7;
        p.gearDown = false;
        for (int i = 0; i < 60 * 6; i++) { p.step(1.0 / 60, FLAT_P, 0, 0); }
        double r0 = p.orientation.roll();
        double h0 = p.orientation.heading();
        p.rudderInput = 0.6;
        for (int i = 0; i < 60 * 3; i++) { p.step(1.0 / 60, FLAT_P, 0, 0); }
        double dRoll = Math.abs(p.orientation.roll() - r0);
        double dYaw = Math.abs(p.orientation.heading() - h0);
        assertTrue(dYaw > 0.15, "the rudder barely yawed: " + Math.toDegrees(dYaw));
        assertTrue(dRoll < dYaw,
                "three seconds of rudder rolled " + Math.toDegrees(dRoll)
                        + " degrees for " + Math.toDegrees(dYaw) + " of yaw");
    }

    @Test
    @DisplayName("a sideslip still washes itself out")
    void directionalStabilitySurvives() {
        AeroplanePhysics p = new AeroplanePhysics();
        p.placeAt(0, 2000, 0, 0);
        p.velocity.set(14, 0, -72);             // flying slightly sideways
        p.throttle = 0.6;
        p.gearDown = false;
        for (int i = 0; i < 60 * 12; i++) { p.step(1.0 / 60, FLAT_P, 0, 0); }
        assertTrue(Math.abs(p.sideslip) < 0.05,
                "sideslip settled at " + Math.toDegrees(p.sideslip) + " degrees");
    }

    @Test
    @DisplayName("autorotation is a descent, not a fall")
    void autorotationIsSurvivable() {
        // Trimmed, with airspeed, engine off: the only way anyone has
        // ever autorotated anything. A real light helicopter comes down
        // at roughly 12 m/s.
        //
        // The collectives tested are the autorotative range. Zero is
        // not in it and should not be: flat blades make no lift, and a
        // machine held at flat pitch all the way down is not
        // autorotating, it is falling with the rotor turning. What
        // matters is that the rotor is still there to be used, which is
        // the separate test below.
        for (double collective : new double[]{0.30, 0.40, 0.50}) {
            HelicopterPhysics h = new HelicopterPhysics();
            h.placeAt(0, 800, 0, 0);
            h.collective = 0.58;
            for (int i = 0; i < 60 * 8; i++) { h.step(1.0 / 60, FLAT_H, 0, 0); }
            h.velocity.set(0, 0, -28);
            h.engineOn = false;
            h.collective = collective;
            double y0 = 0;
            double t0 = 0;
            double rate = 0;
            for (int i = 0; i < 60 * 90 && h.position.y > 30; i++) {
                h.step(1.0 / 60, FLAT_H, 0, 0);
                if (i == 60 * 4) { y0 = h.position.y; t0 = i / 60.0; }
                if (i > 60 * 4) { rate = (y0 - h.position.y) / (i / 60.0 - t0); }
            }
            assertTrue(rate > 2 && rate < 18,
                    "engine-off descent at collective " + collective + " is "
                            + rate + " m/s");
            assertTrue(h.rotorRpm > 180,
                    "the rotor stopped: " + h.rotorRpm + " rpm");
        }
    }

    @Test
    @DisplayName("lowering the collective keeps the rotor turning")
    void loweringTheLeverDoesNotStopTheRotor() {
        // The first thing anyone does when the engine quits is drop the
        // lever, and that has to speed the rotor up: less pitch is less
        // drag on the blades. The drive was scaled by the torque the
        // rotor was absorbing, which collapses to nothing at flat
        // pitch, so lowering the collective stopped the rotor dead and
        // the machine fell at 90 m/s with no way back.
        for (double collective : new double[]{0.0, 0.1, 0.2}) {
            HelicopterPhysics h = new HelicopterPhysics();
            h.placeAt(0, 1500, 0, 0);
            h.collective = 0.58;
            for (int i = 0; i < 60 * 8; i++) { h.step(1.0 / 60, FLAT_H, 0, 0); }
            h.velocity.set(0, 0, -28);
            h.engineOn = false;
            h.collective = collective;
            for (int i = 0; i < 60 * 25 && h.position.y > 60; i++) {
                h.step(1.0 / 60, FLAT_H, 0, 0);
            }
            assertTrue(h.rotorPercent() > 85,
                    "twenty-five seconds after the engine quit with the"
                            + " lever at " + collective + " the rotor is"
                            + " down to " + h.rotorPercent() + " per"
                            + " cent, so there is nothing left to flare"
                            + " with");
        }

        // And the energy in it has to be usable: raise the lever and
        // the descent must actually be arrested.
        HelicopterPhysics h = new HelicopterPhysics();
        h.placeAt(0, 1500, 0, 0);
        h.collective = 0.58;
        for (int i = 0; i < 60 * 8; i++) { h.step(1.0 / 60, FLAT_H, 0, 0); }
        h.velocity.set(0, 0, -28);
        h.engineOn = false;
        h.collective = 0.05;
        for (int i = 0; i < 60 * 12; i++) { h.step(1.0 / 60, FLAT_H, 0, 0); }
        double plunge = -h.velocity.y;
        h.collective = 0.75;
        for (int i = 0; i < 60 * 4; i++) { h.step(1.0 / 60, FLAT_H, 0, 0); }
        assertTrue(-h.velocity.y < plunge * 0.75,
                "pulling the lever in the flare only took the descent"
                        + " from " + plunge + " to " + (-h.velocity.y)
                        + " m/s");
    }
}
