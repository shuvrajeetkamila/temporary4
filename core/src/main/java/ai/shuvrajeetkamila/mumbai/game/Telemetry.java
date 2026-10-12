package ai.shuvrajeetkamila.mumbai.game;

/**
 * Lock free snapshot of simulation state for the HUD and the diagnostics overlay.
 * The GL / simulation thread writes, the UI thread reads. All fields are volatile and
 * individually meaningful, so a torn read can never crash the HUD.
 */
public final class Telemetry {

    // --- frame / perf -------------------------------------------------------
    public volatile float fps;
    public volatile float cpuFrameMs;
    public volatile float simMs;
    public volatile int drawCalls;
    public volatile int triangles;
    public volatile int chunksActive;
    public volatile int chunksPending;
    public volatile int chunksCpuReady;
    public volatile long heapUsedMb;
    public volatile int gpuBytesMb;
    public volatile int activeBodies;
    public volatile int trafficAgents;
    public volatile int pedestrians;
    public volatile String profileName = "BALANCED";
    public volatile boolean thermalEco;
    public volatile boolean diagnostics = false;

    // --- world / player -----------------------------------------------------
    public volatile String locationName = "Mumbai";
    public volatile String controlProfile = "ON FOOT";
    public volatile double worldX;
    public volatile double worldY;
    public volatile double worldZ;
    public volatile double latitude;
    public volatile double longitude;
    public volatile float headingDeg;
    public volatile float speedKmh;
    public volatile float speedKnots;
    public volatile float altitudeM;
    public volatile float verticalSpeed;
    public volatile float engineRpm;
    public volatile int gear;
    public volatile float rotorRpm;
    public volatile float collective;
    public volatile float airspeedKts;
    public volatile int flapNotch;
    public volatile boolean gearDown = true;
    public volatile boolean engineOn;
    public volatile boolean stallWarning;
    public volatile float timeOfDayHours = 8f;
    public volatile float rainIntensity;
    public volatile float tideMetres;

    // --- flow ---------------------------------------------------------------
    public volatile boolean loading = true;
    public volatile float loadProgress;
    public volatile String loadStage = "Starting";
    public volatile String toast = "";
    public volatile long toastUntilMs;
    public volatile boolean paused;
    public volatile boolean fatalError;
    public volatile String fatalMessage = "";
    public volatile String contextHint = "";

    public void showToast(String text, long nowMs, long durationMs) {
        toast = text;
        toastUntilMs = nowMs + durationMs;
    }
}
