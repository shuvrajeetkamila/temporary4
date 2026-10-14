package ai.shuvrajeetkamila.mumbai.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Brings the city in and out of memory around the player.
 *
 * <p>A tile walks a fixed path:
 *
 * <pre>
 *   UNLOADED -> QUEUED_IO -> DECODING -> READY_CPU -> QUEUED_GPU
 *            -> ACTIVE_HIGH | ACTIVE_MED | ACTIVE_LOW -> EVICTING -> UNLOADED
 * </pre>
 *
 * <p>The entire machine lives here, in the core module, where it can be tested
 * without a GPU. The renderer is not allowed to own a transition: it calls
 * {@link #pollReady} to collect tiles whose meshes are built, uploads them,
 * then reports back through {@link #markUploaded} or {@link #markUploadFailed}.
 * An earlier design let the renderer flip the state itself, and a tile whose
 * upload threw left the machine convinced it was resident forever — the city
 * grew holes that never healed. Reporting back makes the failure path explicit.
 *
 * <p>Preload is biased by where the player is going, not just where they are:
 * the ring extends along the velocity vector, and widens with altitude so a
 * helicopter climbing out of Juhu does not fly into unloaded terrain. Work in
 * flight is bounded so a fast drive cannot queue the whole island, and
 * cancellation is honoured at every stage because by the time a tile requested
 * eight seconds ago finishes decoding, a car at 90 km/h is 200 m past it.
 */
public final class WorldStreamer {

    public static final int S_UNLOADED = 0;
    public static final int S_QUEUED_IO = 1;
    public static final int S_DECODING = 2;
    public static final int S_READY_CPU = 3;
    public static final int S_QUEUED_GPU = 4;
    public static final int S_ACTIVE_HIGH = 5;
    public static final int S_ACTIVE_MED = 6;
    public static final int S_ACTIVE_LOW = 7;
    public static final int S_EVICTING = 8;

    public static final String[] STATE_NAMES = {
        "UNLOADED", "QUEUED_IO", "DECODING", "READY_CPU", "QUEUED_GPU",
        "ACTIVE_HIGH", "ACTIVE_MED", "ACTIVE_LOW", "EVICTING",
    };

    /** How much decode work may be outstanding at once. */
    public static final int MAX_IN_FLIGHT = 48;
    /** Hard cap on resident tiles, so a long drive cannot exhaust memory. */
    public static final int MAX_RESIDENT = 420;
    /** Frames a tile must stay unwanted before its GPU memory is released. */
    public static final int EVICT_GRACE_FRAMES = 45;

    /** One streamed tile. */
    public static final class Tile {
        public final int id;
        public final double originX;
        public final double originZ;
        public volatile int state = S_UNLOADED;
        public volatile int lod = ChunkMesher.LOD_LOW;
        public volatile int wantLod = ChunkMesher.LOD_LOW;
        public volatile ChunkMesher.Mesh mesh;
        public volatile ChunkFeatures features;
        /** GPU handle, meaningful only to the host. */
        public volatile Object gpu;
        public volatile long geometryBytes;
        /** Set when the tile stopped being wanted while work was outstanding. */
        public volatile boolean cancelled;
        /**
         * A new detail level is being built for a tile that is already on
         * screen. The tile stays ACTIVE throughout: the old mesh keeps
         * being drawn until the new one has been uploaded, and only then
         * do they swap. Without this a tile vanishes for the whole round
         * trip through the I/O queue, a worker thread and the upload
         * budget - and because the sea is drawn under everything, a
         * missing tile is not a gap, it is water.
         */
        public volatile boolean rebuilding;
        /** The new mesh, parked until the swap. */
        public volatile ChunkMesher.Mesh pendingMesh;
        /** The detail level {@link #pendingMesh} was built at. */
        public volatile int pendingLod;
        volatile int lastWantedFrame;
        volatile int generation;
        double priority;

        Tile(int id, double ox, double oz) {
            this.id = id;
            this.originX = ox;
            this.originZ = oz;
        }

        public boolean isActive() {
            int s = state;
            return s == S_ACTIVE_HIGH || s == S_ACTIVE_MED || s == S_ACTIVE_LOW;
        }

        @Override public String toString() {
            return "Tile#" + id + "[" + STATE_NAMES[state] + " lod=" + lod + "]";
        }
    }

    /** Where decoded tiles are handed to; implemented by the host's uploader. */
    public interface Sink {
        /** Called on the main thread when a tile's mesh is ready to upload. */
        void onReady(Tile tile);
    }

    private final WorldData world;
    private final Tile[] tiles;
    private final ArrayDeque<Tile> ioQueue = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Tile> decoded = new ConcurrentLinkedQueue<>();
    private final List<Tile> resident = new ArrayList<>();
    /**
     * GPU handles replaced by a rebuild, waiting for the host to free
     * them. A displaced handle that nobody frees is leaked for the rest
     * of the session, and a driver that runs out of memory stops honouring
     * uploads, which is what torn and striped geometry on screen is.
     */
    private final ConcurrentLinkedQueue<Object> displaced =
            new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();

    private int frame;
    private int maxResident = MAX_RESIDENT;
    private double highRadius = 800;
    private double medRadius = 3200;
    private double lowRadius = 22000;

    // Counters, for the HUD and for tests that need to see work actually happen.
    private int statRequested;
    private int statDecoded;
    private int statActivated;
    private int statEvicted;
    private int statCancelled;
    private int statStranded;
    private int statDisplaced;
    private int statDecodeFailures;
    private int statUploadFailures;
    private long statDecodeNanos;

    public WorldStreamer(WorldData world) {
        this.world = world;
        int n = world.chunkCount();
        tiles = new Tile[n];
        for (int i = 0; i < n; i++) {
            tiles[i] = new Tile(i, world.chunkOriginX(i), world.chunkOriginZ(i));
        }
    }

    public void setRadii(double high, double med, double low) {
        highRadius = high;
        medRadius = med;
        lowRadius = low;
    }

    public void setMaxResident(int n) { maxResident = Math.max(16, n); }

    public int maxResident() { return maxResident; }

    public Tile tile(int id) { return tiles[id]; }

    public int tileCount() { return tiles.length; }

    public int frameNumber() { return frame; }

    /**
     * Decide what should be resident this frame.
     *
     * @param x        player position east
     * @param z        player position south
     * @param velX     velocity east, m/s
     * @param velZ     velocity south, m/s
     * @param altitude metres above the ground
     */
    public void update(double x, double z, double velX, double velZ, double altitude) {
        frame++;

        double speed = Math.sqrt(velX * velX + velZ * velZ);
        // Look ahead along the direction of travel, further the faster we go,
        // and widen everything with altitude because height reveals distance.
        double lead = Math.min(2600.0, speed * 9.0);
        double altScale = 1.0 + clamp(altitude / 900.0, 0, 2.2);
        double ax = x, az = z;
        if (speed > 1.0) {
            ax += velX / speed * lead;
            az += velZ / speed * lead;
        }
        double rHigh = highRadius * altScale;
        double rMed = medRadius * altScale;
        double rLow = lowRadius * altScale;

        int cx = (int) Math.floor(x / world.chunkM) - world.chunkMinX;
        int cz = (int) Math.floor(z / world.chunkM) - world.chunkMinZ;
        int span = (int) Math.ceil(rLow / world.chunkM) + 1;

        ioQueue.clear();
        List<Tile> wanted = new ArrayList<>();
        for (int j = cz - span; j <= cz + span; j++) {
            if (j < 0 || j >= world.chunkNz) { continue; }
            for (int i = cx - span; i <= cx + span; i++) {
                if (i < 0 || i >= world.chunkNx) { continue; }
                int id = j * world.chunkNx + i;
                Tile t = tiles[id];
                double tx = t.originX + world.chunkM * 0.5;
                double tz = t.originZ + world.chunkM * 0.5;
                // Distance to the look-ahead point for priority, but to the
                // player for the level of detail, so the tile the player is
                // standing in is never downgraded because they are moving.
                double dAhead = Math.hypot(tx - ax, tz - az);
                double dNow = Math.hypot(tx - x, tz - z);
                double d = Math.min(dAhead, dNow);
                if (d > rLow) { continue; }

                int want = d <= rHigh ? ChunkMesher.LOD_HIGH
                        : (d <= rMed ? ChunkMesher.LOD_MED : ChunkMesher.LOD_LOW);
                t.wantLod = want;
                t.lastWantedFrame = frame;
                t.priority = d;
                t.cancelled = false;
                wanted.add(t);
            }
        }

        wanted.sort(Comparator.comparingDouble(a -> a.priority));

        for (Tile t : wanted) {
            int s = t.state;
            if (s == S_UNLOADED) {
                if (inFlight.get() + ioQueue.size() < MAX_IN_FLIGHT
                        && residentCount() < maxResident) {
                    t.state = S_QUEUED_IO;
                    ioQueue.add(t);
                    statRequested++;
                }
            } else if (t.isActive() && t.lod != t.wantLod && !t.rebuilding) {
                // Already resident but at the wrong detail: rebuild it,
                // keeping the current mesh on screen until the new one is
                // uploaded. The state deliberately stays ACTIVE - the
                // renderer draws whatever is active and knows nothing
                // about rebuilds, so moving the state here is the same
                // thing as deleting the ground for a few frames.
                if (inFlight.get() + ioQueue.size() < MAX_IN_FLIGHT) {
                    t.rebuilding = true;
                    ioQueue.add(t);
                    statRequested++;
                }
            }
        }

        evictStale();
    }

    /** Cancel outstanding work for tiles nobody wants any more. */
    private void evictStale() {
        for (int i = resident.size() - 1; i >= 0; i--) {
            Tile t = resident.get(i);
            if (frame - t.lastWantedFrame <= EVICT_GRACE_FRAMES) { continue; }
            if (t.isActive()) {
                t.state = S_EVICTING;
            }
        }
        // Anything resident that holds GPU memory but is neither drawable
        // nor already on its way out is stranded: it occupies the
        // residency cap for ever and nothing will free it. That can only
        // happen through a path not thought of, which is exactly why the
        // sweep is here.
        for (int i = resident.size() - 1; i >= 0; i--) {
            Tile t = resident.get(i);
            if (t.gpu != null && !t.isActive() && t.state != S_EVICTING) {
                t.state = S_EVICTING;
                statStranded++;
            }
        }
        // Over the cap: drop the furthest-from-wanted first.
        if (resident.size() > maxResident) {
            resident.sort(Comparator.comparingInt(a -> a.lastWantedFrame));
            int excess = resident.size() - maxResident;
            for (int i = 0; i < excess && i < resident.size(); i++) {
                Tile t = resident.get(i);
                if (t.isActive()) { t.state = S_EVICTING; }
            }
        }
    }

    /** Take the next tile to decode, or null. Called by worker threads. */
    public Tile nextForDecode() {
        synchronized (ioQueue) {
            Tile t = ioQueue.poll();
            while (t != null && t.state != S_QUEUED_IO && !t.rebuilding) {
                t = ioQueue.poll();
            }
            if (t != null) {
                // A rebuild keeps its ACTIVE state; only a fresh load
                // moves to DECODING.
                if (!t.rebuilding) { t.state = S_DECODING; }
                inFlight.incrementAndGet();
            }
            return t;
        }
    }

    public int pendingIo() {
        synchronized (ioQueue) { return ioQueue.size(); }
    }

    public int inFlightCount() { return inFlight.get(); }

    /**
     * Decode and mesh a tile. Runs on a worker thread; touches only the tile
     * and the immutable world data.
     */
    public void decode(Tile t, ChunkMesher mesher) {
        long t0 = System.nanoTime();
        try {
            if (t.cancelled) {
                abandon(t);
                statCancelled++;
                return;
            }
            int lod = t.wantLod;
            ChunkFeatures f;
            if (world.chunkHasData(t.id)) {
                byte[] payload = world.readChunk(t.id);
                f = ChunkFeatures.decode(t.id, t.originX, t.originZ, payload,
                        world.quant);
            } else {
                f = ChunkFeatures.empty(t.id, t.originX, t.originZ);
            }
            ChunkMesher.Mesh m = mesher.build(f, lod);
            if (t.cancelled) {
                abandon(t);
                statCancelled++;
                return;
            }
            t.features = f;
            if (t.rebuilding) {
                // Park it. The tile on screen is untouched until the swap,
                // so a rebuild that never finishes costs detail, not land.
                t.pendingMesh = m;
                t.pendingLod = lod;
            } else {
                t.mesh = m;
                t.lod = lod;
                t.geometryBytes = m.byteSize();
                t.state = S_READY_CPU;
            }
            decoded.add(t);
            statDecoded++;
        } catch (Throwable e) {                                   // noqa
            // A corrupt tile must not take the session with it. Mark it
            // unloaded; it will simply not be requested again this frame.
            // If it was a rebuild of something already on screen, that
            // something stays on screen - a tile that fails to re-mesh at
            // a new detail level is not a reason to delete the ground.
            abandon(t);
            statDecodeFailures++;
        } finally {
            inFlight.decrementAndGet();
            statDecodeNanos += System.nanoTime() - t0;
        }
    }

    /**
     * Give up on outstanding work for a tile.
     *
     * <p>The distinction that matters: a tile with a mesh already on the
     * GPU keeps it and keeps being drawn. Only a tile that has nothing on
     * screen goes back to UNLOADED. Treating those two the same is how a
     * cancelled rebuild used to strand a tile - still in the resident
     * list, still holding GPU memory, no longer drawable, and invisible
     * to the eviction path that would have freed it.
     */
    private void abandon(Tile t) {
        t.pendingMesh = null;
        t.rebuilding = false;
        if (t.gpu != null) { return; }
        t.mesh = null;
        t.state = S_UNLOADED;
    }

    /**
     * Collect tiles whose meshes are built, up to {@code max} per call.
     *
     * <p>Bounded because a single frame that uploads forty meshes is a visible
     * hitch; spreading them costs a few frames of a slightly sparser skyline
     * and keeps the frame time flat.
     */
    public List<Tile> pollReady(int max) {
        List<Tile> out = new ArrayList<>(Math.max(1, max));
        for (int i = 0; i < max; i++) {
            Tile t = decoded.poll();
            if (t == null) { break; }
            if (t.rebuilding) {
                if (t.pendingMesh == null) { continue; }
                // Hand the new mesh over in the usual place so the host
                // uploads it the same way it uploads a fresh tile. The
                // state is untouched: this tile is on screen right now.
                t.mesh = t.pendingMesh;
                t.pendingMesh = null;
                out.add(t);
                continue;
            }
            if (t.state != S_READY_CPU) { continue; }
            t.state = S_QUEUED_GPU;
            out.add(t);
        }
        return out;
    }

    /** The host reports a successful GPU upload. */
    public void markUploaded(Tile t, Object handle) {
        Object old = t.gpu;
        t.gpu = handle;
        if (t.rebuilding) {
            t.lod = t.pendingLod;
            t.geometryBytes = t.mesh == null ? t.geometryBytes : t.mesh.byteSize();
            t.rebuilding = false;
        }
        t.state = t.lod == ChunkMesher.LOD_HIGH ? S_ACTIVE_HIGH
                : (t.lod == ChunkMesher.LOD_MED ? S_ACTIVE_MED : S_ACTIVE_LOW);
        // CPU-side mesh has served its purpose; the GPU copy is authoritative.
        t.mesh = null;
        if (!resident.contains(t)) { resident.add(t); }
        // The mesh this one replaced is still allocated on the GPU. Hand
        // it back rather than overwriting the reference and hoping: see
        // pollDisplaced.
        if (old != null && old != handle) {
            displaced.add(old);
            statDisplaced++;
        }
        statActivated++;
    }

    /**
     * GPU handles that have been replaced and must be freed by the host.
     *
     * <p>Drained next to the evictions every frame. The queue exists so
     * that forgetting to free a replaced mesh is a growing number some
     * test can see, rather than a silent leak that only shows up as a
     * corrupted screen after twenty minutes of play.
     */
    public List<Object> pollDisplaced(int max) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            Object h = displaced.poll();
            if (h == null) { break; }
            out.add(h);
        }
        return out;
    }

    /** Replaced meshes still waiting to be freed. */
    public int displacedPending() { return displaced.size(); }

    /** Tiles reclaimed by the stranded sweep. Should stay at zero. */
    public int strandedCount() { return statStranded; }

    /** The host reports that the upload failed, and nothing was allocated. */
    public void markUploadFailed(Tile t) {
        statUploadFailures++;
        if (t.rebuilding && t.gpu != null) {
            // The old mesh is still there and still correct.
            t.rebuilding = false;
            t.pendingMesh = null;
            t.mesh = null;
            return;
        }
        t.gpu = null;
        t.mesh = null;
        t.state = S_UNLOADED;
        resident.remove(t);
    }

    /**
     * Tiles the host should release this frame, with their GPU handles still
     * attached. The host frees them and calls {@link #markReleased}.
     */
    public List<Tile> pollEvictions(int max) {
        List<Tile> out = new ArrayList<>();
        for (int i = resident.size() - 1; i >= 0 && out.size() < max; i--) {
            Tile t = resident.get(i);
            if (t.state == S_EVICTING) { out.add(t); }
        }
        return out;
    }

    public void markReleased(Tile t) {
        t.gpu = null;
        t.mesh = null;
        t.pendingMesh = null;
        t.rebuilding = false;
        t.features = null;
        t.geometryBytes = 0;
        t.state = S_UNLOADED;
        resident.remove(t);
        statEvicted++;
    }

    /** Tiles currently on the GPU, for the renderer to walk. */
    public List<Tile> residentTiles() { return resident; }

    public int residentCount() { return resident.size(); }

    public int activeCount() {
        int n = 0;
        for (int i = 0; i < resident.size(); i++) {
            if (resident.get(i).isActive()) { n++; }
        }
        return n;
    }

    public int readyCpuCount() { return decoded.size(); }

    public long residentGeometryBytes() {
        long b = 0;
        for (int i = 0; i < resident.size(); i++) { b += resident.get(i).geometryBytes; }
        return b;
    }

    public int requested() { return statRequested; }

    public int decodedCount() { return statDecoded; }

    public int activatedCount() { return statActivated; }

    public int evictedCount() { return statEvicted; }

    public int cancelledCount() { return statCancelled; }

    public int decodeFailures() { return statDecodeFailures; }

    public int uploadFailures() { return statUploadFailures; }

    public double averageDecodeMillis() {
        return statDecoded == 0 ? 0 : statDecodeNanos / 1e6 / statDecoded;
    }

    public int countInState(int state) {
        int n = 0;
        for (Tile t : tiles) { if (t.state == state) { n++; } }
        return n;
    }

    /** Drop everything. Used when the floating origin rebases or on teardown. */
    public void reset() {
        synchronized (ioQueue) { ioQueue.clear(); }
        decoded.clear();
        for (Tile t : tiles) {
            t.cancelled = true;
            t.rebuilding = false;
            t.pendingMesh = null;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
