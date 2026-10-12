package ai.shuvrajeetkamila.mumbai;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import ai.shuvrajeetkamila.mumbai.world.WorldData;

/**
 * Loads the packaged world the same way the application does, but from the plain file
 * system so the whole simulation can be tested on a desktop JVM.
 *
 * <p>The asset root is supplied by Gradle through the {@code mumbai.assets} system
 * property and defaults to the in-repo asset tree. If the layers are missing the tests
 * fail loudly instead of silently skipping: a build that cannot find its own world data
 * must not report success.
 */
public final class TestWorld {

    private static WorldData cached;

    private TestWorld() {
    }

    public static File assetRoot() {
        String p = System.getProperty("mumbai.assets");
        if (p == null || p.isEmpty()) {
            File here = new File("android/src/main/assets");
            if (here.isDirectory()) {
                return here;
            }
            return new File("../android/src/main/assets");
        }
        return new File(p);
    }

    public static WorldData.AssetSource source() {
        final File root = assetRoot();
        return new WorldData.AssetSource() {
            @Override
            public InputStream open(String name) throws IOException {
                File f = new File(root, name);
                if (!f.isFile()) {
                    throw new IOException("packaged world layer missing: " + f.getAbsolutePath());
                }
                return new FileInputStream(f);
            }
        };
    }

    /** Loaded once and shared: parsing five layers per test method is wasteful. */
    public static synchronized WorldData get() {
        if (cached == null) {
            try {
                cached = WorldData.load(source());
            } catch (Exception e) {
                throw new IllegalStateException("could not load packaged world from "
                        + assetRoot().getAbsolutePath(), e);
            }
        }
        return cached;
    }
}
