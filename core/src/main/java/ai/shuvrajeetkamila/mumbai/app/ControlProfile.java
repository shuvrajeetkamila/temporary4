package ai.shuvrajeetkamila.mumbai.app;

/** What the player is currently controlling. */
public enum ControlProfile {
    ON_FOOT("On foot"),
    CAR("Car"),
    BOAT("Boat"),
    HELICOPTER("Helicopter"),
    AEROPLANE("Aeroplane");

    public final String label;

    ControlProfile(String label) { this.label = label; }

    public ControlProfile next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
