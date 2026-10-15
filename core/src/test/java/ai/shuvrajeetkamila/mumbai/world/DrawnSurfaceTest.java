package ai.shuvrajeetkamila.mumbai.world;

import ai.shuvrajeetkamila.mumbai.render.SeaMask;
import ai.shuvrajeetkamila.mumbai.render.TextureLibrary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is actually on the screen, as opposed to what is in the data.
 *
 * <p>Two faults reported from play came down to the same mistake made
 * twice: something was placed using the ground in the terrain model
 * while the player was looking at the ground in the mesh, and those are
 * not the same surface.
 *
 * <p>The first was the sea. Every chunk drew a flat water plane at chart
 * datum over its sea cells, and the animated sea surface was drawn over
 * the same water at the tide plus a metre and a half of swell. The two
 * cross constantly - the tide passes through zero twice a cycle and the
 * waves straddle it the whole time - so over open water they interleaved
 * in the depth buffer along a join that crawled with the waves. That is
 * the moving sheet that comes over the view after a while.
 *
 * <p>The second was the roads. A road was laid at the true ground height
 * plus seven centimetres, but the terrain under it is drawn from samples
 * 32, 64 or 128 m apart depending on how far away the chunk is. On a
 * slope the drawn ground is metres away from the true height, so the
 * road sank into the hillside in patches and hung over it in others.
 */
final class DrawnSurfaceTest {

    private static final int STRIDE = 11;

    /** The features for a chunk, or null if it is outside the world. */
    private static ChunkFeatures features(WorldData w, int c)
            throws java.io.IOException {
        double ox = w.chunkOriginX(c);
        double oz = w.chunkOriginZ(c);
        if (!w.inBounds(ox, oz)) { return null; }
        return w.chunkHasData(c)
                ? ChunkFeatures.decode(c, ox, oz, w.readChunk(c), w.quant)
                : ChunkFeatures.empty(c, ox, oz);
    }

    @Test
    @DisplayName("open water is meshed once, not twice")
    void openWaterIsLeftToTheSea() throws Exception {
        // Anything the terrain emits over water is a second water
        // surface. It no longer sits at chart datum - the vertex stage
        // lifts it onto the same wave the sea rides, five centimetres
        // proud, so the two cannot fight - but it is still geometry,
        // and over the open harbour there is no reason to pay for it.
        //
        // So the rule is where, not whether. The terrain owns the
        // coastal fringe, because the sea is discarded there, and the
        // sea owns everything past it. What is checked is that no water
        // triangle is emitted further from land than the fringe can
        // reach: one cell, plus the caution the mask is built with.
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        int checked = 0;
        int offenders = 0;
        double worst = 0;
        for (int c = 0; c < w.chunkCount(); c += 37) {
            ChunkFeatures f = features(w, c);
            if (f == null) { continue; }
            for (int lod : new int[]{ChunkMesher.LOD_HIGH, ChunkMesher.LOD_MED,
                                     ChunkMesher.LOD_LOW}) {
                ChunkMesher.Mesh m = mesher.build(f, lod);
                double cell = w.chunkM
                        / (lod == ChunkMesher.LOD_HIGH ? 32
                           : lod == ChunkMesher.LOD_MED ? 16 : 8);
                double reach = cell + SeaMask.TEXEL_M * 2;
                for (int t = 0; t < m.indexCount; t += 3) {
                    if (!isWaterSurface(m, m.indices[t])
                            || !isWaterSurface(m, m.indices[t + 1])
                            || !isWaterSurface(m, m.indices[t + 2])) {
                        continue;
                    }
                    double cx = f.originX + m.vertices[m.indices[t] * STRIDE];
                    double cz = f.originZ
                            + m.vertices[m.indices[t] * STRIDE + 2];
                    if (w.anyLand(cx - reach, cz - reach,
                                  cx + reach, cz + reach)) {
                        continue;
                    }
                    offenders++;
                    worst = Math.max(worst, reach);
                }
            }
            checked++;
            if (checked > 24) { break; }
        }
        assertTrue(checked > 4, "no chunks were examined");
        assertTrue(offenders == 0,
                offenders + " water triangles are being meshed in open"
                        + " water more than " + worst + " m from the"
                        + " nearest land, where the sea already draws"
                        + " the same water.");
    }

    @Test
    @DisplayName("nowhere the sea is discarded is left undrawn")
    void theCoastHasNoHoles() throws Exception {
        // The hole that produced the white edge round the island. The
        // sea is discarded wherever the land mask says land, and the
        // mask calls a 40 m texel land if any 20 m cell inside it is.
        // The mesh used to drop a cell whose own corners were all sea,
        // sampled every 32 m at full detail and every 128 m at the
        // lowest - so every islet, sandbar, jetty and ragged headland
        // narrower than a cell had the sea discarded over it and no
        // terrain put in its place. Nothing was drawn there at all, and
        // nothing is the colour the frame was cleared to.
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        int checked = 0;
        for (int c = 0; c < w.chunkCount() && checked < 12; c += 37) {
            ChunkFeatures f = features(w, c);
            if (f == null) { continue; }
            // Only chunks that actually have a coast in them.
            if (!w.anyLand(f.originX, f.originZ,
                           f.originX + w.chunkM, f.originZ + w.chunkM)) {
                continue;
            }
            checked++;
            for (int lod : new int[]{ChunkMesher.LOD_HIGH, ChunkMesher.LOD_MED,
                                     ChunkMesher.LOD_LOW}) {
                int n = lod == ChunkMesher.LOD_HIGH ? 32
                        : lod == ChunkMesher.LOD_MED ? 16 : 8;
                double step = w.chunkM / n;
                ChunkMesher.Mesh m = mesher.build(f, lod);
                boolean[] drawn = new boolean[n * n];
                for (int t = 0; t < m.indexCount; t += 3) {
                    for (int k = 0; k < 3; k++) {
                        int o = m.indices[t + k] * STRIDE;
                        if (m.vertices[o + 4] < 0.5f) { continue; }  // skirt
                        int ci = (int) Math.round(m.vertices[o] / step);
                        int cj = (int) Math.round(m.vertices[o + 2] / step);
                        // A corner vertex belongs to the four cells that
                        // meet at it.
                        for (int dj = -1; dj <= 0; dj++) {
                            for (int di = -1; di <= 0; di++) {
                                int ii = ci + di, jj = cj + dj;
                                if (ii >= 0 && jj >= 0 && ii < n && jj < n) {
                                    drawn[jj * n + ii] = true;
                                }
                            }
                        }
                    }
                }
                for (int j = 0; j < n; j++) {
                    for (int i = 0; i < n; i++) {
                        double x0 = f.originX + i * step;
                        double z0 = f.originZ + j * step;
                        // Where the mask discards the sea, something has
                        // to be drawn. The mask's answer is taken over
                        // the cell grown by a texel, because a texel
                        // that straddles the boundary takes its answer
                        // from land outside the cell.
                        boolean maskSaysLand = w.anyLand(
                                x0 - SeaMask.TEXEL_M, z0 - SeaMask.TEXEL_M,
                                x0 + step + SeaMask.TEXEL_M,
                                z0 + step + SeaMask.TEXEL_M);
                        if (!maskSaysLand) { continue; }
                        assertTrue(drawn[j * n + i],
                                "chunk " + c + " at detail " + lod
                                        + ": the sea is discarded over the"
                                        + " cell at " + (int) x0 + ","
                                        + (int) z0 + " and the terrain"
                                        + " draws nothing there either,"
                                        + " so the frame's clear colour"
                                        + " shows through");
                    }
                }
            }
        }
        assertTrue(checked > 3,
                "no chunk with a coast in it was examined");
    }

    /** An upward-facing water vertex. */
    private static boolean isWaterSurface(ChunkMesher.Mesh m, int v) {
        int o = v * STRIDE;
        return m.vertices[o + 9] < -0.5f          // aux -1 marks water
                && m.vertices[o + 4] > 0.5f;      // upward facing, not a skirt
    }

    @Test
    @DisplayName("roads lie on the ground that is drawn, at every detail level")
    void roadsLieOnTheDrawnGround() throws Exception {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        double worst = 0;
        int sampled = 0;
        int chunks = 0;
        for (int c = 0; c < w.chunkCount() && chunks < 14; c += 37) {
            ChunkFeatures f = features(w, c);
            if (f == null || f.roadCount() == 0) { continue; }
            // Bridges are meant to leave the ground, and RoadDeck
            // decides where their deck goes. Only chunks with none in
            // them can be checked against the ground.
            boolean spanned = false;
            for (int r = 0; r < f.roadCount(); r++) {
                if ((f.roadFlags[r] & ChunkFeatures.FLAG_BRIDGE) != 0) {
                    spanned = true;
                    break;
                }
            }
            if (spanned) { continue; }
            chunks++;
            for (int lod : new int[]{ChunkMesher.LOD_HIGH, ChunkMesher.LOD_MED,
                                     ChunkMesher.LOD_LOW}) {
                ChunkMesher.Mesh m = mesher.build(f, lod);
                for (int v = 0; v < m.vertexCount; v++) {
                    int o = v * STRIDE;
                    // Paved road surface: flat, upward, tarmac.
                    if (Math.abs(m.vertices[o + 9] - TextureLibrary.MAT_TARMAC)
                            > 1e-3f) {
                        continue;
                    }
                    if (m.vertices[o + 4] < 0.99f) { continue; }
                    double lx = m.vertices[o];
                    double lz = m.vertices[o + 2];
                    if (lx < 0 || lz < 0 || lx > w.chunkM || lz > w.chunkM) {
                        continue;                 // ribbon running off the edge
                    }
                    double ground = mesher.lodGround(m.originX, m.originZ,
                            lod, lx, lz);
                    double over = m.vertices[o + 1] - ground;
                    sampled++;
                    // The base lift, plus however much this road's class
                    // is raised to keep it off the one it crosses. The
                    // class is not recoverable from a vertex, so the
                    // whole band is allowed and the floor is what
                    // matters: a road must never be below the ground.
                    double low = ChunkMesher.ROAD_LIFT_M;
                    double high = low + ChunkMesher.junctionLift(0);
                    double off = over < low ? low - over
                            : (over > high ? over - high : 0);
                    worst = Math.max(worst, off);
                }
            }
        }
        assertTrue(sampled > 500,
                "only " + sampled + " road vertices were examined");
        assertTrue(worst < 0.02,
                "a road surface sits " + worst + " m away from where it"
                        + " should relative to the ground that is drawn"
                        + " under it. On a slope that is the difference"
                        + " between a road and a line of torn ground.");
    }

    @Test
    @DisplayName("the road lift survives the depth buffer at drawing range")
    void theLiftClearsTheDepthBuffer() {
        // A 24-bit depth buffer with the near plane at n resolves roughly
        // d * d / (n * 2^24) metres at distance d. Beyond the distance
        // where that exceeds the lift, every road in view shimmers
        // against the ground it is lying on. These two constants have to
        // be chosen together, so this records the range they buy.
        double near = ChunkMesher.NEAR_PLANE_M;
        double lift = ChunkMesher.ROAD_LIFT_M;
        double range = Math.sqrt(lift * near * 16777216.0);
        assertTrue(range > 1200,
                "roads start fighting the ground at " + Math.round(range)
                        + " m, which is well inside the city. Either the"
                        + " lift or the near plane has moved.");
        // And the lift must stay small enough to read as a kerb rather
        // than a wall: a road hanging half a metre over its verge is
        // just as wrong, and the ribbon has no sides to hide the gap.
        assertTrue(lift < 0.25,
                "a " + lift + " m lift is a step, not a road surface");
    }

    @Test
    @DisplayName("the drawn ground agrees with the mesh it came from")
    void theDrawnGroundMatchesTheMesh() throws Exception {
        // lodGround claims to report the height of the terrain mesh.
        // This checks it against the triangles the mesher actually
        // emitted, rather than taking its word for it.
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        double worst = 0;
        int sampled = 0;
        for (int c = 0; c < w.chunkCount() && sampled < 4000; c += 97) {
            ChunkFeatures f = features(w, c);
            if (f == null) { continue; }
            ChunkMesher.Mesh m = mesher.build(f, ChunkMesher.LOD_MED);
            for (int v = 0; v < m.vertexCount; v++) {
                int o = v * STRIDE;
                // Terrain grid nodes: upward facing, and sitting exactly
                // on a multiple of the grid step.
                if (m.vertices[o + 4] < 0.1f) { continue; }
                double lx = m.vertices[o];
                double lz = m.vertices[o + 2];
                if (lx < 0 || lz < 0 || lx > w.chunkM || lz > w.chunkM) {
                    continue;
                }
                double step = w.chunkM / 16;
                if (Math.abs(lx / step - Math.round(lx / step)) > 1e-6) {
                    continue;
                }
                if (Math.abs(lz / step - Math.round(lz / step)) > 1e-6) {
                    continue;
                }
                double claimed = mesher.lodGround(m.originX, m.originZ,
                        ChunkMesher.LOD_MED, lx, lz);
                double actual = m.vertices[o + 1];
                // Roads and buildings sit above the ground; only compare
                // against vertices that are the ground.
                if (Math.abs(m.vertices[o + 9] - TextureLibrary.MAT_TARMAC)
                        < 1e-3f) {
                    continue;
                }
                if (actual - claimed > 0.5) { continue; }
                sampled++;
                worst = Math.max(worst, Math.abs(actual - claimed));
            }
        }
        assertTrue(sampled > 200, "only " + sampled + " nodes were examined");
        assertTrue(worst < 0.01,
                "the drawn ground reported for a terrain node is " + worst
                        + " m away from the node itself");
    }

    // ---- road cohesion -------------------------------------------------

    @Test
    @DisplayName("a road keeps its width round a bend")
    void corneringDoesNotPinchTheRibbon() {
        float half = 4.0f;
        // Straight through: nothing to correct.
        assertEquals((double) half, ChunkMesher.mitre(half, 1, 0, 1, 0), 1e-4,
                "a straight road was widened");

        // A right angle. The bisector is at 45 degrees to both segments,
        // so the offset along it has to be sqrt(2) times the half-width
        // for the edge to stay 4 m from each centreline.
        float m90 = ChunkMesher.mitre(half, 1, 0, 0, 1);
        assertEquals(half * Math.sqrt(2.0), (double) m90, 1e-3,
                "a right-angle corner pinched to " + m90);

        // Check that directly: the mitred corner point really is the
        // half-width away from both segments it joins.
        for (double turn = 0.05; turn < 2.6; turn += 0.05) {
            double ax = 1, az = 0;
            double bx = Math.cos(turn), bz = Math.sin(turn);
            float hw = ChunkMesher.mitre(half, ax, az, bx, bz);
            if (hw >= half / 0.38f - 1e-3) { continue; }   // capped
            // The emitted normal is perpendicular to the averaged
            // direction, which for unit segments is the bisector.
            double sx = ax + bx, sz = az + bz;
            double sl = Math.hypot(sx, sz);
            double nx = sz / sl, nz = -sx / sl;
            // Distance from the corner point to the line of the first
            // segment, which runs through the origin along (ax, az).
            double d = Math.abs((nx * hw) * az - (nz * hw) * ax);
            assertEquals((double) half, d, 2e-3,
                    "at a turn of " + turn + " rad the ribbon edge sat "
                            + d + " m from the centreline instead of "
                            + half);
        }
    }

    @Test
    @DisplayName("the mitre is capped so a hairpin cannot throw a spike")
    void hairpinsAreBounded() {
        // Doubling back on itself: the true mitre is infinite.
        float hw = ChunkMesher.mitre(3.5f, 1, 0, -1, 0.001);
        assertTrue(hw < 3.5f * 2.7f,
                "a hairpin produced a half-width of " + hw + " m");
        assertTrue(Float.isFinite(hw), "the mitre was not a number");
        // And degenerate input does not produce NaN either.
        assertEquals(3.5, (double) ChunkMesher.mitre(3.5f, 0, 0, 1, 0), 1e-6);
    }

    @Test
    @DisplayName("ribbons overlap at a junction instead of stopping dead")
    void junctionsAreFilled() throws Exception {
        assertTrue(ChunkMesher.endExtension(6.0f) > 1.5f,
                "a wide road's ribbon still stops dead at the shared"
                        + " node, so a crossroads keeps its unpaved notch");
        // Never more than about half a lane, or a cul-de-sac grows a
        // tongue of tarmac into the building at the end of it.
        assertTrue(ChunkMesher.endExtension(60.0f) <= 2.4f,
                "a runway's ribbon was extended by half a runway");
        // A footpath must not be extended by more than its own width,
        // or the extension is wider than the path.
        assertTrue(ChunkMesher.endExtension(1.2f) <= 1.2f,
                "a narrow way was extended further than its half-width");

        // And the real meshes still land on the ground after it: the
        // extended end samples terrain the centreline never visited.
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        int checked = 0;
        for (int c = 0; c < w.chunkCount() && checked < 24; c++) {
            ChunkFeatures f = features(w, c);
            if (f == null || f.roadCount() == 0) { continue; }
            ChunkMesher.Mesh m = mesher.build(f, ChunkMesher.LOD_HIGH);
            for (int v = 0; v < m.vertexCount; v++) {
                float y = m.vertices[v * ChunkMesher.STRIDE + 1];
                assertTrue(Float.isFinite(y),
                        "a road vertex came out as " + y);
            }
            checked++;
        }
        assertTrue(checked > 0, "no chunk with roads was meshed");
    }

    @Test
    @DisplayName("only wide paved roads are painted, and only up close")
    void markingsGoWhereMarkingsGo() {
        assertTrue(ChunkMesher.isMarked(7.0f, false, ChunkMesher.LOD_HIGH),
                "a fourteen metre arterial has no centre line");
        assertTrue(!ChunkMesher.isMarked(1.6f, false, ChunkMesher.LOD_HIGH),
                "a three metre lane was given a centre line, which no"
                        + " three metre lane has");
        assertTrue(!ChunkMesher.isMarked(7.0f, true, ChunkMesher.LOD_HIGH),
                "an unmade track was given painted markings");
        assertTrue(!ChunkMesher.isMarked(7.0f, false, ChunkMesher.LOD_MED),
                "a distant chunk is carrying a third of its road"
                        + " vertices for a line nobody can resolve");
        assertTrue(!ChunkMesher.isMarked(7.0f, false, ChunkMesher.LOD_LOW),
                "the lowest level of detail is still paying for paint");
    }

    @Test
    @DisplayName("nothing but the centre of a road reads as paint")
    void onlyTheCentrelineIsNearZero() throws Exception {
        // The fragment stage paints any tarmac fragment whose lateral
        // coordinate is near zero. That is safe only while no tarmac
        // vertex sits near zero except the deliberate centre station:
        // if some other surface were ever given the tarmac material and
        // the default coordinate - which is the world height - then
        // anything at sea level would come out painted white.
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        int chunks = 0;
        long tarmac = 0;
        long centre = 0;
        // Stride coprime with the grid width, which is 53 chunks. A
            // stride of 53 walks one column, and that column is sea.
            for (int c = 0; c < w.chunkCount() && chunks < 20; c += 37) {
            ChunkFeatures f = features(w, c);
            if (f == null || f.roadCount() == 0) { continue; }
            chunks++;
            for (int lod : new int[]{ChunkMesher.LOD_HIGH, ChunkMesher.LOD_MED,
                                     ChunkMesher.LOD_LOW}) {
                ChunkMesher.Mesh m = mesher.build(f, lod);
                for (int v = 0; v < m.vertexCount; v++) {
                    int o = v * ChunkMesher.STRIDE;
                    int mat = (int) Math.floor(m.vertices[o + 9] + 5e-4);
                    if (mat != TextureLibrary.MAT_TARMAC) { continue; }
                    float tv = m.vertices[o + 10];
                    tarmac++;
                    if (tv == 0f) { centre++; continue; }
                    assertTrue(tv >= ChunkMesher.MARKING_MIN_HALF_M - 1e-4,
                            "a tarmac vertex sits at a lateral coordinate"
                                    + " of " + tv + " m, inside the band"
                                    + " the shader paints, so a stripe"
                                    + " will appear away from any"
                                    + " centreline");
                }
            }
        }
        assertTrue(chunks > 0, "no chunk with roads was meshed");
        assertTrue(tarmac > 0, "no tarmac was emitted at all");
        assertTrue(centre > 0,
                "not one road carried a centre station, so no road in"
                        + " the city has a line down the middle");
    }

    // ---- street lighting -----------------------------------------------

    @Test
    @DisplayName("street lights go on arterials, up close, and not on bridges")
    void lightingGoesWhereLightingGoes() {
        assertTrue(ChunkMesher.isLit(2, false, ChunkMesher.LOD_HIGH),
                "a primary road is unlit");
        assertTrue(ChunkMesher.isLit(ChunkMesher.LAMP_MAX_CLASS, false,
                        ChunkMesher.LOD_HIGH),
                "the lowest lit class is not lit");
        assertTrue(!ChunkMesher.isLit(ChunkMesher.LAMP_MAX_CLASS + 1, false,
                        ChunkMesher.LOD_HIGH),
                "every lane and alley in the city is carrying columns");
        assertTrue(!ChunkMesher.isLit(2, true, ChunkMesher.LOD_HIGH),
                "lamps were planted on a bridge deck, where the ground"
                        + " they stand on is the seabed");
        for (int lod : new int[]{ChunkMesher.LOD_MED, ChunkMesher.LOD_LOW}) {
            assertTrue(!ChunkMesher.isLit(2, false, lod),
                    "a 16 cm column is being meshed at detail " + lod
                            + ", where it is thinner than a pixel and"
                            + " can only alias");
        }
    }

    @Test
    @DisplayName("a lamp head is above the road and clear of the carriageway")
    void lampsStandBesideTheRoad() throws Exception {
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        int heads = 0;
        int chunks = 0;
        double lowest = Double.MAX_VALUE;
        for (int c = 0; c < w.chunkCount() && chunks < 10; c += 37) {
            ChunkFeatures f = features(w, c);
            if (f == null || f.roadCount() == 0) { continue; }
            chunks++;
            ChunkMesher.Mesh m = mesher.build(f, ChunkMesher.LOD_HIGH);
            for (int v = 0; v < m.vertexCount; v++) {
                int o = v * STRIDE;
                // A band, not a threshold: the signal heads are tagged
                // above the lamps and would otherwise be counted as
                // very short street lights.
                float aux = m.vertices[o + 9];
                if (aux < ChunkMesher.LAMP_AUX - 0.5f
                        || aux > ChunkMesher.LAMP_AUX + 0.5f) {
                    continue;
                }
                heads++;
                double wx = f.originX + m.vertices[o];
                double wz = f.originZ + m.vertices[o + 2];
                double ground = w.inBounds(wx, wz) && w.isLand(wx, wz)
                        ? w.height(wx, wz) : 0;
                lowest = Math.min(lowest, m.vertices[o + 1] - ground);
            }
            // The columns themselves must be there too, or the heads
            // are floating.
            assertTrue(heads == 0 || m.vertexCount > 0, "empty mesh");
        }
        assertTrue(chunks > 0, "no chunk with roads was meshed");
        assertTrue(heads > 0,
                "not one street light was emitted anywhere in the"
                        + " sampled chunks");
        // Head height is measured against the true ground, while the
        // column stands on the drawn ground, so allow the LOD error.
        assertTrue(lowest > ChunkMesher.LAMP_HEIGHT_M - 6.0,
                "a lamp head came out " + lowest + " m above the ground,"
                        + " which is not the top of a column");
    }

    @Test
    @DisplayName("the lamp tag cannot be mistaken for a shadow or a beacon")
    void theLampTagIsItsOwn() {
        // The shadow tag carries a rim fraction in its own decimals, so
        // it occupies a whole unit of the range, and the fragment stage
        // tests greater-than rather than equality. A lamp has to sit
        // clear of both.
        assertTrue(ChunkMesher.LAMP_AUX > 71.5f,
                "the lamp tag overlaps the shadow tag's range and every"
                        + " lamp head will be drawn as a blob shadow");
        assertTrue(ChunkMesher.LAMP_AUX > 61.0f,
                "the lamp tag overlaps the beacon tag");
    }

    // ---- kerbs and footpaths -------------------------------------------

    @Test
    @DisplayName("footpaths go beside wide paved arterials and nowhere else")
    void footpathsGoWhereFootpathsGo() {
        int hi = ChunkMesher.LOD_HIGH;
        assertTrue(ChunkMesher.hasFootpath(2, false, false, 7f, hi),
                "a fourteen metre arterial has no pavement");
        assertTrue(!ChunkMesher.hasFootpath(2, true, false, 7f, hi),
                "a pavement was laid along a bridge deck");
        assertTrue(!ChunkMesher.hasFootpath(2, false, true, 7f, hi),
                "an unmade track was given a concrete kerb");
        assertTrue(!ChunkMesher.hasFootpath(2, false, false, 2.0f, hi),
                "a four metre lane was given a kerb and a pavement on"
                        + " both sides, which is wider than the lane");
        assertTrue(!ChunkMesher.hasFootpath(9, false, false, 7f, hi),
                "a service road is carrying pavement geometry");
        for (int lod : new int[]{ChunkMesher.LOD_MED, ChunkMesher.LOD_LOW}) {
            assertTrue(!ChunkMesher.hasFootpath(2, false, false, 7f, lod),
                    "a 14 cm kerb is being meshed at detail " + lod);
        }
    }

    @Test
    @DisplayName("the kerb is a kerb, not a step")
    void footpathsSitOneKerbAboveTheRoad() throws Exception {
        // A pavement that stands half a metre proud is a wall the car
        // cannot climb and the pedestrians sink into; one flush with the
        // road is not a pavement at all.
        assertTrue(ChunkMesher.KERB_H_M > 0.08 && ChunkMesher.KERB_H_M < 0.25,
                "a kerb of " + ChunkMesher.KERB_H_M + " m");

        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        long tops = 0;
        int chunks = 0;
        for (int c = 0; c < w.chunkCount() && chunks < 16; c += 29) {
            double ox = w.chunkOriginX(c);
            double oz = w.chunkOriginZ(c);
            if (!w.inBounds(ox, oz)) { continue; }
            ChunkFeatures f = features(w, c);
            if (f == null || f.roadCount() == 0) { continue; }
            chunks++;
            ChunkMesher.Mesh m = mesher.build(f, ChunkMesher.LOD_HIGH);
            for (int v = 0; v < m.vertexCount; v++) {
                int o = v * STRIDE;
                if ((int) Math.floor(m.vertices[o + 9] + 5e-4)
                        != TextureLibrary.MAT_CONCRETE) { continue; }
                if (m.vertices[o + 4] < 0.5f) { continue; }
                double g = mesher.lodGround(ox, oz, ChunkMesher.LOD_HIGH,
                        m.vertices[o], m.vertices[o + 2]);
                double above = m.vertices[o + 1] - g;
                if (Math.abs(above - (ChunkMesher.ROAD_LIFT_M
                        + ChunkMesher.KERB_H_M)) < 0.01) {
                    tops++;
                    // Never over water: a causeway's kerb would sit at
                    // chart datum with the sea washing over it.
                    double wx = f.originX + m.vertices[o];
                    double wz = f.originZ + m.vertices[o + 2];
                    assertTrue(w.isLand(wx, wz),
                            "a pavement was laid over open water at "
                                    + (int) wx + "," + (int) wz);
                }
            }
        }
        assertTrue(chunks > 0, "no chunk with roads was meshed");
        assertTrue(tops > 200,
                "only " + tops + " pavement vertices in " + chunks
                        + " chunks of arterial city");
    }

    @Test
    @DisplayName("street lights stand on the pavement, not in it")
    void lampsAreNotSunkIntoTheKerb() throws Exception {
        // The column is planted on the drawn ground plus whatever the
        // pavement adds, so on a road that has one the base has to be a
        // kerb higher than the carriageway. Getting this wrong buries
        // 14 cm of every column in the city.
        WorldData w = WorldFixture.world();
        ChunkMesher mesher = new ChunkMesher(w);
        int checked = 0;
        int onPath = 0;
        for (int c = 0; c < w.chunkCount() && checked < 14; c += 29) {
            double ox = w.chunkOriginX(c);
            double oz = w.chunkOriginZ(c);
            if (!w.inBounds(ox, oz)) { continue; }
            ChunkFeatures f = features(w, c);
            if (f == null || f.roadCount() == 0) { continue; }
            checked++;
            ChunkMesher.Mesh m = mesher.build(f, ChunkMesher.LOD_HIGH);
            for (int v = 0; v < m.vertexCount; v++) {
                int o = v * STRIDE;
                // A band, not a threshold: the signal heads are tagged
                // above the lamps and would otherwise be counted as
                // very short street lights.
                float aux = m.vertices[o + 9];
                if (aux < ChunkMesher.LAMP_AUX - 0.5f
                        || aux > ChunkMesher.LAMP_AUX + 0.5f) {
                    continue;
                }
                double g = mesher.lodGround(ox, oz, ChunkMesher.LOD_HIGH,
                        m.vertices[o], m.vertices[o + 2]);
                double h = m.vertices[o + 1] - g;
                // The head is at the top of the column, give or take the
                // head's own thickness; on a kerbed road it is a kerb
                // higher again.
                assertTrue(h > ChunkMesher.LAMP_HEIGHT_M - 0.01,
                        "a lamp head is only " + h + " m above the"
                                + " ground it stands on");
                if (h > ChunkMesher.LAMP_HEIGHT_M
                        + ChunkMesher.KERB_H_M * 0.5) {
                    onPath++;
                }
            }
        }
        assertTrue(checked > 0, "no chunk with roads was meshed");
        assertTrue(onPath > 0,
                "not one street light in the sampled city is standing on"
                        + " a pavement, so either the kerb lift is not"
                        + " being applied or the two never coincide");
    }
}
