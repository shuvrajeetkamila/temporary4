package ai.shuvrajeetkamila.mumbai.physics;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.world.LandUse;
import ai.shuvrajeetkamila.mumbai.world.WaterModel;
import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.WorldStreamer;

/**
 * The query surface every physical system shares: terrain, water and the static boxes
 * produced by the streamer.
 *
 * <p>Ground contact is analytic against the height field (cheap, always available even
 * before a tile finishes streaming) while buildings, bridge decks and walls come from the
 * per-tile axis aligned boxes. This keeps collision correct at 300 km/h in an aeroplane
 * where a mesh-based broadphase would stall.
 */
public final class CollisionWorld {

    private static final int MAX_BOXES = 512;

    private final WorldData world;
    private final WorldStreamer streamer;
    private final WaterModel water;
    private final Atmosphere atmosphere;

    private final float[] boxes = new float[MAX_BOXES * 6];
    private int boxCount;
    private double boxCentreX = Double.NaN;
    private double boxCentreZ = Double.NaN;
    private final float[] tmp = new float[3];

    public CollisionWorld(WorldData world, WorldStreamer streamer,
                          WaterModel water, Atmosphere atmosphere) {
        this.world = world;
        this.streamer = streamer;
        this.water = water;
        this.atmosphere = atmosphere;
    }

    public WorldData world() {
        return world;
    }

    public WaterModel water() {
        return water;
    }

    public Atmosphere atmosphere() {
        return atmosphere;
    }

    /** Refreshes the local box cache; call once per physics frame per active actor. */
    public void refreshBoxes(double x, double z, float radius) {
        if (streamer == null) {
            boxCount = 0;
            return;
        }
        double dx = x - boxCentreX;
        double dz = z - boxCentreZ;
        if (!Double.isNaN(boxCentreX) && dx * dx + dz * dz < 16.0) {
            return;       // still fresh
        }
        boxCount = streamer.collectColliders(x, z, radius, boxes, MAX_BOXES) / 6;
        boxCentreX = x;
        boxCentreZ = z;
    }

    public void invalidateBoxes() {
        boxCentreX = Double.NaN;
        boxCount = 0;
    }

    public int boxCount() {
        return boxCount;
    }

    /** Terrain surface height (no water, no buildings). */
    public float terrainHeight(double x, double z) {
        return world.heightAt(x, z);
    }

    public void terrainNormal(double x, double z, float[] out) {
        world.normalAt(x, z, out);
    }

    public int landUse(double x, double z) {
        return world.landUseAt(x, z);
    }

    public boolean isWater(double x, double z) {
        return LandUse.isWater(world.landUseAt(x, z));
    }

    /** Water surface height at a point, or {@link Float#NEGATIVE_INFINITY} on dry land. */
    public float waterSurface(double x, double z) {
        int lu = world.landUseAt(x, z);
        if (!LandUse.isWater(lu)) {
            return Float.NEGATIVE_INFINITY;
        }
        return water.surfaceHeight(x, z, WaterModel.classOf(lu), world.heightAt(x, z));
    }

    /** Depth of water at a point (0 on land). */
    public float waterDepth(double x, double z) {
        float s = waterSurface(x, z);
        if (s == Float.NEGATIVE_INFINITY) {
            return 0f;
        }
        return Math.max(0f, s - terrainHeight(x, z));
    }

    /**
     * Highest supporting surface at or below {@code yHint + tolerance}: terrain, or the top
     * of a building/deck box. Returns terrain height when nothing else qualifies.
     */
    public float supportHeight(double x, double z, double yHint, float tolerance) {
        float best = terrainHeight(x, z);
        float ceiling = (float) (yHint + tolerance);
        for (int i = 0; i < boxCount; i++) {
            int o = i * 6;
            if (x < boxes[o] || x > boxes[o + 3] || z < boxes[o + 2] || z > boxes[o + 5]) {
                continue;
            }
            float top = boxes[o + 4];
            if (top > best && top <= ceiling) {
                best = top;
            }
        }
        return best;
    }

    /** Surface normal of whatever supports a point (flat on top of boxes). */
    public void supportNormal(double x, double z, double yHint, float[] out) {
        float terrain = terrainHeight(x, z);
        float support = supportHeight(x, z, yHint, 1.2f);
        if (support > terrain + 0.05f) {
            out[0] = 0f;
            out[1] = 1f;
            out[2] = 0f;
            return;
        }
        world.normalAt(x, z, out);
    }

    /** Tyre/foot grip multiplier: surface material scaled by rain. */
    public float grip(double x, double z) {
        float base = LandUse.gripScale(world.landUseAt(x, z));
        float onRoad = world.distanceToRoad(x, z) < 9.0 ? 1.0f : 0.78f;
        float wet = 1f - 0.34f * Mth.clamp01(atmosphere.rain);
        return base * onRoad * wet;
    }

    public boolean onRoad(double x, double z) {
        return world.distanceToRoad(x, z) < 10.0;
    }

    /**
     * Pushes a vertical capsule out of the static boxes. Returns true when it moved.
     * The capsule spans [y, y + height] with the given radius.
     */
    public boolean resolveCapsule(double[] pos, float radius, float height, float stepUp) {
        boolean moved = false;
        double x = pos[0];
        double y = pos[1];
        double z = pos[2];
        for (int pass = 0; pass < 3; pass++) {
            boolean any = false;
            for (int i = 0; i < boxCount; i++) {
                int o = i * 6;
                float bx0 = boxes[o], by0 = boxes[o + 1], bz0 = boxes[o + 2];
                float bx1 = boxes[o + 3], by1 = boxes[o + 4], bz1 = boxes[o + 5];
                if (y + height < by0 || y > by1) {
                    continue;
                }
                if (by1 - y <= stepUp) {
                    continue;           // low enough to walk/drive over
                }
                double cx = Mth.clamp(x, bx0, bx1);
                double cz = Mth.clamp(z, bz0, bz1);
                double dx = x - cx;
                double dz = z - cz;
                double d2 = dx * dx + dz * dz;
                if (d2 >= (double) radius * radius) {
                    continue;
                }
                double d = Math.sqrt(d2);
                if (d < 1e-6) {
                    // Centre inside the box: escape along the shallowest face.
                    double left = x - bx0, right = bx1 - x;
                    double front = z - bz0, back = bz1 - z;
                    double m = Math.min(Math.min(left, right), Math.min(front, back));
                    if (m == left) {
                        x = bx0 - radius;
                    } else if (m == right) {
                        x = bx1 + radius;
                    } else if (m == front) {
                        z = bz0 - radius;
                    } else {
                        z = bz1 + radius;
                    }
                } else {
                    double push = (radius - d) / d;
                    x += dx * push;
                    z += dz * push;
                }
                any = true;
                moved = true;
            }
            if (!any) {
                break;
            }
        }
        pos[0] = x;
        pos[2] = z;
        return moved;
    }

    /**
     * Raycast down from a point; returns the hit height or
     * {@link Float#NEGATIVE_INFINITY} when nothing is below.
     */
    public float rayDown(double x, double y, double z, float maxDistance) {
        float h = supportHeight(x, z, y, 0.0f);
        if (y - h > maxDistance) {
            return Float.NEGATIVE_INFINITY;
        }
        return h;
    }

    /** True when a sphere overlaps any static box (used by aircraft hull impact). */
    public boolean sphereHitsSolid(double x, double y, double z, float radius) {
        for (int i = 0; i < boxCount; i++) {
            int o = i * 6;
            double cx = Mth.clamp(x, boxes[o], boxes[o + 3]);
            double cy = Mth.clamp(y, boxes[o + 1], boxes[o + 4]);
            double cz = Mth.clamp(z, boxes[o + 2], boxes[o + 5]);
            double dx = x - cx, dy = y - cy, dz = z - cz;
            if (dx * dx + dy * dy + dz * dz < (double) radius * radius) {
                return true;
            }
        }
        return false;
    }

    /** Local building density 0..1, used for wind shadow and traffic density. */
    public float urbanDensity(double x, double z) {
        return Mth.clamp01(LandUse.buildingDensity(world.landUseAt(x, z)));
    }

    public void windAt(double x, double y, double z, float[] out) {
        atmosphere.windAt(x, y, z, out);
        float shelter = 1f - 0.55f * urbanDensity(x, z)
                * Mth.clamp01(1f - (float) (y - terrainHeight(x, z)) / 90f);
        out[0] *= shelter;
        out[1] *= shelter;
        out[2] *= shelter;
    }

    float[] scratch() {
        return tmp;
    }
}
