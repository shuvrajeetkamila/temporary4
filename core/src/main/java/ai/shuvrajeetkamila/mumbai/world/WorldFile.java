package ai.shuvrajeetkamila.mumbai.world;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.InflaterInputStream;

/**
 * Reader for the MWL2 container written by {@code tools/world_format.py}.
 *
 * <p>A header, a directory of named layers, and zlib payloads. Each layer
 * carries the CRC32 of its uncompressed bytes, which is checked on inflate; a
 * world file that has been truncated by a failed install is then a clean
 * exception at load time instead of a building with a vertex at infinity
 * somewhere over Chembur an hour later.
 *
 * <p>Layers inflate lazily and are not cached here. The caller decides what to
 * hold: {@link WorldData} keeps the terrain rasters resident for the whole
 * session and re-reads chunk payloads on the streaming workers.
 */
public final class WorldFile {

    private static final int MAGIC = 0x324C574D;   // "MWL2" little-endian
    private static final int VERSION = 2;
    private static final int HEADER_BYTES = 4 + 4 + 4 + 4 + 8 + 32;
    private static final int DIR_ENTRY_BYTES = 16 + 4 + 4 + 8 + 8 + 8;

    /** One directory entry. */
    public static final class Layer {
        public final String name;
        public final int kind;
        public final int crc;
        public final long rawBytes;
        public final long storedBytes;
        public final long offset;

        Layer(String name, int kind, int crc, long raw, long stored, long offset) {
            this.name = name;
            this.kind = kind;
            this.crc = crc;
            this.rawBytes = raw;
            this.storedBytes = stored;
            this.offset = offset;
        }

        @Override public String toString() {
            return name + "[kind=" + kind + " raw=" + rawBytes + " stored=" + storedBytes + "]";
        }
    }

    public static final int KIND_RAW = 0;
    public static final int KIND_GRID_I16 = 1;
    public static final int KIND_GRID_U8 = 2;
    public static final int KIND_BITSET = 3;
    public static final int KIND_VECTOR = 4;
    public static final int KIND_TABLE = 5;
    public static final int KIND_JSON = 6;
    public static final int KIND_INDEX = 7;
    /** OR'd into {@code kind} when the payload is stored rather than deflated. */
    public static final int KIND_STORED = 0x100;

    private final byte[] data;
    private final Map<String, Layer> layers = new LinkedHashMap<>();
    private final long builtUnix;
    private final String buildId;

    public WorldFile(byte[] whole) throws IOException {
        this.data = whole;
        if (whole.length < HEADER_BYTES) {
            throw new IOException("world file is only " + whole.length + " bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(whole).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt();
        if (magic != MAGIC) {
            throw new IOException(String.format(
                    "not an MWL2 file (magic 0x%08X)", magic));
        }
        int version = bb.getInt();
        if (version != VERSION) {
            throw new IOException("world format version " + version
                    + ", this build reads " + VERSION);
        }
        int count = bb.getInt();
        bb.getInt();                                  // flags, reserved
        builtUnix = bb.getLong();
        byte[] id = new byte[32];
        bb.get(id);
        int end = 32;
        while (end > 0 && id[end - 1] == 0) { end--; }
        buildId = new String(id, 0, end, java.nio.charset.StandardCharsets.UTF_8);

        if (count < 0 || count > 4096) {
            throw new IOException("implausible layer count " + count);
        }
        for (int i = 0; i < count; i++) {
            byte[] nm = new byte[16];
            bb.get(nm);
            int n = 0;
            while (n < 16 && nm[n] != 0) { n++; }
            String name = new String(nm, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            int kind = bb.getInt();
            int crc = bb.getInt();
            long raw = bb.getLong();
            long stored = bb.getLong();
            long off = bb.getLong();
            if (off < 0 || stored < 0 || off + stored > whole.length) {
                throw new IOException("layer " + name + " runs past the end of the file");
            }
            layers.put(name, new Layer(name, kind, crc, raw, stored, off));
        }
    }

    /** Read a whole stream. The packaged files are read in one go at load time. */
    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); }
        return out.toByteArray();
    }

    public static WorldFile open(InputStream in) throws IOException {
        try (InputStream s = in) {
            return new WorldFile(readAll(s));
        }
    }

    public long builtUnixSeconds() { return builtUnix; }

    public String buildId() { return buildId; }

    public boolean has(String name) { return layers.containsKey(name); }

    public List<Layer> layers() { return new ArrayList<>(layers.values()); }

    public Layer layer(String name) throws IOException {
        Layer l = layers.get(name);
        if (l == null) {
            throw new IOException("world file has no layer '" + name + "'; it has "
                    + layers.keySet());
        }
        return l;
    }

    /** Inflate a layer and verify its CRC32. */
    public byte[] read(String name) throws IOException {
        Layer l = layer(name);
        if ((l.kind & KIND_STORED) != 0) {
            byte[] verbatim = new byte[(int) l.storedBytes];
            System.arraycopy(data, (int) l.offset, verbatim, 0, verbatim.length);
            return verbatim;
        }
        byte[] out = new byte[(int) l.rawBytes];
        try (InflaterInputStream z = new InflaterInputStream(
                new java.io.ByteArrayInputStream(data, (int) l.offset,
                        (int) l.storedBytes), new java.util.zip.Inflater(), 1 << 16)) {
            int got = 0;
            while (got < out.length) {
                int n = z.read(out, got, out.length - got);
                if (n < 0) {
                    throw new EOFException("layer " + name + " ended after " + got
                            + " of " + out.length + " bytes");
                }
                got += n;
            }
        }
        CRC32 crc = new CRC32();
        crc.update(out);
        if ((int) crc.getValue() != l.crc) {
            throw new IOException("layer " + name + " failed its CRC check; the "
                    + "packaged world is corrupt");
        }
        return out;
    }

    /**
     * Bytes straight out of a stored (undeflated) layer, with no inflate.
     *
     * <p>The chunk payload is written this way: each chunk is deflated on its
     * own and the layer holds the concatenation. Without this a streaming
     * worker would have to inflate the entire hundred-megabyte layer to read
     * one thirty-kilobyte tile.
     */
    public byte[] readStored(String name, int offset, int length) throws IOException {
        Layer l = layer(name);
        if ((l.kind & KIND_STORED) == 0) {
            throw new IOException("layer " + name + " is deflated, not stored");
        }
        if (offset < 0 || length < 0 || offset + (long) length > l.storedBytes) {
            throw new IOException("layer " + name + ": slice " + offset + "+" + length
                    + " runs past " + l.storedBytes);
        }
        byte[] out = new byte[length];
        System.arraycopy(data, (int) l.offset + offset, out, 0, length);
        return out;
    }

    public ByteBuffer readBuffer(String name) throws IOException {
        return ByteBuffer.wrap(read(name)).order(ByteOrder.LITTLE_ENDIAN);
    }

    public String readString(String name) throws IOException {
        return new String(read(name), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Inflate an int16 grid that was delta coded along x by the compiler, and
     * undo the delta. Row-major, {@code nz} rows of {@code nx}.
     */
    public short[] readGridI16(String name, int nx, int nz) throws IOException {
        byte[] raw = read(name);
        if (raw.length != nx * nz * 2L) {
            throw new IOException("grid " + name + " is " + raw.length
                    + " bytes, expected " + (nx * nz * 2L));
        }
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        short[] out = new short[nx * nz];
        for (int j = 0; j < nz; j++) {
            int base = j * nx;
            int acc = 0;
            for (int i = 0; i < nx; i++) {
                acc = (short) (acc + bb.getShort());
                out[base + i] = (short) acc;
            }
        }
        return out;
    }

    /** Inflate a bitset layer into a packed long array, MSB-first within each byte. */
    public long[] readBitset(String name, int bits) throws IOException {
        byte[] raw = read(name);
        long[] out = new long[(bits + 63) >>> 6];
        int n = Math.min(bits, raw.length * 8);
        for (int i = 0; i < n; i++) {
            if ((raw[i >>> 3] & (0x80 >>> (i & 7))) != 0) {
                out[i >>> 6] |= 1L << (i & 63);
            }
        }
        return out;
    }

    /** Decode a {@code pack_strings} table. */
    public String[] readStringTable(String name) throws IOException {
        ByteBuffer bb = readBuffer(name);
        int n = bb.getInt();
        if (n < 0 || n > 4_000_000) {
            throw new IOException("string table " + name + " claims " + n + " entries");
        }
        int[] offs = new int[n + 1];
        for (int i = 0; i <= n; i++) { offs[i] = bb.getInt(); }
        int base = bb.position();
        byte[] all = bb.array();
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = new String(all, base + offs[i], offs[i + 1] - offs[i],
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        return out;
    }

    public long totalStoredBytes() {
        long t = 0;
        for (Layer l : layers.values()) { t += l.storedBytes; }
        return t;
    }
}
