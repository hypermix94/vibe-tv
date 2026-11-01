package io.github.hypermix94.vibetv;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * vibe-tv's settings, as the Settings screen sets them. Each falls back to the build's own value (local.properties)
 * or a sensible default until someone changes it.
 */
final class Prefs {
    private Prefs() {
    }

    private static SharedPreferences p(Context c) {
        return Apps.prefs(c);
    }

    static String name(Context c) {
        return p(c).getString("set_name", c.getString(R.string.owner_name)).trim();
    }

    static String weatherPlace(Context c) {
        return p(c).getString("set_place", c.getString(R.string.weather_place)).trim();
    }

    static String weatherLatitude(Context c) {
        return p(c).getString("set_lat", c.getString(R.string.weather_latitude)).trim();
    }

    static String weatherLongitude(Context c) {
        return p(c).getString("set_lon", c.getString(R.string.weather_longitude)).trim();
    }

    /** How many apps the dock holds at most: 4 to 10. */
    static int dockMax(Context c) {
        return p(c).getInt("set_dock_max", Apps.MAX_DOCK);
    }

    /** Icons per row in the home screen's Apps: 6 to 10. */
    static int columns(Context c) {
        return p(c).getInt("set_columns", 10);
    }

    static boolean seconds(Context c) {
        return p(c).getBoolean("set_seconds", true);
    }

    static boolean greetingCharacter(Context c) {
        return p(c).getBoolean("set_character", true);
    }

    /** The preview panel on the All apps page. */
    static boolean previewPanel(Context c) {
        return p(c).getBoolean("set_preview", true);
    }

    /** Minutes without a button press before the screensaver (music screen or photographs). */
    static int screensaverMinutes(Context c) {
        try {
            int ms = android.provider.Settings.System.getInt(c.getContentResolver(),
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT);
            return Math.max(1, ms / 60_000);
        } catch (android.provider.Settings.SettingNotFoundException e) {
            return 2;
        }
    }

    /** Changes the screensaver delay; false when the TV does not allow it (needs WRITE_SETTINGS). */
    static boolean setScreensaverMinutes(Context c, int minutes) {
        try {
            return android.provider.Settings.System.putInt(c.getContentResolver(),
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT, minutes * 60_000);
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Saves a change and tells screens built earlier to rebuild (the same signal the dock uses). */
    static SharedPreferences.Editor edit(Context c) {
        return p(c).edit().putLong("rev", Apps.revision(c) + 1);
    }
}
