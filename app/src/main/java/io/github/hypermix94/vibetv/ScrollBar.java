package io.github.hypermix94.vibetv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

/**
 * A slim scroll bar for a grid of apps: a faint track and a white thumb whose length is the share of the rows on
 * screen and whose place follows the rows shown, gliding as the grid scrolls.
 */
final class ScrollBar extends FrameLayout {
    private final View thumb;
    private final int width;

    ScrollBar(Context c, float s) {
        super(c);
        width = Cards.d(s, 6);
        View track = new View(c);
        GradientDrawable t = new GradientDrawable();
        t.setColor(0x26FFFFFF);
        t.setCornerRadius(width);
        track.setBackground(t);
        addView(track, new LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL));
        thumb = new View(c);
        GradientDrawable h = new GradientDrawable();
        h.setColor(0xD9FFFFFF);
        h.setCornerRadius(width);
        thumb.setBackground(h);
        addView(thumb, new LayoutParams(width, 0, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        setAlpha(0f);
    }

    /** {@code total} rows, {@code visible} of them on screen, starting at row {@code first}. */
    void set(int total, int visible, int first, boolean animate) {
        int track = getHeight();
        if (track == 0) {   // not laid out yet: try again once it is
            post(() -> set(total, visible, first, animate));
            return;
        }
        float share = total <= visible ? 1f : visible / (float) total;
        int len = Math.max(width * 4, Math.round(track * share));
        LayoutParams lp = (LayoutParams) thumb.getLayoutParams();
        if (lp.height != len) {
            lp.height = len;
            thumb.setLayoutParams(lp);
        }
        int maxFirst = Math.max(1, total - visible);
        float y = total <= visible ? 0 : (track - len) * Math.min(1f, first / (float) maxFirst);
        thumb.animate().translationY(y).setDuration(animate ? 600 : 0)
                .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f)).start();
    }

    void show(boolean on, boolean animate) {
        animate().alpha(on ? 1f : 0f).setDuration(animate ? 500 : 0).start();
    }
}
