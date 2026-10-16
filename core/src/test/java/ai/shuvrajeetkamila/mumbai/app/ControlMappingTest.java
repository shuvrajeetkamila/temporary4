package ai.shuvrajeetkamila.mumbai.app;

import ai.shuvrajeetkamila.mumbai.physics.HelicopterPhysics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The on-screen controls, for every one of the five things you can be.
 *
 * <p>This exists because two mappings silently collided for a long time:
 * on foot one button was both RUN and JUMP, so setting off at a run
 * always launched you into the air, and in the aeroplane one button was
 * both GEAR and the wheel brakes. Neither showed up anywhere, because
 * nothing had ever tested the input layer.
 */
final class ControlMappingTest {

    private static final ControlProfile[] ALL = {
            ControlProfile.ON_FOOT, ControlProfile.CAR, ControlProfile.BOAT,
            ControlProfile.HELICOPTER, ControlProfile.AEROPLANE,
    };

    private boolean[] held() { return new boolean[ControlMapping.ACT_COUNT]; }

    private InputState run(ControlProfile p, double lx, double ly,
                           double rx, double ry, boolean[] h, boolean[] e) {
        InputState in = new InputState();
        ControlMapping.apply(p, in, lx, ly, rx, ry, h, e);
        return in;
    }

    // --------------------------------------------------------- the button set

    @Test
    @DisplayName("every profile's buttons fit on screen and are all labelled")
    void buttonSetsAreSane() {
        for (ControlProfile p : ALL) {
            int[] acts = ControlMapping.actionsFor(p);
            assertTrue(acts.length <= ControlMapping.MAX_SLOTS,
                    p + " asks for " + acts.length + " buttons, only "
                            + ControlMapping.MAX_SLOTS + " fit");
            assertTrue(acts.length > 0, p + " has no buttons at all");
            for (int a : acts) {
                assertTrue(!ControlMapping.labelFor(a).isEmpty(),
                        p + " has an unlabelled button, action " + a);
                assertTrue(a != ControlMapping.ACT_NONE,
                        p + " has a dead button slot");
            }
        }
    }

    @Test
    @DisplayName("no button appears twice on the same profile")
    void noDuplicateButtons() {
        for (ControlProfile p : ALL) {
            Set<Integer> seen = new HashSet<>();
            for (int a : ControlMapping.actionsFor(p)) {
                assertTrue(seen.add(a),
                        p + " shows " + ControlMapping.labelFor(a) + " twice");
            }
        }
    }

    @Test
    @DisplayName("you can always get out, and always get back to the camera")
    void everyProfileCanBeLeft() {
        for (ControlProfile p : ALL) {
            Set<Integer> acts = new HashSet<>();
            for (int a : ControlMapping.actionsFor(p)) { acts.add(a); }
            // The enter/exit button used to be drawn as an en dash in the
            // boat, helicopter and aeroplane: it worked, but nothing told
            // the player it was there.
            assertTrue(acts.contains(ControlMapping.ACT_ENTER),
                    p + " has no visible way in or out");
            assertTrue(acts.contains(ControlMapping.ACT_CAM),
                    p + " cannot change camera");
            assertTrue(acts.contains(ControlMapping.ACT_SWAP),
                    p + " cannot switch to another vehicle type");
        }
    }

    @Test
    @DisplayName("each profile shows the controls it actually needs")
    void profilesExposeTheirOwnControls() {
        assertHas(ControlProfile.CAR, ControlMapping.ACT_HANDBRAKE,
                ControlMapping.ACT_REVERSE);
        assertHas(ControlProfile.HELICOPTER, ControlMapping.ACT_ENGINE);
        // Flaps must go both ways. They could only ever be extended, so
        // once you had them out for take-off you flew the rest of the
        // sortie with them hanging there.
        assertHas(ControlProfile.AEROPLANE, ControlMapping.ACT_FLAP_DOWN,
                ControlMapping.ACT_FLAP_UP, ControlMapping.ACT_GEAR,
                ControlMapping.ACT_WHEEL_BRAKE);
        assertHas(ControlProfile.ON_FOOT, ControlMapping.ACT_JUMP,
                ControlMapping.ACT_SPRINT, ControlMapping.ACT_BATON);
    }

    private void assertHas(ControlProfile p, int... wanted) {
        Set<Integer> acts = new HashSet<>();
        for (int a : ControlMapping.actionsFor(p)) { acts.add(a); }
        for (int w : wanted) {
            assertTrue(acts.contains(w),
                    p + " is missing " + ControlMapping.labelFor(w));
        }
    }

    // ------------------------------------------------------------- on foot

    @Test
    @DisplayName("the run button does not also jump")
    void runningDoesNotJump() {
        boolean[] h = held(), e = held();
        // Push the stick right forward: that is a run.
        InputState in = run(ControlProfile.ON_FOOT, 0, -1, 0, 0, h, e);
        assertTrue(in.run, "a fully pushed stick should be a run");
        assertTrue(!in.jump,
                "running launched the player into the air, which is the bug "
                        + "that came of sharing one button between the two");

        // And jump is its own button.
        e[ControlMapping.ACT_JUMP] = true;
        in = run(ControlProfile.ON_FOOT, 0, 0, 0, 0, h, e);
        assertTrue(in.jump, "the jump button did not jump");
        assertTrue(!in.run, "jumping on the spot should not be a run");
    }

    @Test
    @DisplayName("how far the stick goes decides walking or running")
    void stickDeflectionPicksThePace() {
        boolean[] h = held(), e = held();
        assertTrue(!run(ControlProfile.ON_FOOT, 0, -0.3, 0, 0, h, e).run,
                "a gentle push should be a walk");
        assertTrue(run(ControlProfile.ON_FOOT, 0, -0.95, 0, 0, h, e).run,
                "a firm push should be a run");
        // Diagonals count their full length, not just one axis.
        assertTrue(run(ControlProfile.ON_FOOT, -0.7, -0.7, 0, 0, h, e).run,
                "a full diagonal should be a run too");
    }

    @Test
    @DisplayName("sprint is held, jump and baton fire once")
    void heldAndEdgeControlsBehave() {
        boolean[] h = held(), e = held();
        h[ControlMapping.ACT_SPRINT] = true;
        e[ControlMapping.ACT_JUMP] = true;
        e[ControlMapping.ACT_BATON] = true;
        InputState in = run(ControlProfile.ON_FOOT, 0, 0, 0, 0, h, e);
        assertTrue(in.sprint && in.jump && in.attack);

        // Next frame the edges have been consumed but the hold remains.
        boolean[] none = held();
        in = run(ControlProfile.ON_FOOT, 0, 0, 0, 0, h, none);
        assertTrue(in.sprint, "sprint should stay on while held");
        assertTrue(!in.jump, "jump repeated without a new press");
        assertTrue(!in.attack, "the baton swung twice off one press");
    }

    // ---------------------------------------------------------------- car

    @Test
    @DisplayName("the car stick is throttle up, brake down, and never both")
    void carThrottleAndBrakeAreExclusive() {
        boolean[] h = held(), e = held();
        for (double ly = -1; ly <= 1.0001; ly += 0.1) {
            InputState in = run(ControlProfile.CAR, 0, ly, 0, 0, h, e);
            assertTrue(in.throttle >= 0 && in.throttle <= 1,
                    "throttle out of range: " + in.throttle);
            assertTrue(in.brake >= 0 && in.brake <= 1,
                    "brake out of range: " + in.brake);
            assertTrue(in.throttle == 0 || in.brake == 0,
                    "throttle and brake applied together at ly=" + ly);
        }
        assertEquals(1.0, run(ControlProfile.CAR, 0, -1, 0, 0, h, e).throttle, 1e-9);
        assertEquals(1.0, run(ControlProfile.CAR, 0, 1, 0, 0, h, e).brake, 1e-9);
        assertEquals(0.0, run(ControlProfile.CAR, 0, 0, 0, 0, h, e).throttle, 1e-9);
    }

    @Test
    @DisplayName("reverse only engages from a standstill")
    void reverseNeedsTheStickNeutral() {
        boolean[] h = held(), e = held();
        h[ControlMapping.ACT_REVERSE] = true;
        assertTrue(run(ControlProfile.CAR, 0, 0, 0, 0, h, e).reverse,
                "reverse should engage with the stick centred");
        assertTrue(!run(ControlProfile.CAR, 0, -0.9, 0, 0, h, e).reverse,
                "reverse engaged at full throttle");
        assertTrue(!run(ControlProfile.CAR, 0, 0.9, 0, 0, h, e).reverse,
                "reverse engaged under braking");
    }

    @Test
    @DisplayName("steering is the right stick and is signed the obvious way")
    void steeringIsSigned() {
        boolean[] h = held(), e = held();
        assertTrue(run(ControlProfile.CAR, 0, 0, 1, 0, h, e).steer > 0,
                "pushing the stick right should steer right");
        assertTrue(run(ControlProfile.CAR, 0, 0, -1, 0, h, e).steer < 0,
                "pushing the stick left should steer left");
        assertEquals(0.0, run(ControlProfile.CAR, 0, 0, 0, 0, h, e).steer, 1e-9);
    }

    // --------------------------------------------------------------- boat

    @Test
    @DisplayName("a boat goes astern rather than braking")
    void boatHasAstern() {
        boolean[] h = held(), e = held();
        InputState ahead = run(ControlProfile.BOAT, 0, -1, 0, 0, h, e);
        InputState astern = run(ControlProfile.BOAT, 0, 1, 0, 0, h, e);
        // The engine turns throttle minus brake into a signed demand, so
        // what matters is that pulling back produces the opposite sign.
        assertTrue(ahead.throttle - ahead.brake > 0.9, "no ahead thrust");
        assertTrue(astern.throttle - astern.brake < -0.9, "no astern thrust");
    }

    // --------------------------------------------------------- helicopter

    @Test
    @DisplayName("the collective is a lever, and it spans its whole range")
    void collectiveSpansItsRange() {
        // It used to be the stick itself: centred meant half collective
        // and letting go meant half collective. Half is below a hover,
        // so taking a thumb off the stick - which you must do, because
        // the other thumb is busy with the cyclic - started a descent
        // at four metres a second. A real collective is a lever that
        // stays where it is put, and so is this one.
        boolean[] h = held(), e = held();
        InputState in = new InputState();

        // On the GROUND setting, not the hover one. A parked helicopter
        // is in ground effect, where the same blade pitch makes about a
        // quarter more thrust, so a lever resting at the hover value
        // floated the machine off the pad and up to seventeen metres
        // with nobody touching the controls.
        assertEquals(HelicopterPhysics.GROUND_COLLECTIVE, in.collectiveLever,
                1e-9, "a fresh machine does not start on the ground setting");
        assertTrue(HelicopterPhysics.GROUND_COLLECTIVE
                        < HelicopterPhysics.HOVER_COLLECTIVE,
                "the parked setting is not below the hover");

        // Hold the stick up for a second and it must reach the stop.
        for (int i = 0; i < 120; i++) {
            ControlMapping.apply(ControlProfile.HELICOPTER, in, 0, -1, 0, 0,
                    h, e, 1.0 / 60.0);
        }
        assertEquals(1.0, in.collective, 1e-9,
                "two seconds of full up stick did not reach full"
                        + " collective");

        // And it stays there when the stick is released.
        for (int i = 0; i < 60; i++) {
            ControlMapping.apply(ControlProfile.HELICOPTER, in, 0, 0, 0, 0,
                    h, e, 1.0 / 60.0);
        }
        assertEquals(1.0, in.collective, 1e-9,
                "the lever sprang back when the thumb came off, which is"
                        + " the whole bug this replaced");

        for (int i = 0; i < 120; i++) {
            ControlMapping.apply(ControlProfile.HELICOPTER, in, 0, 1, 0, 0,
                    h, e, 1.0 / 60.0);
        }
        assertEquals(0.0, in.collective, 1e-9,
                "two seconds of full down stick did not reach flat pitch");

        // Never outside its range, whatever is done to it.
        for (double ly = -1; ly <= 1.0001; ly += 0.05) {
            for (int i = 0; i < 40; i++) {
                ControlMapping.apply(ControlProfile.HELICOPTER, in, 0, ly,
                        0, 0, h, e, 1.0 / 60.0);
                assertTrue(in.collective >= 0 && in.collective <= 1,
                        "collective left its range: " + in.collective);
            }
        }

        // Changing seat puts it back on the ground setting, or the
        // next helicopter inherits wherever the last one's lever was
        // left - and the one you are climbing into is parked.
        in.resetLevers();
        assertEquals(HelicopterPhysics.GROUND_COLLECTIVE, in.collectiveLever,
                1e-9, "the lever survived a change of vehicle");
    }

    @Test
    @DisplayName("pedals are the left stick, cyclic is the right")
    void helicopterAxesAreSeparate() {
        boolean[] h = held(), e = held();
        InputState in = run(ControlProfile.HELICOPTER, 0.8, 0, -0.6, 0.4, h, e);
        assertEquals(0.8, in.pedal, 1e-9, "left stick is not the pedals");
        assertEquals(-0.6, in.lookX, 1e-9, "right stick is not the cyclic");
        assertEquals(0.4, in.lookY, 1e-9);
    }

    // ----------------------------------------------------------- aeroplane

    @Test
    @DisplayName("flaps go both ways and the gear is its own button")
    void flapsAndGearAreIndependent() {
        boolean[] h = held(), e = held();

        e[ControlMapping.ACT_FLAP_DOWN] = true;
        InputState in = run(ControlProfile.AEROPLANE, 0, 0, 0, 0, h, e);
        assertTrue(in.flapsDown && !in.flapsUp, "FLAP+ did not extend flaps");
        assertTrue(!in.gearToggle, "extending flaps also moved the gear");

        e = held();
        e[ControlMapping.ACT_FLAP_UP] = true;
        in = run(ControlProfile.AEROPLANE, 0, 0, 0, 0, h, e);
        assertTrue(in.flapsUp && !in.flapsDown, "FLAP- did not retract flaps");

        e = held();
        e[ControlMapping.ACT_GEAR] = true;
        in = run(ControlProfile.AEROPLANE, 0, 0, 0, 0, h, e);
        assertTrue(in.gearToggle, "the gear button did nothing");
        // This is the collision that was live: the gear button also held
        // the wheel brakes on, so raising the gear braked the aeroplane.
        assertEquals(0.0, in.brake, 1e-9,
                "moving the gear also applied the wheel brakes");
    }

    @Test
    @DisplayName("wheel brakes are held, not toggled, and are their own button")
    void wheelBrakesAreHeld() {
        boolean[] h = held(), e = held();
        h[ControlMapping.ACT_WHEEL_BRAKE] = true;
        InputState in = run(ControlProfile.AEROPLANE, 0, 0, 0, 0, h, e);
        assertEquals(1.0, in.brake, 1e-9, "the brake button did not brake");
        assertTrue(!in.gearToggle, "braking also moved the gear");
        assertTrue(!in.flapsDown && !in.flapsUp, "braking also moved the flaps");
    }

    @Test
    @DisplayName("aeroplane throttle spans idle to full and never leaves it")
    void planeThrottleSpansItsRange() {
        boolean[] h = held(), e = held();
        assertEquals(1.0, run(ControlProfile.AEROPLANE, 0, -1, 0, 0, h, e)
                .throttle, 1e-9);
        assertEquals(0.0, run(ControlProfile.AEROPLANE, 0, 1, 0, 0, h, e)
                .throttle, 1e-9);
        for (double ly = -1; ly <= 1.0001; ly += 0.05) {
            double t = run(ControlProfile.AEROPLANE, 0, ly, 0, 0, h, e).throttle;
            assertTrue(t >= 0 && t <= 1, "throttle left its range: " + t);
        }
    }

    // ------------------------------------------------------------- hygiene

    @Test
    @DisplayName("no control is left applied when you change vehicle")
    void resetClearsEverything() {
        InputState in = new InputState();
        boolean[] h = held(), e = held();
        h[ControlMapping.ACT_HANDBRAKE] = true;
        ControlMapping.apply(ControlProfile.CAR, in, 0, -1, 0.7, 0, h, e);
        assertTrue(in.throttle > 0 && in.handbrake);

        in.reset();
        assertEquals(0.0, in.throttle, 1e-9, "throttle survived the reset");
        assertEquals(0.0, in.brake, 1e-9);
        assertEquals(0.0, in.steer, 1e-9);
        assertEquals(0.0, in.collective, 1e-9);
        assertEquals(0.0, in.pedal, 1e-9);
        assertTrue(!in.handbrake && !in.reverse && !in.run && !in.sprint);
        assertTrue(!in.jump && !in.enterExit && !in.attack && !in.gearToggle);
        assertTrue(!in.flapsUp && !in.flapsDown && !in.engineToggle);
    }

    @Test
    @DisplayName("every profile has a legend saying what the left stick does")
    void stickLegendsExist() {
        Set<String> seen = new HashSet<>();
        for (ControlProfile p : ALL) {
            String s = ControlMapping.stickLegend(p);
            assertTrue(s != null && !s.isEmpty(), p + " has no stick legend");
            seen.add(s);
        }
        // A helicopter's collective is not a car's accelerator; if two
        // profiles claim the same thing one of them is lying.
        assertTrue(seen.size() >= 4,
                "the stick legends do not distinguish the vehicles: " + seen);
    }
}
