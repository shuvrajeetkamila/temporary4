package ai.shuvrajeetkamila.mumbai.world;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reader for the packaged .mwl world layers. Mirrors tools/world_format.py exactly.
 *
 * <p>Every layer is validated: magic bytes, format version and (via the manifest) a CRC-32.
 * A corrupt optional layer raises {@link WorldFormatException} so the game can show a
 * fallback instead of rendering a silent hole in Mumbai.
 */
public final class MwlReader {

    public static final int MAGIC = ('M') | ('W' << 8) | ('L' << 16) | ('1' << 24);
    public static final int FORMAT_VERSION = 1;

    public static final int LAYER_MANIFEST = 0;
    public static final int LAYER_TERRAIN = 1;
    public static final int LAYER_NETWORK = 2;
    public static final int LAYER_CITY = 3;
    public static final int LAYER_CHUNKS = 4;

    private final ByteBuffer buf;
    private final byte[] raw;
    public final int layerId;
    public final int version;

    public MwlReader(byte[] data) throws WorldFormatException {
        this.raw = data;
        if (data == null || data.length < 12) {
            throw new WorldFormatException("layer too small (" + (data == null ? 0 : data.length) + " B)");
        }
        buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt();
        if (magic != MAGIC) {
            throw new WorldFormatException("bad magic 0x" + Integer.toHexString(magic));
        }
        layerId = buf.getInt();
        version = buf.getInt();
        if (version != FORMAT_VERSION) {
            throw new WorldFormatException("unsupported world format version " + version);
        }
    }

    public int u8() {
        return buf.get() & 0xFF;
    }

    public int i8() {
        return buf.get();
    }

    public int u16() {
        return buf.getShort() & 0xFFFF;
    }

    public int i32() {
        return buf.getInt();
    }

    public long u32() {
        return buf.getInt() & 0xFFFFFFFFL;
    }

    public float f32() {
        return buf.getFloat();
    }

    public double f64() {
        return buf.getDouble();
    }

    public String string() {
        int n = u16();
        byte[] b = new byte[n];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Reads a (possibly zlib compressed) blob. */
    public byte[] blob() throws WorldFormatException {
        int compressed = u8();
        int rawSize = (int) u32();
        int stored = (int) u32();
        byte[] data = new byte[stored];
        buf.get(data);
        if (compressed == 0) {
            return data;
        }
        byte[] out = new byte[rawSize];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            int done = 0;
            while (done < rawSize) {
                int n = inflater.inflate(out, done, rawSize - done);
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput()) {
                        break;
                    }
                }
                done += n;
            }
            if (done != rawSize) {
                throw new WorldFormatException("inflate produced " + done + " of " + rawSize);
            }
        } catch (DataFormatException e) {
            throw new WorldFormatException("corrupt compressed block: " + e.getMessage());
        } finally {
            inflater.end();
        }
        return out;
    }

    public short[] blobAsShorts() throws WorldFormatException {
        byte[] b = blob();
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        short[] out = new short[b.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = bb.getShort();
        }
        return out;
    }

    public int[] blobAsUnsignedShorts() throws WorldFormatException {
        byte[] b = blob();
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[b.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = bb.getShort() & 0xFFFF;
        }
        return out;
    }

    public long crc32() {
        CRC32 crc = new CRC32();
        crc.update(raw, 0, raw.length);
        return crc.getValue();
    }

    public int remaining() {
        return buf.remaining();
    }

    /** Utility: slurp a stream fully (assets are small and read once at load). */
    public static byte[] readFully(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(4096, in.available()));
        byte[] tmp = new byte[64 * 1024];
        int n;
        while ((n = in.read(tmp)) > 0) {
            bos.write(tmp, 0, n);
        }
        return bos.toByteArray();
    }

    /** Thrown for any structural problem in packaged world data. */
    public static class WorldFormatException extends Exception {
        public WorldFormatException(String msg) {
            super(msg);
        }
    }
}
