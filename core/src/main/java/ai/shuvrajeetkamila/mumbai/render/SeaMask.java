package ai.shuvrajeetkamila.mumbai.render;

import ai.shuvrajeetkamila.mumbai.world.WorldData;

/**
 * Where the sea is allowed to be drawn.
 *
 * <p>The sea is a surface at mean level plus the tide, and the tide is a
 * real one: a shade under two metres either way, which is what Mumbai
 * Harbour does. Six per cent of the land in the 20 m terrain model sits
 * below that line - the reclaimed ground along Back Bay, the dock aprons,
 * the low ground behind Mahim - so at high water the sea was drawn over
 * the city. Where it stood clear of the ground you got open water in the
 * middle of a street; where it passed within a few centimetres of the
 * ground the two surfaces fought for the depth buffer and striped the
 * road with shimmering lines.
 *
 * <p>The land mask has always been the single authority for where the
 * coast is - the boat physics has used it since the first boat. This
 * hands the same mask to the fragment shader, which discards any sea
 * pixel that falls on land.
 *
 * <p>The transform is here, in the simulation, rather than in the
 * renderer, for one reason: the renderer works in <b>render space</b>,
 * which is world space minus the floating origin. Deriving a texture
 * coordinate from a world constant and a render-space position is
 * exactly the mistake the floating origin exists to prevent, and it
 * would not have shown up until the player had driven far enough for the
 * origin to rebase - at which point the coastline would have silently
 * slid out from under the sea.
 */
public final class SeaMask {

    /** Metres per texel. Coarser than the mask; the terrain draws the edge. */
    public static final double TEXEL_M = 40.0;

    private SeaMask() { }

    /**
     * Fills the shader's {@code uLandXform}: origin in render space in
     * {@code xy}, one over the mask's span in {@code zw}.
     *
     * @param offsetX the floating origin's current easting offset
     * @param offsetZ the floating origin's current southing offset
     */
    public static void transform(WorldData w, double offsetX, double offsetZ,
                                 float[] out) {
        out[0] = (float) (w.minX - offsetX);
        out[1] = (float) (w.minZ - offsetZ);
        out[2] = (float) (1.0 / w.maskSpanX());
        out[3] = (float) (1.0 / w.maskSpanZ());
    }

    /** The texture coordinate the shader will compute, for testing. */
    public static double u(float[] xform, double renderX) {
        return (renderX - xform[0]) * xform[2];
    }

    /** The texture coordinate the shader will compute, for testing. */
    public static double v(float[] xform, double renderZ) {
        return (renderZ - xform[1]) * xform[3];
    }
}
