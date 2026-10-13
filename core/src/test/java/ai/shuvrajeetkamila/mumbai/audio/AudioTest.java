package ai.shuvrajeetkamila.mumbai.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.sim.Agents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The audio is synthesised, so it can be checked the same way the physics
 * is: render a buffer and measure it. These tests never open a device.
 */
final class AudioTest {

    /** Root-mean-square of one channel of an interleaved buffer. */
    private static double rms(short[] buf, int frames, int channel) {
        double sum = 0;
        for (int i = 0; i < frames; i++) {
            double v = buf[i * 2 + channel] / 32768.0;
            sum += v * v;
        }
        return Math.sqrt(sum / frames);
    }

    /**
     * Energy at one frequency, by Goertzel.
     *
     * <p>Counting zero crossings is not good enough here: the induction
     * noise crosses zero constantly and swamps the engine note, which
     * makes the estimate a measure of the filter cutoff rather than the
     * pitch.
     */
    private static double goertzel(short[] buf, int frames, double hz) {
        double w = Synth.TAU * hz / Synth.SAMPLE_RATE;
        double coeff = 2 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < frames; i++) {
            double s0 = buf[i * 2] / 32768.0 + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }

    /** The strongest frequency in a band, found by brute force. */
    private static double dominantHz(short[] buf, int frames,
                                     double lo, double hi) {
        double best = lo, bestPower = -1;
        for (double hz = lo; hz <= hi; hz += 1.0) {
            double p = goertzel(buf, frames, hz);
            if (p > bestPower) {
                bestPower = p;
                best = hz;
            }
        }
        return best;
    }

    private static short[] render(AudioMixer m, int frames) {
        short[] buf = new short[frames * 2];
        m.render(buf, frames);
        return buf;
    }

    // ------------------------------------------------------------ synthesis

    @Test
    @DisplayName("a band-limited saw never aliases, however high it is pitched")
    void sawStaysBandLimited() {
        // The partial count has to fall as pitch rises. If it does not,
        // harmonics fold back and an engine at high revs buzzes.
        for (double hz : new double[] {50, 200, 800, 3000, 9000}) {
            int n = Synth.partialsFor(hz);
            double highest = hz * n;
            assertTrue(highest <= Synth.SAMPLE_RATE * 0.5 + 1e-9,
                    "at " + hz + " Hz the top partial is " + highest
                            + " Hz, past Nyquist");
        }
    }

    @Test
    @DisplayName("every waveform stays inside the rails")
    void waveformsAreBounded() {
        for (int i = 0; i < 1000; i++) {
            double p = i / 1000.0;
            assertTrue(Math.abs(Synth.saw(p, 12)) < 1.25, "saw at " + p);
            assertTrue(Math.abs(Synth.square(p, 11)) < 1.35, "square at " + p);
            assertTrue(Math.abs(Synth.sine(p)) <= 1.0, "sine at " + p);
        }
    }

    @Test
    @DisplayName("the soft clipper is monotonic and never exceeds unity")
    void softClipIsSane() {
        double prev = -2;
        for (double v = -3; v <= 3; v += 0.01) {
            double c = Synth.softClip(v);
            assertTrue(Math.abs(c) <= 1.0 + 1e-9, "clip(" + v + ") = " + c);
            assertTrue(c >= prev - 1e-9, "clipper went backwards at " + v);
            prev = c;
        }
        // Small signals must come through essentially untouched, or the
        // whole mix is quietly distorted.
        assertEquals(0.1, Synth.softClip(0.1), 0.002);
    }

    @Test
    @DisplayName("the low pass actually removes high frequencies")
    void lowPassAttenuates() {
        int n = 4096;
        double stateLow = 0, stateHigh = 0;
        double sumLow = 0, sumHigh = 0;
        // Same 4 kHz tone through a wide filter and a narrow one.
        for (int i = 0; i < n; i++) {
            double in = Math.sin(Synth.TAU * 4000 * i / Synth.SAMPLE_RATE);
            stateLow = Synth.lowPass(stateLow, in, 300);
            stateHigh = Synth.lowPass(stateHigh, in, 9000);
            if (i > n / 2) {
                sumLow += stateLow * stateLow;
                sumHigh += stateHigh * stateHigh;
            }
        }
        assertTrue(sumLow < sumHigh * 0.25,
                "a 300 Hz cutoff passed " + sumLow + " of a 4 kHz tone vs "
                        + sumHigh + " at 9 kHz");
    }

    // ------------------------------------------------------------ panning

    @Test
    @DisplayName("a source on your right is louder in the right ear")
    void panningFollowsTheListener() {
        double[] out = new double[2];
        // Facing north (heading 0, forward -Z). East is +X, to the right.
        Synth.pan(30, 0, 0, 10, 200, out);
        assertTrue(out[1] > out[0] * 1.3,
                "source to the east should favour the right ear, got L="
                        + out[0] + " R=" + out[1]);

        Synth.pan(-30, 0, 0, 10, 200, out);
        assertTrue(out[0] > out[1] * 1.3,
                "source to the west should favour the left ear, got L="
                        + out[0] + " R=" + out[1]);

        // Dead ahead is even.
        Synth.pan(0, -30, 0, 10, 200, out);
        assertEquals(out[0], out[1], 0.02);
    }

    @Test
    @DisplayName("turning your head swaps the ears")
    void panningRotatesWithHeading() {
        double[] facingNorth = new double[2];
        double[] facingSouth = new double[2];
        Synth.pan(30, 0, 0, 10, 200, facingNorth);
        Synth.pan(30, 0, Math.PI, 10, 200, facingSouth);
        assertTrue(facingNorth[1] > facingNorth[0], "right ear when facing north");
        assertTrue(facingSouth[0] > facingSouth[1],
                "the same source must move to the left ear once you turn round");
    }

    @Test
    @DisplayName("sound gets quieter with distance and reaches silence")
    void attenuationIsMonotonicAndTerminates() {
        double[] out = new double[2];
        double prev = Double.MAX_VALUE;
        for (double d = 1; d <= 250; d += 1) {
            Synth.pan(0, -d, 0, 10, 200, out);
            double g = Math.hypot(out[0], out[1]);
            assertTrue(g <= prev + 1e-9,
                    "gain rose from " + prev + " to " + g + " at " + d + " m");
            prev = g;
        }
        Synth.pan(0, -200, 0, 10, 200, out);
        assertEquals(0.0, out[0] + out[1], 1e-9,
                "a source at max range must be silent, not merely quiet,"
                        + " or it clicks off when culled");
    }

    @Test
    @DisplayName("Doppler is bounded even at absurd closing speeds")
    void dopplerIsClamped() {
        assertTrue(Synth.doppler(0) == 1.0, "no shift when nothing moves");
        assertTrue(Synth.doppler(25) > 1.0, "approaching raises the pitch");
        assertTrue(Synth.doppler(-25) < 1.0, "receding lowers it");
        // A physics glitch must not produce a shriek or a DC offset.
        assertTrue(Synth.doppler(5000) <= 1.35, "runaway approach");
        assertTrue(Synth.doppler(-5000) >= 0.75, "runaway retreat");
    }

    // ------------------------------------------------------------ engines

    @Test
    @DisplayName("firing frequency tracks revs and cylinder count")
    void engineHzFollowsTheCrankshaft() {
        // A four-cylinder four-stroke fires twice a revolution.
        assertEquals(100.0, AudioMixer.engineHz(3000, 4), 0.01);
        // Doubling the revs doubles the note.
        assertEquals(200.0, AudioMixer.engineHz(6000, 4), 0.01);
        // A rickshaw's single cylinder is two octaves below the four-pot.
        assertTrue(AudioMixer.engineHz(3000, 1) < AudioMixer.engineHz(3000, 4));
        // Even stopped it must return something positive, or the phase
        // accumulator stalls and the voice goes to DC.
        assertTrue(AudioMixer.engineHz(0, 4) > 0);
    }

    @Test
    @DisplayName("the player's engine actually changes pitch with revs")
    void engineNoteRisesWithRevs() {
        AudioMixer m = new AudioMixer();
        m.master = 1.0;
        m.playerCylinders = 4;
        m.playerLoad = 0.25;

        m.playerRpm = 1000;
        for (int i = 0; i < 40; i++) { render(m, 512); }   // let it settle
        double low = dominantHz(render(m, 8192), 8192, 10, 400);

        m.playerRpm = 5000;
        for (int i = 0; i < 40; i++) { render(m, 512); }
        double high = dominantHz(render(m, 8192), 8192, 10, 400);

        // Five times the revs is five times the note. Asserting the ratio
        // rather than an absolute pitch keeps this honest whichever
        // partial happens to dominate.
        double ratio = high / low;
        assertTrue(ratio > 4.0 && ratio < 6.0,
                "revving 1000 -> 5000 rpm should raise the note five-fold,"
                        + " got " + low + " Hz then " + high + " Hz (x"
                        + ratio + ")");
    }

    @Test
    @DisplayName("on foot there is no engine")
    void silenceWhenNotDriving() {
        AudioMixer m = new AudioMixer();
        m.playerRpm = 0;
        m.rainLevel = 0;
        m.windLevel = 0;
        for (int i = 0; i < 10; i++) { render(m, 512); }
        short[] buf = render(m, 2048);
        assertEquals(0.0, rms(buf, 2048, 0), 1e-6,
                "a walking player in clear weather must be silent");
    }

    @Test
    @DisplayName("rain is audible and gets louder as it gets heavier")
    void rainScalesWithWeather() {
        AudioMixer m = new AudioMixer();
        m.master = 1.0;
        m.rainLevel = 0.2;
        for (int i = 0; i < 40; i++) { render(m, 512); }
        double light = rms(render(m, 4096), 4096, 0);

        m.rainLevel = 1.0;
        for (int i = 0; i < 40; i++) { render(m, 512); }
        double heavy = rms(render(m, 4096), 4096, 0);

        assertTrue(light > 1e-4, "drizzle should still be audible");
        assertTrue(heavy > light * 2.0,
                "a downpour must be clearly louder than drizzle, got "
                        + light + " then " + heavy);
    }

    // ------------------------------------------------------------ mixing

    @Test
    @DisplayName("a full, loud mix never wraps round into noise")
    void mixNeverClipsHard() {
        AudioMixer m = new AudioMixer();
        m.master = 1.0;
        m.playerRpm = 6500;
        m.playerLoad = 1.0;
        m.rainLevel = 1.0;
        m.windLevel = 1.0;
        m.impactPending = 1.0;
        // Every slot busy with a siren right on top of the listener.
        for (AudioMixer.Source s : m.sources()) {
            s.active = true;
            s.kind = AudioMixer.KIND_SIREN;
            s.x = 1;
            s.z = 1;
        }
        for (int i = 0; i < 80; i++) {
            short[] buf = render(m, 512);
            for (int k = 0; k < buf.length; k++) {
                // Wrapping shows up as a sample at the opposite rail; the
                // clipper must have caught it long before here.
                assertTrue(buf[k] > -32768 && buf[k] < 32767,
                        "sample " + k + " hit the rail at " + buf[k]);
            }
        }
    }

    @Test
    @DisplayName("sources fade in rather than popping")
    void newSourcesRampUp() {
        AudioMixer m = new AudioMixer();
        m.master = 1.0;
        AudioMixer.Source s = m.sources()[0];
        s.active = true;
        s.kind = AudioMixer.KIND_SIREN;
        s.x = 5;
        s.z = 0;
        double first = rms(render(m, 256), 256, 0);
        for (int i = 0; i < 40; i++) { render(m, 256); }
        double settled = rms(render(m, 256), 256, 0);
        assertTrue(first < settled * 0.6,
                "the first buffer of a new source should be well below its"
                        + " settled level, got " + first + " vs " + settled);
    }

    @Test
    @DisplayName("rendering is deterministic")
    void renderIsReproducible() {
        // Two mixers in the same state must produce the same samples. The
        // noise generator is seeded, not random, so a failure here means
        // something is reading the clock.
        AudioMixer a = new AudioMixer();
        AudioMixer b = new AudioMixer();
        for (AudioMixer m : new AudioMixer[] {a, b}) {
            m.playerRpm = 3200;
            m.playerLoad = 0.7;
            m.rainLevel = 0.5;
        }
        for (int i = 0; i < 10; i++) {
            short[] ba = render(a, 512);
            short[] bb = render(b, 512);
            for (int k = 0; k < ba.length; k++) {
                assertEquals(ba[k], bb[k], "buffer " + i + " sample " + k);
            }
        }
    }

    @Test
    @DisplayName("the mixer allocates nothing per buffer")
    void renderDoesNotAllocate() {
        // An audio thread that makes garbage eventually meets the collector
        // mid-buffer and you hear it. Measuring allocation directly is not
        // portable, so this checks the proxy that matters: a long run at
        // full load must not grow the heap meaningfully.
        AudioMixer m = new AudioMixer();
        m.playerRpm = 4000;
        m.playerLoad = 1;
        m.rainLevel = 1;
        m.windLevel = 1;
        for (AudioMixer.Source s : m.sources()) {
            s.active = true;
            s.kind = AudioMixer.KIND_ENGINE;
            s.rpm = 3000;
            s.x = 10;
        }
        short[] buf = new short[512 * 2];
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 200; i++) { m.render(buf, 512); }
        System.gc();
        long before = rt.totalMemory() - rt.freeMemory();
        for (int i = 0; i < 4000; i++) { m.render(buf, 512); }
        long after = rt.totalMemory() - rt.freeMemory();
        long grew = after - before;
        // 4000 buffers is about 90 seconds of audio. Anything allocating
        // per buffer would be far past this.
        assertTrue(grew < 4_000_000,
                "heap grew " + grew + " bytes over 4000 buffers, which means"
                        + " the render path is allocating");
    }

    // ------------------------------------------------------------ director

    private static Agents.Car car(double x, double z, boolean police,
                                  double horn, double speed) {
        Agents.Car c = new Agents.Car();
        c.active = true;
        c.x = x;
        c.z = z;
        c.speed = speed;
        c.police = police;
        c.hornTimer = horn;
        c.kindOrdinal = VehicleKind.SEDAN.ordinal();
        return c;
    }

    @Test
    @DisplayName("a distant siren beats a close engine for a voice")
    void sirensOutrankEngines() {
        // Priority, not distance, decides. Knowing the police are behind
        // you matters more than a parked tempo idling at your elbow.
        double closeEngine = AudioDirector.priority(false, false, 5);
        double farSiren = AudioDirector.priority(true, false, 200);
        assertTrue(farSiren > closeEngine,
                "a siren 200 m away scored " + farSiren
                        + " against an engine 5 m away at " + closeEngine);
        assertTrue(AudioDirector.priority(false, true, 50)
                > AudioDirector.priority(false, false, 5), "horns beat engines");
    }

    @Test
    @DisplayName("out-of-range sources are dropped, not just quietened")
    void outOfRangeIsRejected() {
        assertTrue(AudioDirector.priority(false, false,
                AudioDirector.ENGINE_RANGE + 1) < 0);
        assertTrue(AudioDirector.priority(true, false,
                AudioDirector.SIREN_RANGE + 1) < 0);
        // And within range it must be positive, or everything is culled.
        assertTrue(AudioDirector.priority(false, false, 10) > 0);
    }

    @Test
    @DisplayName("more traffic than voices keeps the important ones")
    void directorKeepsThePriorityCases() {
        AudioMixer m = new AudioMixer();
        AudioDirector d = new AudioDirector();
        Agents.Car[] cars = new Agents.Car[40];
        // Thirty-nine ordinary cars in a ring, plus one police car further
        // out than any of them.
        for (int i = 0; i < 39; i++) {
            double a = i / 39.0 * Math.PI * 2;
            cars[i] = car(Math.cos(a) * 30, Math.sin(a) * 30, false, 0, 8);
        }
        cars[39] = car(0, 180, true, 0, 20);

        d.update(m, cars, 0, 0, 0, 0, 0, 2);

        int sirens = 0, active = 0;
        for (AudioMixer.Source s : m.sources()) {
            if (!s.active) { continue; }
            active++;
            if (s.kind == AudioMixer.KIND_SIREN) { sirens++; }
        }
        assertEquals(AudioMixer.MAX_SOURCES, active, "every slot should be used");
        assertEquals(1, sirens,
                "the police car must get a voice even though 39 cars are closer");
    }

    @Test
    @DisplayName("police are silent until they are actually after you")
    void noSirenWithoutAWantedLevel() {
        AudioMixer m = new AudioMixer();
        AudioDirector d = new AudioDirector();
        Agents.Car[] cars = {car(0, 20, true, 0, 10)};

        d.update(m, cars, 0, 0, 0, 0, 0, 0);
        assertFalse(m.sources()[0].kind == AudioMixer.KIND_SIREN
                        && m.sources()[0].active,
                "a parked patrol car should not be wailing at a law-abiding"
                        + " player");

        d.update(m, cars, 0, 0, 0, 0, 0, 1);
        assertTrue(m.sources()[0].active
                        && m.sources()[0].kind == AudioMixer.KIND_SIREN,
                "once you are wanted it must sound");
    }

    @Test
    @DisplayName("closing speed is positive only when the gap is shrinking")
    void closingSpeedHasTheRightSign() {
        // Source south of the listener, driving north towards them.
        double c = AudioDirector.closingSpeed(0, 50, 0, -20, 0, 0, 0, 0);
        assertTrue(c > 0, "approaching should be positive, got " + c);

        // Same car, now driving away.
        double away = AudioDirector.closingSpeed(0, 50, 0, 20, 0, 0, 0, 0);
        assertTrue(away < 0, "receding should be negative, got " + away);

        // Both travelling the same way at the same speed: no shift at all.
        double convoy = AudioDirector.closingSpeed(0, 50, 0, -20, 0, 0, 0, -20);
        assertEquals(0.0, convoy, 1e-9,
                "two cars holding station should have no Doppler between them");

        // Crossing at right angles, instantaneously neither closing nor
        // opening.
        double cross = AudioDirector.closingSpeed(0, 50, 20, 0, 0, 0, 0, 0);
        assertEquals(0.0, cross, 1e-9);
    }

    @Test
    @DisplayName("a rickshaw does not sound like a bus")
    void cylinderCountsAreDistinct() {
        assertEquals(1, AudioDirector.cylindersFor(VehicleKind.AUTORICKSHAW));
        assertEquals(1, AudioDirector.cylindersFor(VehicleKind.SCOOTER));
        assertEquals(6, AudioDirector.cylindersFor(VehicleKind.DOUBLE_DECKER));
        assertTrue(AudioDirector.cylindersFor(VehicleKind.DOUBLE_DECKER)
                > AudioDirector.cylindersFor(VehicleKind.AUTORICKSHAW));
        // Every kind must map to something, including a null.
        for (VehicleKind k : VehicleKind.values()) {
            assertTrue(AudioDirector.cylindersFor(k) >= 1, k.name());
        }
        assertEquals(4, AudioDirector.cylindersFor(null));
    }

    @Test
    @DisplayName("inferred traffic revs stay in a believable band")
    void inferredRpmIsBounded() {
        for (VehicleKind k : VehicleKind.values()) {
            double idle = AudioDirector.inferredRpm(0, k);
            assertTrue(idle >= 500 && idle <= 1300,
                    k + " idles at " + idle + " rpm");
            double flat = AudioDirector.inferredRpm(40, k);
            assertTrue(flat <= 4300, k + " revs to " + flat);
            assertTrue(flat > idle, k + " must rev up as it speeds up");
        }
    }

    @Test
    @DisplayName("wind grows with speed and is nothing when stopped")
    void windFollowsSpeed() {
        assertEquals(0.0, AudioDirector.windFor(0), 1e-9);
        assertTrue(AudioDirector.windFor(10) < AudioDirector.windFor(25));
        assertTrue(AudioDirector.windFor(1000) <= 1.0, "must saturate");
        assertTrue(AudioDirector.windFor(-5) >= 0, "reverse is not negative wind");
    }

    @Test
    @DisplayName("the director reuses slots without leaving stale sources")
    void slotsAreClearedBetweenUpdates() {
        AudioMixer m = new AudioMixer();
        AudioDirector d = new AudioDirector();

        d.update(m, new Agents.Car[] {car(0, 20, false, 0, 10)}, 0, 0, 0, 0, 0, 0);
        assertTrue(m.sources()[0].active);

        // The car drives off into the distance; nothing should remain.
        d.update(m, new Agents.Car[] {car(0, 5000, false, 0, 10)},
                0, 0, 0, 0, 0, 0);
        for (AudioMixer.Source s : m.sources()) {
            assertFalse(s.active, "a source out of range was left active");
        }
    }

    @Test
    @DisplayName("a null or empty traffic array is survivable")
    void directorToleratesNoTraffic() {
        AudioMixer m = new AudioMixer();
        AudioDirector d = new AudioDirector();
        d.update(m, null, 0, 0, 0, 0, 0, 0);
        d.update(m, new Agents.Car[0], 0, 0, 0, 0, 0, 0);
        d.update(m, new Agents.Car[] {null, null}, 0, 0, 0, 0, 0, 0);
        // And it must still render afterwards.
        render(m, 512);
    }

    @Test
    @DisplayName("the listener pose is handed to the mixer")
    void directorUpdatesTheListener() {
        AudioMixer m = new AudioMixer();
        AudioDirector d = new AudioDirector();
        d.update(m, new Agents.Car[0], 123.0, -456.0, 0, 0, 1.25, 0);
        assertEquals(123.0, m.listenerX, 1e-9);
        assertEquals(-456.0, m.listenerZ, 1e-9);
        assertEquals(1.25, m.listenerHeading, 1e-9);
    }
}
