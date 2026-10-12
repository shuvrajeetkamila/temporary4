package ai.shuvrajeetkamila.mumbai.world;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where packaged world files come from.
 *
 * <p>One method, so that the engine core has no dependency on
 * {@code android.content.res.AssetManager} and can therefore be unit tested on
 * a desktop JVM against the very same files that ship in the APK. The Android
 * host supplies an AssetManager-backed implementation; the tests supply one
 * that reads the build directory.
 */
public interface Assets {
    /** Open a packaged file by its path relative to the assets root. */
    InputStream open(String path) throws IOException;
}
