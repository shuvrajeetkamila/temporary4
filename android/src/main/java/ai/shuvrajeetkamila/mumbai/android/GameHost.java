package ai.shuvrajeetkamila.mumbai.android;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import ai.shuvrajeetkamila.mumbai.core.Mth;
import ai.shuvrajeetkamila.mumbai.game.InputState;
import ai.shuvrajeetkamila.mumbai.game.QualityProfile;
import ai.shuvrajeetkamila.mumbai.game.Telemetry;

/**
 * Milestone 0 toolchain proof host: GLES3 program, a lit indexed mesh, a packaged binary
 * resource read, touch driven camera and pause/resume safety. The world streamer, physics
 * and vehicles plug into this same lifecycle in later milestones.
 */
class GameHost {

    private static final String TAG = "MumbaiHost";

    private final Context context;
    private final Telemetry telemetry;
    private final InputState input;
    private final TouchController touch;
    private final HudView hud;

    private int program;
    private int vao;
    private int vbo;
    private int ibo;
    private int indexCount;

    private int uViewProj;
    private int uModel;
    private int uLightDir;
    private int uTint;

    private final float[] model = new float[16];
    private final float[] lightDir = new float[]{0.42f, 0.78f, 0.46f};

    private float camYaw = 0.6f;
    private float camPitch = 0.42f;
    private float camDist = 46f;
    private float time;
    private boolean glReady;

    GameHost(Context context, Telemetry telemetry, InputState input, TouchController touch, HudView hud) {
        this.context = context;
        this.telemetry = telemetry;
        this.input = input;
        this.touch = touch;
        this.hud = hud;
    }

    private static final String VERT =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "layout(location = 0) in vec3 aPos;\n"
            + "layout(location = 1) in vec3 aNormal;\n"
            + "layout(location = 2) in vec3 aColor;\n"
            + "uniform mat4 uViewProj;\n"
            + "uniform mat4 uModel;\n"
            + "out vec3 vNormal;\n"
            + "out vec3 vColor;\n"
            + "out vec3 vWorld;\n"
            + "void main() {\n"
            + "    vec4 world = uModel * vec4(aPos, 1.0);\n"
            + "    vWorld = world.xyz;\n"
            + "    vNormal = mat3(uModel) * aNormal;\n"
            + "    vColor = aColor;\n"
            + "    gl_Position = uViewProj * world;\n"
            + "}\n";

    private static final String FRAG =
            "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec3 vNormal;\n"
            + "in vec3 vColor;\n"
            + "in vec3 vWorld;\n"
            + "uniform vec3 uLightDir;\n"
            + "uniform vec3 uTint;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "    vec3 n = normalize(vNormal);\n"
            + "    float ndl = max(dot(n, normalize(uLightDir)), 0.0);\n"
            + "    vec3 sky = vec3(0.34, 0.45, 0.62) * (0.45 + 0.55 * max(n.y, 0.0));\n"
            + "    vec3 sun = vec3(1.05, 0.96, 0.84) * ndl;\n"
            + "    vec3 col = vColor * uTint * (sky + sun);\n"
            + "    float fog = clamp(length(vWorld.xz) / 900.0, 0.0, 1.0);\n"
            + "    col = mix(col, vec3(0.62, 0.70, 0.78), fog * 0.75);\n"
            + "    fragColor = vec4(col, 1.0);\n"
            + "}\n";

    void onGlContextCreated() {
        program = GlUtil.linkProgram(VERT, FRAG);
        if (program == 0) {
            telemetry.fatalError = true;
            telemetry.fatalMessage = "Shader failure: " + GlUtil.lastError();
            Log.e(TAG, telemetry.fatalMessage);
            return;
        }
        uViewProj = GLES30.glGetUniformLocation(program, "uViewProj");
        uModel = GLES30.glGetUniformLocation(program, "uModel");
        uLightDir = GLES30.glGetUniformLocation(program, "uLightDir");
        uTint = GLES30.glGetUniformLocation(program, "uTint");

        buildTestMesh();
        verifyPackagedResource();
        glReady = true;
        telemetry.loading = false;
        telemetry.loadProgress = 1f;
        telemetry.loadStage = "Ready";
    }

    private void verifyPackagedResource() {
        try {
            byte[] data = MumbaiRenderer.readAsset(context, "world/toolchain.mwl");
            if (data.length < 8 || data[0] != 'M' || data[1] != 'W' || data[2] != 'L' || data[3] != '1') {
                throw new IllegalStateException("bad magic in world/toolchain.mwl");
            }
            telemetry.loadStage = "Packaged world resource OK (" + data.length + " B)";
            Log.i(TAG, telemetry.loadStage);
        } catch (Exception e) {
            telemetry.fatalError = true;
            telemetry.fatalMessage = "Packaged world resource missing: " + e;
            Log.e(TAG, telemetry.fatalMessage, e);
        }
    }

    /** A small lit city blockout: ground plate plus boxes. Replaced by the streamer later. */
    private void buildTestMesh() {
        final int blocks = 36;
        final int maxVerts = (blocks + 1) * 24 + 4;
        final int maxIdx = (blocks + 1) * 36 + 6;
        float[] verts = new float[maxVerts * 9];
        int[] idx = new int[maxIdx];
        int vi = 0;
        int ii = 0;
        int base = 0;

        // Ground quad.
        float g = 420f;
        float[][] groundCorners = {{-g, 0, -g}, {g, 0, -g}, {g, 0, g}, {-g, 0, g}};
        for (float[] corner : groundCorners) {
            verts[vi++] = corner[0];
            verts[vi++] = corner[1];
            verts[vi++] = corner[2];
            verts[vi++] = 0f;
            verts[vi++] = 1f;
            verts[vi++] = 0f;
            verts[vi++] = 0.33f;
            verts[vi++] = 0.35f;
            verts[vi++] = 0.33f;
        }
        idx[ii++] = 0;
        idx[ii++] = 2;
        idx[ii++] = 1;
        idx[ii++] = 0;
        idx[ii++] = 3;
        idx[ii++] = 2;
        base = 4;

        for (int b = 0; b < blocks; b++) {
            float bx = ((b % 6) - 2.5f) * 46f + Mth.rand01(b, 7) * 8f;
            float bz = ((b / 6) - 2.5f) * 46f + Mth.rand01(b, 9) * 8f;
            float w = 10f + Mth.rand01(b, 1) * 12f;
            float d = 10f + Mth.rand01(b, 2) * 12f;
            float hgt = 8f + Mth.rand01(b, 3) * 58f;
            float shade = 0.55f + Mth.rand01(b, 4) * 0.4f;
            int added = appendBox(verts, vi, idx, ii, base, bx, 0f, bz, w, hgt, d,
                    shade * 0.84f, shade * 0.82f, shade * 0.78f);
            vi += added * 9;
            ii += 36;
            base += 24;
        }
        indexCount = ii;

        FloatBuffer vb = GlUtil.floatBuffer(vi);
        vb.put(verts, 0, vi).position(0);
        IntBuffer ib = GlUtil.intBuffer(ii);
        ib.put(idx, 0, ii).position(0);

        int[] arrays = new int[1];
        GLES30.glGenVertexArrays(1, arrays, 0);
        vao = arrays[0];
        GLES30.glBindVertexArray(vao);

        vbo = GlUtil.createBuffer(GLES30.GL_ARRAY_BUFFER, vb, vi * 4, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        ibo = GlUtil.createBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib, ii * 4, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo);

        int stride = 9 * 4;
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24);

        GLES30.glBindVertexArray(0);
        GlUtil.checkGlError("buildTestMesh");
    }

    /** Appends a box; returns the number of vertices written (24). */
    private static int appendBox(float[] v, int vOff, int[] idx, int iOff, int base,
                                 float cx, float baseY, float cz, float w, float h, float d,
                                 float r, float g, float b) {
        float x0 = cx - w * 0.5f, x1 = cx + w * 0.5f;
        float y0 = baseY, y1 = baseY + h;
        float z0 = cz - d * 0.5f, z1 = cz + d * 0.5f;
        float[][] faces = {
                {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1},
                {x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1},
                {x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0},
                {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0},
                {x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0},
                {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0},
        };
        int p = vOff;
        for (int f = 0; f < 6; f++) {
            float[] face = faces[f];
            float nx = face[12], ny = face[13], nz = face[14];
            float shade = 1f;
            if (ny > 0.5f) {
                shade = 1.08f;
            } else if (nx != 0f) {
                shade = 0.88f;
            } else if (nz < 0f) {
                shade = 0.78f;
            }
            for (int c = 0; c < 4; c++) {
                v[p++] = face[c * 3];
                v[p++] = face[c * 3 + 1];
                v[p++] = face[c * 3 + 2];
                v[p++] = nx;
                v[p++] = ny;
                v[p++] = nz;
                v[p++] = r * shade;
                v[p++] = g * shade;
                v[p++] = b * shade;
            }
            int q = base + f * 4;
            idx[iOff++] = q;
            idx[iOff++] = q + 1;
            idx[iOff++] = q + 2;
            idx[iOff++] = q;
            idx[iOff++] = q + 2;
            idx[iOff++] = q + 3;
        }
        return 24;
    }

    void onViewport(int w, int h) {
        // handled by the renderer
    }

    void onMemoryTrim(int level) {
        Log.i(TAG, "memory trim level " + level);
    }

    void shutdown() {
        glReady = false;
    }

    void frame(float dt, QualityProfile profile, float[] projection, float[] view, float[] viewProj,
               int viewW, int viewH) {
        time += dt;
        telemetry.paused = touch.isPaused();
        if (!telemetry.paused) {
            camYaw += input.lookX * dt * 1.8f;
            camPitch = Mth.clamp(camPitch + input.lookY * dt * 1.2f, 0.08f, 1.35f);
            camDist = Mth.clamp(camDist - input.moveY * dt * 40f, 12f, 200f);
        }

        GLES30.glClearColor(0.47f, 0.62f, 0.76f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        if (!glReady || program == 0) {
            return;
        }

        float aspect = viewW / (float) Math.max(1, viewH);
        Matrix.perspectiveM(projection, 0, 62f, aspect, 0.4f, 4000f);
        float cx = (float) (Math.cos(camPitch) * Math.sin(camYaw)) * camDist;
        float cy = (float) Math.sin(camPitch) * camDist + 6f;
        float cz = (float) (Math.cos(camPitch) * Math.cos(camYaw)) * camDist;
        Matrix.setLookAtM(view, 0, cx, cy, cz, 0f, 10f, 0f, 0f, 1f, 0f);
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0);

        GLES30.glUseProgram(program);
        Matrix.setIdentityM(model, 0);
        GLES30.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0);
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES30.glUniform3f(uLightDir, lightDir[0], lightDir[1], lightDir[2]);
        GLES30.glUniform3f(uTint, 1f, 1f, 1f);

        GLES30.glBindVertexArray(vao);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0);
        GLES30.glBindVertexArray(0);

        telemetry.drawCalls = 1;
        telemetry.triangles = indexCount / 3;
        telemetry.timeOfDayHours = 8f + (time * 0.01f) % 12f;
        telemetry.locationName = "Toolchain proof";
        telemetry.controlProfile = touch.profile().displayName();
        telemetry.diagnostics = true;
    }
}
