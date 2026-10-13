package ai.shuvrajeetkamila.mumbai.audio;

import ai.shuvrajeetkamila.mumbai.game.VehicleKind;
import ai.shuvrajeetkamila.mumbai.sim.Agents;

/**
 * Decides what you can actually hear.
 *
 * <p>A busy junction can have forty cars in it and the mixer carries a
 * dozen voices, so something has to choose. The rule is priority first
 * and distance second: a siren outranks a horn, a horn outranks an
 * engine, and within a class the closest wins. That matches what matters
 * to a player, who needs to know the police are behind them long before
 * they need to know a parked tempo is idling.
 *
 * <p>Kept separate from {@link AudioMixer} so the selection can be
 * tested without rendering a single sample.
 */
public final class AudioDirector {

    /** Beyond this a siren is inaudible and not worth a voice. */
    public static final double SIREN_RANGE = 260.0;
    /** Engines carry much less far. */
    public static final double ENGINE_RANGE = 95.0;
    public static final double HORN_RANGE = 150.0;

    /** Speed at which wind noise reaches full level, m/s. */
    public static final double WIND_FULL = 42.0;

    /**
     * Cylinder count per vehicle, which is most of what makes one engine
     * sound unlike another. A rickshaw's single cylinder is the reason it
     * putters; a double-decker's big six is the reason it drones.
     */
    public static int cylindersFor(VehicleKind k) {
        if (k == null) { return 4; }
        switch (k) {
            case AUTORICKSHAW: return 1;
            case SCOOTER: return 1;
            case TEMPO: return 3;
            case DOUBLE_DECKER:
            case CITY_BUS: return 6;
            case SUV: return 6;
            default: return 4;
        }
    }

    /**
     * Idle-to-redline rpm for a traffic car, which has no engine model of
     * its own; we infer a plausible crank speed from road speed so the
     * note still rises and falls with the traffic.
     */
    public static double inferredRpm(double speed, VehicleKind kind) {
        int cyl = cylindersFor(kind);
        double idle = cyl == 1 ? 1100 : cyl >= 6 ? 600 : 800;
        // Pretend a single ratio around 1:9 at the wheel. It is not a
        // gearbox, but it gives a believable spread across city speeds
        // and it never sits at a constant pitch.
        double rpm = idle + speed * (cyl >= 6 ? 62 : 95);
        return Math.min(rpm, cyl >= 6 ? 2600 : 4200);
    }

    /**
     * Closing speed along the line from source to listener, positive when
     * they are approaching. This is the only quantity Doppler needs.
     */
    public static double closingSpeed(double sx, double sz, double svx, double svz,
                                      double lx, double lz, double lvx, double lvz) {
        double dx = lx - sx, dz = lz - sz;
        double d = Math.hypot(dx, dz);
        if (d < 1e-6) { return 0; }
        dx /= d;
        dz /= d;
        // Source closing on the listener, minus the listener running away.
        return (svx * dx + svz * dz) - (lvx * dx + lvz * dz);
    }

    /**
     * Priority for a candidate. Higher is kept. Returns a negative number
     * for anything out of range, which the caller drops.
     */
    public static double priority(boolean siren, boolean horn, double distance) {
        double range = siren ? SIREN_RANGE : horn ? HORN_RANGE : ENGINE_RANGE;
        if (distance > range) { return -1; }
        double base = siren ? 3000 : horn ? 2000 : 1000;
        return base + (range - distance) / range * 900;
    }

    /**
     * Fills the mixer's source slots from the traffic around the player.
     *
     * @param wantedStars  police only run a siren when they are after you
     */
    public void update(AudioMixer mixer, Agents.Car[] cars,
                       double lx, double lz, double lvx, double lvz,
                       double heading, int wantedStars) {
        mixer.listenerX = lx;
        mixer.listenerZ = lz;
        mixer.listenerHeading = heading;

        AudioMixer.Source[] slots = mixer.sources();
        for (AudioMixer.Source s : slots) { s.active = false; }

        if (cars == null) { return; }

        // Insertion sort into the slot array by priority. With a dozen
        // slots this beats allocating and sorting a list every frame, and
        // the audio path should not be making garbage.
        int used = 0;
        double[] prio = new double[slots.length];

        for (Agents.Car c : cars) {
            if (c == null || !c.active) { continue; }
            double d = Math.hypot(c.x - lx, c.z - lz);
            boolean siren = c.police && wantedStars > 0;
            boolean horn = c.hornTimer > 0;
            double p = priority(siren, horn, d);
            if (p < 0) { continue; }

            int at;
            if (used < slots.length) {
                at = used++;
            } else {
                // Find the weakest slot and take it if we beat it.
                int worst = 0;
                for (int i = 1; i < used; i++) {
                    if (prio[i] < prio[worst]) { worst = i; }
                }
                if (prio[worst] >= p) { continue; }
                at = worst;
            }
            prio[at] = p;
            AudioMixer.Source s = slots[at];
            int kindNow = siren ? AudioMixer.KIND_SIREN
                    : horn ? AudioMixer.KIND_HORN : AudioMixer.KIND_ENGINE;
            // A slot that changes job must restart its phase, or a siren
            // inherits a horn's half-cycle and clicks.
            if (s.kind != kindNow) { s.phase = 0; s.phase2 = 0; }
            s.active = true;
            s.kind = kindNow;
            s.x = c.x;
            s.z = c.z;
            VehicleKind kind = kindOf(c.kindOrdinal);
            s.rpm = inferredRpm(Math.abs(c.speed), kind);
            s.load = Math.min(1.0, Math.abs(c.speed) / 18.0);
            double svx = Math.sin(c.heading) * c.speed;
            double svz = -Math.cos(c.heading) * c.speed;
            s.closing = closingSpeed(c.x, c.z, svx, svz, lx, lz, lvx, lvz);
        }
    }

    private static VehicleKind kindOf(int ordinal) {
        VehicleKind[] all = VehicleKind.values();
        if (ordinal < 0 || ordinal >= all.length) { return VehicleKind.SEDAN; }
        return all[ordinal];
    }

    /** Wind level for a speed, 0..1. Grows with the square, like drag. */
    public static double windFor(double speed) {
        double t = Math.min(1.0, Math.max(0, speed) / WIND_FULL);
        return t * t;
    }
}
