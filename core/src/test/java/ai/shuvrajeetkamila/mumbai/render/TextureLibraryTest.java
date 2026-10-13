package ai.shuvrajeetkamila.mumbai.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The atlas has to tile, stay in range, and be the same every time. */
final class TextureLibraryTest {

    private static int px(byte[] t, int x, int y, int c) {
        return t[(y * TextureLibrary.SIZE + x) * 4 + c] & 0xFF;
    }

    @Test
    @DisplayName("every material produces a full tile")
    void allMaterialsProduceTiles() {
        for (int m = 0; m < TextureLibrary.MAT_COUNT; m++) {
            byte[] t = TextureLibrary.tile(m);
            assertEquals(TextureLibrary.SIZE * TextureLibrary.SIZE * 4, t.length,
                    "material " + m + " wrong size");
        }
    }

    /**
     * The seam test, and the reason the noise wraps at all.
     *
     * <p>UVs come from world position, so one tile repeats across a kilometre
     * of road. If the right-hand column does not continue into the left-hand
     * one, the result is not a subtle artefact: it is a visible grid drawn
     * over the entire city, once every few metres, in every direction.
     *
     * <p>The threshold is measured against the tile's own internal roughness
     * rather than being an absolute number. A noisy tile is allowed a noisy
     * seam; what is not allowed is a seam that is <i>worse</i> than the
     * texture's ordinary neighbour-to-neighbour variation, because that is
     * what the eye picks out as a line.
     */
    /**
     * The seam test, and the reason the noise wraps at all.
     *
     * <p>UVs come from world position, so one tile repeats across a
     * kilometre of road. If the right-hand column does not continue into the
     * left-hand one, the result is not a subtle artefact: it is a visible
     * grid drawn over the entire city, once every few metres, in every
     * direction.
     *
     * <p>The reference is the <i>harshest transition already inside the
     * tile</i>, not an absolute number and not an arbitrary interior row.
     * Several of these textures are deliberately banded - a tiled roof has a
     * shadow line under every lap, a chawl has a painted plinth at each
     * storey - and a band edge that happens to land on the tile boundary is
     * correct tiling, not a seam. What would be a bug is a join sharper than
     * anything the pattern does in its own interior. An earlier version of
     * this test sampled one interior row, hit a smooth part of the chawl,
     * and reported a seam that was really just the plinth.
     */
    @Test
    @DisplayName("tiles are seamless in both directions")
    void tilesAreSeamless() {
        int n = TextureLibrary.SIZE;
        for (int m = 0; m < TextureLibrary.MAT_COUNT; m++) {
            byte[] t = TextureLibrary.tile(m);

            double seamH = 0;
            for (int y = 0; y < n; y++) {
                for (int c = 0; c < 3; c++) {
                    seamH += Math.abs(px(t, n - 1, y, c) - px(t, 0, y, c));
                }
            }
            double worstH = 0;
            for (int x = 0; x < n - 1; x++) {
                double col = 0;
                for (int y = 0; y < n; y++) {
                    for (int c = 0; c < 3; c++) {
                        col += Math.abs(px(t, x, y, c) - px(t, x + 1, y, c));
                    }
                }
                worstH = Math.max(worstH, col);
            }

            double seamV = 0;
            for (int x = 0; x < n; x++) {
                for (int c = 0; c < 3; c++) {
                    seamV += Math.abs(px(t, x, n - 1, c) - px(t, x, 0, c));
                }
            }
            double worstV = 0;
            for (int y = 0; y < n - 1; y++) {
                double row = 0;
                for (int x = 0; x < n; x++) {
                    for (int c = 0; c < 3; c++) {
                        row += Math.abs(px(t, x, y, c) - px(t, x, y + 1, c));
                    }
                }
                worstV = Math.max(worstV, row);
            }

            // A little slack plus a floor, so a perfectly smooth tile is not
            // held to a zero-tolerance comparison against its own noise.
            assertTrue(seamH <= worstH * 1.20 + 3.0 * n,
                    "material " + m + " horizontal seam " + (int) seamH
                            + " is sharper than its worst interior column "
                            + (int) worstH);
            assertTrue(seamV <= worstV * 1.20 + 3.0 * n,
                    "material " + m + " vertical seam " + (int) seamV
                            + " is sharper than its worst interior row "
                            + (int) worstV);
        }
    }

    @Test
    @DisplayName("the same atlas is produced on every run")
    void generationIsDeterministic() {
        for (int m = 0; m < TextureLibrary.MAT_COUNT; m++) {
            byte[] a = TextureLibrary.tile(m);
            byte[] b = TextureLibrary.tile(m);
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) {
                    throw new AssertionError("material " + m + " differs at byte " + i);
                }
            }
        }
    }

    /**
     * Alpha is the emissive mask, not opacity. The facades that are supposed
     * to have lit windows must actually have some, and the ground materials
     * must have none - a road that glows at night would be very obvious.
     */
    @Test
    @DisplayName("only window materials carry an emissive mask")
    void emissiveMaskIsOnlyOnWindows() {
        int[] shouldGlow = {
            TextureLibrary.MAT_FACADE_OFFICE,
            TextureLibrary.MAT_FACADE_APARTMENT,
            TextureLibrary.MAT_FACADE_CHAWL,
            TextureLibrary.MAT_FACADE_SHOP,
        };
        for (int m : shouldGlow) {
            byte[] t = TextureLibrary.tile(m);
            int lit = 0;
            for (int i = 0; i < TextureLibrary.SIZE * TextureLibrary.SIZE; i++) {
                if ((t[i * 4 + 3] & 0xFF) > 128) { lit++; }
            }
            double frac = lit / (double) (TextureLibrary.SIZE * TextureLibrary.SIZE);
            assertTrue(frac > 0.04,
                    "material " + m + " has almost no window area: " + frac);
            assertTrue(frac < 0.80,
                    "material " + m + " is nearly all window: " + frac);
        }

        int[] shouldNotGlow = {
            TextureLibrary.MAT_TARMAC, TextureLibrary.MAT_DIRT,
            TextureLibrary.MAT_GRASS, TextureLibrary.MAT_SAND,
            TextureLibrary.MAT_ROCK, TextureLibrary.MAT_CONCRETE,
            TextureLibrary.MAT_ROOF_CONCRETE, TextureLibrary.MAT_ROOF_TIN,
            TextureLibrary.MAT_ROOF_TILE,
        };
        for (int m : shouldNotGlow) {
            byte[] t = TextureLibrary.tile(m);
            for (int i = 0; i < TextureLibrary.SIZE * TextureLibrary.SIZE; i++) {
                assertEquals(0, t[i * 4 + 3] & 0xFF,
                        "material " + m + " must not be emissive");
            }
        }
    }

    /**
     * These multiply the per-vertex albedo, so a tile that averages far from
     * white would wash the colour out of the map data the mesher worked out
     * from OSM classes.
     */
    @Test
    @DisplayName("tiles stay near neutral so albedo still shows through")
    void tilesAreRoughlyNeutral() {
        for (int m = 0; m < TextureLibrary.MAT_COUNT; m++) {
            if (m == TextureLibrary.MAT_FACADE_OFFICE
                    || m == TextureLibrary.MAT_FACADE_SHOP) {
                continue;   // deliberately dark glass and bright signage
            }
            if (m == TextureLibrary.MAT_NONE) {
                continue;   // pure white on purpose: it is the no-op material
            }
            byte[] t = TextureLibrary.tile(m);
            long sum = 0;
            for (int i = 0; i < TextureLibrary.SIZE * TextureLibrary.SIZE; i++) {
                sum += (t[i * 4] & 0xFF) + (t[i * 4 + 1] & 0xFF) + (t[i * 4 + 2] & 0xFF);
            }
            double mean = sum / (double) (TextureLibrary.SIZE * TextureLibrary.SIZE * 3);
            assertTrue(mean > 90 && mean < 240,
                    "material " + m + " mean brightness " + mean
                            + " would overwhelm the albedo");
        }
    }

    @Test
    @DisplayName("a scale is defined for every material")
    void scalesAreSaneAndComplete() {
        assertEquals(TextureLibrary.MAT_COUNT, TextureLibrary.SCALE_M.length);
        for (int m = 0; m < TextureLibrary.MAT_COUNT; m++) {
            assertTrue(TextureLibrary.SCALE_M[m] > 0.5f
                            && TextureLibrary.SCALE_M[m] < 40f,
                    "material " + m + " scale " + TextureLibrary.SCALE_M[m]);
        }
        // Facades must repeat on an exact multiple of the 3.1 m storey the
        // mesher uses, or windows slice through floor slabs.
        float storey = 3.1f;
        int[] facades = {
            TextureLibrary.MAT_FACADE_OFFICE, TextureLibrary.MAT_FACADE_APARTMENT,
            TextureLibrary.MAT_FACADE_CHAWL, TextureLibrary.MAT_FACADE_SHOP,
        };
        for (int m : facades) {
            double floors = TextureLibrary.SCALE_M[m] / storey;
            assertEquals(Math.round(floors), floors, 1e-4,
                    "facade " + m + " is " + floors + " storeys per tile");
        }
    }
}
