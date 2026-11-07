package io.github.hypermix94.vibetv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.hypermix94.vibetv.Apps.App;

import java.util.ArrayList;
import java.util.List;

/**
 * "All apps": every app on the TV, with the focused one's name set large above them. OK opens an app; holding OK
 * offers what fits where it lives (add to or remove from Home and the dock), plus Open, App info and Uninstall.
 * Back returns to the home screen, which picks up the changes.
 */
public class LibraryActivity extends Activity {
    /** Icons per row: 7 beside the preview panel, 10 without it. */
    private int COLUMNS = 7;
    private boolean withPreview;
    private static final Interpolator EASE_OUT = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private static final Interpolator SPRING = new OvershootInterpolator(1.4f);

    private float s;
    private FrameLayout root;
    private LinearLayout grid;
    private final List<App> apps = new ArrayList<>();
    private final List<FrameLayout> cards = new ArrayList<>();
    private int focus;
    private Menu menu;
    private boolean longPressed;
    private FrameLayout nameBox;
    private TextView name;
    private ScrollBar scroll;
    private AppPreview preview;
    /** Every app, and the ones the grid shows now (all of them, or those matching the search). */
    private final List<App> all = new ArrayList<>();
    private SearchBar search;
    private boolean onSearch;
    private int searchFocus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = getResources().getDisplayMetrics().widthPixels / 1920f;
        withPreview = Prefs.previewPanel(this);
        COLUMNS = withPreview ? 7 : 10;
        all.addAll(Apps.all(this).values());   // already sorted by name
        apps.addAll(all);
        setContentView(build());
        Cards.ripple(cards, 0.9f);
        root.post(() -> paint(true));
        new Thread(() -> {
            DailyWallpaper.Picture p = DailyWallpaper.today(this);
            runOnUiThread(() -> {
                if (p == null || isDestroyed()) return;
                ImageView back = (ImageView) root.getChildAt(0);
                back.setImageBitmap(p.bitmap);
                back.animate().alpha(0.3f).setDuration(900).start();
            });
        }, "wallpaper").start();
    }

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080A);
        root.setClipChildren(false);
        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        back.setAlpha(0f);
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        // "All apps" on the left, the search on the right.
        FrameLayout head = new FrameLayout(this);
        head.setClipChildren(false);
        TextView title = Cards.text(this, s, 48, R.font.outfit_medium, Color.WHITE);
        title.setText("All apps");
        title.setLetterSpacing(-0.02f);
        head.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        search = new SearchBar(this, s, this::filter, this::searchDone);
        head.addView(search, new FrameLayout.LayoutParams(d(560), d(68), Gravity.END | Gravity.CENTER_VERTICAL));
        column.addView(head, new LinearLayout.LayoutParams(-1, d(80)));
        nameBox = new FrameLayout(this);
        nameBox.setClipChildren(false);
        // Tall enough for the big name and the room around it on every side, so no letter is cut.
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, d(118));
        nameLp.topMargin = d(10);
        column.addView(nameBox, nameLp);
        // With the preview panel on, it carries the focused app's name; without it, the name shows here, large.
        nameBox.setVisibility(withPreview ? View.GONE : View.VISIBLE);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        buildGrid();
        // The icons scroll inside their own window under the header: the title, the search and the big name stay
        // put. The window starts a little above the first row and is padded, so a lifted icon is never cut.
        FrameLayout window = new FrameLayout(this);
        window.setClipChildren(true);
        window.setClipToPadding(false);
        window.setPadding(0, d(24), 0, d(24));
        window.addView(grid, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        int above = 96 + 80 + (withPreview ? 0 : 128) + 6;   // the header's height on the 1080 grid
        LinearLayout.LayoutParams winLp = new LinearLayout.LayoutParams(-1, d(1080 - above - 40));
        winLp.leftMargin = -d(20);   // icons sit centred in their places: line the first up with the title
        winLp.rightMargin = -d(20);
        column.addView(window, winLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        lp.setMargins(d(112), d(96), d(withPreview ? 112 + 520 + 48 : 112), 0);
        root.addView(column, lp);
        // On the right: the focused app at a glance.
        preview = new AppPreview(this, s);
        FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(d(520), -1, Gravity.END);
        pLp.setMargins(0, d(96), d(112), d(96));
        root.addView(preview, pLp);
        if (!withPreview) preview.setVisibility(View.GONE);   // Settings: no preview panel
        scroll = new ScrollBar(this, s);   // on the right: where in the apps the remote is
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(d(20), -1, Gravity.END);
        barLp.setMargins(0, d(200), d(withPreview ? 112 + 520 + 14 : 48), d(96));
        root.addView(scroll, barLp);
        column.setAlpha(0f);
        column.setTranslationY(d(28));
        column.animate().alpha(1f).translationY(0).setDuration(700).setInterpolator(EASE_OUT).start();
        return root;
    }

    /** Fills the grid with the apps shown now. */
    private void buildGrid() {
        grid.removeAllViews();
        cards.clear();
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, d(156));
                rowLp.topMargin = d(14);
                grid.addView(row, rowLp);
            }
            FrameLayout c = Cards.app(this, s, apps.get(i));
            c.setAlpha(0.9f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i % COLUMNS > 0) lp.leftMargin = d(12);
            row.addView(c, lp);
            cards.add(c);
        }
        if (row != null) {
            for (int i = apps.size() % COLUMNS; i > 0 && i < COLUMNS; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                lp.leftMargin = d(12);
                row.addView(new View(this), lp);
            }
        }
    }

    /** The search's words changed: show only the apps whose names contain them. */
    private void filter(String q) {
        String needle = q.trim().toLowerCase(java.util.Locale.ROOT);
        apps.clear();
        for (App a : all) if (needle.isEmpty() || a.label.toLowerCase(java.util.Locale.ROOT).contains(needle)) apps.add(a);
        focus = 0;
        buildGrid();
        Cards.ripple(cards, 0.9f);
        paint(true);
    }

    /** Typing or speaking finished: go to the results, if there are any. */
    private void searchDone() {
        if (!apps.isEmpty()) {
            onSearch = false;
            search.focus(-1);
            focus = 0;
        }
        paint(true);
    }

    @Override
    protected void onDestroy() {
        search.release();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (search.typing()) {   // the on-screen keyboard has the keys; Back finishes typing
            if (k == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) search.stopTyping();
            return k == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(e);
        }
        if (k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST || k == KeyEvent.KEYCODE_ASSIST) {
            if (e.getAction() == KeyEvent.ACTION_UP) {   // the remote's voice button: straight to listening
                onSearch = true;
                searchFocus = 1;
                search.voice();
            }
            return true;
        }
        if (onSearch) {
            boolean okKey = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (okKey) {
                if (e.getAction() == KeyEvent.ACTION_UP) search.press();
                return true;
            }
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {   // field, mic, assistant
                searchFocus = Math.max(0, Math.min(2, searchFocus + (k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                search.focus(searchFocus);
            } else if (k == KeyEvent.KEYCODE_DPAD_DOWN && !apps.isEmpty()) {
                onSearch = false;
                search.focus(-1);
                paint(true);
            } else if (k == KeyEvent.KEYCODE_BACK) {
                if (!search.query().isEmpty()) search.clear();
                else finish();
            }
            return true;
        }
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    longPressed = false;
                    e.startTracking();
                } else if (!longPressed && menu == null && !apps.isEmpty()
                        && (e.isLongPress() || e.getEventTime() - e.getDownTime() > 500)) {
                    longPressed = true;
                    openMenu();
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP && !longPressed && !apps.isEmpty()) {
                if (menu != null) pick();
                else open(apps.get(focus));
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        if (menu != null) {
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                menu.move(k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            } else if (k == KeyEvent.KEYCODE_BACK) {
                menu.close();
                menu = null;
            }
            return true;
        }
        if (apps.isEmpty()) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) {
                onSearch = true;
                search.focus(searchFocus);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        int col = focus % COLUMNS;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col > 0) focus--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (col < COLUMNS - 1 && focus + 1 < apps.size()) focus++;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focus >= COLUMNS) {
                    focus -= COLUMNS;
                } else {   // above the first row: the search
                    onSearch = true;
                    searchFocus = 0;
                    search.focus(0);
                    paint(true);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focus + COLUMNS < apps.size()) focus += COLUMNS;
                else if (focus / COLUMNS < (apps.size() - 1) / COLUMNS) focus = apps.size() - 1;
                break;
            default:
                return super.dispatchKeyEvent(e);
        }
        paint(true);
        return true;
    }

    /** The choices for the focused app, by where it lives now. */
    private void openMenu() {
        App a = apps.get(focus);
        boolean inDock = Apps.dockOrder(this).contains(a.pkg), hidden = Apps.hidden(this).contains(a.pkg);
        List<String> items = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        if (hidden) {
            items.add("Add to Home");
            icons.add(R.drawable.ic_menu_home_add);
        }
        if (inDock) {
            items.add("Remove from dock");
            icons.add(R.drawable.ic_menu_dock_remove);
        } else {
            items.add("Add to dock");
            icons.add(R.drawable.ic_menu_dock_add);
        }
        if (!hidden) {
            items.add("Remove from Home");
            icons.add(R.drawable.ic_menu_hide);
        }
        items.add("Open");
        icons.add(R.drawable.ic_menu_open);
        items.add("App info");
        icons.add(R.drawable.ic_menu_info);
        String remove = Apps.removeAction(this, apps.get(focus).pkg);   // Uninstall, Disable, or nothing for the TV's own
        if (remove != null) {
            items.add(remove);
            icons.add(R.drawable.ic_menu_delete);
        }
        int[] ic = new int[icons.size()];
        for (int i = 0; i < ic.length; i++) ic[i] = icons.get(i);
        menu = new Menu(this, s, items.toArray(new String[0]), ic);
        menu.show(root, cards.get(focus));
    }

    private void pick() {
        App a = apps.get(focus);
        String label = menu.picked();
        menu.close();
        menu = null;
        switch (label) {
            case "Add to Home":
                Apps.setHidden(this, a.pkg, false);
                android.widget.Toast.makeText(this, a.label + " is back on Home", android.widget.Toast.LENGTH_SHORT).show();
                break;
            case "Remove from Home":
                Apps.setHidden(this, a.pkg, true);
                android.widget.Toast.makeText(this, a.label + " is off Home", android.widget.Toast.LENGTH_SHORT).show();
                break;
            case "Remove from dock":
                Apps.removeFromDock(this, a.pkg);
                android.widget.Toast.makeText(this, a.label + " is out of the dock", android.widget.Toast.LENGTH_SHORT).show();
                break;
            case "App info":
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case "Uninstall":
            case "Disable":
                Apps.remove(this, a.pkg, label);
                finish();
                break;
            case "Add to dock":
                if (!Apps.addToDock(this, a.pkg)) {
                    android.widget.Toast.makeText(this, "The dock is full: remove an app from it first", android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                android.widget.Toast.makeText(this, a.label + " is in the dock", android.widget.Toast.LENGTH_SHORT).show();
                break;
            default:
                open(a);
                break;
        }
    }

    private void open(App a) {
        Apps.markOpened(this, a.pkg);
        try {
            startActivity(a.launch);
        } catch (RuntimeException ignored) {
            // uninstalled meanwhile
        }
    }

    /** Lifts the focused card and scrolls its row into view. */
    private void paint(boolean animate) {
        long ms = animate ? 420 : 0;
        for (int i = 0; i < cards.size(); i++) {
            boolean on = i == focus && !onSearch;
            FrameLayout c = cards.get(i);
            Object was = c.getTag(R.id.focused);
            if (animate && was != null && (Boolean) was == on) continue;   // unchanged: leave its ripple alone
            c.setTag(R.id.focused, on);
            c.animate().translationY(on ? -d(10) : 0).scaleX(on ? 1.14f : 1f).scaleY(on ? 1.14f : 1f)
                    .alpha(on ? 1f : 0.9f).setDuration(ms).setInterpolator(SPRING).start();
            c.setTranslationZ(on ? 1 : 0);
            Cards.focus(c, on, ms);
            if (on && animate) c.announceForAccessibility(c.getContentDescription());
        }
        if (!apps.isEmpty()) {
            App a = apps.get(focus);
            String where = Apps.dockOrder(this).contains(a.pkg) ? "In the dock"
                    : Apps.hidden(this).contains(a.pkg) ? "Not on Home" : "On Home";
            if (withPreview) preview.show(a, where, animate);
            else swapName(a.label, animate);
        }
        int row = focus / COLUMNS;
        int rows = (apps.size() + COLUMNS - 1) / COLUMNS;
        if (scroll != null && !apps.isEmpty()) {
            scroll.show(true, animate);
            scroll.set(rows, visibleRows(), Math.max(0, row - (visibleRows() - 1)), animate);
        }
        grid.animate().translationY(-Math.max(0, row - (visibleRows() - 1)) * d(170)).setDuration(animate ? 600 : 0).setInterpolator(EASE_OUT).start();
    }

    /** The focused app's name: the old one lifts away as the new one rises in. */
    private void swapName(String words, boolean animate) {
        if (name != null && words.contentEquals(name.getText())) return;
        if (name != null) {
            TextView old = name;
            if (animate) {
                old.animate().translationY(-d(24)).alpha(0f).setDuration(380).setInterpolator(EASE_OUT)
                        .withEndAction(() -> nameBox.removeView(old)).start();
            } else {
                nameBox.removeView(old);
            }
        }
        name = Cards.text(this, s, 72, R.font.outfit_medium, Color.WHITE);
        name.setTypeface(Cards.weight(this, R.font.outfit_medium, 560));
        Cards.softCorners(name, s);
        name.setLetterSpacing(-0.03f);
        name.setSingleLine(true);
        name.setText(words);
        name.forceHasOverlappingRendering(false);   // no off-screen layer to cut a round first letter mid-fade
        name.setPadding(d(24) + d(12), d(10), d(24), d(10));   // room on every side: nothing is cut
        name.setTranslationX(-d(24) - d(12));
        nameBox.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL | Gravity.START));
        if (animate) {
            name.setTranslationY(d(24));
            name.setAlpha(0f);
            name.animate().translationY(0).alpha(1f).setDuration(520).setInterpolator(EASE_OUT).start();
        }
    }

    /** How many rows of icons fit in the window under the header. */
    private int visibleRows() {
        int above = 96 + 80 + (withPreview ? 0 : 128) + 6;
        return Math.max(1, (1080 - above - 40 - 48) / 170);
    }

    private int d(float designPx) {
        return Cards.d(s, designPx);
    }
}
