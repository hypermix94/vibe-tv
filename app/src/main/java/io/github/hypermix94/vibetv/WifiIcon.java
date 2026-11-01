package io.github.hypermix94.vibetv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * The Wi-Fi button's icon, as a live signal meter like a phone's: a dot and three arcs, lit up to the connection's
 * strength and faint above it. A wired connection shows full; no connection shows a faint fan struck through. On
 * focus the parts fill in one after another up to the strength, then stay solid. Drawn on a 24-unit grid.
 */
final class WifiIcon extends View {
    private static final float DIM = 0.28f;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private int colour = Color.WHITE;
    /** How many parts are lit: 0 (no connection) to 4 (dot and all three arcs). */
    private int level = 4;
    private boolean connected = true;
    /** The fill-in on focus, 0 to 1; 1 when resting. */
    private float progress = 1f;
    private ValueAnimator filling;

    WifiIcon(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        refresh();
    }

    void setColour(int c) {
        colour = c;
        invalidate();
    }

    /** Reads the connection now: Wi-Fi strength, or full for a cable. */
    void refresh() {
        int lit = 0;
        boolean on = false;
        try {
            ConnectivityManager cm = getContext().getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                on = true;
                lit = 4;
            } else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                on = true;
                WifiManager wifi = getContext().getApplicationContext().getSystemService(WifiManager.class);
                @SuppressWarnings("deprecation")
                int rssi = wifi.getConnectionInfo().getRssi();
                lit = 1 + WifiManager.calculateSignalLevel(rssi, 4);   // the dot, plus 0 to 3 arcs
            }
        } catch (RuntimeException e) {
            on = true;   // cannot tell: do not claim a problem
            lit = 4;
        }
        if (lit != level || on != connected) {
            level = lit;
            connected = on;
            setContentDescription(on ? "Wi-Fi, signal " + lit + " of 4" : "Wi-Fi, not connected");
            invalidate();
        }
    }

    /** On focus: the parts fill in one after another up to the strength, and stay. */
    void play() {
        if (filling != null) filling.cancel();
        filling = ValueAnimator.ofFloat(0f, 1f);
        filling.setDuration(1300);
        filling.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        filling.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        filling.start();
    }

    /** Back at rest: everything at its settled look, no animation half way. */
    void settle() {
        if (filling != null) filling.cancel();
        progress = 1f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float u = Math.min(getWidth(), getHeight()) / 24f;
        c.save();
        c.translate((getWidth() - 24 * u) / 2f, (getHeight() - 24 * u) / 2f);
        stroke.setStrokeWidth(2.2f * u);
        float shown = progress * 4f;   // how many parts the fill-in has reached
        // Part 0 is the dot; parts 1 to 3 the arcs, from small to wide (centre 12, 18.2 on the grid).
        for (int part = 0; part < 4; part++) {
            boolean lit = connected && part < level;
            float reach = Math.max(0f, Math.min(1f, shown - part));   // this part's own fade-in
            float alpha = lit ? DIM + (1f - DIM) * reach : DIM;
            int col = (Math.round(alpha * 255) << 24) | (colour & 0xFFFFFF);
            if (part == 0) {
                fill.setColor(col);
                c.drawCircle(12 * u, 18.2f * u, 1.6f * u, fill);
            } else {
                float r = new float[]{0, 4.1f, 8.8f, 13.5f}[part];
                arc.set((12 - r) * u, (18.2f - r) * u, (12 + r) * u, (18.2f + r) * u);
                stroke.setColor(col);
                c.drawArc(arc, 225, 90, false, stroke);
            }
        }
        if (!connected) {   // no connection: struck through
            stroke.setColor(colour);
            c.drawLine(4 * u, 4 * u, 20 * u, 20 * u, stroke);
        }
        c.restore();
    }
}
