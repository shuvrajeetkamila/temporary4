package ai.shuvrajeetkamila.mumbai.android;

import android.content.SharedPreferences;

import ai.shuvrajeetkamila.mumbai.game.Progress;

/**
 * The save, in SharedPreferences.
 *
 * <p>One string under one key. The format is core's business, not this
 * class's: the point of the interface is that nothing in core has to know
 * what a {@code SharedPreferences} is.
 *
 * <p>Committed synchronously rather than applied, because the one moment
 * this is called is the moment the process may be about to be killed.
 */
final class PrefsStore implements Progress.Store {

    private static final String KEY = "progress";

    private final SharedPreferences prefs;

    PrefsStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    @Override
    public String read() {
        try {
            return prefs.getString(KEY, "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public void write(String text) {
        try {
            prefs.edit().putString(KEY, text).commit();
        } catch (RuntimeException ignored) {
            // A save that fails is a save that fails. It must never take
            // the game down with it.
        }
    }
}
