package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.math.Vec3;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which way the camera is pointing when you get in.
 *
 * <p>This is what "the car starts backwards" turned out to be, after
 * four genuine driveline faults had been found and fixed and the
 * complaint did not go away. The chase camera sits behind whatever
 * {@code cameraYaw} points at, and getting into a car never set
 * {@code cameraYaw}: it kept the heading the player had on foot. You
 * walk up to a parked car, which means you are facing it, so the camera
 * stayed in front of the bonnet looking back down it. Press forward,
 * the car drives correctly along its own nose - straight at the camera
 * and then away behind it - and what you watch is a car reversing.
 */
final class ChaseCameraTest {

    /** Unit vector along a heading, in the engine's frame. */
    private static double[] nose(double heading) {
        return new double[]{Math.sin(heading), -Math.cos(heading)};
    }

    private static Engine engineWithACarAt(double[] out) {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(18.9430, 72.8230, ControlProfile.ON_FOOT);
        InputState in = new InputState();
        for (int i = 0; i < 240; i++) { e.frame(1.0 / 60, in); }
        // Find a parked car and stand on it, facing the WRONG way: the
        // exact opposite of the car's nose, which is roughly what
        // walking up to a car from in front gives you.
        Agents.Car[] cs = e.agents.cars();
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < cs.length; i++) {
            if (!cs[i].active || Math.abs(cs[i].speed) > 0.05) { continue; }
            double d = Math.hypot(cs[i].x - e.human.position.x,
                    cs[i].z - e.human.position.z);
            if (d < bd) { bd = d; best = i; }
        }
        if (best < 0) { return null; }
        out[0] = cs[best].heading;
        e.human.position.set(cs[best].x, e.human.position.y, cs[best].z);
        e.cameraYaw = cs[best].heading + Math.PI;
        return e;
    }

    @Test
    @DisplayName("getting into a car puts the camera behind it, facing its nose")
    void theCameraFacesTheWayTheCarDoes() {
        double[] carHeading = new double[1];
        Engine e = engineWithACarAt(carHeading);
        if (e == null) { return; }           // no parked car near the spawn
        assertTrue(e.tryEnterVehicle(), "could not get into the car");
        // One frame, so the render pose catches up: cameraPosition works
        // off the interpolated pose, not the physics body.
        e.frame(1.0 / 60, new InputState());

        // The camera must end up behind the car, not in front of it.
        Vec3 cam = e.cameraPosition(new Vec3());
        double[] n = nose(e.car.orientation.heading());
        double bx = cam.x - e.car.position.x;
        double bz = cam.z - e.car.position.z;
        double along = bx * n[0] + bz * n[1];
        assertTrue(along < 0,
                "the camera is " + (-along) + " m in FRONT of the car's nose,"
                        + " so driving forward looks like reversing");

        // And driving forward must take the car away from the camera.
        InputState in = new InputState();
        in.throttle = 1.0;
        double before = Math.hypot(e.car.position.x - cam.x,
                e.car.position.z - cam.z);
        for (int i = 0; i < 90; i++) { e.frame(1.0 / 60, in); }
        double[] n2 = nose(e.car.orientation.heading());
        double moved = (e.car.position.x - (cam.x - bx)) * n2[0]
                + (e.car.position.z - (cam.z - bz)) * n2[1];
        assertTrue(moved > 0.5,
                "full throttle moved the car " + moved + " m along its own"
                        + " nose, so it is going backwards");
        assertTrue(before > 0, "camera coincided with the car");
    }

    @Test
    @DisplayName("the camera never starts in front of any vehicle you board")
    void boardingAlwaysAlignsTheCamera() {
        // Whichever way the player happens to be facing when they get in.
        for (double facing : new double[]{0, 1.1, 2.4, 3.9, 5.2}) {
            double[] carHeading = new double[1];
            Engine e = engineWithACarAt(carHeading);
            if (e == null) { return; }
            e.cameraYaw = facing;
            if (!e.tryEnterVehicle()) { continue; }
            double d = Math.abs(wrap(e.cameraYaw - e.car.orientation.heading()));
            assertTrue(d < 1e-6,
                    "entered facing " + facing + " and the camera ended up "
                            + d + " rad off the car's heading");
        }
    }

    private static double wrap(double a) {
        while (a > Math.PI) { a -= 2 * Math.PI; }
        while (a < -Math.PI) { a += 2 * Math.PI; }
        return a;
    }
}
