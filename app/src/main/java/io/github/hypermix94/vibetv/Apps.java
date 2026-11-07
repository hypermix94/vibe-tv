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
                boolean mine = a.packageName.equals(c.getPackageName());
                if ((mine && !a.name.endsWith(".SettingsActivity")) || found.containsKey(a.packageName)) continue;   // our Settings shows
                if (!mine && !wanted(a.applicationInfo)) continue;   // factory utilities stay out of sight
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

    /**
     * The apps worth listing: ones you installed, ones kept up to date from the Play Store (Netflix, YouTube...), the
     * TV's own Settings, and its TV app (live TV and the inputs). Factory utilities (notification centres, help,
     * media players, updaters) are left out.
     */
    private static boolean wanted(android.content.pm.ApplicationInfo ai) {
        boolean system = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updated = (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        if (!system || updated) return true;
        String p = ai.packageName;
        return p.equals("com.android.tv.settings") || p.equals("com.android.vending")
                || p.contains("tvcenter") || p.equals("com.google.android.tv") || p.contains("livetv");
    }

    /**
     * What the hold-OK menu offers for getting rid of an app: "Uninstall" for one you installed; "Disable" for one
     * that came with the TV (YouTube on Google TV, say), which Android cannot uninstall, only switch off; null for the
     * TV's own Settings, Play Store and live TV, which the TV needs, and for vibe-tv itself.
     */
    static String removeAction(Context c, String pkg) {
        if (pkg.equals(c.getPackageName())) return null;
        try {
            android.content.pm.ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) return "Uninstall";
            boolean needed = pkg.equals("com.android.tv.settings") || pkg.equals("com.android.vending")
                    || pkg.contains("tvcenter") || pkg.equals("com.google.android.tv") || pkg.contains("livetv");
            return needed ? null : "Disable";
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Uninstall asks Android's own uninstaller; Disable opens the app's page in the TV's Settings, where its Disable
     * button is, since only the system may switch an app off.
     */
    static void remove(Context c, String pkg, String action) {
        android.net.Uri uri = android.net.Uri.parse("package:" + pkg);
        Intent i = "Uninstall".equals(action) ? new Intent(Intent.ACTION_DELETE, uri)
                : new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri);
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
        prefs(c).edit().putString("dock", String.join(",", pkgs.subList(0, Math.min(pkgs.size(), Prefs.dockMax(c)))))
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
        if (d.size() >= Prefs.dockMax(c)) return false;
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
