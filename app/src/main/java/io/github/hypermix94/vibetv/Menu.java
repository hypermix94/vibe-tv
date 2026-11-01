package io.github.hypermix94.vibetv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The hold-OK menu: a dark card of choices, each a Material-style icon and a word, opened above the app it is for.
 * Up and Down pick, OK chooses (the screen showing it asks {@link #picked()}), Back closes.
 */
final class Menu {
    private final Context context;
    private final float s;
    private final LinearLayout card;
    private final List<LinearLayout> rows = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private int focus;
    private FrameLayout host;

    Menu(Context context, float s, String[] items, int[] icons) {
        this.context = context;
        this.s = s;
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21C1E24);
        bg.setCornerRadius(Cards.d(s, 18));
        card.setBackground(bg);
        card.setPadding(Cards.d(s, 10), Cards.d(s, 10), Cards.d(s, 10), Cards.d(s, 10));
        card.setElevation(Cards.d(s, 30));
        for (int i = 0; i < items.length; i++) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Cards.d(s, 20), Cards.d(s, 14), Cards.d(s, 40), Cards.d(s, 14));
            ImageView icon = new ImageView(context);
            icon.setImageResource(icons[i]);
            row.addView(icon, new LinearLayout.LayoutParams(Cards.d(s, 32), Cards.d(s, 32)));
            TextView t = Cards.text(context, s, 28, R.font.outfit_medium, Color.WHITE);
            t.setText(items[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = Cards.d(s, 18);
            row.addView(t, lp);
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
            rows.add(row);
            labels.add(items[i]);
        }
        paint();
    }

    /** Opens above {@code anchor}, lined up with its left edge, inside {@code root}. */
    void show(FrameLayout root, View anchor) {
        host = root;
        int[] at = new int[2], rootAt = new int[2];
        anchor.getLocationOnScreen(at);
        root.getLocationOnScreen(rootAt);
        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = Math.max(Cards.d(s, 340), card.getMeasuredWidth()), h = card.getMeasuredHeight();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, -2);
        lp.leftMargin = Math.max(Cards.d(s, 112), Math.min(at[0] - rootAt[0], root.getWidth() - Cards.d(s, 112) - w));
        int above = at[1] - rootAt[1] - h - Cards.d(s, 28);
        lp.topMargin = above >= Cards.d(s, 40) ? above : at[1] - rootAt[1] + anchor.getHeight() + Cards.d(s, 28);
        root.addView(card, lp);
        card.setAlpha(0f);
        card.setTranslationY(Cards.d(s, 16));
        card.animate().alpha(1f).translationY(0).setDuration(320)
                .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f)).start();
    }

    void move(int by) {
        focus = Math.max(0, Math.min(rows.size() - 1, focus + by));
        paint();
    }

    String picked() {
        return labels.get(focus);
    }

    void close() {
        View c = card;
        c.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (host != null) host.removeView(c);
        }).start();
    }

    private void paint() {
        for (int i = 0; i < rows.size(); i++) {
            boolean on = i == focus;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Cards.d(s, 12));
            bg.setColor(on ? Color.WHITE : 0x00000000);
            LinearLayout row = rows.get(i);
            row.setBackground(bg);
            ((ImageView) row.getChildAt(0)).setColorFilter(on ? Color.BLACK : Color.WHITE);
            ((TextView) row.getChildAt(1)).setTextColor(on ? Color.BLACK : Color.WHITE);
        }
        if (focus < rows.size()) rows.get(focus).announceForAccessibility(labels.get(focus));
    }
}
