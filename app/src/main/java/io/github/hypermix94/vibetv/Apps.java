package io.github.hypermix94.vibetv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The TV's apps and where each one lives: in the dock, in the home screen's Apps section, or off the home screen
 * (on the "Not on Home" page). The dock's order and the hidden apps are kept in the app's preferences.
 */
final class Apps {
    static final int MAX_DOCK = 10;
    private static final String PREFS = "home";

    /** One app the home screen can open. */
    static final class App {
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

    private Apps() {
    }

    /** Every app a TV can open, by package, sorted by name; this app itself left out. */
    static Map<String, App> all(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> found = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                ActivityInfo a = r.activityInfo;
                if (a.packageName.equals(c.getPackageName()) || found.containsKey(a.packageName)) continue;
                Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setClassName(a.packageName, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Drawable banner = a.loadBanner(pm);
                if (banner == null) banner = a.applicationInfo.loadBanner(pm);
                found.put(a.packageName, new App(a.packageName, r.loadLabel(pm).toString(), launch, banner, a.loadIcon(pm)));
            }
        }
        List<App> sorted = new ArrayList<>(found.values());
        sorted.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
        Map<String, App> out = new LinkedHashMap<>();
        for (App a : sorted) out.put(a.pkg, a);
        return out;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The dock's packages in order: as the owner arranged it, else the build's default list. */
    static List<String> dockOrder(Context c) {
        String saved = prefs(c).getString("dock", "");
        String list = saved.isEmpty() ? c.getString(R.string.dock_apps) : saved;
        List<String> out = new ArrayList<>();
        for (String p : list.split(",")) if (!p.trim().isEmpty() && !out.contains(p.trim())) out.add(p.trim());
        return out;
    }

    static void saveDock(Context c, List<String> pkgs) {
        prefs(c).edit().putString("dock", String.join(",", pkgs.subList(0, Math.min(pkgs.size(), MAX_DOCK))))
                .putLong("rev", revision(c) + 1).apply();
    }

    /** Goes up whenever the dock or the hidden apps change, so a screen built earlier knows to rebuild. */
    static long revision(Context c) {
        return prefs(c).getLong("rev", 0);
    }

    /** Apps taken off the home screen; they wait on the "Not on Home" page. */
    static Set<String> hidden(Context c) {
        String s = prefs(c).getString("hidden", "");
        return new LinkedHashSet<>(s.isEmpty() ? new ArrayList<>() : Arrays.asList(s.split(",")));
    }

    static void setHidden(Context c, String pkg, boolean hide) {
        Set<String> h = hidden(c);
        if (hide) h.add(pkg);
        else h.remove(pkg);
        prefs(c).edit().putString("hidden", String.join(",", h)).putLong("rev", revision(c) + 1).apply();
        if (hide) {
            List<String> d = dockOrder(c);
            if (d.remove(pkg)) saveDock(c, d);
        }
    }

    /** Puts an app at the end of the dock (and back on the home screen). False when the dock is full. */
    static boolean addToDock(Context c, String pkg) {
        List<String> d = dockOrder(c);
        if (d.contains(pkg)) return true;
        if (d.size() >= MAX_DOCK) return false;
        d.add(pkg);
        saveDock(c, d);
        setHidden(c, pkg, false);
        return true;
    }

    static void removeFromDock(Context c, String pkg) {
        List<String> d = dockOrder(c);
        if (d.remove(pkg)) saveDock(c, d);
    }

    static void markOpened(Context c, String pkg) {
        prefs(c).edit().putLong("opened_" + pkg, System.currentTimeMillis()).apply();
    }
}
