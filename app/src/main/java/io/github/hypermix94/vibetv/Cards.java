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
 * App cards, minimal and flat: a warm charcoal card with a hairline border, the app's icon in a pale well of its
 * own colour, and no words (the focused app's name shows above the grid). Focus turns the card bone-white. All the
 * same size, so a page of apps reads as one calm grid. Sizes are on the 1920 x 1080
 * design grid; {@code s} turns them into screen pixels.
 */
final class Cards {
    static final int SURFACE = 0xEB1B1A19;   // warm charcoal, a touch see-through over the photo
    static final int FOCUSED = 0xFFF7F6F3;   // bone white
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

    /** A card for an app: its icon in a pale well of its own colour, on a quiet charcoal card. No words. */
    static FrameLayout app(Context c, float s, Apps.App a) {
        ImageView icon = new ImageView(c);
        icon.setImageDrawable(a.icon);
        icon.setOutlineProvider(rounded(s, 14));
        icon.setClipToOutline(true);
        return card(c, s, icon, pastel(tint(a.icon)), a.label, 60);
    }

    /** A card for something that is not an app ("All apps"): a line icon in a neutral well. */
    static FrameLayout action(Context c, float s, int iconRes, String label) {
        ImageView icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFF2F3437);
        icon.setPadding(d(s, 8), d(s, 8), d(s, 8), d(s, 8));
        return card(c, s, icon, 0xFFE9E7E2, label, 60);
    }

    /** Charcoal card, hairline border, crisp corners; the icon sits in a soft well in the middle. */
    private static FrameLayout card(Context c, float s, ImageView icon, int well, String label, int iconPx) {
        FrameLayout t = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE);
        bg.setCornerRadius(d(s, RADIUS));
        bg.setStroke(Math.max(1, d(s, 1)), BORDER);
        t.setBackground(bg);
        t.setTag(bg);   // its surface turns bone-white on focus (see focus())
        t.setOutlineProvider(rounded(s, RADIUS));
        t.setClipToOutline(true);
        FrameLayout pocket = new FrameLayout(c);
        GradientDrawable w = new GradientDrawable();
        w.setColor(well);
        w.setCornerRadius(d(s, 20));
        pocket.setBackground(w);
        pocket.addView(icon, new FrameLayout.LayoutParams(d(s, iconPx), d(s, iconPx), Gravity.CENTER));
        t.addView(pocket, new FrameLayout.LayoutParams(d(s, 88), d(s, 88), Gravity.CENTER));
        t.setContentDescription(label);
        t.setForeground(new GradientDrawable());   // kept so callers can treat every tile alike
        t.getForeground().setAlpha(0);
        return t;
    }

    /**
     * Focus for a card: the charcoal surface turns bone-white (and the border fades), a calm, unmistakable change at
     * ten feet. Tiles without a card surface (dock banners) keep their white ring instead.
     */
    static void focus(View t, boolean on, long ms) {
        if (!(t.getTag() instanceof GradientDrawable)) {
            if (t.getForeground() != null) t.getForeground().setAlpha(on ? 255 : 0);
            return;
        }
        GradientDrawable bg = (GradientDrawable) t.getTag();
        Integer was = (Integer) t.getTag(R.id.card_colour);
        int from = was != null ? was : SURFACE, to = on ? FOCUSED : SURFACE;
        t.setTag(R.id.card_colour, to);
        if (from == to) return;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofObject(
                new android.animation.ArgbEvaluator(), from, to);
        a.setDuration(ms);
        a.addUpdateListener(v -> bg.setColor((int) v.getAnimatedValue()));
        a.start();
    }

    /** A washed-out pastel of a colour: mostly bone-white with a hint of the hue. */
    static int pastel(int colour) {
        if (colour == SURFACE) return 0xFFE9E7E2;
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        hsv[1] = Math.min(0.28f, hsv[1] * 0.45f);
        hsv[2] = 0.95f;
        return Color.HSVToColor(hsv);
    }

    /**
     * The card colour for an icon: its main colour (the colourful pixels count most, so a white tile behind the logo
     * does not win), deepened so the icon stands out on it. A colourless icon gets the plain surface.
     */
    static int tint(android.graphics.drawable.Drawable icon) {
        if (icon == null) return SURFACE;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(b);
        android.graphics.Rect old = icon.copyBounds();
        icon.setBounds(0, 0, 24, 24);
        icon.draw(canvas);
        icon.setBounds(old);
        float r = 0, g = 0, bl = 0, w = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                float k = hsv[1] * hsv[2] * hsv[1];
                r += Color.red(p) * k;
                g += Color.green(p) * k;
                bl += Color.blue(p) * k;
                w += k;
            }
        }
        b.recycle();
        if (w < 3f) return SURFACE;
        Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (bl / w)), hsv);
        hsv[1] = Math.min(0.7f, hsv[1]);
        hsv[2] = 0.32f;   // deep: the icon is the bright thing on the card
        return Color.HSVToColor(hsv);
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
