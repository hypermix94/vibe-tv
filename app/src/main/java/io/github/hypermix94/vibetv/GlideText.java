package io.github.hypermix94.vibetv;

import android.animation.ValueAnimator;
import android.text.Layout;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/**
 * A one-line label that is too long for its space glides slowly to its end, comes back to the start, rests for two
 * seconds and glides again. A label that fits stays still. Used for the song name and the artist.
 */
final class GlideText {
    private static final long REST_MS = 2000L;
    private static final long END_PAUSE_MS = 1200L;

    private final TextView view;
    private final float speed;   // pixels a second
    private ValueAnimator glide;

    private final Runnable start = this::glideOnce;
    private final Runnable back = this::comeBack;

    GlideText(TextView view) {
        this.view = view;
        this.speed = 40f * view.getResources().getDisplayMetrics().density;
        view.setSingleLine(true);
        view.setEllipsize(null);
        view.setHorizontallyScrolling(true);
        view.setHorizontalFadingEdgeEnabled(true);
    }

    /** New words: back to the start, and glide after a rest if they do not fit. */
    void setText(CharSequence text) {
        stop();
        view.setText(text);
        view.scrollTo(0, 0);
        view.postDelayed(start, REST_MS);
    }

    void stop() {
        view.removeCallbacks(start);
        view.removeCallbacks(back);
        if (glide != null) glide.cancel();
        glide = null;
    }

    private void comeBack() {
        view.scrollTo(0, 0);
        view.postDelayed(start, REST_MS);
    }

    private int overflow() {
        Layout layout = view.getLayout();
        if (layout == null) return 0;
        int room = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        return Math.max(0, (int) Math.ceil(layout.getLineWidth(0)) - room);
    }

    private void glideOnce() {
        int distance = overflow();
        if (distance <= 0) return;   // it fits: nothing to do
        glide = ValueAnimator.ofInt(0, distance);
        glide.setDuration((long) (distance / speed * 1000));
        glide.setInterpolator(new LinearInterpolator());
        glide.addUpdateListener(a -> view.scrollTo((int) a.getAnimatedValue(), 0));
        glide.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled) return;
                // Hold at the end a moment, come back to the start, rest, and go again.
                view.postDelayed(back, END_PAUSE_MS);
            }
        });
        glide.start();
    }
}
