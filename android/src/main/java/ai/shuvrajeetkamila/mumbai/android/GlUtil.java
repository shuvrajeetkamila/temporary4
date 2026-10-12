package ai.shuvrajeetkamila.mumbai.android;

import android.opengl.GLES30;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Thin, defensive OpenGL ES 3.0 helper layer.
 *
 * <p>Every shader compile and program link is checked and logged with the driver info log,
 * so a GLSL failure on a specific device shows up in logcat with line numbers instead of a
 * silent black screen. Callers can ask whether the last program built successfully and fall
 * back to the flat-colour pipeline.
 */
public final class GlUtil {

    public static final String TAG = "MumbaiGL";

    private static String lastError = "";

    private GlUtil() {
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * Drains the GL error queue and returns the first code seen, or 0 for a clean
     * frame. The CI device job greps for {@code glError=0}, so this is the one place
     * that decides whether a run is reported as clean.
     */
    public static int drainGlError() {
        int first = 0;
        for (int guard = 0; guard < 16; guard++) {
            int e = GLES30.glGetError();
            if (e == GLES30.GL_NO_ERROR) {
                break;
            }
            if (first == 0) {
                first = e;
            }
            Log.e(TAG, "GL error 0x" + Integer.toHexString(e));
        }
        return first;
    }

    public static FloatBuffer floatBuffer(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    public static ShortBuffer shortBuffer(int shorts) {
        return ByteBuffer.allocateDirect(shorts * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
    }

    public static IntBuffer intBuffer(int ints) {
        return ByteBuffer.allocateDirect(ints * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    public static int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        if (shader == 0) {
            lastError = "glCreateShader returned 0";
            Log.e(TAG, lastError);
            return 0;
        }
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(shader);
            lastError = (type == GLES30.GL_VERTEX_SHADER ? "vertex" : "fragment") + " shader: " + log;
            Log.e(TAG, "SHADER COMPILE FAILED: " + log);
            Log.e(TAG, numberSource(source));
            GLES30.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    public static int linkProgram(String vertexSource, String fragmentSource) {
        int vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource);
        if (vs == 0) {
            return 0;
        }
        int fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
        if (fs == 0) {
            GLES30.glDeleteShader(vs);
            return 0;
        }
        int program = GLES30.glCreateProgram();
        if (program == 0) {
            lastError = "glCreateProgram returned 0";
            return 0;
        }
        GLES30.glAttachShader(program, vs);
        GLES30.glAttachShader(program, fs);
        // Lock the attribute slots from Java so the layout cannot drift from
        // MeshBuilder.STRIDE even if a driver ignores layout qualifiers.
        GLES30.glBindAttribLocation(program, 0, "aPos");
        GLES30.glBindAttribLocation(program, 1, "aNormal");
        GLES30.glBindAttribLocation(program, 2, "aColour");
        GLES30.glBindAttribLocation(program, 3, "aAux");
        GLES30.glBindAttribLocation(program, 4, "aInstance");
        GLES30.glLinkProgram(program);
        int[] status = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(program);
            lastError = "link: " + log;
            Log.e(TAG, "PROGRAM LINK FAILED: " + log);
            GLES30.glDeleteProgram(program);
            program = 0;
        }
        if (program != 0) {
            GLES30.glDetachShader(program, vs);
            GLES30.glDetachShader(program, fs);
        }
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        return program;
    }

    private static String numberSource(String src) {
        String[] lines = src.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    public static void checkGlError(String where) {
        int err = GLES30.glGetError();
        if (err != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "GL error 0x" + Integer.toHexString(err) + " at " + where);
        }
    }

    public static int createBuffer(int target, java.nio.Buffer data, int sizeBytes, int usage) {
        int[] ids = new int[1];
        GLES30.glGenBuffers(1, ids, 0);
        GLES30.glBindBuffer(target, ids[0]);
        GLES30.glBufferData(target, sizeBytes, data, usage);
        GLES30.glBindBuffer(target, 0);
        return ids[0];
    }

    public static void deleteBuffer(int id) {
        if (id != 0) {
            int[] ids = new int[]{id};
            GLES30.glDeleteBuffers(1, ids, 0);
        }
    }

    public static void deleteVertexArray(int id) {
        if (id != 0) {
            int[] ids = new int[]{id};
            GLES30.glDeleteVertexArrays(1, ids, 0);
        }
    }
}
