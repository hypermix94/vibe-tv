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
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
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
    /** True while the stage shows a logo or an icon card (not a full picture): the grid hides it, keeping the glow. */
    private boolean stageIsCard;
    private View veil, chevron;
    private TextView title, line, gridName;
    /** The line of the song being sung now, just above the tray, while the focused music app plays. */
    private TextView lyric;
    private Lyrics songLyrics = Lyrics.NONE;
    private String songKey = "", playingPkg = "";
    /** A trending title's own logo, in place of its name in words. */
    private ImageView titleLogo;
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

    /** Home comes back the way you expect: on the tray, with the panel and any menu closed. */
    @Override
    protected void onStop() {
        super.onStop();
        if (menu != null) closeMenu();
        if (moving) stopMoving();
        if (zone != Zone.TRAY) {
            zone = Zone.TRAY;
            paint(false);
        }
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        main.removeCallbacks(nextSlide);
        main.removeCallbacks(lyricTick);
        if (watched != null) {
            watched.unregisterCallback(songChanged);
            watched = null;
        }
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
        boolean unset = Apps.dockOrder(this).isEmpty();
        for (String pkg : Apps.dockOrder(this)) {
            App a = all.remove(pkg);
            if (a != null && dock.size() < Apps.MAX_DOCK) dock.add(a);
        }
        others.addAll(all.values());   // every app is on Home: the grid holds all that are not on the tray
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

    /**
     * Where a plain banner's logo is, and the matrix that draws the banner so that logo is 78% of the tile's width
     * (or 56% of its height, for tall logos), centred in the tile.
     */
    private static android.graphics.Matrix logoFit(Drawable banner, int tileW, int tileH) {
        int w = 160, h = 90;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Drawable copy = banner.getConstantState() != null ? banner.getConstantState().newDrawable().mutate() : banner;
        copy.setBounds(0, 0, w, h);
        copy.draw(new Canvas(b));
        int bg = b.getPixel(0, 0), l = w, t = h, r = -1, bo = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (near(bg, b.getPixel(x, y))) continue;
                l = Math.min(l, x);
                r = Math.max(r, x);
                t = Math.min(t, y);
                bo = Math.max(bo, y);
            }
        }
        b.recycle();
        android.graphics.Matrix m = new android.graphics.Matrix();
        int iw = Math.max(1, banner.getIntrinsicWidth()), ih = Math.max(1, banner.getIntrinsicHeight());
        if (r < 0) {   // nothing found: the banner as a whole, fitted
            float k = Math.min(tileW / (float) iw, tileH / (float) ih);
            m.setScale(k, k);
            m.postTranslate((tileW - iw * k) / 2f, (tileH - ih * k) / 2f);
            return m;
        }
        float fw = (r - l + 1) / (float) w, fh = (bo - t + 1) / (float) h;   // the logo's share of the banner
        float cx = (l + r + 1) / 2f / w, cy = (t + bo + 1) / 2f / h;          // and its middle
        // How wide the whole banner must be drawn for its logo to be the target size.
        float bw = Math.min(tileW * 0.78f / fw, tileH * 0.56f / fh * iw / ih);
        float k = bw / iw;
        m.setScale(k, k);
        m.postTranslate(tileW / 2f - cx * iw * k, tileH / 2f - cy * ih * k);
        return m;
    }

    /** An app's icon drawn at 512 px from the sharpest copy it ships (TV icons are often small at the TV's density). */
    private Bitmap hiResIcon(App a) {
        Drawable icon = a.icon;
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(a.pkg, 0);
            if (ai.icon != 0) {
                Drawable big = getPackageManager().getResourcesForApplication(ai)
                        .getDrawableForDensity(ai.icon, android.util.DisplayMetrics.DENSITY_XXXHIGH, null);
                if (big != null) icon = big;
            }
        } catch (Exception ignored) {
            // the icon as listed
        }
        Bitmap b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (icon instanceof android.graphics.drawable.AdaptiveIconDrawable) {   // its own background, full bleed
            android.graphics.drawable.AdaptiveIconDrawable ad = (android.graphics.drawable.AdaptiveIconDrawable) icon;
            int bleed = 512 / 6;   // the adaptive icon's outer 1/6 on each side is safe-zone overscan
            for (Drawable layer : new Drawable[]{ad.getBackground(), ad.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(-bleed, -bleed, 512 + bleed, 512 + bleed);
                layer.draw(c);
            }
        } else {
            c.drawColor(colourOf(a));
            icon.setBounds(64, 64, 448, 448);
            icon.draw(c);
        }
        return b;
    }

    /** Dark grey lettering in a logo made for white backgrounds turns white, for the dark stage; colour stays. */
    private static Bitmap whiten(Bitmap src) {
        Bitmap b = src.copy(Bitmap.Config.ARGB_8888, true);
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        float[] hsv = new float[3];
        for (int i = 0; i < px.length; i++) {
            if (Color.alpha(px[i]) == 0) continue;
            Color.colorToHSV(px[i], hsv);
            if (hsv[1] < 0.25f && hsv[2] < 0.45f) px[i] = (px[i] & 0xFF000000) | 0x00FFFFFF;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    /** True when every edge of a banner is one plain colour (Spotify's black, Netflix's white), not a gradient. */
    private static boolean plainEdges(Drawable d) {
        Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, 32, 18);
        copy.draw(new Canvas(b));
        int first = b.getPixel(0, 0);
        boolean plain = true;
        for (int x = 0; x < 32 && plain; x += 2) plain = near(first, b.getPixel(x, 0)) && near(first, b.getPixel(x, 17));
        for (int y = 0; y < 18 && plain; y += 2) plain = near(first, b.getPixel(0, y)) && near(first, b.getPixel(31, y));
        b.recycle();
        return plain;
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
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x66000000, 0x00000000, 0x00000000}),
                corner()}));
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
        // A soft shadow keeps the words readable over bright artwork (a yellow cartoon, a white sky).
        title.setShadowLayer(d(24), 0, d(2), 0x99000000);
        line.setShadowLayer(d(12), 0, d(1), 0xB3000000);
        titleLogo = new ImageView(this);
        titleLogo.setAdjustViewBounds(true);
        titleLogo.setScaleType(ImageView.ScaleType.FIT_START);
        titleLogo.setMaxWidth(d(640));
        titleLogo.setMaxHeight(d(170));
        titleLogo.setVisibility(View.GONE);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = d(24);
        tl.bottomMargin = d(18);
        words.addView(titleLogo, tl);
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

        lyric = Cards.text(this, s, 36, R.font.outfit_medium, Color.WHITE);
        lyric.setTypeface(Cards.weight(this, R.font.outfit_medium, 700));   // bold, as the music screen's lyrics
        lyric.setMaxLines(2);   // a long line wraps onto a second, never more
        lyric.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lyric.setLineSpacing(0, 1.05f);
        lyric.setShadowLayer(d(14), 0, d(2), 0x99000000);
        lyric.setAlpha(0f);
        // Sits on a line just above the tray: one line or two, it grows upward, and ends well short of the cover.
        FrameLayout lyricBox = new FrameLayout(this);
        lyricBox.setClipChildren(false);
        lyricBox.addView(lyric, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START));
        page.addView(lyricBox, at(d(860), d(110), Gravity.TOP | Gravity.START, LEFT, trayTop - 140, 0, 0));

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
            int shadow = luminance(col) > 0.6f ? Color.BLACK : lighter(col);   // a white tile casts a shadow, not a haze
            t.setOutlineSpotShadowColor(shadow);
            t.setOutlineAmbientShadowColor(Color.BLACK);
        }
        ImageView img = new ImageView(this);
        if (a.banner != null) {
            img.setImageDrawable(a.banner);
            if (plainEdges(a.banner)) {
                // A banner on a plain colour: every logo is scaled to the same optical size in its tile (whatever share
                // of its banner it fills), centred, with the tile's colour around it.
                img.setScaleType(ImageView.ScaleType.MATRIX);
                img.setImageMatrix(logoFit(a.banner, d(tw), d(th)));
            } else {
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);   // a gradient banner fills the 5:3 tile, no bars
            }
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
    /** Which trending title shows for the focused app; the next one comes every fifteen seconds. */
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
        android.media.session.MediaController player = MusicScreen.playingMusic(this);
        String song = player != null && player.getPackageName().equals(a.pkg) && player.getMetadata() != null
                ? player.getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE) : "";
        String key = a.pkg + "/" + NowPlaying.artTime(this, a.pkg) + "/" + slide + "/" + song;
        if (key.equals(stageKey)) return;
        stageKey = key;
        main.removeCallbacks(nextSlide);
        String said = NowPlaying.loadTitle(this, a.pkg);
        android.media.session.MediaController live = MusicScreen.playingMusic(this);
        boolean playingNow = live != null && live.getPackageName().equals(a.pkg);
        String nowTitle = playingNow && live.getMetadata() != null ? live.getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE) : null;
        new Thread(() -> {
            // What is trending on a streaming app comes first (with your TMDB key); a new list redraws the stage.
            List<Trending.Item> hot = Trending.enabled(this) && Trending.covers(a.pkg) ? Trending.cached(this, a.pkg) : new ArrayList<>();
            Trending.Item item = hot.isEmpty() ? null : hot.get(slide % hot.size());
            Bitmap pic = item != null ? Trending.picture(item.picture) : null;
            Bitmap showLogo = item != null ? Trending.logo(item.logo) : null;
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
            // Music: the album's own main colour fills the screen, as on Spotify's screensaver, and the cover sits
            // on the right with rounded corners and a soft shadow.
            boolean square = pic == null && art != null && art.getWidth() < art.getHeight() * 1.3f;
            int albumColour = square ? MusicScreen.primary(art) : 0;
            Bitmap coverCard = square ? card(art, d(560), d(18), d(48)) : null;
            // The app's logo: yours (local-logos) first, else the service's own from TMDB, crisp at any size.
            Bitmap own = ownLogo(a.pkg);
            Bitmap service = own == null && art == null ? Trending.serviceLogo(this, a.pkg) : null;
            Bitmap logo = own != null ? trim(unmatte(own)) : service != null ? whiten(trim(service)) : null;
            int logoGlow = glowColour(own != null ? own : service, null);   // a white logo: the banner's colour instead
            int glow = logoGlow != NO_GLOW ? logoGlow : glowColour(null, a.banner != null ? a.banner : a.icon);
            boolean light = a.banner != null && luminance(colourOf(a)) > 0.7f;
            // Any other app: its own icon at the highest resolution it ships, as a rounded card.
            Bitmap iconCard = logo == null && art == null ? card(hiResIcon(a), d(300), d(66), d(48)) : null;
            main.post(() -> {
                if (!key.equals(stageKey) || isDestroyed()) return;
                int next = 1 - picOn;
                ImageView p = pics[next];
                ImageView f = fills[next];
                f.setImageDrawable(null);
                f.setColorFilter(null);
                if (Build.VERSION.SDK_INT >= 31) f.setRenderEffect(null);
                boolean plain = art == null;
                if (plain && logo != null) {
                    // Your logo: centred between the name (its line ends near 280) and the tray (from 770), on a soft
                    // glow in the app's own colour, never larger than the picture itself.
                    p.setImageBitmap(logo);
                    p.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    float w = Math.min(720, logo.getWidth() / s), h = w * logo.getHeight() / logo.getWidth();
                    p.setPadding(d(960 - w / 2), d(525 - h / 2), d(960 - w / 2), d(1080 - 525 - h / 2));
                    f.setBackground(glow(glow));
                } else if (plain) {
                    // Any other app: its icon card in the middle of the whole screen, over its colours blurred.
                    p.setImageBitmap(iconCard);
                    p.setScaleType(ImageView.ScaleType.CENTER);
                    p.setPadding(0, 0, 0, 0);
                    if (a.banner != null && !light && Build.VERSION.SDK_INT >= 31) {
                        f.setBackgroundColor(colourOf(a));
                        f.setImageDrawable(a.banner.getConstantState() != null ? a.banner.getConstantState().newDrawable() : a.banner);
                        f.setColorFilter(0x59000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
                        f.setRenderEffect(RenderEffect.createBlurEffect(d(90), d(90), Shader.TileMode.CLAMP));
                    } else {
                        f.setBackground(glow(glow));
                    }
                } else if (square) {
                    p.setImageBitmap(coverCard);
                    p.setScaleType(ImageView.ScaleType.CENTER);
                    // On the right, its middle level with the space between the time pill and the tray.
                    int cw = coverCard.getWidth(), ch = coverCard.getHeight();
                    int right = d(1920 - 120) , midY = d(445);
                    p.setPadding(right - cw, midY - ch / 2, d(1920) - right, d(1080) - midY - ch / 2);
                    f.setBackgroundColor(albumColour);
                } else {
                    p.setImageBitmap(art);
                    p.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    p.setPadding(0, 0, 0, 0);
                    f.setBackgroundColor(Color.BLACK);
                }
                long ms = animate ? 600 : 0;
                p.animate().cancel();
                p.setTranslationY(0);
                if (square && animate) {   // the album rises into place with a little bounce
                    p.setTranslationY(d(70));
                    p.animate().translationY(0).setDuration(750).setInterpolator(new OvershootInterpolator(1.8f)).start();
                }
                stageIsCard = plain;
                p.animate().alpha(zone == Zone.GRID && plain ? 0f : 1f).setDuration(ms).setInterpolator(EASE).start();
                f.animate().alpha(1f).setDuration(ms).start();
                pics[picOn].animate().alpha(0f).setDuration(ms).start();
                fills[picOn].animate().alpha(0f).setDuration(ms).start();
                picOn = next;
                veil.animate().alpha(plain ? 0.6f : square ? 0.45f : 1f).setDuration(ms).start();   // music: the album colour stays a clean field
                // The name, large at the top left for every app, as on Apple TV; the line under it says what is on.
                boolean bigName = true;
                title.setText(item != null ? item.title : a.label);
                titleLogo.setImageBitmap(showLogo);
                titleLogo.setVisibility(showLogo != null ? View.VISIBLE : View.GONE);   // the show's own lettering
                title.setVisibility(showLogo != null ? View.GONE : View.VISIBLE);
                String what = item != null ? "Trending on " + android.text.TextUtils.htmlEncode(a.label)
                        : nowTitle != null ? "Playing now · " + android.text.TextUtils.htmlEncode(nowTitle)
                        : said.isEmpty() ? lastOpened(a.pkg) : "Last played " + android.text.TextUtils.htmlEncode(said);
                line.setText(android.text.Html.fromHtml(bigName ? what
                        : "<b>" + android.text.TextUtils.htmlEncode(a.label) + "</b> · " + what, android.text.Html.FROM_HTML_MODE_LEGACY));
                if (hot.size() > 1) main.postDelayed(nextSlide, 15_000);
                followSong(playingNow ? live : null);
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

    /** The focused music app's song: its lyrics fetched once, then the line being sung shown above the tray. */
    private void followSong(android.media.session.MediaController live) {
        main.removeCallbacks(lyricTick);
        // Listen to the player itself: a new song, a pause or a play redraws the stage (the name, the cover, the colour).
        if (watched != null && (live == null || watched != live)) {
            watched.unregisterCallback(songChanged);
            watched = null;
        }
        if (live != null && watched == null) {
            watched = live;
            live.registerCallback(songChanged, main);
        }
        if (live == null || live.getMetadata() == null) {
            playingPkg = "";
            lyric.animate().alpha(0f).setDuration(300).start();
            return;
        }
        playingPkg = live.getPackageName();
        android.media.MediaMetadata m = live.getMetadata();
        String name = m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE), who = m.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        String key = name + "/" + who;
        if (!key.equals(songKey)) {
            songKey = key;
            songLyrics = Lyrics.NONE;
            String album = m.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM);
            long duration = m.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
            new Thread(() -> {
                Lyrics found = Lyrics.find(this, name, who, album, duration);
                main.post(() -> {
                    if (key.equals(songKey) && found != null) songLyrics = found;
                });
            }, "home-lyrics").start();
        }
        lyricTick.run();
    }

    private android.media.session.MediaController watched;
    private final Runnable restage = () -> {
        stageKey = "";
        stage(true);
    };
    private final android.media.session.MediaController.Callback songChanged = new android.media.session.MediaController.Callback() {
        @Override
        public void onMetadataChanged(android.media.MediaMetadata metadata) {
            main.removeCallbacks(restage);
            main.postDelayed(restage, 1200);   // a moment for the new cover to be saved
        }

        @Override
        public void onPlaybackStateChanged(android.media.session.PlaybackState state) {
            main.removeCallbacks(restage);
            main.postDelayed(restage, 400);   // "Playing now" or "Last played"
        }
    };

    private final Runnable lyricTick = new Runnable() {
        @Override
        public void run() {
            android.media.session.MediaController live = MusicScreen.playingMusic(StageActivity.this);
            App a = focused();
            boolean show = zone == Zone.TRAY && live != null && a != null && live.getPackageName().equals(a.pkg) && songLyrics.timed;
            String text = "";
            if (show) {
                android.media.session.PlaybackState st = live.getPlaybackState();
                long pos = st == null ? 0 : st.getPosition() + (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                // In step with the song's own position, just early enough to cover the quick swap below.
                for (Lyrics.Line l : songLyrics.lines) if (l.at <= pos + 150) text = l.text;
            }
            if (!text.contentEquals(lyric.getText())) {   // the next line rises in
                String t = text;
                lyric.animate().alpha(0f).translationY(-d(8)).setDuration(90).withEndAction(() -> {
                    lyric.setText(t);
                    lyric.setTranslationY(d(8));
                    lyric.animate().alpha(t.isEmpty() ? 0f : 1f).translationY(0).setDuration(220).setInterpolator(EASE).start();
                }).start();
            }
            if (live != null) main.postDelayed(this, 120);
        }
    };

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
        // Dark grey lettering made for a white banner would vanish on the dark stage: it turns white; colour stays.
        float[] hsv = new float[3];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            if (Color.alpha(c) == 0) continue;
            Color.colorToHSV(c, hsv);
            if (hsv[1] < 0.25f && hsv[2] < 0.45f) px[i] = (c & 0xFF000000) | 0x00FFFFFF;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    /**
     * A logo on a plain dark background with the background taken away: each pixel keeps as much of itself as differs
     * from the background, so soft edges stay soft and the box around it is gone (JPEG logos are rarely pure black).
     */
    private static Bitmap unmatte(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int bg = px[0];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int dr = Color.red(c) - Color.red(bg), dg = Color.green(c) - Color.green(bg), db = Color.blue(c) - Color.blue(bg);
            float a = Math.min(1f, Math.max(0f, (Math.max(Math.abs(dr), Math.max(Math.abs(dg), Math.abs(db))) - 12) / 90f));
            if (a <= 0f) {
                px[i] = 0;
                continue;
            }
            int r = clamp(Color.red(bg) + Math.round(dr / a)), g = clamp(Color.green(bg) + Math.round(dg / a)), b = clamp(Color.blue(bg) + Math.round(db / a));
            px[i] = Color.argb(Math.round(a * 255), r, g, b);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** The picture cut to what it actually draws: no clear margins, so it lines up with the words under it. */
    private static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), l = w, t = h, r = -1, bo = -1;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (Color.alpha(px[y * w + x]) < 24) continue;
                l = Math.min(l, x);
                r = Math.max(r, x);
                t = Math.min(t, y);
                bo = Math.max(bo, y);
            }
        }
        return r < 0 ? b : Bitmap.createBitmap(b, l, t, r - l + 1, bo - t + 1);
    }

    private static final int NO_GLOW = 0xFF3A3A44;

    /** The app's colour for its glow: the average of its logo's (or banner's) vivid pixels, else a quiet grey. */
    private static int glowColour(Bitmap logo, Drawable banner) {
        Bitmap b = logo;
        if (b == null && banner != null) {
            b = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
            Drawable copy = banner.getConstantState() != null ? banner.getConstantState().newDrawable().mutate() : banner;
            copy.setBounds(0, 0, 64, 36);
            copy.draw(new Canvas(b));
        }
        if (b == null) return NO_GLOW;
        Bitmap small = Bitmap.createScaledBitmap(b, 64, Math.max(1, 64 * b.getHeight() / b.getWidth()), true);
        long r = 0, g = 0, bl = 0, n = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int c = small.getPixel(x, y);
                Color.colorToHSV(c, hsv);
                if (Color.alpha(c) < 200 || hsv[1] < 0.4f || hsv[2] < 0.3f) continue;
                r += Color.red(c);
                g += Color.green(c);
                bl += Color.blue(c);
                n++;
            }
        }
        if (n < 8) return NO_GLOW;
        return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    /** A picture with rounded corners on a soft drop shadow, {@code size} wide, with room around it for the shadow. */
    private static Bitmap card(Bitmap art, int size, int radius, int shadow) {
        Bitmap out = Bitmap.createBitmap(size + 2 * shadow, size + 2 * shadow, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        android.graphics.RectF box = new android.graphics.RectF(shadow, shadow, shadow + size, shadow + size);
        android.graphics.Paint shade = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        shade.setColor(Color.BLACK);
        shade.setShadowLayer(shadow * 0.6f, 0, shadow * 0.3f, 0x8C000000);
        c.drawRoundRect(box, radius, radius, shade);
        android.graphics.Paint img = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        Bitmap scaled = Bitmap.createScaledBitmap(art, size, size, true);
        img.setShader(new android.graphics.BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        c.translate(shadow, shadow);
        c.drawRoundRect(new android.graphics.RectF(0, 0, size, size), radius, radius, img);
        return out;
    }

    /** A soft shade in the top left corner, behind the title and its line, so they read over any picture. */
    private Drawable corner() {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.12f, 0.14f);
        g.setGradientRadius(d(900));
        g.setColors(new int[]{0x8C000000, 0x4D000000, 0x00000000});
        return g;
    }

    /** A dark stage lit softly from the right in {@code colour}, like light from off screen. */
    private Drawable glow(int colour) {
        GradientDrawable light = new GradientDrawable();
        light.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        light.setGradientCenter(0.7f, 0.38f);
        light.setGradientRadius(d(1150));
        light.setColors(new int[]{(colour & 0x00FFFFFF) | 0x8C000000, (colour & 0x00FFFFFF) | 0x26000000, 0x00000000});
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{new android.graphics.drawable.ColorDrawable(0xFF0B0B0D), light});
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
        // In the grid a logo or icon card would blur into a smudge under the tiles: only its glow stays.
        pics[picOn].animate().alpha(inGrid && stageIsCard ? 0f : 1f).setDuration(animate ? 400 : 0).start();
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
                ? new String[]{"Move", "Remove from dock", "App info", remove}
                : new String[]{"Add to dock", "App info", remove};
        int[] icons = inTray
                ? new int[]{R.drawable.ic_menu_move, R.drawable.ic_menu_dock_remove, R.drawable.ic_menu_info, R.drawable.ic_menu_delete}
                : new int[]{R.drawable.ic_menu_dock_add, R.drawable.ic_menu_info, R.drawable.ic_menu_delete};
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
     * Up: the time pill at the top right stretches left into a bar (the time, a running timer, the controls, you) and
     * a panel unfolds from under it. Left and Right along the bar swap what the panel shows; Down goes into it.
     * The time opens a card of the time, day, date and weather, its background the weather itself. The controls are
     * Wi-Fi (its name, strength and speed), Inputs, Speakers (where the sound goes), Volume (Left and Right turn it, as
     * the remote's keys do) and Brightness (Sony's picture brightness lives in its Picture settings, which it opens).
     */
    private final class ControlPanel {
        private static final int CLOCK = 0, TIMER = 1, CONTROLS = 2, ME = 3, W = 600, PAD = 24;
        private static final int WIFI = 0, INPUTS = 1, SPEAKERS = 2, VOLUME = 3, BRIGHTNESS = 4;
        private final LinearLayout bar;
        private final TextView clock;
        /** The bar's modules in order: the time, the timer, the controls (your picture is {@link #me}). */
        private final List<View> mods = new ArrayList<>();
        private final TextView me;
        private final FrameLayout card;
        private final FrameLayout[] views = new FrameLayout[3];
        private final List<FrameLayout> keys = new ArrayList<>();
        private int m = CONTROLS, q;
        private boolean inBar, shown;
        private TextView timerBig, clockBig, clockDate, wifiName, wifiSub, speakerSub, volumeValue;
        private WifiIcon wifiIcon;
        private View volumeFill;
        private FrameLayout clockCard;
        private final AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);

        private final String[][] KEYS = {{"Wi-Fi", ""}, {"Inputs", "Change input"}, {"Speakers", ""},
                {"Volume", ""}, {"Brightness", "Picture settings"}};
        private final int[] ICONS = {0, R.drawable.ic_cc_inputs, R.drawable.ic_cc_sound, R.drawable.ic_cc_sound, R.drawable.ic_cc_bright};
        /** The Settings page each control opens; null for Volume, which Left and Right turn. */
        private final String[] KEY_PAGES = {android.provider.Settings.ACTION_WIFI_SETTINGS, "com.android.tv.action.VIEW_INPUTS",
                "android.settings.AUDIO_OUTPUT_SETTINGS", null, "android.settings.DISPLAY_SETTINGS"};
        /** Where each control sits in the panel, in design pixels: x, y, width, height. */
        private final int[][] AT = {{0, 0, 266, 266}, {286, 0, 266, 123}, {286, 143, 266, 123}, {0, 286, 552, 123}, {0, 429, 552, 123}};

        ControlPanel() {
            bar = new LinearLayout(StageActivity.this);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(d(8), 0, d(8), 0);
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
            // The time is the first module: a pill of its own that lights when the remote is on it.
            clock = Cards.text(StageActivity.this, s, 26, R.font.outfit_regular, Color.WHITE);
            clock.setFontFeatureSettings("tnum");
            clock.setGravity(Gravity.CENTER);
            clock.setPadding(d(18), 0, d(18), 0);
            bar.addView(clock, new LinearLayout.LayoutParams(-2, d(50)));
            mods.add(clock);
            for (int icon : new int[]{R.drawable.ic_cc_timer, R.drawable.ic_cc_toggles}) {
                ImageView v = new ImageView(StageActivity.this);
                v.setImageResource(icon);
                v.setPadding(d(11), d(11), d(11), d(11));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(50), d(50));
                lp.leftMargin = d(3);
                lp.rightMargin = d(3);
                bar.addView(v, lp);
                v.setVisibility(View.GONE);   // at rest only the time and your picture show
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
            for (int i = 0; i < views.length; i++) {
                views[i] = new FrameLayout(StageActivity.this);
                views[i].setClipChildren(false);
                card.addView(views[i], new FrameLayout.LayoutParams(-1, -1));
            }
            for (int i = 0; i < KEYS.length; i++) {
                FrameLayout k = key(i);
                views[CONTROLS].addView(k, at(d(AT[i][2]), d(AT[i][3]), Gravity.TOP | Gravity.START, AT[i][0], AT[i][1], 0, 0));
                keys.add(k);
            }
            // The time card: the weather is its background.
            clockCard = new FrameLayout(StageActivity.this);
            clockCard.setOutlineProvider(Cards.rounded(s, 34));
            clockCard.setClipToOutline(true);
            views[CLOCK].addView(clockCard, match());
            // The timer card.
            FrameLayout tm = new FrameLayout(StageActivity.this);
            GradientDrawable tbg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF4A5160, 0xFF262A33});
            tbg.setCornerRadius(d(34));
            tm.setBackground(tbg);
            timerBig = cardWords(tm, "Timer", "", "", 130, false);
            views[TIMER].addView(tm, match());
            refresh();
        }

        private FrameLayout key(int i) {
            boolean big = i == WIFI;
            FrameLayout k = new FrameLayout(StageActivity.this);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x1FFFFFFF);
            bg.setCornerRadius(d(34));
            k.setBackground(bg);
            k.setContentDescription(KEYS[i][0]);
            LinearLayout in = new LinearLayout(StageActivity.this);
            in.setOrientation(big ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            in.setGravity(big ? Gravity.START | Gravity.BOTTOM : Gravity.CENTER_VERTICAL);
            in.setPadding(d(26), big ? d(24) : 0, d(26), big ? d(24) : 0);
            View icon;
            if (big) {
                wifiIcon = new WifiIcon(StageActivity.this);
                icon = wifiIcon;
            } else {
                ImageView iv = new ImageView(StageActivity.this);
                iv.setImageResource(ICONS[i]);
                icon = iv;
            }
            int size = big ? 96 : 42;
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(d(size), d(size));
            if (big) ilp.bottomMargin = d(26);
            in.addView(icon, ilp);
            LinearLayout txt = new LinearLayout(StageActivity.this);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView a = Cards.text(StageActivity.this, s, big ? 30 : 27, R.font.outfit_medium, Color.WHITE);
            a.setText(KEYS[i][0]);
            a.setSingleLine(true);
            a.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(a);
            TextView b = Cards.text(StageActivity.this, s, 23, R.font.outfit_regular, 0xB3FFFFFF);
            b.setText(KEYS[i][1]);
            b.setTag("sub");
            b.setSingleLine(true);
            txt.addView(b);
            if (i == WIFI) {   // a long network name gets two lines
                a.setSingleLine(false);
                a.setMaxLines(2);
                a.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, d(27));
                wifiName = a;
                wifiSub = b;
            } else if (i == SPEAKERS) {
                speakerSub = b;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (!big) lp.leftMargin = d(18);
            if (big) lp = new LinearLayout.LayoutParams(-1, -2);
            in.addView(txt, lp);
            if (i == VOLUME) {   // the level: a number, and a bar under the whole tile
                volumeValue = Cards.text(StageActivity.this, s, 27, R.font.outfit_medium, Color.WHITE);
                volumeValue.setFontFeatureSettings("tnum");
                in.addView(volumeValue);
                FrameLayout track = new FrameLayout(StageActivity.this);
                GradientDrawable tr = new GradientDrawable();
                tr.setColor(0x33FFFFFF);
                tr.setCornerRadius(d(4));
                track.setBackground(tr);
                volumeFill = new View(StageActivity.this);
                GradientDrawable fl = new GradientDrawable();
                fl.setColor(Color.WHITE);
                fl.setCornerRadius(d(4));
                volumeFill.setBackground(fl);
                volumeFill.setTag("fill");
                track.addView(volumeFill, new FrameLayout.LayoutParams(0, -1));
                k.addView(track, at(-1, d(8), Gravity.BOTTOM, 26, 0, 26, 16));
            }
            k.addView(in, match());
            return k;
        }

        /** Reads what changes: the Wi-Fi, where the sound goes, the volume, the weather. */
        void refresh() {
            // Wi-Fi: its name (with location access), its strength in words, and its link speed.
            try {
                WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
                @SuppressWarnings("deprecation")
                android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    wifiName.setText("Ethernet");
                    wifiSub.setText("Connected by cable");
                } else if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || info == null) {
                    wifiName.setText("Wi-Fi");
                    wifiSub.setText("Not connected");
                } else {
                    String ssid = info.getSSID();
                    if (ssid == null || ssid.contains("unknown ssid")) ssid = "Wi-Fi";
                    wifiName.setText(ssid.replace("\"", ""));
                    int level = WifiManager.calculateSignalLevel(info.getRssi(), 4);
                    String strength = new String[]{"Weak", "Fair", "Good", "Excellent"}[Math.max(0, Math.min(3, level))];
                    int speed = info.getLinkSpeed();
                    wifiSub.setText(speed > 0 ? strength + " · " + speed + " Mbps" : strength);
                }
            } catch (RuntimeException e) {
                wifiName.setText("Wi-Fi");
                wifiSub.setText("");
            }
            wifiIcon.refresh();
            // Where the sound goes.
            String out = "TV speakers";
            for (android.media.AudioDeviceInfo dev : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = dev.getType();
                if (t == android.media.AudioDeviceInfo.TYPE_HDMI_ARC || (Build.VERSION.SDK_INT >= 31 && t == android.media.AudioDeviceInfo.TYPE_HDMI_EARC)) {
                    out = "Audio system";
                    break;
                }
                if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) out = "Bluetooth";
            }
            speakerSub.setText(out);
            showVolume();
            showClock();
            new HeaderWeather(StageActivity.this, Prefs.weatherLatitude(StageActivity.this), Prefs.weatherLongitude(StageActivity.this))
                    .refresh(r -> showClock());
            tick();
        }

        private void showVolume() {
            int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC), max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
            volumeValue.setText(muted ? "Muted" : String.valueOf(v));
            View track = (View) volumeFill.getParent();
            track.post(() -> {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) volumeFill.getLayoutParams();
                lp.width = muted ? 0 : Math.round(track.getWidth() * v / (float) max);
                volumeFill.setLayoutParams(lp);
            });
        }

        /** The time card: the time large, the day and date, and the weather, over a picture of the weather. */
        private void showClock() {
            clockCard.removeAllViews();
            HeaderWeather.Reading r = new HeaderWeather(StageActivity.this, Prefs.weatherLatitude(StageActivity.this),
                    Prefs.weatherLongitude(StageActivity.this)).cached();
            clockCard.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, sky(r)));
            if (r != null) {
                ImageView ic = new ImageView(StageActivity.this);
                ic.setImageResource(r.iconRes());
                ic.setAlpha(0.95f);
                clockCard.addView(ic, at(d(190), d(190), Gravity.TOP | Gravity.END, 0, 26, 26, 0));
            }
            boolean h24 = DateFormat.is24HourFormat(StageActivity.this);
            Date now = new Date();
            TextView small = Cards.text(StageActivity.this, s, 26, R.font.outfit_medium, 0xE6FFFFFF);
            small.setText(DateFormat.format("EEEE", now));
            clockCard.addView(small, at(-2, -2, Gravity.TOP | Gravity.START, 32, 30, 0, 0));
            LinearLayout bottom = new LinearLayout(StageActivity.this);
            bottom.setOrientation(LinearLayout.VERTICAL);
            clockBig = Cards.text(StageActivity.this, s, 132, R.font.outfit_light, Color.WHITE);
            clockBig.setFontFeatureSettings("tnum");
            clockBig.setText(DateFormat.format(h24 ? "H:mm" : "h:mm", now));
            clockDate = Cards.text(StageActivity.this, s, 30, R.font.outfit_medium, Color.WHITE);
            clockDate.setText(DateFormat.format("d MMMM yyyy", now));
            TextView wx = Cards.text(StageActivity.this, s, 26, R.font.outfit_regular, 0xD9FFFFFF);
            String place = Prefs.weatherPlace(StageActivity.this);
            wx.setText(r == null ? "Set your city in Home settings for the weather"
                    : r.temp + "° · " + r.words() + " · H " + r.high + "°  L " + r.low + "°" + (place.isEmpty() ? "" : " · " + place));
            bottom.addView(clockBig);
            bottom.addView(clockDate);
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
            wl.topMargin = d(8);
            bottom.addView(wx, wl);
            for (TextView t : new TextView[]{small, clockBig, clockDate, wx}) t.setShadowLayer(d(10), 0, d(1), 0x66000000);
            clockCard.addView(bottom, at(-1, -2, Gravity.BOTTOM | Gravity.START, 32, 0, 24, 32));
        }

        /** The sky for a reading: sunny blue, a clear night's navy, cloud grey, rain slate, storm violet, snow pale. */
        private int[] sky(HeaderWeather.Reading r) {
            if (r == null) return new int[]{0xFF4A5160, 0xFF262A33};
            int c = r.code;
            if (c >= 95) return new int[]{0xFF4A4266, 0xFF17131F};                          // thunderstorm
            if (c >= 71 && c <= 77 || c == 85 || c == 86) return new int[]{0xFFD3DEE9, 0xFF7E93A8};   // snow
            if (c >= 51) return new int[]{0xFF56677D, 0xFF1F2B38};                          // drizzle, rain, showers
            if (c == 45 || c == 48) return new int[]{0xFF9CA5AF, 0xFF5B636C};               // fog
            if (!r.day) return c <= 1 ? new int[]{0xFF22305A, 0xFF0A1024} : new int[]{0xFF39425A, 0xFF141A2A};   // night
            if (c <= 1) return new int[]{0xFF5DB4F2, 0xFF2667B8};                           // sunny
            return new int[]{0xFF7E9BB8, 0xFF3F5671};                                       // cloudy
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
            if (!t.contentEquals(clock.getText())) {
                clock.setText(t);
                if (clockBig != null) {
                    clockBig.setText(DateFormat.format(DateFormat.is24HourFormat(StageActivity.this) ? "H:mm" : "h:mm", new Date()));
                    clockDate.setText(DateFormat.format("d MMMM yyyy", new Date()));
                }
            }
        }

        /** The timer shows in the bar only while one runs. */
        private boolean timerOn() {
            return Clocks.timer() != null;
        }

        void tick() {
            String l = Clocks.line(StageActivity.this);
            timerBig.setText(l != null && l.startsWith("Timer · ") ? l.substring(8) : "0:00");
            TextView sub = (TextView) ((LinearLayout) timerBig.getParent()).getChildAt(1);
            sub.setText(l != null && l.startsWith("Timer") ? "Press OK to open it" : "No timer running");
            if (shown && (mods.get(TIMER).getVisibility() == View.VISIBLE) != timerOn()) {   // a timer started or ended
                if (m == TIMER && !timerOn()) {
                    m = CONTROLS;
                    inBar = true;
                }
                StageActivity.this.paint(true);
            }
            if (shown && System.currentTimeMillis() / 1000 % 5 == 0) {   // the Wi-Fi and the volume, every few seconds
                wifiIcon.refresh();
                showVolume();
            }
        }

        void open() {
            m = CONTROLS;
            inBar = false;
            q = 0;
            refresh();
        }

        /** Moves within the panel; Left and Right on Volume turn it instead. */
        boolean move(int dx, int dy) {
            if (inBar) {
                if (dx > 0 && m < ME) m += m + 1 == TIMER && !timerOn() ? 2 : 1;
                else if (dx < 0 && m > 0) m -= m - 1 == TIMER && !timerOn() ? 2 : 1;
                else if (dy > 0 && m < ME) {
                    inBar = false;
                    q = 0;
                } else return true;
            } else if (m != CONTROLS) {
                if (dy < 0) inBar = true;
            } else if (q == VOLUME && dx != 0) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dx > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                showVolume();
                main.postDelayed(this::showVolume, 400);   // an audio system answers a moment later
                return true;
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
                else if (m < CONTROLS) inBar = false;   // into the time or timer card
                StageActivity.this.paint(true);
                return;
            }
            if (m == TIMER) {
                Clocks.open(StageActivity.this);
                return;
            }
            if (m == CLOCK) return;   // the time card is to look at
            if (q == VOLUME) {   // OK on Volume mutes, or brings the sound back
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                main.postDelayed(this::showVolume, 300);
                return;
            }
            Intent i = new Intent(KEY_PAGES[q]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (getPackageManager().resolveActivity(i, 0) == null) i = new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (RuntimeException e) {
                // no such page on this TV
            }
        }

        /** Opens or closes the panel, and lights what has focus in it. */
        void paint(boolean on, boolean animate) {
            long ms = animate ? 520 : 0;
            if (on != shown) {
                shown = on;
                // The bar stretches out to the left; the panel unfolds from under its right end.
                mods.get(TIMER).setVisibility(on && timerOn() ? View.VISIBLE : View.GONE);
                mods.get(CONTROLS).setVisibility(on ? View.VISIBLE : View.GONE);
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
                        k.animate().alpha(1f).translationY(0).setStartDelay(animate ? 200 + 40L * i : 0).setDuration(ms)
                                .setInterpolator(EASE).withEndAction(() -> k.animate().setStartDelay(0)).start();
                    }
                } else {
                    card.animate().alpha(0f).scaleX(0.35f).scaleY(0.1f).translationY(-d(60)).setStartDelay(0)
                            .setDuration(animate ? 380 : 0).setInterpolator(EASE)
                            .withEndAction(() -> card.setVisibility(View.INVISIBLE)).start();
                }
            }
            if (shown) mods.get(TIMER).setVisibility(timerOn() ? View.VISIBLE : View.GONE);
            for (int i = 0; i < views.length; i++) views[i].setVisibility(i == Math.min(m, CONTROLS) ? View.VISIBLE : View.GONE);
            for (int i = 0; i < mods.size(); i++) {
                View v = mods.get(i);
                boolean sel = on && i == m, lit = sel && inBar;
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(d(25));
                bg.setColor(lit ? Color.WHITE : sel ? 0x38FFFFFF : 0x00000000);
                v.setBackground(bg);
                if (v instanceof ImageView) ((ImageView) v).setImageTintList(ColorStateList.valueOf(lit ? 0xFF111111 : 0xD9FFFFFF));
                else ((TextView) v).setTextColor(lit ? 0xFF111111 : Color.WHITE);
                v.animate().scaleX(lit ? 1.1f : 1f).scaleY(lit ? 1.1f : 1f).setDuration(300).setInterpolator(SPRING).start();
            }
            boolean meOn = on && inBar && m == ME;
            me.animate().scaleX(meOn ? 1.15f : 1f).scaleY(meOn ? 1.15f : 1f).setDuration(300).setInterpolator(SPRING).start();
            me.setForeground(meOn ? ring() : null);
            for (int i = 0; i < CONTROLS; i++) {
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
                k.animate().scaleX(kf ? 1.05f : 1f).scaleY(kf ? 1.05f : 1f).setDuration(animate ? 350 : 0).setInterpolator(SPRING).start();
            }
        }

        private void tint(View v, int colour) {
            if (v instanceof WifiIcon) ((WifiIcon) v).setColour(colour);
            else if (v instanceof ImageView) ((ImageView) v).setImageTintList(ColorStateList.valueOf(colour));
            else if (v instanceof TextView) ((TextView) v).setTextColor("sub".equals(v.getTag()) ? (colour & 0x00FFFFFF) | 0xB3000000 : colour);
            else if ("fill".equals(v.getTag())) ((GradientDrawable) v.getBackground()).setColor(colour);
            else if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                if (g.getBackground() instanceof GradientDrawable && g != v.getParent() && g.getChildCount() == 1 && "fill".equals(g.getChildAt(0).getTag())) {
                    ((GradientDrawable) g.getBackground()).setColor(colour == Color.WHITE ? 0x33FFFFFF : 0x33000000);
                }
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
