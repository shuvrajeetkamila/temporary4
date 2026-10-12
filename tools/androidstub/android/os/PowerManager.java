package android.os;
public class PowerManager {
 public interface OnThermalStatusChangedListener { void onThermalStatusChanged(int status); }
 public void addThermalStatusListener(OnThermalStatusChangedListener l){}
 public void removeThermalStatusListener(OnThermalStatusChangedListener l){}
 public int getCurrentThermalStatus(){return 0;} }
