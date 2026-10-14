package ai.shuvrajeetkamila.mumbai.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.geo.GeoProjection;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The full-screen map's coordinate transform and teleport picking.
 *
 * <p>Worth testing on its own because a map is a pure function from
 * world metres to pixels and back, and a transform that is subtly wrong
 * looks almost right: roads land in plausible places and the bug only
 * shows when you tap one and arrive somewhere else. The radar had
 * exactly that fault once, from keeping two copies of the rotation.
 */
final class MapViewTest {

    private static final int W = 1080;
    private static final int H = 1920;

    private static MapView centred() {
        MapView m = new MapView();
        m.setWorldBounds(-30000, -40000, 30000, 40000);
        m.openAt(1234, -5678);
        return m;
    }

    @Test
    @DisplayName("world to screen and back is the identity")
    void transformRoundTrips() {
        MapView m = centred();
        for (double span : new double[] {250, 1000, 3200, 20000, 80000}) {
            m.setSpan(span);
            for (double dx = -4000; dx <= 4000; dx += 1700) {
                for (double dz = -4000; dz <= 4000; dz += 1700) {
                    double wx = m.centreX + dx;
                    double wz = m.centreZ + dz;
                    double sx = m.screenX(wx, wz, W, H);
                    double sy = m.screenY(wx, wz, W, H);
                    double bx = m.worldXAt(sx, sy, W, H);
                    double bz = m.worldZAt(sx, sy, W, H);
                    assertEquals(wx, bx, 1e-6, "x round trip at span " + span);
                    assertEquals(wz, bz, 1e-6, "z round trip at span " + span);
                }
            }
        }
    }

    @Test
    @DisplayName("the map is north up and east right")
    void orientationIsNorthUp() {
        MapView m = centred();
        // World Z is south, so north is -Z, and screen y grows downwards.
        double northY = m.screenY(m.centreX, m.centreZ - 500, W, H);
        double southY = m.screenY(m.centreX, m.centreZ + 500, W, H);
        assertTrue(northY < southY,
                "north must be above south: " + northY + " vs " + southY);
        double eastX = m.screenX(m.centreX + 500, m.centreZ, W, H);
        double westX = m.screenX(m.centreX - 500, m.centreZ, W, H);
        assertTrue(eastX > westX,
                "east must be right of west: " + eastX + " vs " + westX);
        // And the centre of the view is the centre of the map.
        assertEquals(W * 0.5, m.screenX(m.centreX, m.centreZ, W, H), 1e-9);
        assertEquals(H * 0.5, m.screenY(m.centreX, m.centreZ, W, H), 1e-9);
    }

    @Test
    @DisplayName("the transform agrees with the radar's")
    void sharesTheRadarTransform() {
        // One copy of the rotation, used by both. If this ever diverges,
        // road lines and blips will disagree the way they used to.
        MapView m = centred();
        double dx = 400;
        double dz = -250;
        double mx = Minimap.mapX(dx, dz, 1, 0);
        double my = Minimap.mapY(dx, dz, 1, 0);
        double sx = m.screenX(m.centreX + dx, m.centreZ + dz, W, H);
        double sy = m.screenY(m.centreX + dx, m.centreZ + dz, W, H);
        double k = m.scale(W, H);
        assertEquals(W * 0.5 + mx * k, sx, 1e-9);
        assertEquals(H * 0.5 - my * k, sy, 1e-9);
    }

    @Test
    @DisplayName("pinching holds the world point under the fingers")
    void zoomKeepsTheFocalPoint() {
        MapView m = centred();
        double fx = 300;
        double fy = 1400;
        double beforeX = m.worldXAt(fx, fy, W, H);
        double beforeZ = m.worldZAt(fx, fy, W, H);
        m.zoomAbout(2.4, fx, fy, W, H);
        assertEquals(beforeX, m.worldXAt(fx, fy, W, H), 1e-6,
                "the point under the finger moved while zooming in");
        assertEquals(beforeZ, m.worldZAt(fx, fy, W, H), 1e-6);
        m.zoomAbout(0.3, fx, fy, W, H);
        assertEquals(beforeX, m.worldXAt(fx, fy, W, H), 1e-6,
                "the point under the finger moved while zooming out");
        assertEquals(beforeZ, m.worldZAt(fx, fy, W, H), 1e-6);
    }

    @Test
    @DisplayName("dragging moves the map with the finger, not against it")
    void panFollowsTheFinger() {
        MapView m = centred();
        double sx = 540;
        double sy = 960;
        double wx = m.worldXAt(sx, sy, W, H);
        double wz = m.worldZAt(sx, sy, W, H);
        // Drag 100 px right and 60 px down; the world point that was
        // under the finger should still be under it.
        m.panPixels(100, 60, W, H);
        assertEquals(wx, m.worldXAt(sx + 100, sy + 60, W, H), 1e-6,
                "the map did not follow the finger in x");
        assertEquals(wz, m.worldZAt(sx + 100, sy + 60, W, H), 1e-6,
                "the map did not follow the finger in z");
    }

    @Test
    @DisplayName("zoom is clamped at both ends")
    void zoomIsBounded() {
        MapView m = centred();
        for (int i = 0; i < 100; i++) { m.zoomBy(2.0); }
        assertEquals(MapView.MIN_SPAN_M, m.spanM, 1e-9,
                "zoomed in past the limit");
        for (int i = 0; i < 100; i++) { m.zoomBy(0.5); }
        assertEquals(MapView.MAX_SPAN_M, m.spanM, 1e-9,
                "zoomed out past the limit");
        m.setSpan(Double.NaN);
        assertTrue(Double.isFinite(m.spanM), "NaN got into the zoom");
        m.zoomAbout(0, 10, 10, W, H);
        assertTrue(Double.isFinite(m.spanM), "a zero zoom factor broke the span");
    }

    @Test
    @DisplayName("the view cannot be dragged away from the island")
    void centreIsClamped() {
        MapView m = centred();
        for (int i = 0; i < 400; i++) { m.panPixels(900, 900, W, H); }
        assertTrue(m.centreX > -40000 && m.centreX < 40000,
                "panned off the world in x: " + m.centreX);
        assertTrue(m.centreZ > -50000 && m.centreZ < 50000,
                "panned off the world in z: " + m.centreZ);
    }

    @Test
    @DisplayName("a wider view asks for fewer road classes")
    void roadDetailFallsOffWithZoom() {
        int close = Minimap.classLimitFor(0.5);
        int mid = Minimap.classLimitFor(8);
        int far = Minimap.classLimitFor(60);
        assertTrue(close > mid, "a close view must show more than a mid one");
        assertTrue(mid > far, "a mid view must show more than the whole city");
        assertTrue(far >= 0, "something must still be drawn when zoomed out");
    }

    @Test
    @DisplayName("a destination is only usable where the profile can go")
    void targetSuitsTheProfile() {
        MapView m = centred();
        m.hasTarget = true;
        m.targetInBounds = true;
        m.targetInWater = false;
        assertTrue(m.targetSuits(ControlProfile.ON_FOOT));
        assertTrue(m.targetSuits(ControlProfile.CAR));
        assertFalse(m.targetSuits(ControlProfile.BOAT),
                "a boat was allowed to teleport onto dry land");

        m.targetInWater = true;
        assertTrue(m.targetSuits(ControlProfile.BOAT));
        assertTrue(m.targetSuits(ControlProfile.HELICOPTER));
        assertFalse(m.targetSuits(ControlProfile.ON_FOOT),
                "a pedestrian was allowed to teleport into the sea");

        m.targetInBounds = false;
        assertFalse(m.targetSuits(ControlProfile.CAR),
                "a destination outside the world was accepted");
    }

    @Test
    @DisplayName("closing the map forgets the pending destination")
    void closingClearsTheTarget() {
        MapView m = centred();
        m.hasTarget = true;
        m.targetInBounds = true;
        m.close();
        assertFalse(m.open);
        assertFalse(m.hasTarget,
                "the old destination survived closing the map and would be"
                        + " used by the next confirm");
    }

    // ----------------------------------------------------- with the world

    @Test
    @DisplayName("a tap near a road snaps onto it")
    void pickSnapsToRoad() {
        Assumptions.assumeTrue(WorldFixture.present(), "compiled world absent");
        WorldData w = WorldFixture.world();
        Minimap mini = new Minimap(w);
        mini.ensureIndex();
        MapView m = new MapView();
        m.setWorldBounds(w);

        // Start from a known road vertex, step off it, and check the pick
        // comes back to tarmac.
        int tested = 0;
        int snapped = 0;
        for (int n = 0; n < 40; n++) {
            int node = (n * 7919) % (w.graphNodeXZ.length / 2);
            double rx = w.graphNodeXZ[node * 2];
            double rz = w.graphNodeXZ[node * 2 + 1];
            m.openAt(rx, rz);
            if (!m.pickWorld(rx + 18, rz + 18, mini, w)) { continue; }
            tested++;
            if (m.targetOnRoad) {
                snapped++;
                double[] back = mini.nearestRoad(m.targetX, m.targetZ, 5);
                assertTrue(back != null,
                        "the snapped point is not on a road after all");
            }
        }
        assertTrue(tested > 20, "only " + tested + " picks landed in bounds");
        assertTrue(snapped > tested * 0.8,
                "only " + snapped + " of " + tested
                        + " taps near a road snapped onto one");
    }

    @Test
    @DisplayName("the window query returns what is in the window, once each")
    void rectQueryIsWindowedAndUnique() {
        Assumptions.assumeTrue(WorldFixture.present(), "compiled world absent");
        WorldData w = WorldFixture.world();
        Minimap mini = new Minimap(w);

        double cx = (w.minWorldX() + w.maxWorldX()) * 0.5;
        double cz = (w.minWorldZ() + w.maxWorldZ()) * 0.5;
        double half = 1500;
        double[] out = new double[20000 * 5];
        int n = mini.collectInRect(cx - half, cz - half, cx + half, cz + half,
                9, out, 20000);
        assertTrue(n > 0, "the middle of the city has no roads in it");

        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) {
            double ax = out[i * 5];
            double az = out[i * 5 + 1];
            double bx = out[i * 5 + 2];
            double bz = out[i * 5 + 3];
            // Every returned segment must touch the window.
            assertTrue(Math.max(ax, bx) >= cx - half
                            && Math.min(ax, bx) <= cx + half
                            && Math.max(az, bz) >= cz - half
                            && Math.min(az, bz) <= cz + half,
                    "a segment outside the window was returned");
            assertTrue(seen.add(ax + "," + az + "," + bx + "," + bz),
                    "an edge straddling a cell boundary was returned twice");
        }

        // And the class filter must actually reduce the count.
        int trunkOnly = mini.collectInRect(cx - half, cz - half,
                cx + half, cz + half, 1, out, 20000);
        assertTrue(trunkOnly < n,
                "filtering to trunk roads returned as much as everything");
    }

    @Test
    @DisplayName("the budget is respected rather than overrunning the array")
    void rectQueryHonoursItsBudget() {
        Assumptions.assumeTrue(WorldFixture.present(), "compiled world absent");
        WorldData w = WorldFixture.world();
        Minimap mini = new Minimap(w);
        double[] small = new double[50 * 5];
        int n = mini.collectInRect(w.minWorldX(), w.minWorldZ(),
                w.maxWorldX(), w.maxWorldZ(), 9, small, 50);
        assertTrue(n <= 50, "wrote " + n + " segments into room for 50");
    }

    @Test
    @DisplayName("the whole island fits in the widest zoom")
    void theWholeIslandFits() {
        Assumptions.assumeTrue(WorldFixture.present(), "compiled world absent");
        WorldData w = WorldFixture.world();
        MapView m = new MapView();
        m.setWorldBounds(w);
        m.openAt((w.minWorldX() + w.maxWorldX()) * 0.5,
                (w.minWorldZ() + w.maxWorldZ()) * 0.5);
        m.setSpan(MapView.MAX_SPAN_M);
        double[] vis = m.visibleBounds(W, H);
        assertTrue(vis[0] <= w.minWorldX() && vis[2] >= w.maxWorldX(),
                "the widest zoom does not reach Vasai Creek to Navy Nagar in x");
        assertTrue(vis[1] <= w.minWorldZ() && vis[3] >= w.maxWorldZ(),
                "the widest zoom does not span the island in z");
    }

    // ---- how finely the coastline is sampled ----------------------------

    @Test
    @DisplayName("zoomed out, the map still draws a coastline and not a blob")
    void theCoastlineSurvivesZoomingOut() {
        // A fixed 72 columns made a map cell 1.25 km across at the widest
        // zoom - wider than most of the creeks, bays and inlets on this
        // coast, so they vanished and Salsette was drawn as one solid
        // lump. Rather than naming a particular bay, this counts the
        // land-water transitions the map would actually draw across the
        // whole island at both resolutions. More transitions is more
        // coastline.
        WorldData w = WorldFixture.world();
        MapView v = new MapView();
        v.spanM = MapView.MAX_SPAN_M;
        int viewW = 1080, viewH = 1920;
        double mpp = 1.0 / v.scale(viewW, viewH);
        int cols = MapView.landCols(viewW, mpp);
        assertTrue(cols > 72, "the map is still sampled at " + cols
                + " columns, which is what it was before");

        double cellM = viewW * mpp / cols;
        assertTrue(cellM < 400,
                "a map cell is " + cellM + " m across at the widest zoom");

        int coarse = coastlineCrossings(w, 1250.0);
        int fine = coastlineCrossings(w, cellM);
        assertTrue(fine > coarse * 1.5,
                "at " + cellM + " m a draw finds " + fine + " coastline"
                        + " crossings against " + coarse + " at the old"
                        + " 1.25 km. The coastline is no sharper than it"
                        + " was. (Cells went from 1250 m to " + cellM
                        + " m, so the gain is real but sub-linear: a"
                        + " finer sample finds more of the same coast,"
                        + " not proportionally more coast.)");
    }

    /** Land-water transitions along east-west lines across the island. */
    private static int coastlineCrossings(WorldData w, double stepM) {
        int crossings = 0;
        for (double z = -30000; z < 30000; z += 2000) {
            Boolean prev = null;
            for (double x = -24000; x < 24000; x += stepM) {
                if (!w.inBounds(x, z)) { continue; }
                boolean land = w.isLand(x, z);
                if (prev != null && land != prev) { crossings++; }
                prev = land;
            }
        }
        return crossings;
    }

    @Test
    @DisplayName("sampling the coastline stays cheap at every zoom")
    void theSamplingIsBounded() {
        MapView v = new MapView();
        int viewW = 1440, viewH = 2560;
        for (double span = MapView.MIN_SPAN_M; span <= MapView.MAX_SPAN_M;
                span *= 1.35) {
            v.spanM = span;
            double mpp = 1.0 / v.scale(viewW, viewH);
            int cols = MapView.landCols(viewW, mpp);
            int rows = (int) (cols * (double) viewH / viewW);
            assertTrue((long) cols * rows <= 200000,
                    "at a " + span + " m span the map would take "
                            + ((long) cols * rows) + " mask lookups a draw");
            assertTrue(cols >= MapView.LAND_COLS_MIN,
                    "at a " + span + " m span the map dropped to " + cols
                            + " columns");
        }
    }

    @Test
    @DisplayName("close up, a cell is finer than the land mask itself")
    void closeUpIsFinerThanTheData() {
        MapView v = new MapView();
        v.spanM = 400;
        int viewW = 1080;
        double mpp = 1.0 / v.scale(viewW, 1920);
        double cellM = viewW * mpp / MapView.landCols(viewW, mpp);
        assertTrue(cellM <= 20.0,
                "zoomed right in the map samples every " + cellM + " m,"
                        + " coarser than the 20 m mask it is reading");
    }
}
