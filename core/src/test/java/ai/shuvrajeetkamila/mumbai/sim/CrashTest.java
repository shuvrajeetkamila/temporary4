package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.physics.CarPhysics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Traffic that hits traffic, and the damage it does.
 *
 * <p>Up to now a car yielded to the player and to nothing else in the
 * world. Two cars arriving at the same junction occupied the same
 * tarmac, which on screen is one car flickering between two liveries,
 * and no queue ever formed anywhere because no car was ever in another
 * car's way.
 */
final class CrashTest {

    private static Agents.Car at(double x, double z, double heading,
                                 double speed, VehicleKind k) {
        Agents.Car c = new Agents.Car();
        c.x = x;
        c.z = z;
        c.heading = heading;
        c.speed = speed;
        c.kindOrdinal = k.ordinal();
        c.active = true;
        return c;
    }

    @Test
    @DisplayName("two cars in the same place are touching")
    void overlapIsDetected() {
        Agents.Car a = at(0, 0, 0, 10, VehicleKind.SEDAN);
        Agents.Car b = at(0, -1.5, 0, 0, VehicleKind.SEDAN);
        assertTrue(Agents.touching(a, b),
                "a car a metre and a half behind another, both four and a"
                        + " half metres long, is inside it");
    }

    @Test
    @DisplayName("a car in the oncoming lane is not touching")
    void oncomingTrafficIsNotAnOverlap() {
        // This is the bug a bounding circle would have. A circle that
        // covers a five metre vehicle end to end has a radius of two and
        // a half metres, and the lanes are 2.6 m either side of the
        // centreline - so every car would have spent the drive shoving
        // the oncoming traffic off the road.
        Agents.Car a = at(0, 0, 0, 14, VehicleKind.SEDAN);
        Agents.Car b = at(5.2, 0, Math.PI, 14, VehicleKind.SEDAN);
        assertTrue(!Agents.touching(a, b),
                "cars passing in opposite lanes were counted as a crash");

        // Even the long ones.
        Agents.Car bus = at(0, 0, 0, 10, VehicleKind.DOUBLE_DECKER);
        Agents.Car onc = at(5.2, 0, Math.PI, 10, VehicleKind.DOUBLE_DECKER);
        assertTrue(!Agents.touching(bus, onc),
                "two buses passing each other were counted as a crash");
    }

    @Test
    @DisplayName("the overlap test is symmetric")
    void overlapDoesNotDependOnWhichCarAsks() {
        for (int i = 0; i < 400; i++) {
            double h1 = i * 0.0157, h2 = i * 0.0411;
            double dx = Math.sin(i * 0.37) * 6, dz = Math.cos(i * 0.21) * 6;
            Agents.Car a = at(0, 0, h1, 0, VehicleKind.HATCHBACK);
            Agents.Car b = at(dx, dz, h2, 0, VehicleKind.TEMPO);
            assertEquals(Agents.touching(a, b), Agents.touching(b, a),
                    "whether two cars are touching changed when the"
                            + " question was asked the other way round");
        }
    }

    @Test
    @DisplayName("a shunt damages both cars and pushes them apart")
    void collisionsExchangeMomentum() {
        // One car doing 50 km/h straight into a stationary one. Heading
        // 0 is north, so forward is -Z: the moving car starts south of
        // the one it is about to hit.
        Agents.Car[] cs = {
            at(0, 2.0, 0, 13.9, VehicleKind.SEDAN),
            at(0, 0.0, 0, 0.0, VehicleKind.SEDAN),
        };
        double gapBefore = Math.abs(cs[0].z - cs[1].z);
        Agents.resolveCarCollisions(cs, 2, 1.0 / 60.0);

        assertTrue(cs[0].damage > 0.2 && cs[1].damage > 0.2,
                "a 50 km/h shunt left the cars undamaged: "
                        + cs[0].damage + " and " + cs[1].damage);
        assertTrue(cs[1].shoveTimer > 0,
                "the car that was hit stayed on its route as though"
                        + " nothing had happened");
        assertTrue(cs[1].shoveZ < -0.5,
                "the car that was hit was not pushed the way the other"
                        + " one was going: shoveZ " + cs[1].shoveZ);
        assertTrue(Math.hypot(cs[0].shoveX, cs[0].shoveZ) < 13.9,
                "the car that did the hitting carried on at full speed");

        // Momentum along the line of centres is conserved to within the
        // restitution. Equal masses, so the pair's closing speed must
        // have dropped to nearly nothing.
        double closing = (-cs[0].shoveZ) - (-cs[1].shoveZ);
        assertTrue(closing < 2.0,
                "the two cars are still driving into each other at "
                        + closing + " m/s after the impact");

        for (int i = 0; i < 20; i++) {
            Agents.resolveCarCollisions(cs, 2, 1.0 / 60.0);
        }
        assertTrue(Math.abs(cs[0].z - cs[1].z) > gapBefore * 0.9,
                "the cars settled inside one another instead of"
                        + " separating");
    }

    @Test
    @DisplayName("cars resting against each other do not shake")
    void stationaryContactIsQuiet() {
        Agents.Car[] cs = {
            at(0, 0.0, 0, 0, VehicleKind.SEDAN),
            at(0, 3.6, 0, 0, VehicleKind.SEDAN),
        };
        for (int i = 0; i < 240; i++) {
            Agents.resolveCarCollisions(cs, 2, 1.0 / 60.0);
        }
        assertEquals(0.0, cs[0].damage, 1e-9,
                "two parked cars touching bumpers destroyed each other");
        assertEquals(0.0, cs[1].damage, 1e-9,
                "two parked cars touching bumpers destroyed each other");
    }

    @Test
    @DisplayName("nudging does not wear a car out")
    void lowSpeedContactIsFree() {
        CarPhysics car = new CarPhysics();
        car.configure(VehicleKind.SEDAN);
        for (int i = 0; i < 200; i++) { car.takeImpact(1.4); }
        assertEquals(0.0, car.damage, 1e-9,
                "two hundred kerb scrapes at walking pace wrote the car"
                        + " off, so crawling through a queue is fatal");
    }

    @Test
    @DisplayName("damage costs engine torque and nothing else")
    void damageCostsPower() {
        CarPhysics clean = new CarPhysics();
        clean.configure(VehicleKind.SEDAN);
        CarPhysics bent = new CarPhysics();
        bent.configure(VehicleKind.SEDAN);

        // A 50 km/h shunt into something that does not move.
        bent.takeImpact(13.9);
        assertTrue(bent.damage > 0.4,
                "a 50 km/h crash barely marked the car: " + bent.damage);

        double rpm = 3400;
        double t0 = clean.engineTorque(rpm, 1.0);
        double t1 = bent.engineTorque(rpm, 1.0);
        assertTrue(t1 < t0 * 0.95,
                "a wrecked engine made as much torque as a healthy one: "
                        + t1 + " against " + t0);
        assertTrue(t1 > 0,
                "a damaged car cannot be left with no drive at all or it"
                        + " cannot be moved off the road");

        // Steering, brakes and mass are untouched on purpose. A car that
        // handles worse every time you clip a bollard is a car you
        // cannot drive to the end of the mission, and there is nowhere
        // to repair it.
        assertEquals(clean.mass, bent.mass, 1e-9);
        assertEquals(clean.wheelbase, bent.wheelbase, 1e-9);
    }

    @Test
    @DisplayName("damage saturates at a wreck")
    void damageIsBounded() {
        CarPhysics car = new CarPhysics();
        car.configure(VehicleKind.KAALI_PEELI);
        for (int i = 0; i < 50; i++) { car.takeImpact(30.0); }
        assertEquals(1.0, car.damage, 1e-9,
                "damage ran past a total loss");
        assertEquals(1.0 - CarPhysics.DAMAGE_POWER_LOSS,
                car.powerFraction(), 1e-9);
        assertTrue(car.powerFraction() > 0.2,
                "a wreck has to be able to limp");
    }

    @Test
    @DisplayName("a heavy vehicle wins")
    void massDecidesWhoComesOffWorse() {
        Agents.Car[] cs = {
            at(0, 4.0, 0, 12, VehicleKind.DOUBLE_DECKER),
            at(0, 0.0, 0, 0, VehicleKind.AUTORICKSHAW),
        };
        Agents.resolveCarCollisions(cs, 2, 1.0 / 60.0);
        assertTrue(cs[1].damage > cs[0].damage * 2,
                "a bus ran over an autorickshaw and came off equally:"
                        + " bus " + cs[0].damage + ", auto " + cs[1].damage);
        assertTrue(Math.hypot(cs[1].shoveX, cs[1].shoveZ)
                        > Math.hypot(cs[0].shoveX, cs[0].shoveZ),
                "the bus was knocked further than the autorickshaw");
    }
}
