package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.math.Vec3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the steering does to the driver, as opposed to what it does to
 * the tyres.
 *
 * <p>A front tyre makes its most lateral force at a few degrees of slip
 * and less past that, so at every speed there is a steering angle
 * beyond which more lock means less turn. The rack shed lock linearly
 * with speed, which left that angle well inside the stick's travel:
 * measured on the sedan at a held speed, the yaw rate peaked at four
 * tenths of stick at 50 and 70 km/h and at two tenths at 100, and full
 * stick was about a fifth worse than the peak. Turning in harder made
 * the car wash wider, which is the exact opposite of what a driver
 * expects and is why the controls felt wrong.
 */
final class CarHandlingTest {

    private static final CarPhysics.GroundProbe FLAT =
            new CarPhysics.GroundProbe() {
                @Override public double heightAt(double x, double z) {
                    return 0;
                }
                @Override public void normalAt(double x, double z, Vec3 out) {
                    out.set(0, 1, 0);
                }
                @Override public boolean isUnpaved(double x, double z) {
                    return false;
                }
            };

    private static CarPhysics upToSpeed(VehicleKind k, double kph) {
        CarPhysics c = new CarPhysics();
        c.configure(k);
        c.reset();
        c.position.set(0, k.comHeightM + 0.22, 0);
        for (int i = 0; i < 120; i++) { c.step(1.0 / 60, FLAT, 0); }
        for (int i = 0; i < 3600; i++) {
            c.throttle = 1;
            c.step(1.0 / 60, FLAT, 0);
            if (c.groundSpeed() * 3.6 >= kph) { break; }
        }
        return c;
    }

    /** Mean yaw rate in a steady corner at a held speed, rad/s. */
    private static double steadyYaw(VehicleKind k, double kph, double stick) {
        CarPhysics c = upToSpeed(k, kph);
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 240; i++) {
            double err = kph - c.groundSpeed() * 3.6;
            c.throttle = Math.max(0, Math.min(1, err * 0.08));
            c.brake = Math.max(0, Math.min(1, -err * 0.05));
            c.steerInput = stick;
            c.step(1.0 / 60, FLAT, 0);
            if (i >= 120) { sum += Math.abs(c.angularVelocity.y); n++; }
        }
        return sum / n;
    }

    @Test
    @DisplayName("more steering is more turn, at every speed")
    void theResponseIsMonotonic() {
        for (double kph : new double[]{30, 50, 70, 100, 140}) {
            double prev = -1;
            double peak = 0;
            for (double stick = 0.2; stick <= 0.81; stick += 0.2) {
                double yaw = steadyYaw(VehicleKind.SEDAN, kph, stick);
                assertTrue(yaw > prev,
                        "at " + kph + " km/h, " + stick + " of stick"
                                + " turns the car at " + yaw + " rad/s,"
                                + " which is no more than the " + prev
                                + " a smaller input gives - so turning"
                                + " in harder makes it wash wider");
                prev = yaw;
                peak = Math.max(peak, yaw);
            }
            // And the last fifth of travel must not throw the corner
            // away either. A few per cent of understeer at the stop is
            // right; a fifth of the turn is not.
            double full = steadyYaw(VehicleKind.SEDAN, kph, 1.0);
            assertTrue(full > peak * 0.92,
                    "at " + kph + " km/h full lock gives " + full
                            + " rad/s against a peak of " + peak
                            + ", so the end of the stick is a penalty");
        }
    }

    @Test
    @DisplayName("full lock is available for parking")
    void slowSpeedKeepsTheLock() {
        assertTrue(CarPhysics.steeringLock(0) > 0.99,
                "the wheel is already being wound off at a standstill");
        assertTrue(CarPhysics.steeringLock(5) > 0.99,
                "no full lock at walking pace, so it cannot be parked");
        assertTrue(CarPhysics.steeringLock(40) < 0.2,
                "still " + CarPhysics.steeringLock(40) + " of lock at"
                        + " 144 km/h, which is a spin waiting to happen");
        // Monotonic, or there is a speed where the rack tightens up.
        double prev = 2;
        for (double v = 0; v < 60; v += 1) {
            double f = CarPhysics.steeringLock(v);
            assertTrue(f <= prev + 1e-9,
                    "the lock rose from " + prev + " to " + f
                            + " at " + v + " m/s");
            prev = f;
        }
    }

    @Test
    @DisplayName("the car turns the way the stick is pushed, both ways")
    void steeringIsNotInvertedAndIsSymmetric() {
        CarPhysics right = upToSpeed(VehicleKind.SEDAN, 40);
        double h0 = right.orientation.heading();
        for (int i = 0; i < 120; i++) {
            right.throttle = 0.3;
            right.steerInput = 0.7;
            right.step(1.0 / 60, FLAT, 0);
        }
        double turnedRight = ai.shuvrajeetkamila.mumbai.math.MathUtil
                .wrapPi(right.orientation.heading() - h0);

        CarPhysics left = upToSpeed(VehicleKind.SEDAN, 40);
        double h1 = left.orientation.heading();
        for (int i = 0; i < 120; i++) {
            left.throttle = 0.3;
            left.steerInput = -0.7;
            left.step(1.0 / 60, FLAT, 0);
        }
        double turnedLeft = ai.shuvrajeetkamila.mumbai.math.MathUtil
                .wrapPi(left.orientation.heading() - h1);

        assertTrue(turnedRight * turnedLeft < 0,
                "both stick directions turned the car the same way");
        assertTrue(Math.abs(Math.abs(turnedRight) - Math.abs(turnedLeft))
                        < Math.abs(turnedRight) * 0.12,
                "the car turns " + Math.abs(turnedRight) + " rad one way"
                        + " and " + Math.abs(turnedLeft) + " the other");
        assertTrue(Math.abs(turnedRight) > 0.3,
                "two seconds of seven tenths stick at 40 km/h moved the"
                        + " heading by " + turnedRight + " rad");
    }

    @Test
    @DisplayName("every vehicle gets the same treatment")
    void theRackIsNotJustForTheSedan() {
        for (VehicleKind k : new VehicleKind[]{VehicleKind.KAALI_PEELI,
                                               VehicleKind.AUTORICKSHAW,
                                               VehicleKind.HATCHBACK}) {
            double half = steadyYaw(k, 45, 0.5);
            double full = steadyYaw(k, 45, 1.0);
            assertTrue(full > half,
                    k + " turns at " + full + " rad/s on full lock"
                            + " against " + half + " on half, so its"
                            + " steering still fights the driver");
        }
    }
}
