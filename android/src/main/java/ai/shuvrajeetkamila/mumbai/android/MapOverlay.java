package ai.shuvrajeetkamila.mumbai.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.game.MapView;
import ai.shuvrajeetkamila.mumbai.game.Minimap;
import ai.shuvrajeetkamila.mumbai.world.WorldData;

/**
 * The full-screen map.
 *
 * <p>Drag to pan, pinch or use the buttons to zoom, tap to put a marker
 * down, GO to teleport there. It exists because a 52 by 72 km city with
 * a 320 m radar is a city you cannot navigate: the radar tells you which
 * way the next junction goes and nothing at all about where Bandra is.
 *
 * <p>The simulation is stopped while this is up, which is why reading
 * engine state straight off the UI thread here is safe. {@code
 * Engine.frame} returns before it touches the clock as soon as the map
 * opens, so nothing is mutating underneath, no time accumulates, and the
 * fixed-step accumulator does not have to swallow however long the
 * player spent deciding.
 *
 * <p>The teleport itself is not performed here. This sets an edge on the
 * shared {@link InputState} and the engine does it on its own thread,
 * because moving a rigid body and rebasing the floating origin from the
 * UI thread is the kind of race that shows up once in a hundred runs.
 */
final class MapOverlay extends View {

    /** Packed x1,z1,x2,z2,class per segment, reused between draws. */
    private static final int MAX_SEGMENTS = 9000;

    private final Paint sea = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint land = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadMinor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadMajor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadTrunk = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint player = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint target = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetBad = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint button = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonGo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonOff = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scaleBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final android.graphics.Path tri = new android.graphics.Path();

    private final double[] segments = new double[MAX_SEGMENTS * 5];
    private final float[] lineBuf = new float[MAX_SEGMENTS * 4];

    private final InputState input;

    private volatile MapView view;
    private volatile Minimap minimap;
    private volatile WorldData world;
    private volatile double playerX;
    private volatile double playerZ;
    private volatile double playerHeading;

    // ---- gesture state --------------------------------------------------

    private float lastX;
    private float lastY;
    private float downX;
    private float downY;
    private long downAt;
    private boolean dragging;
    private boolean pinching;
    private double pinchDistance;
    private float pinchCx;
    private float pinchCy;

    MapOverlay(Context c, InputState input) {
        super(c);
        this.input = input;
        setWillNotDraw(false);
        setVisibility(GONE);
        float d = c.getResources().getDisplayMetrics().density;

        sea.setColor(Color.rgb(16, 32, 52));
        land.setColor(Color.rgb(32, 42, 38));
        land.setStyle(Paint.Style.FILL);

        roadMinor.setColor(Color.rgb(104, 110, 112));
        roadMinor.setStrokeWidth(1.2f * d);
        roadMinor.setStrokeCap(Paint.Cap.ROUND);
        roadMajor.setColor(Color.rgb(196, 190, 150));
        roadMajor.setStrokeWidth(2.1f * d);
        roadMajor.setStrokeCap(Paint.Cap.ROUND);
        roadTrunk.setColor(Color.rgb(236, 196, 96));
        roadTrunk.setStrokeWidth(3.2f * d);
        roadTrunk.setStrokeCap(Paint.Cap.ROUND);

        player.setColor(Color.rgb(90, 200, 255));
        target.setColor(Color.rgb(255, 212, 64));
        target.setStyle(Paint.Style.STROKE);
        target.setStrokeWidth(2.4f * d);
        targetBad.setColor(Color.rgb(224, 86, 72));
        targetBad.setStyle(Paint.Style.STROKE);
        targetBad.setStrokeWidth(2.4f * d);

        panel.setColor(Color.argb(190, 8, 12, 16));
        button.setColor(Color.argb(220, 36, 44, 54));
        buttonGo.setColor(Color.argb(235, 44, 120, 70));
        buttonOff.setColor(Color.argb(150, 40, 40, 44));

        label.setColor(Color.WHITE);
        label.setTextSize(15 * d);
        label.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(true);
        title.setColor(Color.WHITE);
        title.setTextSize(14 * d);
        scaleBar.setColor(Color.WHITE);
        scaleBar.setStrokeWidth(2 * d);
    }

    /** Pushed from the render thread every few frames. */
    void update(MapView v, Minimap m, WorldData w,
                double px, double pz, double heading) {
        this.view = v;
        this.minimap = m;
        this.world = w;
        this.playerX = px;
        this.playerZ = pz;
        this.playerHeading = heading;
        boolean open = v != null && v.open;
        int want = open ? VISIBLE : GONE;
        if (getVisibility() != want) { setVisibility(want); }
        if (open) { invalidate(); }
    }

    // ---- drawing ---------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        MapView v = view;
        if (v == null || !v.open) { return; }
        float d = getResources().getDisplayMetrics().density;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) { return; }

        canvas.drawRect(0, 0, w, h, sea);
        drawLand(canvas, v, w, h);
        drawRoads(canvas, v, w, h, d);
        drawPlayer(canvas, v, w, h, d);
        drawTarget(canvas, v, w, h, d);
        drawChrome(canvas, v, w, h, d);
    }

    /**
     * Land, as a grid of cells sampled from the land mask.
     *
     * <p>Coarse on purpose. A per-pixel landmass would mean a million
     * mask lookups per draw, and the cell count is capped so that zooming
     * out does not quietly turn into a hundred thousand rectangles. The
     * result is chunky at the widest zoom, which is honest about what it
     * is: a sketch of the coastline, good enough to tell Salsette from
     * the Arabian Sea.
     */
    private void drawLand(Canvas canvas, MapView v, int w, int h) {
        WorldData wd = world;
        if (wd == null) { return; }
        int cols = 72;
        int rows = Math.max(8, Math.min(128, (int) (cols * (double) h / w)));
        float cw = (float) w / cols;
        float ch = (float) h / rows;
        for (int r = 0; r < rows; r++) {
            float sy = r * ch;
            int runStart = -1;
            for (int c = 0; c <= cols; c++) {
                boolean isLand = false;
                if (c < cols) {
                    double wx = v.worldXAt(c * cw + cw * 0.5f, sy + ch * 0.5f,
                            w, h);
                    double wz = v.worldZAt(c * cw + cw * 0.5f, sy + ch * 0.5f,
                            w, h);
                    isLand = wd.inBounds(wx, wz) && !wd.isWater(wx, wz);
                }
                // Coalesce horizontal runs into one rectangle instead of
                // drawing every cell: far fewer draw calls for the same
                // picture, and most rows are mostly one thing or the other.
                if (isLand && runStart < 0) {
                    runStart = c;
                } else if (!isLand && runStart >= 0) {
                    canvas.drawRect(runStart * cw, sy, c * cw, sy + ch + 1,
                            land);
                    runStart = -1;
                }
            }
        }
    }

    private void drawRoads(Canvas canvas, MapView v, int w, int h, float d) {
        Minimap m = minimap;
        if (m == null) { return; }
        double mpp = 1.0 / v.scale(w, h);
        int limit = Minimap.classLimitFor(mpp);
        double[] vis = v.visibleBounds(w, h);
        int n = m.collectInRect(vis[0], vis[1], vis[2], vis[3], limit,
                segments, MAX_SEGMENTS);

        // Three passes so the trunk network draws over the side streets
        // rather than being chopped up by them.
        for (int pass = 0; pass < 3; pass++) {
            Paint p = pass == 0 ? roadMinor : pass == 1 ? roadMajor : roadTrunk;
            int count = 0;
            for (int i = 0; i < n; i++) {
                int cls = (int) segments[i * 5 + 4];
                int band = cls <= 1 ? 2 : cls <= 3 ? 1 : 0;
                if (band != pass) { continue; }
                int o = count * 4;
                if (o + 4 > lineBuf.length) { break; }
                lineBuf[o] = (float) v.screenX(segments[i * 5],
                        segments[i * 5 + 1], w, h);
                lineBuf[o + 1] = (float) v.screenY(segments[i * 5],
                        segments[i * 5 + 1], w, h);
                lineBuf[o + 2] = (float) v.screenX(segments[i * 5 + 2],
                        segments[i * 5 + 3], w, h);
                lineBuf[o + 3] = (float) v.screenY(segments[i * 5 + 2],
                        segments[i * 5 + 3], w, h);
                count++;
            }
            if (count > 0) { canvas.drawLines(lineBuf, 0, count * 4, p); }
        }
    }

    private void drawPlayer(Canvas canvas, MapView v, int w, int h, float d) {
        float sx = (float) v.screenX(playerX, playerZ, w, h);
        float sy = (float) v.screenY(playerX, playerZ, w, h);
        float r = 9 * d;
        // A triangle pointing the way the player faces. Heading is
        // measured the same way the radar measures it.
        double a = playerHeading;
        float nx = (float) Math.sin(a);
        float ny = (float) -Math.cos(a);
        tri.reset();
        tri.moveTo(sx + nx * r, sy + ny * r);
        tri.lineTo(sx - ny * r * 0.62f - nx * r * 0.5f,
                sy + nx * r * 0.62f - ny * r * 0.5f);
        tri.lineTo(sx + ny * r * 0.62f - nx * r * 0.5f,
                sy - nx * r * 0.62f - ny * r * 0.5f);
        tri.close();
        canvas.drawPath(tri, player);
    }

    private void drawTarget(Canvas canvas, MapView v, int w, int h, float d) {
        if (!v.hasTarget) { return; }
        float sx = (float) v.screenX(v.targetX, v.targetZ, w, h);
        float sy = (float) v.screenY(v.targetX, v.targetZ, w, h);
        Paint p = canTeleport(v) ? target : targetBad;
        canvas.drawCircle(sx, sy, 11 * d, p);
        canvas.drawLine(sx - 17 * d, sy, sx - 5 * d, sy, p);
        canvas.drawLine(sx + 5 * d, sy, sx + 17 * d, sy, p);
        canvas.drawLine(sx, sy - 17 * d, sx, sy - 5 * d, p);
        canvas.drawLine(sx, sy + 5 * d, sx, sy + 17 * d, p);
    }

    private boolean canTeleport(MapView v) {
        return v.hasTarget && v.targetSuits(profileOf());
    }

    private ai.shuvrajeetkamila.mumbai.app.ControlProfile profileOf() {
        ai.shuvrajeetkamila.mumbai.app.ControlProfile p = profile;
        return p == null ? ai.shuvrajeetkamila.mumbai.app.ControlProfile.ON_FOOT
                : p;
    }

    private volatile ai.shuvrajeetkamila.mumbai.app.ControlProfile profile;

    void setProfile(ai.shuvrajeetkamila.mumbai.app.ControlProfile p) {
        this.profile = p;
    }

    private void drawChrome(Canvas canvas, MapView v, int w, int h, float d) {
        // Title strip.
        rect.set(0, 0, w, 44 * d);
        canvas.drawRect(rect, panel);
        title.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("MUMBAI", 14 * d, 28 * d, title);

        String desc = v.targetDescription();
        if (v.hasTarget && !canTeleport(v)) {
            desc = v.targetInWater ? "Water - you need a boat"
                    : "Dry land - you are in a boat";
        }
        title.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(desc, w - 14 * d, 28 * d, title);

        // Scale bar: pick a round number of metres near 90 dp wide.
        double mpp = 1.0 / v.scale(w, h);
        double want = 90 * d * mpp;
        double unit = Math.pow(10, Math.floor(Math.log10(Math.max(1, want))));
        double step = unit;
        if (want / unit > 5) { step = unit * 5; } else if (want / unit > 2) {
            step = unit * 2;
        }
        float barPx = (float) (step / mpp);
        float by = h - 118 * d;
        canvas.drawLine(16 * d, by, 16 * d + barPx, by, scaleBar);
        canvas.drawLine(16 * d, by - 5 * d, 16 * d, by + 5 * d, scaleBar);
        canvas.drawLine(16 * d + barPx, by - 5 * d, 16 * d + barPx, by + 5 * d,
                scaleBar);
        title.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(step >= 1000
                        ? String.format("%.0f km", step / 1000.0)
                        : String.format("%.0f m", step),
                16 * d, by - 10 * d, title);

        // Zoom buttons, right edge.
        drawButton(canvas, zoomInRect(w, h, d), "+", button, d);
        drawButton(canvas, zoomOutRect(w, h, d), "\u2212", button, d);
        // Recentre on the player.
        drawButton(canvas, hereRect(w, h, d), "ME", button, d);

        // Bottom bar: close and go.
        rect.set(0, h - 86 * d, w, h);
        canvas.drawRect(rect, panel);
        drawButton(canvas, closeRect(w, h, d), "CLOSE", button, d);
        drawButton(canvas, goRect(w, h, d), "GO",
                canTeleport(v) ? buttonGo : buttonOff, d);
    }

    private void drawButton(Canvas canvas, RectF r, String text, Paint bg,
                            float d) {
        canvas.drawRoundRect(r, 8 * d, 8 * d, bg);
        label.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, r.centerX(),
                r.centerY() + label.getTextSize() * 0.35f, label);
    }

    // Button geometry is computed from the view size in both the drawing
    // and the hit test, so the two cannot drift apart.
    private RectF zoomInRect(int w, int h, float d) {
        rectOut.set(w - 70 * d, h * 0.5f - 76 * d, w - 14 * d,
                h * 0.5f - 20 * d);
        return rectOut;
    }

    private RectF zoomOutRect(int w, int h, float d) {
        rectOut.set(w - 70 * d, h * 0.5f - 8 * d, w - 14 * d,
                h * 0.5f + 48 * d);
        return rectOut;
    }

    private RectF hereRect(int w, int h, float d) {
        rectOut.set(w - 70 * d, h * 0.5f + 60 * d, w - 14 * d,
                h * 0.5f + 116 * d);
        return rectOut;
    }

    private RectF closeRect(int w, int h, float d) {
        rectOut.set(14 * d, h - 72 * d, 14 * d + 120 * d, h - 14 * d);
        return rectOut;
    }

    private RectF goRect(int w, int h, float d) {
        rectOut.set(w - 14 * d - 150 * d, h - 72 * d, w - 14 * d, h - 14 * d);
        return rectOut;
    }

    private final RectF rectOut = new RectF();

    private static boolean hit(RectF r, float x, float y) {
        return x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
    }

    // ---- touch -----------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        MapView v = view;
        if (v == null || !v.open) { return false; }
        int w = getWidth();
        int h = getHeight();
        float d = getResources().getDisplayMetrics().density;

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX(0);
                downY = lastY = ev.getY(0);
                downAt = System.currentTimeMillis();
                dragging = false;
                pinching = false;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() >= 2) {
                    pinching = true;
                    dragging = true;
                    pinchDistance = spread(ev);
                    pinchCx = (ev.getX(0) + ev.getX(1)) * 0.5f;
                    pinchCy = (ev.getY(0) + ev.getY(1)) * 0.5f;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pinching && ev.getPointerCount() >= 2) {
                    double now = spread(ev);
                    float cx = (ev.getX(0) + ev.getX(1)) * 0.5f;
                    float cy = (ev.getY(0) + ev.getY(1)) * 0.5f;
                    if (pinchDistance > 1 && now > 1) {
                        v.zoomAbout(now / pinchDistance, cx, cy, w, h);
                    }
                    // The midpoint of the fingers also drags the map, or a
                    // pinch that wanders feels like it is fighting you.
                    v.panPixels(cx - pinchCx, cy - pinchCy, w, h);
                    pinchDistance = now;
                    pinchCx = cx;
                    pinchCy = cy;
                    invalidate();
                } else if (!pinching) {
                    float x = ev.getX(0);
                    float y = ev.getY(0);
                    if (!dragging
                            && Math.hypot(x - downX, y - downY) > 8 * d) {
                        dragging = true;
                    }
                    if (dragging) {
                        v.panPixels(x - lastX, y - lastY, w, h);
                        invalidate();
                    }
                    lastX = x;
                    lastY = y;
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // Dropping back to one finger: restart the drag from
                // wherever the remaining finger is, or the map jumps.
                pinching = false;
                dragging = true;
                int remaining = ev.getActionIndex() == 0 ? 1 : 0;
                if (ev.getPointerCount() > remaining) {
                    lastX = ev.getX(remaining);
                    lastY = ev.getY(remaining);
                }
                return true;

            case MotionEvent.ACTION_UP:
                boolean tap = !dragging
                        && System.currentTimeMillis() - downAt < 400;
                if (tap) { handleTap(v, downX, downY, w, h, d); }
                dragging = false;
                pinching = false;
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                pinching = false;
                return true;

            default:
                return true;
        }
    }

    private static double spread(MotionEvent ev) {
        return Math.hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1));
    }

    private void handleTap(MapView v, float x, float y, int w, int h, float d) {
        if (hit(zoomInRect(w, h, d), x, y)) { v.zoomBy(1.8); return; }
        if (hit(zoomOutRect(w, h, d), x, y)) { v.zoomBy(1 / 1.8); return; }
        if (hit(hereRect(w, h, d), x, y)) {
            v.centreX = playerX;
            v.centreZ = playerZ;
            v.clampCentre();
            return;
        }
        if (hit(closeRect(w, h, d), x, y)) {
            // Routed through the input edge so the engine closes it on its
            // own thread, the same way the MAP button does.
            input.map = true;
            return;
        }
        if (hit(goRect(w, h, d), x, y)) {
            if (canTeleport(v)) { input.mapConfirm = true; }
            return;
        }
        // Anywhere else on the map is a destination.
        if (y > 44 * d && y < h - 86 * d) {
            v.pick(x, y, w, h, minimap, world);
        }
    }
}
