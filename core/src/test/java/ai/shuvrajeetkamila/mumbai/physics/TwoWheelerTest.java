package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.math.Vec3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scooter, which was the weakest vehicle in the game.
 *
 * <p>A two-wheeler is modelled single-track: both axles sit on the
 * centreline, which is right, and which also leaves the suspension with no
 * roll stiffness at all. Nothing then stopped the body flopping to the
 * outside of a corner until the contact patch was tilted and the machine
 * was pulling a quarter of a gravity, less than a loaded bus. The rider is
 * now modelled as what she is: the thing that holds the scooter up.
 */
final class TwoWheelerTest {

    private static final CarPhysics.GroundProbe FLAT = new CarPhysics.GroundProbe() {
        @Override public double heightAt(double x, double z) { return 0; }
        @Override public void normalAt(double x, double z, Vec3 out) { out.set(0, 1, 0); }
        @Override public boolean isUnpaved(double x, double z) { return false; }
    };

    private static CarPhysics scooter() {
        CarPhysics c = new CarPhysics();
        c.configure(VehicleKind.SCOOTER);
        c.placeAt(0, 0.8, 0, 0);
        return c;
    }

    /** Drive up to speed, then hold a steady steer and report the corner. */
    private static double[] steadyCorner(CarPhysics c, double steer,
                                         double targetKph) {
        double peakLat = 0;
        double leanAtPeak = 0;
        double sumLat = 0;
        double sumLean = 0;
        int n = 0;
        for (int i = 0; i < 60 * 24; i++) {
            double v = Math.hypot(c.velocity.x, c.velocity.z);
            c.throttle = v < targetKph / 3.6 ? 1.0 : 0.22;
            c.steerInput = i > 60 * 7 ? steer : 0.0;
            c.step(1.0 / 60, FLAT, 0.0);
            if (i > 60 * 14) {
                double lat = Math.abs(c.angularVelocity.y) * v / 9.81;
                sumLat += lat;
                sumLean += c.orientation.roll();
                n++;
                if (lat > peakLat) {
                    peakLat = lat;
                    leanAtPeak = c.orientation.roll();
                }
            }
        }
        return new double[]{peakLat, leanAtPeak, sumLat / Math.max(1, n),
                sumLean / Math.max(1, n)};
    }

    @Test
    @DisplayName("a scooter corners like a scooter, not like a shopping trolley")
    void theScooterCanActuallyCorner() {
        double[] r = steadyCorner(scooter(), 1.0, 55);
        assertTrue(r[0] > 0.55,
                "peak cornering was only " + String.format("%.2f", r[0]) + " g");
        assertTrue(r[0] < 1.25,
                "a scooter pulling " + String.format("%.2f", r[0]) + " g is not a scooter");
    }

    @Test
    @DisplayName("it leans into the corner, not out of it")
    void itLeansTheRightWay() {
        double[] right = steadyCorner(scooter(), 1.0, 50);
        double[] left = steadyCorner(scooter(), -1.0, 50);
        assertTrue(Math.abs(right[3]) > Math.toRadians(8),
                "barely leaned at all: " + Math.toDegrees(right[3]) + " deg");
        assertTrue(right[3] * left[3] < 0,
                "leaned the same way in both directions: "
                        + Math.toDegrees(right[3]) + " and " + Math.toDegrees(left[3]));
        // The direction that matters: into the turn. The body rolls the
        // opposite way from a car, whose springs let it fall outwards.
        CarPhysics car = new CarPhysics();
        car.configure(VehicleKind.SEDAN);
        car.placeAt(0, 0.8, 0, 0);
        double[] carRight = steadyCorner(car, 1.0, 50);
        assertTrue(right[3] * carRight[3] < 0,
                "the scooter rolled the same way as a car's body: scooter "
                        + Math.toDegrees(right[3]) + " deg, car "
                        + Math.toDegrees(carRight[3]) + " deg");
    }

    @Test
    @DisplayName("the lean angle is the one the corner asks for")
    void theLeanMatchesTheCornerItIsPulling() {
        double[] r = steadyCorner(scooter(), 0.65, 45);
        // tan(lean) = a_lat / g is the whole of two-wheeler balance.
        double wanted = Math.atan(r[2]);
        double got = Math.abs(r[3]);
        assertTrue(Math.abs(got - wanted) < Math.toRadians(14),
                "leaning " + String.format("%.1f", Math.toDegrees(got))
                        + " deg for " + String.format("%.2f", r[2])
                        + " g, which asks for "
                        + String.format("%.1f", Math.toDegrees(wanted)) + " deg");
    }

    @Test
    @DisplayName("it does not lie down when it is standing still")
    void itStaysUprightAtRest() {
        CarPhysics c = scooter();
        for (int i = 0; i < 60 * 6; i++) {
            c.steerInput = 1.0;
            c.step(1.0 / 60, FLAT, 0.0);
        }
        assertTrue(Math.abs(Math.toDegrees(c.orientation.roll())) < 6,
                "a parked scooter leaned " + Math.toDegrees(c.orientation.roll())
                        + " deg with the bars on full lock");
    }

    @Test
    @DisplayName("it runs straight and level on a straight road")
    void itRunsLevelInAStraightLine() {
        CarPhysics c = scooter();
        double worst = 0;
        for (int i = 0; i < 60 * 18; i++) {
            c.throttle = 1.0;
            c.steerInput = 0;
            c.step(1.0 / 60, FLAT, 0.0);
            if (i > 60 * 3) {
                worst = Math.max(worst, Math.abs(Math.toDegrees(c.orientation.roll())));
            }
        }
        assertTrue(worst < 9,
                "wandered to " + String.format("%.1f", worst)
                        + " deg of lean going straight ahead");
        assertTrue(c.speedKph > 45,
                "only reached " + String.format("%.0f", c.speedKph) + " km/h");
    }

    @Test
    @DisplayName("the rider does not throw it over on its side")
    void itNeverCapsizes() {
        CarPhysics c = scooter();
        double worst = 0;
        for (int i = 0; i < 60 * 30; i++) {
            c.throttle = 1.0;
            // Thrash it: full lock one way, then the other.
            c.steerInput = ((i / 90) % 2 == 0) ? 1.0 : -1.0;
            c.step(1.0 / 60, FLAT, 0.0);
            worst = Math.max(worst, Math.abs(Math.toDegrees(c.orientation.roll())));
        }
        assertTrue(worst < 52,
                "reached " + String.format("%.0f", worst) + " deg of lean");
        assertTrue(c.position.isFinite() && c.orientation.isFinite(),
                "the integrator came apart");
    }

    @Test
    @DisplayName("the four-wheelers were not changed by any of this")
    void fourWheelersAreUntouched() {
        for (VehicleKind k : VehicleKind.values()) {
            if (k.isTwoWheeler()) { continue; }
            CarPhysics c = new CarPhysics();
            c.configure(k);
            c.placeAt(0, 0.8, 0, 0);
            double[] r = steadyCorner(c, 1.0, 50);
            assertTrue(Math.abs(Math.toDegrees(r[3])) < 12,
                    k + " rolled " + Math.toDegrees(r[3]) + " deg");
            assertTrue(r[0] > 0.45, k + " only pulled " + r[0] + " g");
        }
    }
}
