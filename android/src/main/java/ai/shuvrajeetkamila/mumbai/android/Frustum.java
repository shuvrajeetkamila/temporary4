package ai.shuvrajeetkamila.mumbai.android;

/**
 * View frustum culling.
 *
 * <p>Without it the renderer submits every resident chunk every frame,
 * including the two thirds of them behind the camera, and a phone spends most
 * of its budget on geometry that is never seen.
 */
final class Frustum {

    private final float[][] planes = new float[6][4];

    /** Extract the six planes from a view-projection matrix. */
    void set(float[] m) {
        // Gribb and Hartmann: rows of the combined matrix, in column-major
        // storage, so m[col * 4 + row].
        for (int i = 0; i < 3; i++) {
            int sign = 1;
            for (int s = 0; s < 2; s++) {
                int p = i * 2 + s;
                for (int r = 0; r < 4; r++) {
                    planes[p][r] = m[r * 4 + 3] + sign * m[r * 4 + i];
                }
                sign = -1;
            }
        }
        for (float[] pl : planes) {
            double len = Math.sqrt(pl[0] * pl[0] + pl[1] * pl[1] + pl[2] * pl[2]);
            if (len > 1e-9) {
                pl[0] /= (float) len;
                pl[1] /= (float) len;
                pl[2] /= (float) len;
                pl[3] /= (float) len;
            }
        }
    }

    /** True if any part of the sphere could be visible. */
    boolean sphereVisible(float x, float y, float z, float radius) {
        for (float[] pl : planes) {
            if (pl[0] * x + pl[1] * y + pl[2] * z + pl[3] < -radius) {
                return false;
            }
        }
        return true;
    }

    boolean boxVisible(float minX, float minY, float minZ,
                       float maxX, float maxY, float maxZ) {
        for (float[] pl : planes) {
            // The corner furthest along the plane normal. If even that is
            // behind, the whole box is.
            float px = pl[0] >= 0 ? maxX : minX;
            float py = pl[1] >= 0 ? maxY : minY;
            float pz = pl[2] >= 0 ? maxZ : minZ;
            if (pl[0] * px + pl[1] * py + pl[2] * pz + pl[3] < 0) {
                return false;
            }
        }
        return true;
    }
}
