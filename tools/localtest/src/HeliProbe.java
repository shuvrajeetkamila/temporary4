import ai.shuvrajeetkamila.mumbai.physics.HelicopterPhysics;
import ai.shuvrajeetkamila.mumbai.app.ControlMapping;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.math.Vec3;

/** Flies the helicopter through the real control chain and reports. */
public final class HeliProbe {

    static final double DT = 1.0 / 60.0;
    static HelicopterPhysics.GroundProbe FLAT = (x, z) -> 0.0;

    static HelicopterPhysics fresh(double alt) {
        HelicopterPhysics h = new HelicopterPhysics();
        h.reset();
        h.position.set(0, alt, 0);
        h.collective = HelicopterPhysics.HOVER_COLLECTIVE;
        return h;
    }

    static double deg(double r) { return Math.toDegrees(r); }

    static double pitchDeg(HelicopterPhysics h) {
        Vec3 f = new Vec3();
        h.orientation.forward(f);
        return deg(Math.asin(Math.max(-1, Math.min(1, f.y))));
    }

    static double rollDeg(HelicopterPhysics h) {
        Vec3 r = new Vec3();
        h.orientation.right(r);
        return deg(Math.asin(Math.max(-1, Math.min(1, r.y))));
    }

    static double headingDeg(HelicopterPhysics h) {
        Vec3 f = new Vec3();
        h.orientation.forward(f);
        return deg(Math.atan2(f.x, -f.z));
    }

    public static void main(String[] a) {
        System.out.println("=== 1. SITTING ON THE PAD, lever at HOVER_COLLECTIVE ===");
        {
            HelicopterPhysics h = fresh(0.9);
            for (int i = 0; i < 60 * 8; i++) { h.step(DT, FLAT, 0, 0); }
            System.out.printf("  after 8 s: alt=%.2f m  vy=%.2f m/s  GE=%.3f  T/W=%.3f%n",
                    h.position.y, h.velocity.y, h.groundEffectFactor, h.thrustToWeight());
        }

        System.out.println("=== 2. HOVER AT 200 m, hands off ===");
        {
            HelicopterPhysics h = fresh(200);
            double minA = 1e9, maxA = -1e9;
            for (int i = 0; i < 60 * 20; i++) {
                h.step(DT, FLAT, 0, 0);
                minA = Math.min(minA, h.position.y);
                maxA = Math.max(maxA, h.position.y);
            }
            System.out.printf("  after 20 s: alt=%.1f (min %.1f max %.1f) vy=%.2f "
                    + "pitch=%.1f roll=%.1f drift=%.1f m  T/W=%.3f%n",
                    h.position.y, minA, maxA, h.velocity.y, pitchDeg(h), rollDeg(h),
                    Math.hypot(h.position.x, h.position.z), h.thrustToWeight());
        }

        System.out.println("=== 3. CLIMB: full collective from 100 m ===");
        {
            HelicopterPhysics h = fresh(100);
            h.collective = 1.0;
            double t0 = -1;
            for (int i = 0; i < 60 * 30; i++) {
                h.step(DT, FLAT, 0, 0);
                if (t0 < 0 && h.position.y > 110) { t0 = i * DT; }
            }
            System.out.printf("  30 s: alt=%.0f vy=%.2f m/s  rotor=%.0f%%  10 m in %.2f s%n",
                    h.position.y, h.velocity.y, h.rotorPercent(), t0);
        }

        System.out.println("=== 4. FORWARD FLIGHT: cyclic steps, 40 s each ===");
        for (double stick : new double[]{-0.25, -0.5, -0.75, -1.0}) {
            HelicopterPhysics h = fresh(300);
            h.cyclicPitch = stick;
            for (int i = 0; i < 60 * 40; i++) { h.step(DT, FLAT, 0, 0); }
            double spd = Math.hypot(h.velocity.x, h.velocity.z);
            System.out.printf("  stick %.2f -> %.1f m/s (%.0f km/h) pitch=%+.1f deg "
                    + "alt=%.0f vy=%+.2f%n",
                    stick, spd, spd * 3.6, pitchDeg(h), h.position.y, h.velocity.y);
        }

        System.out.println("=== 5. ALTITUDE HOLD IN FORWARD FLIGHT (half stick, 40 s) ===");
        {
            HelicopterPhysics h = fresh(300);
            h.cyclicPitch = -0.5;
            double min = 1e9, max = -1e9;
            for (int i = 0; i < 60 * 40; i++) {
                h.step(DT, FLAT, 0, 0);
                min = Math.min(min, h.position.y); max = Math.max(max, h.position.y);
            }
            System.out.printf("  alt 300 -> %.0f  (min %.0f max %.0f)  swing %.0f m%n",
                    h.position.y, min, max, max - min);
        }

        System.out.println("=== 6. ROLL / TURN: half right cyclic from a hover, 15 s ===");
        {
            HelicopterPhysics h = fresh(300);
            h.cyclicRoll = 0.5;
            for (int i = 0; i < 60 * 15; i++) { h.step(DT, FLAT, 0, 0); }
            System.out.printf("  roll=%+.1f deg  x=%+.1f z=%+.1f  heading=%+.1f  "
                    + "speed=%.1f m/s%n", rollDeg(h), h.position.x, h.position.z,
                    headingDeg(h), Math.hypot(h.velocity.x, h.velocity.z));
        }

        System.out.println("=== 7. PEDAL: half right pedal from a hover, 10 s ===");
        {
            HelicopterPhysics h = fresh(300);
            h.pedal = 0.5;
            double[] hd = new double[4];
            for (int i = 0; i < 60 * 10; i++) {
                h.step(DT, FLAT, 0, 0);
                if (i == 60 * 2) { hd[0] = headingDeg(h); }
                if (i == 60 * 4) { hd[1] = headingDeg(h); }
            }
            System.out.printf("  heading after 2 s=%+.0f  4 s=%+.0f  10 s=%+.0f  "
                    + "yaw rate=%+.1f deg/s%n", hd[0], hd[1], headingDeg(h),
                    deg(h.angularVelocity.y));
        }

        System.out.println("=== 8. FEET OFF THE PEDALS: does it spin? 20 s hover ===");
        {
            HelicopterPhysics h = fresh(300);
            for (int i = 0; i < 60 * 20; i++) { h.step(DT, FLAT, 0, 0); }
            System.out.printf("  heading drift=%+.1f deg  yaw rate=%+.2f deg/s%n",
                    headingDeg(h), deg(h.angularVelocity.y));
        }

        System.out.println("=== 9. LANDING: descend from 60 m on 0.46 collective ===");
        {
            HelicopterPhysics h = fresh(60);
            h.collective = 0.46;
            double touch = -1; double impact = 0;
            for (int i = 0; i < 60 * 60; i++) {
                double before = h.velocity.y;
                h.step(DT, FLAT, 0, 0);
                if (touch < 0 && h.position.y < 1.3) { touch = i * DT; impact = before; }
            }
            System.out.printf("  touched at t=%.1f s at %.2f m/s; settled alt=%.2f vy=%.2f%n",
                    touch, impact, h.position.y, h.velocity.y);
        }

        System.out.println("=== 10. AUTOROTATION: engine off at 500 m ===");
        {
            HelicopterPhysics h = fresh(500);
            h.engineOn = false;
            h.collective = 0.15;
            for (int i = 0; i < 60 * 25; i++) { h.step(DT, FLAT, 0, 0); }
            System.out.printf("  25 s: alt=%.0f vy=%.2f m/s rotor=%.0f%%%n",
                    h.position.y, h.velocity.y, h.rotorPercent());
        }

        System.out.println("=== 11. CONTROL CHAIN: left stick full up for 2 s ===");
        {
            InputState in = new InputState();
            in.reset();
            boolean[] held = new boolean[16];
            boolean[] edge = new boolean[16];
            for (int i = 0; i < 120; i++) {
                ControlMapping.apply(ControlProfile.HELICOPTER, in, 0, -1f, 0, 0,
                        held, edge, DT);
            }
            System.out.printf("  lever=%.3f collective=%.3f (started %.3f)%n",
                    in.collectiveLever, in.collective,
                    HelicopterPhysics.HOVER_COLLECTIVE);
        }

        System.out.println("=== 12. TAKEOFF FROM THE PAD: lever up for 3 s then hold ===");
        {
            HelicopterPhysics h = fresh(0.9);
            InputState in = new InputState();
            in.reset();
            boolean[] held = new boolean[16];
            boolean[] edge = new boolean[16];
            for (int i = 0; i < 60 * 12; i++) {
                float ly = i < 180 ? -1f : 0f;
                ControlMapping.apply(ControlProfile.HELICOPTER, in, 0, ly, 0, 0,
                        held, edge, DT);
                h.collective = in.collective;
                h.step(DT, FLAT, 0, 0);
                if (i % 120 == 0) {
                    System.out.printf("    t=%4.1f lever=%.2f alt=%7.2f vy=%+6.2f%n",
                            i * DT, in.collective, h.position.y, h.velocity.y);
                }
            }
        }
    }
}
