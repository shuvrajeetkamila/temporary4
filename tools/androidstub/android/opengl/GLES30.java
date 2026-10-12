package android.opengl;
public class GLES30 extends GLES20 {
    public static final int GL_ARRAY_BUFFER = 36363;
    public static final int GL_BACK = 51568;
    public static final int GL_CCW = 510;
    public static final int GL_COLOR_BUFFER_BIT = 39006;
    public static final int GL_COMPILE_STATUS = 57997;
    public static final int GL_CULL_FACE = 50652;
    public static final int GL_DEPTH_BUFFER_BIT = 28315;
    public static final int GL_DEPTH_TEST = 30980;
    public static final int GL_DYNAMIC_DRAW = 47715;
    public static final int GL_ELEMENT_ARRAY_BUFFER = 39;
    public static final int GL_FLOAT = 797;
    public static final int GL_FRAGMENT_SHADER = 53962;
    public static final int GL_LEQUAL = 49014;
    public static final int GL_LINK_STATUS = 3595;
    public static final int GL_NO_ERROR = 2795;
    public static final int GL_RENDERER = 43086;
    public static final int GL_STATIC_DRAW = 56377;
    public static final int GL_TRIANGLES = 46027;
    public static final int GL_UNSIGNED_INT = 42034;
    public static final int GL_VENDOR = 37817;
    public static final int GL_VERSION = 44004;
    public static final int GL_VERTEX_SHADER = 44417;
    public static void glAttachShader(int p, int s) {  }
    public static void glBindBuffer(int t, int b) {  }
    public static void glBindVertexArray(int a) {  }
    public static void glBufferData(int t, int size, java.nio.Buffer d, int usage) {  }
    public static void glBufferSubData(int t, int off, int size, java.nio.Buffer d) {  }
    public static void glClear(int mask) {  }
    public static void glClearColor(float r, float g, float b, float a) {  }
    public static void glCompileShader(int s) {  }
    public static int glCreateProgram() { return 0; }
    public static int glCreateShader(int type) { return 0; }
    public static void glCullFace(int f) {  }
    public static void glDeleteBuffers(int n, int[] o, int off) {  }
    public static void glDeleteProgram(int p) {  }
    public static void glDeleteShader(int s) {  }
    public static void glDeleteVertexArrays(int n, int[] o, int off) {  }
    public static void glDepthFunc(int f) {  }
    public static void glDepthMask(boolean b) {  }
    public static void glDetachShader(int p, int s) {  }
    public static void glDisable(int c) {  }
    public static void glDrawArrays(int mode, int first, int count) {  }
    public static void glDrawElements(int mode, int count, int type, int offset) {  }
    public static void glDrawElementsInstanced(int mode, int count, int type, int offset, int inst) {  }
    public static void glEnable(int c) {  }
    public static void glEnableVertexAttribArray(int i) {  }
    public static void glFrontFace(int f) {  }
    public static void glGenBuffers(int n, int[] o, int off) {  }
    public static void glGenVertexArrays(int n, int[] o, int off) {  }
    public static int glGetError() { return 0; }
    public static String glGetProgramInfoLog(int p) { return null; }
    public static void glGetProgramiv(int p, int n, int[] o, int off) {  }
    public static String glGetShaderInfoLog(int s) { return null; }
    public static void glGetShaderiv(int s, int p, int[] o, int off) {  }
    public static String glGetString(int n) { return null; }
    public static int glGetUniformLocation(int p, String n) { return 0; }
    public static void glLinkProgram(int p) {  }
    public static void glShaderSource(int s, String src) {  }
    public static void glUniform1f(int l, float a) {  }
    public static void glUniform1fv(int l, int c, float[] v, int off) {  }
    public static void glUniform2f(int l, float a, float b) {  }
    public static void glUniform3f(int l, float a, float b, float c) {  }
    public static void glUniform4fv(int l, int c, float[] v, int off) {  }
    public static void glUniformMatrix4fv(int l, int c, boolean t, float[] v, int off) {  }
    public static void glUseProgram(int p) {  }
    public static void glVertexAttribDivisor(int i, int d) {  }
    public static void glVertexAttribPointer(int i, int size, int type, boolean norm, int stride, int offset) {  }
    public static void glViewport(int x, int y, int w, int h) {  }
}
