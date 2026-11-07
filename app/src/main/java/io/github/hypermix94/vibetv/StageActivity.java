package io.github.hypermix94.vibetv;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.hypermix94.vibetv.Apps.App;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * The home screen in the manner of Apple TV. The focused app fills the whole screen: what it last played, or its own
 * banner on its own colour. Your favourites sit on a frosted tray low over it, at Apple's tile sizes for that many
 * columns; Down slides up every other app in a grid lined up under the tray while the picture softens; Up stretches
 * the time pill at the top right into a bar and unfolds a control panel under it: what is playing, the weather, a
 * timer, and the TV's own quick settings. Laid out on a 1920 x 1080 grid scaled to the screen.
 */
public class StageActivity extends Activity {
    private static final Interpolator EASE = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private static final Interpolator SPRING = new OvershootInterpolator(1.2f);
    /** Apple's tvOS grid: the unfocused tile width for each number of columns, always 40 apart. */
    private static final int[] HIG = {260, 260, 260, 260, 260, 260, 260, 217, 184, 160};
    private static final int VISIBLE = 9, GAP = 40, TRAY_BOTTOM = 980, LEFT = 80;
    private static final float FOCUS_SCALE = 1.14f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private float s;
    private FrameLayout root, page, strip;
    private final ImageView[] pics = new ImageView[2];
    private final ImageView[] fills = new ImageView[2];
    private int picOn;
    private View veil, chevron;
    private TextView title, line, gridName;
    private LinearLayout words, dots;
    private final List<App> dock = new ArrayList<>(), others = new ArrayList<>();
    private final List<FrameLayout> trayTiles = new ArrayList<>(), gridTiles = new ArrayList<>();
    private int cols, tw, th, gridTop, first;

    private enum Zone { TRAY, GRID, PANEL }

    private Zone zone = Zone.TRAY;
    private int at, g;
    private Menu menu;
    private boolean moving, longPressed;
    private long builtRevision;
    private String stageKey = "";
    private ControlPanel panel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = getResources().getDisplayMetrics().widthPixels / 1920f;
        builtRevision = Apps.revision(this);
        loadApps();
        setContentView(build());
        paint(false);
        enter();
        android.content.IntentFilter apps = new android.content.IntentFilter();
        apps.addAction(Intent.ACTION_PACKAGE_ADDED);
        apps.addAction(Intent.ACTION_PACKAGE_REMOVED);
        apps.addAction(Intent.ACTION_PACKAGE_CHANGED);
        apps.addAction(Intent.ACTION_PACKAGE_REPLACED);
        apps.addDataScheme("package");
        registerReceiver(appsChanged, apps);
    }

    private boolean appsStale;
    private final android.content.BroadcastReceiver appsChanged = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            appsStale = true;
            if (hasWindowFocus()) recreate();
        }
    };

    @Override
    protected void onDestroy() {
        unregisterReceiver(appsChanged);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appsStale || Apps.revision(this) != builtRevision) {
            recreate();
            return;
        }
        if (foldGone()) return;   // an app was uninstalled while we were away: it folds away, then Home rebuilds
        main.post(tick);
        stageKey = "";   // what an app last played may have changed
        stage(false);
        if (panel != null) panel.refresh();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        main.removeCallbacks(nextSlide);
        super.onPause();
    }

    /** Home out of sight: give back the full-screen pictures; they come back from their files on return. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN) return;
        for (ImageView p : pics) p.setImageDrawable(null);
        stageKey = "";
    }

    @Override
    public void onBackPressed() {
        if (menu != null) closeMenu();
        else if (moving) stopMoving();
        else if (zone != Zone.TRAY) {
            zone = Zone.TRAY;
            paint(true);
        }
    }

    // ---- the apps ---------------------------------------------------------------------------------

    private void loadApps() {
        Map<String, App> all = Apps.all(this);
        java.util.Set<String> hidden = Apps.hidden(this);
        boolean unset = Apps.dockOrder(this).isEmpty();
        for (String pkg : Apps.dockOrder(this)) {
            App a = all.remove(pkg);
            if (a != null && dock.size() < Apps.MAX_DOCK) dock.add(a);
        }
        for (App a : all.values()) if (!hidden.contains(a.pkg)) others.add(a);
        while (unset && dock.size() < 6 && !others.isEmpty()) dock.add(others.remove(0));
    }

    private void open(App a) {
        if (a == null) return;
        Apps.markOpened(this, a.pkg);
        try {
            startActivity(a.launch);
        } catch (RuntimeException e) {
            // uninstalled meanwhile
        }
    }

    /** An app's own colour: the edge of its banner, or of its icon; the TV's Settings stays black. */
    private final java.util.HashMap<String, Integer> colours = new java.util.HashMap<>();

    private int colourOf(App a) {
        Integer c = colours.get(a.pkg);
        if (c != null) return c;
        int col = a.pkg.equals("com.android.tv.settings") ? Color.BLACK : edgeColour(a.banner != null ? a.banner : a.icon);
        colours.put(a.pkg, col);
        return col;
    }

    /** The commonest solid colour around a picture's edge, or a dark grey when the edge is clear or mixed. */
    private static int edgeColour(Drawable d) {
        if (d == null) return 0xFF1C1C20;
        Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, 32, 18);
        copy.draw(new Canvas(b));
        int[] px = {b.getPixel(1, 1), b.getPixel(30, 1), b.getPixel(1, 16), b.getPixel(30, 16), b.getPixel(16, 1), b.getPixel(16, 16)};
        b.recycle();
        int best = 0xFF1C1C20, votes = 0;
        for (int p : px) {
            if (Color.alpha(p) < 200) continue;
            int n = 0;
            for (int q : px) if (Color.alpha(q) >= 200 && near(p, q)) n++;
            if (n > votes) {
                votes = n;
                best = p | 0xFF000000;
            }
        }
        return votes >= 3 ? best : 0xFF1C1C20;
    }

    private static boolean near(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b)) < 40;
    }

    // ---- building the screen ---------------------------------------------------------------------

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E0E10);
        root.setClipChildren(false);

        // The stage: two layers that crossfade, each a colour with a picture over it.
        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, match());
        for (int i = 0; i < 2; i++) {
            fills[i] = new ImageView(this);
            fills[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            fills[i].setAlpha(0f);
            stage.addView(fills[i], match());
            pics[i] = new ImageView(this);
            pics[i].setAlpha(0f);
            stage.addView(pics[i], match());
        }
        veil = new View(this);
        // Soft shades so the name at the top left and the tray read on any picture.
        veil.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x59000000, 0x00000000, 0x00000000, 0x73000000, 0xB3000000}),
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x66000000, 0x00000000, 0x00000000})}));
        root.addView(veil, match());

        page = new FrameLayout(this);
        page.setClipChildren(false);
        root.addView(page, match());

        int n = Math.max(1, dock.size());
        cols = Math.max(6, Math.min(9, n));
        tw = HIG[cols];
        th = Math.round(tw * 0.6f);
        int trayH = th + 56, trayTop = TRAY_BOTTOM - trayH;

        // The focused app's name, set large, and the line under it.
        words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setClipChildren(false);
        title = Cards.text(this, s, 112, R.font.outfit_medium, Color.WHITE);
        title.setLetterSpacing(-0.03f);
        title.setSingleLine(true);
        title.setPadding(d(16), d(8), d(16), d(8));
        title.setTranslationX(-d(16));
        line = Cards.text(this, s, 29, R.font.outfit_regular, 0xD9FFFFFF);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-2, -2);
        lineLp.topMargin = d(4);
        words.addView(title);
        words.addView(line, lineLp);
        page.addView(words, at(d(1100), -2, Gravity.TOP | Gravity.START, LEFT, 72, 0, 0));
        // Which trending title shows: small dots above the tray, on the right.
        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        page.addView(dots, at(-2, d(12), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, trayTop - 40, 0, 0));
        words.setTag("rise:120");

        // The tray: frosted glass low over the picture, with the favourites on it.
        View tray = new View(this);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0x52222228);
        glass.setCornerRadius(d(40));
        glass.setStroke(Math.max(1, d(1.5f)), 0x24FFFFFF);
        tray.setBackground(glass);
        page.addView(tray, at(-1, d(trayH), Gravity.TOP, 44, trayTop, 44, 0));
        tray.setTag("rise:200");
        FrameLayout clip = new FrameLayout(this);   // the tiles scroll inside the tray, never past its ends
        clip.setClipChildren(false);
        page.addView(clip, at(-1, d(trayH), Gravity.TOP, 44, trayTop, 44, 0));
        strip = new FrameLayout(this);
        strip.setClipChildren(false);
        clip.addView(strip, new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < dock.size(); i++) {
            FrameLayout t = tile(dock.get(i), false);
            strip.addView(t, at(d(tw), d(th), Gravity.TOP | Gravity.START, 36 + i * (tw + GAP), 28, 0, 0));
            t.setTag("rise:" + (260 + 50 * i));
            trayTiles.add(t);
        }

        // Every other app, in a grid lined up under the tray; its first row peeks above the bottom edge.
        gridTop = 1040;
        for (int i = 0; i < others.size(); i++) {
            FrameLayout t = tile(others.get(i), true);
            int x = LEFT + (i % cols) * (tw + GAP), y = gridTop + (i / cols) * (th + 100);
            page.addView(t, at(d(tw), d(th), Gravity.TOP | Gravity.START, x, y, 0, 0));
            gridTiles.add(t);
        }
        gridName = Cards.text(this, s, 26, R.font.outfit_medium, Color.WHITE);
        gridName.setSingleLine(true);
        gridName.setGravity(Gravity.CENTER_HORIZONTAL);
        gridName.setAlpha(0f);
        page.addView(gridName, at(d(tw + 120), -2, Gravity.TOP | Gravity.START, 0, 0, 0, 0));

        // Up: the small chevron says the control panel is there.
        chevron = new ImageView(this);
        ((ImageView) chevron).setImageResource(R.drawable.ic_chevron_up);
        chevron.setAlpha(0.85f);
        root.addView(chevron, at(d(44), d(44), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        panel = new ControlPanel();
        return root;
    }

    /** A tile: the app's banner filling it, or its icon on the app's own colour. */
    private FrameLayout tile(App a, boolean inGrid) {
        FrameLayout t = new FrameLayout(this);
        int col = colourOf(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(col);
        bg.setCornerRadius(d(20));
        t.setBackground(bg);
        t.setOutlineProvider(Cards.rounded(s, 20));
        t.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= 28) {   // focus glows in the app's own colour
            t.setOutlineSpotShadowColor(lighter(col));
            t.setOutlineAmbientShadowColor(lighter(col));
        }
        ImageView img = new ImageView(this);
        if (a.banner != null) {
            img.setImageDrawable(a.banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);   // a 16:9 banner fills the 5:3 tile, no bars
            t.addView(img, match());
        } else {
            img.setImageDrawable(a.icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int icon = Math.round(th * 0.56f);
            t.addView(img, new FrameLayout.LayoutParams(d(icon), d(icon), Gravity.CENTER));
        }
        t.setContentDescription(a.label);
        return t;
    }

    private static int lighter(int c) {
        return Color.rgb(Math.min(255, Color.red(c) + 60), Math.min(255, Color.green(c) + 60), Math.min(255, Color.blue(c) + 60));
    }

    // ---- the stage -------------------------------------------------------------------------------

    private App focused() {
        if (zone == Zone.GRID && !others.isEmpty()) return others.get(Math.min(g, others.size() - 1));
        return dock.isEmpty() ? null : dock.get(Math.min(at, dock.size() - 1));
    }

    private final Runnable stageNow = () -> stage(true);
    /** Which trending title shows for the focused app; the next one comes every six seconds. */
    private int slide;
    private String slidePkg = "";
    private final Runnable nextSlide = () -> {
        if (zone != Zone.TRAY || !hasWindowFocus()) return;
        slide++;
        stage(true);
    };

    /**
     * The focused app on the whole screen: what it last played, full bleed. Else its banner, large: a banner on white
     * (Netflix, YouTube) has its logo cut out onto a dark stage; any other spreads its own colours, blurred, across
     * the whole screen behind it. Else its icon on a dark stage. Loads off the main thread.
     */
    private void stage(boolean animate) {
        App a = focused();
        if (a == null) return;
        if (!a.pkg.equals(slidePkg)) {
            slidePkg = a.pkg;
            slide = 0;
        }
        String key = a.pkg + "/" + NowPlaying.artTime(this, a.pkg) + "/" + slide;
        if (key.equals(stageKey)) return;
        stageKey = key;
        main.removeCallbacks(nextSlide);
        String said = NowPlaying.loadTitle(this, a.pkg);
        new Thread(() -> {
            // What is trending on a streaming app comes first (with your TMDB key); a new list redraws the stage.
            List<Trending.Item> hot = Trending.enabled(this) && Trending.covers(a.pkg) ? Trending.cached(this, a.pkg) : new ArrayList<>();
            Trending.Item item = hot.isEmpty() ? null : hot.get(slide % hot.size());
            Bitmap pic = item != null ? Trending.picture(item.picture) : null;
            if (Trending.enabled(this) && Trending.covers(a.pkg)) {
                new Thread(() -> {
                    if (Trending.refresh(this, a.pkg)) main.post(() -> {
                        if (a.pkg.equals(slidePkg)) {
                            stageKey = "";
                            stage(true);
                        }
                    });
                }, "trending").start();
            }
            Bitmap art = pic != null ? pic : NowPlaying.loadArt(this, a.pkg);
            Bitmap logo = art == null ? ownLogo(a.pkg) : null;
            boolean light = art == null && a.banner != null && luminance(colourOf(a)) > 0.7f;
            Bitmap mark = light ? cutOut(a.banner, colourOf(a)) : art == null && a.banner != null ? feather(a.banner) : null;
            main.post(() -> {
                if (!key.equals(stageKey) || isDestroyed()) return;
                int next = 1 - picOn;
                ImageView p = pics[next];
                ImageView f = fills[next];
                f.setImageDrawable(null);
                f.setColorFilter(null);
                if (Build.VERSION.SDK_INT >= 31) f.setRenderEffect(null);
                boolean plain = art == null;
                if (plain && logo != null) {   // your own logo for the app, on black, never past its own size
                    p.setImageBitmap(logo);
                    p.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    // Centred across the screen, and between the name's line (ends near 280) and the tray (starts near 770).
                    float w = Math.min(720, logo.getWidth() / s), h = w * logo.getHeight() / logo.getWidth();
                    p.setPadding(d(960 - w / 2), d(525 - h / 2), d(960 - w / 2), d(1080 - 525 - h / 2));
                    f.setBackgroundColor(Color.BLACK);
                } else if (!plain && art.getWidth() < art.getHeight() * 1.3f) {
                    // A square cover: large on the right, over itself spread and softened across the screen.
                    p.setImageBitmap(art);
                    p.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    p.setPadding(d(1240), d(150), d(80), d(1080 - 150 - 600));   // between the time pill and the tray, flush with the tray's right
                    p.setClipToOutline(false);
                    f.setBackgroundColor(Color.BLACK);
                    f.setImageBitmap(art);
                    f.setColorFilter(0x8C000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
                    if (Build.VERSION.SDK_INT >= 31) f.setRenderEffect(RenderEffect.createBlurEffect(d(80), d(80), Shader.TileMode.CLAMP));
                } else if (!plain) {
                    p.setImageBitmap(art);
                    p.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    p.setPadding(0, 0, 0, 0);
                    f.setBackgroundColor(Color.BLACK);
                } else {
                    if (mark != null) p.setImageBitmap(mark);
                    else p.setImageDrawable(a.banner != null ? a.banner : a.icon);
                    p.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    // The banner large but not past twice its size, centred above the tray; the icon smaller.
                    int w = a.banner != null ? 640 : 240, h = Math.round(w * (a.banner != null ? 0.5625f : 1f));
                    int cx = 960, cy = 525;
                    p.setPadding(d(cx - w / 2f), d(cy - h / 2f), d(1920 - cx - w / 2f), d(1080 - cy - h / 2f));
                    f.setBackgroundColor(a.banner != null && !light ? colourOf(a) : 0xFF0E0E10);
                    if (a.banner != null && !light && Build.VERSION.SDK_INT >= 31) {
                        f.setImageDrawable(a.banner.getConstantState() != null ? a.banner.getConstantState().newDrawable() : a.banner);
                        f.setColorFilter(0x59000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
                        f.setRenderEffect(RenderEffect.createBlurEffect(d(90), d(90), Shader.TileMode.CLAMP));
                    }
                }
                long ms = animate ? 600 : 0;
                p.animate().alpha(1f).setDuration(ms).start();
                f.animate().alpha(1f).setDuration(ms).start();
                pics[picOn].animate().alpha(0f).setDuration(ms).start();
                fills[picOn].animate().alpha(0f).setDuration(ms).start();
                picOn = next;
                veil.animate().alpha(plain ? 0.6f : 1f).setDuration(ms).start();
                // The name, large at the top left for every app, as on Apple TV; the line under it says what is on.
                boolean bigName = true;
                title.setText(item != null ? item.title : a.label);
                title.setVisibility(bigName ? View.VISIBLE : View.GONE);
                String what = item != null ? "Trending on " + android.text.TextUtils.htmlEncode(a.label) + " · from TMDB"
                        : said.isEmpty() ? lastOpened(a.pkg) : "Last played " + android.text.TextUtils.htmlEncode(said);
                line.setText(android.text.Html.fromHtml(bigName ? what
                        : "<b>" + android.text.TextUtils.htmlEncode(a.label) + "</b> · " + what, android.text.Html.FROM_HTML_MODE_LEGACY));
                if (hot.size() > 1) main.postDelayed(nextSlide, 6000);
                dots.removeAllViews();
                for (int i = 0; item != null && hot.size() > 1 && i < hot.size(); i++) {
                    View dot = new View(this);
                    GradientDrawable g = new GradientDrawable();
                    g.setCornerRadius(d(6));
                    g.setColor(i == slide % hot.size() ? Color.WHITE : 0x59FFFFFF);
                    dot.setBackground(g);
                    LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(d(i == slide % hot.size() ? 36 : 12), d(12));
                    dl.leftMargin = dl.rightMargin = d(5);
                    dots.addView(dot, dl);
                }
                if (animate) {
                    words.setAlpha(0f);
                    words.animate().alpha(zone == Zone.TRAY ? 1f : 0f).setDuration(500).start();
                }
            });
        }, "stage").start();
    }

    private static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    /**
     * A banner's logo without its plain background: drawn at twice the banner's size, everything joined to the edge in
     * the background's colour becomes clear (a flood fill from the edges), so the logo stands on the stage alone.
     */
    private static Bitmap cutOut(Drawable banner, int bg) {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Drawable copy = banner.getConstantState() != null ? banner.getConstantState().newDrawable().mutate() : banner;
        copy.setBounds(0, 0, w, h);
        copy.draw(new Canvas(b));
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        int top = 0;
        for (int x = 0; x < w; x++) {   // every pixel goes on the list at most once, so the list never overflows
            for (int i : new int[]{x, (h - 1) * w + x}) if (!seen[i]) { seen[i] = true; stack[top++] = i; }
        }
        for (int y = 0; y < h; y++) {
            for (int i : new int[]{y * w, y * w + w - 1}) if (!seen[i]) { seen[i] = true; stack[top++] = i; }
        }
        while (top > 0) {
            int i = stack[--top];
            int p = px[i];
            int diff = Math.abs(Color.red(p) - Color.red(bg)) + Math.abs(Color.green(p) - Color.green(bg)) + Math.abs(Color.blue(p) - Color.blue(bg));
            if (Color.alpha(p) > 40 && diff > 90) continue;   // the logo's edge: stop here
            px[i] = diff < 30 || Color.alpha(p) <= 40 ? 0 : (p & 0x00FFFFFF) | (Math.min(255, diff * 255 / 90) << 24);
            int x = i % w, y = i / w;
            for (int n : new int[]{x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1}) {
                if (n >= 0 && !seen[n]) {
                    seen[n] = true;
                    stack[top++] = n;
                }
            }
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    /** Your own logo for an app, from local-logos (see app/build.gradle), or null. */
    private Bitmap ownLogo(String pkg) {
        for (String ext : new String[]{".png", ".jpg"}) {
            try (java.io.InputStream in = getAssets().open("logos/" + pkg + ext)) {
                return android.graphics.BitmapFactory.decodeStream(in);
            } catch (java.io.IOException ignored) {
                // not this one
            }
        }
        return null;
    }

    /** A banner whose edges fade out, so it melts into its own colours spread across the screen behind it. */
    private static Bitmap feather(Drawable banner) {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Drawable copy = banner.getConstantState() != null ? banner.getConstantState().newDrawable().mutate() : banner;
        copy.setBounds(0, 0, w, h);
        copy.draw(c);
        android.graphics.Paint fade = new android.graphics.Paint();
        fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        int edgeX = w / 5, edgeY = h / 5;
        int[] ramp = {0x00000000, 0xFF000000};
        fade.setShader(new android.graphics.LinearGradient(0, 0, edgeX, 0, ramp, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, edgeX, h, fade);
        fade.setShader(new android.graphics.LinearGradient(w, 0, w - edgeX, 0, ramp, null, Shader.TileMode.CLAMP));
        c.drawRect(w - edgeX, 0, w, h, fade);
        fade.setShader(new android.graphics.LinearGradient(0, 0, 0, edgeY, ramp, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, edgeY, fade);
        fade.setShader(new android.graphics.LinearGradient(0, h, 0, h - edgeY, ramp, null, Shader.TileMode.CLAMP));
        c.drawRect(0, h - edgeY, w, h, fade);
        return b;
    }

    private String lastOpened(String pkg) {
        long when = Apps.prefs(this).getLong("opened_" + pkg, 0);
        if (when == 0) return "Press OK to open";
        Calendar then = Calendar.getInstance(), now = Calendar.getInstance();
        then.setTimeInMillis(when);
        String time = DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a", then).toString().toLowerCase(java.util.Locale.getDefault());
        long days = (startOfDay(now) - startOfDay(then)) / 86_400_000L;
        String ago = days == 0 ? "today, " + time : days == 1 ? "yesterday, " + time
                : days < 7 ? days + " days ago" : DateFormat.format("d MMMM", then).toString();
        return "Last opened " + ago;
    }

    private static long startOfDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    // ---- the remote -----------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok) {   // OK acts when it comes up; held for half a second it opens the app's menu instead
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    longPressed = false;
                    e.startTracking();
                } else if (!longPressed && menu == null && !moving && zone != Zone.PANEL
                        && (e.isLongPress() || e.getEventTime() - e.getDownTime() > 500)) {
                    longPressed = true;
                    openMenu();
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP && !longPressed) {
                if (menu != null) pickMenu();
                else if (moving) stopMoving();
                else press();
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int dx = k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
        int dy = k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 0;
        if (menu != null) {
            if (dy != 0) menu.move(dy);
            else if (k == KeyEvent.KEYCODE_BACK) closeMenu();
            return true;
        }
        if (moving) {
            if (dx != 0) shift(dx);
            else if (k == KeyEvent.KEYCODE_BACK) stopMoving();
            return true;
        }
        if (dx == 0 && dy == 0) return super.dispatchKeyEvent(e);
        move(dx, dy);
        return true;
    }

    private void move(int dx, int dy) {
        if (zone == Zone.PANEL) {
            if (!panel.move(dx, dy)) return;
        } else if (zone == Zone.TRAY) {
            if (dx != 0) {
                int next = Math.max(0, Math.min(dock.size() - 1, at + dx));
                if (next == at) {
                    bump(trayTiles.get(at), dx);
                    return;
                }
                at = next;
            } else if (dy < 0) {
                zone = Zone.PANEL;
                panel.open();
            } else if (!others.isEmpty()) {
                zone = Zone.GRID;
                g = Math.min(others.size() - 1, Math.max(0, at - first));   // straight down, under the tray
            }
        } else {
            int col = g % cols;
            if (dx != 0) {
                if (col + dx < 0 || col + dx >= cols || g + dx >= others.size()) {
                    bump(gridTiles.get(g), dx);
                    return;
                }
                g += dx;
            } else if (dy < 0) {
                if (g >= cols) g -= cols;
                else {
                    zone = Zone.TRAY;
                    at = Math.min(dock.size() - 1, first + col);
                }
            } else if (g + cols < others.size()) {
                g += cols;
            } else if (g / cols < (others.size() - 1) / cols) {
                g = others.size() - 1;
            }
        }
        paint(true);
    }

    private void press() {
        if (zone == Zone.PANEL) {
            panel.press();
        } else if (zone == Zone.TRAY && !dock.isEmpty()) {
            pressed(trayTiles.get(at));
            open(dock.get(at));
        } else if (zone == Zone.GRID && !others.isEmpty()) {
            pressed(gridTiles.get(g));
            open(others.get(g));
        }
    }

    // ---- focus and motion -----------------------------------------------------------------------

    private void paint(boolean animate) {
        long ms = animate ? 420 : 0;
        // Past nine apps the tray slides, keeping one app of room either side of the focused one.
        if (dock.size() > VISIBLE) {
            if (at > first + VISIBLE - 2) first = at - VISIBLE + 2;
            if (at < first + 1) first = at - 1;
            first = Math.max(0, Math.min(dock.size() - VISIBLE, first));
        } else {
            first = 0;
        }
        strip.animate().translationX(-d(first * (tw + GAP))).setDuration(animate ? 600 : 0).setInterpolator(EASE).start();
        for (int i = 0; i < trayTiles.size(); i++) focus(trayTiles.get(i), zone == Zone.TRAY && i == at || moving && i == at, ms);
        for (int i = 0; i < gridTiles.size(); i++) focus(gridTiles.get(i), zone == Zone.GRID && i == g, ms);

        // Down: the page slides up to the grid and the picture softens; the focused app's name sits under its tile.
        boolean inGrid = zone == Zone.GRID;
        int row = inGrid ? g / cols : 0;
        float pageY = inGrid ? -d(gridTop - 300 + Math.max(0, row - 1) * (th + 100)) : 0;
        page.animate().translationY(pageY).setDuration(animate ? 800 : 0).setInterpolator(EASE).start();
        if (inGrid) {
            FrameLayout t = gridTiles.get(g);
            gridName.setText(others.get(g).label);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) t.getLayoutParams();
            gridName.setTranslationX(lp.leftMargin - d(60));
            gridName.setTranslationY(lp.topMargin + d(th) * (1 + FOCUS_SCALE) / 2f + d(14));
        }
        gridName.animate().alpha(inGrid ? 1f : 0f).setDuration(animate ? 300 : 0).start();
        words.animate().alpha(zone == Zone.TRAY ? 1f : 0f).setDuration(animate ? 400 : 0).start();
        dots.animate().alpha(zone == Zone.TRAY ? 1f : 0f).setDuration(animate ? 400 : 0).start();
        chevron.animate().alpha(zone == Zone.TRAY ? 0.85f : 0f).setDuration(animate ? 400 : 0).start();
        View stage = (View) pics[0].getParent();
        stage.animate().scaleX(inGrid ? 1.08f : 1f).scaleY(inGrid ? 1.08f : 1f).alpha(inGrid ? 0.5f : 1f)
                .setDuration(animate ? 900 : 0).setInterpolator(EASE).start();
        if (Build.VERSION.SDK_INT >= 31) {
            stage.setRenderEffect(inGrid ? RenderEffect.createBlurEffect(d(30), d(30), Shader.TileMode.CLAMP) : null);
        }
        page.animate().alpha(zone == Zone.PANEL ? 0.55f : 1f).setDuration(animate ? 500 : 0).start();
        panel.bar.animate().alpha(inGrid ? 0f : 1f).setDuration(animate ? 400 : 0).start();   // the grid has the screen
        panel.paint(zone == Zone.PANEL, animate);
        main.removeCallbacks(stageNow);
        if (zone != Zone.PANEL) main.postDelayed(stageNow, animate ? 140 : 0);   // not while the remote races along
    }

    /** The focused tile rises a little and glows in its own colour; the others rest. */
    private void focus(View t, boolean on, long ms) {
        Object was = t.getTag(R.id.focused);
        if (ms > 0 && was != null && (Boolean) was == on) return;
        t.setTag(R.id.focused, on);
        if (on && ms > 0) t.announceForAccessibility(t.getContentDescription());
        t.animate().scaleX(on ? FOCUS_SCALE : 1f).scaleY(on ? FOCUS_SCALE : 1f).translationZ(on ? d(30) : 0)
                .setDuration(ms).setInterpolator(EASE).start();
        t.setElevation(on ? d(20) : 0);
    }

    private void bump(View t, int dir) {
        t.animate().translationX(d(14) * dir).setDuration(110).withEndAction(() ->
                t.animate().translationX(0).setDuration(380).setInterpolator(SPRING).start()).start();
    }

    private void pressed(View t) {
        t.animate().scaleX(1.05f).scaleY(1.05f).setDuration(90).withEndAction(() ->
                t.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).setDuration(300).setInterpolator(SPRING).start()).start();
    }

    /** First appearance: each part rises into place and fades in, one after another. */
    private void enter() {
        List<View> parts = new ArrayList<>();
        collect(root, parts);
        for (View v : parts) {
            long delay = Long.parseLong(((String) v.getTag()).substring(5));
            v.setAlpha(0f);
            v.setTranslationY(v.getTranslationY() + d(28));
            v.animate().alpha(1f).translationYBy(-d(28)).setStartDelay(delay).setDuration(800)
                    .setInterpolator(EASE).withEndAction(() -> v.animate().setStartDelay(0)).start();
        }
    }

    private static void collect(View v, List<View> out) {
        if (v.getTag() instanceof String && ((String) v.getTag()).startsWith("rise:")) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    // ---- the hold-OK menu, moving and removing -----------------------------------------------------

    private void openMenu() {
        App a = focused();
        if (a == null) return;
        boolean inTray = zone == Zone.TRAY;
        String remove = Apps.removeAction(this, a.pkg);
        String[] items = inTray
                ? new String[]{"Move", "Remove from dock", "Remove from Home", "App info", remove}
                : new String[]{"Add to dock", "Remove from Home", "App info", remove};
        int[] icons = inTray
                ? new int[]{R.drawable.ic_menu_move, R.drawable.ic_menu_dock_remove, R.drawable.ic_menu_hide,
                        R.drawable.ic_menu_info, R.drawable.ic_menu_delete}
                : new int[]{R.drawable.ic_menu_dock_add, R.drawable.ic_menu_hide, R.drawable.ic_menu_info,
                        R.drawable.ic_menu_delete};
        if (remove == null) {
            items = java.util.Arrays.copyOf(items, items.length - 1);
            icons = java.util.Arrays.copyOf(icons, icons.length - 1);
        }
        menu = new Menu(this, s, items, icons);
        menu.show(root, inTray ? trayTiles.get(at) : gridTiles.get(g));
    }

    private void closeMenu() {
        if (menu == null) return;
        menu.close();
        menu = null;
    }

    private void pickMenu() {
        App a = focused();
        String label = menu.picked();
        closeMenu();
        if (a == null) return;
        switch (label) {
            case "Move":
                moving = true;
                trayTiles.get(at).animate().translationY(-d(24)).setDuration(300).setInterpolator(EASE).start();
                break;
            case "Add to dock":
                if (!Apps.addToDock(this, a.pkg)) {
                    android.widget.Toast.makeText(this, "The dock is full: remove an app from it first", android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                fold(gridTiles.get(g), this::recreate);
                break;
            case "Remove from dock":
                Apps.removeFromDock(this, a.pkg);
                fold(trayTiles.get(at), this::recreate);
                break;
            case "Remove from Home":
                Apps.setHidden(this, a.pkg, true);
                fold(zone == Zone.TRAY ? trayTiles.get(at) : gridTiles.get(g), this::recreate);
                break;
            case "App info":
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case "Uninstall":
            case "Disable":
                Apps.remove(this, a.pkg, label);
                break;
            default:
                break;
        }
    }

    /** Moves the lifted tray app one place: the neighbour glides across into the gap. */
    private void shift(int by) {
        int to = at + by;
        if (to < 0 || to >= dock.size()) return;
        dock.add(to, dock.remove(at));
        FrameLayout t = trayTiles.remove(at), other = trayTiles.get(to);
        trayTiles.add(to, t);
        for (int i = 0; i < trayTiles.size(); i++) {
            View v = trayTiles.get(i);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            int x = d(36 + i * (tw + GAP));
            if (lp.leftMargin == x) continue;
            float was = lp.leftMargin + v.getTranslationX();
            lp.leftMargin = x;
            v.setLayoutParams(lp);
            v.setTranslationX(was - x);
            v.animate().translationX(0).setDuration(450).setInterpolator(EASE).start();
        }
        other.animate().translationY(0).start();
        at = to;
        paint(true);
    }

    private void stopMoving() {
        moving = false;
        List<String> order = new ArrayList<>();
        for (App x : dock) order.add(x.pkg);
        Apps.saveDock(this, order);
        trayTiles.get(at).animate().translationY(0).setDuration(300).setInterpolator(EASE).start();
        paint(true);
    }

    /** A tile folds away: it narrows, sinks and fades, then {@code done} runs. */
    private void fold(View t, Runnable done) {
        t.setPivotY(t.getHeight());
        t.animate().scaleX(0.6f).scaleY(0.2f).alpha(0f).translationY(d(30)).setDuration(450).setInterpolator(EASE)
                .withEndAction(done).start();
    }

    /** True when an app on Home was uninstalled meanwhile: its tile folds away and Home rebuilds. */
    private boolean foldGone() {
        List<App> shown = new ArrayList<>(dock);
        shown.addAll(others);
        for (int i = 0; i < shown.size(); i++) {
            App a = shown.get(i);
            if (getPackageManager().getLaunchIntentForPackage(a.pkg) != null
                    || getPackageManager().getLeanbackLaunchIntentForPackage(a.pkg) != null) continue;
            View t = i < dock.size() ? trayTiles.get(i) : gridTiles.get(i - dock.size());
            main.postDelayed(() -> fold(t, this::recreate), 250);
            return true;
        }
        return false;
    }

    // ---- the clock ---------------------------------------------------------------------------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            panel.time(DateFormat.format(DateFormat.is24HourFormat(StageActivity.this) ? "H:mm" : "h:mm a", new Date()).toString());
            panel.tick();
            main.postDelayed(this, 1000 - System.currentTimeMillis() % 1000 + 10);
        }
    };

    // ---- the control panel -------------------------------------------------------------------------

    /**
     * Up: the time pill at the top right stretches left into a bar of modules (what is playing, the weather, a timer,
     * the controls, you) and a panel unfolds from under it. Left and Right along the bar swap what the panel shows;
     * Down goes into it. The controls are the TV's own quick settings; on a Sony each opens Sony's Quick Settings
     * strip, where the values live (other apps cannot read them), and Settings opens the TV's Settings.
     */
    private final class ControlPanel {
        private static final int CONTROLS = 3, ME = 4, W = 600, PAD = 24;
        private final LinearLayout bar;
        private final TextView clock;
        private final List<ImageView> mods = new ArrayList<>();
        private final TextView me;
        private final FrameLayout card;
        private final FrameLayout[] views = new FrameLayout[4];
        private final List<FrameLayout> keys = new ArrayList<>();
        private int m = CONTROLS, q;
        private boolean inBar, shown;
        private TextView timerBig;

        private final String[][] KEYS = {
                {"Picture Off", "Sound keeps playing"}, {"Picture mode", ""}, {"Brightness", ""},
                {"Speakers", ""}, {"Sleep timer", ""}, {"Power saving", ""}, {"Wide mode", ""},
                {"Parental controls", ""}, {"Settings", ""}};
        private final int[] ICONS = {R.drawable.ic_cc_picoff, R.drawable.ic_cc_pmode, R.drawable.ic_cc_bright,
                R.drawable.ic_cc_sound, R.drawable.ic_cc_timer, R.drawable.ic_cc_leaf, R.drawable.ic_cc_wide,
                R.drawable.ic_cc_lock, R.drawable.ic_cc_gear};
        /** Where each control sits in the panel, in design pixels: x, y, width, height. */
        private final int[][] AT = {{0, 0, 266, 266}, {286, 0, 266, 123}, {286, 143, 266, 123}, {0, 286, 266, 123},
                {286, 286, 266, 123}, {0, 429, 123, 123}, {143, 429, 123, 123}, {286, 429, 123, 123}, {429, 429, 123, 123}};

        ControlPanel() {
            // The pill, which is also the bar.
            bar = new LinearLayout(StageActivity.this);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(d(26), 0, d(8), 0);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0x73282A30);
            pill.setCornerRadius(d(32));
            pill.setStroke(Math.max(1, d(1)), 0x21FFFFFF);
            bar.setBackground(pill);
            LayoutTransition lt = new LayoutTransition();
            lt.setDuration(420);
            lt.setInterpolator(LayoutTransition.CHANGE_APPEARING, EASE);
            lt.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, EASE);
            bar.setLayoutTransition(lt);
            clock = Cards.text(StageActivity.this, s, 26, R.font.outfit_regular, Color.WHITE);
            clock.setFontFeatureSettings("tnum");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.rightMargin = d(10);
            bar.addView(clock, clp);
            int[] modIcons = {R.drawable.ic_cc_wave, R.drawable.ic_weather_cloud_sun, R.drawable.ic_cc_timer, R.drawable.ic_cc_toggles};
            for (int i = 0; i < modIcons.length; i++) {
                ImageView v = new ImageView(StageActivity.this);
                v.setImageResource(modIcons[i]);
                v.setPadding(d(11), d(11), d(11), d(11));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(50), d(50));
                lp.leftMargin = d(3);
                lp.rightMargin = d(3);
                bar.addView(v, lp);
                if (i > 0) v.setVisibility(View.GONE);   // at rest only what is playing shows
                mods.add(v);
            }
            me = Cards.text(StageActivity.this, s, 24, R.font.outfit_medium, Color.WHITE);
            me.setGravity(Gravity.CENTER);
            String name = Prefs.name(StageActivity.this).trim();
            me.setText(name.isEmpty() ? "" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
            GradientDrawable av = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFF7A6C4, 0xFFA46CF0});
            av.setShape(GradientDrawable.OVAL);
            me.setBackground(av);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(d(50), d(50));
            mlp.leftMargin = d(6);
            bar.addView(me, mlp);
            root.addView(bar, at(-2, d(64), Gravity.TOP | Gravity.END, 0, 60, 80, 0));

            // The panel under it.
            card = new FrameLayout(StageActivity.this);
            GradientDrawable glass = new GradientDrawable();
            glass.setColor(0xD9383B42);
            glass.setCornerRadius(d(44));
            glass.setStroke(Math.max(1, d(1)), 0x1FFFFFFF);
            card.setBackground(glass);
            card.setElevation(d(30));
            card.setPadding(d(PAD), d(PAD), d(PAD), d(PAD));
            card.setAlpha(0f);
            card.setVisibility(View.INVISIBLE);
            root.addView(card, at(d(W), d(552 + 2 * PAD), Gravity.TOP | Gravity.END, 0, 140, 80, 0));
            for (int i = 0; i < 4; i++) {
                views[i] = new FrameLayout(StageActivity.this);
                views[i].setClipChildren(false);
                card.addView(views[i], new FrameLayout.LayoutParams(-1, -1));
            }
            for (int i = 0; i < KEYS.length; i++) {
                FrameLayout k = key(i);
                views[CONTROLS].addView(k, at(d(AT[i][2]), d(AT[i][3]), Gravity.TOP | Gravity.START, AT[i][0], AT[i][1], 0, 0));
                keys.add(k);
            }
            refresh();
        }

        private FrameLayout key(int i) {
            boolean big = i == 0, square = AT[i][2] < 200;
            FrameLayout k = new FrameLayout(StageActivity.this);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x1FFFFFFF);
            bg.setCornerRadius(d(34));
            k.setBackground(bg);
            k.setContentDescription(KEYS[i][0]);
            LinearLayout in = new LinearLayout(StageActivity.this);
            in.setOrientation(big ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            in.setGravity(big || square ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
            in.setPadding(square ? 0 : d(22), 0, square ? 0 : d(16), 0);
            ImageView icon = new ImageView(StageActivity.this);
            icon.setImageResource(ICONS[i]);
            int size = big ? 110 : square ? 46 : 42;
            in.addView(icon, new LinearLayout.LayoutParams(d(size), d(size)));
            if (!square) {
                LinearLayout txt = new LinearLayout(StageActivity.this);
                txt.setOrientation(LinearLayout.VERTICAL);
                txt.setGravity(big ? Gravity.CENTER_HORIZONTAL : Gravity.START);
                TextView a = Cards.text(StageActivity.this, s, big ? 29 : 27, R.font.outfit_medium, Color.WHITE);
                a.setText(KEYS[i][0]);
                txt.addView(a);
                if (!KEYS[i][1].isEmpty()) {
                    TextView b = Cards.text(StageActivity.this, s, 23, R.font.outfit_regular, 0xB3FFFFFF);
                    b.setText(KEYS[i][1]);
                    b.setTag("sub");
                    txt.addView(b);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (big) lp.topMargin = d(20);
                else lp.leftMargin = d(16);
                in.addView(txt, lp);
            }
            k.addView(in, match());
            return k;
        }

        /** A module's card, filling the panel: what last played, the weather, or a running timer. */
        void refresh() {
            for (int i = 0; i < CONTROLS; i++) views[i].removeAllViews();
            // What is playing: the music app that played most recently.
            String best = null;
            long when = 0;
            for (String pkg : new String[]{"com.spotify.tv.android", "com.google.android.youtube.tvmusic", "com.google.android.youtube.tv"}) {
                long t = NowPlaying.artTime(StageActivity.this, pkg);
                if (t > when) {
                    when = t;
                    best = pkg;
                }
            }
            FrameLayout mu = cardView(0xFF3A3326, 0xFF1C1810);
            if (best != null) {
                ImageView cover = new ImageView(StageActivity.this);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setOutlineProvider(Cards.rounded(s, 20));
                cover.setClipToOutline(true);
                cover.setElevation(d(16));
                Bitmap art = NowPlaying.loadArt(StageActivity.this, best);
                if (art != null) cover.setImageBitmap(art);
                mu.addView(cover, at(d(300), d(300), Gravity.TOP | Gravity.START, 34, 76, 0, 0));
                cardWords(mu, label(best), NowPlaying.loadTitle(StageActivity.this, best), "Press OK to open it", 40, true);
            } else {
                cardWords(mu, "Now playing", "Nothing yet", "Music from Spotify or YouTube Music shows here", 40, true);
            }
            views[0].addView(mu, match());
            // The weather, from Home settings' city.
            FrameLayout wx = cardView(0xFF3D7FC9, 0xFF142D55);
            HeaderWeather.Reading r = new HeaderWeather(StageActivity.this, Prefs.weatherLatitude(StageActivity.this),
                    Prefs.weatherLongitude(StageActivity.this)).cached();
            if (r != null) {
                ImageView ic = new ImageView(StageActivity.this);
                ic.setImageResource(r.iconRes());
                wx.addView(ic, at(d(150), d(150), Gravity.TOP | Gravity.END, 0, 34, 34, 0));
                cardWords(wx, "Weather · " + Prefs.weatherPlace(StageActivity.this), r.temp + "°",
                        r.words() + " · high " + r.high + "°, low " + r.low + "°", 130, false);
            } else {
                cardWords(wx, "Weather", "—", "Set your city in Home settings", 130, false);
            }
            views[1].addView(wx, match());
            // A running timer, from the TV's timers app.
            FrameLayout tm = cardView(0xFF4A5160, 0xFF262A33);
            timerBig = cardWords(tm, "Timer", "", "", 130, false);
            views[2].addView(tm, match());
            tick();
        }

        private String label(String pkg) {
            try {
                return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
            } catch (Exception e) {
                return pkg;
            }
        }

        private FrameLayout cardView(int top, int bottom) {
            FrameLayout c = new FrameLayout(StageActivity.this);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
            bg.setCornerRadius(d(34));
            c.setBackground(bg);
            c.setOutlineProvider(Cards.rounded(s, 34));
            return c;
        }

        /** The card's words: a small label at the top, and at the bottom a big line over a quieter one. */
        private TextView cardWords(FrameLayout c, String small, String big, String sub, float bigSize, boolean bold) {
            TextView l = Cards.text(StageActivity.this, s, 24, R.font.outfit_medium, 0xCCFFFFFF);
            l.setText(small);
            c.addView(l, at(-2, -2, Gravity.TOP | Gravity.START, 30, 28, 0, 0));
            LinearLayout bottom = new LinearLayout(StageActivity.this);
            bottom.setOrientation(LinearLayout.VERTICAL);
            TextView b = Cards.text(StageActivity.this, s, bigSize, bold ? R.font.outfit_medium : R.font.outfit_light, Color.WHITE);
            b.setText(big);
            b.setMaxLines(2);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setFontFeatureSettings("tnum");
            TextView u = Cards.text(StageActivity.this, s, 26, R.font.outfit_regular, 0xB8FFFFFF);
            u.setText(sub);
            bottom.addView(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = d(10);
            bottom.addView(u, lp);
            c.addView(bottom, at(-1, -2, Gravity.BOTTOM | Gravity.START, 34, 0, 34, 34));
            return b;
        }

        void time(String t) {
            if (!t.contentEquals(clock.getText())) clock.setText(t);
        }

        /** The timer shows in the bar only while one runs. */
        private boolean timerOn() {
            return Clocks.timer() != null;
        }

        void tick() {
            if (timerBig == null) return;
            if (shown && (mods.get(2).getVisibility() == View.VISIBLE) != timerOn()) {   // a timer started or ended
                if (m == 2 && !timerOn()) {
                    m = CONTROLS;
                    inBar = true;
                }
                StageActivity.this.paint(true);
            }
            String l = Clocks.line(StageActivity.this);
            timerBig.setText(l != null && l.startsWith("Timer · ") ? l.substring(8) : "0:00");
            TextView sub = (TextView) ((LinearLayout) timerBig.getParent()).getChildAt(1);
            sub.setText(l != null && l.startsWith("Timer") ? "Press OK to open it" : "No timer running");
        }

        void open() {
            m = CONTROLS;
            inBar = false;
            q = 0;
        }

        /** Moves within the panel; false when the remote leaves it (it closes). */
        boolean move(int dx, int dy) {
            if (inBar) {
                if (dx > 0 && m < ME) m += m + 1 == 2 && !timerOn() ? 2 : 1;
                else if (dx < 0 && m > 0) m -= m - 1 == 2 && !timerOn() ? 2 : 1;
                else if (dy > 0 && m < ME) {
                    inBar = false;
                    q = 0;
                } else return true;
            } else if (m != CONTROLS) {
                if (dy < 0) inBar = true;
            } else {
                int n = nextKey(q, dx, dy);
                if (n >= 0) q = n;
                else if (dy < 0) inBar = true;
            }
            return true;
        }

        /** The next control in a direction, by where the controls sit. */
        private int nextKey(int i, int dx, int dy) {
            float x = AT[i][0] + AT[i][2] / 2f, y = AT[i][1] + AT[i][3] / 2f;
            int best = -1;
            float bd = Float.MAX_VALUE;
            for (int j = 0; j < AT.length; j++) {
                if (j == i) continue;
                float ox = AT[j][0] + AT[j][2] / 2f - x, oy = AT[j][1] + AT[j][3] / 2f - y;
                boolean ahead = dx != 0 ? ox * dx > AT[i][2] / 2f - 2 : oy * dy > AT[i][3] / 2f - 2;
                if (!ahead) continue;
                float dist = Math.abs(dx != 0 ? ox : oy) + 2 * Math.abs(dx != 0 ? oy : ox);
                if (dist < bd) {
                    bd = dist;
                    best = j;
                }
            }
            return best;
        }

        void press() {
            if (inBar) {
                if (m == ME) startActivity(new Intent(StageActivity.this, SettingsActivity.class));   // your picture: Home settings
                else if (m < CONTROLS) inBar = false;
                StageActivity.this.paint(true);
                return;
            }
            if (m == 0) {
                for (String pkg : new String[]{"com.spotify.tv.android", "com.google.android.youtube.tvmusic"}) {
                    if (NowPlaying.artTime(StageActivity.this, pkg) > 0) {
                        Intent l = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                        if (l != null) {
                            startActivity(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            return;
                        }
                    }
                }
                return;
            }
            if (m == 2) {
                Clocks.open(StageActivity.this);
                return;
            }
            if (m == 1) {
                startActivity(new Intent(StageActivity.this, SettingsActivity.class));
                return;
            }
            if (q == KEYS.length - 1) {
                settings();
                return;
            }
            // Sony's own Quick Settings strip, over the home screen; the panel steps back for it.
            Intent sony = new Intent("com.sony.dtv.smartui.settings.intent.action.SHOW")
                    .setClassName("com.sony.dtv.smartui", "com.sony.dtv.smartui.SmartUiReceiver");
            if (getPackageManager().queryBroadcastReceivers(sony, 0).isEmpty()) {
                settings();
                return;
            }
            sendBroadcast(sony);
            zone = Zone.TRAY;
            StageActivity.this.paint(true);
        }

        private void settings() {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
                // no Settings app
            }
        }

        /** Opens or closes the panel, and lights what has focus in it. */
        void paint(boolean on, boolean animate) {
            long ms = animate ? 520 : 0;
            if (on != shown) {
                shown = on;
                // The bar stretches out to the left; the panel unfolds from under its right end.
                for (int i = 1; i < mods.size(); i++) mods.get(i).setVisibility(on && (i != 2 || timerOn()) ? View.VISIBLE : View.GONE);
                ((GradientDrawable) bar.getBackground()).setColor(on ? 0x8C464A52 : 0x73282A30);
                card.setPivotX(d(W) * 0.92f);
                card.setPivotY(0);
                card.animate().cancel();
                if (on) {
                    card.setVisibility(View.VISIBLE);
                    card.setScaleX(0.35f);
                    card.setScaleY(0.1f);
                    card.setTranslationY(-d(60));
                    card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setStartDelay(animate ? 140 : 0)
                            .setDuration(ms).setInterpolator(EASE).withEndAction(() -> card.animate().setStartDelay(0)).start();
                    for (int i = 0; i < keys.size(); i++) {   // the controls drop in one after another
                        View k = keys.get(i);
                        k.setAlpha(0f);
                        k.setTranslationY(-d(18));
                        k.animate().alpha(1f).translationY(0).setStartDelay(animate ? 200 + 30L * i : 0).setDuration(ms)
                                .setInterpolator(EASE).withEndAction(() -> k.animate().setStartDelay(0)).start();
                    }
                } else {
                    card.animate().alpha(0f).scaleX(0.35f).scaleY(0.1f).translationY(-d(60)).setStartDelay(0)
                            .setDuration(animate ? 380 : 0).setInterpolator(EASE)
                            .withEndAction(() -> card.setVisibility(View.INVISIBLE)).start();
                }
            }
            if (shown) mods.get(2).setVisibility(timerOn() ? View.VISIBLE : View.GONE);
            for (int i = 0; i < 4; i++) views[i].setVisibility(i == Math.min(m, CONTROLS) ? View.VISIBLE : View.GONE);
            for (int i = 0; i < mods.size(); i++) {
                ImageView v = mods.get(i);
                boolean sel = on && i == m, lit = sel && inBar;
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(lit ? Color.WHITE : sel ? 0x38FFFFFF : 0x00000000);
                v.setBackground(bg);
                v.setImageTintList(ColorStateList.valueOf(lit ? 0xFF111111 : 0xD9FFFFFF));
                v.animate().scaleX(lit ? 1.15f : 1f).scaleY(lit ? 1.15f : 1f).setDuration(300).setInterpolator(SPRING).start();
            }
            boolean meOn = on && inBar && m == ME;
            me.animate().scaleX(meOn ? 1.15f : 1f).scaleY(meOn ? 1.15f : 1f).setDuration(300).setInterpolator(SPRING).start();
            me.setForeground(meOn ? ring() : null);
            for (int i = 0; i < 3; i++) {
                View c = views[i].getChildCount() > 0 ? views[i].getChildAt(0) : null;
                if (c == null) continue;
                boolean cf = on && !inBar && m == i;
                c.setForeground(cf ? ring34() : null);
                c.animate().scaleX(cf ? 1.03f : 1f).scaleY(cf ? 1.03f : 1f).setDuration(300).setInterpolator(EASE).start();
            }
            for (int i = 0; i < keys.size(); i++) {
                FrameLayout k = keys.get(i);
                boolean kf = on && !inBar && m == CONTROLS && i == q;
                ((GradientDrawable) k.getBackground()).setColor(kf ? 0xF0FFFFFF : 0x1FFFFFFF);
                tint(k, kf ? 0xFF111111 : Color.WHITE);
                k.setElevation(kf ? d(16) : 0);
                k.animate().scaleX(kf ? 1.07f : 1f).scaleY(kf ? 1.07f : 1f).setDuration(animate ? 350 : 0).setInterpolator(SPRING).start();
            }
        }

        private void tint(View v, int colour) {
            if (v instanceof ImageView) ((ImageView) v).setImageTintList(ColorStateList.valueOf(colour));
            else if (v instanceof TextView) ((TextView) v).setTextColor("sub".equals(v.getTag()) ? (colour & 0x00FFFFFF) | 0xB3000000 : colour);
            else if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) tint(g.getChildAt(i), colour);
            }
        }

        private GradientDrawable ring() {
            GradientDrawable r = new GradientDrawable();
            r.setShape(GradientDrawable.OVAL);
            r.setStroke(d(4), Color.WHITE);
            return r;
        }

        private GradientDrawable ring34() {
            GradientDrawable r = new GradientDrawable();
            r.setCornerRadius(d(34));
            r.setStroke(d(4), Color.WHITE);
            return r;
        }
    }

    // ---- small helpers ------------------------------------------------------------------------------

    private int d(float designPx) {
        return Math.round(designPx * s);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private FrameLayout.LayoutParams at(int w, int h, int gravity, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(d(left), d(top), d(right), d(bottom));
        return lp;
    }
}
