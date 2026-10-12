package ai.shuvrajeetkamila.mumbai.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.game.QualityProfile;

/**
 * Hierarchical world streamer.
 *
 * <p>Mumbai at 1:1 scale is ~44 x 65 km, so nothing is ever loaded wholesale. Tiles are
 * selected from camera position, velocity, altitude and quality profile across three
 * levels of detail (256 m full detail, 1 km massing, 4 km skyline), decoded on a bounded
 * worker pool, and uploaded to the GPU under a per-frame budget owned by the renderer.
 *
 * <p>State machine per tile:
 * UNLOADED -> QUEUED_IO -> DECODING -> READY_CPU -> QUEUED_GPU -> ACTIVE_* -> EVICTING.
 */
public final class WorldStreamer {

    public static final int S_UNLOADED = 0;
    public static final int S_QUEUED_IO = 1;
    public static final int S_DECODING = 2;
    public static final int S_READY_CPU = 3;
    public static final int S_QUEUED_GPU = 4;
    public static final int S_ACTIVE_HIGH = 5;
    public static final int S_ACTIVE_MEDIUM = 6;
    public static final int S_ACTIVE_LOW = 7;
    public static final int S_EVICTING = 8;

    /** One streamed tile. */
    public static final class Tile {
        public long key;
        public int cx, cz, lod;
        public volatile int state;
        public volatile boolean cancelled;
        public ChunkBuilder.Geometry geometry;
        /** Renderer owned handle (VAO/VBO ids); opaque to the streamer. */
        public Object gpu;
        public long lastUsedFrame;
        public double centreX, centreZ;
        public float radius;
        public float minY, maxY;
    }

    /** Lets the renderer release GPU resources when a tile is evicted. */
    public interface TileSink {
        void onTileEvicted(Tile tile);
    }

    private final WorldData world;
    private final ExecutorService pool;
    private final Map<Long, Tile> tiles = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Tile> ready = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<ChunkBuilder> builders;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger decoded = new AtomicInteger();
    private TileSink sink;

    private long frame;
    private final List<Tile> scratch = new ArrayList<>();
    private final Set<Long> desired = new HashSet<>(2048);

    public int statActive;
    public int statCpuReady;
    public int statPending;
    public int statEvicted;
    public int statUploads;

    public WorldStreamer(final WorldData world, int threads) {
        this.world = world;
        this.builders = new ThreadLocal<ChunkBuilder>() {
            @Override
            protected ChunkBuilder initialValue() {
                return new ChunkBuilder(world);
            }
        };
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), new ThreadFactory() {
            private int n = 0;

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mumbai-stream-" + (n++));
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });
    }

    public void setSink(TileSink sink) {
        this.sink = sink;
    }

    public static long key(int cx, int cz, int lod) {
        return ((long) (cx & 0x3FFFF) << 40) | ((long) (cz & 0x3FFFF) << 8) | (lod & 0xFF);
    }

    /**
     * Chooses the tile set for the current focus and queues the work.
     *
     * @param fx      focus world X
     * @param fz      focus world Z
     * @param altitude metres above ground (aircraft stream coarser, wider)
     * @param velX    world velocity X (m/s), used to bias loading forward
     * @param velZ    world velocity Z (m/s)
     */
    public void update(double fx, double fz, float altitude, float velX, float velZ,
                       QualityProfile profile) {
        frame++;
        desired.clear();

        float speed = (float) Math.sqrt(velX * velX + velZ * velZ);
        // Look ahead proportional to speed: a plane at 70 m/s needs ~10 s of corridor.
        float lead = Math.min(2600f, speed * 9f);
        double lx = fx, lz = fz;
        if (speed > 1f) {
            lx += velX / speed * lead;
            lz += velZ / speed * lead;
        }

        float altScale = 1f + Mth.clamp(altitude / 900f, 0f, 2.2f);
        float high = profile.highRing * (altitude > 250f ? 0.55f : 1f);
        float mid = profile.midRing * altScale;
        float far = profile.farRing * altScale;

        // LOD 0 ring - full detail + collision, biased forward.
        addRing(fx, fz, lx, lz, high, 0);
        addRing(lx, lz, lx, lz, high * 0.8f, 0);
        // LOD 1 ring - city massing.
        addRing(fx, fz, lx, lz, mid, 1);
        // LOD 2 ring - skyline and terrain to the horizon.
        addRing(fx, fz, lx, lz, far, 2);

        statPending = pending.get();
        statCpuReady = ready.size();

        // Evict anything outside the desired set that has not been used for a while.
        Iterator<Map.Entry<Long, Tile>> it = tiles.entrySet().iterator();
        int evicted = 0;
        while (it.hasNext()) {
            Map.Entry<Long, Tile> e = it.next();
            Tile t = e.getValue();
            if (desired.contains(t.key)) {
                t.lastUsedFrame = frame;
                continue;
            }
            if (frame - t.lastUsedFrame < 90) {
                continue;
            }
            if (t.state == S_QUEUED_IO || t.state == S_DECODING) {
                t.cancelled = true;      // worker drops the result
                continue;
            }
            t.state = S_EVICTING;
            if (sink != null) {
                sink.onTileEvicted(t);
            }
            t.geometry = null;
            it.remove();
            evicted++;
        }
        statEvicted += evicted;

        int active = 0;
        for (Tile t : tiles.values()) {
            if (t.state >= S_ACTIVE_HIGH && t.state <= S_ACTIVE_LOW) {
                active++;
            }
        }
        statActive = active;
    }

    private void addRing(double fx, double fz, double leadX, double leadZ, float radius, int lod) {
        int span = 1 << lod;
        float tileSize = world.chunkSize * span;
        int r = (int) Math.ceil(radius / tileSize);
        int ccx = (int) Math.floor((fx - world.chunkX0) / tileSize);
        int ccz = (int) Math.floor((fz - world.chunkZ0) / tileSize);
        float r2 = radius * radius;
        for (int j = -r; j <= r; j++) {
            for (int i = -r; i <= r; i++) {
                int tx = (ccx + i) * span;
                int tz = (ccz + j) * span;
                if (tx < 0 || tz < 0 || tx >= world.chunksX || tz >= world.chunksZ) {
                    continue;
                }
                double cx = world.chunkX0 + (tx + span * 0.5) * world.chunkSize;
                double cz = world.chunkZ0 + (tz + span * 0.5) * world.chunkSize;
                double dx = cx - fx;
                double dz = cz - fz;
                double d2 = dx * dx + dz * dz;
                if (d2 > r2) {
                    // keep tiles that lie along the travel corridor
                    double ldx = cx - leadX;
                    double ldz = cz - leadZ;
                    if (ldx * ldx + ldz * ldz > (radius * 0.6) * (radius * 0.6)) {
                        continue;
                    }
                }
                // Higher LODs are redundant where a finer tile already covers the ground.
                if (lod > 0 && coveredByFiner(tx, tz, span)) {
                    continue;
                }
                long k = key(tx, tz, lod);
                desired.add(k);
                Tile t = tiles.get(k);
                if (t == null) {
                    t = new Tile();
                    t.key = k;
                    t.cx = tx;
                    t.cz = tz;
                    t.lod = lod;
                    t.state = S_QUEUED_IO;
                    t.centreX = cx;
                    t.centreZ = cz;
                    t.radius = tileSize * 0.75f;
                    t.lastUsedFrame = frame;
                    tiles.put(k, t);
                    submit(t);
                } else {
                    t.lastUsedFrame = frame;
                }
            }
        }
    }

    private boolean coveredByFiner(int tx, int tz, int span) {
        // A coarse tile is skipped when its centre chunk is already loaded at LOD 0/1.
        for (int lod = 0; lod < 2; lod++) {
            int s = 1 << lod;
            if (s >= span) {
                break;
            }
            int ax = (tx / s) * s;
            int az = (tz / s) * s;
            Tile t = tiles.get(key(ax, az, lod));
            if (t != null && t.state >= S_ACTIVE_HIGH && t.state <= S_ACTIVE_LOW) {
                return true;
            }
        }
        return false;
    }

    private void submit(final Tile tile) {
        pending.incrementAndGet();
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (tile.cancelled) {
                            return;
                        }
                        tile.state = S_DECODING;
                        ChunkBuilder cb = builders.get();
                        ChunkBuilder.Geometry g = cb.build(tile.cx, tile.cz, tile.lod);
                        if (tile.cancelled) {
                            return;
                        }
                        tile.geometry = g;
                        tile.minY = g.minY;
                        tile.maxY = g.maxY;
                        tile.state = S_READY_CPU;
                        ready.add(tile);
                        decoded.incrementAndGet();
                    } catch (Throwable th) {
                        // A failed optional tile must never take the game down.
                        tile.state = S_UNLOADED;
                        tile.cancelled = true;
                    } finally {
                        pending.decrementAndGet();
                    }
                }
            });
        } catch (Throwable rejected) {
            pending.decrementAndGet();
            tile.state = S_UNLOADED;
        }
    }

    /** Pops up to {@code max} tiles whose CPU geometry is ready for GPU upload. */
    public List<Tile> pollReady(int max) {
        scratch.clear();
        for (int i = 0; i < max; i++) {
            Tile t = ready.poll();
            if (t == null) {
                break;
            }
            if (t.cancelled || t.geometry == null) {
                continue;
            }
            t.state = S_QUEUED_GPU;
            scratch.add(t);
        }
        statUploads += scratch.size();
        return scratch;
    }

    /** All tiles currently holding GPU resources, for the render pass. */
    public void collectRenderable(List<Tile> out) {
        out.clear();
        for (Tile t : tiles.values()) {
            if (t.gpu != null && t.state >= S_ACTIVE_HIGH && t.state <= S_ACTIVE_LOW) {
                out.add(t);
            }
        }
        out.sort(new Comparator<Tile>() {
            @Override
            public int compare(Tile a, Tile b) {
                return Integer.compare(a.lod, b.lod);
            }
        });
    }

    /** Static collision boxes near a position, gathered from active LOD 0 tiles. */
    public int collectColliders(double x, double z, float radius, float[] out, int maxBoxes) {
        int n = 0;
        float r2 = radius * radius;
        for (Tile t : tiles.values()) {
            if (t.lod != 0 || t.geometry == null || t.geometry.colliders == null) {
                continue;
            }
            double dx = t.centreX - x;
            double dz = t.centreZ - z;
            if (dx * dx + dz * dz > (radius + t.radius) * (radius + t.radius)) {
                continue;
            }
            float[] c = t.geometry.colliders;
            double ox = t.geometry.originX;
            double oz = t.geometry.originZ;
            for (int i = 0; i + 5 < c.length; i += 6) {
                double bx0 = ox + c[i];
                double bz0 = oz + c[i + 2];
                double bx1 = ox + c[i + 3];
                double bz1 = oz + c[i + 5];
                double cx = (bx0 + bx1) * 0.5;
                double cz = (bz0 + bz1) * 0.5;
                double ddx = cx - x;
                double ddz = cz - z;
                if (ddx * ddx + ddz * ddz > r2) {
                    continue;
                }
                if (n + 6 > out.length || n / 6 >= maxBoxes) {
                    return n;
                }
                out[n] = (float) bx0;
                out[n + 1] = c[i + 1];
                out[n + 2] = (float) bz0;
                out[n + 3] = (float) bx1;
                out[n + 4] = c[i + 4];
                out[n + 5] = (float) bz1;
                n += 6;
            }
        }
        return n;
    }

    /** True when the full-detail collision ring around a point is loaded. */
    public boolean collisionReady(double x, double z) {
        int cx = world.chunkIndexX(x);
        int cz = world.chunkIndexZ(z);
        Tile t = tiles.get(key(cx, cz, 0));
        return t != null && t.state >= S_ACTIVE_HIGH && t.state <= S_ACTIVE_LOW;
    }

    public int tileCount() {
        return tiles.size();
    }

    public int activeCount() {
        return statActive;
    }

    public int pendingCount() {
        return statPending;
    }

    public int readyCount() {
        return statCpuReady;
    }

    public int uploadCount() {
        return statUploads;
    }

    public int evictedCount() {
        return statEvicted;
    }

    public int decodedCount() {
        return decoded.get();
    }

    public Tile tile(int cx, int cz, int lod) {
        return tiles.get(key(cx, cz, lod));
    }

    /** Drops every tile (used on fatal reload or a massive teleport). */
    public void clear() {
        for (Tile t : tiles.values()) {
            t.cancelled = true;
            if (sink != null && t.gpu != null) {
                sink.onTileEvicted(t);
            }
        }
        tiles.clear();
        ready.clear();
    }

    public void shutdown() {
        clear();
        pool.shutdownNow();
        try {
            pool.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
