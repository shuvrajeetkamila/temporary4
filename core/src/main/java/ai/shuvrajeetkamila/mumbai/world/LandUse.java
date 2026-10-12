package ai.shuvrajeetkamila.mumbai.world;

/**
 * Land use classes produced by the world compiler, plus the surface properties the
 * renderer, the human controller, the car tyre model and the boat use.
 * Codes must stay in sync with tools/build_world.py.
 */
public final class LandUse {

    public static final int DEEP_SEA = 0;
    public static final int SHALLOW_SEA = 1;
    public static final int HARBOUR = 2;
    public static final int CREEK = 3;
    public static final int LAKE = 4;
    public static final int RIVER = 5;
    public static final int BEACH = 6;
    public static final int MUDFLAT = 7;
    public static final int MANGROVE = 8;
    public static final int FOREST = 9;
    public static final int PARK = 10;
    public static final int OPEN = 11;
    public static final int URBAN_LOW = 12;
    public static final int SUBURBAN = 13;
    public static final int URBAN = 14;
    public static final int URBAN_DENSE = 15;
    public static final int CBD = 16;
    public static final int INDUSTRIAL = 17;
    public static final int AIRPORT = 18;
    public static final int RAILYARD = 19;
    public static final int COUNT = 20;

    private LandUse() {
    }

    public static boolean isWater(int code) {
        return code <= RIVER;
    }

    /** Navigable for the player boat (drinking water lakes are deliberately excluded). */
    public static boolean isNavigable(int code) {
        return code == DEEP_SEA || code == SHALLOW_SEA || code == HARBOUR || code == CREEK;
    }

    public static boolean isUrban(int code) {
        return code >= URBAN_LOW && code <= CBD;
    }

    /** Buildings per 256 m chunk target for a land use class. */
    public static int buildingDensity(int code) {
        switch (code) {
            case URBAN_LOW: return 10;
            case SUBURBAN: return 20;
            case URBAN: return 34;
            case URBAN_DENSE: return 60;
            case CBD: return 26;
            case INDUSTRIAL: return 8;
            case OPEN: return 2;
            default: return 0;
        }
    }

    /** Typical building height range (metres) for procedural massing. */
    public static float minHeight(int code) {
        switch (code) {
            case URBAN_LOW: return 4f;
            case SUBURBAN: return 7f;
            case URBAN: return 10f;
            case URBAN_DENSE: return 4f;
            case CBD: return 40f;
            case INDUSTRIAL: return 7f;
            default: return 4f;
        }
    }

    public static float maxHeight(int code) {
        switch (code) {
            case URBAN_LOW: return 12f;
            case SUBURBAN: return 24f;
            case URBAN: return 48f;
            case URBAN_DENSE: return 30f;
            case CBD: return 180f;
            case INDUSTRIAL: return 22f;
            default: return 9f;
        }
    }

    /** Tyre / foot friction coefficient scale for a surface (dry). */
    public static float gripScale(int code) {
        switch (code) {
            case BEACH: return 0.55f;
            case MUDFLAT: return 0.42f;
            case MANGROVE: return 0.40f;
            case FOREST:
            case PARK: return 0.72f;
            case OPEN: return 0.78f;
            case AIRPORT: return 1.0f;
            default:
                return isWater(code) ? 0.08f : 0.95f;
        }
    }

    /** Walking speed scale on this surface. */
    public static float walkScale(int code) {
        switch (code) {
            case BEACH: return 0.72f;
            case MUDFLAT: return 0.55f;
            case MANGROVE: return 0.5f;
            case FOREST: return 0.8f;
            default: return 1.0f;
        }
    }

    /** Base albedo used by the renderer when no texture is bound (r,g,b packed 0-255). */
    public static int baseColour(int code) {
        switch (code) {
            case DEEP_SEA: return 0x0E2642;
            case SHALLOW_SEA: return 0x1C4A70;
            case HARBOUR: return 0x204660;
            case CREEK: return 0x2A5664;
            case LAKE: return 0x2C6E8C;
            case RIVER: return 0x326482;
            case BEACH: return 0xDCC8A0;
            case MUDFLAT: return 0x7A6E5C;
            case MANGROVE: return 0x3C5C3C;
            case FOREST: return 0x22542C;
            case PARK: return 0x4A8046;
            case OPEN: return 0x8C926E;
            case URBAN_LOW: return 0x9A9484;
            case SUBURBAN: return 0xA69C8C;
            case URBAN: return 0xA89A8A;
            case URBAN_DENSE: return 0xAE9A82;
            case CBD: return 0xB4A490;
            case INDUSTRIAL: return 0x827A76;
            case AIRPORT: return 0x9A9A9E;
            case RAILYARD: return 0x6E6864;
            default: return 0x808080;
        }
    }

    public static String name(int code) {
        switch (code) {
            case DEEP_SEA: return "Arabian Sea";
            case SHALLOW_SEA: return "coastal water";
            case HARBOUR: return "Mumbai Harbour";
            case CREEK: return "creek";
            case LAKE: return "lake";
            case RIVER: return "river";
            case BEACH: return "beach";
            case MUDFLAT: return "mudflat";
            case MANGROVE: return "mangrove";
            case FOREST: return "forest";
            case PARK: return "park";
            case OPEN: return "open land";
            case URBAN_LOW: return "low density";
            case SUBURBAN: return "suburban";
            case URBAN: return "urban";
            case URBAN_DENSE: return "dense urban";
            case CBD: return "business district";
            case INDUSTRIAL: return "industrial";
            case AIRPORT: return "airport";
            case RAILYARD: return "rail yard";
            default: return "unknown";
        }
    }
}
