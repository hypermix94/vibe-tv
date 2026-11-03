package io.github.hypermix94.vibetv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/**
 * The small picture beside the greeting, drawn in thin white lines like the top-right icons: a sun rising over the
 * horizon in the morning, a full sun in the afternoon, a sun setting in the evening, and a moon with a twinkling star
 * late at night, or popcorn for a late show. Each comes in with one gentle motion, then keeps moving softly: the rays turn and breathe, the low
 * suns bob on the horizon, the moon rocks while its star twinkles. Drawn on
 * a 24-unit grid; nothing is fetched.
 */
final class GreetingIcon extends View {
    static final int SUNRISE = 0, SUN = 1, SUNSET = 2, MOON = 3, POPCORN = 4;

    /** The picture that says what the greeting says: a rising sun for "morning", popcorn for "late show" and so on. */
    static int forWords(String words) {
        String w = words.toLowerCase(java.util.Locale.ROOT);
        if (w.contains("show") || w.contains("movie") || w.contains("film")) return POPCORN;
        if (w.contains("morning")) return SUNRISE;
        if (w.contains("afternoon") || w.contains("day")) return SUN;
        if (w.contains("evening")) return SUNSET;
        return MOON;   // night, or anything else after dark
    }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int kind = -1;
    /** The entrance, 0 to 1, and the slow loop, 0 to 1 and round again. */
    private float in = 1f, loop;
    private ValueAnimator entrance, idle;

    GreetingIcon(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.WHITE);
    }

    /** Shows the picture for the hour, with its entrance; the same one again does nothing. */
    void show(int kind) {
        if (kind == this.kind) return;
        this.kind = kind;
        if (entrance != null) entrance.cancel();
        entrance = ValueAnimator.ofFloat(0f, 1f);
        entrance.setDuration(1400);
        entrance.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        entrance.addUpdateListener(a -> {
            in = (float) a.getAnimatedValue();
            invalidate();
        });
        entrance.start();
        if (idle == null) {
            idle = ValueAnimator.ofFloat(0f, 1f);
            idle.setDuration(12_000);   // one loop: the rays turn once, breathing in and out four times
            idle.setInterpolator(new LinearInterpolator());
            idle.setRepeatCount(ValueAnimator.INFINITE);
            idle.addUpdateListener(a -> {
                loop = (float) a.getAnimatedValue();
                invalidate();
            });
        }
        if (isAttachedToWindow() && !idle.isStarted()) idle.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (idle != null && !idle.isStarted()) idle.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (idle != null) idle.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (kind < 0) return;
        float u = Math.min(getWidth(), getHeight()) / 24f;
        c.save();
        c.translate((getWidth() - 24 * u) / 2f, (getHeight() - 24 * u) / 2f);
        c.scale(u, u);
        stroke.setStrokeWidth(1.8f);
        stroke.setAlpha(255);
        if (kind == SUN) {
            c.save();
            c.scale(0.6f + 0.4f * in, 0.6f + 0.4f * in, 12, 12);   // grows into place
            c.drawCircle(12, 12, 4.2f, stroke);
            rays(c, 12, 12, 6.8f, 9f + 1.2f * breathe(), 8, 360f * loop + 45f * (1f - in));
            c.restore();
        } else if (kind == SUNRISE || kind == SUNSET) {
            // The sun rises to sit on the horizon in the morning, and sinks to it in the evening.
            float bob = 0.8f * (float) Math.sin(loop * 2 * Math.PI * 2);
            float y = (kind == SUNRISE ? 13f + 7f * (1f - in) : 9f + 4f * in) + bob;
            c.save();
            c.clipRect(0, 0, 24, 17.2f);   // nothing shows below the horizon
            path.rewind();
            path.addCircle(12, y, 4.2f, Path.Direction.CW);
            c.drawPath(path, stroke);
            rays(c, 12, y, 6.6f, 8.4f + 1.1f * breathe(), 7, 360f * loop);
            c.restore();
            c.drawLine(2.5f, 18, 21.5f, 18, stroke);
            if (kind == SUNSET) c.drawLine(7, 21, 17, 21, stroke);
        } else if (kind == POPCORN) {
            // A striped bucket; kernels pop up out of it in turn and drop back.
            c.save();
            c.translate(0, 5f * (1f - in));
            path.rewind();
            path.moveTo(6.5f, 11.5f);
            path.lineTo(8.3f, 21.5f);
            path.lineTo(15.7f, 21.5f);
            path.lineTo(17.5f, 11.5f);
            path.close();
            c.drawPath(path, stroke);
            c.drawLine(10.4f, 12.5f, 10.9f, 20.8f, stroke);
            c.drawLine(13.6f, 12.5f, 13.1f, 20.8f, stroke);
            float[][] kernels = {{8.6f, 9.4f}, {12f, 8.2f}, {15.4f, 9.4f}};
            for (int i = 0; i < kernels.length; i++) {
                float p = (loop * 6f + i / 3f) % 1f;   // each pops six times a loop, one after another
                float hop = p < 0.35f ? (float) Math.sin(p / 0.35f * Math.PI) : 0f;
                c.drawCircle(kernels[i][0], kernels[i][1] - 3.2f * hop, 1.9f, stroke);
            }
            c.restore();
        } else {
            // The moon rises a little and settles; a small star beside it twinkles.
            c.save();
            c.translate(0, 4f * (1f - in));
            c.rotate(9f * (float) Math.sin(loop * 2 * Math.PI * 2), 11, 13);   // a slow rock
            c.drawPath(crescent(), stroke);
            c.restore();
            float twinkle = 0.5f + 0.5f * (float) Math.sin(loop * 2 * Math.PI * 8);   // eight twinkles a loop
            float r = 0.9f + 0.9f * twinkle * in;
            stroke.setAlpha(Math.round(255 * (0.45f + 0.55f * twinkle) * in));
            c.drawLine(19.5f - r, 6, 19.5f + r, 6, stroke);
            c.drawLine(19.5f, 6 - r, 19.5f, 6 + r, stroke);
        }
        c.restore();
    }

    /** 0 to 1 and back, four times a loop: how far the rays reach. */
    private float breathe() {
        return 0.5f - 0.5f * (float) Math.cos(loop * 2 * Math.PI * 4);
    }

    private Path moon;

    /** A crescent: a full moon with a slightly smaller one cut out of its upper right. */
    private Path crescent() {
        if (moon == null) {
            moon = new Path();
            moon.addCircle(11f, 13f, 7.5f, Path.Direction.CW);
            Path bite = new Path();
            bite.addCircle(14.8f, 9.6f, 6.4f, Path.Direction.CW);
            moon.op(bite, Path.Op.DIFFERENCE);
        }
        return moon;
    }

    /** Short rays round a sun, turned by an angle. */
    private void rays(Canvas c, float cx, float cy, float from, float to, int count, float turn) {
        c.save();
        c.rotate(turn, cx, cy);
        for (int i = 0; i < count; i++) {
            c.drawLine(cx, cy - from, cx, cy - to, stroke);
            c.rotate(360f / count, cx, cy);
        }
        c.restore();
    }
}
