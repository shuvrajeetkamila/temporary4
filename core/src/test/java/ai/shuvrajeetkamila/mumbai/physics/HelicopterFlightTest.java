package ai.shuvrajeetkamila.mumbai.physics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the helicopter does when somebody tries to fly it somewhere.
 *
 * <p>{@link HelicopterTrimTest} covers the attitude loop - that the
 * machine does not tumble and that the stick points it the right way.
 * This covers the things that only show up once you are going
 * somewhere, which is where it was still wrong: it could not be flown
 * level at any speed, it span like a top, and it would not autorotate.
 */
final class HelicopterFlightTest {

    private static final double DT = 1.0 / 60.0;
    private static final HelicopterPhysics.GroundProbe FLAT = (x, z) -> 0.0;

    private static HelicopterPhysics at(double alt) {
        HelicopterPhysics h = new HelicopterPhysics();
        h.reset();
        h.position.set(0, alt, 0);
        h.collective = HelicopterPhysics.HOVER_COLLECTIVE;
        return h;
    }

    private static HelicopterPhysics fly(double alt, double stick,
                                         double collective, double seconds) {
        HelicopterPhysics h = at(alt);
        h.cyclicPitch = stick;
        h.collective = collective;
        for (int i = 0; i < (int) (60 * seconds); i++) {
            h.step(DT, FLAT, 0, 0);
        }
        return h;
    }

    @Test
    @DisplayName("forward flight does not turn into a climb")
    void itCanBeFlownLevel() {
        // Translational lift was being applied twice: once inside the
        // blade-element solver, where forward speed collapses the
        // induced inflow and the thrust genuinely rises, and then
        // again as a 1.26 multiplier bolted on the outside. On top of
        // that the solver only ever saw the VERTICAL airspeed, so the
        // air blowing down through a disc tilted forward - the thing
        // that stops the thrust running away as speed builds - was
        // missing entirely.
        //
        // Together those made the rotor produce 204 per cent of the
        // aircraft's weight at 40 m/s. Forward cyclic was a climb at
        // 12 m/s and there was no collective setting that would hold
        // an altitude at speed.
        for (double stick : new double[]{-0.4, -0.7, -1.0}) {
            boolean trimmed = false;
            double closest = 1e9;
            for (double c = 0.30; c <= 0.80; c += 0.01) {
                HelicopterPhysics h = fly(400, stick, c, 35);
                double vy = Math.abs(h.velocity.y);
                closest = Math.min(closest, vy);
                if (vy < 0.6 && h.position.y > 100) { trimmed = true; break; }
            }
            assertTrue(trimmed,
                    "no collective holds a level cruise at stick " + stick
                            + "; the best was " + closest + " m/s of climb");
        }
    }

    @Test
    @DisplayName("the rotor does not make more than it should at speed")
    void thrustDoesNotRunAwayWithAirspeed() {
        HelicopterPhysics hover = at(400);
        for (int i = 0; i < 60; i++) { hover.step(DT, FLAT, 0, 0); }
        double atHover = hover.thrustToWeight();

        HelicopterPhysics fast = at(400);
        fast.cyclicPitch = -1.0;
        for (int i = 0; i < 60 * 35; i++) { fast.step(DT, FLAT, 0, 0); }
        double speed = Math.hypot(fast.velocity.x, fast.velocity.z);
        assertTrue(speed > 40, "it never got going: " + speed + " m/s");
        assertTrue(fast.thrustToWeight() < 1.35,
                "at " + Math.round(speed * 3.6) + " km/h the rotor is making "
                        + Math.round(fast.thrustToWeight() * 100)
                        + "% of the aircraft's weight on the hover collective");
        assertTrue(fast.thrustToWeight() > atHover * 0.8,
                "translational lift disappeared altogether");
    }

    @Test
    @DisplayName("it puts its nose down to go fast")
    void goingFastLooksLikeGoingFast() {
        // Nine degrees of free disc tilt let the machine do 230 km/h
        // while sitting 2.6 degrees nose down, which does not read as a
        // helicopter. The tilt is five degrees now, so the airframe has
        // to do the work.
        HelicopterPhysics h = fly(500, -1.0, HelicopterPhysics.HOVER_COLLECTIVE, 35);
        ai.shuvrajeetkamila.mumbai.math.Vec3 f =
                new ai.shuvrajeetkamila.mumbai.math.Vec3();
        h.orientation.forward(f);
        double pitchDeg = Math.toDegrees(Math.asin(
                Math.max(-1, Math.min(1, f.y))));
        assertTrue(pitchDeg < -3.0,
                "full forward cyclic and the nose is only " + pitchDeg
                        + " degrees down");
        assertTrue(pitchDeg > -35.0,
                "it is standing on its nose at " + pitchDeg + " degrees");
    }

    @Test
    @DisplayName("full pedal is a pedal turn, not a spin")
    void pedalAuthorityIsSane() {
        // The pedal moment used to be 55 per cent of whatever the main
        // rotor happened to be drawing, which at hover power gave 134
        // degrees a second - so a nudge of pedal to line up a landing
        // threw the machine a quarter turn.
        HelicopterPhysics h = at(300);
        h.pedal = 1.0;
        for (int i = 0; i < 60 * 8; i++) { h.step(DT, FLAT, 0, 0); }
        double rate = Math.abs(Math.toDegrees(h.angularVelocity.y));
        assertTrue(rate > 30 && rate < 90,
                "full pedal gives " + rate + " deg/s");

        // And it must be roughly proportional, so half pedal is half a
        // rate and the control is predictable.
        HelicopterPhysics half = at(300);
        half.pedal = 0.5;
        for (int i = 0; i < 60 * 8; i++) { half.step(DT, FLAT, 0, 0); }
        double halfRate = Math.abs(Math.toDegrees(half.angularVelocity.y));
        assertTrue(Math.abs(halfRate - rate * 0.5) < rate * 0.15,
                "half pedal gave " + halfRate + " deg/s against " + rate
                        + " at full, which is not a proportional control");
    }

    @Test
    @DisplayName("pulling harder climbs harder, all the way to the stop")
    void theCollectiveIsMonotonic() {
        // With the lever geared past what the engine could drive, the
        // governor saturated in the top third of the travel: the rotor
        // drooped to 82 per cent and the climb rate got WORSE the
        // harder you pulled. Nothing about that is discoverable from
        // the cockpit.
        double last = -99;
        for (double c : new double[]{0.65, 0.75, 0.85, 1.0}) {
            HelicopterPhysics h = at(300);
            h.collective = c;
            for (int i = 0; i < 60 * 25; i++) { h.step(DT, FLAT, 0, 0); }
            assertTrue(h.velocity.y > last + 0.3,
                    "collective " + c + " climbs at " + h.velocity.y
                            + " m/s against " + last + " at the notch below");
            assertTrue(h.rotorPercent() > 93,
                    "collective " + c + " drooped the rotor to "
                            + h.rotorPercent() + "%");
            last = h.velocity.y;
        }
    }

    @Test
    @DisplayName("a parked helicopter stays parked")
    void itDoesNotLiftOffOnItsOwn() {
        // In ground effect the same blade pitch makes about a quarter
        // more thrust, so a lever resting at the hover value floated
        // the machine off the pad, up through the cushion, and left it
        // hovering at seventeen metres with nobody touching anything.
        HelicopterPhysics h = new HelicopterPhysics();
        h.reset();
        h.position.set(0, 0.9, 0);
        h.collective = HelicopterPhysics.GROUND_COLLECTIVE;
        for (int i = 0; i < 60 * 20; i++) { h.step(DT, FLAT, 0, 0); }
        assertTrue(h.position.y < 1.5,
                "it took off by itself to " + h.position.y + " m");

        // And it must still be able to go flying.
        for (int i = 0; i < 60 * 6; i++) {
            h.collective = 1.0;
            h.step(DT, FLAT, 0, 0);
        }
        assertTrue(h.position.y > 20,
                "six seconds at full lever and it is only " + h.position.y
                        + " m up");
    }

    @Test
    @DisplayName("an engine failure with speed on is a glide")
    void autorotationGlides() {
        HelicopterPhysics h = fly(900, -0.7, 0.47, 30);
        h.engineOn = false;
        h.collective = 0.15;
        double y0 = h.position.y;
        for (int i = 0; i < 60 * 20; i++) { h.step(DT, FLAT, 0, 0); }
        double rate = (y0 - h.position.y) / 20.0;
        double speed = Math.hypot(h.velocity.x, h.velocity.z);
        assertTrue(rate > 3 && rate < 15, "engine-off descent is " + rate + " m/s");
        assertTrue(speed / rate > 2.5,
                "glide ratio " + (speed / rate) + ":1");
        assertTrue(h.rotorPercent() > 60,
                "the rotor wound down to " + h.rotorPercent() + "%");
        assertTrue(h.inAutorotation, "it does not know it is autorotating");
    }
}
