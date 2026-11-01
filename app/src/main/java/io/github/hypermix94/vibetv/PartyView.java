package io.github.hypermix94.vibetv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Party mode: the whole screen in one of the song's colours, flowing slowly from one to the next. A soft glow behind
 * the cover and a second colour drifting gently round the screen give it motion; the bass lifts the brightness and
 * the glow. Everything is smoothed every frame, so it swells rather than jumps, and it is drawn with a few large
 * shapes on the graphics chip, so it runs at the screen's full frame rate. MusicScreen feeds it the sound levels and
 * reads {@link #pump()} each frame for the cover.
 */
final class PartyView extends View {
    /** How long the screen takes to flow from one of the song's colours to the next. */
    private static final long COLOUR_MS = 8000L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] palette = {0xFFFF2E8A, 0xFF2A6BFF, 0xFF1FD1C1};
    private boolean running;
    /** The latest sound levels, and the bass as it is shown: eased toward the level every frame. */
    private float bassLevel, trebleLevel, bass, treble;
    private long lastFrame;
    private Runnable onFrame;

    PartyView(Context context) {
        super(context);
    }

    /** The song's colours, vivid enough to light a room. */
    void setColours(int[] cover) {
        int n = Math.max(3, cover.length);
        int[] p = new int[n];
        for (int i = 0; i < n; i++) {
            float[] hsv = new float[3];
            Color.colorToHSV(cover[i % cover.length], hsv);
            if (i >= cover.length) hsv[0] = (hsv[0] + 50f * (i - cover.length + 1)) % 360f;
            hsv[1] = Math.max(0.65f, hsv[1]);
            hsv[2] = 1f;
            p[i] = Color.HSVToColor(hsv);
        }
        palette = p;
    }

    /** Sound levels, 0 to 1, about 20 times a second; the drawing eases toward them. */
    void setLevels(float b, float m, float t) {
        bassLevel = b;
        trebleLevel = t;
    }

    /** Called every frame while party mode runs, after the levels are eased: MusicScreen pumps the cover. */
    void setOnFrame(Runnable r) {
        onFrame = r;
    }

    /** The cover's scale for this frame: up to 5% bigger on the bass, eased. */
    float pump() {
        return 1f + 0.05f * bass;
    }

    void start() {
        running = true;
        lastFrame = 0;
        postInvalidateOnAnimation();
    }

    void stop() {
        running = false;
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static int withAlpha(int colour, float a) {
        return (Math.max(0, Math.min(255, (int) (a * 255))) << 24) | (colour & 0xFFFFFF);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        // Ease toward the sound: quick to rise, slow to settle, the same at any frame rate.
        float up = 1f - (float) Math.exp(-dt * 14), down = 1f - (float) Math.exp(-dt * 3);
        bass += (bassLevel - bass) * (bassLevel > bass ? up : down);
        treble += (trebleLevel - treble) * down;

        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h * 0.42f;
        int n = palette.length;

        // One solid colour, flowing slowly through the song's colours.
        float phase = (now % (COLOUR_MS * n)) / (float) COLOUR_MS;
        int i = (int) phase;
        float f = phase - i;
        f = f * f * (3 - 2 * f);
        int field = mix(palette[i % n], palette[(i + 1) % n], f);
        float[] hsv = new float[3];
        Color.colorToHSV(field, hsv);
        hsv[2] = 0.7f + 0.3f * bass;
        c.drawColor(Color.HSVToColor(hsv));

        // The next colour as a big soft glow, drifting slowly round the screen.
        double t = now / 1000.0;
        float gx = cx + (float) Math.sin(t * 0.21) * w * 0.32f, gy = cy + (float) Math.cos(t * 0.17) * h * 0.3f;
        float gr = h * (0.75f + 0.1f * bass);
        paint.setShader(new RadialGradient(gx, gy, gr, withAlpha(palette[(i + 2) % n], 0.55f), 0x00000000,
                Shader.TileMode.CLAMP));
        c.drawCircle(gx, gy, gr, paint);

        // A white glow behind the cover, swelling with the bass and shimmering with the treble.
        float wr = h * (0.42f + 0.2f * bass);
        paint.setShader(new RadialGradient(cx, cy, wr, withAlpha(Color.WHITE, 0.16f + 0.24f * bass + 0.06f * treble),
                0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, wr, paint);
        paint.setShader(null);

        if (onFrame != null) onFrame.run();
        if (running) postInvalidateOnAnimation();   // every frame the screen shows
    }
}
