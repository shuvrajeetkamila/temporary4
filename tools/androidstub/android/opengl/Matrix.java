package android.opengl;
public class Matrix {
    public static void perspectiveM(float[] m, int o, float fovy, float aspect, float zNear, float zFar) {}
    public static void setLookAtM(float[] rm, int rmOffset, float eyeX, float eyeY, float eyeZ,
        float centerX, float centerY, float centerZ, float upX, float upY, float upZ) {}
    public static void multiplyMM(float[] result, int ro, float[] lhs, int lo, float[] rhs, int rro) {}
    public static void setIdentityM(float[] sm, int smOffset) {}
    public static void translateM(float[] m, int o, float x, float y, float z) {}
    public static void rotateM(float[] m, int o, float a, float x, float y, float z) {}
    public static void scaleM(float[] m, int o, float x, float y, float z) {}
    public static boolean invertM(float[] mInv, int mInvOffset, float[] m, int mOffset) { return true; }
    public static void multiplyMV(float[] rv, int ro, float[] lhs, int lo, float[] rhs, int rro) {}
    public static void orthoM(float[] m, int o, float l, float r, float b, float t, float n, float f) {}
}
