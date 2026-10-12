package ai.shuvrajeetkamila.mumbai.sim;

/**
 * Keeps the rendered world near the origin so single-precision maths stays
 * accurate.
 *
 * <p>The world is 52 by 72 kilometres. A 32-bit float has about 24 bits of
 * mantissa, so at 45 km from the origin the gap between representable values
 * is around 4 mm, and every vertex, every camera matrix and every interpolated
 * position snaps to that grid. The result is visible jitter in distant
 * geometry and a camera that stutters as it moves.
 *
 * <p>The fix is to keep the authoritative simulation in doubles over the whole
 * world, and subtract a rebasing offset before anything is handed to the GPU.
 * When the player drifts more than the threshold from the current offset, the
 * offset snaps to a 512 m grid near them. Snapping to a grid rather than to the
 * player's exact position means the shift is always a round number, which
 * keeps the rebase itself exact in floating point and makes rebases rare and
 * repeatable.
 *
 * <p>Anything caching a render-space position must subscribe to
 * {@link #consumeRebase} and shift by the delta, or it will jump by half a
 * kilometre the first time the player crosses a boundary.
 */
public final class FloatingOrigin {

    /** Rebase offsets snap to this grid, metres. */
    public static final double GRID = 512.0;
    /** Drift from the current offset that triggers a rebase, metres. */
    public static final double THRESHOLD = 1024.0;

    private double offsetX;
    private double offsetZ;
    private double pendingDx;
    private double pendingDz;
    private boolean pending;
    private int rebaseCount;

    public double offsetX() { return offsetX; }

    public double offsetZ() { return offsetZ; }

    public int rebaseCount() { return rebaseCount; }

    /**
     * Update for the player's world position. Returns true if the origin moved
     * this call, in which case {@link #consumeRebase} has a delta waiting.
     */
    public boolean update(double worldX, double worldZ) {
        double dx = worldX - offsetX;
        double dz = worldZ - offsetZ;
        if (Math.abs(dx) < THRESHOLD && Math.abs(dz) < THRESHOLD) {
            return false;
        }
        double nx = Math.rint(worldX / GRID) * GRID;
        double nz = Math.rint(worldZ / GRID) * GRID;
        if (nx == offsetX && nz == offsetZ) {
            return false;
        }
        pendingDx += nx - offsetX;
        pendingDz += nz - offsetZ;
        pending = true;
        offsetX = nx;
        offsetZ = nz;
        rebaseCount++;
        return true;
    }

    /** True if a shift is waiting to be applied by render-space caches. */
    public boolean hasRebase() { return pending; }

    /**
     * Take the accumulated shift and clear it. {@code out[0]} is the x delta
     * and {@code out[1]} the z delta; subtract them from any cached render
     * position.
     */
    public void consumeRebase(double[] out2) {
        out2[0] = pendingDx;
        out2[1] = pendingDz;
        pendingDx = 0;
        pendingDz = 0;
        pending = false;
    }

    /** World metres to render space. */
    public float renderX(double worldX) { return (float) (worldX - offsetX); }

    public float renderZ(double worldZ) { return (float) (worldZ - offsetZ); }

    /** Render space back to world metres. */
    public double worldX(double renderX) { return renderX + offsetX; }

    public double worldZ(double renderZ) { return renderZ + offsetZ; }

    /**
     * Largest representable-value gap, in metres, for a render-space
     * coordinate at the given distance from the origin. Used by the test that
     * asserts rebasing actually keeps precision under a millimetre.
     */
    public static double floatResolutionAt(double metres) {
        return Math.ulp((float) Math.abs(metres));
    }

    public void reset() {
        offsetX = 0;
        offsetZ = 0;
        pendingDx = 0;
        pendingDz = 0;
        pending = false;
    }
}
