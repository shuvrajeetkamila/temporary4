package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.math.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The car has to behave like a car, not like a box being pushed. */
class CarPhysicsTest {

    /** Dead flat tarmac at y = 0. */
    private static CarPhysics.GroundProbe flat() {
        return new CarPhysics.GroundProbe() {
            @Override public double heightAt(double x, double z) { return 0; }
            @Override public void normalAt(double x, double z, Vec3 out) {
                out.set(0, 1, 0);
            }
            @Override public boolean isUnpaved(double x, double z) { return false; }
        };
    }

    private static CarPhysics onGround() {
        CarPhysics c = new CarPhysics();
        c.position.set(0, 0.62, 0);
        // Let it settle onto its springs first, or the first measurement is
        // taken while the suspension is still ringing.
        for (int i = 0; i < 240; i++) { c.step(1.0 / 60, flat(), 0); }
        return c;
    }

    @Test
    @DisplayName("parked car settles on its springs and stays put")
    void settles() {
        CarPhysics c = onGround();
        assertTrue(c.position.y > 0.2 && c.position.y < 1.2,
                "ride height should be sane, was " + c.position.y);
        assertTrue(c.groundSpeed() < 0.05,
                "a parked car should not drift, was " + c.groundSpeed());
        for (CarPhysics.Wheel w : c.wheels) {
            assertTrue(w.load > 1000 && w.load < 6000,
                    "each corner carries roughly a quarter of 1340 kg, was " + w.load);
        }
    }

    @Test
    @DisplayName("accelerates and reaches a terminal speed rather than infinity")
    void accelerates() {
        CarPhysics c = onGround();
        c.throttle = 1.0;
        double after5s = 0;
        for (int i = 0; i < 60 * 60; i++) {
            c.step(1.0 / 60, flat(), 0);
            if (i == 5 * 60) { after5s = c.groundSpeed(); }
        }
        // A 1340 kg car with 196 N m should see roughly 60-100 km/h in 5 s.
        assertTrue(after5s > 13 && after5s < 32,
                "5 s speed should be 47-115 km/h, was " + after5s * 3.6 + " km/h");
        double top = c.groundSpeed() * 3.6;
        assertTrue(top > 110 && top < 260,
                "drag must impose a terminal speed, got " + top + " km/h");
        assertTrue(c.gear >= 4, "it should have changed up, gear " + c.gear);
    }

    @Test
    @DisplayName("braking distance from 100 km/h is physically plausible")
    void braking() {
        CarPhysics c = onGround();
        c.throttle = 1.0;
        for (int i = 0; i < 60 * 45 && c.groundSpeed() < 27.8; i++) {
            c.step(1.0 / 60, flat(), 0);
        }
        double v0 = c.groundSpeed();
        double x0 = c.position.x, z0 = c.position.z;
        c.throttle = 0;
        c.brake = 1.0;
        for (int i = 0; i < 60 * 15 && c.groundSpeed() > 0.4; i++) {
            c.step(1.0 / 60, flat(), 0);
        }
        double d = Math.hypot(c.position.x - x0, c.position.z - z0);
        // mu around 1.18 gives roughly v^2 / (2 mu g) = 33 m from 100 km/h.
        // Allow a generous band but reject anything absurd in either direction.
        assertTrue(d > 20 && d < 85,
                "stopping from " + v0 * 3.6 + " km/h took " + d + " m");
    }

    @Test
    @DisplayName("wet tarmac lengthens the stop, and the model says so up front")
    void wetGrip() {
        assertTrue(CarPhysics.surfaceGrip(false, 1.0) < CarPhysics.surfaceGrip(false, 0.0),
                "rain must reduce grip");
        assertTrue(CarPhysics.surfaceGrip(true, 0.0) < CarPhysics.surfaceGrip(false, 0.0),
                "dirt must offer less grip than tarmac");

        double dry = stopDistance(0.0);
        double wet = stopDistance(1.0);
        assertTrue(wet > dry * 1.08,
                "wet stop " + wet + " m should clearly exceed dry " + dry + " m");
    }

    private double stopDistance(double wetness) {
        CarPhysics c = onGround();
        c.throttle = 1.0;
        for (int i = 0; i < 60 * 45 && c.groundSpeed() < 22.0; i++) {
            c.step(1.0 / 60, flat(), wetness);
        }
        double x0 = c.position.x, z0 = c.position.z;
        c.throttle = 0;
        c.brake = 1.0;
        for (int i = 0; i < 60 * 20 && c.groundSpeed() > 0.4; i++) {
            c.step(1.0 / 60, flat(), wetness);
        }
        return Math.hypot(c.position.x - x0, c.position.z - z0);
    }

    @Test
    @DisplayName("steering turns the car, and it leans into the corner")
    void cornering() {
        CarPhysics c = onGround();
        c.throttle = 0.45;
        for (int i = 0; i < 60 * 8; i++) { c.step(1.0 / 60, flat(), 0); }
        double h0 = c.orientation.heading();
        c.steerInput = 0.7;
        double maxRoll = 0;
        for (int i = 0; i < 60 * 6; i++) {
            c.step(1.0 / 60, flat(), 0);
            maxRoll = Math.max(maxRoll, Math.abs(c.orientation.roll()));
        }
        double turned = Math.abs(c.orientation.heading() - h0);
        assertTrue(turned > 0.3, "the car should have changed heading, got " + turned + " rad");
        assertTrue(maxRoll > 0.004, "weight transfer should roll the body, got " + maxRoll);
        assertTrue(maxRoll < 0.5, "it should not roll over on tarmac, got " + maxRoll);
    }

    @Test
    @DisplayName("the integrator does not explode at 60 Hz under full load")
    void stable() {
        CarPhysics c = onGround();
        c.throttle = 1.0;
        c.steerInput = 1.0;
        for (int i = 0; i < 60 * 120; i++) {
            c.step(1.0 / 60, flat(), 0.5);
            assertTrue(c.position.isFinite(), "position went non-finite at step " + i);
            assertTrue(c.velocity.length() < 200,
                    "velocity blew up to " + c.velocity.length() + " at step " + i);
            for (CarPhysics.Wheel w : c.wheels) {
                assertTrue(Double.isFinite(w.spin),
                        "wheel spin went non-finite at step " + i);
                assertTrue(Math.abs(w.spin) < 900,
                        "wheel spin " + w.spin + " rad/s at step " + i
                                + " - driveline inertia is not being reflected");
            }
        }
    }

    @Test
    @DisplayName("the gearbox walks up and back down")
    void gearbox() {
        CarPhysics c = onGround();
        c.throttle = 1.0;
        int highest = 0;
        for (int i = 0; i < 60 * 50; i++) {
            c.step(1.0 / 60, flat(), 0);
            highest = Math.max(highest, c.gear);
            assertTrue(c.engineRpm <= c.maxRpm + 50,
                    "over-revved to " + c.engineRpm + " in gear " + c.gear);
        }
        assertTrue(highest >= 5, "should have reached at least 5th, got " + highest);
        c.throttle = 0;
        c.brake = 0.6;
        for (int i = 0; i < 60 * 25 && c.groundSpeed() > 1; i++) {
            c.step(1.0 / 60, flat(), 0);
        }
        assertTrue(c.gear <= 2, "should have changed back down, in " + c.gear);
    }
}
