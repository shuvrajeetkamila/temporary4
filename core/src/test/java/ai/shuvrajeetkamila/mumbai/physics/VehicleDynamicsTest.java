package ai.shuvrajeetkamila.mumbai.physics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.math.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Performance-envelope tests for the whole vehicle roster.
 *
 * <p>The physics tests that came before this one check signs, stability
 * and conservation: that a torque produces rotation the right way round,
 * that nothing diverges. They all passed while a double-decker bus could
 * not exceed 2.5 km/h and a saloon quietly drove itself backwards with
 * the throttle shut, because neither fault shows up in a sign or a
 * magnitude bound. What catches those is driving each vehicle and
 * measuring what it actually does against what the real thing does.
 *
 * <p>The bands here are deliberately wide. The point is not to pin the
 * handling to three significant figures, it is to fail loudly when a
 * vehicle becomes undriveable.
 */
final class VehicleDynamicsTest {

    private static final double DT = 1.0 / 60;

    private static CarPhysics.GroundProbe flat() {
        return new CarPhysics.GroundProbe() {
            @Override public double heightAt(double x, double z) { return 0; }
            @Override public void normalAt(double x, double z, Vec3 out) {
                out.set(0, 1, 0);
            }
            @Override public boolean isUnpaved(double x, double z) { return false; }
        };
    }

    private static CarPhysics settled(VehicleKind k) {
        CarPhysics c = new CarPhysics();
        c.configure(k);
        c.position.set(0, k.wheelRadiusM() + 0.9, 0);
        for (int i = 0; i < 300; i++) { c.step(DT, flat(), 0); }
        return c;
    }

    // ------------------------------------------------------ running gear

    @Test
    @DisplayName("suspension carries the vehicle's own weight, whatever it weighs")
    void suspensionIsSizedForTheMass() {
        // configure() used to set mass and gearing but leave the running
        // gear at a saloon's. A 12.4 t bus needs about 30 kN per corner,
        // and a 42 kN/m spring over 0.22 m of travel can only push back
        // 9.2 kN, so the bus bottomed out and sank through the road.
        for (VehicleKind k : VehicleKind.values()) {
            CarPhysics c = settled(k);
            double corner = c.mass * 9.81 / 4.0;
            CarPhysics.Wheel w = c.wheels[0];
            double capacity = w.stiffness * w.travel;
            assertTrue(capacity > corner * 1.25,
                    k + " can only support " + (int) capacity + " N per corner"
                            + " but weighs " + (int) corner + " N there");
            assertTrue(c.anyWheelGrounded, k + " is not on the ground at all");
            assertTrue(c.position.y > 0, k + " sank to y=" + c.position.y);
            for (CarPhysics.Wheel x : c.wheels) {
                assertTrue(x.compression < 0.92,
                        k + " rests at " + x.compression + " of its travel,"
                                + " which is on the bump stop");
            }
        }
    }

    @Test
    @DisplayName("damping is a sane fraction of critical for every vehicle")
    void dampingIsReferencedToCritical() {
        for (VehicleKind k : VehicleKind.values()) {
            CarPhysics c = new CarPhysics();
            c.configure(k);
            CarPhysics.Wheel w = c.wheels[0];
            double corner = c.mass / 4.0;
            double critical = 2 * Math.sqrt(w.stiffness * corner);
            double zBump = w.damperBump / critical;
            double zRebound = w.damperRebound / critical;
            assertTrue(zBump > 0.2 && zBump < 1.0,
                    k + " bump damping is " + zBump + " of critical");
            assertTrue(zRebound > zBump,
                    k + " rebound must be firmer than bump");
            assertTrue(zRebound < 1.2,
                    k + " rebound damping is " + zRebound + " of critical");
        }
    }

    @Test
    @DisplayName("brakes and clutch are sized for the engine and the mass")
    void drivelineIsSizedForTheVehicle() {
        for (VehicleKind k : VehicleKind.values()) {
            CarPhysics c = new CarPhysics();
            c.configure(k);
            // A clutch must hold more than the engine can make, or it
            // slips for ever and the vehicle never pulls away.
            assertTrue(c.clutchCapacity > c.peakTorqueNm * 1.2,
                    k + " clutch holds " + c.clutchCapacity
                            + " N m against an engine making " + c.peakTorqueNm);
            // Brakes must be able to lock a wheel, or the thing cannot stop.
            double lockTorque = c.mass * 9.81 * c.wheels[0].radius / 2.0;
            assertTrue(c.brakeTorqueMax > lockTorque * 0.9,
                    k + " brakes make " + (int) c.brakeTorqueMax
                            + " N m, needs about " + (int) lockTorque);
        }
    }

    // --------------------------------------------------------- behaviour

    @Test
    @DisplayName("nothing drives itself anywhere with the throttle shut")
    void noVehicleCreepsAtIdle() {
        // Overrun torque was applied in a fixed direction rather than
        // against the wheel's rotation, so from rest it spun the wheels
        // backwards: the saloon reached 4.1 m/s in reverse at idle and
        // was still accelerating.
        for (VehicleKind k : VehicleKind.values()) {
            CarPhysics c = settled(k);
            c.throttle = 0;
            for (int i = 0; i < 60 * 10; i++) { c.step(DT, flat(), 0); }
            assertTrue(Math.abs(c.forwardSpeed()) < 0.6,
                    k + " drifted to " + c.forwardSpeed()
                            + " m/s with the throttle shut");
        }
    }

    @Test
    @DisplayName("every vehicle reaches roughly the speed it is geared for")
    void topSpeedsAreBelievable() {
        for (VehicleKind k : VehicleKind.values()) {
            CarPhysics c = settled(k);
            c.throttle = 1;
            double top = 0;
            for (int i = 0; i < 60 * 150; i++) {
                c.step(DT, flat(), 0);
                top = Math.max(top, c.groundSpeed() * 3.6);
            }
            double design = k.topSpeedKph();
            assertTrue(top > design * 0.62,
                    k + " only reached " + (int) top + " km/h of a designed "
                            + (int) design);
            assertTrue(top < design * 1.45,
                    k + " reached " + (int) top + " km/h, far past its designed "
                            + (int) design);
        }
    }

    @Test
    @DisplayName("a bus is slower than a police car, and a rickshaw slower still")
    void theRosterIsOrdered() {
        // Cheap, but it is the property a player actually feels, and it
        // was comprehensively false: the bus did 2.5 km/h and the
        // rickshaw out-ran a saloon.
        double bus = measureTop(VehicleKind.DOUBLE_DECKER);
        double auto = measureTop(VehicleKind.AUTORICKSHAW);
        double police = measureTop(VehicleKind.POLICE);
        double taxi = measureTop(VehicleKind.KAALI_PEELI);
        assertTrue(auto < bus, "a rickshaw (" + auto + ") out-ran a bus (" + bus + ")");
        assertTrue(bus < taxi, "a bus (" + bus + ") out-ran a taxi (" + taxi + ")");
        assertTrue(taxi < police, "a taxi (" + taxi + ") out-ran a police car");
    }

    private static double measureTop(VehicleKind k) {
        CarPhysics c = settled(k);
        c.throttle = 1;
        double top = 0;
        for (int i = 0; i < 60 * 120; i++) {
            c.step(DT, flat(), 0);
            top = Math.max(top, c.groundSpeed() * 3.6);
        }
        return top;
    }

    @Test
    @DisplayName("a car stops from 100 km/h in a road-car distance")
    void brakingDistanceIsRealistic() {
        for (VehicleKind k : new VehicleKind[] {VehicleKind.SEDAN,
                VehicleKind.HATCHBACK, VehicleKind.POLICE, VehicleKind.SUV}) {
            CarPhysics c = settled(k);
            c.throttle = 1;
            int guard = 0;
            while (c.groundSpeed() * 3.6 < 100 && guard++ < 60 * 150) {
                c.step(DT, flat(), 0);
            }
            double z0 = c.position.z;
            double x0 = c.position.x;
            c.throttle = 0;
            c.brake = 1;
            guard = 0;
            while (c.groundSpeed() > 0.5 && guard++ < 60 * 40) {
                c.step(DT, flat(), 0);
            }
            double d = Math.hypot(c.position.x - x0, c.position.z - z0);
            // A road car on dry tarmac takes about 40 m. Anything under 25
            // means the tyres are making grip they should not have.
            assertTrue(d > 25 && d < 70,
                    k + " stopped from 100 km/h in " + (int) d + " m");
        }
    }

    @Test
    @DisplayName("cars corner at a tyre's worth of grip, and buses at less")
    void corneringGripIsBelievable() {
        double car = measureLateralG(VehicleKind.SEDAN);
        double bus = measureLateralG(VehicleKind.DOUBLE_DECKER);
        assertTrue(car > 0.55 && car < 1.15,
                "a saloon pulled " + car + " g, which is not a road tyre");
        assertTrue(bus > 0.2 && bus < car,
                "a double-decker pulled " + bus + " g against a saloon's " + car);
    }

    private static double measureLateralG(VehicleKind k) {
        CarPhysics c = settled(k);
        c.throttle = 0.5;
        int guard = 0;
        while (c.groundSpeed() < 12 && guard++ < 60 * 150) { c.step(DT, flat(), 0); }
        c.steerInput = 1;
        c.throttle = 0.35;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 60 * 14; i++) {
            c.step(DT, flat(), 0);
            if (i > 60 * 6) {
                sum += Math.abs(c.angularVelocity.y) * c.groundSpeed();
                n++;
            }
        }
        return sum / Math.max(1, n) / 9.81;
    }

    @Test
    @DisplayName("a steady turn settles instead of chattering")
    void thereIsNoPermanentBurnout() {
        // Tyre force used to flip sign every step on a loaded wheel: the
        // bus alternated between driving at +25 kN and braking at -31 kN
        // and averaged nothing at all.
        for (VehicleKind k : new VehicleKind[] {VehicleKind.DOUBLE_DECKER,
                VehicleKind.CITY_BUS, VehicleKind.SEDAN}) {
            CarPhysics c = settled(k);
            c.throttle = 1;
            for (int i = 0; i < 60 * 30; i++) { c.step(DT, flat(), 0); }
            int signFlips = 0;
            double prev = c.wheels[2].longForce;
            for (int i = 0; i < 600; i++) {
                c.step(DT, flat(), 0);
                double now = c.wheels[2].longForce;
                if (prev * now < 0) { signFlips++; }
                prev = now;
            }
            assertTrue(signFlips < 90,
                    k + " drive force changed sign " + signFlips
                            + " times in 10 s of steady acceleration");
            assertTrue(c.wheelSlipMax < 0.6,
                    k + " is still spinning its wheels at " + c.wheelSlipMax
                            + " after 30 s at full throttle");
        }
    }

    // ------------------------------------------------- engine modelling

    @Test
    @DisplayName("the torque curve fits whatever redline the engine has")
    void torqueCurveFollowsTheRedline() {
        // The breakpoints used to be fixed rpm numbers taken from a petrol
        // saloon, so a 2300 rpm diesel bus never reached the plateau at
        // all and a 7500 rpm scooter spent its life past the drop-off.
        for (VehicleKind k : VehicleKind.values()) {
            CarPhysics c = new CarPhysics();
            c.configure(k);
            double best = 0;
            double bestRpm = 0;
            for (double rpm = c.idleRpm; rpm <= c.redlineRpm; rpm += 10) {
                double t = c.engineTorque(rpm, 1.0);
                if (t > best) { best = t; bestRpm = rpm; }
            }
            assertTrue(best > c.peakTorqueNm * 0.95,
                    k + " never makes its rated " + c.peakTorqueNm
                            + " N m; the best is " + best);
            double where = bestRpm / c.redlineRpm;
            assertTrue(where > 0.3 && where < 0.85,
                    k + " peaks at " + where + " of redline");
            // And engine braking must stay in proportion to the engine.
            double overrun = -c.engineTorque(c.redlineRpm, 0.0);
            assertTrue(overrun > 0 && overrun < c.peakTorqueNm,
                    k + " engine braking is " + overrun
                            + " N m against a peak of " + c.peakTorqueNm);
        }
    }

    // ------------------------------------------------------- helicopter

    @Test
    @DisplayName("a helicopter hovers at a sane collective")
    void helicopterHovers() {
        HelicopterPhysics.GroundProbe deep = (x, z) -> -100000;
        double bestCol = -1;
        double bestErr = 1e9;
        for (double col = 0.30; col <= 0.95; col += 0.02) {
            HelicopterPhysics h = new HelicopterPhysics();
            h.position.set(0, 500, 0);
            h.collective = col;
            for (int i = 0; i < 60 * 25; i++) { h.step(DT, deep, 0, 0); }
            double e = Math.abs(h.velocity.y);
            if (e < bestErr) { bestErr = e; bestCol = col; }
        }
        assertTrue(bestErr < 1.5,
                "no collective held a hover; the best was " + bestCol
                        + " still moving at " + bestErr + " m/s");
        assertTrue(bestCol > 0.35 && bestCol < 0.80,
                "hover needs " + bestCol + " collective, which is not a hover");
    }

    @Test
    @DisplayName("a helicopter cannot climb on free energy")
    void helicopterClimbIsPowerLimited() {
        // Rotor torque charged for induced and profile power but not for
        // climb power, so the machine climbed at 148 m/s - about 7 MW of
        // work against gravity - on an engine good for 560 kW.
        HelicopterPhysics.GroundProbe deep = (x, z) -> -100000;
        double best = -1e9;
        for (double col = 0.3; col <= 1.0; col += 0.05) {
            HelicopterPhysics h = new HelicopterPhysics();
            h.position.set(0, 500, 0);
            h.collective = col;
            for (int i = 0; i < 60 * 40; i++) { h.step(DT, deep, 0, 0); }
            best = Math.max(best, h.velocity.y);
        }
        assertTrue(best > 4,
                "the helicopter can only climb at " + best + " m/s");
        assertTrue(best < 22,
                "the helicopter climbs at " + best
                        + " m/s, which its engine cannot pay for");

        // Sanity on the power book-keeping itself.
        HelicopterPhysics h = new HelicopterPhysics();
        double omega = h.nominalRotorRpm * Math.PI / 30.0;
        double availableKw = h.maxEngineTorque * omega / 1000.0;
        double hoverInducedKw = h.mass * 9.81
                * Math.sqrt(h.mass * 9.81 / (2 * 1.225 * h.discArea())) / 1000.0;
        assertTrue(availableKw > hoverInducedKw * 1.3,
                "only " + availableKw + " kW available against "
                        + hoverInducedKw + " kW of induced power in the hover");
    }

    @Test
    @DisplayName("over-pitching droops the rotor instead of making more lift")
    void overPitchingDroopsTheRotor() {
        HelicopterPhysics.GroundProbe deep = (x, z) -> -100000;
        HelicopterPhysics h = new HelicopterPhysics();
        h.position.set(0, 500, 0);
        h.collective = 1.0;
        for (int i = 0; i < 60 * 40; i++) { h.step(DT, deep, 0, 0); }
        assertTrue(h.rotorPercent() < 97,
                "full collective held the rotor at " + h.rotorPercent()
                        + "%, so the engine is not being asked for the torque");
    }

    @Test
    @DisplayName("the rotor keeps turning with the engine off")
    void autorotationSustainsTheRotor() {
        // The blade element used only the induced inflow, so in a descent
        // thrust collapsed to nothing and an engine failure was a 105 m/s
        // free fall rather than an autorotation.
        HelicopterPhysics.GroundProbe deep = (x, z) -> -100000;
        double bestDescent = -1e9;
        double rotorThere = 0;
        for (double col = 0.15; col <= 0.6; col += 0.05) {
            HelicopterPhysics h = new HelicopterPhysics();
            h.position.set(0, 6000, 0);
            h.collective = 0.58;
            for (int i = 0; i < 60 * 12; i++) { h.step(DT, deep, 0, 0); }
            h.engineOn = false;
            h.collective = col;
            for (int i = 0; i < 60 * 90; i++) { h.step(DT, deep, 0, 0); }
            if (h.velocity.y > bestDescent) {
                bestDescent = h.velocity.y;
                rotorThere = h.rotorPercent();
            }
        }
        assertTrue(bestDescent > -40,
                "the best engine-off descent was " + bestDescent
                        + " m/s, which is a free fall, not an autorotation");
        assertTrue(rotorThere > 70,
                "the rotor decayed to " + rotorThere + "% in autorotation");
    }

    // ------------------------------------------------------------ human

    @Test
    @DisplayName("a jump lifts a person, not a high jumper")
    void jumpHeightIsHuman() {
        HumanPhysics h = new HumanPhysics();
        double apex = h.jumpSpeed * h.jumpSpeed / (2 * 9.81);
        assertTrue(apex > 0.35 && apex < 0.75,
                "a standing jump clears " + apex + " m");
    }

    @Test
    @DisplayName("walking, running and sprinting are ordered and human")
    void gaitsAreOrdered() {
        HumanPhysics h = new HumanPhysics();
        assertTrue(h.walkSpeed > 1.0 && h.walkSpeed < 2.0, "walk " + h.walkSpeed);
        assertTrue(h.runSpeed > h.walkSpeed, "run must beat walk");
        assertTrue(h.sprintSpeed > h.runSpeed, "sprint must beat run");
        assertTrue(h.sprintSpeed < 9.0,
                "sprinting at " + h.sprintSpeed + " m/s is a world record");
        assertTrue(h.swimSpeed < h.walkSpeed,
                "swimming must be slower than walking");
    }

    @Test
    @DisplayName("a wheel radius is chosen per vehicle, not inherited")
    void wheelRadiiDiffer() {
        double bus = VehicleKind.DOUBLE_DECKER.wheelRadiusM();
        double scooter = VehicleKind.SCOOTER.wheelRadiusM();
        double sedan = VehicleKind.SEDAN.wheelRadiusM();
        assertTrue(bus > sedan, "a bus wheel must be bigger than a saloon's");
        assertTrue(scooter < sedan, "a scooter wheel must be smaller");
        assertFalse(bus == scooter, "every vehicle had the same wheel");
        for (VehicleKind k : VehicleKind.values()) {
            double r = k.wheelRadiusM();
            assertTrue(r > 0.12 && r < 0.75, k + " wheel radius " + r);
        }
    }
}
