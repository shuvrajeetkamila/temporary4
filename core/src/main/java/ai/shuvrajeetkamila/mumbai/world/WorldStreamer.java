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
        /** The frame this tile was last put into the I/O queue. */
        volatile int queuedFrame = -1;
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
    /** Scratch: what was in the I/O queue before this frame rebuilt it. */
    private final List<Tile> requeue = new ArrayList<>();
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

    /** The outer radius actually being streamed this frame, metres. */
    private double effectiveRadius = 1024;

    /**
     * How far out the tile budget can reach, metres.
     *
     * <p>A disc of radius r covers {@code pi * r^2 / chunk^2} tiles. Run
     * backwards from the budget, with a tenth held back for the tiles a
     * fast approach has already queued ahead of the player, and that is
     * the furthest the ground can be guaranteed to exist.
     */
    private double budgetRadius() {
        return Math.sqrt(maxResident * 0.90 / Math.PI) * world.chunkM;
    }

    /**
     * The radius the ground is actually being kept out to, metres.
     *
     * <p>The renderer needs this, not the configured far radius: the fog
     * and the far plane have to be set from the distance at which the
     * world really stops, or the player sees the edge of it.
     */
    public double effectiveRadius() { return effectiveRadius; }
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
        // Clamp the outer ring to what the resident budget can actually
        // hold. This is the whole of the "white land" fault: the far
        // radius at HIGH quality is 22 km, a disc of that radius is
        // about 1,500 chunks, and only 420 may be resident at once. The
        // nearest 420 loaded and the rest silently never did, so beyond
        // about 11 km there was no ground - and since the sea is masked
        // off over land, the hole showed the colour the frame is cleared
        // to, which is the fog. Pale grey-white, in daylight.
        //
        // At LOW quality the disc is 9 km, which is 250 chunks and fits,
        // so there were no holes at all. That is why cycling the
        // graphics button sometimes fixed it and sometimes did not: it
        // was not fixing anything, it was choosing a tier whose view
        // distance the memory budget could meet.
        double rHigh = highRadius * altScale;
        double rMed = medRadius * altScale;
        double rLow = Math.min(lowRadius * altScale, budgetRadius());
        if (rMed > rLow) { rMed = rLow; }
        if (rHigh > rMed) { rHigh = rMed; }
        effectiveRadius = rLow;

        int cx = (int) Math.floor(x / world.chunkM) - world.chunkMinX;
        int cz = (int) Math.floor(z / world.chunkM) - world.chunkMinZ;
        int span = (int) Math.ceil(rLow / world.chunkM) + 1;

        // The request queue is rebuilt from scratch every frame so that
        // it is always in priority order for where the player is NOW.
        // Everything that was in it has to be either put back or reset:
        // a tile left marked S_QUEUED_IO after the clear is in no queue
        // and matches no branch below - the re-add only fired for
        // S_UNLOADED - so it could never be requested again. That is
        // what made the ground disappear. Walking the city, the tile the
        // player was standing on was stuck in QUEUED_IO with an empty
        // queue for 413 of 450 samples, and the longer the session ran
        // the more of the map was permanently stranded, which is why
        // only restarting the app brought it back.
        requeue.clear();
        synchronized (ioQueue) {
            requeue.addAll(ioQueue);
            ioQueue.clear();
        }
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

        synchronized (ioQueue) {
        for (Tile t : wanted) {
            int s = t.state;
            if (s == S_QUEUED_IO) {
                // Already requested and not yet picked up by a worker.
                // Put it back, in this frame's priority order.
                ioQueue.add(t);
                t.queuedFrame = frame;
            } else if (s == S_UNLOADED) {
                if (inFlight.get() + ioQueue.size() < MAX_IN_FLIGHT
                        && residentCount() < maxResident) {
                    t.state = S_QUEUED_IO;
                    ioQueue.add(t);
                    t.queuedFrame = frame;
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
                    t.queuedFrame = frame;
                    statRequested++;
                }
            }
        }
        }

        // Anything that was queued and did not make it back in - because
        // the player walked away from it, or because the in-flight cap
        // was reached first - goes back to UNLOADED so that it can be
        // asked for again later. Leaving it in QUEUED_IO strands it.
        for (int i = 0; i < requeue.size(); i++) {
            Tile t = requeue.get(i);
            if (t.state == S_QUEUED_IO && t.queuedFrame != frame) {
                t.state = S_UNLOADED;
                t.cancelled = false;
                statCancelled++;
            }
        }
        requeue.clear();

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
    /**
     * How many finished meshes may be waiting for the GPU at once.
     *
     * <p>The uploader takes two to four tiles a frame, deliberately, so
     * that buffer creation never stalls the driver. Decoding is not
     * throttled by anything else, so workers will run as far ahead as
     * the request queue lets them and park finished meshes in memory -
     * measured at a peak of eighty, which at a megabyte for a
     * high-detail chunk is tens of megabytes of CPU-side geometry that
     * nothing can use yet. It went unnoticed while a bug in the request
     * queue meant almost nothing was ever requested; with that fixed,
     * the backlog is real, and on a 512 MB heap it is the difference
     * between running and being killed.
     *
     * <p>Six frames of upload is plenty to keep the uploader fed.
     */
    private static final int MAX_DECODED_BACKLOG = 12;

    public Tile nextForDecode() {
        // Pace the workers against the upload budget rather than the
        // request queue. Decoding further ahead than this buys nothing
        // and costs memory that the device may not have.
        if (decoded.size() >= MAX_DECODED_BACKLOG) { return null; }
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

    /**
     * The host is not ready to upload this tile yet; put it back.
     *
     * <p>Not a failure: the mesh is good and still wanted, the frame's
     * upload budget simply ran out. It goes back on the ready queue in
     * the state it came off in.
     */
    public void deferUpload(Tile t) {
        if (t.state == S_QUEUED_GPU) { t.state = S_READY_CPU; }
        decoded.add(t);
    }

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

    /**
     * Forget every GPU handle without trying to free it.
     *
     * <p>For when the GL context has gone - the app was backgrounded and
     * Android threw the context away. Every buffer and every vertex array
     * died with it, but the tiles still hold their handles, and those are
     * small integers that the driver will hand out again to completely
     * different objects. Drawing with them afterwards is drawing whatever
     * happens to own that name now. The tiles go back to UNLOADED and are
     * streamed in again.
     */
    public void forgetGpu() {
        synchronized (ioQueue) { ioQueue.clear(); }
        decoded.clear();
        displaced.clear();
        for (Tile t : tiles) {
            t.gpu = null;
            t.mesh = null;
            t.pendingMesh = null;
            t.rebuilding = false;
            t.cancelled = true;
            t.state = S_UNLOADED;
            t.geometryBytes = 0;
        }
        resident.clear();
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
