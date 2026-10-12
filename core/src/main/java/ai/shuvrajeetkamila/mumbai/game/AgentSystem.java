package ai.shuvrajeetkamila.mumbai.game;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.physics.CollisionWorld;
import ai.shuvrajeetkamila.mumbai.world.LandUse;
import ai.shuvrajeetkamila.mumbai.world.WorldData;

/**
 * Ambient life: road traffic and pedestrians.
 *
 * <p>Honest scope note: these background agents are <em>not</em> full rigid bodies. They
 * follow the road graph with a car-following model (desired speed, gap keeping, junction
 * slow-down) and are sampled onto the terrain height. Only the vehicle the player is
 * actually controlling runs the full force-based simulation; spending a per-wheel
 * suspension solve on ninety background taxis would cost more than the entire renderer.
 *
 * <p>Traffic keeps <b>left</b>, as it does in India: agents travel on the left side of the
 * centre line and overtake on the right.
 */
public final class AgentSystem {

    /** Stride of the packed vehicle instance array. */
    public static final int VEHICLE_STRIDE = 8;
    /** Stride of the packed pedestrian instance array. */
    public static final int PED_STRIDE = 7;

    private static final float SPAWN_RADIUS = 420f;
    private static final float DESPAWN_RADIUS = 620f;

    private final WorldData world;
    private final CollisionWorld collision;

    private int trafficCap = 90;
    private int pedCap = 70;

    // Vehicle agents -------------------------------------------------------------------
    private int[] vRoad;
    private int[] vSeg;
    private float[] vT;
    private float[] vDir;
    private float[] vSpeed;
    private float[] vDesired;
    private int[] vType;
    private float[] vLane;
    private double[] vX;
    private double[] vZ;
    private float[] vY;
    private float[] vYaw;
    private int vCount;

    // Pedestrian agents ----------------------------------------------------------------
    private double[] pX;
    private double[] pZ;
    private float[] pY;
    private float[] pYaw;
    private float[] pSpeed;
    private float[] pPhase;
    private int[] pType;
    private float[] pTimer;
    private int pCount;

    private float[] vehicleInstances = new float[0];
    private float[] pedInstanceData = new float[0];
    private int rng = 0x5eed;
    private float spawnAccum;

    public AgentSystem(WorldData world, CollisionWorld collision) {
        this.world = world;
        this.collision = collision;
        allocate(256, 256);
    }

    private void allocate(int vehicles, int peds) {
        vRoad = new int[vehicles];
        vSeg = new int[vehicles];
        vT = new float[vehicles];
        vDir = new float[vehicles];
        vSpeed = new float[vehicles];
        vDesired = new float[vehicles];
        vType = new int[vehicles];
        vLane = new float[vehicles];
        vX = new double[vehicles];
        vZ = new double[vehicles];
        vY = new float[vehicles];
        vYaw = new float[vehicles];

        pX = new double[peds];
        pZ = new double[peds];
        pY = new float[peds];
        pYaw = new float[peds];
        pSpeed = new float[peds];
        pPhase = new float[peds];
        pType = new int[peds];
        pTimer = new float[peds];
    }

    public void setCaps(int traffic, int pedestrians) {
        trafficCap = Mth.clamp(traffic, 0, vRoad.length);
        pedCap = Mth.clamp(pedestrians, 0, pX.length);
        while (vCount > trafficCap) {
            removeVehicle(vCount - 1);
        }
        while (pCount > pedCap) {
            removePed(pCount - 1);
        }
    }

    public int vehicleCount() {
        return vCount;
    }

    public int pedestrianCount() {
        return pCount;
    }

    private float rand() {
        rng = rng * 1103515245 + 12345;
        return ((rng >>> 8) & 0xFFFFFF) / (float) 0x1000000;
    }

    public void update(float dt, double focusX, double focusZ, QualityProfile profile) {
        spawnAccum += dt;
        if (spawnAccum > 0.25f) {
            spawnAccum = 0f;
            maintainPopulation(focusX, focusZ);
        }
        updateVehicles(dt, focusX, focusZ);
        updatePedestrians(dt, focusX, focusZ);
    }

    // --- population ------------------------------------------------------------------

    private void maintainPopulation(double fx, double fz) {
        // Remove anything that has fallen too far behind.
        for (int i = vCount - 1; i >= 0; i--) {
            double dx = vX[i] - fx;
            double dz = vZ[i] - fz;
            if (dx * dx + dz * dz > DESPAWN_RADIUS * DESPAWN_RADIUS) {
                removeVehicle(i);
            }
        }
        for (int i = pCount - 1; i >= 0; i--) {
            double dx = pX[i] - fx;
            double dz = pZ[i] - fz;
            if (dx * dx + dz * dz > (DESPAWN_RADIUS * 0.6) * (DESPAWN_RADIUS * 0.6)) {
                removePed(i);
            }
        }

        // Density follows the land use under the camera: the CBD is bumper to bumper, the
        // national park is empty.
        float density = collision.urbanDensity(fx, fz);
        int wantVehicles = Math.round(trafficCap * Mth.clamp(0.15f + density * 1.1f, 0.1f, 1f));
        int wantPeds = Math.round(pedCap * Mth.clamp(0.1f + density * 1.2f, 0.05f, 1f));

        int attempts = 0;
        while (vCount < wantVehicles && attempts++ < 24) {
            spawnVehicle(fx, fz);
        }
        attempts = 0;
        while (pCount < wantPeds && attempts++ < 24) {
            spawnPedestrian(fx, fz);
        }
    }

    private void spawnVehicle(double fx, double fz) {
        if (world.roads.isEmpty() || vCount >= vRoad.length) {
            return;
        }
        // Pick a road segment near the focus.
        int tries = 12;
        while (tries-- > 0) {
            int ri = (int) (rand() * world.roads.size());
            if (ri >= world.roads.size()) {
                continue;
            }
            WorldData.Polyline p = world.roads.get(ri);
            if (p.pointCount() < 2) {
                continue;
            }
            int si = 1 + (int) (rand() * (p.pointCount() - 1));
            if (si >= p.pointCount()) {
                si = p.pointCount() - 1;
            }
            double mx = (p.x(si - 1) + p.x(si)) * 0.5;
            double mz = (p.z(si - 1) + p.z(si)) * 0.5;
            double d2 = (mx - fx) * (mx - fx) + (mz - fz) * (mz - fz);
            if (d2 > SPAWN_RADIUS * SPAWN_RADIUS || d2 < 900) {
                continue;
            }
            int i = vCount++;
            vRoad[i] = ri;
            vSeg[i] = si;
            vT[i] = rand();
            vDir[i] = rand() < 0.5f ? 1f : -1f;
            float r = rand();
            // Mumbai's road mix: lots of taxis, autos and two wheelers.
            vType[i] = r < 0.22f ? 0 : r < 0.42f ? 1 : r < 0.60f ? 2 : r < 0.74f ? 3
                    : r < 0.84f ? 4 : r < 0.92f ? 5 : r < 0.97f ? 6 : 7;
            float lanes = Math.max(1, p.lanes);
            float laneWidth = 3.3f;
            int laneIdx = (int) (rand() * Math.max(1, lanes / 2f));
            // Left-hand traffic: positive offset is the left of the travel direction.
            vLane[i] = (0.6f + laneIdx) * laneWidth;
            vDesired[i] = desiredSpeed(p.cls, vType[i]);
            vSpeed[i] = vDesired[i] * (0.6f + rand() * 0.4f);
            evaluateVehicle(i);
            return;
        }
    }

    private static float desiredSpeed(int roadClass, int type) {
        float base;
        switch (roadClass) {
            case 0: base = 22f; break;        // expressway / sea link
            case 1: base = 15f; break;        // arterial
            case 2: base = 11f; break;        // connector
            default: base = 8f; break;        // local street
        }
        switch (type) {
            case 1: return base * 0.72f;      // auto rickshaw
            case 4: return base * 0.65f;      // bus
            case 5: return base * 0.6f;       // truck
            case 6: return base * 1.08f;      // motorcycle weaving
            default: return base;
        }
    }

    private void removeVehicle(int i) {
        int last = --vCount;
        if (i != last) {
            vRoad[i] = vRoad[last];
            vSeg[i] = vSeg[last];
            vT[i] = vT[last];
            vDir[i] = vDir[last];
            vSpeed[i] = vSpeed[last];
            vDesired[i] = vDesired[last];
            vType[i] = vType[last];
            vLane[i] = vLane[last];
            vX[i] = vX[last];
            vZ[i] = vZ[last];
            vY[i] = vY[last];
            vYaw[i] = vYaw[last];
        }
    }

    private void spawnPedestrian(double fx, double fz) {
        if (pCount >= pX.length) {
            return;
        }
        int tries = 10;
        while (tries-- > 0) {
            double ang = rand() * Math.PI * 2.0;
            double r = 30 + rand() * 180;
            double x = fx + Math.cos(ang) * r;
            double z = fz + Math.sin(ang) * r;
            int lu = world.landUseAt(x, z);
            if (LandUse.isWater(lu) || !LandUse.isUrban(lu)) {
                continue;
            }
            double droad = world.distanceToRoad(x, z);
            if (droad > 22) {
                continue;        // keep people on pavements and plazas
            }
            int i = pCount++;
            pX[i] = x;
            pZ[i] = z;
            pY[i] = world.heightAt(x, z);
            pYaw[i] = rand() * Mth.TAU;
            pSpeed[i] = 0.9f + rand() * 0.75f;
            pPhase[i] = rand() * Mth.TAU;
            pType[i] = (int) (rand() * 6);
            pTimer[i] = 1f + rand() * 4f;
            return;
        }
    }

    private void removePed(int i) {
        int last = --pCount;
        if (i != last) {
            pX[i] = pX[last];
            pZ[i] = pZ[last];
            pY[i] = pY[last];
            pYaw[i] = pYaw[last];
            pSpeed[i] = pSpeed[last];
            pPhase[i] = pPhase[last];
            pType[i] = pType[last];
            pTimer[i] = pTimer[last];
        }
    }

    // --- simulation --------------------------------------------------------------------

    private void updateVehicles(float dt, double fx, double fz) {
        for (int i = 0; i < vCount; i++) {
            WorldData.Polyline p = world.roads.get(vRoad[i]);
            if (p.pointCount() < 2) {
                removeVehicle(i--);
                continue;
            }
            int si = Mth.clamp(vSeg[i], 1, p.pointCount() - 1);
            double ax = p.x(si - 1), az = p.z(si - 1);
            double bx = p.x(si), bz = p.z(si);
            double segLen = Math.hypot(bx - ax, bz - az);
            if (segLen < 0.5) {
                advanceSegment(i, p);
                continue;
            }

            // Car following: slow for the agent ahead in the same lane on the same road.
            float gapLimit = vDesired[i];
            for (int j = 0; j < vCount; j++) {
                if (j == i || vRoad[j] != vRoad[i] || vDir[j] != vDir[i]) {
                    continue;
                }
                double dx = vX[j] - vX[i];
                double dz = vZ[j] - vZ[i];
                double fwdX = Math.sin(vYaw[i]);
                double fwdZ = -Math.cos(vYaw[i]);
                double along = dx * fwdX + dz * fwdZ;
                double lateral = Math.abs(dx * -fwdZ + dz * fwdX);
                if (along > 0 && along < 26 && lateral < 2.4) {
                    float safe = (float) Math.max(0, (along - 6.0) * 0.45);
                    gapLimit = Math.min(gapLimit, safe);
                }
            }

            float target = Math.min(vDesired[i], gapLimit);
            float accel = target > vSpeed[i] ? 2.4f : 5.0f;
            vSpeed[i] = Mth.approach(vSpeed[i], target, accel * dt);

            float advance = (float) (vSpeed[i] * dt / segLen);
            vT[i] += advance * vDir[i];
            if (vT[i] > 1f || vT[i] < 0f) {
                advanceSegment(i, p);
            } else {
                evaluateVehicle(i);
            }
        }
    }

    private void advanceSegment(int i, WorldData.Polyline p) {
        if (vDir[i] > 0) {
            vSeg[i]++;
            vT[i] = 0f;
            if (vSeg[i] >= p.pointCount()) {
                // End of the road: turn around rather than vanish in front of the player.
                vSeg[i] = p.pointCount() - 1;
                vDir[i] = -1f;
                vT[i] = 1f;
            }
        } else {
            vSeg[i]--;
            vT[i] = 1f;
            if (vSeg[i] < 1) {
                vSeg[i] = 1;
                vDir[i] = 1f;
                vT[i] = 0f;
            }
        }
        evaluateVehicle(i);
    }

    private void evaluateVehicle(int i) {
        WorldData.Polyline p = world.roads.get(vRoad[i]);
        int si = Mth.clamp(vSeg[i], 1, p.pointCount() - 1);
        double ax = p.x(si - 1), az = p.z(si - 1);
        double bx = p.x(si), bz = p.z(si);
        double t = Mth.clamp(vT[i], 0f, 1f);
        double cx = ax + (bx - ax) * t;
        double cz = az + (bz - az) * t;
        double dx = bx - ax;
        double dz = bz - az;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            len = 1;
        }
        dx /= len;
        dz /= len;
        if (vDir[i] < 0) {
            dx = -dx;
            dz = -dz;
        }
        // Left-hand traffic: offset to the left of the direction of travel.
        double leftX = dz;
        double leftZ = -dx;
        vX[i] = cx + leftX * vLane[i];
        vZ[i] = cz + leftZ * vLane[i];
        vYaw[i] = (float) Math.atan2(dx, -dz);
        float h = world.heightAt(vX[i], vZ[i]);
        if (p.layer > 0) {
            h += 9.5f;          // on the flyover deck
        }
        vY[i] = h;
    }

    private void updatePedestrians(float dt, double fx, double fz) {
        for (int i = 0; i < pCount; i++) {
            pTimer[i] -= dt;
            if (pTimer[i] <= 0f) {
                pTimer[i] = 2f + rand() * 6f;
                pYaw[i] = Mth.wrapPi(pYaw[i] + (rand() - 0.5f) * 2.2f);
            }
            float sx = (float) Math.sin(pYaw[i]);
            float sz = -(float) Math.cos(pYaw[i]);
            double nx = pX[i] + sx * pSpeed[i] * dt;
            double nz = pZ[i] + sz * pSpeed[i] * dt;
            int lu = world.landUseAt(nx, nz);
            if (LandUse.isWater(lu)) {
                pYaw[i] = Mth.wrapPi(pYaw[i] + Mth.PI * 0.6f);
                continue;
            }
            double droad = world.distanceToRoad(nx, nz);
            if (droad < 5.5 || droad > 26) {
                // Stay on the pavement band beside the carriageway.
                pYaw[i] = Mth.wrapPi(pYaw[i] + (rand() < 0.5f ? 1f : -1f) * 1.1f);
                continue;
            }
            pX[i] = nx;
            pZ[i] = nz;
            pY[i] = world.heightAt(nx, nz);
            pPhase[i] += pSpeed[i] * dt * 2.1f;
            if (pPhase[i] > Mth.TAU) {
                pPhase[i] -= Mth.TAU;
            }
        }
    }

    // --- render feeds -------------------------------------------------------------------

    /**
     * Packs vehicles for instanced drawing, relative to the floating origin.
     * Layout per instance: x, y, z, yaw, type, speed, lane, 0.
     */
    public float[] vehicleInstances(FloatingOrigin origin, int[] outCount) {
        if (vehicleInstances.length < vCount * VEHICLE_STRIDE) {
            vehicleInstances = new float[Math.max(64, vCount * 2) * VEHICLE_STRIDE];
        }
        for (int i = 0; i < vCount; i++) {
            int o = i * VEHICLE_STRIDE;
            vehicleInstances[o] = origin.localX(vX[i]);
            vehicleInstances[o + 1] = origin.localY(vY[i]);
            vehicleInstances[o + 2] = origin.localZ(vZ[i]);
            vehicleInstances[o + 3] = vYaw[i];
            vehicleInstances[o + 4] = vType[i];
            vehicleInstances[o + 5] = vSpeed[i];
            vehicleInstances[o + 6] = vLane[i];
            vehicleInstances[o + 7] = 0f;
        }
        outCount[0] = vCount;
        return vehicleInstances;
    }

    /** Layout per instance: x, y, z, yaw, type, stridePhase, speed. */
    public float[] pedestrianInstances(FloatingOrigin origin, int[] outCount) {
        if (pedInstanceData.length < pCount * PED_STRIDE) {
            pedInstanceData = new float[Math.max(64, pCount * 2) * PED_STRIDE];
        }
        for (int i = 0; i < pCount; i++) {
            int o = i * PED_STRIDE;
            pedInstanceData[o] = origin.localX(pX[i]);
            pedInstanceData[o + 1] = origin.localY(pY[i]);
            pedInstanceData[o + 2] = origin.localZ(pZ[i]);
            pedInstanceData[o + 3] = pYaw[i];
            pedInstanceData[o + 4] = pType[i];
            pedInstanceData[o + 5] = pPhase[i];
            pedInstanceData[o + 6] = pSpeed[i];
        }
        outCount[0] = pCount;
        return pedInstanceData;
    }

    public void clear() {
        vCount = 0;
        pCount = 0;
    }
}
