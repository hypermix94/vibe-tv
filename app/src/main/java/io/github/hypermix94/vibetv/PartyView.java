package io.github.hypermix94.vibetv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

/**
 * Party mode: the song's colours flowing across the whole screen like liquid. The colour field is a slowly warping
 * pattern of waves, folded into itself so the colours swirl and melt into each other; the bass speeds the flow up
 * and brightens it, the treble lifts its sheen. Worked out at 160 x 90 and stretched smooth over the screen, about
 * 30 times a second. MusicScreen feeds it the sound levels from the Visualizer.
 */
final class PartyView extends View {
    private static final int W = 160, H = 90;
    private static final int TABLE = 1024;
    private static final float[] SIN = new float[TABLE];

    static {
        for (int i = 0; i < TABLE; i++) SIN[i] = (float) Math.sin(i * 2 * Math.PI / TABLE);
    }

    private final Bitmap small = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final int[] pixels = new int[W * H];
    private final Paint stretch = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect(0, 0, W, H);
    private final Rect dst = new Rect();

    private int[] palette = {0xFFFF2E8A, 0xFF2A6BFF, 0xFF1FD1C1, 0xFFFFB02E};
    private boolean running;
    private float bass, treble;
    /** The flow's own clock: it runs faster on the bass, so the liquid surges with the music. */
    private float flow;
    private long lastFrame;

    PartyView(Context context) {
        super(context);
    }

    /** sin of an angle in radians, from the table. */
    private static float sin(float a) {
        int i = (int) (a * (TABLE / (2 * (float) Math.PI))) & (TABLE - 1);
        return SIN[i];
    }

    /** The song's colours, vivid, with extra shades between them when the cover has fewer than four. */
    void setColours(int[] cover) {
        int n = Math.max(4, cover.length);
        int[] p = new int[n];
        for (int i = 0; i < n; i++) {
            float[] hsv = new float[3];
            Color.colorToHSV(cover[i % cover.length], hsv);
            if (i >= cover.length) hsv[0] = (hsv[0] + 40f * (i - cover.length + 1)) % 360f;
            hsv[1] = Math.max(0.6f, hsv[1]);
            hsv[2] = 1f;
            p[i] = Color.HSVToColor(hsv);
        }
        palette = p;
    }

    /** Sound levels, 0 to 1, eased so the liquid swells rather than jumps. */
    void setLevels(float b, float m, float t) {
        bass += (b - bass) * (b > bass ? 0.35f : 0.08f);
        treble += (t - treble) * 0.2f;
    }

    void start() {
        running = true;
        lastFrame = 0;
        invalidate();
    }

    void stop() {
        running = false;
    }

    /** How loud the bass is right now, smoothed, for the cover's pump. */
    float bass() {
        return bass;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        flow += dt * (0.35f + 1.2f * bass);
        float t = flow;

        int n = palette.length;
        float bright = 0.78f + 0.22f * bass;
        float sheen = 0.12f * treble;
        int i = 0;
        for (int y = 0; y < H; y++) {
            float v = y * 0.045f;
            for (int x = 0; x < W; x++) {
                float u = x * 0.04f;
                // Waves folded into waves: each layer bends the next, which is what makes it look like liquid.
                float a = sin(u * 1.3f + t * 0.9f) + sin(v * 1.7f - t * 0.7f);
                float b = sin(u * 0.8f + a * 1.4f + t * 0.5f) + sin(v * 1.1f + a * 1.1f - t * 0.6f + 1.7f);
                float k = (b + 2f) * 0.25f;                       // 0 to 1 across the field
                float pos = (k * n + t * 0.15f) % n;              // which colours, drifting round the palette
                if (pos < 0) pos += n;
                int c0 = palette[(int) pos % n], c1 = palette[((int) pos + 1) % n];
                float f = pos - (int) pos;
                f = f * f * (3 - 2 * f);                          // melt smoothly from one colour to the next
                float light = bright + sheen * (a * 0.5f);
                int r = (int) ((Color.red(c0) + (Color.red(c1) - Color.red(c0)) * f) * light);
                int g = (int) ((Color.green(c0) + (Color.green(c1) - Color.green(c0)) * f) * light);
                int bl = (int) ((Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * f) * light);
                pixels[i++] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(bl);
            }
        }
        small.setPixels(pixels, 0, W, 0, 0, W, H);
        dst.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(small, src, dst, stretch);
        if (running) postInvalidateDelayed(33);   // about 30 frames a second
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
