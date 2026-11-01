package io.github.hypermix94.vibetv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * The Inputs and Settings button icons, drawn here so their motion always ends in a solid colour: Inputs is a screen
 * with an arrow that slides into it on focus; Settings is three sliders whose knobs glide to new places on focus and
 * back when focus leaves. Drawn on a 24-unit grid in one colour (white, or black on the focused button's disc).
 */
final class ActionIcon extends View {
    static final int INPUTS = 0, SETTINGS = 1;

    private final int kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int colour = Color.WHITE;
    /** The motion, 0 (at rest) to 1 (played through). */
    private float t;
    private ValueAnimator motion;

    ActionIcon(Context context, int kind) {
        super(context);
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        setContentDescription(kind == INPUTS ? "Inputs" : "Settings");
    }

    void setColour(int c) {
        colour = c;
        invalidate();
    }

    /** Focus: play the motion forward. Inputs replays from the start each time. */
    void play() {
        animateTo(1f, kind == INPUTS ? 0f : t, 1100);
    }

    /** Focus left: Settings' knobs glide home; Inputs simply rests. */
    void settle() {
        if (kind == SETTINGS) animateTo(0f, t, 900);
        else {
            if (motion != null) motion.cancel();
            t = 0f;
            invalidate();
        }
    }

    private void animateTo(float to, float from, long ms) {
        if (motion != null) motion.cancel();
        motion = ValueAnimator.ofFloat(from, to);
        motion.setDuration(ms);
        motion.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        motion.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        motion.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float u = Math.min(getWidth(), getHeight()) / 24f;
        c.save();
        c.translate((getWidth() - 24 * u) / 2f, (getHeight() - 24 * u) / 2f);
        c.scale(u, u);
        stroke.setStrokeWidth(2.1f);
        stroke.setColor(colour);   // always the full colour: the motion moves things, it never fades them
        fill.setColor(colour);
        if (kind == INPUTS) {
            path.rewind();   // the screen, open on the left
            path.moveTo(9, 5);
            path.lineTo(19, 5);
            path.quadTo(21, 5, 21, 7);
            path.lineTo(21, 17);
            path.quadTo(21, 19, 19, 19);
            path.lineTo(9, 19);
            c.drawPath(path, stroke);
            // The arrow: when focus arrives it starts a little out to the left and slides into the screen.
            float dx = kind == INPUTS && motion != null && motion.isRunning() ? -6f * (1f - t) : 0f;
            c.translate(dx, 0);
            path.rewind();
            path.moveTo(3, 12);
            path.lineTo(14, 12);
            path.moveTo(10.5f, 8.5f);
            path.lineTo(14, 12);
            path.lineTo(10.5f, 15.5f);
            c.drawPath(path, stroke);
        } else {
            for (int y : new int[]{6, 12, 18}) c.drawLine(4, y, 20, y, stroke);
            float e = t;   // knobs glide between their two places
            c.drawCircle(9 + 6 * e, 6, 2.6f, fill);
            c.drawCircle(15 - 7 * e, 12, 2.6f, fill);
            c.drawCircle(8 + 7 * e, 18, 2.6f, fill);
        }
        c.restore();
    }
}
