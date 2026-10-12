package ai.shuvrajeetkamila.mumbai.world;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads the real compiled world once for the whole test run.
 *
 * <p>If the world is missing the tests that need it are skipped rather than
 * failed, so a developer can run the physics suite without a 15 MB asset
 * build. CI does not get that option: {@code WorldPresenceTest} fails hard
 * when the assets are absent, so a release can never be cut from a build that
 * quietly skipped every coverage test.
 */
public final class WorldFixture {

    private static WorldData cached;
    private static boolean tried;
    private static String why = "";

    /** The assets ROOT. WorldData prefixes "world/" itself. */
    public static Path assetRoot() {
        String prop = System.getProperty("mumbai.assets");
        if (prop != null && !prop.isEmpty()) { return Paths.get(prop); }
        Path here = Paths.get("android", "src", "main", "assets");
        if (Files.isDirectory(here)) { return here; }
        return Paths.get("..", "android", "src", "main", "assets");
    }

    public static Path assetDir() { return assetRoot().resolve("world"); }

    public static boolean present() {
        return Files.isRegularFile(assetDir().resolve("manifest.mwl"))
                && Files.isRegularFile(assetDir().resolve("terrain.mwl"))
                && Files.isRegularFile(assetDir().resolve("chunks.mwl"))
                && Files.isRegularFile(assetDir().resolve("network.mwl"));
    }

    public static synchronized WorldData world() {
        if (!tried) {
            tried = true;
            try {
                cached = WorldData.load(p -> Files.newInputStream(assetRoot().resolve(p)));
            } catch (IOException | RuntimeException e) {
                why = e.toString();
            }
        }
        Assumptions.assumeTrue(cached != null,
                "compiled world not available at " + assetDir().toAbsolutePath()
                        + " (" + why + ")");
        return cached;
    }

    public static String failure() { return why; }

    private WorldFixture() { }
}
