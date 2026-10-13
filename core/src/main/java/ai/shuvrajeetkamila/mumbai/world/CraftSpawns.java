package ai.shuvrajeetkamila.mumbai.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the boats, helicopters and aeroplanes of the island are tied up.
 *
 * <p>Every one of these is a real place on the map. Boats are moored at the
 * seaward end of a real pier or at a real ferry wharf; helicopters stand on
 * real helipads; aeroplanes are held at the threshold of a real runway,
 * lined up along its real centreline and with its real length in front of
 * them. The compiler decides which candidates survive, because only the
 * compiler owns the land mask: a mooring the tide has left dry and a
 * helipad on a cliff edge are both thrown away before they ever reach the
 * APK.
 *
 * <p>Before this existed the boat, helicopter and aeroplane models were
 * complete, tested, and unreachable: the only way into one was a debug
 * button that turned the player into an aircraft wherever they happened to
 * be standing. A flight model you cannot walk up to is not a flight model,
 * it is an unused class.
 */
public final class CraftSpawns {

    public static final int BOAT = 0;
    public static final int HELI = 1;
    public static final int PLANE = 2;

    /** One parked craft. Mutable, because the player moves it. */
    public static final class Spawn {
        /** Where the compiler put it. Never changes. */
        public final double homeX, homeZ, homeY, homeHeading;
        public final int kind;
        public final String name;

        /** Where it is now: its mooring until somebody takes it somewhere. */
        public double x, z, y, heading;
        /** True while the player is aboard, so it is not drawn twice. */
        public boolean taken;

        Spawn(double x, double z, double y, double heading, int kind, String name) {
            this.homeX = x; this.homeZ = z; this.homeY = y; this.homeHeading = heading;
            this.x = x; this.z = z; this.y = y; this.heading = heading;
            this.kind = kind;
            this.name = name;
        }

        public String label() {
            switch (kind) {
                case BOAT: return "Boat";
                case HELI: return "Helicopter";
                default: return "Aeroplane";
            }
        }
    }

    private final List<Spawn> all = new ArrayList<>();
    private final int[] counts = new int[3];

    private CraftSpawns() { }

    public static CraftSpawns empty() { return new CraftSpawns(); }

    public static CraftSpawns parse(String json) {
        CraftSpawns c = new CraftSpawns();
        for (String obj : Json.objectsOf(json, null)) {
            double x = Json.number(obj, "x", Double.NaN);
            double z = Json.number(obj, "z", Double.NaN);
            if (!Double.isFinite(x) || !Double.isFinite(z)) { continue; }
            double y = Json.number(obj, "y", 0);
            double h = Json.number(obj, "h", 0);
            int kind = kindOf(Json.string(obj, "kind", ""));
            if (kind < 0) { continue; }
            c.all.add(new Spawn(x, z, y, h, kind, Json.string(obj, "name", "")));
            c.counts[kind]++;
        }
        return c;
    }

    private static int kindOf(String s) {
        if ("BOAT".equals(s)) { return BOAT; }
        if ("HELI".equals(s)) { return HELI; }
        if ("PLANE".equals(s)) { return PLANE; }
        return -1;
    }

    public int size() { return all.size(); }

    public int countOf(int kind) {
        return kind >= 0 && kind < counts.length ? counts[kind] : 0;
    }

    public Spawn get(int i) { return all.get(i); }

    public List<Spawn> all() { return all; }

    /** Put every craft back where the compiler moored it. */
    public void reset() {
        for (int i = 0; i < all.size(); i++) {
            Spawn s = all.get(i);
            s.x = s.homeX;
            s.z = s.homeZ;
            s.y = s.homeY;
            s.heading = s.homeHeading;
            s.taken = false;
        }
    }

    /**
     * Index of the nearest craft the player could board, or -1.
     *
     * @param fx    unit forward vector of the player's gaze, x
     * @param fz    unit forward vector of the player's gaze, z
     * @param bias  how much being in front counts for, in metres
     */
    public int nearest(double px, double pz, double maxM, double fx, double fz,
                       double bias) {
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        double r2 = maxM * maxM;
        for (int i = 0; i < all.size(); i++) {
            Spawn s = all.get(i);
            if (s.taken) { continue; }
            double dx = s.x - px, dz = s.z - pz;
            double d2 = dx * dx + dz * dz;
            if (d2 > r2) { continue; }
            double d = Math.sqrt(Math.max(1e-9, d2));
            double facing = (dx / d) * fx + (dz / d) * fz;
            double score = d - facing * bias;
            if (score < bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    /** Distance to the nearest boardable craft, or a large number. */
    public double nearestDistance(double px, double pz) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < all.size(); i++) {
            Spawn s = all.get(i);
            if (s.taken) { continue; }
            double dx = s.x - px, dz = s.z - pz;
            double d2 = dx * dx + dz * dz;
            if (d2 < best) { best = d2; }
        }
        return Math.sqrt(best);
    }

    /** Nearest of one kind, however far: used to point the player at one. */
    public int nearestOfKind(double px, double pz, int kind) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < all.size(); i++) {
            Spawn s = all.get(i);
            if (s.kind != kind) { continue; }
            double dx = s.x - px, dz = s.z - pz;
            double d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }
}
