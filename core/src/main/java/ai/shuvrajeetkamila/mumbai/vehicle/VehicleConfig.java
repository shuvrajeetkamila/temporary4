package ai.shuvrajeetkamila.mumbai.vehicle;

/**
 * Tuning data for a ground vehicle. Every value is SI and every vehicle in the game is a
 * fictional, unbranded design: the numbers below are plausible for the class, not copied
 * from any manufacturer's specification sheet.
 */
public final class VehicleConfig {

    public String name = "Hatch";
    public String className = "car";

    // Body ---------------------------------------------------------------------------
    public float mass = 1150f;                 // kg, kerb + driver
    public float bodyLength = 3.9f;            // m
    public float bodyWidth = 1.66f;
    public float bodyHeight = 1.5f;
    public float cgHeight = 0.52f;             // above the wheel hub plane
    public float inertiaScale = 1.0f;

    // Wheels -------------------------------------------------------------------------
    /** Local X (right+), Z (forward+) of each wheel, relative to the centre of mass. */
    public float[] wheelX = {-0.72f, 0.72f, -0.72f, 0.72f};
    public float[] wheelZ = {1.28f, 1.28f, -1.24f, -1.24f};
    public boolean[] steered = {true, true, false, false};
    public boolean[] driven = {true, true, false, false};
    public boolean[] handbraked = {false, false, true, true};
    public float wheelRadius = 0.29f;
    public float wheelMass = 14f;
    public float wheelWidth = 0.19f;

    // Suspension ---------------------------------------------------------------------
    public float suspensionRest = 0.40f;       // m of travel from the attachment point
    public float suspensionTravel = 0.20f;
    public float springRate = 32000f;          // N/m per corner
    public float damperBump = 3600f;           // N per m/s
    public float damperRebound = 4800f;
    public float antiRollStiffness = 9000f;

    // Tyre (simplified Pacejka magic formula) ----------------------------------------
    public float tyreGrip = 1.05f;             // peak mu on dry asphalt
    public float latB = 9.5f, latC = 1.42f, latE = 0.97f;
    public float longB = 11.0f, longC = 1.62f, longE = 0.95f;
    public float rollingResistance = 0.016f;

    // Drivetrain ---------------------------------------------------------------------
    public float peakTorque = 115f;            // Nm at the crank
    public float peakTorqueRpm = 3600f;
    public float redlineRpm = 6200f;
    public float idleRpm = 820f;
    public float[] gearRatios = {-3.3f, 0f, 3.55f, 2.05f, 1.38f, 1.03f, 0.82f};
    public float finalDrive = 4.1f;
    public float driveEfficiency = 0.88f;
    public float engineInertia = 0.22f;
    public float shiftTime = 0.35f;
    public float upshiftRpm = 5400f;
    public float downshiftRpm = 1900f;

    // Brakes -------------------------------------------------------------------------
    public float brakeTorqueFront = 1800f;
    public float brakeTorqueRear = 1100f;
    public float handbrakeTorque = 2600f;

    // Steering -----------------------------------------------------------------------
    public float maxSteerDeg = 34f;
    public float steerSpeedFalloff = 26f;      // m/s at which steering is halved
    public float steerRate = 3.2f;             // rad/s of steering wheel travel

    // Aerodynamics -------------------------------------------------------------------
    public float dragArea = 0.68f;             // Cd * A
    public float downforceCoefficient = 0.04f;

    public float topSpeedEstimate = 42f;       // m/s, for HUD and AI only

    // Appearance (fictional liveries) -------------------------------------------------
    public float colourR = 0.85f, colourG = 0.82f, colourB = 0.78f;
    public boolean taxiLivery;
    public boolean rickshawLivery;

    public VehicleConfig copy() {
        VehicleConfig c = new VehicleConfig();
        c.name = name;
        c.className = className;
        c.mass = mass;
        c.bodyLength = bodyLength;
        c.bodyWidth = bodyWidth;
        c.bodyHeight = bodyHeight;
        c.cgHeight = cgHeight;
        c.inertiaScale = inertiaScale;
        c.wheelX = wheelX.clone();
        c.wheelZ = wheelZ.clone();
        c.steered = steered.clone();
        c.driven = driven.clone();
        c.handbraked = handbraked.clone();
        c.wheelRadius = wheelRadius;
        c.wheelMass = wheelMass;
        c.wheelWidth = wheelWidth;
        c.suspensionRest = suspensionRest;
        c.suspensionTravel = suspensionTravel;
        c.springRate = springRate;
        c.damperBump = damperBump;
        c.damperRebound = damperRebound;
        c.antiRollStiffness = antiRollStiffness;
        c.tyreGrip = tyreGrip;
        c.latB = latB;
        c.latC = latC;
        c.latE = latE;
        c.longB = longB;
        c.longC = longC;
        c.longE = longE;
        c.rollingResistance = rollingResistance;
        c.peakTorque = peakTorque;
        c.peakTorqueRpm = peakTorqueRpm;
        c.redlineRpm = redlineRpm;
        c.idleRpm = idleRpm;
        c.gearRatios = gearRatios.clone();
        c.finalDrive = finalDrive;
        c.driveEfficiency = driveEfficiency;
        c.engineInertia = engineInertia;
        c.shiftTime = shiftTime;
        c.upshiftRpm = upshiftRpm;
        c.downshiftRpm = downshiftRpm;
        c.brakeTorqueFront = brakeTorqueFront;
        c.brakeTorqueRear = brakeTorqueRear;
        c.handbrakeTorque = handbrakeTorque;
        c.maxSteerDeg = maxSteerDeg;
        c.steerSpeedFalloff = steerSpeedFalloff;
        c.steerRate = steerRate;
        c.dragArea = dragArea;
        c.downforceCoefficient = downforceCoefficient;
        c.topSpeedEstimate = topSpeedEstimate;
        c.colourR = colourR;
        c.colourG = colourG;
        c.colourB = colourB;
        c.taxiLivery = taxiLivery;
        c.rickshawLivery = rickshawLivery;
        return c;
    }

    // --- Fictional vehicle catalogue --------------------------------------------------

    /** The black-and-yellow city taxi: small, soft, utterly unbranded. */
    public static VehicleConfig taxi() {
        VehicleConfig c = new VehicleConfig();
        c.name = "Kaali-Peeli";
        c.className = "taxi";
        c.mass = 1010f;
        c.bodyLength = 3.74f;
        c.bodyWidth = 1.58f;
        c.bodyHeight = 1.54f;
        c.springRate = 24000f;
        c.damperBump = 2500f;
        c.damperRebound = 3200f;
        c.peakTorque = 78f;
        c.peakTorqueRpm = 3000f;
        c.redlineRpm = 5600f;
        c.tyreGrip = 0.92f;
        c.topSpeedEstimate = 33f;
        c.colourR = 0.95f;
        c.colourG = 0.78f;
        c.colourB = 0.06f;
        c.taxiLivery = true;
        return c;
    }

    /** Three-wheeled auto: two rear wheels, one steered front wheel, tuck-under roll. */
    public static VehicleConfig autoRickshaw() {
        VehicleConfig c = new VehicleConfig();
        c.name = "Auto";
        c.className = "rickshaw";
        c.mass = 395f;
        c.bodyLength = 2.63f;
        c.bodyWidth = 1.3f;
        c.bodyHeight = 1.7f;
        c.cgHeight = 0.58f;
        c.wheelX = new float[]{0.0f, -0.6f, 0.6f};
        c.wheelZ = new float[]{1.0f, -1.0f, -1.0f};
        c.steered = new boolean[]{true, false, false};
        c.driven = new boolean[]{false, true, true};
        c.handbraked = new boolean[]{false, true, true};
        c.wheelRadius = 0.22f;
        c.wheelMass = 8f;
        c.springRate = 14000f;
        c.damperBump = 1500f;
        c.damperRebound = 2000f;
        c.antiRollStiffness = 1800f;
        c.peakTorque = 22f;
        c.peakTorqueRpm = 4000f;
        c.redlineRpm = 6000f;
        c.gearRatios = new float[]{-3.9f, 0f, 3.9f, 2.3f, 1.55f, 1.12f};
        c.finalDrive = 4.6f;
        c.tyreGrip = 0.82f;
        c.maxSteerDeg = 42f;
        c.brakeTorqueFront = 600f;
        c.brakeTorqueRear = 520f;
        c.dragArea = 1.1f;
        c.topSpeedEstimate = 17f;
        c.colourR = 0.1f;
        c.colourG = 0.12f;
        c.colourB = 0.14f;
        c.rickshawLivery = true;
        return c;
    }

    public static VehicleConfig hatchback() {
        VehicleConfig c = new VehicleConfig();
        c.name = "City Hatch";
        c.colourR = 0.72f;
        c.colourG = 0.1f;
        c.colourB = 0.12f;
        return c;
    }

    public static VehicleConfig sedan() {
        VehicleConfig c = new VehicleConfig();
        c.name = "Sedan";
        c.mass = 1420f;
        c.bodyLength = 4.6f;
        c.bodyWidth = 1.78f;
        c.bodyHeight = 1.46f;
        c.wheelZ = new float[]{1.42f, 1.42f, -1.48f, -1.48f};
        c.peakTorque = 190f;
        c.peakTorqueRpm = 3400f;
        c.springRate = 36000f;
        c.tyreGrip = 1.08f;
        c.topSpeedEstimate = 52f;
        c.colourR = 0.14f;
        c.colourG = 0.18f;
        c.colourB = 0.34f;
        return c;
    }

    public static VehicleConfig suv() {
        VehicleConfig c = new VehicleConfig();
        c.name = "Ghat Runner";
        c.className = "suv";
        c.mass = 1890f;
        c.bodyLength = 4.6f;
        c.bodyWidth = 1.86f;
        c.bodyHeight = 1.84f;
        c.cgHeight = 0.72f;
        c.wheelX = new float[]{-0.82f, 0.82f, -0.82f, 0.82f};
        c.wheelZ = new float[]{1.38f, 1.38f, -1.42f, -1.42f};
        c.driven = new boolean[]{true, true, true, true};
        c.wheelRadius = 0.36f;
        c.suspensionRest = 0.48f;
        c.suspensionTravel = 0.26f;
        c.springRate = 42000f;
        c.damperBump = 4600f;
        c.damperRebound = 5800f;
        c.peakTorque = 320f;
        c.peakTorqueRpm = 2200f;
        c.redlineRpm = 4600f;
        c.tyreGrip = 1.02f;
        c.dragArea = 0.95f;
        c.topSpeedEstimate = 48f;
        c.colourR = 0.2f;
        c.colourG = 0.22f;
        c.colourB = 0.2f;
        return c;
    }

    /** Red double-height city bus, long wheelbase, heavy roll. */
    public static VehicleConfig bus() {
        VehicleConfig c = new VehicleConfig();
        c.name = "City Bus";
        c.className = "bus";
        c.mass = 11500f;
        c.bodyLength = 10.8f;
        c.bodyWidth = 2.55f;
        c.bodyHeight = 4.1f;
        c.cgHeight = 1.25f;
        c.inertiaScale = 1.3f;
        c.wheelX = new float[]{-1.05f, 1.05f, -1.05f, 1.05f};
        c.wheelZ = new float[]{3.6f, 3.6f, -3.1f, -3.1f};
        c.wheelRadius = 0.52f;
        c.wheelMass = 80f;
        c.suspensionRest = 0.55f;
        c.springRate = 240000f;
        c.damperBump = 26000f;
        c.damperRebound = 32000f;
        c.antiRollStiffness = 90000f;
        c.peakTorque = 900f;
        c.peakTorqueRpm = 1400f;
        c.redlineRpm = 2600f;
        c.idleRpm = 600f;
        c.gearRatios = new float[]{-5.2f, 0f, 6.2f, 3.6f, 2.2f, 1.4f, 1.0f};
        c.finalDrive = 5.3f;
        c.upshiftRpm = 2200f;
        c.downshiftRpm = 1000f;
        c.tyreGrip = 0.88f;
        c.maxSteerDeg = 42f;
        c.brakeTorqueFront = 14000f;
        c.brakeTorqueRear = 12000f;
        c.dragArea = 4.9f;
        c.topSpeedEstimate = 22f;
        c.colourR = 0.75f;
        c.colourG = 0.13f;
        c.colourB = 0.1f;
        return c;
    }

    public static VehicleConfig truck() {
        VehicleConfig c = new VehicleConfig();
        c.name = "Tempo";
        c.className = "truck";
        c.mass = 4200f;
        c.bodyLength = 6.2f;
        c.bodyWidth = 2.1f;
        c.bodyHeight = 2.8f;
        c.cgHeight = 0.95f;
        c.wheelX = new float[]{-0.9f, 0.9f, -0.9f, 0.9f};
        c.wheelZ = new float[]{1.9f, 1.9f, -1.8f, -1.8f};
        c.wheelRadius = 0.44f;
        c.wheelMass = 48f;
        c.springRate = 95000f;
        c.damperBump = 11000f;
        c.damperRebound = 14000f;
        c.peakTorque = 430f;
        c.peakTorqueRpm = 1800f;
        c.redlineRpm = 3400f;
        c.finalDrive = 4.9f;
        c.tyreGrip = 0.9f;
        c.dragArea = 3.2f;
        c.topSpeedEstimate = 25f;
        c.colourR = 0.3f;
        c.colourG = 0.4f;
        c.colourB = 0.55f;
        return c;
    }

    /** Commuter motorcycle: two wheels, narrow track, leans into turns. */
    public static VehicleConfig motorcycle() {
        VehicleConfig c = new VehicleConfig();
        c.name = "Commuter 150";
        c.className = "bike";
        c.mass = 182f;
        c.bodyLength = 2.0f;
        c.bodyWidth = 0.74f;
        c.bodyHeight = 1.2f;
        c.cgHeight = 0.42f;
        c.inertiaScale = 0.6f;
        c.wheelX = new float[]{0f, 0f};
        c.wheelZ = new float[]{0.68f, -0.68f};
        c.steered = new boolean[]{true, false};
        c.driven = new boolean[]{false, true};
        c.handbraked = new boolean[]{false, true};
        c.wheelRadius = 0.3f;
        c.wheelMass = 11f;
        c.springRate = 17000f;
        c.damperBump = 1800f;
        c.damperRebound = 2200f;
        c.antiRollStiffness = 0f;
        c.peakTorque = 13f;
        c.peakTorqueRpm = 6000f;
        c.redlineRpm = 9500f;
        c.idleRpm = 1400f;
        c.gearRatios = new float[]{0f, 0f, 2.9f, 1.9f, 1.4f, 1.1f, 0.92f};
        c.finalDrive = 3.3f;
        c.upshiftRpm = 8200f;
        c.downshiftRpm = 3200f;
        c.tyreGrip = 1.0f;
        c.maxSteerDeg = 30f;
        c.brakeTorqueFront = 620f;
        c.brakeTorqueRear = 380f;
        c.dragArea = 0.52f;
        c.topSpeedEstimate = 32f;
        c.colourR = 0.1f;
        c.colourG = 0.3f;
        c.colourB = 0.5f;
        return c;
    }

    public static VehicleConfig[] catalogue() {
        return new VehicleConfig[]{
                taxi(), autoRickshaw(), hatchback(), sedan(), suv(), bus(), truck(), motorcycle()
        };
    }
}
