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
    private static final int COLUMNS = 6;
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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = getResources().getDisplayMetrics().widthPixels / 1920f;
        apps.addAll(Apps.all(this).values());   // already sorted by name
        setContentView(build());
        paint(false);
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
        TextView title = Cards.text(this, s, 30, R.font.outfit_regular, 0xB8FFFFFF);
        title.setText("All apps \u00b7 hold OK to choose where an app goes");
        column.addView(title);
        nameBox = new FrameLayout(this);
        nameBox.setClipChildren(false);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, d(90));
        nameLp.topMargin = d(10);
        column.addView(nameBox, nameLp);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, d(144));
                rowLp.topMargin = d(30);
                grid.addView(row, rowLp);
            }
            FrameLayout c = Cards.app(this, s, apps.get(i));
            c.setAlpha(0.9f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i % COLUMNS > 0) lp.leftMargin = d(32);
            row.addView(c, lp);
            cards.add(c);
        }
        if (row != null) {
            for (int i = apps.size() % COLUMNS; i > 0 && i < COLUMNS; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                lp.leftMargin = d(32);
                row.addView(new View(this), lp);
            }
        }
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.topMargin = d(30);
        column.addView(grid, gridLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        lp.setMargins(d(112), d(96), d(112), 0);
        root.addView(column, lp);
        column.setAlpha(0f);
        column.setTranslationY(d(28));
        column.animate().alpha(1f).translationY(0).setDuration(700).setInterpolator(EASE_OUT).start();
        return root;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
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
        if (apps.isEmpty()) return super.dispatchKeyEvent(e);
        int col = focus % COLUMNS;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col > 0) focus--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (col < COLUMNS - 1 && focus + 1 < apps.size()) focus++;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focus >= COLUMNS) focus -= COLUMNS;
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
        items.add("Uninstall");
        icons.add(R.drawable.ic_menu_delete);
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
                startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + a.pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
            boolean on = i == focus;
            FrameLayout c = cards.get(i);
            c.animate().translationY(on ? -d(10) : 0).scaleX(on ? 1.1f : 1f).scaleY(on ? 1.1f : 1f)
                    .alpha(on ? 1f : 0.9f).setDuration(ms).setInterpolator(SPRING).start();
            c.setTranslationZ(on ? 1 : 0);
            c.getForeground().setAlpha(on ? 255 : 0);
            if (on && animate) c.announceForAccessibility(c.getContentDescription());
        }
        if (!apps.isEmpty()) swapName(apps.get(focus).label, animate);
        int row = focus / COLUMNS;
        grid.animate().translationY(-Math.max(0, row - 2) * d(174)).setDuration(animate ? 600 : 0).setInterpolator(EASE_OUT).start();
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
        name.setTypeface(android.graphics.Typeface.create(getResources().getFont(R.font.outfit_medium), 560, false));
        Cards.softCorners(name, s);
        name.setLetterSpacing(-0.03f);
        name.setSingleLine(true);
        name.setText(words);
        name.forceHasOverlappingRendering(false);   // no off-screen layer to cut a round first letter mid-fade
        name.setPadding(d(24) + d(12), 0, 0, 0);   // room for round first letters inside the box
        name.setTranslationX(-d(24) - d(12));
        nameBox.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START));
        if (animate) {
            name.setTranslationY(d(24));
            name.setAlpha(0f);
            name.animate().translationY(0).alpha(1f).setDuration(520).setInterpolator(EASE_OUT).start();
        }
    }

    private int d(float designPx) {
        return Cards.d(s, designPx);
    }
}
