package ai.shuvrajeetkamila.mumbai.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import ai.shuvrajeetkamila.mumbai.game.Minimap;
import ai.shuvrajeetkamila.mumbai.sim.Telemetry;

/**
 * The heads-up display.
 *
 * <p>Carries the OpenStreetMap attribution. That is not decoration: the
 * geometry in this game is derived from OSM, the ODbL requires the credit to
 * be visible to the user, and it is drawn every frame in a fixed position
 * that nothing else is allowed to cover.
 */
final class Hud extends View {

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint attrib = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final Paint roadMinor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadMajor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radarBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radarRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blipNext = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blipLater = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint money = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint objective = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint banner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path arrowPath = new android.graphics.Path();

    private volatile Telemetry t;
    /**
     * Read on the UI thread, written on the GL thread. Only ever read as a
     * snapshot of segment data that is replaced wholesale, so a torn read
     * shows one stale segment rather than a corrupt one - acceptable for a
     * radar, and far cheaper than locking the simulation for the HUD.
     */
    private volatile Minimap map;
    private final String attribution;

    Hud(Context c) {
        super(c);
        setWillNotDraw(false);
        float d = c.getResources().getDisplayMetrics().density;

        text.setColor(Color.WHITE);
        text.setTextSize(15 * d);
        text.setFakeBoldText(true);

        small.setColor(Color.argb(225, 206, 222, 236));
        small.setTextSize(11 * d);

        attrib.setColor(Color.argb(205, 228, 236, 244));
        attrib.setTextSize(9.5f * d);

        panel.setColor(Color.argb(118, 4, 10, 18));

        warn.setColor(Color.rgb(255, 128, 96));
        warn.setTextSize(19 * d);
        warn.setFakeBoldText(true);

        roadMinor.setColor(Color.argb(190, 150, 164, 178));
        roadMinor.setStyle(Paint.Style.STROKE);
        roadMinor.setStrokeWidth(1.4f * d);
        roadMinor.setStrokeCap(Paint.Cap.ROUND);

        roadMajor.setColor(Color.argb(235, 246, 214, 136));
        roadMajor.setStyle(Paint.Style.STROKE);
        roadMajor.setStrokeWidth(2.8f * d);
        roadMajor.setStrokeCap(Paint.Cap.ROUND);

        radarBg.setColor(Color.argb(150, 6, 13, 22));
        radarRing.setColor(Color.argb(170, 196, 212, 228));
        radarRing.setStyle(Paint.Style.STROKE);
        radarRing.setStrokeWidth(1.6f * d);

        arrow.setColor(Color.rgb(255, 232, 110));

        blipNext.setColor(0xFFFFC421);
        blipNext.setStyle(Paint.Style.FILL);
        blipLater.setColor(0x99FFC421);
        blipLater.setStyle(Paint.Style.FILL);

        money.setColor(0xFF7CE08A);
        money.setTextSize(22 * d);
        money.setFakeBoldText(true);
        objective.setColor(0xFFF2F2F2);
        objective.setTextSize(14 * d);
        banner.setColor(0xFFFFD452);
        banner.setTextSize(20 * d);
        banner.setFakeBoldText(true);

        attribution = c.getString(R.string.attribution);
    }

    void update(Telemetry telemetry, Minimap minimap) {
        this.t = telemetry;
        this.map = minimap;
        invalidate();
    }

    /**
     * The radar: real OSM centrelines, rotated so the way you are facing is
     * up.
     *
     * <p>Clipped to the disc rather than drawn and covered, because the
     * segments run well past the edge and overdrawing a panel on top of
     * them would hide the rest of the HUD underneath.
     */
    private void drawRadar(Canvas canvas, float d) {
        Minimap m = map;
        if (m == null) { return; }

        float r = 58 * d;
        float cx = getWidth() - r - 14 * d;
        float cy = getHeight() - r - 26 * d;
        float scale = r / (float) Minimap.RANGE_M;

        int saved = canvas.save();
        canvas.drawCircle(cx, cy, r, radarBg);
        canvas.clipRect(cx - r, cy - r, cx + r, cy + r);

        int n = m.segmentCount;
        double[] seg = m.segments;
        int[] cls = m.segmentClass;
        for (int i = 0; i < n; i++) {
            int o = i * 4;
            float x0 = cx + (float) seg[o] * scale;
            float y0 = cy - (float) seg[o + 1] * scale;
            float x1 = cx + (float) seg[o + 2] * scale;
            float y1 = cy - (float) seg[o + 3] * scale;
            // Classes 0-3 are motorway through secondary: the roads worth
            // picking out when you are deciding where to turn.
            canvas.drawLine(x0, y0, x1, y1, cls[i] <= 3 ? roadMajor : roadMinor);
        }
        // Job markers. Drawn inside the clip so one beyond the rim is
        // clamped to the edge and still points the way.
        for (int i = 0; i < m.blipCount; i++) {
            int o = i * 3;
            float bx = cx + (float) m.blips[o] * scale;
            float by = cy - (float) m.blips[o + 1] * scale;
            boolean next = (int) m.blips[o + 2] == Minimap.BLIP_NEXT;
            canvas.drawCircle(bx, by, (next ? 5f : 3.5f) * d,
                    next ? blipNext : blipLater);
        }
        canvas.restoreToCount(saved);

        canvas.drawCircle(cx, cy, r, radarRing);

        // Player, always at the centre, always pointing up.
        arrowPath.reset();
        arrowPath.moveTo(cx, cy - 7 * d);
        arrowPath.lineTo(cx - 5 * d, cy + 5 * d);
        arrowPath.lineTo(cx, cy + 2.5f * d);
        arrowPath.lineTo(cx + 5 * d, cy + 5 * d);
        arrowPath.close();
        canvas.drawPath(arrowPath, arrow);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        Telemetry s = t;

        // --- attribution. Drawn whether or not telemetry has arrived, because
        // the licence obligation does not wait for the world to finish loading.
        float aw = attrib.measureText(attribution);
        float ah = 15 * d;
        rect.set(8 * d, getHeight() - ah - 8 * d, 16 * d + aw, getHeight() - 4 * d);
        canvas.drawRoundRect(rect, 4 * d, 4 * d, panel);
        canvas.drawText(attribution, 12 * d, getHeight() - 10 * d, attrib);

        if (s == null) { return; }

        // --- main readout, top left ---------------------------------------
        float x = 12 * d;
        float y = 26 * d;
        rect.set(6 * d, 8 * d, 232 * d, 150 * d);
        canvas.drawRoundRect(rect, 6 * d, 6 * d, panel);

        canvas.drawText(String.format("%s   %.0f km/h", s.profile, s.speedKmh),
                x, y, text);
        y += 19 * d;
        canvas.drawText(String.format("%.5f N  %.5f E", s.lat, s.lon), x, y, small);
        y += 15 * d;
        canvas.drawText(String.format("alt %.0f m   hdg %03.0f\u00b0   %s",
                s.altitude, s.headingDeg, s.gear), x, y, small);
        y += 15 * d;
        if (!s.nearestPlace.isEmpty()) {
            canvas.drawText(s.nearestPlace, x, y, small);
        }
        y += 15 * d;
        canvas.drawText(String.format("%s %.1f    %s %.2f",
                s.auxLabel1, s.aux1, s.auxLabel2, s.aux2), x, y, small);
        y += 15 * d;
        canvas.drawText(String.format("tide %+.2f m   %s",
                s.tideM, s.onWater ? "over water" : "over land"), x, y, small);
        y += 15 * d;
        canvas.drawText(String.format("%.0f fps  %.1f ms  x%d",
                s.fps, s.frameMs, s.substeps), x, y, small);

        // --- streaming and thermal, top right ------------------------------
        float rx = getWidth() - 212 * d;
        rect.set(rx - 6 * d, 8 * d, getWidth() - 6 * d, 118 * d);
        canvas.drawRoundRect(rect, 6 * d, 6 * d, panel);
        float ry = 26 * d;
        canvas.drawText(s.quality + "   thermal " + s.thermalLabel, rx, ry, text);
        ry += 18 * d;
        canvas.drawText(String.format("tiles %d active / %d resident",
                s.tilesActive, s.tilesResident), rx, ry, small);
        ry += 15 * d;
        canvas.drawText(String.format("io %d  decode %d  gpu %d",
                s.tilesPendingIo, s.tilesDecoding, s.tilesQueuedGpu), rx, ry, small);
        ry += 15 * d;
        canvas.drawText(String.format("geometry %.1f MB   rebases %d",
                s.geometryBytes / 1048576.0, s.rebases), rx, ry, small);
        ry += 15 * d;
        canvas.drawText(String.format("%d draws   %dk tris",
                s.drawCalls, s.trianglesK), rx, ry, small);
        ry += 15 * d;
        canvas.drawText(String.format("%d cars   %d people", s.cars, s.pedestrians),
                rx, ry, small);

        // --- radar, bottom right ---------------------------------------------
        drawRadar(canvas, d);

        // --- clock, under the main panel -------------------------------------
        int hh = (int) s.timeOfDay;
        int mm = (int) ((s.timeOfDay - hh) * 60.0);
        canvas.drawText(String.format("%02d:%02d", hh, mm), x, 168 * d, text);

        // --- money and the current job, top right ----------------------------
        String cash = "Rs " + s.money;
        float cw = money.measureText(cash);
        canvas.drawText(cash, getWidth() - cw - 12 * d, 30 * d, money);

        if (!s.objective.isEmpty()) {
            String line = s.objective;
            if (s.jobDistance >= 0) {
                line = (s.jobDistance >= 1000
                        ? String.format("%.1f km", s.jobDistance / 1000.0)
                        : String.format("%d m", (int) s.jobDistance))
                        + "   " + line;
            }
            float ow = objective.measureText(line);
            float ox = getWidth() - ow - 12 * d;
            rect.set(ox - 8 * d, 38 * d, getWidth() - 6 * d, 60 * d);
            canvas.drawRoundRect(rect, 4 * d, 4 * d, panel);
            canvas.drawText(line, ox, 53 * d, objective);
        }
        if (s.jobTimeLeft > 0) {
            int sec = (int) Math.ceil(s.jobTimeLeft);
            String clk = String.format("%d:%02d", sec / 60, sec % 60);
            // Red once it is tight, because that is the whole tension.
            objective.setColor(sec <= 15 ? 0xFFFF6A5A : 0xFFF2F2F2);
            float tw = objective.measureText(clk);
            canvas.drawText(clk, getWidth() - tw - 12 * d, 78 * d, objective);
            objective.setColor(0xFFF2F2F2);
        }

        // --- job banner, upper centre ----------------------------------------
        if (!s.banner.isEmpty()) {
            float bw = banner.measureText(s.banner);
            float bx = (getWidth() - bw) * 0.5f;
            float by = getHeight() * 0.18f;
            rect.set(bx - 14 * d, by - 20 * d, bx + bw + 14 * d, by + 9 * d);
            canvas.drawRoundRect(rect, 6 * d, 6 * d, panel);
            canvas.drawText(s.banner, bx, by, banner);
        }

        // --- action prompt, above the controls -------------------------------
        if (!s.prompt.isEmpty()) {
            float pw = text.measureText(s.prompt);
            float px = (getWidth() - pw) * 0.5f;
            float py = getHeight() * 0.72f;
            rect.set(px - 10 * d, py - 15 * d, px + pw + 10 * d, py + 7 * d);
            canvas.drawRoundRect(rect, 5 * d, 5 * d, panel);
            canvas.drawText(s.prompt, px, py, text);
        }

        // --- warnings, centre ------------------------------------------------
        if (!s.warning.isEmpty()) {
            float w = warn.measureText(s.warning);
            canvas.drawText(s.warning, (getWidth() - w) * 0.5f, getHeight() * 0.3f, warn);
        }
    }
}
