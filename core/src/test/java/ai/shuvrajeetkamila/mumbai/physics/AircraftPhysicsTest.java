package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.math.MathUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The helicopter and the aeroplane, with the two mistakes that are easy to
 * make and hard to see pinned down by name.
 */
class AircraftPhysicsTest {

    private static final HelicopterPhysics.GroundProbe PAD = (x, z) -> 0;
    private static final AeroplanePhysics.GroundProbe RUNWAY = (x, z) -> 0;

    // ---------------------------------------------------------------- helicopter

    @Test
    @DisplayName("hover needs most of the collective, not a third of it")
    void hoverThrustIsNotOverestimated() {
        HelicopterPhysics h = new HelicopterPhysics();
        h.position.set(0, 300, 0);           // well clear of ground effect
        double omega = h.nominalRotorRpm * 2 * Math.PI / 60;
        double weight = h.mass * MathUtil.G;

        // Find the collective pitch that holds a hover.
        double lo = 0, hi = Math.toRadians(h.maxCollectivePitchDeg);
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            double t = h.solveThrust(mid, 0, 0, MathUtil.airDensity(300), omega);
            if (t < weight) { lo = mid; } else { hi = mid; }
        }
        double hoverDeg = Math.toDegrees((lo + hi) / 2);
        // A real machine of this size hovers somewhere around 6 to 11 degrees
        // of collective pitch. If the induced inflow fixed point were skipped
        // this would come out near 3 degrees, because the thrust would be
        // roughly three and a half times too high.
        assertTrue(hoverDeg > 5.0 && hoverDeg < 12.5,
                "hover collective should be 5-12.5 deg, got " + hoverDeg
                        + " deg - check the induced inflow iteration");
    }

    @Test
    @DisplayName("thrust rises with collective and saturates, never runs away")
    void thrustMonotonic() {
        HelicopterPhysics h = new HelicopterPhysics();
        double omega = h.nominalRotorRpm * 2 * Math.PI / 60;
        double prev = -1;
        for (double deg = 0.4; deg <= 14.5; deg += 0.5) {
            double t = h.solveThrust(Math.toRadians(deg), 0, 0, 1.225, omega);
            assertTrue(Double.isFinite(t), "non-finite thrust at " + deg + " deg");
            assertTrue(t >= prev - 1.0, "thrust fell as pitch rose at " + deg + " deg");
            prev = t;
        }
        double maxTw = prev / (h.mass * MathUtil.G);
        assertTrue(maxTw > 1.05, "it must be able to climb, max T/W " + maxTw);
        assertTrue(maxTw < 2.6, "T/W of " + maxTw + " is not a helicopter");
    }

    @Test
    @DisplayName("ground effect makes the same collective lift more near the pad")
    void groundEffect() {
        HelicopterPhysics low = new HelicopterPhysics();
        low.position.set(0, 1.4, 0);
        low.collective = 0.62;
        HelicopterPhysics high = new HelicopterPhysics();
        high.position.set(0, 400, 0);
        high.collective = 0.62;
        for (int i = 0; i < 12; i++) {
            low.step(1.0 / 60, PAD, 0, 0);
            high.step(1.0 / 60, (x, z) -> 380, 0, 0);
        }
        assertTrue(low.groundEffectFactor > high.groundEffectFactor + 0.1,
                "in the cushion " + low.groundEffectFactor + " vs out of it "
                        + high.groundEffectFactor);
    }

    @Test
    @DisplayName("translational lift arrives with forward speed")
    void translationalLift() {
        HelicopterPhysics h = new HelicopterPhysics();
        h.position.set(0, 200, 0);
        h.collective = 0.6;
        h.step(1.0 / 60, (x, z) -> 0, 0, 0);
        double hover = h.translationalLift;
        h.velocity.set(25, 0, 0);
        h.step(1.0 / 60, (x, z) -> 0, 0, 0);
        assertTrue(h.translationalLift > hover + 0.1,
                "forward flight should ease the induced losses: " + hover
                        + " -> " + h.translationalLift);
    }

    @Test
    @DisplayName("engine out: a fast descent sustains rotor RPM, a slow one does not")
    void autorotation() {
        HelicopterPhysics fast = new HelicopterPhysics();
        fast.position.set(0, 900, 0);
        fast.engineOn = false;
        fast.collective = 0.08;
        fast.velocity.set(0, -38, 0);
        for (int i = 0; i < 60 * 4; i++) {
            fast.velocity.y = -38;                 // hold the descent rate
            fast.step(1.0 / 60, (x, z) -> 0, 0, 0);
        }
        assertTrue(fast.inAutorotation, "it should know it is in autorotation");
        assertTrue(fast.rotorPercent() > 55,
                "a 38 m/s descent should keep the rotor turning, got "
                        + fast.rotorPercent() + "%");

        HelicopterPhysics slow = new HelicopterPhysics();
        slow.position.set(0, 900, 0);
        slow.engineOn = false;
        slow.collective = 0.75;
        slow.velocity.set(0, -3, 0);
        for (int i = 0; i < 60 * 8; i++) {
            slow.velocity.y = -3;
            slow.step(1.0 / 60, (x, z) -> 0, 0, 0);
        }
        assertTrue(slow.rotorPercent() < fast.rotorPercent(),
                "high collective at a low descent rate must decay the rotor: "
                        + slow.rotorPercent() + "% vs " + fast.rotorPercent() + "%");
    }

    @Test
    @DisplayName("the tail rotor has something to cancel")
    void tailRotorTorque() {
        HelicopterPhysics h = new HelicopterPhysics();
        h.position.set(0, 200, 0);
        h.collective = 0.65;
        for (int i = 0; i < 30; i++) { h.step(1.0 / 60, (x, z) -> 0, 0, 0); }
        assertTrue(h.torqueRequired > 500,
                "the main rotor must demand real torque, got " + h.torqueRequired);
    }

    // ---------------------------------------------------------------- aeroplane

    @Test
    @DisplayName("the wing stalls: lift peaks and then falls away")
    void stallIsReal() {
        AeroplanePhysics a = new AeroplanePhysics();
        double peak = 0;
        double peakDeg = 0;
        for (double deg = 0; deg <= 30; deg += 0.25) {
            double cl = a.liftCoefficient(Math.toRadians(deg), 0);
            if (cl > peak) { peak = cl; peakDeg = deg; }
        }
        assertTrue(peakDeg > 10 && peakDeg < 20,
                "critical angle should be 10-20 deg, peak was at " + peakDeg);
        double past = a.liftCoefficient(Math.toRadians(peakDeg + 8), 0);
        assertTrue(past < peak * 0.85,
                "past the stall lift must drop: " + peak + " -> " + past);
    }

    @Test
    @DisplayName("drag grows with the square of lift, so induced drag is real")
    void dragPolar() {
        AeroplanePhysics a = new AeroplanePhysics();
        double d0 = a.dragCoefficient(0.2, 0, false);
        double d1 = a.dragCoefficient(0.4, 0, false);
        double d2 = a.dragCoefficient(0.8, 0, false);
        assertTrue(d1 - d0 > 0, "drag must grow with lift");
        assertTrue((d2 - d1) > (d1 - d0) * 2.0,
                "the induced term is quadratic, deltas were " + (d1 - d0)
                        + " and " + (d2 - d1));
        assertTrue(a.dragCoefficient(0.4, 0, true) > a.dragCoefficient(0.4, 0, false),
                "the undercarriage must cost drag");
        assertTrue(a.dragCoefficient(0.4, 40, false) > a.dragCoefficient(0.4, 0, false),
                "flaps must cost drag");
    }

    @Test
    @DisplayName("flaps lower the stall speed, which is what they are for")
    void flapsHelp() {
        AeroplanePhysics a = new AeroplanePhysics();
        a.flapSetting = 0;
        double clean = a.stallSpeed(1.225);
        a.flapSetting = 3;
        double dirty = a.stallSpeed(1.225);
        assertTrue(dirty < clean - 1.0,
                "full flap should cut the stall speed: " + clean + " -> " + dirty);
        assertTrue(clean > 20 && clean < 45,
                "clean stall should be 40-90 kt, got " + clean * 1.94384 + " kt");
    }

    @Test
    @DisplayName("no hidden takeoff impulse: it needs real runway to fly")
    void needsRunwayLength() {
        AeroplanePhysics a = new AeroplanePhysics();
        a.position.set(0, 1.1, 0);
        a.throttle = 1.0;
        a.flapSetting = 1;
        double startZ = a.position.z;
        double roll = -1;
        for (int i = 0; i < 60 * 90; i++) {
            // Rotate once past the stall speed, as a pilot would.
            a.elevator = a.indicatedAirspeed > a.stallSpeed(1.225) * 1.12 ? 0.42 : 0;
            a.step(1.0 / 60, RUNWAY, 0, 0);
            if (roll < 0 && a.position.y > 4.0) {
                roll = Math.hypot(a.position.x, a.position.z - startZ);
            }
            if (a.position.y > 60) { break; }
        }
        assertTrue(roll > 0, "it never got airborne at all");
        // Juhu's runway 08/26 is about 1143 m. A light twin should get off in
        // several hundred metres, not in twenty.
        assertTrue(roll > 150,
                "took off in " + roll + " m - that is a hidden impulse, not lift");
        assertTrue(roll < 2500,
                "took off in " + roll + " m, which no Mumbai runway can offer");
    }

    @Test
    @DisplayName("it cannot hold altitude below the stall speed")
    void cannotFlyTooSlow() {
        AeroplanePhysics a = new AeroplanePhysics();
        a.position.set(0, 600, 0);
        a.gearDown = false;
        a.throttle = 0.05;
        a.velocity.set(0, 0, 18);            // well under the clean stall
        double y0 = a.position.y;
        for (int i = 0; i < 60 * 6; i++) {
            a.elevator = 0.6;                // hauling back does not help
            a.step(1.0 / 60, (x, z) -> 0, 0, 0);
        }
        assertTrue(a.position.y < y0 - 15,
                "below the stall speed it must descend, lost only "
                        + (y0 - a.position.y) + " m");
    }

    @Test
    @DisplayName("neither aircraft explodes over two minutes of abuse")
    void stability() {
        HelicopterPhysics h = new HelicopterPhysics();
        h.position.set(0, 220, 0);
        AeroplanePhysics a = new AeroplanePhysics();
        a.position.set(0, 600, 0);
        a.velocity.set(0, 0, 62);
        for (int i = 0; i < 60 * 120; i++) {
            double t = i / 60.0;
            h.collective = 0.5 + 0.45 * Math.sin(t * 1.7);
            h.cyclicPitch = Math.sin(t * 2.3);
            h.cyclicRoll = Math.cos(t * 1.9);
            h.pedal = Math.sin(t * 0.7);
            h.step(1.0 / 60, (x, z) -> 0, 0, 0);
            a.throttle = 0.5 + 0.5 * Math.sin(t * 1.1);
            a.elevator = Math.sin(t * 2.7);
            a.aileron = Math.cos(t * 2.1);
            a.rudderInput = Math.sin(t * 1.3);
            a.step(1.0 / 60, (x, z) -> 0, 0, 0);
            assertTrue(h.position.isFinite() && h.velocity.length() < 400,
                    "helicopter diverged at " + t + " s, v=" + h.velocity.length());
            assertTrue(a.position.isFinite() && a.velocity.length() < 400,
                    "aeroplane diverged at " + t + " s, v=" + a.velocity.length());
            assertTrue(Double.isFinite(h.rotorRpm) && h.rotorRpm >= 0,
                    "rotor rpm went bad at " + t + " s");
        }
    }

    /** Five thousand metres below, i.e. nothing to hit. */
    private static final AeroplanePhysics.GroundProbe AIR = (x, z) -> -5000;

    private static AeroplanePhysics cruising(double throttle) {
        AeroplanePhysics p = new AeroplanePhysics();
        p.reset();
        p.position.set(0, 1500, 0);
        p.velocity.set(0, 0, 70);
        p.throttle = throttle;
        p.gearDown = false;
        p.elevator = 0;
        return p;
    }

    @Test
    @DisplayName("the phugoid damps out instead of running away")
    void thePhugoidDamps() {
        // This was written down as an open fault - "no fixed elevator
        // holds level flight, 757 m of altitude wander over three
        // minutes" - on the strength of a measurement taken from a
        // badly chosen starting condition. Flown from a sensible one it
        // is a textbook phugoid: a long slow exchange of speed for
        // height that decays. Measured here so the claim is checked
        // rather than repeated.
        AeroplanePhysics p = cruising(0.72);
        double firstPeak = 0, lastPeak = 0;
        for (int minute = 0; minute < 12; minute++) {
            double peak = 0;
            for (int i = 0; i < 60 * 60; i++) {
                p.step(1.0 / 60, AIR, 0, 0);
                peak = Math.max(peak, Math.abs(p.velocity.y));
            }
            if (minute == 0) { firstPeak = peak; }
            lastPeak = peak;
        }
        assertTrue(firstPeak > 4.0,
                "the disturbance was too small to prove anything, "
                        + firstPeak + " m/s");
        assertTrue(lastPeak < firstPeak * 0.25,
                "the phugoid is not damping: peak climb rate went from "
                        + firstPeak + " to " + lastPeak + " m/s over twelve"
                        + " minutes");
    }

    @Test
    @DisplayName("a fixed throttle and a centred stick hold a level cruise")
    void itTrimsLevel() {
        // Bracket the level-flight throttle, then fly the middle of the
        // bracket and demand that it actually holds height.
        double lo = 0.40, hi = 0.80;
        for (int k = 0; k < 11; k++) {
            double mid = (lo + hi) * 0.5;
            if (settledClimb(mid) > 0) { hi = mid; } else { lo = mid; }
        }
        double throttle = (lo + hi) * 0.5;
        assertTrue(throttle > 0.42 && throttle < 0.78,
                "level flight needs an implausible throttle: " + throttle);

        AeroplanePhysics p = cruising(throttle);
        for (int i = 0; i < 60 * 540; i++) { p.step(1.0 / 60, AIR, 0, 0); }
        double y0 = p.position.y;
        double peak = 0;
        for (int i = 0; i < 60 * 120; i++) {
            p.step(1.0 / 60, AIR, 0, 0);
            peak = Math.max(peak, Math.abs(p.velocity.y));
        }
        double drift = p.position.y - y0;
        assertTrue(Math.abs(drift) < 60,
                "trimmed at " + throttle + " it still drifted " + drift
                        + " m in two minutes");
        assertTrue(peak < 1.0,
                "the oscillation never settled, peak " + peak + " m/s");
        double kmh = p.velocity.length() * 3.6;
        assertTrue(kmh > 200 && kmh < 340,
                "cruise speed is not an aeroplane's: " + kmh + " km/h");
    }

    /** Mean climb rate over the tenth minute of a flight at this throttle. */
    private static double settledClimb(double throttle) {
        AeroplanePhysics p = cruising(throttle);
        for (int i = 0; i < 60 * 540; i++) { p.step(1.0 / 60, AIR, 0, 0); }
        double sum = 0;
        for (int i = 0; i < 60 * 60; i++) {
            p.step(1.0 / 60, AIR, 0, 0);
            sum += p.velocity.y;
        }
        return sum / (60 * 60);
    }
}
