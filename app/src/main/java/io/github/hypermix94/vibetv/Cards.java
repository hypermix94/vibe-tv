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
 * App cards: the app's icon, large and centred, on a deep tint of the icon's own colour; no words on the card (the
 * focused app's name shows above the grid). All the same size, so a page of apps reads as one calm grid. Sizes are on the 1920 x 1080
 * design grid; {@code s} turns them into screen pixels.
 */
final class Cards {
    static final int SURFACE = 0xFF2A2D35;
    static final int RADIUS = 16;

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

    /** A card for an app: its icon, large, on a deep tint of the icon's own colour. The name shows above the grid. */
    static FrameLayout app(Context c, float s, Apps.App a) {
        ImageView icon = new ImageView(c);
        icon.setImageDrawable(a.icon);
        icon.setOutlineProvider(rounded(s, 18));
        icon.setClipToOutline(true);
        return card(c, s, icon, tint(a.icon), a.label, 76);
    }

    /** A card for something that is not an app ("All apps"): a white line icon on the plain surface. */
    static FrameLayout action(Context c, float s, int iconRes, String label) {
        ImageView icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        return card(c, s, icon, SURFACE, label, 52);
    }

    private static FrameLayout card(Context c, float s, ImageView icon, int colour, String label, int iconPx) {
        FrameLayout t = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colour);
        bg.setCornerRadius(d(s, RADIUS));
        t.setBackground(bg);
        t.setOutlineProvider(rounded(s, RADIUS));
        t.setClipToOutline(true);
        t.addView(icon, new FrameLayout.LayoutParams(d(s, iconPx), d(s, iconPx), Gravity.CENTER));
        t.setContentDescription(label);
        focusRing(t, s);
        return t;
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
