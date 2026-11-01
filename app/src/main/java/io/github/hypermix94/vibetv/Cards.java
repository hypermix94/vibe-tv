package io.github.hypermix94.vibetv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Apps as on a phone: each app's own icon, large, in a rounded square, with no card and no words (the focused
 * app's name shows above the grid). Focus lifts the icon with a spring onto a dark plate; icons ripple in when the
 * grid appears. Sizes are on the 1920 x 1080
 * design grid; {@code s} turns them into screen pixels.
 */
final class Cards {
    static final int SURFACE = 0xEB1B1A19;   // warm charcoal, a touch see-through over the photo
    static final int PLATE = 0xE6232428;     // the dark plate behind a focused icon
    static final int BORDER = 0x14FFFFFF;    // a hairline
    static final int RADIUS = 12;

    private Cards() {
    }

    static int d(float s, float designPx) {
        return Math.round(designPx * s);
    }

    static TextView text(Context c, float s, float designPx, int font, int colour) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, d(s, designPx));
        t.setTypeface(c.getResources().getFont(font));
        t.setTextColor(colour);
        t.setIncludeFontPadding(false);
        return t;
    }

    /**
     * Softens a large name's letters: Outfit ends its curves (the tips of S, J, C) in sharp straight cuts that read as
     * clipped at display size. A thin outline drawn with round joins rounds every corner off.
     */
    static void softCorners(TextView t, float s) {
        android.graphics.Paint p = t.getPaint();
        p.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        p.setStrokeWidth(t.getTextSize() * 0.024f);   // in step with the size: heavy outlines blur smaller words
        t.invalidate();
    }

    /** An app as on a phone: its own icon, large, in a rounded square. No card and no words. */
    static FrameLayout app(Context c, float s, Apps.App a) {
        ImageView icon = new ImageView(c);
        icon.setImageDrawable(a.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return cell(c, s, icon, a.label, 0);
    }

    /** "All apps", drawn like an app icon: a grid glyph in a dark rounded square. */
    static FrameLayout action(Context c, float s, int iconRes, String label) {
        ImageView icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        int pad = d(s, 26);
        icon.setPadding(pad, pad, pad, pad);
        return cell(c, s, icon, label, 0xFF3A3B40);
    }

    /**
     * One place in the grid: a dark plate (shown on focus) behind an icon in a rounded square. {@link #focus} fades the
     * plate in; the screen showing the grid lifts and scales the whole place.
     */
    private static FrameLayout cell(Context c, float s, ImageView icon, String label, int iconBackground) {
        FrameLayout t = new FrameLayout(c);
        t.setClipChildren(false);
        View plate = new View(c);
        GradientDrawable p = new GradientDrawable();
        p.setColor(PLATE);
        p.setCornerRadius(d(s, 30));
        p.setStroke(Math.max(1, d(s, 1)), BORDER);
        plate.setBackground(p);
        plate.setAlpha(0f);
        t.addView(plate, new FrameLayout.LayoutParams(d(s, 140), d(s, 140), Gravity.CENTER));
        if (iconBackground != 0) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(iconBackground);
            bg.setCornerRadius(d(s, 24));
            icon.setBackground(bg);
        }
        icon.setOutlineProvider(rounded(s, 24));
        icon.setClipToOutline(true);
        icon.setElevation(d(s, 6));
        t.addView(icon, new FrameLayout.LayoutParams(d(s, 104), d(s, 104), Gravity.CENTER));
        t.setTag(R.id.plate, plate);
        t.setContentDescription(label);
        t.setForeground(new GradientDrawable());   // kept so callers can treat every tile alike
        t.getForeground().setAlpha(0);
        return t;
    }

    /**
     * Focus: an app icon gets a dark plate behind it (the screen lifts and scales it, with a spring); a dock banner,
     * which has no plate, shows its white ring instead.
     */
    static void focus(View t, boolean on, long ms) {
        Object plate = t.getTag(R.id.plate);
        if (plate instanceof View) {
            ((View) plate).animate().alpha(on ? 1f : 0f).setDuration(ms).start();
        } else if (t.getForeground() != null) {
            t.getForeground().setAlpha(on ? 255 : 0);
        }
    }

    /** Icons ripple in one after another, the way a phone's home screen arrives. */
    static void ripple(java.util.List<? extends View> cells, float restAlpha) {
        for (int i = 0; i < cells.size(); i++) {
            View v = cells.get(i);
            v.setScaleX(0.82f);
            v.setScaleY(0.82f);
            v.setAlpha(0f);
            v.animate().scaleX(1f).scaleY(1f).alpha(restAlpha).setStartDelay(Math.min(i, 18) * 28L).setDuration(520)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.3f))
                    .withEndAction(() -> v.animate().setStartDelay(0)).start();
        }
    }

    /** The white ring a card wears while focused (hidden until then). */
    static void focusRing(FrameLayout t, float s) {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(d(s, RADIUS));
        ring.setStroke(d(s, 5), Color.WHITE);
        t.setForeground(ring);
        t.getForeground().setAlpha(0);
    }

    static ViewOutlineProvider rounded(float s, float radius) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), d(s, radius));
            }
        };
    }
}
