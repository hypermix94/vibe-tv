package io.github.hypermix94.vibetv;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
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
    private static final String PREFS = "home";

    /** One app the home screen can open. */
    private static final class App {
        final String pkg, label;
        final Intent launch;
        final Drawable banner, icon;

        App(String pkg, String label, Intent launch, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.launch = launch;
            this.banner = banner;
            this.icon = icon;
        }
    }

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
    private LinearLayout menu;
    private final List<TextView> menuItems = new ArrayList<>();
    private int menuFocus;
    /** Moving a dock app: Left and Right shift it along the dock, OK or Back sets it down. */
    private boolean moving;
    private boolean longPressed;
    private int dockFocus, actionFocus, appFocus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = getResources().getDisplayMetrics().widthPixels / 1920f;
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
        PackageManager pm = getPackageManager();
        Map<String, App> all = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                ActivityInfo a = r.activityInfo;
                if (a.packageName.equals(getPackageName()) || all.containsKey(a.packageName)) continue;
                Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setClassName(a.packageName, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Drawable banner = a.loadBanner(pm);
                if (banner == null) banner = a.applicationInfo.loadBanner(pm);
                all.put(a.packageName, new App(a.packageName, r.loadLabel(pm).toString(), launch, banner, a.loadIcon(pm)));
            }
        }
        String saved = prefs().getString("dock", "");
        for (String pkg : (saved.isEmpty() ? getString(R.string.dock_apps) : saved).split(",")) {
            App a = all.remove(pkg.trim());
            if (a != null && dock.size() < DOCK) dock.add(a);
        }
        List<App> rest = new ArrayList<>(all.values());
        rest.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        while (dock.size() < DOCK && !rest.isEmpty()) dock.add(rest.remove(0));   // fill an unset dock
        others.addAll(rest);
    }

    private void open(App a) {
        if (a == null) return;
        prefs().edit().putLong("opened_" + a.pkg, System.currentTimeMillis()).apply();
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
        return getSharedPreferences(PREFS, MODE_PRIVATE);
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
        hm = text(96, R.font.outfit_extralight, Color.WHITE);
        hm.setLetterSpacing(-0.04f);
        hm.setTranslationX(-0.055f * d(96));   // light digits sit a little in from their box
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
        page.addView(heroBox, at(d(1400), d(300), Gravity.BOTTOM | Gravity.START, 112, 0, 0, 390));

        // The dock.
        dockRow = new LinearLayout(this);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        for (int i = 0; i < dock.size(); i++) {
            FrameLayout t = tile(dock.get(i), true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, d(144), 1f);   // 16:9, like TV banners
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
        TextView heading = text(48, R.font.outfit_medium, Color.WHITE);
        heading.setText("Apps");
        heading.setLetterSpacing(-0.02f);
        apps.addView(heading);
        LinearLayout row = null;
        for (int i = 0; i < others.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, d(144));
                rowLp.topMargin = d(30);
                apps.addView(row, rowLp);
            }
            FrameLayout t = tile(others.get(i), false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i % COLUMNS > 0) lp.leftMargin = d(32);
            row.addView(t, lp);
            appTiles.add(t);
        }
        if (row != null) {
            for (int i = others.size() % COLUMNS; i > 0 && i < COLUMNS; i++) {   // keep the last row's tiles the same width
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

    /** An app tile: its banner, or its icon and name on a dark tile when it has no banner. */
    private FrameLayout tile(App a, boolean inDock) {
        FrameLayout t = new FrameLayout(this);
        t.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), d(8));
            }
        });
        t.setClipToOutline(true);
        if (a.banner != null) {
            ImageView b = new ImageView(this);
            b.setImageDrawable(a.banner);
            b.setScaleType(ImageView.ScaleType.CENTER_CROP);
            t.addView(b, match());
        } else {
            t.setBackgroundColor(0xFF2A2D35);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(a.icon);
            t.addView(icon, at(d(52), d(52), Gravity.TOP | Gravity.START, 20, 18, 0, 0));
            TextView name = text(24, R.font.outfit_medium, Color.WHITE);
            name.setText(a.label);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.addView(name, at(-1, -2, Gravity.BOTTOM | Gravity.START, 20, 0, 16, 16));
        }
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(d(8));
        ring.setStroke(d(5), Color.WHITE);
        t.setForeground(ring);
        t.getForeground().setAlpha(0);
        t.setAlpha(inDock ? 0.78f : 0.9f);
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
                menuFocus = Math.max(0, Math.min(menuItems.size() - 1, menuFocus + (k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
                paintMenu();
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
            } else if (!others.isEmpty()) {
                zone = Zone.APPS;
            }
        } else if (zone == Zone.TOP) {
            if (dx != 0) actionFocus = Math.max(0, Math.min(actions.size() - 1, actionFocus + dx));
            else if (dy > 0) zone = Zone.DOCK;
        } else {
            int col = appFocus % COLUMNS, rowIndex = appFocus / COLUMNS;
            if (dx != 0) {
                int nextCol = col + dx;
                if (nextCol >= 0 && nextCol < COLUMNS && appFocus + dx < others.size()) appFocus += dx;
            } else if (dy < 0) {
                if (rowIndex == 0) zone = Zone.DOCK;
                else appFocus -= COLUMNS;
            } else if (appFocus + COLUMNS < others.size()) {
                appFocus += COLUMNS;
            } else if (rowIndex < (others.size() - 1) / COLUMNS) {
                appFocus = others.size() - 1;
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
            open(others.get(appFocus));
        } else {
            openSetting(actionFocus);
        }
    }

    // ---- the hold-OK menu ----------------------------------------------------------------------------

    private App focusedApp() {
        return zone == Zone.DOCK ? dock.get(dockFocus) : zone == Zone.APPS ? others.get(appFocus) : null;
    }

    private View focusedTile() {
        return zone == Zone.DOCK ? dockTiles.get(dockFocus) : appTiles.get(appFocus);
    }

    private void openMenu() {
        App a = focusedApp();
        if (a == null) return;
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xF21C1E24);
        card.setCornerRadius(d(16));
        menu.setBackground(card);
        menu.setPadding(d(10), d(10), d(10), d(10));
        menu.setElevation(d(30));
        menuItems.clear();
        String[] items = zone == Zone.DOCK ? new String[]{"Move", "App info", "Uninstall"}
                : new String[]{"Add to dock", "App info", "Uninstall"};
        for (String label : items) {
            TextView t = text(28, R.font.outfit_medium, Color.WHITE);
            t.setText(label);
            t.setPadding(d(24), d(16), d(48), d(16));
            menu.addView(t, new LinearLayout.LayoutParams(-1, -2));
            menuItems.add(t);
        }
        menuFocus = 0;
        paintMenu();
        // Above the tile, lined up with its left edge.
        View tile = focusedTile();
        int[] at = new int[2], rootAt = new int[2];
        tile.getLocationOnScreen(at);
        root.getLocationOnScreen(rootAt);
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(d(300), menu.getMeasuredWidth()), -2);
        lp.leftMargin = Math.min(at[0] - rootAt[0], root.getWidth() - d(112) - lp.width);
        lp.topMargin = Math.max(d(40), at[1] - rootAt[1] - menu.getMeasuredHeight() - d(28));
        root.addView(menu, lp);
        menu.setAlpha(0f);
        menu.setTranslationY(d(16));
        menu.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(EASE_OUT).start();
    }

    private void paintMenu() {
        for (int i = 0; i < menuItems.size(); i++) {
            TextView t = menuItems.get(i);
            boolean on = i == menuFocus;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(d(10));
            bg.setColor(on ? Color.WHITE : 0x00000000);
            t.setBackground(bg);
            t.setTextColor(on ? Color.BLACK : Color.WHITE);
        }
    }

    private void closeMenu() {
        if (menu == null) return;
        View m = menu;
        menu = null;
        m.animate().alpha(0f).setDuration(200).withEndAction(() -> root.removeView(m)).start();
    }

    private void pickMenu() {
        App a = focusedApp();
        String label = menuItems.get(menuFocus).getText().toString();
        closeMenu();
        if (a == null) return;
        switch (label) {
            case "Move":
                moving = true;
                dockTiles.get(dockFocus).animate().translationY(-d(30)).setDuration(300).setInterpolator(EASE_OUT).start();
                break;
            case "Add to dock":
                List<String> order = new ArrayList<>();
                order.add(a.pkg);
                for (App x : dock) order.add(x.pkg);
                if (order.size() > DOCK) order = order.subList(0, DOCK);   // the last dock app goes back to Apps
                prefs().edit().putString("dock", String.join(",", order)).apply();
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
        prefs().edit().putString("dock", String.join(",", order)).apply();
        paintFocus(true);
        showHero(false);
    }

    private void bounce(View v) {
        v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(90).withEndAction(() ->
                v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(260).setInterpolator(SPRING).start()).start();
    }

    /** Lifts the focused tile or button and settles everything else; slides the page for the apps. */
    private void paintFocus(boolean animate) {
        long tile = animate ? 420 : 0, slow = animate ? 650 : 0;
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

    private void lift(View t, boolean on, float restAlpha, long ms) {
        if (on && ms > 0) t.announceForAccessibility(t.getContentDescription());   // TalkBack says the app's name
        t.animate().translationY(on ? -d(14) : 0).scaleX(on ? 1.12f : 1f).scaleY(on ? 1.12f : 1f)
                .alpha(on ? 1f : restAlpha).setDuration(ms).setInterpolator(SPRING).start();
        t.setElevation(on ? d(24) : 0);
        t.setTranslationZ(on ? 1 : 0);
        t.getForeground().setAlpha(on ? 255 : 0);
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
        heroTitle = text(168, R.font.outfit_medium, Color.WHITE);
        heroTitle.setTypeface(Typeface.create(getResources().getFont(R.font.outfit_medium), 600, false));
        heroTitle.setLetterSpacing(-0.045f);
        heroTitle.setText(a.label);
        heroTitle.setSingleLine(true);
        heroTitle.setTranslationX(-0.06f * d(168));
        heroLine = text(30, R.font.outfit_light, 0xB8FFFFFF);
        heroLine.setText(Html.fromHtml(lastOpened(a.pkg), Html.FROM_HTML_MODE_LEGACY));
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-2, -2);
        lineLp.topMargin = d(10);
        card.addView(heroTitle, new LinearLayout.LayoutParams(-1, -2));
        card.addView(heroLine, lineLp);
        heroBox.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START));
        if (!instant) {
            card.setTranslationY(d(28));
            card.setAlpha(0f);
            card.animate().translationY(0).alpha(1f).setDuration(640).setInterpolator(EASE_OUT).start();
        }
    }

    private String lastOpened(String pkg) {
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
