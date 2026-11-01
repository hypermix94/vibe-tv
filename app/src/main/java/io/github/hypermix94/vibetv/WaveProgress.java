package io.github.hypermix94.vibetv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

/**
 * The music screen's timeline: the part already played is a gently flowing wave, the rest a thin straight line, with a
 * dot of the same colour travelling straight along the middle, like Android's own media player. The wave moves only while the song plays, at about 30 frames a second.
 */
final class WaveProgress extends View {
    private final Paint played = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dotRadius;
    private final Path path = new Path();
    private final float amplitude, wavelength;
    private float progress;
    private boolean moving;

    WaveProgress(Context context) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;
        amplitude = 2.5f * dp;
        dotRadius = 6f * dp;
        dot.setColor(0xFFFFFFFF);
        dot.setShadowLayer(3f * dp, 0, 0, 0x55000000);
        wavelength = 18f * dp;
        played.setStyle(Paint.Style.STROKE);
        played.setStrokeWidth(2.5f * dp);
        played.setStrokeCap(Paint.Cap.ROUND);
        played.setColor(0xFF1ED760);
        rest.setStyle(Paint.Style.STROKE);
        rest.setStrokeWidth(2f * dp);
        rest.setStrokeCap(Paint.Cap.ROUND);
        rest.setColor(0x40FFFFFF);
    }

    void setColour(int colour) {
        played.setColor(colour);
        dot.setColor(colour);   // the playhead matches the wave
        invalidate();
    }

    void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        if (!moving) invalidate();
    }

    /** The wave flows while the song plays and lies still when it is paused. */
    void setMoving(boolean on) {
        if (moving == on) return;
        moving = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), mid = getHeight() / 2f;
        float x = w * progress;
        c.drawLine(Math.min(w, x + played.getStrokeWidth() * 2), mid, w, mid, rest);
        if (x > 1f) {
            float phase = moving ? (SystemClock.uptimeMillis() % 1600L) / 1600f * wavelength : 0f;
            path.rewind();
            for (float i = 0; i <= x; i += 2f) {
                float y = mid + amplitude * (float) Math.sin((i - phase) / wavelength * 2 * Math.PI);
                if (i == 0) path.moveTo(i, y);
                else path.lineTo(i, y);
            }
            c.drawPath(path, played);
            // The playhead: a dot travelling straight along the middle while the wave moves around it.
            c.drawCircle(x, mid, dotRadius, dot);
        }
        if (moving) postInvalidateDelayed(33);
    }
}
