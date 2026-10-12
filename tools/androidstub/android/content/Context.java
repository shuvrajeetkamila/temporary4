package android.content;
public class Context {
    public static final String POWER_SERVICE = "power";
    public Context getApplicationContext() { return this; }
    public android.content.res.AssetManager getAssets() { return null; }
    public Object getSystemService(String name) { return null; }
    public android.content.res.Resources getResources() { return null; }
    public String getPackageName() { return ""; }
    public android.content.pm.PackageManager getPackageManager() { return null; }
}
