package android.media;

/** Local type-check stub. Not shipped; the device provides the real class. */
public final class AudioFormat {
    public static final int ENCODING_PCM_16BIT = 2;
    public static final int CHANNEL_OUT_STEREO = 12;

    public static final class Builder {
        public Builder setEncoding(int e) { return this; }
        public Builder setSampleRate(int r) { return this; }
        public Builder setChannelMask(int m) { return this; }
        public AudioFormat build() { return new AudioFormat(); }
    }
}
