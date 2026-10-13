package ai.shuvrajeetkamila.mumbai.android;

import ai.shuvrajeetkamila.mumbai.audio.AudioMixer;
import ai.shuvrajeetkamila.mumbai.audio.Synth;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Pushes the mixer's output at an {@link AudioTrack}.
 *
 * <p>Its own thread, because the renderer stalling for a second should
 * not silence the game and the audio hardware stalling should not drop a
 * frame. The thread does nothing but ask the mixer for the next buffer
 * and hand it over; every decision about what that buffer contains lives
 * in the engine-side classes, which is what makes the sound testable
 * without a device.
 */
public final class AudioOutput {

    private static final String TAG = "Mumbai";

    /**
     * Frames per buffer. At 22050 Hz this is about 23 ms, which is short
     * enough that a horn feels immediate and long enough that the thread
     * is not woken constantly on a cheap phone.
     */
    private static final int FRAMES = 512;

    private final AudioMixer mixer;
    private volatile boolean running;
    private Thread thread;
    private AudioTrack track;

    public AudioOutput(AudioMixer mixer) {
        this.mixer = mixer;
    }

    /** True once the track exists and the pump is running. */
    public boolean active() { return running; }

    /**
     * Opens the device and starts pumping.
     *
     * <p>Audio is the one subsystem that is never worth crashing for, so
     * every failure here is logged and swallowed: a phone with a busy or
     * missing output should still be playable in silence.
     */
    public synchronized void start() {
        if (running) { return; }
        try {
            int min = AudioTrack.getMinBufferSize(Synth.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            // Three buffers of headroom past whatever the device asks for.
            // Too tight and a scheduling hiccup is an audible gap.
            int bytes = Math.max(min, FRAMES * 2 * 2 * 3);

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Synth.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "MUMBAI_AUDIO_SKIP track did not initialise");
                track.release();
                track = null;
                return;
            }
            track.play();
            running = true;
            thread = new Thread(this::pump, "mumbai-audio");
            thread.setPriority(Thread.NORM_PRIORITY + 2);
            thread.start();
            Log.i(TAG, "MUMBAI_AUDIO_OK rate=" + Synth.SAMPLE_RATE
                    + " frames=" + FRAMES + " buffer=" + bytes);
        } catch (Throwable t) {
            Log.w(TAG, "MUMBAI_AUDIO_SKIP " + t, t);
            running = false;
            if (track != null) {
                try { track.release(); } catch (Throwable ignored) { }
                track = null;
            }
        }
    }

    private void pump() {
        short[] buf = new short[FRAMES * 2];
        AudioTrack t = track;
        while (running && t != null) {
            try {
                mixer.render(buf, FRAMES);
                // Blocking write is the clock: it returns when the device
                // has room, which paces this thread without a sleep.
                int n = t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
                if (n < 0) {
                    Log.w(TAG, "MUMBAI_AUDIO_SKIP write returned " + n);
                    break;
                }
            } catch (Throwable e) {
                Log.w(TAG, "MUMBAI_AUDIO_SKIP " + e);
                break;
            }
        }
    }

    /** Stops the pump and hands the device back. Safe to call twice. */
    public synchronized void stop() {
        running = false;
        Thread th = thread;
        thread = null;
        if (th != null) {
            try {
                th.join(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.stop();
            } catch (Throwable ignored) {
                // Already gone; nothing useful to do.
            }
            try { t.release(); } catch (Throwable ignored) { }
        }
    }
}
