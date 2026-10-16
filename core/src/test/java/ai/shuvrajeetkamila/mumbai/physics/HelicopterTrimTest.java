package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.math.MathUtil;
import ai.shuvrajeetkamila.mumbai.math.Vec3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Can the helicopter actually be flown.
 *
 * <p>Every one of these reproduces something the aircraft did before
 * this round. It was not flyable: three separate faults, two of them
 * sign errors that hid each other and one a missing term that no
 * amount of tuning could have substituted for.
 *
 * <ul>
 *   <li>The disc tilt was inverted against the pitching moment, so the
 *       thrust pushed one way while the nose went the other and the two
 *       fought to a feeble 20 km/h.</li>
 *   <li>The roll moment was inverted, so the stick banked the machine
 *       away from the direction it was translating.</li>
 *   <li>There was no term proportional to attitude at all. Flapback
 *       alone closes the loop through speed, which is an integral, and
 *       a third-order loop missing its first-order term is unstable at
 *       any gain. The aircraft tumbled between seventy degrees nose
 *       down and seventy nose up at any steady stick position.</li>
 * </ul>
 */
final class HelicopterTrimTest {

    private static final HelicopterPhysics.GroundProbe FLAT = (x, z) -> 0.0;

    private static HelicopterPhysics atAltitude() {
        HelicopterPhysics h = new HelicopterPhysics();
        h.reset();
        h.position.set(0, 3000, 0);
        h.collective = HelicopterPhysics.HOVER_COLLECTIVE;
        return h;
    }

    private static void fly(HelicopterPhysics h, double seconds) {
        int n = (int) Math.round(seconds * 60);
        for (int i = 0; i < n; i++) { h.step(1.0 / 60.0, FLAT, 0, 0); }
    }

    /** Nose elevation in degrees, positive nose up. */
    private static double noseDeg(HelicopterPhysics h) {
        Vec3 f = new Vec3();
        h.orientation.forward(f);
        return Math.toDegrees(Math.asin(MathUtil.clamp(f.y, -1, 1)));
    }

    /** Speed along the nose, m/s. Negative is backwards. */
    private static double alongNose(HelicopterPhysics h) {
        Vec3 f = new Vec3();
        h.orientation.forward(f);
        return h.velocity.x * f.x + h.velocity.z * f.z;
    }

    @Test
    @DisplayName("the collective rests where the machine hovers")
    void theRestingCollectiveIsAHover() {
        // The control is a lever and it sits at HOVER_COLLECTIVE when
        // nobody is touching it. If that number is not actually a hover
        // then letting go of the stick starts a climb or a descent, and
        // before this it started a descent at four metres a second.
        // Low, because HOVER_COLLECTIVE is the sea-level figure and
        // the air at 3,000 m is a quarter thinner - but still well
        // clear of the ground, whose cushion reaches about two rotor
        // diameters and would flatter the number.
        HelicopterPhysics h = new HelicopterPhysics();
        h.reset();
        h.position.set(0, 200, 0);
        h.collective = HelicopterPhysics.HOVER_COLLECTIVE;
        fly(h, 12);
        assertTrue(Math.abs(h.velocity.y) < 0.9,
                "at the resting collective the machine is going "
                        + h.velocity.y + " m/s vertically, so taking your"
                        + " thumb off the stick does not hold height");
        assertTrue(Math.abs(h.position.y - 200) < 12,
                "it drifted " + (h.position.y - 200) + " m in twelve"
                        + " seconds of nobody touching anything");
    }

    @Test
    @DisplayName("forward stick drops the nose and flies forward")
    void thePitchAxisIsNotInverted() {
        // cyclicPitch is the screen axis: negative is the stick pushed
        // away from you.
        HelicopterPhysics fwd = atAltitude();
        fwd.cyclicPitch = -0.4;
        fly(fwd, 0.5);
        assertTrue(noseDeg(fwd) < -1.0,
                "pushing the stick forward raised the nose to "
                        + noseDeg(fwd) + " degrees");
        fly(fwd, 20);
        assertTrue(alongNose(fwd) > 8.0,
                "twenty seconds of forward stick and the machine is"
                        + " doing " + alongNose(fwd) + " m/s along its"
                        + " own nose");

        HelicopterPhysics back = atAltitude();
        back.cyclicPitch = 0.4;
        fly(back, 0.5);
        assertTrue(noseDeg(back) > 1.0,
                "pulling back dropped the nose to " + noseDeg(back));
    }

    @Test
    @DisplayName("right stick banks right and translates right")
    void theRollAxisIsNotInverted() {
        HelicopterPhysics h = atAltitude();
        h.cyclicRoll = 0.6;
        fly(h, 40);
        Vec3 r = new Vec3();
        h.orientation.right(r);
        assertTrue(r.y < -0.03,
                "stick right left the right-hand side " + r.y
                        + " above level, so it is banking the wrong way");
        double outRight = h.velocity.x * r.x + h.velocity.z * r.z;
        assertTrue(outRight > 1.0,
                "stick right is translating at " + outRight
                        + " m/s out of the right-hand door, so the bank"
                        + " and the drift disagree");
        // The thrust has to be leaning the same way as the bank, or the
        // two are describing different aircraft.
        Vec3 u = new Vec3();
        h.orientation.up(u);
        assertTrue(u.x * r.x + u.z * r.z > 0.03,
                "banked right but the rotor is pulling left");
    }

    @Test
    @DisplayName("a steady stick settles instead of tumbling")
    void attitudeIsStable() {
        // This is the one that made the aircraft unusable. With no term
        // proportional to attitude the pitch loop had no first-order
        // feedback and diverged: measured at seventy degrees nose down
        // one moment and seventy nose up fifteen seconds later, at a
        // stick position that should hold a gentle cruise.
        for (double stick : new double[]{-0.3, -0.6, -1.0, 0.5}) {
            HelicopterPhysics h = atAltitude();
            h.cyclicPitch = stick;
            fly(h, 25);
            double before = alongNose(h);
            double worstNose = 0;
            for (int i = 0; i < 35 * 60; i++) {
                h.step(1.0 / 60.0, FLAT, 0, 0);
                worstNose = Math.max(worstNose, Math.abs(noseDeg(h)));
            }
            assertTrue(worstNose < 30.0,
                    "at a steady stick of " + stick + " the nose reached "
                            + worstNose + " degrees; it is tumbling, not"
                            + " flying");
            assertTrue(Math.abs(alongNose(h) - before) < 14.0,
                    "at a steady stick of " + stick + " the speed moved "
                            + (alongNose(h) - before) + " m/s over the"
                            + " last thirty-five seconds, so it has not"
                            + " trimmed");
        }
    }

    @Test
    @DisplayName("a nudge is recovered from, not amplified")
    void disturbancesDecay() {
        HelicopterPhysics h = atAltitude();
        h.cyclicPitch = 0.35;
        fly(h, 1.0);
        h.cyclicPitch = 0;
        fly(h, 6.0);
        double rate = Math.hypot(h.angularVelocity.x, h.angularVelocity.z);
        assertTrue(rate < 0.05,
                "six seconds after the stick was centred the machine is"
                        + " still pitching at " + rate + " rad/s");
        assertTrue(Math.abs(noseDeg(h)) < 6.0,
                "it settled " + noseDeg(h) + " degrees off level");
    }

    @Test
    @DisplayName("more stick is more speed, up to a sane maximum")
    void cyclicPositionIsATrimSpeed() {
        double last = 0;
        for (double stick : new double[]{-0.25, -0.5, -0.75, -1.0}) {
            HelicopterPhysics h = atAltitude();
            h.cyclicPitch = stick;
            fly(h, 60);
            double v = alongNose(h);
            assertTrue(v > last,
                    "stick " + stick + " trims at " + v + " m/s, no"
                            + " faster than the gentler " + last);
            last = v;
        }
        assertTrue(last > 40 && last < 95,
                "full forward cyclic trims at " + (last * 3.6)
                        + " km/h, which is not a light helicopter");
    }

    @Test
    @DisplayName("it can be flown off a pad and brought back down")
    void itLeavesTheGroundAndReturns() {
        HelicopterPhysics h = new HelicopterPhysics();
        h.reset();
        h.position.set(0, 0.9, 0);
        for (int i = 0; i < 60 * 8; i++) {
            h.collective = 0.95;
            h.step(1.0 / 60.0, FLAT, 0, 0);
        }
        assertTrue(h.position.y > 25,
                "eight seconds at nearly full collective and it is only "
                        + h.position.y + " m up");
        // Back down under control. The lever has to come properly down
        // to descend - a few hundredths below the hover is a few tenths
        // of a metre a second, which is trimming, not landing.
        for (int i = 0; i < 60 * 30; i++) {
            h.collective = HelicopterPhysics.GROUND_COLLECTIVE;
            h.step(1.0 / 60.0, FLAT, 0, 0);
        }
        assertTrue(h.position.y < 25,
                "it would not come back down");
        assertTrue(h.velocity.y > -12,
                "it arrived at " + h.velocity.y + " m/s, which is not a"
                        + " landing");
    }
}
