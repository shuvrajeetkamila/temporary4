package ai.shuvrajeetkamila.mumbai.game;

/**
 * Keeps rendering coordinates small.
 *
 * <p>The world spans 65 km, so a 32-bit float used directly as a world coordinate has
 * about 4 mm of resolution at the far corner and visibly jitters once it is multiplied
 * through a view-projection matrix. All simulation state stays in {@code double}; the
 * renderer subtracts the current origin and uploads {@code float}s that are never more
 * than a couple of kilometres from zero.
 *
 * <p>The origin only moves in whole {@link #GRID} metre steps so that shader-side
 * derivatives, noise lookups and shadow cascades do not swim when it rebases.
 */
public final class FloatingOrigin {

    /** Rebase quantum in metres. */
    public static final double GRID = 512.0;
    /** Distance from the origin at which a rebase happens. */
    public static final double THRESHOLD = 1024.0;

    private double originX;
    private double originY;
    private double originZ;
    private int rebaseCount;

    public double x() {
        return originX;
    }

    public double y() {
        return originY;
    }

    public double z() {
        return originZ;
    }

    public int rebaseCount() {
        return rebaseCount;
    }

    public void reset(double x, double y, double z) {
        originX = Math.floor(x / GRID) * GRID;
        originY = Math.floor(y / GRID) * GRID;
        originZ = Math.floor(z / GRID) * GRID;
        rebaseCount = 0;
    }

    /**
     * Moves the origin when the camera has drifted too far.
     *
     * @return true when the origin changed this call
     */
    public boolean update(double camX, double camY, double camZ) {
        double dx = camX - originX;
        double dy = camY - originY;
        double dz = camZ - originZ;
        if (Math.abs(dx) < THRESHOLD && Math.abs(dz) < THRESHOLD && Math.abs(dy) < THRESHOLD * 4) {
            return false;
        }
        originX += Math.rint(dx / GRID) * GRID;
        originZ += Math.rint(dz / GRID) * GRID;
        originY += Math.rint(dy / (GRID * 4)) * GRID * 4;
        rebaseCount++;
        return true;
    }

    /** World metres to render-space metres. */
    public float localX(double worldX) {
        return (float) (worldX - originX);
    }

    public float localY(double worldY) {
        return (float) (worldY - originY);
    }

    public float localZ(double worldZ) {
        return (float) (worldZ - originZ);
    }

    /** Render-space metres back to world metres. */
    public double worldX(float localX) {
        return originX + localX;
    }

    public double worldY(float localY) {
        return originY + localY;
    }

    public double worldZ(float localZ) {
        return originZ + localZ;
    }
}
