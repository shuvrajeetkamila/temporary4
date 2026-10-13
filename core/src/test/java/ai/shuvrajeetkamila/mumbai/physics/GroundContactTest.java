package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.math.Vec3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ground is allowed to stop you. It is not allowed to throw you.
 *
 * <p>Ground contact was a plain spring fed the whole overlap, so the
 * deeper you were the harder it pushed. One metre of overlap threw the
 * player eight metres into the air, three metres threw her thirty-five,
 * and twelve metres threw her four hundred and she was still climbing
 * twelve seconds later. Anything that can bury a body - a hard landing in
 * a single step, a teleport onto a slope, a rebase, a wall - was a
 * trampoline.
 */
final class GroundContactTest {

    private static final HumanPhysics.Terrain FLAT = new HumanPhysics.Terrain() {
        @Override public double heightAt(double x, double z) { return 0; }
        @Override public boolean isWater(double x, double z) { return false; }
        @Override public double slopeAt(double x, double z) { return 0; }
    };

    private static final CarPhysics.GroundProbe CAR_FLAT =
            new CarPhysics.GroundProbe() {
                @Override public double heightAt(double x, double z) { return 0; }
                @Override public void normalAt(double x, double z, Vec3 out) {
                    out.set(0, 1, 0);
                }
                @Override public boolean isUnpaved(double x, double z) { return false; }
            };

    /** Drop a body in at {@code y} and report the highest it ever gets. */
    private static double[] settle(double y, int seconds) {
        HumanPhysics h = new HumanPhysics();
        h.position.set(0, y, 0);
        Ocean sea = new Ocean();
        double peak = -1e9;
        for (int i = 0; i < 60 * seconds; i++) {
            h.step(1.0 / 60, FLAT, sea);
            peak = Math.max(peak, h.position.y);
        }
        return new double[]{peak, h.position.y, h.velocity.y};
    }

    @Test
    @DisplayName("being buried does not launch you")
    void buriedBodiesAreNotLaunched() {
        for (double depth : new double[]{0.3, 1.0, 3.0, 6.0, 12.0}) {
            double[] r = settle(-depth, 20);
            assertTrue(r[0] < 2.0,
                    "buried " + depth + " m and thrown to " + r[0] + " m");
        }
    }

    @Test
    @DisplayName("but you do come back up, at a walking pace")
    void buriedBodiesComeBackUp() {
        for (double depth : new double[]{1.0, 6.0, 12.0}) {
            double[] r = settle(-depth, 25);
            assertTrue(r[1] > 0.3,
                    "still " + r[1] + " m down after 25 s from " + depth + " m");
            assertTrue(Math.abs(r[2]) < 0.1,
                    "never settled: " + r[2] + " m/s from " + depth + " m");
        }
    }

    @Test
    @DisplayName("standing on the ground is exactly as it was")
    void restingHeightIsUnchanged() {
        double[] r = settle(1.4, 8);
        assertTrue(r[1] > 0.70 && r[1] < 0.95,
                "resting at " + r[1] + " m, which is not standing on the floor");
        assertTrue(r[0] < 1.45, "bounced up to " + r[0] + " m from a 1.4 m drop");
    }

    @Test
    @DisplayName("a long fall still lands rather than bouncing")
    void aLongFallLands() {
        for (double from : new double[]{8, 30, 90}) {
            double[] r = settle(from, 25);
            assertTrue(r[1] > 0.3 && r[1] < 2.0,
                    "fell from " + from + " m and ended at " + r[1]);
            assertTrue(Math.abs(r[2]) < 0.6,
                    "fell from " + from + " m and is still moving at " + r[2]);
        }
    }

    @Test
    @DisplayName("hard landings still decelerate in one step, not over several")
    void aHardLandingIsStillHard() {
        HumanPhysics h = new HumanPhysics();
        h.position.set(0, 40, 0);
        Ocean sea = new Ocean();
        double worstDown = 0;
        double deepest = 0;
        for (int i = 0; i < 60 * 12; i++) {
            h.step(1.0 / 60, FLAT, sea);
            worstDown = Math.min(worstDown, h.velocity.y);
            deepest = Math.min(deepest, h.position.y);
        }
        assertTrue(worstDown < -15, "never actually fell: " + worstDown + " m/s");
        // The clamp must not let a fast arrival tunnel metres into the floor.
        assertTrue(deepest > -1.2,
                "sank " + (-deepest) + " m into the ground on landing");
    }

    @Test
    @DisplayName("the car was never a trampoline and still is not")
    void carsAreNotLaunchedEither() {
        for (double depth : new double[]{0.5, 3.0, 8.0}) {
            CarPhysics c = new CarPhysics();
            c.configure(VehicleKind.SEDAN);
            c.placeAt(0, -depth, 0, 0);
            double peak = -1e9;
            for (int i = 0; i < 60 * 12; i++) {
                c.step(1.0 / 60, CAR_FLAT, 0.0);
                peak = Math.max(peak, c.position.y);
            }
            assertTrue(peak < 3.0,
                    "a car buried " + depth + " m reached " + peak + " m");
        }
    }

    @Test
    @DisplayName("a car dropped from a height does not bounce back up")
    void carsDoNotBounce() {
        for (double from : new double[]{6, 25, 70}) {
            CarPhysics c = new CarPhysics();
            c.configure(VehicleKind.SEDAN);
            c.placeAt(0, from, 0, 0);
            boolean landed = false;
            double after = -1e9;
            for (int i = 0; i < 60 * 25; i++) {
                c.step(1.0 / 60, CAR_FLAT, 0.0);
                if (!landed && c.anyWheelGrounded) { landed = true; }
                if (landed) { after = Math.max(after, c.position.y); }
            }
            assertTrue(landed, "dropped from " + from + " m and never landed");
            assertTrue(after < 2.5,
                    "dropped from " + from + " m and bounced to " + after);
        }
    }
}
