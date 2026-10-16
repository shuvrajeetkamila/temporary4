package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.app.ControlMapping;
import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control loop, not the aerodynamics.
 *
 * <p>{@link HelicopterFlightTest} measures what the rotor does when
 * given a collective. This measures what the aircraft does when given a
 * <em>thumb</em>: stick, mapping, physics and the skid state feeding
 * back into the mapping, all closed into one loop and run at 60 Hz.
 *
 * <p>That loop is where the machine was unflyable even after the
 * aerodynamics were right. The collective latched wherever it was left,
 * which meant a released stick kept whatever climb rate had been dialled
 * in, and with no detent and no readout the hover was unfindable. Flown
 * here, the aircraft climbed past three hundred metres and was still
 * going up at twelve metres a second with nobody touching anything.
 */
final class HelicopterPilotingTest {

    private static final double DT = 1.0 / 60.0;
    /** A pad at sea level. */
    private static final HelicopterPhysics.GroundProbe PAD = (x, z) -> 0.0;

    /** A machine sitting on its skids with the controls where a fresh one has them. */
    private static final class Rig {
        final HelicopterPhysics h = new HelicopterPhysics();
        final InputState in = new InputState();
        private final boolean[] held = new boolean[64];
        private final boolean[] edge = new boolean[64];

        Rig() {
            h.reset();
            // Rest it on the skids and let the springs settle.
            h.position.set(0, 1.0, 0);
            in.resetLevers();
            hold(0, 0, 0, 0, 1.0);
        }

        /** Fly for {@code seconds} with the sticks held where they are. */
        void hold(double lx, double ly, double rx, double ry, double seconds) {
            int n = (int) Math.round(seconds * 60);
            for (int i = 0; i < n; i++) {
                ControlMapping.apply(ControlProfile.HELICOPTER, in,
                        lx, ly, rx, ry, held, edge, DT);
                h.collective = in.collective;
                h.cyclicPitch = in.lookY;
                h.cyclicRoll = in.lookX;
                h.pedal = in.pedal;
                h.step(DT, PAD, 0, 0);
                in.heliOnSkids = h.onSkids;
            }
        }

        double alt() { return h.position.y; }

        double climb() { return h.velocity.y; }

        double speed() { return Math.hypot(h.velocity.x, h.velocity.z); }
    }

    @Test
    @DisplayName("a parked helicopter stays parked")
    void parkedStaysParked() {
        Rig r = new Rig();
        double a0 = r.alt();
        r.hold(0, 0, 0, 0, 20);
        assertTrue(Math.abs(r.alt() - a0) < 0.5,
                "it drifted off the pad on its own: " + a0 + " -> " + r.alt());
        assertTrue(r.h.onSkids, "it thinks it is flying while sitting still");
    }

    @Test
    @DisplayName("up stick climbs, and letting go stops the climb")
    void lettingGoHolds() {
        Rig r = new Rig();
        r.hold(0, -1, 0, 0, 4);
        double climbing = r.climb();
        assertTrue(climbing > 4.0,
                "four seconds of full up stick should be climbing hard, got "
                        + climbing);

        // Release. The lever must come home and the climb must wash out.
        r.hold(0, 0, 0, 0, 10);
        assertTrue(r.climb() < climbing * 0.35,
                "the climb did not wash out after releasing the stick: "
                        + climbing + " -> " + r.climb());
        assertTrue(Math.abs(r.climb()) < 2.5,
                "a released collective should settle near level flight, got "
                        + r.climb() + " m/s");

        // And it must stay settled rather than creeping away.
        double a = r.alt();
        r.hold(0, 0, 0, 0, 15);
        assertTrue(Math.abs(r.alt() - a) < 40,
                "it wandered " + (r.alt() - a) + " m in fifteen hands-off"
                        + " seconds");
    }

    @Test
    @DisplayName("down stick descends and it can be landed")
    void itCanBeLanded() {
        Rig r = new Rig();
        r.hold(0, -1, 0, 0, 5);
        r.hold(0, 0, 0, 0, 4);
        assertTrue(r.alt() > 25, "it never got off the pad, " + r.alt() + " m");

        // Walk it down with gentle down stick, easing off near the ground
        // the way a landing actually goes.
        for (int i = 0; i < 1200 && r.alt() > 6; i++) {
            r.hold(0, r.climb() < -4 ? 0 : 0.35, 0, 0, DT);
        }
        for (int i = 0; i < 2400 && !r.h.onSkids; i++) {
            r.hold(0, r.climb() < -1.2 ? -0.25 : 0.2, 0, 0, DT);
        }
        assertTrue(r.h.onSkids, "it could not be landed, still at " + r.alt() + " m");
        assertTrue(Math.abs(r.climb()) < 3.0,
                "that was an arrival, not a landing: " + r.climb() + " m/s");

        // Once down it must stay down with the stick released.
        double a = r.alt();
        r.hold(0, 0, 0, 0, 10);
        assertTrue(Math.abs(r.alt() - a) < 0.6,
                "it lifted off again after landing, " + a + " -> " + r.alt());
    }

    @Test
    @DisplayName("forward cyclic flies it somewhere at a sane speed")
    void itGoesSomewhere() {
        Rig r = new Rig();
        r.hold(0, -1, 0, 0, 6);
        r.hold(0, 0, 0, 0, 5);
        double x0 = r.h.position.x, z0 = r.h.position.z;
        // Full forward cyclic, feeding in collective to hold height the
        // way a pilot does.
        for (int i = 0; i < 60 * 25; i++) {
            r.hold(0, r.climb() < -1 ? -0.4 : (r.climb() > 1 ? 0.4 : 0), 0, -1, DT);
        }
        double travelled = Math.hypot(r.h.position.x - x0, r.h.position.z - z0);
        assertTrue(travelled > 400,
                "twenty-five seconds of full cyclic covered only "
                        + travelled + " m");
        double kmh = r.speed() * 3.6;
        assertTrue(kmh > 120 && kmh < 330,
                "cruise should be a helicopter's, got " + kmh + " km/h");
        assertTrue(Math.abs(r.alt() - 0) > 5, "it flew into the ground");
    }

    @Test
    @DisplayName("the pedals turn it without the collective running away")
    void pedalsDoNotUpsetTheHeight() {
        Rig r = new Rig();
        r.hold(0, -1, 0, 0, 5);
        r.hold(0, 0, 0, 0, 5);
        double a = r.alt();
        double h0 = r.h.orientation.heading();
        r.hold(1, 0, 0, 0, 4);
        double swept = Math.abs(wrap(r.h.orientation.heading() - h0));
        assertTrue(swept > 1.0,
                "four seconds of full pedal barely turned it: " + swept + " rad");
        assertTrue(Math.abs(r.alt() - a) < 60,
                "working the pedals moved it " + (r.alt() - a) + " m vertically");
    }

    private static double wrap(double a) {
        while (a > Math.PI) { a -= 2 * Math.PI; }
        while (a < -Math.PI) { a += 2 * Math.PI; }
        return a;
    }
}
