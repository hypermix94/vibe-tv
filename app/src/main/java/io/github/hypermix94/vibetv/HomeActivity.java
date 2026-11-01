package io.github.hypermix94.vibetv;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
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
 * The home screen. The day's wallpaper fills the screen, still; over it a greeting, the clock with seconds and the date at
 * the top left, Inputs, Wi-Fi and Settings at the top right, the focused app's name set large, and a dock of six
 * apps. Down slides up to every other app. Laid out on a 1920 x 1080 grid scaled to the screen; every move eases.
 * The remote is handled here directly (Left, Right, Up, Down, OK), so focus goes exactly where the design says.
 */
public class HomeActivity extends Activity {
    private static final int DOCK = 6, COLUMNS = 6;
    private static final Interpolator EASE_OUT = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private static final Interpolator SPRING = new OvershootInterpolator(1.4f);

    private final Handler main = new Handler(Looper.getMainLooper());
    private float s;   // screen pixels per design pixel
    private FrameLayout root, page, heroBox;
    private ImageView wallpaper;
    private TextView greeting, hm, seconds, ampm, date, credit;
    private ImageView character;
    private final List<FrameLayout> dockTiles = new ArrayList<>(), appTiles = new ArrayList<>();
    private final List<FrameLayout> actions = new ArrayList<>();
    private final List<App> dock = new ArrayList<>(), others = new ArrayList<>();
    private TextView heroTitle, heroLine;
    /** Which top-right button was lit last time, so only a change animates. */
    private int litAction = -1;

    /** Where the remote is: the top right buttons, the dock, or the apps below. */
    private enum Zone { TOP, DOCK, APPS }

    private Zone zone = Zone.DOCK;
    private LinearLayout dockRow;
    /** The hold-OK menu, while it is open, and the item picked in it. */
    private Menu menu;
    /** Moving a dock app: Left and Right shift it along the dock, OK or Back sets it down. */
    private boolean moving;
    private boolean longPressed;
    private FrameLayout headingBox;
    private TextView heading;
    /** The dock and hidden apps as they were when this screen was built. */
    private long builtRevision;
    private int dockFocus, actionFocus, appFocus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = getResources().getDisplayMetrics().widthPixels / 1920f;
        builtRevision = Apps.revision(this);
        loadApps();
        setContentView(build());
        paintFocus(false);
        showHero(true);
        enter();
        new Thread(() -> {
            DailyWallpaper.Picture p = DailyWallpaper.today(this);
            main.post(() -> {
                if (p == null || isDestroyed()) return;
                wallpaper.setAlpha(0f);
                wallpaper.setImageBitmap(p.bitmap);
                wallpaper.animate().alpha(1f).setDuration(1200).setInterpolator(EASE_OUT).start();
                credit.setText(p.credit);
            });
        }, "wallpaper").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Apps.revision(this) != builtRevision) {   // apps were added or put back on another page
            recreate();
            return;
        }
        List<App> shown = new ArrayList<>(dock);
        shown.addAll(others);
        for (App a : shown) {   // an app was uninstalled while we were away: rebuild with the apps there are now
            if (getPackageManager().getLaunchIntentForPackage(a.pkg) == null
                    && getPackageManager().getLeanbackLaunchIntentForPackage(a.pkg) == null) {
                recreate();
                return;
            }
        }
        main.post(tick);
        showHero(true);   // "last opened" may have changed
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (zone != Zone.DOCK) {   // Back from the buttons or the apps returns to the dock; on Home it goes nowhere
            zone = Zone.DOCK;
            paintFocus(true);
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
        while (unset && dock.size() < DOCK && !others.isEmpty()) dock.add(others.remove(0));   // a first-run dock
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

    private void openSetting(int which) {
        String[] actions = {"com.android.tv.action.VIEW_INPUTS", "android.settings.WIFI_SETTINGS", "android.settings.SETTINGS"};
        try {
            startActivity(new Intent(actions[which]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            startActivity(new Intent("android.settings.SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private SharedPreferences prefs() {
        return Apps.prefs(this);
    }

    // ---- building the screen ---------------------------------------------------------------------

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080A);

        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));

        // Neutral shades so the words read on any picture.
        root.addView(shade(GradientDrawable.Orientation.LEFT_RIGHT, 0xB8000000, 0x59000000, 0x00000000), match());
        root.addView(shade(GradientDrawable.Orientation.BOTTOM_TOP, 0xEB000000, 0x8C000000, 0x00000000), match());
        root.addView(shade(GradientDrawable.Orientation.TOP_BOTTOM, 0x8C000000, 0x00000000, 0x00000000), match());

        page = new FrameLayout(this);
        page.setClipChildren(false);   // a focused tile grows past its row
        root.setClipChildren(false);
        root.addView(page, match());

        // Top left: greeting, clock, date.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setClipChildren(false);
        LinearLayout greetRow = new LinearLayout(this);
        greetRow.setGravity(Gravity.CENTER_VERTICAL);
        greeting = text(30, R.font.outfit_regular, 0xB8FFFFFF);
        greetRow.addView(greeting, new LinearLayout.LayoutParams(-2, -2));
        greeting.setSingleLine(true);
        character = new ImageView(this);
        LinearLayout.LayoutParams charLp = new LinearLayout.LayoutParams(d(40), d(40));
        charLp.leftMargin = d(14);
        greetRow.addView(character, charLp);
        top.addView(greetRow);
        LinearLayout clock = new LinearLayout(this);
        clock.setClipChildren(false);
        hm = text(96, R.font.outfit_extralight, Color.WHITE);
        hm.setLetterSpacing(-0.04f);
        hm.setPadding(d(12), 0, 0, 0);   // room inside the box so no digit is cut
        hm.setTranslationX(-d(12) - 0.055f * d(96));   // light digits sit a little in from their box
        clock.addView(hm);
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        seconds = text(26, R.font.outfit_light, Color.WHITE);
        ampm = text(18, R.font.outfit_medium, 0xB8FFFFFF);
        sec.addView(seconds);
        sec.addView(ampm);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(-2, -2);
        secLp.leftMargin = d(10);
        secLp.topMargin = d(24);   // the seconds' top meets the top of the digits
        clock.addView(sec, secLp);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(-2, -2);
        clockLp.topMargin = d(6);
        top.addView(clock, clockLp);
        date = text(26, R.font.outfit_light, 0xB8FFFFFF);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(-2, -2);
        dateLp.topMargin = d(10);
        top.addView(date, dateLp);
        page.addView(top, at(d(1000), -2, Gravity.TOP | Gravity.START, 112, 64, 0, 0));
        top.setTag("rise:80");

        // Top right: Inputs, Wi-Fi, Settings.
        LinearLayout acts = new LinearLayout(this);
        int[] icons = {R.drawable.avd_home_inputs, R.drawable.avd_home_wifi, R.drawable.avd_home_settings};
        for (int i = 0; i < icons.length; i++) {
            FrameLayout b = new FrameLayout(this);
            View disc = new View(this);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Color.WHITE);
            disc.setBackground(circle);
            disc.setAlpha(0f);
            b.addView(disc, match());
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(Color.WHITE);
            b.addView(icon, new FrameLayout.LayoutParams(d(40), d(40), Gravity.CENTER));
            b.setContentDescription(new String[]{"Inputs", "Wi-Fi", "Settings"}[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(72), d(72));
            if (i > 0) lp.leftMargin = d(18);
            acts.addView(b, lp);
            b.setTag("rise:" + (360 + 80 * i));
            actions.add(b);
        }
        page.addView(acts, at(-2, -2, Gravity.TOP | Gravity.END, 0, 0, 112, 0));
        // The buttons sit level with the middle of the clock's digits, measured once laid out.
        acts.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                acts.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                // Layout positions, not screen ones: the entrance animation is still moving things at this point.
                View clockRow = (View) hm.getParent(), topBlock = (View) clockRow.getParent();
                float hmTop = topBlock.getTop() + clockRow.getTop() + hm.getTop();
                float clockMid = hmTop + hm.getBaseline() - hm.getTextSize() * 0.36f;   // middle of the digits
                float actsMid = acts.getTop() + acts.getHeight() / 2f;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) acts.getLayoutParams();
                lp.topMargin += Math.round(clockMid - actsMid);
                acts.setLayoutParams(lp);
            }
        });

        // The focused app's name, large, and when it was last opened.
        heroBox = new FrameLayout(this);
        heroBox.setClipChildren(false);
        page.addView(heroBox, at(d(1400), d(300), Gravity.BOTTOM | Gravity.START, 112, 0, 0, 390));

        // The dock.
        dockRow = new LinearLayout(this);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        // However many apps it holds, the dock fills the same width: the tiles shrink to fit, keeping 16:9.
        int n = Math.max(1, dock.size());
        float tileW = (1920f - 2 * 112 - (n - 1) * 32f) / n;
        int tileH = d(Math.min(144f, tileW * 9f / 16f));
        for (int i = 0; i < dock.size(); i++) {
            FrameLayout t = tile(dock.get(i), true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, tileH, 1f);
            if (i > 0) lp.leftMargin = d(32);
            dockRow.addView(t, lp);
            t.setTag("rise:" + (420 + 70 * i));
            dockTiles.add(t);
        }
        page.addView(dockRow, at(-1, -2, Gravity.BOTTOM, 112, 0, 112, 150));

        // Every other app, below the dock: Down slides the page up to them.
        LinearLayout apps = new LinearLayout(this);
        apps.setOrientation(LinearLayout.VERTICAL);
        apps.setClipChildren(false);
        // "Apps", and once the remote is down here, the focused app's name in its place.
        headingBox = new FrameLayout(this);
        headingBox.setClipChildren(false);
        apps.addView(headingBox, new LinearLayout.LayoutParams(-1, d(60)));
        swapHeading("Apps", false);
        LinearLayout row = null;
        int cards = others.size() + 1;   // every app, then "Not on Home"
        for (int i = 0; i < cards; i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, d(144));
                rowLp.topMargin = d(30);
                apps.addView(row, rowLp);
            }
            FrameLayout t = i < others.size() ? Cards.app(this, s, others.get(i))
                    : Cards.action(this, s, R.drawable.ic_apps_more, "All apps");
            t.setAlpha(0.9f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i % COLUMNS > 0) lp.leftMargin = d(32);
            row.addView(t, lp);
            appTiles.add(t);
        }
        if (row != null) {
            for (int i = cards % COLUMNS; i > 0 && i < COLUMNS; i++) {   // keep the last row's tiles the same width
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                lp.leftMargin = d(32);
                row.addView(new View(this), lp);
            }
        }
        // The "Apps" heading peeks above the bottom edge, so there is plainly more below the dock.
        page.addView(apps, at(-1, -2, Gravity.TOP, 112, 1000, 112, 0));

        credit = text(18, R.font.outfit_regular, 0xB3FFFFFF);
        root.addView(credit, at(-2, -2, Gravity.BOTTOM | Gravity.END, 0, 0, 112, 40));

        greetingLine();
        return root;
    }

    /** A dock tile: the app's banner, or a Material card when it has none. */
    private FrameLayout tile(App a, boolean inDock) {
        if (a.banner == null) {
            FrameLayout c = Cards.app(this, s, a);
            c.setAlpha(0.78f);
            return c;
        }
        FrameLayout t = new FrameLayout(this);
        t.setOutlineProvider(Cards.rounded(s, 8));
        t.setClipToOutline(true);
        ImageView b = new ImageView(this);
        b.setImageDrawable(a.banner);
        b.setScaleType(ImageView.ScaleType.CENTER_CROP);
        t.addView(b, match());
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(d(8));
        ring.setStroke(d(5), Color.WHITE);
        t.setForeground(ring);
        t.getForeground().setAlpha(0);
        t.setAlpha(0.78f);
        t.setContentDescription(a.label);
        return t;
    }

    // ---- focus and motion -----------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok) {
            // OK acts when it comes up; held for half a second it opens the menu instead.
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    longPressed = false;
                    e.startTracking();   // lets Android flag the hold as a long press
                }
                else if (!longPressed && menu == null && !moving && zone != Zone.TOP
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
        if (menu != null) {
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                menu.move(k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            } else if (k == KeyEvent.KEYCODE_BACK) {
                closeMenu();
            }
            return true;
        }
        if (moving) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                shift(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            } else if (k == KeyEvent.KEYCODE_BACK) {
                stopMoving();
            }
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(0, 1);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void move(int dx, int dy) {
        if (zone == Zone.DOCK) {
            if (dx != 0) {
                int next = Math.max(0, Math.min(dock.size() - 1, dockFocus + dx));
                if (next == dockFocus) return;
                dockFocus = next;
                showHero(false);
            } else if (dy < 0) {
                zone = Zone.TOP;
            } else {
                zone = Zone.APPS;
            }
        } else if (zone == Zone.TOP) {
            if (dx != 0) actionFocus = Math.max(0, Math.min(actions.size() - 1, actionFocus + dx));
            else if (dy > 0) zone = Zone.DOCK;
        } else {
            int cards = appTiles.size(), col = appFocus % COLUMNS, rowIndex = appFocus / COLUMNS;
            if (dx != 0) {
                int nextCol = col + dx;
                if (nextCol >= 0 && nextCol < COLUMNS && appFocus + dx < cards) appFocus += dx;
            } else if (dy < 0) {
                if (rowIndex == 0) zone = Zone.DOCK;
                else appFocus -= COLUMNS;
            } else if (appFocus + COLUMNS < cards) {
                appFocus += COLUMNS;
            } else if (rowIndex < (cards - 1) / COLUMNS) {
                appFocus = cards - 1;
            }
        }
        paintFocus(true);
    }

    private void press() {
        if (zone == Zone.DOCK) {
            bounce(dockTiles.get(dockFocus));
            open(dock.get(dockFocus));
        } else if (zone == Zone.APPS) {
            bounce(appTiles.get(appFocus));
            if (appFocus < others.size()) open(others.get(appFocus));
            else startActivity(new Intent(this, LibraryActivity.class));   // the "All apps" page
        } else {
            openSetting(actionFocus);
        }
    }

    // ---- the hold-OK menu ----------------------------------------------------------------------------

    private App focusedApp() {
        if (zone == Zone.DOCK) return dock.isEmpty() ? null : dock.get(dockFocus);
        return zone == Zone.APPS && appFocus < others.size() ? others.get(appFocus) : null;
    }

    private View focusedTile() {
        return zone == Zone.DOCK ? dockTiles.get(dockFocus) : appTiles.get(appFocus);
    }

    private void openMenu() {
        App a = focusedApp();
        if (a == null) return;
        boolean inDock = zone == Zone.DOCK;
        String[] items = inDock
                ? new String[]{"Move", "Remove from dock", "Remove from Home", "App info", "Uninstall"}
                : new String[]{"Add to dock", "Remove from Home", "App info", "Uninstall"};
        int[] icons = inDock
                ? new int[]{R.drawable.ic_menu_move, R.drawable.ic_menu_dock_remove, R.drawable.ic_menu_hide,
                        R.drawable.ic_menu_info, R.drawable.ic_menu_delete}
                : new int[]{R.drawable.ic_menu_dock_add, R.drawable.ic_menu_hide, R.drawable.ic_menu_info,
                        R.drawable.ic_menu_delete};
        menu = new Menu(this, s, items, icons);
        menu.show(root, focusedTile());
    }

    private void closeMenu() {
        if (menu == null) return;
        menu.close();
        menu = null;
    }

    private void pickMenu() {
        App a = focusedApp();
        String label = menu.picked();
        closeMenu();
        if (a == null) return;
        switch (label) {
            case "Move":
                moving = true;
                dockTiles.get(dockFocus).animate().translationY(-d(30)).setDuration(300).setInterpolator(EASE_OUT).start();
                break;
            case "Add to dock":
                if (!Apps.addToDock(this, a.pkg)) {
                    android.widget.Toast.makeText(this, "The dock is full: remove an app from it first", android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                recreate();
                break;
            case "Remove from dock":
                Apps.removeFromDock(this, a.pkg);
                recreate();
                break;
            case "Remove from Home":
                Apps.setHidden(this, a.pkg, true);
                recreate();
                break;
            case "App info":
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case "Uninstall":
                startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + a.pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            default:
                break;
        }
    }

    /** Moves the lifted dock app one place left or right, sliding its neighbour across. */
    private void shift(int by) {
        int to = dockFocus + by;
        if (to < 0 || to >= dock.size()) return;
        App a = dock.remove(dockFocus);
        dock.add(to, a);
        FrameLayout t = dockTiles.remove(dockFocus);
        dockTiles.add(to, t);
        dockRow.removeView(t);
        dockRow.addView(t, to);
        for (int i = 0; i < dockRow.getChildCount(); i++) {   // even gaps again after the swap
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dockRow.getChildAt(i).getLayoutParams();
            lp.leftMargin = i > 0 ? d(32) : 0;
        }
        dockRow.requestLayout();
        dockFocus = to;
        t.setTranslationY(-d(30));
    }

    private void stopMoving() {
        moving = false;
        List<String> order = new ArrayList<>();
        for (App x : dock) order.add(x.pkg);
        Apps.saveDock(this, order);
        paintFocus(true);
        showHero(false);
    }

    private void bounce(View v) {
        v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(90).withEndAction(() ->
                v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(260).setInterpolator(SPRING).start()).start();
    }

    /** Lifts the focused tile or button and settles everything else; slides the page for the apps. */
    private void paintFocus(boolean animate) {
        long tile = animate ? 420 : 0, slow = animate ? 900 : 0;   // the top-right buttons move unhurried
        for (int i = 0; i < dockTiles.size(); i++) {
            boolean on = zone == Zone.DOCK && i == dockFocus;
            lift(dockTiles.get(i), on, 0.78f, tile);
        }
        for (int i = 0; i < appTiles.size(); i++) {
            boolean on = zone == Zone.APPS && i == appFocus;
            lift(appTiles.get(i), on, 0.9f, tile);
        }
        int lit = zone == Zone.TOP ? actionFocus : -1;
        if (lit != litAction) paintActions(lit, slow);
        litAction = lit;
        // Down to the apps: the page slides up, the words above fade, and the picture dims.
        boolean inApps = zone == Zone.APPS;
        swapHeading(!inApps ? "Apps" : appFocus < others.size() ? others.get(appFocus).label : "All apps", animate);
        int row = inApps ? appFocus / COLUMNS : 0;
        float pageY = inApps ? -d(700) - Math.max(0, row - 1) * d(174) : 0;
        page.animate().translationY(pageY).setDuration(animate ? 700 : 0).setInterpolator(EASE_OUT).start();
        for (View v : new View[]{heroBox, (View) hm.getParent().getParent(), (View) actions.get(0).getParent()}) {
            v.animate().alpha(inApps ? 0f : 1f).setDuration(animate ? 500 : 0).start();
        }
        wallpaper.animate().alpha(inApps ? 0.45f : 1f).setDuration(animate ? 700 : 0).start();
    }

    /** Lights the focused top-right button (white disc, dark icon, its own motion) and settles the others. */
    private void paintActions(int lit, long slow) {
        for (int i = 0; i < actions.size(); i++) {
            FrameLayout b = actions.get(i);
            boolean on = i == lit, was = i == litAction;
            View disc = b.getChildAt(0);
            ImageView icon = (ImageView) b.getChildAt(1);
            disc.animate().alpha(on ? 1f : 0f).setDuration(slow).setInterpolator(EASE_OUT).start();
            b.animate().scaleX(on ? 1.08f : 1f).scaleY(on ? 1.08f : 1f).setDuration(slow).setInterpolator(EASE_OUT).start();
            ValueAnimator tint = ValueAnimator.ofObject(new ArgbEvaluator(), on ? Color.WHITE : Color.BLACK, on ? Color.BLACK : Color.WHITE);
            tint.setDuration(slow);
            tint.addUpdateListener(a -> icon.setColorFilter((int) a.getAnimatedValue()));
            if (on != was) tint.start();
            // Each icon plays its own short animation once on focus: the arrow slides in, the Wi-Fi signal fills up,
            // the slider knobs glide. Leaving puts it back.
            if (on != was && icon.getDrawable() instanceof android.graphics.drawable.AnimatedVectorDrawable) {
                android.graphics.drawable.AnimatedVectorDrawable avd =
                        (android.graphics.drawable.AnimatedVectorDrawable) icon.getDrawable();
                if (on) {
                    avd.reset();
                    avd.start();
                } else {
                    avd.reset();
                }
            }
        }
    }

    /** The heading over the apps: the old words lift away as the new ones rise into place. */
    private void swapHeading(String words, boolean animate) {
        if (heading != null && words.contentEquals(heading.getText())) return;
        if (heading != null) {
            TextView old = heading;
            if (animate) {
                old.animate().translationY(-d(20)).alpha(0f).setDuration(380).setInterpolator(EASE_OUT)
                        .withEndAction(() -> headingBox.removeView(old)).start();
            } else {
                headingBox.removeView(old);
            }
        }
        heading = text(48, R.font.outfit_medium, Color.WHITE);
        heading.setLetterSpacing(-0.02f);
        heading.setSingleLine(true);
        heading.setText(words);
        heading.forceHasOverlappingRendering(false);
        heading.setPadding(d(24) + d(12), 0, 0, 0);   // room for round first letters inside the box
        heading.setTranslationX(-d(24) - d(12));
        headingBox.addView(heading, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START));
        if (animate) {
            heading.setTranslationY(d(20));
            heading.setAlpha(0f);
            heading.animate().translationY(0).alpha(1f).setDuration(480).setInterpolator(EASE_OUT).start();
        }
    }

    private void lift(View t, boolean on, float restAlpha, long ms) {
        if (on && ms > 0) t.announceForAccessibility(t.getContentDescription());   // TalkBack says the app's name
        t.animate().translationY(on ? -d(14) : 0).scaleX(on ? 1.12f : 1f).scaleY(on ? 1.12f : 1f)
                .alpha(on ? 1f : restAlpha).setDuration(ms).setInterpolator(SPRING).start();
        t.setElevation(on ? d(24) : 0);
        t.setTranslationZ(on ? 1 : 0);
        Cards.focus(t, on, ms);   // cards turn bone-white; dock banners show their white ring
    }

    /** The big name of the focused dock app: the old one lifts away, the new one rises in. */
    private void showHero(boolean instant) {
        if (dock.isEmpty()) return;
        App a = dock.get(Math.min(dockFocus, dock.size() - 1));
        if (heroTitle != null) {
            View old = (View) heroTitle.getParent();
            if (instant) {
                heroBox.removeView(old);
            } else {
                old.animate().translationY(-d(28)).alpha(0f).setDuration(520).setInterpolator(EASE_OUT)
                        .withEndAction(() -> heroBox.removeView(old)).start();
            }
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);   // round letters like S reach a little past the edge: never cut them
        // Fade the words directly, not through an off-screen layer the size of the box: such a layer would cut the
        // overhang of an S while the name glides in, and it would pop back once the fade ended.
        card.forceHasOverlappingRendering(false);
        card.setPadding(d(24), 0, 0, 0);
        card.setTranslationX(-d(24));
        heroTitle = text(168, R.font.outfit_medium, Color.WHITE);
        heroTitle.setTypeface(Typeface.create(getResources().getFont(R.font.outfit_medium), 560, false));
        Cards.softCorners(heroTitle, s);
        heroTitle.setLetterSpacing(-0.045f);
        heroTitle.setText(a.label);
        heroTitle.setSingleLine(true);
        // A text box cuts whatever its letters draw past its own edge, and round letters (S, O, C) lean a little
        // past the left one: give the words room inside the box and move the box back by the same amount.
        heroTitle.setPadding(d(20), 0, 0, 0);
        heroTitle.setTranslationX(-d(20) - 0.04f * d(168));
        heroLine = text(30, R.font.outfit_light, 0xB8FFFFFF);
        heroLine.setText(Html.fromHtml(lastOpened(a.pkg), Html.FROM_HTML_MODE_LEGACY));
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-2, -2);
        lineLp.topMargin = d(10);
        card.addView(heroTitle, new LinearLayout.LayoutParams(-1, -2));
        card.addView(heroLine, lineLp);
        heroTitle.forceHasOverlappingRendering(false);
        heroLine.forceHasOverlappingRendering(false);
        heroBox.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START));
        if (!instant) {
            card.setTranslationY(d(28));
            card.setAlpha(0f);
            card.animate().translationY(0).alpha(1f).setDuration(640).setInterpolator(EASE_OUT).start();
        }
    }

    /**
     * The line under the big name: what the app last played, as its media session reported it ("Last played
     * <b>title</b>"); for an app that never says (often the case), when it was last opened.
     */
    private String lastOpened(String pkg) {
        String title = NowPlaying.loadTitle(this, pkg);
        if (!title.isEmpty()) return "Last played <b>" + android.text.TextUtils.htmlEncode(title) + "</b>";
        long at = prefs().getLong("opened_" + pkg, 0);
        if (at == 0) return "Press OK to open";
        Calendar then = Calendar.getInstance(), now = Calendar.getInstance();
        then.setTimeInMillis(at);
        String time = DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a", then).toString().toLowerCase();
        int days = (int) ((startOfDay(now) - startOfDay(then)) / 86_400_000L);
        String when = days == 0 ? "today, " + time : days == 1 ? "yesterday, " + time
                : days < 7 ? days + " days ago, " + time : DateFormat.format("d MMMM", then).toString();
        return "Last opened <b>" + when + "</b>";
    }

    private static long startOfDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    /** First appearance: each part rises into place and fades in, one after another. */
    private void enter() {
        List<View> parts = new ArrayList<>();
        collect(root, parts);
        for (View v : parts) {
            long delay = Long.parseLong(((String) v.getTag()).substring(5));
            float restAlpha = v.getAlpha();
            v.setAlpha(0f);
            v.setTranslationY(v.getTranslationY() + d(28));
            v.animate().alpha(restAlpha).translationYBy(-d(28)).setStartDelay(delay).setDuration(900)
                    .setInterpolator(EASE_OUT).withEndAction(() -> v.animate().setStartDelay(0)).start();
        }
    }

    private static void collect(View v, List<View> out) {
        if (v.getTag() instanceof String && ((String) v.getTag()).startsWith("rise:")) out.add(v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    // ---- the clock and the greeting ----------------------------------------------------------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            boolean h24 = DateFormat.is24HourFormat(HomeActivity.this);
            hm.setText(DateFormat.format(h24 ? "H:mm" : "h:mm", now));
            ampm.setText(h24 ? "" : DateFormat.format("a", now).toString().toUpperCase());
            String sec = DateFormat.format("ss", now).toString();
            if (!sec.contentEquals(seconds.getText())) {
                seconds.setText(sec);   // a clean swap with a soft brightness pulse: no digits overlapping
                seconds.setAlpha(0.55f);
                seconds.animate().alpha(1f).setDuration(380).setInterpolator(EASE_OUT).start();
            }
            date.setText(DateFormat.format("EEEE, d MMMM", now));
            if (DateFormat.format("ss", now).toString().equals("00")) greetingLine();
            main.postDelayed(this, 1000 - System.currentTimeMillis() % 1000 + 10);
        }
    };

    /** "Good morning", "Late show" and so on, with the owner's name and an animated character to match the hour. */
    private void greetingLine() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String[][] byHour = {
                {"Late show", "🍿"}, {"Good morning", "☀️"}, {"Good afternoon", "😎"},
                {"Good evening", "🌆"}, {"Late show", "🍿"}};
        String[] pick = h < 5 ? byHour[0] : h < 12 ? byHour[1] : h < 17 ? byHour[2] : h < 22 ? byHour[3] : byHour[4];
        String name = getString(R.string.owner_name).trim();
        String line = name.isEmpty() ? pick[0] + "." : pick[0] + ", " + name + ".";
        if (line.contentEquals(greeting.getText())) return;
        greeting.setText(line);
        new GreetingCharacter(this).load(pick[1], d(40), drawable -> {
            if (drawable != null) character.setImageDrawable(drawable);
        });
    }

    // ---- small helpers ------------------------------------------------------------------------------

    /** Design pixels (on the 1920 x 1080 grid) to screen pixels. */
    private int d(float designPx) {
        return Math.round(designPx * s);
    }

    private TextView text(float designPx, int font, int colour) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, d(designPx));
        t.setTypeface(getResources().getFont(font));
        t.setTextColor(colour);
        t.setIncludeFontPadding(false);
        return t;
    }

    private View shade(GradientDrawable.Orientation o, int a, int b, int c) {
        View v = new View(this);
        v.setBackground(new GradientDrawable(o, new int[]{a, b, c}));
        return v;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    /** Layout params with margins in design pixels. */
    private FrameLayout.LayoutParams at(int w, int h, int gravity, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(d(left), d(top), d(right), d(bottom));
        return lp;
    }
}
