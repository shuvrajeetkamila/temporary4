package ai.shuvrajeetkamila.mumbai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import ai.shuvrajeetkamila.mumbai.world.MwlReader;
import ai.shuvrajeetkamila.mumbai.world.WorldData;

/**
 * Guards the packaged world itself: the game ships its map inside the APK and has no
 * network at all, so a missing, truncated or stale layer is a shipped-broken build.
 * These tests read the real files from {@code android/src/main/assets/world}, the same
 * bytes the APK will contain.
 */
public class PackagedWorldTest {

    private static final String[] LAYERS = {
            "manifest.mwl", "terrain.mwl", "network.mwl", "city.mwl", "chunks.mwl"
    };

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private File worldDir() {
        return new File(TestWorld.assetRoot(), "world");
    }

    @Test
    public void everyLayerIsPresentAndNonTrivial() {
        File dir = worldDir();
        assertTrue("the packaged world directory is missing: " + dir.getAbsolutePath(),
                dir.isDirectory());
        for (String layer : LAYERS) {
            File f = new File(dir, layer);
            assertTrue("missing world layer " + layer, f.isFile());
            assertTrue("world layer " + layer + " is suspiciously small: " + f.length(),
                    f.length() > 256);
        }
    }

    @Test
    public void noPlaceholderOrScratchFilesAreShipped() {
        File[] files = worldDir().listFiles();
        assertNotNull(files);
        Set<String> allowed = new HashSet<>();
        for (String s : LAYERS) {
            allowed.add(s);
        }
        for (File f : files) {
            assertTrue("unexpected file in the packaged world: " + f.getName()
                            + " (placeholders and scratch files must not ship)",
                    allowed.contains(f.getName()));
        }
    }

    @Test
    public void everyLayerCarriesTheCorrectMagicAndVersion() throws Exception {
        for (String layer : LAYERS) {
            try (InputStream in = new FileInputStream(new File(worldDir(), layer))) {
                // Header is magic u32, layer id u32, format version u32.
                byte[] head = new byte[12];
                int n = in.read(head);
                assertEquals("short read on " + layer, 12, n);
                assertEquals(layer + " is not a .mwl file", MwlReader.MAGIC, le32(head, 0));
                assertEquals(layer + " has the wrong format version",
                        MwlReader.FORMAT_VERSION, le32(head, 8));
            }
        }
    }

    @Test
    public void theManifestAgreesWithTheDataItDescribes() {
        WorldData w = TestWorld.get();
        assertEquals("manifest land-use size disagrees with the grid",
                w.luW * w.luH, w.landUse.length);
        assertEquals("manifest elevation size disagrees with the grid",
                w.htW * w.htH, w.heightDm.length);
        assertEquals("manifest chunk count disagrees with the chunk table",
                w.chunksX * w.chunksZ, w.chunkFlags.length);
        assertEquals(w.chunkFlags.length, w.chunkLandFrac.length);
        assertEquals(w.chunkFlags.length, w.chunkDensity.length);
        assertEquals(w.chunkFlags.length, w.chunkBuildings.length);
        assertTrue("the AOI version is missing", w.aoiVersion != null && !w.aoiVersion.isEmpty());
        assertEquals("the manifest does not list every layer it ships",
                LAYERS.length - 1, w.layerNames.size());
    }

    @Test
    public void readingTheWorldTwiceGivesIdenticalData() throws Exception {
        WorldData a = WorldData.load(TestWorld.source());
        WorldData b = WorldData.load(TestWorld.source());

        assertEquals(a.landUse.length, b.landUse.length);
        for (int i = 0; i < a.landUse.length; i += 997) {
            assertEquals("land-use differs between loads at " + i, a.landUse[i], b.landUse[i]);
        }
        for (int i = 0; i < a.heightDm.length; i += 701) {
            assertEquals("elevation differs between loads at " + i,
                    a.heightDm[i], b.heightDm[i]);
        }
        assertEquals(a.roads.size(), b.roads.size());
        assertEquals(a.districts.size(), b.districts.size());
        assertEquals(a.landmarks.size(), b.landmarks.size());
        assertEquals(a.airports.size(), b.airports.size());
    }

    @Test
    public void decompressedLayersAreSelfConsistent() {
        WorldData w = TestWorld.get();

        // Height grid: no NaNs, no absurd values anywhere in the AOI.
        short min = Short.MAX_VALUE;
        short max = Short.MIN_VALUE;
        for (short v : w.heightDm) {
            min = (short) Math.min(min, v);
            max = (short) Math.max(max, v);
        }
        assertTrue("sea floor at " + (min / 10f) + " m is implausible", min / 10f > -90f);
        assertTrue("a summit at " + (max / 10f) + " m is implausible for Mumbai",
                max / 10f < 600f && max / 10f > 250f);

        // Land use: every byte must be a code we know how to draw.
        boolean[] used = new boolean[32];
        for (byte b : w.landUse) {
            int code = b & 0xFF;
            assertTrue("unknown land-use code " + code, code < 20);
            used[code] = true;
        }
        for (int code = 0; code < 20; code++) {
            assertTrue("land-use code " + code + " never appears, so the compiler "
                    + "or the legend is out of date", used[code]);
        }

        // Networks: no zero-length or runaway polylines.
        for (WorldData.Polyline r : w.roads) {
            assertTrue("road " + r.name + " has fewer than two points", r.pts.length >= 4);
            for (int i = 0; i + 1 < r.pts.length; i += 2) {
                assertTrue("road point outside the AOI",
                        r.pts[i] >= w.minX - 1 && r.pts[i] <= w.maxX + 1);
            }
        }
        assertTrue("no railways were packaged", w.rails.size() >= 5);
        assertFalse("no districts were packaged", w.districts.isEmpty());
    }

    @Test
    public void theWorldIsSmallEnoughToShipAndBigEnoughToBeReal() {
        long total = 0;
        for (String layer : LAYERS) {
            total += new File(worldDir(), layer).length();
        }
        // Not a size target: a lower bound catches a world that was silently gutted,
        // and a generous upper bound catches a runaway export.
        assertTrue("the packaged world is only " + total + " bytes, which cannot "
                + "describe 44 x 65 km of city", total > 100_000);
        assertTrue("the packaged world grew to " + total + " bytes", total < 220L * 1024 * 1024);
    }
}
