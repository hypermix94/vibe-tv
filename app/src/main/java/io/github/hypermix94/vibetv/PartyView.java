package io.github.hypermix94.vibetv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Party mode: the whole screen washed corner to corner in two of the song's colours. On every strong bass hit the
 * colours move on to the next pair with a quick crossfade, like stage lights changing on the beat; the screen
 * brightens with the bass and eases back between hits, a big hit adds a short flash, and a white glow behind the
 * cover swells with the music. Everything is eased every frame and drawn with two gradients on the graphics chip,
 * so it runs at the screen's full frame rate. MusicScreen feeds it the sound levels and reads {@link #pump()} each
 * frame for the cover.
 */
final class PartyView extends View {
    private static final long SWAP_MS = 420L;        // the crossfade when the colours move on
    private static final long BEAT_GAP_MS = 450L;    // the shortest time between two colour changes
    private static final long FLASH_MS = 260L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[3];
    private int[] palette = {0xFFFF2E8A, 0xFF2A6BFF, 0xFF1FD1C1};
    private boolean running;
    private Runnable onFrame;

    /** The latest sound levels, and the bass as it is shown: eased toward the level every frame. */
    private float bassLevel, trebleLevel, bass, treble, lastBassLevel;
    private long lastFrame, lastBeat, swapStart, flashStart;
    /** Which pair of colours leads, and the pair it is fading from. */
    private int lead, previous;

    PartyView(Context context) {
        super(context);
    }

    /** The song's colours, vivid enough to light a room. */
    void setColours(int[] cover) {
        int n = Math.max(3, cover.length);
        int[] p = new int[n];
        for (int i = 0; i < n; i++) {
            float[] c = new float[3];
            Color.colorToHSV(cover[i % cover.length], c);
            if (i >= cover.length) c[0] = (c[0] + 50f * (i - cover.length + 1)) % 360f;
            c[1] = Math.max(0.55f, c[1]);
            c[2] = 1f;
            p[i] = Color.HSVToColor(c);
        }
        palette = p;
        lead = previous = 0;
    }

    /** Sound levels, 0 to 1, about 20 times a second. A strong rise in the bass is a beat. */
    void setLevels(float b, float m, float t) {
        long now = SystemClock.uptimeMillis();
        if (b > 0.72f && b - lastBassLevel > 0.18f && now - lastBeat > BEAT_GAP_MS) {
            lastBeat = now;
            previous = lead;
            lead = (lead + 1) % palette.length;
            swapStart = now;
            if (b > 0.9f) flashStart = now;   // a big hit: a short flash
        }
        lastBassLevel = b;
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

    /** A colour at the given brightness (0 to 1), keeping its hue. */
    private int lit(int colour, float value) {
        Color.colorToHSV(colour, hsv);
        hsv[2] = Math.max(0f, Math.min(1f, value));
        return Color.HSVToColor(hsv);
    }

    private static int withAlpha(int colour, float a) {
        return (Math.max(0, Math.min(255, (int) (a * 255))) << 24) | (colour & 0xFFFFFF);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        // Ease toward the sound: quick to rise, slower to settle, the same at any frame rate.
        float up = 1f - (float) Math.exp(-dt * 16), down = 1f - (float) Math.exp(-dt * 4);
        bass += (bassLevel - bass) * (bassLevel > bass ? up : down);
        treble += (trebleLevel - treble) * down;

        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h * 0.42f;
        int n = palette.length;
        float swap = Math.min(1f, (now - swapStart) / (float) SWAP_MS);
        swap = swap * swap * (3 - 2 * swap);
        float flash = Math.max(0f, 1f - (now - flashStart) / (float) FLASH_MS);

        // The wash: corner to corner, from the leading colour to the next, crossfading when the beat moves them on.
        float value = 0.72f + 0.24f * bass + 0.12f * flash;
        int from = lit(mix(palette[previous % n], palette[lead % n], swap), value);
        int to = lit(mix(palette[(previous + 1) % n], palette[(lead + 1) % n], swap), value * 0.92f);
        paint.setShader(new LinearGradient(0, 0, w, h * 0.55f, from, to, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);

        // A white glow behind the cover, swelling with the bass and shimmering with the treble.
        float r = h * (0.5f + 0.22f * bass);
        paint.setShader(new RadialGradient(cx, cy, r,
                withAlpha(Color.WHITE, 0.12f + 0.22f * bass + 0.05f * treble + 0.12f * flash), 0x00000000,
                Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, paint);
        paint.setShader(null);

        if (onFrame != null) onFrame.run();
        if (running) postInvalidateOnAnimation();   // every frame the screen shows
    }
}
