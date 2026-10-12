package ai.shuvrajeetkamila.mumbai.android;

import android.opengl.GLES30;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Small helpers around the GL calls that are easy to get subtly wrong. */
final class GLUtil {

    static final String TAG = "MumbaiGL";

    static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(s);
            GLES30.glDeleteShader(s);
            // Fail loudly. A silently non-compiling shader renders black and
            // looks like a content bug for hours.
            throw new IllegalStateException("shader compile failed: " + log
                    + "\n--- source ---\n" + numbered(src));
        }
        return s;
    }

    static int link(String vs, String fs) {
        int v = compile(GLES30.GL_VERTEX_SHADER, vs);
        int f = compile(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        String log = GLES30.glGetProgramInfoLog(p);
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
        if (ok[0] == 0) {
            GLES30.glDeleteProgram(p);
            // Nearly always a precision mismatch between the two stages.
            throw new IllegalStateException("program link failed: " + log);
        }
        if (log != null && !log.trim().isEmpty()) {
            Log.w(TAG, "link log: " + log.trim());
        }
        return p;
    }

    static FloatBuffer floats(int count) {
        return ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }

    static ByteBuffer bytes(int count) {
        return ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder());
    }

    private static String numbered(String src) {
        StringBuilder sb = new StringBuilder();
        String[] lines = src.split("\n");
        for (int i = 0; i < lines.length; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    static void checkError(String where) {
        int e = GLES30.glGetError();
        if (e != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "GL error 0x" + Integer.toHexString(e) + " at " + where);
        }
    }

    // ---- matrices. Column major, the order GL wants. --------------------

    static void perspective(float[] m, float fovYDeg, float aspect,
                            float near, float far) {
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovYDeg) * 0.5));
        java.util.Arrays.fill(m, 0f);
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1f;
        m[14] = 2f * far * near / (near - far);
    }

    static void lookAt(float[] m, float ex, float ey, float ez,
                       float cx, float cy, float cz, float ux, float uy, float uz) {
        float fx = cx - ex, fy = cy - ey, fz = cz - ez;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1e-6f) { fl = 1e-6f; }
        fx /= fl; fy /= fl; fz /= fl;
        float sx = fy * uz - fz * uy, sy = fz * ux - fx * uz, sz = fx * uy - fy * ux;
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-6f) { sl = 1e-6f; }
        sx /= sl; sy /= sl; sz /= sl;
        float vx = sy * fz - sz * fy, vy = sz * fx - sx * fz, vz = sx * fy - sy * fx;
        m[0] = sx; m[4] = sy; m[8] = sz; m[12] = -(sx * ex + sy * ey + sz * ez);
        m[1] = vx; m[5] = vy; m[9] = vz; m[13] = -(vx * ex + vy * ey + vz * ez);
        m[2] = -fx; m[6] = -fy; m[10] = -fz; m[14] = fx * ex + fy * ey + fz * ez;
        m[3] = 0; m[7] = 0; m[11] = 0; m[15] = 1;
    }

    static void multiply(float[] out, float[] a, float[] b) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float v = 0;
                for (int k = 0; k < 4; k++) { v += a[k * 4 + r] * b[c * 4 + k]; }
                out[c * 4 + r] = v;
            }
        }
    }

    static void ortho(float[] m, float l, float r, float b, float t) {
        java.util.Arrays.fill(m, 0f);
        m[0] = 2f / (r - l);
        m[5] = 2f / (t - b);
        m[10] = -1f;
        m[12] = -(r + l) / (r - l);
        m[13] = -(t + b) / (t - b);
        m[15] = 1f;
    }

    private GLUtil() { }
}
