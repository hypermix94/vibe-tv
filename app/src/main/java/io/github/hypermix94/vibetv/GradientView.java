package io.github.hypermix94.vibetv;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Home's background: the Laminar gradient from GetLayers (getlayers.ai), its own shaders in res/raw, drawn on the
 * graphics chip. It moves slowly through seven deep colour sets, blending to the next over six seconds every 30
 * seconds; {@link #next()} skips ahead. Colours are dimmed so white words always read over them. It draws at half
 * resolution and about 30 frames a second, and stops whenever Home is not on screen.
 */
final class GradientView extends GLSurfaceView {
    /** {name, bg, A, B, C, D}: Laminar's own dark presets. */
    static final String[][] SETS = {
            {"Void Violet", "#06041a", "#130c38", "#3a1a8f", "#a45cf0", "#b8f2ea"},
            {"Midnight Ember", "#03040e", "#0a1338", "#23307d", "#e0663f", "#ffe3c4"},
            {"Obsidian Rose", "#0a0410", "#22082a", "#5e1a5e", "#f0567a", "#ffe0d8"},
            {"Ultramarine Ice", "#050a30", "#0e1866", "#2b3fc8", "#78d8f0", "#f2fbff"},
            {"Verdigris Tide", "#02211c", "#063a30", "#0d5f8a", "#5fd8b0", "#fff2e8"},
            {"Magenta Flare", "#16041a", "#380a38", "#6b1470", "#f04ac0", "#ffd8f2"},
            {"Abyss Amber", "#01100e", "#052824", "#0a4a44", "#e08a3a", "#fff0d8"},
    };
    /** How much of each colour stays: the bright crests (C, D) were what glared. */
    private static final float[] DIM = {1f, 1f, 0.85f, 0.62f, 0.5f};
    private static final long HOLD_MS = 30_000, BLEND_MS = 6_000, FRAME_MS = 33;

    /** False on a TV without OpenGL ES 3: Home then shows a still gradient instead. */
    static boolean supported(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        return am != null && am.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
    }

    /** A still stand-in for TVs that cannot run the shader: the first set's deep colours, top to bottom. */
    static android.graphics.drawable.Drawable still() {
        String[] set = SETS[0];
        return new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor(set[3]), Color.parseColor(set[2]), Color.parseColor(set[1])});
    }

    private final float[][][] linear = new float[SETS.length][5][];
    private volatile int from, to;
    private volatile long blendStart = -1, holdStart = SystemClock.uptimeMillis();
    private Runnable onChange;

    GradientView(Context context, int startSet) {
        super(context);
        for (int i = 0; i < SETS.length; i++) {
            for (int k = 0; k < 5; k++) {
                int c = Color.parseColor(SETS[i][k + 1]);
                linear[i][k] = new float[]{lin(Color.red(c) / 255f * DIM[k]), lin(Color.green(c) / 255f * DIM[k]),
                        lin(Color.blue(c) / 255f * DIM[k])};
            }
        }
        from = to = Math.floorMod(startSet, SETS.length);
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(false);   // the graphics memory goes back while an app is in front
        getHolder().setFixedSize(960, 540);   // half of 1080p, scaled up: the gradient has no edges to soften
        setRenderer(new Laminar());
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    /** Called on the main thread when a new set starts blending in. */
    void setOnChange(Runnable r) {
        onChange = r;
    }

    int current() {
        return to;
    }

    /** Skips to the next colour set now (the New colours button). */
    void next() {
        goTo((to + 1) % SETS.length);
    }

    private void goTo(int i) {
        from = to;
        to = i;
        blendStart = holdStart = SystemClock.uptimeMillis();
        Runnable r = onChange;
        if (r != null) post(r);
    }

    private static float lin(float v) {
        return (float) Math.pow(v, 2.2);
    }

    private static float srgb(float v) {
        return (float) Math.pow(Math.max(0f, v), 1 / 2.2);
    }

    private final class Laminar implements Renderer {
        private int program;
        private final int[] colourLoc = new int[5];
        private int timeLoc;
        private float clock = 8f;
        private long prev;

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            program = GLES30.glCreateProgram();
            GLES30.glAttachShader(program, shader(GLES30.GL_VERTEX_SHADER, R.raw.laminar_vert));
            GLES30.glAttachShader(program, shader(GLES30.GL_FRAGMENT_SHADER, R.raw.laminar_frag));
            GLES30.glLinkProgram(program);
            GLES30.glUseProgram(program);
            int[] vao = new int[1];
            GLES30.glGenVertexArrays(1, vao, 0);
            GLES30.glBindVertexArray(vao[0]);
            // Laminar's CONFIG, roll 003: motion and texture as GetLayers tuned them; only the colours change.
            u("uScale", 0.62f); u("uSpeed", 0.33f); u("uStream", 1.15f); u("uSway", 0.38f); u("uBody", 0.66f);
            u("uCore", 0.44f); u("uSwirl", 0.42f); u("uDrift", 0.55f); u("uSpread", 2.2f); u("uWeave", 0.115f);
            u("uFibre", 54f); u("uTravel", 12f); u("uGloss", 0.34f); u("uSheen", 0.09f); u("uContrast", 1.16f);
            u("uMidpoint", 0.5f); u("uBandAmount", 0f); u("uBandCount", 6f); u("uBandSoft", 0.4f); u("uGlow", 0.3f);
            u("uGrain", 0f); u("uGrainAnim", 0f); u("uDither", 1.4f); u("uVignette", 0.06f);
            u("uPointerRadius", 0.46f); u("uPointerStrength", 0.28f); u("uPointerDrag", 0.3f); u("uParallax", 0.0017f);
            GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "iMouse"), 0f, 0f);
            GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "iMouseVel"), 0f, 0f);
            String[] names = {"uBg", "uColorA", "uColorB", "uColorC", "uColorD"};
            for (int k = 0; k < 5; k++) colourLoc[k] = GLES30.glGetUniformLocation(program, names[k]);
            timeLoc = GLES30.glGetUniformLocation(program, "iTime");
            prev = SystemClock.uptimeMillis();
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int w, int h) {
            GLES30.glViewport(0, 0, w, h);
            GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "iResolution"), w, h);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            long now = SystemClock.uptimeMillis();
            long ms = Math.min(50, now - prev);
            prev = now;
            clock += ms * 0.001f;
            if (now - holdStart >= HOLD_MS) goTo((to + 1) % SETS.length);
            float t = 1f;
            if (blendStart >= 0) {
                t = Math.min(1f, (now - blendStart) / (float) BLEND_MS);
                if (t >= 1f) blendStart = -1;
            }
            float e = t * t * (3 - 2 * t);
            for (int k = 0; k < 5; k++) {
                float[] a = linear[from][k], b = linear[to][k];
                GLES30.glUniform3f(colourLoc[k], srgb(a[0] + (b[0] - a[0]) * e), srgb(a[1] + (b[1] - a[1]) * e),
                        srgb(a[2] + (b[2] - a[2]) * e));
            }
            GLES30.glUniform1f(timeLoc, clock);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            // About 30 frames a second is plenty for a slow flow, and halves the work for the TV's graphics chip.
            long spent = SystemClock.uptimeMillis() - now;
            if (spent < FRAME_MS) SystemClock.sleep(FRAME_MS - spent);
        }

        private void u(String name, float v) {
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, name), v);
        }

        private int shader(int type, int res) {
            int sh = GLES30.glCreateShader(type);
            GLES30.glShaderSource(sh, read(res));
            GLES30.glCompileShader(sh);
            return sh;
        }

        private String read(int res) {
            try (InputStream in = getResources().openRawResource(res)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
    }
}
