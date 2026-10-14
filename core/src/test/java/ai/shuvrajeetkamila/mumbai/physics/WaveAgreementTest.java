package ai.shuvrajeetkamila.mumbai.physics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sea the player sees and the sea the boats float on.
 *
 * <p>Reported from play: after a while the water turns white and a
 * "moving carpet like transparent screen" covers the view, sometimes
 * cured by the graphics button and sometimes not. Measured, there were
 * two faults in the drawn surface and both are arithmetic.
 *
 * <p>First, the phase. The shader was handed elapsed time and worked out
 * the phase itself in 32-bit floats. The phase grows by a few radians a
 * second, so after an hour it is tens of thousands of radians, which a
 * float cannot resolve to anything like a wave crest. Worse, the surface
 * normal was a finite difference of two height samples 1.5 m apart -
 * subtracting two nearly equal large numbers, the one thing floating
 * point is worst at - and the lighting takes a 220-power specular off
 * that normal. Noise in the normal is a sheet of moving white.
 *
 * <p>Second, the wave set in the shader was simply not the wave set in
 * this class, despite a comment in the shader promising it was. Not one
 * of the twelve numbers matched.
 *
 * <p>The shader now receives the waves from {@link Ocean} as uniforms,
 * with the phase already wrapped into one cycle. These tests cover the
 * packing and the wrap; the shader has no constants left to drift.
 */
final class WaveAgreementTest {

    /** Rebuilds the height the shader will draw, from the packed uniforms. */
    private static double shaderHeight(float[] p, double x, double z,
                                       double tide) {
        double h = tide;
        for (int i = 0; i < Ocean.WAVES; i++) {
            double amp = p[i];
            double k = p[Ocean.WAVES + i];
            double dx = p[Ocean.WAVES * 2 + i];
            double dz = p[Ocean.WAVES * 3 + i];
            double phase = p[Ocean.WAVES * 4 + i];
            // The shader reduces the distance along the wave into one
            // wavelength before turning it into an angle.
            double lambda = 2 * Math.PI / k;
            double along = dx * x + dz * z;
            double reduced = along % lambda;
            h += amp * Math.sin(reduced * k + phase);
        }
        return h;
    }

    @Test
    @DisplayName("the drawn surface is the surface the boats float on")
    void theShaderDrawsWhatThePhysicsUses() {
        Ocean o = new Ocean();
        float[] p = new float[Ocean.WAVES * 5];
        double worst = 0;
        double[] times = {0, 1.7, 60, 600, 3600, 36000};
        double[][] places = {
            {0, 0}, {1234.5, -4321.0}, {-18000, 26000}, {45000, -39000},
            {-45000, 39000}, {7.25, 11.5},
        };
        for (double t : times) {
            o.setTime(t);
            o.waveParameters(p);
            for (double[] q : places) {
                double mine = shaderHeight(p, q[0], q[1], o.tide());
                double theirs = o.heightAt(q[0], q[1]);
                worst = Math.max(worst, Math.abs(mine - theirs));
            }
        }
        // A centimetre. A boat sitting a centimetre proud of the drawn
        // water is invisible; ten is not.
        assertTrue(worst < 0.01,
                "the drawn sea and the physical sea differ by up to "
                        + worst + " m. A hull will visibly float above or"
                        + " sink into the water it is drawn on.");
    }

    @Test
    @DisplayName("the phase stays small however long the session runs")
    void thePhaseIsWrapped() {
        // The failure this prevents: a phase of tens of thousands of
        // radians, where a 32-bit float can no longer tell one crest
        // from the next and the sea becomes noise.
        Ocean o = new Ocean();
        float[] p = new float[Ocean.WAVES * 5];
        for (double hours : new double[]{0, 0.5, 2, 12, 96, 1000}) {
            o.setTime(hours * 3600);
            o.waveParameters(p);
            for (int i = 0; i < Ocean.WAVES; i++) {
                double phase = p[Ocean.WAVES * 4 + i];
                assertTrue(phase >= 0 && phase < 2 * Math.PI + 1e-6,
                        "after " + hours + " h wave " + i + " has phase "
                                + phase + " radians");
            }
        }
    }

    @Test
    @DisplayName("a float can still resolve the wave after a long session")
    void theSurfaceSurvivesInSinglePrecision() {
        // The whole point of the wrap: redo the sum the way the shader
        // does, in 32-bit, and check it still matches the double
        // precision answer after a day of play.
        Ocean o = new Ocean();
        float[] p = new float[Ocean.WAVES * 5];
        o.setTime(24 * 3600);
        o.waveParameters(p);
        double worst = 0;
        for (double x = -40000; x <= 40000; x += 3137) {
            for (double z = -40000; z <= 40000; z += 4871) {
                float h = 0;
                for (int i = 0; i < Ocean.WAVES; i++) {
                    float k = p[Ocean.WAVES + i];
                    float lambda = (float) (2 * Math.PI) / k;
                    float along = p[Ocean.WAVES * 2 + i] * (float) x
                            + p[Ocean.WAVES * 3 + i] * (float) z;
                    float a = (along % lambda) * k + p[Ocean.WAVES * 4 + i];
                    h += p[i] * (float) Math.sin(a);
                }
                double exact = o.heightAt(x, z) - o.tide();
                worst = Math.max(worst, Math.abs(h - exact));
            }
        }
        assertTrue(worst < 0.02,
                "in single precision the surface is out by " + worst
                        + " m after a day of play");
    }

    @Test
    @DisplayName("the slope is the derivative, not a difference of heights")
    void theSlopeIsAnalytic() {
        // The normal used to be four extra height samples differenced
        // 1.5 m apart. This checks the closed form the shader now uses
        // against the model's own normal.
        Ocean o = new Ocean();
        o.setTime(1234.5);
        float[] p = new float[Ocean.WAVES * 5];
        o.waveParameters(p);
        double[] n = new double[3];
        double worst = 0;
        for (double x = -500; x <= 500; x += 137) {
            for (double z = -500; z <= 500; z += 149) {
                double sx = 0, sz = 0;
                for (int i = 0; i < Ocean.WAVES; i++) {
                    double amp = p[i], k = p[Ocean.WAVES + i];
                    double dx = p[Ocean.WAVES * 2 + i];
                    double dz = p[Ocean.WAVES * 3 + i];
                    double lambda = 2 * Math.PI / k;
                    double a = ((dx * x + dz * z) % lambda) * k
                            + p[Ocean.WAVES * 4 + i];
                    sx += amp * k * Math.cos(a) * dx;
                    sz += amp * k * Math.cos(a) * dz;
                }
                double len = Math.sqrt(sx * sx + 1 + sz * sz);
                o.normalAt(x, z, n);
                worst = Math.max(worst, Math.abs(-sx / len - n[0]));
                worst = Math.max(worst, Math.abs(-sz / len - n[2]));
            }
        }
        assertTrue(worst < 1e-5,
                "the shader's slope and the model's normal disagree by "
                        + worst);
    }

    @Test
    @DisplayName("choppiness reaches the drawn surface")
    void choppinessIsNotLostInThePacking() {
        // The amplitude handed to the shader has choppiness folded in,
        // because the shader no longer has a choppiness uniform. If that
        // fold were dropped the sea would stop responding to the monsoon.
        Ocean o = new Ocean();
        float[] calm = new float[Ocean.WAVES * 5];
        float[] rough = new float[Ocean.WAVES * 5];
        o.setChoppiness(0.5);
        o.waveParameters(calm);
        o.setChoppiness(2.0);
        o.waveParameters(rough);
        for (int i = 0; i < Ocean.WAVES; i++) {
            assertEquals(calm[i] * 4.0, rough[i], 1e-5,
                    "wave " + i + " does not scale with the sea state");
        }
    }
}
