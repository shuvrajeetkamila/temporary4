package ai.shuvrajeetkamila.mumbai.game;

/**
 * The wanted level: how much trouble you are in, and whether the police
 * have you.
 *
 * <p>Heat is a continuous quantity rather than a counter of stars. Crimes
 * add to it, it bleeds away once nobody has eyes on you, and the star
 * rating is a view of it. That makes the decay smooth and means a long run
 * of small offences escalates the same way one big one does.
 *
 * <p>Two deliberate choices, both about not punishing the player for the
 * city being crowded:
 *
 * <ul>
 *   <li>Heat only falls once no police unit has been near you for a while.
 *       Losing the police is the mechanic; waiting is not.</li>
 *   <li>Clipping a pedestrian at walking pace is not a crime. Mumbai
 *       pavements are busy and a wanted level that triggered on contact
 *       would be permanently on.</li>
 * </ul>
 *
 * <p>There are no guns anywhere in this game, by design. The only force
 * available on foot is a baton, and the police response is vehicles and
 * arrest, not shooting.
 */
public final class Wanted {

    /** Most stars that can be earned. */
    public static final int MAX_STARS = 5;

    /** Heat added per offence. */
    public static final double HEAT_PED = 0.62;
    public static final double HEAT_CAR = 0.22;
    public static final double HEAT_POLICE = 0.95;
    public static final double HEAT_BATON = 0.38;

    /** Below this speed, contact is a bump rather than a crime. */
    public static final double CRIME_SPEED = 4.0;

    /** A police unit this close can see you. */
    public static final double SEEN_M = 95.0;

    /** Seconds out of sight before heat starts to fall. */
    public static final double COOL_DELAY = 8.0;

    /** Stars lost per second once cooling. One star takes about 12 s. */
    public static final double COOL_RATE = 1.0 / 12.0;

    /** How close a police car must be, and how slow you must be, to nick you. */
    public static final double BUST_M = 7.0;
    public static final double BUST_SPEED = 2.2;
    public static final double BUST_SECONDS = 2.6;

    /** Fraction of your money taken when arrested. */
    public static final double FINE_FRACTION = 0.25;

    /** Continuous trouble, 0 to MAX_STARS. */
    public double heat;

    /** Seconds since a police unit was last within sight. */
    public double unseenFor = COOL_DELAY;

    /** Seconds spent cornered by the police. */
    public double bustTimer;

    /** Set for one update when an arrest happens. */
    public boolean bustedThisStep;

    /** Rupees taken by the last fine. */
    public int lastFine;

    public int timesBusted;

    /** Whole stars, which is what the HUD draws. */
    public int stars() {
        int s = (int) Math.floor(heat + 1e-9);
        return s > MAX_STARS ? MAX_STARS : s;
    }

    public boolean active() { return heat >= 1.0; }

    /**
     * How many police cars should be hunting you.
     *
     * <p>One per star, so the pressure scales visibly rather than the same
     * two cars getting angrier.
     */
    public int desiredUnits() {
        return stars();
    }

    /** Police drive this much over the limit, by star rating. */
    public double pursuitPace() {
        return 1.05 + 0.07 * stars();
    }

    /**
     * Records running somebody over. No effect below {@link #CRIME_SPEED}.
     *
     * @return true if it counted
     */
    public boolean ranOverPedestrian(double speed) {
        if (speed < CRIME_SPEED) { return false; }
        add(HEAT_PED);
        return true;
    }

    /** Records hitting another vehicle hard enough to matter. */
    public boolean rammedVehicle(double speed, boolean wasPolice) {
        if (speed < CRIME_SPEED) { return false; }
        add(wasPolice ? HEAT_POLICE : HEAT_CAR);
        return true;
    }

    /** Records swinging the baton at somebody. */
    public void swungBaton() { add(HEAT_BATON); }

    private void add(double h) {
        heat += h;
        if (heat > MAX_STARS) { heat = MAX_STARS; }
        // Any fresh offence puts you back in view, whatever you were doing.
        unseenFor = 0;
    }

    /**
     * Advances the clock.
     *
     * @param nearestPolice metres to the closest police unit, or a large
     *                      number if there are none
     * @param playerSpeed   metres per second
     */
    public void update(double dt, double nearestPolice, double playerSpeed) {
        bustedThisStep = false;
        if (heat <= 0) {
            heat = 0;
            bustTimer = 0;
            unseenFor = COOL_DELAY;
            return;
        }

        if (nearestPolice <= SEEN_M) {
            unseenFor = 0;
        } else {
            unseenFor += dt;
        }

        if (unseenFor >= COOL_DELAY) {
            heat -= COOL_RATE * dt;
            if (heat < 0) { heat = 0; }
        }

        // Cornered: a police car on top of you while you are going nowhere.
        if (nearestPolice <= BUST_M && playerSpeed <= BUST_SPEED) {
            bustTimer += dt;
            if (bustTimer >= BUST_SECONDS) { bustedThisStep = true; }
        } else {
            bustTimer = 0;
        }
    }

    /**
     * Applies an arrest and returns the fine.
     *
     * <p>You lose a quarter of what you are carrying and the heat goes. No
     * other penalty: a hospital-and-wake-up-somewhere-else cycle needs
     * interiors and a save system, neither of which exists here, and
     * teleporting the player across the city with no explanation is worse
     * than a fine.
     */
    public int bust(int money) {
        int fine = (int) Math.round(money * FINE_FRACTION);
        lastFine = fine;
        timesBusted++;
        heat = 0;
        bustTimer = 0;
        unseenFor = COOL_DELAY;
        bustedThisStep = false;
        return fine;
    }

    /** Wipes the slate, for a fresh start or a test. */
    public void clear() {
        heat = 0;
        bustTimer = 0;
        unseenFor = COOL_DELAY;
        bustedThisStep = false;
    }
}
