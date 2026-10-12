package ai.shuvrajeetkamila.mumbai.game;

/** Which control layout / action map is active. */
public enum ControlProfile {
    ON_FOOT,
    CAR,
    BOAT,
    HELICOPTER,
    AEROPLANE;

    public String displayName() {
        switch (this) {
            case ON_FOOT: return "ON FOOT";
            case CAR: return "CAR";
            case BOAT: return "BOAT";
            case HELICOPTER: return "HELICOPTER";
            case AEROPLANE: return "AEROPLANE";
            default: return name();
        }
    }
}
