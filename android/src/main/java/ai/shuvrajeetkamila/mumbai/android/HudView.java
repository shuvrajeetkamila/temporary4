package ai.shuvrajeetkamila.mumbai.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.List;
import java.util.Locale;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.game.Telemetry;

/**
 * Canvas HUD drawn above the GL surface: location, compass, vector minimap, vehicle
 * instruments, touch controls, diagnostics overlay, loading screen and attribution.
 */
public class HudView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private TouchController touch;
    private Telemetry telemetry;
    private MinimapSource minimap;

    /** Supplies vector minimap geometry in local metres around the player. */
    public interface MinimapSource {
        /**
         * Fill {@code out} with line segments (x1,z1,x2,z2 in metres relative to the player,
         * north = -z) and return the number of floats written.
         */
        int minimapSegments(float[] out, float radiusMetres);

        /** Fill {@code out} with coast/water polygon strips; returns floats written. */
        int minimapWater(float[] out, float radiusMetres);
    }

    private final float[] mapSegs = new float[4096];
    private final float[] mapWater = new float[4096];

    public HudView(Context context) {
        super(context);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        stroke.setStyle(Paint.Style.STROKE);
    }

    void attach(TouchController controller, Telemetry telemetry) {
        this.touch = controller;
        this.telemetry = telemetry;
    }

    void setMinimapSource(MinimapSource source) {
        this.minimap = source;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        // All input is handled by the GL surface underneath.
        return false;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        Telemetry t = telemetry;
        if (t == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        float unit = Math.min(w, h);

        if (t.fatalError) {
            drawFatal(c, w, h, unit, t);
            return;
        }
        if (t.loading) {
            drawLoading(c, w, h, unit, t);
            return;
        }

        drawTopBar(c, w, h, unit, t);
        drawMinimap(c, w, h, unit, t);
        drawInstruments(c, w, h, unit, t);
        drawControls(c, unit);
        drawToast(c, w, h, unit, t);
        if (t.diagnostics) {
            drawDiagnostics(c, w, h, unit, t);
        }
        if (t.paused) {
            drawPaused(c, w, h, unit, t);
        }
        drawAttribution(c, w, h, unit);
    }

    private void drawLoading(Canvas c, int w, int h, float unit, Telemetry t) {
        c.drawColor(Color.rgb(8, 12, 20));
        text.setColor(Color.rgb(255, 196, 92));
        text.setTextSize(unit * 0.11f);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("MUMBAI", w * 0.5f, h * 0.36f, text);
        text.setColor(Color.rgb(220, 232, 245));
        text.setTextSize(unit * 0.045f);
        c.drawText("ISLAND CITY", w * 0.5f, h * 0.45f, text);

        float barW = w * 0.56f;
        float barH = unit * 0.022f;
        float bx = (w - barW) * 0.5f;
        float by = h * 0.62f;
        fill.setColor(Color.rgb(30, 38, 52));
        rect.set(bx, by, bx + barW, by + barH);
        c.drawRoundRect(rect, barH * 0.5f, barH * 0.5f, fill);
        fill.setColor(Color.rgb(255, 170, 60));
        rect.set(bx, by, bx + barW * Mth.clamp01(t.loadProgress), by + barH);
        c.drawRoundRect(rect, barH * 0.5f, barH * 0.5f, fill);

        text.setColor(Color.rgb(150, 170, 190));
        text.setTextSize(unit * 0.028f);
        c.drawText(t.loadStage, w * 0.5f, by + barH * 3.2f, text);
        text.setTextSize(unit * 0.022f);
        c.drawText(String.format(Locale.US, "%.0f%%", t.loadProgress * 100f), w * 0.5f, by + barH * 5.4f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawFatal(Canvas c, int w, int h, float unit, Telemetry t) {
        c.drawColor(Color.rgb(32, 8, 8));
        text.setColor(Color.rgb(255, 120, 110));
        text.setTextSize(unit * 0.05f);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("WORLD DATA ERROR", w * 0.5f, h * 0.38f, text);
        text.setColor(Color.rgb(230, 220, 220));
        text.setTextSize(unit * 0.028f);
        String msg = t.fatalMessage == null ? "" : t.fatalMessage;
        int maxChars = 64;
        int line = 0;
        for (int i = 0; i < msg.length(); i += maxChars) {
            c.drawText(msg.substring(i, Math.min(msg.length(), i + maxChars)),
                    w * 0.5f, h * 0.48f + line * unit * 0.04f, text);
            line++;
        }
        c.drawText("Tap to retry", w * 0.5f, h * 0.76f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawTopBar(Canvas c, int w, int h, float unit, Telemetry t) {
        float pad = unit * 0.025f;
        fill.setColor(Color.argb(120, 6, 10, 16));
        rect.set(pad, pad, pad + unit * 0.62f, pad + unit * 0.085f);
        c.drawRoundRect(rect, unit * 0.014f, unit * 0.014f, fill);

        text.setColor(Color.rgb(255, 208, 120));
        text.setTextSize(unit * 0.034f);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(t.locationName, pad + unit * 0.02f, pad + unit * 0.045f, text);

        text.setColor(Color.rgb(170, 190, 210));
        text.setTextSize(unit * 0.022f);
        int hours = (int) t.timeOfDayHours;
        int mins = (int) ((t.timeOfDayHours - hours) * 60f);
        c.drawText(String.format(Locale.US, "%02d:%02d  %s  %s", hours, mins,
                        compass(t.headingDeg), t.controlProfile),
                pad + unit * 0.02f, pad + unit * 0.075f, text);

        // Compass ribbon.
        float cx = w * 0.5f;
        float cy = pad + unit * 0.03f;
        stroke.setColor(Color.argb(150, 220, 230, 240));
        stroke.setStrokeWidth(unit * 0.003f);
        c.drawLine(cx - unit * 0.18f, cy + unit * 0.02f, cx + unit * 0.18f, cy + unit * 0.02f, stroke);
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 8; i++) {
            float bearing = i * 45f;
            float delta = Mth.wrapPi((float) Math.toRadians(bearing - t.headingDeg)) * Mth.RAD2DEG;
            if (Math.abs(delta) > 70f) {
                continue;
            }
            float x = cx + (delta / 70f) * unit * 0.18f;
            text.setColor(Color.argb(Math.max(60, 255 - (int) (Math.abs(delta) * 2.5f)), 235, 240, 248));
            text.setTextSize(unit * 0.022f);
            c.drawText(COMPASS8[i], x, cy + unit * 0.012f, text);
        }
        text.setColor(Color.rgb(255, 170, 60));
        c.drawText("v", cx, cy + unit * 0.037f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private static final String[] COMPASS8 = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private String compass(float deg) {
        int idx = (int) Math.floor(((Mth.wrap360(deg) + 22.5f) % 360f) / 45f);
        return COMPASS8[Mth.clamp(idx, 0, 7)] + String.format(Locale.US, " %03.0f", Mth.wrap360(deg));
    }

    private void drawMinimap(Canvas c, int w, int h, float unit, Telemetry t) {
        float r = unit * 0.115f;
        float cx = w - unit * 0.035f - r;
        float cy = unit * 0.035f + r + unit * 0.07f;

        fill.setColor(Color.argb(170, 8, 14, 22));
        c.drawCircle(cx, cy, r, fill);

        int saved = c.save();
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        c.clipPath(path);

        float radiusMetres = 900f;
        float scale = r / radiusMetres;
        MinimapSource src = minimap;
        if (src != null) {
            int nWater = src.minimapWater(mapWater, radiusMetres);
            fill.setColor(Color.rgb(16, 52, 86));
            for (int i = 0; i + 3 < nWater; i += 4) {
                rect.set(cx + mapWater[i] * scale, cy + mapWater[i + 1] * scale,
                        cx + mapWater[i + 2] * scale, cy + mapWater[i + 3] * scale);
                c.drawRect(rect, fill);
            }
            int n = src.minimapSegments(mapSegs, radiusMetres);
            stroke.setColor(Color.rgb(205, 205, 195));
            stroke.setStrokeWidth(Math.max(1.2f, unit * 0.0035f));
            for (int i = 0; i + 3 < n; i += 4) {
                c.drawLine(cx + mapSegs[i] * scale, cy + mapSegs[i + 1] * scale,
                        cx + mapSegs[i + 2] * scale, cy + mapSegs[i + 3] * scale, stroke);
            }
        }
        c.restoreToCount(saved);

        stroke.setColor(Color.argb(200, 240, 200, 120));
        stroke.setStrokeWidth(unit * 0.004f);
        c.drawCircle(cx, cy, r, stroke);

        // Player arrow (map is north-up).
        float a = (float) Math.toRadians(t.headingDeg);
        float ax = (float) Math.sin(a);
        float az = -(float) Math.cos(a);
        path.reset();
        float s = unit * 0.016f;
        path.moveTo(cx + ax * s, cy + az * s);
        path.lineTo(cx - az * s * 0.6f - ax * s * 0.5f, cy + ax * s * 0.6f - az * s * 0.5f);
        path.lineTo(cx + az * s * 0.6f - ax * s * 0.5f, cy - ax * s * 0.6f - az * s * 0.5f);
        path.close();
        fill.setColor(Color.rgb(255, 180, 60));
        c.drawPath(path, fill);
    }

    private void drawInstruments(Canvas c, int w, int h, float unit, Telemetry t) {
        String profile = t.controlProfile;
        float x = unit * 0.035f;
        float y = h - unit * 0.05f;
        text.setTextAlign(Paint.Align.LEFT);

        if ("CAR".equals(profile) || "BOAT".equals(profile)) {
            drawBigReadout(c, x, y, unit,
                    String.format(Locale.US, "%.0f", Math.abs(t.speedKmh)),
                    "BOAT".equals(profile) ? String.format(Locale.US, "km/h  %.1f kn", t.speedKnots) : "km/h");
            text.setColor(Color.rgb(190, 205, 220));
            text.setTextSize(unit * 0.024f);
            if ("CAR".equals(profile)) {
                c.drawText(String.format(Locale.US, "RPM %.0f   GEAR %s", t.engineRpm, gearLabel(t.gear)),
                        x, y - unit * 0.075f, text);
            } else {
                c.drawText(String.format(Locale.US, "ENG %s   TIDE %+.1fm", t.engineOn ? "ON" : "OFF", t.tideMetres),
                        x, y - unit * 0.075f, text);
            }
        } else if ("HELICOPTER".equals(profile)) {
            drawBigReadout(c, x, y, unit, String.format(Locale.US, "%.0f", t.altitudeM), "m AGL");
            text.setColor(Color.rgb(190, 205, 220));
            text.setTextSize(unit * 0.024f);
            c.drawText(String.format(Locale.US, "ROTOR %.0f%%  COLL %.0f%%  V/S %+.1f m/s  GS %.0f km/h",
                    t.rotorRpm, t.collective * 100f, t.verticalSpeed, t.speedKmh), x, y - unit * 0.075f, text);
        } else if ("AEROPLANE".equals(profile)) {
            drawBigReadout(c, x, y, unit, String.format(Locale.US, "%.0f", t.airspeedKts), "kt IAS");
            text.setColor(t.stallWarning ? Color.rgb(255, 90, 80) : Color.rgb(190, 205, 220));
            text.setTextSize(unit * 0.024f);
            c.drawText(String.format(Locale.US, "ALT %.0f m  V/S %+.1f  RPM %.0f  FLAP %d  GEAR %s%s",
                            t.altitudeM, t.verticalSpeed, t.engineRpm, t.flapNotch, t.gearDown ? "DN" : "UP",
                            t.stallWarning ? "   *** STALL ***" : ""),
                    x, y - unit * 0.075f, text);
        } else {
            text.setColor(Color.rgb(190, 205, 220));
            text.setTextSize(unit * 0.026f);
            c.drawText(String.format(Locale.US, "%.1f m/s", t.speedKmh / 3.6f), x, y, text);
        }

        if (t.contextHint != null && !t.contextHint.isEmpty()) {
            text.setColor(Color.rgb(255, 214, 140));
            text.setTextSize(unit * 0.026f);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(t.contextHint, w * 0.5f, h - unit * 0.035f, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private String gearLabel(int gear) {
        if (gear < 0) {
            return "R";
        }
        if (gear == 0) {
            return "N";
        }
        return Integer.toString(gear);
    }

    private void drawBigReadout(Canvas c, float x, float y, float unit, String value, String unitLabel) {
        text.setColor(Color.rgb(255, 235, 205));
        text.setTextSize(unit * 0.075f);
        c.drawText(value, x, y, text);
        float adv = text.measureText(value);
        text.setColor(Color.rgb(160, 180, 200));
        text.setTextSize(unit * 0.026f);
        c.drawText(unitLabel, x + adv + unit * 0.012f, y, text);
    }

    private void drawControls(Canvas c, float unit) {
        TouchController tc = touch;
        if (tc == null) {
            return;
        }
        List<TouchController.Widget> ws = tc.widgets();
        for (int i = 0; i < ws.size(); i++) {
            TouchController.Widget wd = ws.get(i);
            if (!wd.visible) {
                continue;
            }
            if (wd.type == TouchController.TYPE_STICK) {
                fill.setColor(Color.argb(55, 230, 240, 255));
                c.drawCircle(wd.cx, wd.cy, wd.radius, fill);
                stroke.setColor(Color.argb(110, 240, 245, 255));
                stroke.setStrokeWidth(unit * 0.0035f);
                c.drawCircle(wd.cx, wd.cy, wd.radius, stroke);
                fill.setColor(Color.argb(150, 255, 200, 120));
                c.drawCircle(wd.cx + wd.vx * wd.radius * 0.72f, wd.cy + wd.vy * wd.radius * 0.72f,
                        wd.radius * 0.33f, fill);
            } else {
                fill.setColor(wd.pressed || wd.value > 0.5f
                        ? Color.argb(190, 255, 170, 60) : Color.argb(70, 220, 232, 245));
                c.drawCircle(wd.cx, wd.cy, wd.radius, fill);
                stroke.setColor(Color.argb(140, 250, 250, 255));
                stroke.setStrokeWidth(unit * 0.003f);
                c.drawCircle(wd.cx, wd.cy, wd.radius, stroke);
            }
            if (wd.label != null && !wd.label.isEmpty()) {
                text.setColor(Color.argb(220, 20, 26, 34));
                if (!(wd.pressed || wd.value > 0.5f)) {
                    text.setColor(Color.argb(200, 235, 242, 250));
                }
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(wd.radius * 0.38f);
                c.drawText(wd.label, wd.cx, wd.cy + wd.radius * 0.14f, text);
                text.setTextAlign(Paint.Align.LEFT);
            }
        }
    }

    private void drawToast(Canvas c, int w, int h, float unit, Telemetry t) {
        if (t.toast == null || t.toast.isEmpty() || System.currentTimeMillis() > t.toastUntilMs) {
            return;
        }
        text.setTextSize(unit * 0.03f);
        text.setTextAlign(Paint.Align.CENTER);
        float tw = text.measureText(t.toast);
        fill.setColor(Color.argb(170, 8, 12, 18));
        rect.set(w * 0.5f - tw * 0.5f - unit * 0.02f, h * 0.16f,
                w * 0.5f + tw * 0.5f + unit * 0.02f, h * 0.16f + unit * 0.05f);
        c.drawRoundRect(rect, unit * 0.012f, unit * 0.012f, fill);
        text.setColor(Color.rgb(255, 225, 170));
        c.drawText(t.toast, w * 0.5f, h * 0.16f + unit * 0.035f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawDiagnostics(Canvas c, int w, int h, float unit, Telemetry t) {
        float x = unit * 0.035f;
        float y = unit * 0.14f;
        float lh = unit * 0.026f;
        text.setTextSize(unit * 0.021f);
        fill.setColor(Color.argb(150, 4, 8, 12));
        rect.set(x - unit * 0.012f, y - lh, x + unit * 0.52f, y + lh * 11.4f);
        c.drawRoundRect(rect, unit * 0.01f, unit * 0.01f, fill);
        text.setColor(Color.rgb(140, 255, 180));
        String[] lines = {
                String.format(Locale.US, "FPS %.1f  frame %.1fms  sim %.1fms", t.fps, t.cpuFrameMs, t.simMs),
                String.format(Locale.US, "draws %d  tris %dk", t.drawCalls, t.triangles / 1000),
                String.format(Locale.US, "chunks act %d  cpu %d  pend %d", t.chunksActive, t.chunksCpuReady, t.chunksPending),
                String.format(Locale.US, "heap %d MB  gpu %d MB", t.heapUsedMb, t.gpuBytesMb),
                String.format(Locale.US, "bodies %d  traffic %d  peds %d", t.activeBodies, t.trafficAgents, t.pedestrians),
                String.format(Locale.US, "profile %s%s", t.profileName, t.thermalEco ? "  [THERMAL]" : ""),
                String.format(Locale.US, "pos %.1f %.1f %.1f", t.worldX, t.worldY, t.worldZ),
                String.format(Locale.US, "geo %.5f N  %.5f E", t.latitude, t.longitude),
                String.format(Locale.US, "hdg %.0f  spd %.1f km/h", t.headingDeg, t.speedKmh),
                String.format(Locale.US, "rain %.2f  tide %+.2f m", t.rainIntensity, t.tideMetres),
        };
        for (int i = 0; i < lines.length; i++) {
            c.drawText(lines[i], x, y + i * lh, text);
        }
    }

    private void drawPaused(Canvas c, int w, int h, float unit, Telemetry t) {
        c.drawColor(Color.argb(150, 4, 8, 14));
        text.setColor(Color.rgb(255, 200, 110));
        text.setTextSize(unit * 0.07f);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("PAUSED", w * 0.5f, h * 0.42f, text);
        text.setColor(Color.rgb(200, 215, 230));
        text.setTextSize(unit * 0.026f);
        c.drawText("Tap the pause button again to resume", w * 0.5f, h * 0.52f, text);
        c.drawText(String.format(Locale.US, "%s   %s   %.5f N  %.5f E",
                t.locationName, t.profileName, t.latitude, t.longitude), w * 0.5f, h * 0.58f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawAttribution(Canvas c, int w, int h, float unit) {
        text.setColor(Color.argb(130, 220, 230, 240));
        text.setTextSize(unit * 0.018f);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Mumbai: Island City - original world build - see Credits", w - unit * 0.02f, h - unit * 0.012f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }
}
