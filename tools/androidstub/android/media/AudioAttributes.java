package android.media;

/** Local type-check stub. Not shipped; the device provides the real class. */
public final class AudioAttributes {
    public static final int USAGE_GAME = 14;
    public static final int CONTENT_TYPE_SONIFICATION = 4;

    public static final class Builder {
        public Builder setUsage(int u) { return this; }
        public Builder setContentType(int c) { return this; }
        public AudioAttributes build() { return new AudioAttributes(); }
    }
}
