package ai.shuvrajeetkamila.mumbai.game;

/**
 * The traffic of Mumbai, unbranded.
 *
 * <p>Every one of these is described by dimensions and physical parameters,
 * never by a likeness. A kerb-weight, a wheelbase and a drag area are facts
 * about a <i>class</i> of vehicle; they are not anybody's design. There is no
 * manufacturer named anywhere in this file, no badge modelled, no grille
 * pattern copied and no real livery reproduced.
 *
 * <p>The two-tone black and yellow of the taxi and the yellow-and-green of
 * the autorickshaw are not brands: they are livery mandated by the state
 * transport authority for licensed public vehicles, in the same way a London
 * taxi is black or a New York cab is yellow. Reproducing a regulatory colour
 * scheme is not reproducing a trade mark.
 *
 * <p>Handling differences are real and deliberate. An autorickshaw with
 * 300 kg and one front wheel genuinely does tip into corners; a loaded
 * double-decker genuinely does roll and understeer. Those come out of the
 * mass, the inertia tensor and the centre-of-mass height below, not out of a
 * fudge factor applied afterwards.
 */
public enum VehicleKind {

    /**
     * The kaali-peeli. A small, elderly, upright saloon in black over
     * yellow: the licensed taxi of the island city for sixty years.
     */
    KAALI_PEELI("Taxi", 1180, 3.92, 1.58, 1.52, 2.41, 0.56,
            132, 5400, 0.62, 4,
            0.07f, 0.07f, 0.07f, 0.95f, 0.78f, 0.10f, true),

    /**
     * Autorickshaw. Three wheels, one at the front, a two-stroke engine's
     * worth of power and a very short wheelbase. Tips readily, turns inside
     * anything else on the road, and is not allowed south of Bandra — which
     * the traffic generator respects.
     */
    AUTORICKSHAW("Auto", 340, 2.63, 1.40, 1.72, 2.00, 0.60,
            21, 5000, 0.48, 4,
            0.09f, 0.09f, 0.09f, 0.95f, 0.78f, 0.10f, true),

    /** Double-decker city bus. Tall, heavy, deliberately ponderous. */
    DOUBLE_DECKER("Bus", 12400, 10.20, 2.44, 4.35, 5.30, 1.62,
            620, 2300, 0.86, 6,
            0.74f, 0.13f, 0.11f, 0.90f, 0.88f, 0.84f, false),

    /** Single-deck city bus, the commoner sight on a suburban route. */
    CITY_BUS("Bus", 9800, 10.80, 2.50, 3.18, 5.60, 1.08,
            560, 2400, 0.84, 6,
            0.26f, 0.42f, 0.70f, 0.92f, 0.92f, 0.90f, false),

    /** Three-wheeled goods carrier. Ubiquitous, slow, usually overloaded. */
    TEMPO("Tempo", 1450, 4.40, 1.62, 1.98, 2.70, 0.78,
            95, 4200, 0.70, 5,
            0.82f, 0.80f, 0.72f, 0.30f, 0.33f, 0.38f, false),

    /** 125 cc scooter. The actual transport of the city. */
    SCOOTER("Scooter", 142, 1.86, 0.70, 1.12, 1.30, 0.44,
            11, 7500, 0.42, 4,
            0.18f, 0.20f, 0.26f, 0.72f, 0.72f, 0.74f, true),

    /** Small modern hatchback. Private traffic, no particular model. */
    HATCHBACK("Hatchback", 1040, 3.75, 1.66, 1.50, 2.40, 0.52,
            128, 6000, 0.60, 5,
            0.76f, 0.78f, 0.80f, 0.76f, 0.78f, 0.80f, true),

    /** Mid-size saloon. */
    SEDAN("Sedan", 1340, 4.48, 1.73, 1.47, 2.62, 0.54,
            196, 6200, 0.59, 6,
            0.58f, 0.60f, 0.66f, 0.58f, 0.60f, 0.66f, true),

    /** Compact SUV. Taller, heavier, rolls more. */
    SUV("SUV", 1680, 4.32, 1.80, 1.76, 2.60, 0.70,
            210, 5800, 0.68, 6,
            0.34f, 0.36f, 0.40f, 0.34f, 0.36f, 0.40f, true),

    /** Police interceptor: a marked saloon, white over blue. */
    POLICE("Police", 1420, 4.50, 1.76, 1.52, 2.64, 0.55,
            240, 6400, 0.60, 6,
            0.94f, 0.94f, 0.96f, 0.10f, 0.22f, 0.58f, true);

    public final String label;
    /** Kerb mass, kg. */
    public final double massKg;
    public final double lengthM;
    public final double widthM;
    public final double heightM;
    public final double wheelbaseM;
    /** Height of the centre of mass above ground, m. Drives roll. */
    public final double comHeightM;
    /** Peak engine torque, N·m. */
    public final double peakTorqueNm;
    /** Engine speed at peak power, rpm. */
    public final double redlineRpm;
    /** Drag area, Cd·A, m². */
    public final double dragArea;
    public final int gearCount;
    /** Primary body colour. */
    public final float pr, pg, pb;
    /** Secondary colour: roof on the taxi, canopy on the auto, stripe else. */
    public final float sr, sg, sb;
    /** Whether the player may drive it. Buses and tempos stay ambient. */
    public final boolean enterable;

    VehicleKind(String label, double massKg, double lengthM, double widthM,
                double heightM, double wheelbaseM, double comHeightM,
                double peakTorqueNm, double redlineRpm, double dragArea,
                int gearCount,
                float pr, float pg, float pb, float sr, float sg, float sb,
                boolean enterable) {
        this.label = label;
        this.massKg = massKg;
        this.lengthM = lengthM;
        this.widthM = widthM;
        this.heightM = heightM;
        this.wheelbaseM = wheelbaseM;
        this.comHeightM = comHeightM;
        this.peakTorqueNm = peakTorqueNm;
        this.redlineRpm = redlineRpm;
        this.dragArea = dragArea;
        this.gearCount = gearCount;
        this.pr = pr; this.pg = pg; this.pb = pb;
        this.sr = sr; this.sg = sg; this.sb = sb;
        this.enterable = enterable;
    }

    /** Three wheels, so it leans rather than rolls flat. */
    public boolean isThreeWheeler() {
        return this == AUTORICKSHAW || this == TEMPO;
    }

    public boolean isTwoWheeler() { return this == SCOOTER; }

    /**
     * Rolling radius of a driven wheel, metres.
     *
     * <p>This is not cosmetic. The wheel radius converts engine speed into
     * road speed and brake torque into deceleration, so getting it wrong
     * mis-gears the whole vehicle. A bus on a car's 0.32 m wheel is
     * geared roughly a third too short and cannot reach its own top
     * speed; a scooter on the same wheel is geared far too long to pull
     * away.
     *
     * <p>The car case is centred on a mid-size saloon at 0.32 m and
     * scaled gently with wheelbase, which tracks real tyre sizes closely
     * enough across the range this game contains.
     */
    /**
     * Governed top speed in km/h, used to choose the final drive.
     *
     * <p>Real gearing is picked so the engine reaches its limiter at the
     * vehicle's design speed, and that is what this is for. Without it
     * every vehicle inherited a saloon's 4.06 final drive, which geared
     * an autorickshaw for 95 km/h and a double-decker for nearly 100 -
     * both roughly half as long as they should be.
     */
    public double topSpeedKph() {
        switch (this) {
            case AUTORICKSHAW: return 62;
            case DOUBLE_DECKER: return 70;
            case CITY_BUS: return 80;
            case SCOOTER: return 85;
            case TEMPO: return 92;
            case KAALI_PEELI: return 130;
            case HATCHBACK: return 158;
            case SUV: return 182;
            case POLICE: return 205;
            default: return 196;
        }
    }

    public double wheelRadiusM() {
        if (isTwoWheeler()) { return 0.25; }          // 12 inch rim plus tyre
        if (massKg > 5000) { return 0.52; }           // 22.5 inch truck wheel
        if (isThreeWheeler()) { return 0.21; }        // auto and tempo
        double r = 0.32 + (wheelbaseM - 2.62) * 0.06;
        return Math.max(0.26, Math.min(0.40, r));
    }

    /**
     * Yaw inertia, kg·m². Approximated as a uniform box about the vertical,
     * which for a vehicle is within about fifteen percent of measured values
     * and is the right shape of answer: long vehicles resist turning.
     */
    public double yawInertia() {
        return massKg * (lengthM * lengthM + widthM * widthM) / 12.0;
    }

    public double rollInertia() {
        return massKg * (heightM * heightM + widthM * widthM) / 12.0;
    }

    public double pitchInertia() {
        return massKg * (lengthM * lengthM + heightM * heightM) / 12.0;
    }

    /**
     * How common each is in ambient traffic, as an unnormalised weight.
     *
     * <p>Roughly the real mix: two-wheelers and autos dominate by count,
     * private cars next, buses and taxis a steady minority. Police are not
     * ambient traffic and are spawned by the wanted system instead.
     */
    public double trafficWeight() {
        switch (this) {
            case SCOOTER: return 0.26;
            case AUTORICKSHAW: return 0.22;
            case HATCHBACK: return 0.16;
            case SEDAN: return 0.11;
            case KAALI_PEELI: return 0.09;
            case SUV: return 0.07;
            case TEMPO: return 0.05;
            case CITY_BUS: return 0.025;
            case DOUBLE_DECKER: return 0.015;
            default: return 0.0;
        }
    }

    /** Autorickshaws are barred from the southern tip by licence. */
    public boolean allowedAt(double worldZ) {
        // Bandra is near z = 0 in the local frame; south of the island runs
        // positive. Autos are not licensed below roughly Mahim.
        return this != AUTORICKSHAW || worldZ < 12000;
    }

    private static final VehicleKind[] AMBIENT = values();

    /** Picks an ambient vehicle by weight from a uniform sample in [0, 1). */
    public static VehicleKind sample(double u, double worldZ) {
        double total = 0;
        for (VehicleKind k : AMBIENT) {
            if (k.allowedAt(worldZ)) { total += k.trafficWeight(); }
        }
        double pick = u * total;
        for (VehicleKind k : AMBIENT) {
            if (!k.allowedAt(worldZ)) { continue; }
            pick -= k.trafficWeight();
            if (pick <= 0) { return k; }
        }
        return HATCHBACK;
    }
}
