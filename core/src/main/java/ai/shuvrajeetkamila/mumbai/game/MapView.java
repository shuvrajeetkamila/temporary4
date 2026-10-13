package ai.shuvrajeetkamila.mumbai.game;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.world.WorldData;

/**
 * The full-screen map: a pannable, zoomable, north-up view of the whole
 * island, and the thing you pick a teleport destination on.
 *
 * <p>This is deliberately pure arithmetic with no Android in it, because
 * the interesting part is the coordinate transform and that is exactly
 * the part worth testing. The renderer and the touch handler on the
 * other side of the fence only feed it pixels.
 *
 * <p>The world-to-screen transform goes through {@link Minimap#mapX} and
 * {@link Minimap#mapY} rather than reimplementing the rotation. The
 * radar once had two copies of that transform which disagreed with each
 * other, so road lines and mission blips drew in different places and
 * the blips drifted as the player turned. One copy, used by everything.
 *
 * <p>Zoom is held as the number of world metres across the shorter side
 * of the view, which is the quantity that stays meaningful whatever the
 * screen's shape: 200 m is a junction, 80 km is Vasai Creek to Navy
 * Nagar in one screen.
 */
public final class MapView {

    /** Closest zoom, metres across the short side. A single junction. */
    public static final double MIN_SPAN_M = 200.0;

    /** Widest zoom. The whole area of interest, with room around it. */
    public static final double MAX_SPAN_M = 90000.0;

    /** What the map opens at: a few streets either side of the player. */
    public static final double DEFAULT_SPAN_M = 3200.0;

    /**
     * How far from the tap a road may be and still capture the target.
     *
     * <p>Teleporting onto a roof or into the middle of a block is a
     * broken feature, so a destination is pulled onto the nearest road
     * when there is one within reach. The reach is in world metres, not
     * pixels, so it does not change meaning as you zoom.
     */
    public static final double SNAP_M = 140.0;

    public boolean open;

    /** Metres across the shorter side of the view. */
    public double spanM = DEFAULT_SPAN_M;

    /** World position at the centre of the view, metres. */
    public double centreX;
    public double centreZ;

    /**
     * View rotation, radians. Zero is north-up, which is what a map a
     * player reads to choose a destination should be. The field exists
     * so the same transform can serve a heading-up view without a second
     * copy of the maths.
     */
    public double rotation;

    // ---- the pending teleport destination -------------------------------

    public boolean hasTarget;
    public double targetX;
    public double targetZ;
    /** True when the tap was pulled onto a road rather than used raw. */
    public boolean targetOnRoad;
    public boolean targetInWater;
    /** False when the tap landed outside the built world entirely. */
    public boolean targetInBounds;

    private double worldMinX = -1e9;
    private double worldMinZ = -1e9;
    private double worldMaxX = 1e9;
    private double worldMaxZ = 1e9;

    public void setWorldBounds(double minX, double minZ,
                               double maxX, double maxZ) {
        worldMinX = Math.min(minX, maxX);
        worldMaxX = Math.max(minX, maxX);
        worldMinZ = Math.min(minZ, maxZ);
        worldMaxZ = Math.max(minZ, maxZ);
    }

    public void setWorldBounds(WorldData w) {
        if (w == null) { return; }
        setWorldBounds(w.minWorldX(), w.minWorldZ(),
                w.maxWorldX(), w.maxWorldZ());
    }

    /** Opens the map centred on the player at the default zoom. */
    public void openAt(double playerX, double playerZ) {
        open = true;
        spanM = DEFAULT_SPAN_M;
        centreX = playerX;
        centreZ = playerZ;
        rotation = 0;
        clearTarget();
        clampCentre();
    }

    public void close() {
        open = false;
        clearTarget();
    }

    public void clearTarget() {
        hasTarget = false;
        targetOnRoad = false;
        targetInWater = false;
        targetInBounds = false;
    }

    // ---- the transform ---------------------------------------------------

    /** Pixels per world metre for a view of this size. */
    public double scale(int viewW, int viewH) {
        int shorter = Math.max(1, Math.min(viewW, viewH));
        return shorter / Math.max(1e-6, spanM);
    }

    public double screenX(double worldX, double worldZ, int viewW, int viewH) {
        double c = Math.cos(-rotation);
        double s = Math.sin(-rotation);
        double mx = Minimap.mapX(worldX - centreX, worldZ - centreZ, c, s);
        return viewW * 0.5 + mx * scale(viewW, viewH);
    }

    public double screenY(double worldX, double worldZ, int viewW, int viewH) {
        double c = Math.cos(-rotation);
        double s = Math.sin(-rotation);
        double my = Minimap.mapY(worldX - centreX, worldZ - centreZ, c, s);
        // Screen y grows downwards and map y grows north, so it flips.
        return viewH * 0.5 - my * scale(viewW, viewH);
    }

    public double worldXAt(double screenX, double screenY,
                           int viewW, int viewH) {
        double k = 1.0 / scale(viewW, viewH);
        // Invert mapX/mapY. With u = mapX and w = -mapY, the pair (u, w)
        // is the world offset turned by the view rotation, so undoing it
        // is the transpose.
        double u = (screenX - viewW * 0.5) * k;
        double w = (screenY - viewH * 0.5) * k;
        double c = Math.cos(-rotation);
        double s = Math.sin(-rotation);
        return centreX + u * c + w * s;
    }

    public double worldZAt(double screenX, double screenY,
                           int viewW, int viewH) {
        double k = 1.0 / scale(viewW, viewH);
        double u = (screenX - viewW * 0.5) * k;
        double w = (screenY - viewH * 0.5) * k;
        double c = Math.cos(-rotation);
        double s = Math.sin(-rotation);
        return centreZ - u * s + w * c;
    }

    // ---- navigation ------------------------------------------------------

    /**
     * Zooms by a factor while holding one screen point over the same
     * world point, which is what a pinch has to do to feel attached to
     * the fingers rather than to the middle of the screen.
     */
    public void zoomAbout(double factor, double screenX, double screenY,
                          int viewW, int viewH) {
        if (!(factor > 0) || !Double.isFinite(factor)) { return; }
        double beforeX = worldXAt(screenX, screenY, viewW, viewH);
        double beforeZ = worldZAt(screenX, screenY, viewW, viewH);
        setSpan(spanM / factor);
        double afterX = worldXAt(screenX, screenY, viewW, viewH);
        double afterZ = worldZAt(screenX, screenY, viewW, viewH);
        centreX += beforeX - afterX;
        centreZ += beforeZ - afterZ;
        clampCentre();
    }

    /** Zooms about the centre of the view. Used by the +/- buttons. */
    public void zoomBy(double factor) {
        setSpan(spanM / Math.max(1e-6, factor));
    }

    public void setSpan(double metres) {
        if (!Double.isFinite(metres)) { return; }
        spanM = Math.max(MIN_SPAN_M, Math.min(MAX_SPAN_M, metres));
    }

    /** Drags the map by a pixel delta. */
    /** Drags the map by a pixel delta, so the map follows the finger. */
    public void panPixels(double dxPixels, double dyPixels,
                          int viewW, int viewH) {
        double k = 1.0 / scale(viewW, viewH);
        double u = -dxPixels * k;
        double w = -dyPixels * k;
        double c = Math.cos(-rotation);
        double s = Math.sin(-rotation);
        centreX += u * c + w * s;
        centreZ += -u * s + w * c;
        clampCentre();
    }

    /**
     * Keeps the centre inside the world.
     *
     * <p>Without this you can drag the island off the edge of the screen
     * and be left looking at empty space with no way of knowing which
     * direction to drag back.
     */
    public void clampCentre() {
        if (worldMinX < -1e8) { return; }
        double marginX = (worldMaxX - worldMinX) * 0.05;
        double marginZ = (worldMaxZ - worldMinZ) * 0.05;
        centreX = Math.max(worldMinX - marginX,
                Math.min(worldMaxX + marginX, centreX));
        centreZ = Math.max(worldMinZ - marginZ,
                Math.min(worldMaxZ + marginZ, centreZ));
    }

    /** True when the world rectangle is at least partly on screen. */
    public boolean isVisible(double worldX, double worldZ,
                             int viewW, int viewH, double slackPx) {
        double sx = screenX(worldX, worldZ, viewW, viewH);
        double sy = screenY(worldX, worldZ, viewW, viewH);
        return sx >= -slackPx && sx <= viewW + slackPx
                && sy >= -slackPx && sy <= viewH + slackPx;
    }

    /** World rectangle currently on screen, as {minX, minZ, maxX, maxZ}. */
    public double[] visibleBounds(int viewW, int viewH) {
        double[] out = new double[] {1e18, 1e18, -1e18, -1e18};
        double[][] corners = {
                {0, 0}, {viewW, 0}, {0, viewH}, {viewW, viewH}};
        for (double[] cxy : corners) {
            double wx = worldXAt(cxy[0], cxy[1], viewW, viewH);
            double wz = worldZAt(cxy[0], cxy[1], viewW, viewH);
            out[0] = Math.min(out[0], wx);
            out[1] = Math.min(out[1], wz);
            out[2] = Math.max(out[2], wx);
            out[3] = Math.max(out[3], wz);
        }
        return out;
    }

    // ---- choosing a destination -----------------------------------------

    /**
     * Picks a teleport destination from a screen tap.
     *
     * <p>The raw tap is pulled onto the nearest road within {@link #SNAP_M}
     * when there is one, because a destination in the middle of a block
     * is somewhere you cannot drive out of. Water is allowed but
     * flagged - arriving in the harbour is reasonable in a boat and not
     * otherwise - and the caller decides what to do about it.
     *
     * @return true if the tap produced a usable destination
     */
    public boolean pick(double screenX, double screenY, int viewW, int viewH,
                        Minimap minimap, WorldData world) {
        double wx = worldXAt(screenX, screenY, viewW, viewH);
        double wz = worldZAt(screenX, screenY, viewW, viewH);
        return pickWorld(wx, wz, minimap, world);
    }

    /** The same decision, from world metres. Split out so it can be tested. */
    public boolean pickWorld(double wx, double wz,
                             Minimap minimap, WorldData world) {
        targetX = wx;
        targetZ = wz;
        targetOnRoad = false;
        targetInWater = false;
        targetInBounds = world == null || world.inBounds(wx, wz);

        if (minimap != null) {
            double[] road = minimap.nearestRoad(wx, wz, SNAP_M);
            if (road != null) {
                targetX = road[0];
                targetZ = road[1];
                targetOnRoad = true;
            }
        }
        if (world != null) {
            targetInBounds = world.inBounds(targetX, targetZ);
            targetInWater = targetInBounds && world.isWater(targetX, targetZ);
        }
        hasTarget = targetInBounds;
        return hasTarget;
    }

    /**
     * Whether the pending target is somewhere the given profile can
     * actually be put down.
     */
    public boolean targetSuits(ControlProfile profile) {
        if (!hasTarget || !targetInBounds) { return false; }
        if (!targetInWater) {
            // A boat on dry land is as broken as a pedestrian at sea.
            return profile != ControlProfile.BOAT;
        }
        return profile == ControlProfile.BOAT
                || profile == ControlProfile.HELICOPTER
                || profile == ControlProfile.AEROPLANE;
    }

    /** One line for the HUD, describing what was picked. */
    public String targetDescription() {
        if (!hasTarget) { return "Tap the map to choose a destination"; }
        if (!targetInBounds) { return "Outside the city"; }
        if (targetInWater) { return "Water"; }
        return targetOnRoad ? "On a road" : "Off-road";
    }
}
