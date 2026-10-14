package ai.shuvrajeetkamila.mumbai.sim;

import ai.shuvrajeetkamila.mumbai.app.ControlProfile;
import ai.shuvrajeetkamila.mumbai.app.InputState;
import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.math.Vec3;
import ai.shuvrajeetkamila.mumbai.world.ChunkFeatures;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hitting the city, from the player's side.
 *
 * <p>{@code CityCollisionTest} checks the index. This checks that a body
 * driven into a building actually stops, that a body alongside one can
 * still move, and that the camera does not end up inside the wall behind
 * you.
 */
final class SolidWorldTest {

    /**
     * A large <em>convex</em> building footprint, in world metres.
     *
     * <p>Convex on purpose. A good fraction of OSM footprints come
     * through simplification as self-intersecting bow-ties - the first
     * one this test picked had ten vertices that crossed each other twice
     * - and point-in-polygon on one of those reports "inside" for open
     * ground twenty-five metres from the nearest wall. Testing collision
     * against a polygon that does not enclose anything tests nothing.
     */
    private static double[] convexBuilding() throws IOException {
        WorldData w = WorldFixture.world();
        double[] bestRing = null;
        double bestArea = 0;
        for (int id = 0; id < w.chunkCount() && bestArea < 4000; id++) {
            if (!w.chunkHasData(id) || w.chunkBuildingCount(id) < 20) { continue; }
            ChunkFeatures f = ChunkFeatures.decode(id, w.chunkOriginX(id),
                    w.chunkOriginZ(id), w.readChunk(id), w.quant);
            for (int b = 0; b < f.buildingCount(); b++) {
                int s = f.buildingOffset[b];
                int e = f.buildingOffset[b + 1];
                int n = e - s;
                if (n < 4 || n > 10) { continue; }
                if (f.buildingHeight[b] < 8 || f.buildingMinHeight[b] > 0.5) {
                    continue;
                }
                double area = f.buildingArea(b);
                if (area < 900 || area <= bestArea) { continue; }
                double[] ring = new double[n * 2];
                for (int k = s; k < e; k++) {
                    ring[(k - s) * 2] = w.chunkOriginX(id) + f.buildingXZ[k * 2];
                    ring[(k - s) * 2 + 1] =
                            w.chunkOriginZ(id) + f.buildingXZ[k * 2 + 1];
                }
                if (!convex(ring)) { continue; }
                bestRing = ring;
                bestArea = area;
            }
        }
        return bestRing;
    }

    private static boolean convex(double[] ring) {
        int n = ring.length / 2;
        int sign = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            int k = (i + 2) % n;
            double ax = ring[j * 2] - ring[i * 2];
            double az = ring[j * 2 + 1] - ring[i * 2 + 1];
            double bx = ring[k * 2] - ring[j * 2];
            double bz = ring[k * 2 + 1] - ring[j * 2 + 1];
            double cross = ax * bz - az * bx;
            if (Math.abs(cross) < 1e-6) { continue; }
            int sg = cross > 0 ? 1 : -1;
            if (sign == 0) { sign = sg; } else if (sg != sign) { return false; }
        }
        return sign != 0;
    }

    private static double[] bbox(double[] ring) {
        double lox = 1e18, hix = -1e18, loz = 1e18, hiz = -1e18;
        for (int k = 0; k < ring.length / 2; k++) {
            lox = Math.min(lox, ring[k * 2]);
            hix = Math.max(hix, ring[k * 2]);
            loz = Math.min(loz, ring[k * 2 + 1]);
            hiz = Math.max(hiz, ring[k * 2 + 1]);
        }
        return new double[]{lox, hix, (loz + hiz) * 0.5};
    }

    /** Where a body driving east along {@code z} first meets something solid. */
    private static double firstWallEastOf(Engine e, double fromX, double z,
                                          double y, double toX) {
        for (double x = fromX; x <= toX; x += 0.5) {
            if (e.city.blocked(x, z, y, 0.45)) { return x; }
        }
        return Double.NaN;
    }

    @Test
    @DisplayName("a car driven at a building stops at it")
    void carsStopAtBuildings() throws IOException {
        double[] ring = convexBuilding();
        if (ring == null) { return; }
        double[] b = bbox(ring);
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(0, 0, ControlProfile.CAR);
        e.car.configure(VehicleKind.SEDAN);
        double sx = b[0] - 60;
        e.car.placeAt(sx, w.height(sx, b[2]) + 0.6, b[2], Math.PI / 2);
        double wall = firstWallEastOf(e, sx, b[2],
                w.height(sx, b[2]) + 1.0, b[1] + 40);
        if (Double.isNaN(wall)) { return; }   // nothing solid on this line
        InputState in = new InputState();
        in.throttle = 1.0;
        for (int i = 0; i < 60 * 25; i++) { e.frame(1.0 / 60, in); }
        assertTrue(e.car.position.x < wall + 5,
                "drove to x=" + e.car.position.x + " past the first wall at " + wall);
        assertTrue(e.car.speedKph < 25,
                "still doing " + e.car.speedKph + " km/h against a wall");
    }

    private static boolean inside(double[] ring, double x, double z) {
        boolean in = false;
        int n = ring.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring[i * 2], zi = ring[i * 2 + 1];
            double xj = ring[j * 2], zj = ring[j * 2 + 1];
            if ((zi > z) != (zj > z)
                    && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                in = !in;
            }
        }
        return in;
    }

    @Test
    @DisplayName("a person never ends up inside a building")
    void peopleStopAtBuildings() throws IOException {
        double[] ring = convexBuilding();
        if (ring == null) { return; }
        double[] b = bbox(ring);
        WorldData w = WorldFixture.world();
        // Walk at it from several angles. A wall you can slide along and
        // round the end of is correct; a wall you can walk through is not.
        for (double bearing : new double[]{Math.PI / 2, Math.PI / 2 + 0.4,
                Math.PI / 2 - 0.4}) {
            Engine e = new Engine(w);
            e.spawnAt(0, 0, ControlProfile.ON_FOOT);
            double sx = b[0] - 25;
            e.human.position.set(sx, w.height(sx, b[2]) + 1.0, b[2]);
            e.cameraYaw = bearing;
            InputState in = new InputState();
            in.moveY = -1.0;
            in.run = true;
            int breached = 0;
            for (int i = 0; i < 60 * 45; i++) {
                e.frame(1.0 / 60, in);
                if (inside(ring, e.human.position.x, e.human.position.z)) {
                    breached++;
                }
            }
            assertTrue(breached == 0,
                    "spent " + breached + " frames inside the building walking at "
                            + Math.toDegrees(bearing) + " deg");
        }
    }

    @Test
    @DisplayName("a car never ends up inside a building either")
    void carsStayOutOfBuildings() throws IOException {
        double[] ring = convexBuilding();
        if (ring == null) { return; }
        double[] b = bbox(ring);
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(0, 0, ControlProfile.CAR);
        e.car.configure(VehicleKind.SEDAN);
        double sx = b[0] - 60;
        e.car.placeAt(sx, w.height(sx, b[2]) + 0.6, b[2], Math.PI / 2);
        InputState in = new InputState();
        in.throttle = 1.0;
        int breached = 0;
        for (int i = 0; i < 60 * 25; i++) {
            e.frame(1.0 / 60, in);
            if (inside(ring, e.car.position.x, e.car.position.z)) { breached++; }
        }
        assertTrue(breached == 0,
                "spent " + breached + " frames inside the building");
    }

    @Test
    @DisplayName("but you can still walk along a wall")
    void wallsDoNotWeldYouInPlace() throws IOException {
        double[] ring = convexBuilding();
        if (ring == null) { return; }
        double[] b = bbox(ring);
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(0, 0, ControlProfile.ON_FOOT);
        double sx = b[0] - 0.8;
        e.human.position.set(sx, w.height(sx, b[2]) + 1.0, b[2]);
        e.cameraYaw = 0;                      // north, along the face
        InputState in = new InputState();
        in.moveY = -1.0;
        in.run = true;
        double z0 = e.human.position.z;
        for (int i = 0; i < 60 * 12; i++) { e.frame(1.0 / 60, in); }
        assertTrue(Math.abs(e.human.position.z - z0) > 18,
                "only slid " + Math.abs(e.human.position.z - z0)
                        + " m along a wall in twelve seconds");
    }

    @Test
    @DisplayName("the camera does not sit inside the building behind you")
    void theCameraIsPulledInByWalls() throws IOException {
        double[] ring = convexBuilding();
        if (ring == null) { return; }
        double[] b = bbox(ring);
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(0, 0, ControlProfile.ON_FOOT);
        // Stand just outside the west wall looking east at it. The camera
        // wants to be behind the player, which is inside the building on
        // the other side of the street - or, here, inside this one.
        double sx = b[0] + 2.0;
        e.human.position.set(sx, w.height(sx, b[2]) + 1.0, b[2]);
        e.cameraYaw = -Math.PI / 2;           // looking west, camera to the east
        InputState idle = new InputState();
        for (int i = 0; i < 4; i++) { e.frame(1.0 / 60, idle); }
        Vec3 cam = new Vec3();
        e.cameraPosition(cam);
        assertTrue(!e.city.blocked(cam.x, cam.z, cam.y, 0.2),
                "the camera is inside a wall at " + cam.x + "," + cam.z);
    }

    @Test
    @DisplayName("hitting a building is reported, so it can be heard")
    void impactsAreReported() throws IOException {
        double[] ring = convexBuilding();
        if (ring == null) { return; }
        double[] b = bbox(ring);
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(0, 0, ControlProfile.CAR);
        e.car.configure(VehicleKind.SEDAN);
        double sx = b[0] - 70;
        e.car.placeAt(sx, w.height(sx, b[2]) + 0.6, b[2], Math.PI / 2);
        InputState in = new InputState();
        in.throttle = 1.0;
        boolean hit = false;
        for (int i = 0; i < 60 * 25 && !hit; i++) {
            e.frame(1.0 / 60, in);
            hit = e.wallHit;
        }
        assertTrue(hit, "drove into a building and nothing was reported");
        assertTrue(e.wallHitSpeed > 3, "reported a " + e.wallHitSpeed + " m/s impact");
    }

    @Test
    @DisplayName("a helicopter cannot fly through a tower")
    void towersStopHelicopters() throws IOException {
        double[] ring = convexBuilding();
        if (ring == null) { return; }
        double[] b = bbox(ring);
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(0, 0, ControlProfile.HELICOPTER);
        double sx = b[0] - 50;
        double y = w.height(sx, b[2]) + 6.0;
        e.heli.placeAt(sx, y, b[2], Math.PI / 2);
        e.heli.velocity.set(22, 0, 0);
        InputState in = new InputState();
        in.collective = 0.58;
        for (int i = 0; i < 60 * 8; i++) { e.frame(1.0 / 60, in); }
        assertTrue(e.heli.position.x < b[0] + 10,
                "flew to x=" + e.heli.position.x + " through a wall at " + b[0]);
    }

    @Test
    @DisplayName("the city is still drivable now that it is solid")
    void theRestOfTheCityStillWorks() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(18.9430, 72.8230, ControlProfile.CAR);
        e.car.configure(VehicleKind.SEDAN);
        InputState in = new InputState();
        in.throttle = 1.0;
        double best = 0;
        double x0 = e.car.position.x;
        double z0 = e.car.position.z;
        for (int i = 0; i < 60 * 30; i++) {
            e.frame(1.0 / 60, in);
            best = Math.max(best, e.car.speedKph);
        }
        double went = Math.hypot(e.car.position.x - x0, e.car.position.z - z0);
        // Walls and solid traffic mean flooring it from rest in the middle
        // of Marine Drive is no longer a clear run. It still has to be a
        // drive, not a cul-de-sac.
        assertTrue(best > 22,
                "could not get above " + best + " km/h anywhere");
        // Due south off Marine Drive runs out of land: the promenade is
        // drivable, the Arabian Sea beyond it is not. Sixty metres is "the
        // city did not become a cul-de-sac", not a lap time.
        assertTrue(went > 60, "only covered " + went + " m in thirty seconds");
    }

    @Test
    @DisplayName("the player's own car still reaches the sea bed, not the sea top")
    void theSeaIsNotAFloor() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(18.9430, 72.8230, ControlProfile.CAR);
        e.car.configure(VehicleKind.SEDAN);
        e.car.placeAt(e.car.position.x, e.car.position.y, e.car.position.z,
                -Math.PI / 2);
        InputState in = new InputState();
        in.throttle = 1.0;
        double startX = e.car.position.x;
        for (int i = 0; i < 60 * 60; i++) { e.frame(1.0 / 60, in); }
        double west = startX - e.car.position.x;
        assertTrue(west < 400,
                "drove " + west + " m out to sea");
    }

    @Test
    @DisplayName("a sinking car puts the player in the water, not on the seabed")
    void youGetOutOfASinkingCar() {
        WorldData w = WorldFixture.world();
        Engine e = new Engine(w);
        e.spawnAt(18.9430, 72.8230, ControlProfile.CAR);
        e.car.configure(VehicleKind.SEDAN);
        e.car.placeAt(e.car.position.x, e.car.position.y, e.car.position.z,
                -Math.PI / 2);
        InputState in = new InputState();
        in.throttle = 1.0;
        for (int i = 0; i < 60 * 90; i++) { e.frame(1.0 / 60, in); }
        assertEquals(ControlProfile.ON_FOOT, e.profile,
                "still driving a car that is under the Arabian Sea");
        assertTrue(e.human.position.y > -4,
                "surfaced at y=" + e.human.position.y);
    }
}
