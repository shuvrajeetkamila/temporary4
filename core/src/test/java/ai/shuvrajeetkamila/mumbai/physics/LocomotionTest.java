package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.math.MathUtil;
import ai.shuvrajeetkamila.mumbai.math.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Does each control move you the way it says it does?
 *
 * <p>The existing physics tests check that the models are sound: that a
 * wing stalls, that a hull planes, that a gearbox shifts. What none of
 * them check is direction. A rudder wired backwards still produces a
 * perfectly plausible turn rate, a reversed aileron still rolls the
 * aeroplane, and every energy and force assertion still passes. Those
 * only show up when a person tries to drive the thing, so they are
 * pinned here.
 *
 * <p>Conventions under test: world X east, Y up, Z south; heading is
 * atan2(forward.x, -forward.z), so it grows clockwise from north. Body
 * axes are +X right, +Y up, +Z forward.
 */
final class LocomotionTest {

    private static final double DT = 1.0 / 60;

    // ------------------------------------------------------------- helpers

    /** Dead flat tarmac at sea level. */
    private static CarPhysics.GroundProbe flatCar() {
        return new CarPhysics.GroundProbe() {
            public double heightAt(double x, double z) { return 0; }

            public void normalAt(double x, double z, Vec3 out) { out.set(0, 1, 0); }

            public boolean isUnpaved(double x, double z) { return false; }
        };
    }

    private static HumanPhysics.Terrain flatGround() {
        return new HumanPhysics.Terrain() {
            public double heightAt(double x, double z) { return 0; }

            public boolean isWater(double x, double z) { return false; }

            public double slopeAt(double x, double z) { return 0; }
        };
    }

    private static Ocean calm() {
        Ocean o = new Ocean();
        o.setChoppiness(0);
        return o;
    }

    /** Change in heading over a run, wrapped to the shortest way round. */
    private static double headingDelta(double from, double to) {
        return MathUtil.wrapPi(to - from);
    }

    // --------------------------------------------------------------- walking

    @Test
    @DisplayName("forward is forward and right is right, whatever way you face")
    void walkingIsRelativeToWhereYouLook() {
        for (double heading : new double[]{0, Math.PI / 2, Math.PI, -Math.PI / 3}) {
            // Straight ahead.
            HumanPhysics h = new HumanPhysics();
            h.position.set(0, h.height * 0.5, 0);
            h.headingRad = heading;
            h.moveForward = 1;
            h.moveRight = 0;
            h.wantRun = true;
            for (int i = 0; i < 180; i++) { h.step(DT, flatGround(), calm()); }

            double fx = Math.sin(heading), fz = -Math.cos(heading);
            double alongF = h.position.x * fx + h.position.z * fz;
            double acrossF = h.position.x * (-fz) + h.position.z * fx;
            assertTrue(alongF > 2.0,
                    "pushing forward on heading " + heading + " only moved "
                            + alongF + " m ahead");
            assertTrue(Math.abs(acrossF) < Math.abs(alongF) * 0.2,
                    "walking forward drifted sideways by " + acrossF + " m");

            // Straight right.
            HumanPhysics r = new HumanPhysics();
            r.position.set(0, r.height * 0.5, 0);
            r.headingRad = heading;
            r.moveForward = 0;
            r.moveRight = 1;
            r.wantRun = true;
            for (int i = 0; i < 180; i++) { r.step(DT, flatGround(), calm()); }

            double acrossR = r.position.x * (-fz) + r.position.z * fx;
            assertTrue(acrossR > 2.0,
                    "strafing right on heading " + heading + " moved "
                            + acrossR + " m, which is not right");
        }
    }

    @Test
    @DisplayName("a half-pushed stick walks, a full one runs")
    void partialStickGivesPartialSpeed() {
        double slow = topSpeedOnFoot(0.45, true);
        double fast = topSpeedOnFoot(1.0, true);
        assertTrue(slow < fast * 0.7,
                "half a stick gave " + slow + " m/s against a full " + fast
                        + "; movement is not analogue");
        assertTrue(slow > 0.3, "half a stick barely moved: " + slow);
    }

    private double topSpeedOnFoot(double amount, boolean run) {
        HumanPhysics h = new HumanPhysics();
        h.position.set(0, h.height * 0.5, 0);
        h.moveForward = amount;
        h.wantRun = run;
        double best = 0;
        for (int i = 0; i < 400; i++) {
            h.step(DT, flatGround(), calm());
            best = Math.max(best, h.velocity.planarLength());
        }
        return best;
    }

    @Test
    @DisplayName("sprint is faster than run is faster than walk")
    void thePacesAreOrdered() {
        double walk = topSpeedOnFoot(1.0, false);
        double run = topSpeedOnFoot(1.0, true);
        HumanPhysics h = new HumanPhysics();
        h.position.set(0, h.height * 0.5, 0);
        h.moveForward = 1;
        h.wantRun = true;
        h.wantSprint = true;
        double sprint = 0;
        for (int i = 0; i < 180; i++) {
            h.step(DT, flatGround(), calm());
            sprint = Math.max(sprint, h.velocity.planarLength());
        }
        assertTrue(walk < run, "walk " + walk + " was not slower than run " + run);
        assertTrue(run < sprint, "run " + run + " was not slower than sprint " + sprint);
    }

    // ------------------------------------------------------------------- car

    @Test
    @DisplayName("reverse actually goes backwards")
    void carReverses() {
        CarPhysics c = new CarPhysics();
        c.configure(ai.shuvrajeetkamila.mumbai.game.VehicleKind.SEDAN);
        c.position.set(0, 0.6, 0);
        for (int i = 0; i < 120; i++) { c.step(DT, flatCar(), 0); }
        double startZ = c.position.z;

        c.selectReverse();
        c.throttle = 1;
        for (int i = 0; i < 60 * 5; i++) { c.step(DT, flatCar(), 0); }

        // Body forward is +Z, and the car starts facing north, which is -Z
        // in world terms. Reversing must move it the other way.
        Vec3 fwd = c.orientation.forward(new Vec3());
        double travelled = (c.position.x - 0) * fwd.x + (c.position.z - startZ) * fwd.z;
        assertTrue(travelled < -1.0,
                "selecting reverse and opening the throttle moved the car "
                        + travelled + " m along its nose; it should go backwards");
    }

    @Test
    @DisplayName("steering right turns right, steering left turns left")
    void carSteeringIsSigned() {
        assertTrue(carHeadingChange(1.0) > 0.25,
                "full right lock did not turn the car clockwise");
        assertTrue(carHeadingChange(-1.0) < -0.25,
                "full left lock did not turn the car anticlockwise");
    }

    private double carHeadingChange(double steer) {
        CarPhysics c = new CarPhysics();
        c.configure(ai.shuvrajeetkamila.mumbai.game.VehicleKind.SEDAN);
        c.position.set(0, 0.6, 0);
        c.throttle = 0.55;
        for (int i = 0; i < 60 * 4; i++) { c.step(DT, flatCar(), 0); }
        c.steerInput = steer;
        // Accumulate the change step by step rather than comparing the
        // start and end headings. On full lock the car now turns at about
        // 1.2 rad/s, so it passes half a turn inside this window and a
        // single start-to-end comparison wraps and reports the opposite
        // sign - which looks exactly like a steering inversion and is not
        // one.
        double total = 0;
        double prev = c.orientation.heading();
        for (int i = 0; i < 60 * 3; i++) {
            c.step(DT, flatCar(), 0);
            double now = c.orientation.heading();
            total += headingDelta(prev, now);
            prev = now;
        }
        return total;
    }

    @Test
    @DisplayName("the handbrake stops the car and locks the back wheels")
    void handbrakeWorks() {
        CarPhysics c = new CarPhysics();
        c.configure(ai.shuvrajeetkamila.mumbai.game.VehicleKind.SEDAN);
        c.position.set(0, 0.6, 0);
        c.throttle = 1;
        for (int i = 0; i < 60 * 8; i++) { c.step(DT, flatCar(), 0); }
        double rolling = c.velocity.planarLength();
        assertTrue(rolling > 8, "the car never got going: " + rolling);

        c.throttle = 0;
        c.handbrakeOn = true;
        for (int i = 0; i < 60 * 8; i++) { c.step(DT, flatCar(), 0); }
        assertTrue(c.velocity.planarLength() < rolling * 0.25,
                "the handbrake left the car doing " + c.velocity.planarLength()
                        + " m/s, down from " + rolling);
    }

    // ------------------------------------------------------------------ boat

    @Test
    @DisplayName("the rudder turns the boat, and starboard means right")
    void boatRudderIsSigned() {
        double right = boatHeadingChange(1.0);
        double left = boatHeadingChange(-1.0);
        assertTrue(right > 0.1,
                "starboard rudder turned the boat " + right
                        + " rad; it should go clockwise");
        assertTrue(left < -0.1,
                "port rudder turned the boat " + left
                        + " rad; it should go anticlockwise");
    }

    private double boatHeadingChange(double rudder) {
        BoatPhysics b = new BoatPhysics();
        Ocean sea = calm();
        BoatPhysics.SeabedProbe deep = (x, z) -> -40.0;
        b.position.set(0, 0, 0);
        b.throttle = 1;
        // A rudder does nothing without water moving past it, so get her
        // up to speed first.
        for (int i = 0; i < 60 * 25; i++) { b.step(DT, sea, deep); }
        double h0 = b.orientation.heading();
        b.rudder = rudder;
        for (int i = 0; i < 60 * 20; i++) { b.step(DT, sea, deep); }
        return headingDelta(h0, b.orientation.heading());
    }

    // ------------------------------------------------------------ helicopter

    @Test
    @DisplayName("more collective climbs, less descends")
    void collectiveControlsHeight() {
        assertTrue(heliClimbRate(1.0) > 0.5,
                "full collective did not climb");
        assertTrue(heliClimbRate(0.0) < -0.5,
                "flat pitch did not descend");
    }

    private double heliClimbRate(double collective) {
        HelicopterPhysics h = new HelicopterPhysics();
        HelicopterPhysics.GroundProbe ground = (x, z) -> 0.0;
        h.position.set(0, 120, 0);
        h.engineOn = true;
        h.collective = collective;
        double y0 = h.position.y;
        for (int i = 0; i < 60 * 6; i++) { h.step(DT, ground, 0, 0); }
        return (h.position.y - y0) / 6.0;
    }

    @Test
    @DisplayName("right pedal yaws right, left pedal yaws left")
    void helicopterPedalsAreSigned() {
        double right = heliYaw(1.0);
        double left = heliYaw(-1.0);
        assertTrue(right > left + 0.05,
                "the pedals do not separate: right gave " + right
                        + " rad and left " + left);
        assertTrue(right > 0, "right pedal did not yaw the nose right: " + right);
        assertTrue(left < 0, "left pedal did not yaw the nose left: " + left);
    }

    private double heliYaw(double pedal) {
        HelicopterPhysics h = new HelicopterPhysics();
        HelicopterPhysics.GroundProbe ground = (x, z) -> 0.0;
        h.position.set(0, 200, 0);
        h.engineOn = true;
        h.collective = 0.62;
        for (int i = 0; i < 60 * 3; i++) { h.step(DT, ground, 0, 0); }
        double h0 = h.orientation.heading();
        h.pedal = pedal;
        for (int i = 0; i < 60 * 4; i++) { h.step(DT, ground, 0, 0); }
        return headingDelta(h0, h.orientation.heading());
    }

    @Test
    @DisplayName("cyclic forward drops the nose, cyclic right drops the right wing")
    void helicopterCyclicIsSigned() {
        assertTrue(heliAttitude(-1, 0)[0] < -0.02,
                "pushing the cyclic forward did not pitch the nose down");
        assertTrue(heliAttitude(1, 0)[0] > 0.02,
                "pulling the cyclic back did not pitch the nose up");
        assertTrue(heliAttitude(0, 1)[1] > 0.02,
                "cyclic right did not roll right");
        assertTrue(heliAttitude(0, -1)[1] < -0.02,
                "cyclic left did not roll left");
    }

    /** Returns {pitch, roll} after holding a cyclic input. */
    private double[] heliAttitude(double pitch, double roll) {
        // Short horizon on purpose: this asks which way the control moves
        // the machine, not where it ends up after four seconds of full
        // deflection, by which point it is in a spiral and the attitude
        // angles say more about the spiral than the control.
        HelicopterPhysics h = new HelicopterPhysics();
        HelicopterPhysics.GroundProbe ground = (x, z) -> 0.0;
        h.position.set(0, 300, 0);
        h.engineOn = true;
        h.collective = 0.62;
        for (int i = 0; i < 60 * 2; i++) { h.step(DT, ground, 0, 0); }
        h.cyclicPitch = pitch;
        h.cyclicRoll = roll;
        for (int i = 0; i < 24; i++) { h.step(DT, ground, 0, 0); }
        return new double[]{h.orientation.pitch(), h.orientation.roll()};
    }

    // ------------------------------------------------------------- aeroplane

    @Test
    @DisplayName("elevator back raises the nose, forward lowers it")
    void planeElevatorIsSigned() {
        assertTrue(planeAttitude(1, 0)[0] > 0.02,
                "back stick did not raise the nose");
        assertTrue(planeAttitude(-1, 0)[0] < -0.02,
                "forward stick did not lower the nose");
    }

    @Test
    @DisplayName("aileron right rolls right, aileron left rolls left")
    void planeAileronIsSigned() {
        assertTrue(planeAttitude(0, 1)[1] > 0.02,
                "right aileron did not roll the aeroplane right");
        assertTrue(planeAttitude(0, -1)[1] < -0.02,
                "left aileron did not roll the aeroplane left");
    }

    /** Returns {pitch, roll} after holding a control input in flight. */
    private double[] planeAttitude(double elevator, double aileron) {
        AeroplanePhysics p = flying();
        p.elevator = elevator;
        p.aileron = aileron;
        for (int i = 0; i < 18; i++) { p.step(DT, (x, z) -> 0.0, 0, 0); }
        return new double[]{p.orientation.pitch(), p.orientation.roll()};
    }

    @Test
    @DisplayName("rudder right yaws right")
    void planeRudderIsSigned() {
        AeroplanePhysics p = flying();
        double h0 = p.orientation.heading();
        p.rudderInput = 1;
        for (int i = 0; i < 18; i++) { p.step(DT, (x, z) -> 0.0, 0, 0); }
        assertTrue(headingDelta(h0, p.orientation.heading()) > 0,
                "right rudder did not yaw the nose right");
    }

    /** An aeroplane trimmed and flying straight and level at altitude. */
    private AeroplanePhysics flying() {
        AeroplanePhysics p = new AeroplanePhysics();
        p.position.set(0, 900, 0);
        p.gearDown = false;
        p.throttle = 0.8;
        // Launch it at a sensible cruise so the surfaces have authority.
        p.velocity.set(0, 0, 0);
        p.orientation.identity();
        Vec3 f = p.orientation.forward(new Vec3());
        p.velocity.set(f.x * 68, 0, f.z * 68);
        for (int i = 0; i < 60; i++) { p.step(DT, (x, z) -> 0.0, 0, 0); }
        return p;
    }

    @Test
    @DisplayName("a sideslip washes out instead of running away")
    void theAeroplaneIsLaterallyStable() {
        AeroplanePhysics p = flying();
        Vec3 right = p.orientation.right(new Vec3());
        p.velocity.addSelf(right.x * 14, 0, right.z * 14);
        p.step(DT, (x, z) -> 0.0, 0, 0);     // populate sideslip
        double start = Math.abs(p.sideslip);
        assertTrue(start > 0.1, "the kick did not produce a slip: " + start);

        double worst = start;
        for (int i = 0; i < 60 * 6; i++) {
            p.step(DT, (x, z) -> 0.0, 0, 0);
            worst = Math.max(worst, Math.abs(p.sideslip));
        }
        // The fin and the dihedral must oppose the slip. Get either sign
        // backwards and this grows without bound: an 11 degree kick became
        // 89 degrees inside two seconds, which is the aeroplane flying
        // sideways, and every force and energy assertion still passed.
        assertTrue(worst < start * 1.6,
                "a slip of " + Math.toDegrees(start) + " deg grew to "
                        + Math.toDegrees(worst) + " deg; the aeroplane is "
                        + "laterally divergent");
        assertTrue(Math.abs(p.sideslip) < start * 0.5,
                "the slip never washed out: still "
                        + Math.toDegrees(Math.abs(p.sideslip)) + " deg");
    }

    @Test
    @DisplayName("the helicopter can actually hover, and holds its rotor RPM")
    void helicopterCanHover() {
        HelicopterPhysics.GroundProbe ground = (x, z) -> 0.0;
        double hoverCollective = -1;
        for (double col = 0.3; col <= 1.0; col += 0.05) {
            HelicopterPhysics h = new HelicopterPhysics();
            h.position.set(0, 400, 0);
            h.engineOn = true;
            h.collective = col;
            double y0 = h.position.y;
            for (int i = 0; i < 60 * 5; i++) { h.step(DT, ground, 0, 0); }
            double climb = (h.position.y - y0) / 5.0;
            if (climb > 0 && hoverCollective < 0) { hoverCollective = col; }
        }
        // The engine was specified at 3400 N m against the 8100 it needs to
        // hover this mass, so the machine sank at full collective and could
        // not leave the ground at all.
        assertTrue(hoverCollective > 0,
                "the helicopter cannot climb at any collective setting");
        assertTrue(hoverCollective > 0.35 && hoverCollective < 0.85,
                "hover should need most of the collective but not all of "
                        + "it; it needs " + hoverCollective);

        // And the governor must hold the rotor up under that load.
        HelicopterPhysics h = new HelicopterPhysics();
        h.position.set(0, 400, 0);
        h.engineOn = true;
        h.collective = hoverCollective;
        for (int i = 0; i < 60 * 5; i++) { h.step(DT, ground, 0, 0); }
        assertTrue(h.rotorRpm > h.nominalRotorRpm * 0.93,
                "rotor drooped to " + h.rotorRpm + " of "
                        + h.nominalRotorRpm + " just holding a hover");
    }

    @Test
    @DisplayName("feet off the pedals does not spin the helicopter up")
    void helicopterHoldsHeadingHandsOff() {
        HelicopterPhysics h = new HelicopterPhysics();
        HelicopterPhysics.GroundProbe ground = (x, z) -> 0.0;
        h.position.set(0, 400, 0);
        h.engineOn = true;
        h.collective = 0.6;
        h.pedal = 0;
        for (int i = 0; i < 60; i++) { h.step(DT, ground, 0, 0); }
        double h0 = h.orientation.heading();
        for (int i = 0; i < 60 * 5; i++) { h.step(DT, ground, 0, 0); }
        double turned = Math.abs(headingDelta(h0, h.orientation.heading()));
        // The tail rotor used to be given 45 per cent of the anti-torque at
        // neutral pedal, which only looked survivable while the engine was
        // too weak to make any torque worth cancelling.
        assertTrue(turned < 1.2,
                "hands off the pedals, the helicopter yawed " + turned
                        + " rad in five seconds");
    }

    @Test
    @DisplayName("the gear costs you speed, which is why it comes up")
    void gearIsDraggy() {
        double clean = planeCruise(false);
        double dirty = planeCruise(true);
        assertTrue(dirty < clean,
                "leaving the gear down cost nothing: clean " + clean
                        + " m/s against dirty " + dirty);
    }

    private double planeCruise(boolean gearDown) {
        AeroplanePhysics p = flying();
        p.gearDown = gearDown;
        p.throttle = 1;
        p.elevator = 0;
        double best = 0;
        for (int i = 0; i < 60 * 40; i++) {
            p.step(DT, (x, z) -> 0.0, 0, 0);
            best = Math.max(best, p.velocity.length());
        }
        return best;
    }
}
