package ai.shuvrajeetkamila.mumbai.physics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sea the boat floats on has to be the same sea the renderer draws, or
 * boats sit in the air above the waves.
 */
class OceanTest {

    @Test
    @DisplayName("the surface is bounded and continuous")
    void surfaceIsSane() {
        Ocean o = new Ocean();
        o.setTime(1234.5);
        double min = 1e9, max = -1e9;
        for (int i = 0; i < 4000; i++) {
            double x = (i % 200) * 37.1;
            double z = (i / 200) * 51.3;
            double h = o.heightAt(x, z);
            assertTrue(Double.isFinite(h), "non-finite height at " + x + "," + z);
            min = Math.min(min, h);
            max = Math.max(max, h);
        }
        assertTrue(max - min > 0.3, "the sea should not be a flat plane");
        assertTrue(max - min < 14, "wave range of " + (max - min) + " m is a tsunami");
    }

    @Test
    @DisplayName("neighbouring points agree, so the surface has no cliffs")
    void continuity() {
        Ocean o = new Ocean();
        o.setTime(77.0);
        for (int i = 0; i < 500; i++) {
            double x = i * 13.7, z = i * -9.1;
            double a = o.heightAt(x, z);
            double b = o.heightAt(x + 0.1, z);
            assertTrue(Math.abs(a - b) < 0.35,
                    "0.1 m apart differed by " + Math.abs(a - b) + " m");
        }
    }

    @Test
    @DisplayName("the normal matches the slope of the height field")
    void normalMatchesHeight() {
        Ocean o = new Ocean();
        o.setTime(400.0);
        double[] n = new double[3];
        double e = 0.25;
        for (int i = 0; i < 80; i++) {
            double x = i * 31.0, z = i * 17.0;
            o.normalAt(x, z, n);
            double dx = (o.heightAt(x + e, z) - o.heightAt(x - e, z)) / (2 * e);
            double dz = (o.heightAt(x, z + e) - o.heightAt(x, z - e)) / (2 * e);
            // Analytic normal for y = h(x, z) is (-dh/dx, 1, -dh/dz) normalised.
            double len = Math.sqrt(dx * dx + 1 + dz * dz);
            assertEquals(-dx / len, n[0], 0.16, "nx disagreed at " + x);
            assertEquals(-dz / len, n[2], 0.16, "nz disagreed at " + x);
            assertTrue(n[1] > 0.6, "the normal should point up, got " + n[1]);
        }
    }

    @Test
    @DisplayName("vertical velocity is the time derivative of the height")
    void verticalVelocity() {
        Ocean o = new Ocean();
        double dt = 0.01;
        for (int i = 0; i < 40; i++) {
            double x = i * 61.0, z = i * -23.0;
            o.setTime(200 + i);
            double v = o.verticalVelocityAt(x, z);
            double h0 = o.heightAt(x, z);
            o.setTime(200 + i + dt);
            double h1 = o.heightAt(x, z);
            assertEquals((h1 - h0) / dt, v, 0.45,
                    "dh/dt disagreed with verticalVelocityAt at " + x);
        }
    }

    @Test
    @DisplayName("the tide is a real M2 cycle, not a constant")
    void tide() {
        Ocean o = new Ocean();
        double min = 1e9, max = -1e9;
        for (int t = 0; t < 90000; t += 300) {
            o.setTime(t);
            double h = o.tide();
            min = Math.min(min, h);
            max = Math.max(max, h);
        }
        double range = max - min;
        // Mumbai sees a spring range around 4 to 5 m; this is a single
        // constituent so a 3-4 m range is the right order.
        assertTrue(range > 2.0 && range < 6.0,
                "tidal range of " + range + " m is not Mumbai");
    }

    @Test
    @DisplayName("current is finite and gentle")
    void current() {
        Ocean o = new Ocean();
        double[] c = new double[2];
        for (int i = 0; i < 300; i++) {
            o.setTime(i * 97.0);
            o.currentAt(i * 51.0, i * -33.0, c);
            double s = Math.hypot(c[0], c[1]);
            assertTrue(Double.isFinite(s), "non-finite current");
            assertTrue(s < 5.0, "a " + s + " m/s current is a river in spate");
        }
    }
}
