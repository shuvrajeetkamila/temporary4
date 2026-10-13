package android.media;

/** Local type-check stub. Not shipped; the device provides the real class. */
public final class AudioTrack {
    public static final int MODE_STREAM = 1;
    public static final int WRITE_BLOCKING = 0;
    public static final int STATE_INITIALIZED = 1;
    public static final int PLAYSTATE_PLAYING = 3;

    public static int getMinBufferSize(int rate, int channels, int encoding) { return 0; }

    public static final class Builder {
        public Builder setAudioAttributes(AudioAttributes a) { return this; }
        public Builder setAudioFormat(AudioFormat f) { return this; }
        public Builder setBufferSizeInBytes(int b) { return this; }
        public Builder setTransferMode(int m) { return this; }
        public AudioTrack build() { return new AudioTrack(); }
    }

    public int getState() { return 0; }
    public int getPlayState() { return 0; }
    public void play() { }
    public void pause() { }
    public void stop() { }
    public void flush() { }
    public void release() { }
    public void setVolume(float v) { }
    public int write(short[] data, int offset, int size, int mode) { return 0; }
}
