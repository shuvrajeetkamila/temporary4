package ai.shuvrajeetkamila.mumbai.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Real place names from OpenStreetMap, used for the "you are near" readout and
 * the minimap labels.
 *
 * <p>Names are OSM's, not invented ones. Lookup is a linear scan with an early
 * squared-distance reject over a few thousand entries, which costs far less
 * than the HUD redraw it feeds and avoids another spatial index to keep
 * correct.
 */
public final class Places {

    public static final class Place {
        public final double x, z;
        public final String kind;
        public final String name;

        Place(double x, double z, String kind, String name) {
            this.x = x; this.z = z; this.kind = kind; this.name = name;
        }
    }

    private final List<Place> all = new ArrayList<>();

    private Places() { }

    public static Places parse(String json) {
        Places p = new Places();
        for (String obj : Json.objectsOf(json, null)) {
            double x = Json.number(obj, "x", Double.NaN);
            double z = Json.number(obj, "z", Double.NaN);
            String k = Json.string(obj, "kind", "");
            String n = Json.string(obj, "name", "");
            if (Double.isFinite(x) && Double.isFinite(z) && !n.isEmpty()) {
                p.all.add(new Place(x, z, k, n));
            }
        }
        return p;
    }

    public int size() { return all.size(); }

    public List<Place> all() { return all; }

    /** Nearest named place, or null if the table is empty or everything is far. */
    public Place nearest(double x, double z, double maxMetres) {
        Place best = null;
        double bd = maxMetres * maxMetres;
        for (int i = 0; i < all.size(); i++) {
            Place p = all.get(i);
            double dx = p.x - x;
            double dz = p.z - z;
            double d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** Nearest place whose kind starts with the given prefix. */
    public Place nearestOfKind(double x, double z, String kindPrefix, double maxMetres) {
        Place best = null;
        double bd = maxMetres * maxMetres;
        for (int i = 0; i < all.size(); i++) {
            Place p = all.get(i);
            if (!p.kind.startsWith(kindPrefix)) { continue; }
            double dx = p.x - x;
            double dz = p.z - z;
            double d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }
}
