import ai.shuvrajeetkamila.mumbai.world.WorldData;
import ai.shuvrajeetkamila.mumbai.world.TransitStops;
import ai.shuvrajeetkamila.mumbai.sim.Transit;

/** Walks every rail alignment in the real world data and reports. */
public final class RailProbe {

    static String clsName(int c) {
        switch (c) {
            case WorldData.CLS_RAIL: return "RAIL";
            case WorldData.CLS_LIGHT_RAIL: return "METRO";
            case WorldData.CLS_SUBWAY: return "SUBWAY";
            case WorldData.CLS_MONORAIL: return "MONO";
            case WorldData.CLS_FERRY: return "FERRY";
            default: return "cls" + c;
        }
    }

    public static void main(String[] a) throws Exception {
        WorldData w = WorldData.load(q -> java.nio.file.Files.newInputStream(
                java.nio.file.Paths.get("android/src/main/assets").resolve(q)));
        System.out.println("routes=" + w.transitCount());

        int[] byCls = new int[32];
        double[] kmByCls = new double[32];
        for (int r = 0; r < w.transitCount(); r++) {
            int c = w.transitClass(r);
            if (c < 32) { byCls[c]++; kmByCls[c] += w.transitLength(r) / 1000.0; }
        }
        System.out.println("=== ROUTES BY CLASS ===");
        for (int c = 0; c < 32; c++) {
            if (byCls[c] > 0) {
                System.out.printf("  %-7s %4d routes  %8.1f km%n",
                        clsName(c), byCls[c], kmByCls[c]);
            }
        }

        Transit t = new Transit(w);
        System.out.println("usable routes = " + t.routeCount());
        System.out.println("stations = " + w.transitStops().count());

        // --- how bumpy is the track? ----------------------------------
        System.out.println("=== TRACK PROFILE: gradient along the alignment ===");
        System.out.println("  (rail cannot climb more than about 3%; a viaduct is flat)");
        for (int c : new int[]{WorldData.CLS_RAIL, WorldData.CLS_LIGHT_RAIL,
                WorldData.CLS_SUBWAY, WorldData.CLS_MONORAIL}) {
            double worst = 0; int worstRoute = -1; double worstAt = 0;
            int steepSamples = 0, total = 0;
            double maxStep = 0;
            for (int r = 0; r < w.transitCount(); r++) {
                if (w.transitClass(r) != c) { continue; }
                double len = w.transitLength(r);
                if (len < 500) { continue; }
                double prev = Double.NaN;
                for (double s = 0; s < len; s += 20.0) {
                    double[] p = t.pointAt(r, s);
                    double g = p[2];
                    if (!Double.isNaN(prev)) {
                        double grad = Math.abs(g - prev) / 20.0;
                        total++;
                        if (grad > 0.03) { steepSamples++; }
                        if (grad > worst) { worst = grad; worstRoute = r; worstAt = s; }
                        maxStep = Math.max(maxStep, Math.abs(g - prev));
                    }
                    prev = g;
                }
            }
            if (total == 0) { continue; }
            System.out.printf("  %-7s worst grade %.1f%% (route %d at %.0f m)  "
                    + "%.1f%% of 20 m samples steeper than 3%%  biggest step %.1f m%n",
                    clsName(c), worst * 100, worstRoute, worstAt,
                    100.0 * steepSamples / total, maxStep);
        }

        // --- does the track go under water? ---------------------------
        System.out.println("=== TRACK OVER WATER ===");
        for (int c : new int[]{WorldData.CLS_RAIL, WorldData.CLS_LIGHT_RAIL,
                WorldData.CLS_SUBWAY, WorldData.CLS_MONORAIL}) {
            int wet = 0, total = 0; double lowest = 1e9;
            for (int r = 0; r < w.transitCount(); r++) {
                if (w.transitClass(r) != c) { continue; }
                double len = w.transitLength(r);
                for (double s = 0; s < len; s += 25.0) {
                    double[] p = t.pointAt(r, s);
                    total++;
                    if (!w.isLand(p[0], p[1])) { wet++; }
                    lowest = Math.min(lowest, p[2]);
                }
            }
            if (total == 0) { continue; }
            System.out.printf("  %-7s %d of %d samples over water (%.1f%%)  "
                    + "lowest rail head %.2f m%n",
                    clsName(c), wet, total, 100.0 * wet / total, lowest);
        }

        // --- platform vs train height ---------------------------------
        System.out.println("=== PLATFORM vs TRAIN HEIGHT AT EVERY STATION ===");
        int mismatch = 0; double worstGap = 0;
        TransitStops stops = w.transitStops();
        for (int i = 0; i < stops.count(); i++) {
            TransitStops.Stop st = stops.get(i);
            // what the mesher builds the platform on
            double meshGround = st.y;
            // what Transit puts the train on
            double[] q = t.pointAt(st.route, 0);
            double simGround = st.y;
            double gap = Math.abs(meshGround - simGround);
            if (gap > 0.25) { mismatch++; worstGap = Math.max(worstGap, gap); }
        }
        System.out.printf("  %d of %d stations have the platform and the train "
                + "on different ground (worst %.2f m)%n",
                mismatch, stops.count(), worstGap);

        // --- how much does a 195 m rake span? -------------------------
        System.out.println("=== RAKE SPAN: ground height change over one train ===");
        {
            double worst = 0; int n = 0; double sum = 0;
            for (int r = 0; r < w.transitCount(); r++) {
                int c = w.transitClass(r);
                if (c != WorldData.CLS_RAIL) { continue; }
                double len = w.transitLength(r);
                if (len < 600) { continue; }
                for (double s = 200; s < len; s += 200.0) {
                    double[] nose = t.pointAt(r, s);
                    double[] tail = t.pointAt(r, s - 195);
                    double d = Math.abs(nose[2] - tail[2]);
                    worst = Math.max(worst, d);
                    sum += d; n++;
                }
            }
            System.out.printf("  mean %.2f m, worst %.2f m rail-head change across a 9-car rake%n",
                    sum / Math.max(1, n), worst);
        }

        // --- boarding in 3D -------------------------------------------
        System.out.println("=== BOARDING ===");
        System.out.println("  Transit.boardableAt now takes the player's feet; "
                + "tolerance " + Transit.BOARD_RISE_M + " m.");

        // --- stations by grade ----------------------------------------
        int[] grade = new int[4];
        for (int i = 0; i < stops.count(); i++) {
            grade[stops.get(i).grade.ordinal()]++;
        }
        System.out.printf("  stations: %d underground (no platform drawn), "
                + "%d surface, %d elevated, %d water%n",
                grade[0], grade[1], grade[2], grade[3]);
    }
}
