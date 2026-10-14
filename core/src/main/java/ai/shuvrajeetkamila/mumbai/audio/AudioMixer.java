package ai.shuvrajeetkamila.mumbai.audio;

/**
 * The whole soundtrack, mixed a buffer at a time.
 *
 * <p>Nothing here is a recording. Engines are a sawtooth stack whose
 * fundamental follows the crankshaft speed the physics actually reports,
 * so the note rises through a gear and drops when it changes; the siren
 * is a two-tone wail; rain is filtered noise. That keeps every sound in
 * the game original, and it means the audio cannot drift out of step
 * with the simulation the way a looped sample does.
 *
 * <p>Threading: the game thread writes the fields below and the audio
 * thread reads them. They are plain doubles rather than a lock, because
 * a torn read here is a fraction of a decibel or a few hertz for one
 * buffer, and a lock on the audio path is how you get a dropout.
 */
public final class AudioMixer {

    /** Positional sources the mixer will carry at once. */
    public static final int MAX_SOURCES = 12;

    /** A positional noise in the world. */
    public static final class Source {
        public boolean active;
        /** One of the KIND_ constants. */
        public int kind;
        public double x, z;
        /** Metres per second along the line to the listener, for Doppler. */
        public double closing;
        /** 0..1, how hard it is working. Drives timbre as well as level. */
        public double load;
        /** Engine speed in rpm, for KIND_ENGINE. */
        public double rpm;
        /** Running phase, kept between buffers so nothing clicks. */
        double phase;
        double phase2;
        double filter;
        /** Smoothed gains, so a source appearing does not pop. */
        double gainL, gainR;
    }

    public static final int KIND_ENGINE = 0;
    public static final int KIND_SIREN = 1;
    public static final int KIND_HORN = 2;

    private final Source[] sources = new Source[MAX_SOURCES];

    // --- listener, written by the game thread ------------------------------
    public volatile double listenerX, listenerZ, listenerHeading;

    /** Player's own engine: rpm and load, or rpm <= 0 when on foot. */
    public volatile double playerRpm;
    public volatile double playerLoad;
    /** Cylinder count, so a rickshaw does not sound like a bus. */
    public volatile int playerCylinders = 4;

    /** Ambient beds, 0..1. */
    public volatile double rainLevel;
    public volatile double windLevel;

    /** Master, so the host can duck or mute everything. */
    public volatile double master = 0.85;

    /** Set from the game thread to fire a one-shot. Consumed by the mixer. */
    public volatile double impactPending;

    // --- audio-thread state ------------------------------------------------
    private double engPhase, engPhase2, engFilter;
    private double rainFilter, windFilter;
    private int noise = 0x1234567;
    private double impactEnv;
    private double impactPhase;
    private double rainSmooth, windSmooth, rpmSmooth, loadSmooth;

    private final double[] pan = new double[2];

    public AudioMixer() {
        for (int i = 0; i < sources.length; i++) { sources[i] = new Source(); }
    }

    public Source[] sources() { return sources; }

    /** Drops every positional source, for a teleport or a profile change. */
    public void clearSources() {
        for (Source s : sources) {
            s.active = false;
            s.gainL = 0;
            s.gainR = 0;
        }
    }

    /**
     * Engine fundamental for a crankshaft speed.
     *
     * <p>A four-stroke fires twice per revolution per pair of cylinders,
     * so the firing frequency is rpm/60 times cylinders/2. That is what
     * you actually hear, not the shaft rate: it is why a four-cylinder at
     * 3000 rpm sits around 100 Hz and a single-cylinder rickshaw at the
     * same revs is an octave and a half lower and far rougher.
     */
    public static double engineHz(double rpm, int cylinders) {
        return Math.max(8.0, rpm / 60.0 * Math.max(1, cylinders) * 0.5);
    }

    /**
     * Renders one buffer of interleaved stereo 16-bit samples.
     *
     * @param out    interleaved L,R; length must be even
     * @param frames how many stereo frames to write
     */
    /**
     * Fill {@code out} with {@code frames} stereo frames.
     *
     * <p>{@code out} must hold {@code 2 * frames} shorts. Reading the
     * signature the other way - passing the array length as the frame
     * count - used to walk off the end of the buffer, which is a crash in
     * the audio thread and therefore a crash with no useful stack. It is
     * clamped rather than thrown, because silence is a better failure than
     * a dead process.
     */
    public void render(short[] out, int frames) {
        frames = Math.min(frames, out.length / 2);
        if (frames <= 0) { return; }
        double inv = 1.0 / Synth.SAMPLE_RATE;
        double lx = listenerX, lz = listenerZ, lh = listenerHeading;

        // Smooth the controls once per buffer rather than per sample: a
        // buffer is a few milliseconds and the ear cannot tell, but it
        // keeps a torn read from the game thread out of the waveform.
        rainSmooth += (rainLevel - rainSmooth) * 0.25;
        windSmooth += (windLevel - windSmooth) * 0.25;
        rpmSmooth += (playerRpm - rpmSmooth) * 0.35;
        loadSmooth += (playerLoad - loadSmooth) * 0.25;

        double pending = impactPending;
        if (pending > 0) {
            impactPending = 0;
            impactEnv = Math.min(1.0, impactEnv + pending);
            impactPhase = 0;
        }

        // Player engine, dead centre and never attenuated.
        double engHz = engineHz(rpmSmooth, playerCylinders);
        int engPartials = Synth.partialsFor(engHz);
        boolean engineOn = rpmSmooth > 1.0;

        // Positional sources: compute their gains once per buffer.
        for (Source s : sources) {
            if (!s.active) {
                s.gainL += (0 - s.gainL) * 0.2;
                s.gainR += (0 - s.gainR) * 0.2;
                continue;
            }
            double ref = s.kind == KIND_SIREN ? 28.0 : 9.0;
            double max = s.kind == KIND_SIREN ? 260.0 : 95.0;
            Synth.pan(s.x - lx, s.z - lz, lh, ref, max, pan);
            double want = s.kind == KIND_ENGINE ? 0.34 : 0.55;
            s.gainL += (pan[0] * want - s.gainL) * 0.2;
            s.gainR += (pan[1] * want - s.gainR) * 0.2;
        }

        for (int i = 0; i < frames; i++) {
            double l = 0, r = 0;

            // --- the car you are in ---------------------------------------
            if (engineOn) {
                engPhase += engHz * inv;
                if (engPhase >= 1) { engPhase -= (int) engPhase; }
                engPhase2 += engHz * 0.503 * inv;      // slightly detuned
                if (engPhase2 >= 1) { engPhase2 -= (int) engPhase2; }
                double body = Synth.saw(engPhase, engPartials) * 0.6
                        + Synth.square(engPhase2, Math.max(1, engPartials / 2)) * 0.25;
                // Induction roar: noise that opens up with throttle.
                noise = Synth.noiseNext(noise);
                double air = Synth.noiseValue(noise) * loadSmooth * 0.30;
                engFilter = Synth.lowPass(engFilter, body + air,
                        320 + loadSmooth * 2600);
                double g = 0.16 + loadSmooth * 0.20;
                l += engFilter * g;
                r += engFilter * g;
            }

            // --- rain ------------------------------------------------------
            if (rainSmooth > 0.002) {
                noise = Synth.noiseNext(noise);
                double n = Synth.noiseValue(noise);
                rainFilter = Synth.lowPass(rainFilter, n, 2200 + rainSmooth * 3800);
                double g = rainSmooth * 0.22;
                // Decorrelate the ears a little so it sits around you.
                l += rainFilter * g;
                r += (rainFilter * 0.72 + n * 0.28) * g;
            }

            // --- wind, from how fast you are going --------------------------
            if (windSmooth > 0.002) {
                noise = Synth.noiseNext(noise);
                windFilter = Synth.lowPass(windFilter, Synth.noiseValue(noise),
                        140 + windSmooth * 900);
                double g = windSmooth * 0.18;
                l += windFilter * g;
                r += windFilter * g;
            }

            // --- everything out in the world --------------------------------
            for (Source s : sources) {
                if (s.gainL == 0 && s.gainR == 0) { continue; }
                double v;
                switch (s.kind) {
                    case KIND_SIREN: {
                        // Two-tone wail: a slow sweep between two pitches.
                        s.phase2 += 0.62 * inv;
                        if (s.phase2 >= 1) { s.phase2 -= 1; }
                        double sweep = s.phase2 < 0.5 ? s.phase2 * 2 : 2 - s.phase2 * 2;
                        double hz = 640 + sweep * 460;
                        s.phase += hz * inv;
                        if (s.phase >= 1) { s.phase -= (int) s.phase; }
                        v = Synth.sine(s.phase) * 0.6
                                + Synth.sine(s.phase * 2) * 0.18;
                        break;
                    }
                    case KIND_HORN: {
                        s.phase += 405 * inv;
                        if (s.phase >= 1) { s.phase -= (int) s.phase; }
                        s.phase2 += 508 * inv;         // a rough minor third
                        if (s.phase2 >= 1) { s.phase2 -= (int) s.phase2; }
                        v = (Synth.square(s.phase, 7) + Synth.square(s.phase2, 7))
                                * 0.22;
                        break;
                    }
                    default: {
                        double hz = engineHz(s.rpm, 4) * Synth.doppler(s.closing);
                        s.phase += hz * inv;
                        if (s.phase >= 1) { s.phase -= (int) s.phase; }
                        double raw = Synth.saw(s.phase, Synth.partialsFor(hz));
                        s.filter = Synth.lowPass(s.filter, raw,
                                260 + s.load * 1800);
                        v = s.filter;
                        break;
                    }
                }
                l += v * s.gainL;
                r += v * s.gainR;
            }

            // --- a bang -------------------------------------------------------
            if (impactEnv > 1e-4) {
                impactPhase += 90 * inv;
                if (impactPhase >= 1) { impactPhase -= 1; }
                noise = Synth.noiseNext(noise);
                double body = Synth.sine(impactPhase) * 0.6
                        + Synth.noiseValue(noise) * 0.4;
                double v = body * impactEnv * 0.5;
                l += v;
                r += v;
                impactEnv *= 0.9992;
                if (impactEnv < 1e-4) { impactEnv = 0; }
            }

            double m = master;
            short sl = toPcm(Synth.softClip(l * m));
            short sr = toPcm(Synth.softClip(r * m));
            out[i * 2] = sl;
            out[i * 2 + 1] = sr;
        }
    }

    private static short toPcm(double v) {
        int s = (int) Math.round(v * 32000.0);
        if (s > 32767) { s = 32767; }
        if (s < -32768) { s = -32768; }
        return (short) s;
    }
}
