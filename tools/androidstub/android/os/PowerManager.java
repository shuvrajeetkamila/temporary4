package android.os;
public class PowerManager {
    public static final int THERMAL_STATUS_NONE = 0, THERMAL_STATUS_LIGHT = 1,
        THERMAL_STATUS_MODERATE = 2, THERMAL_STATUS_SEVERE = 3, THERMAL_STATUS_CRITICAL = 4,
        THERMAL_STATUS_EMERGENCY = 5, THERMAL_STATUS_SHUTDOWN = 6;
    public interface OnThermalStatusChangedListener { void onThermalStatusChanged(int status); }
    public int getCurrentThermalStatus() { return 0; }
    public void addThermalStatusListener(OnThermalStatusChangedListener l) {}
    public void removeThermalStatusListener(OnThermalStatusChangedListener l) {}
    public boolean isPowerSaveMode() { return false; }
}
