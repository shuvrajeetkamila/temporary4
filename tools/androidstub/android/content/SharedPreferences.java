package android.content;

/** Minimal stub for local type-checking only. Not shipped. */
public interface SharedPreferences {
    String getString(String key, String def);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        boolean commit();

        void apply();
    }
}
