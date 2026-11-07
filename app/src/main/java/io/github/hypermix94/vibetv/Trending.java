package io.github.hypermix94.vibetv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What is popular on each streaming app, from TMDB (themoviedb.org), for the home screen to show behind the tray:
 * films and series that app streams in the country, most popular first, each with its wide picture. Needs your own
 * free TMDB key in local.properties (stage.tmdbKey); without one, nothing is asked and the home screen shows logos.
 * Lists are kept for six hours and pictures on disk, so the home screen shows them at once. This product uses the TMDB
 * API but is not endorsed or certified by TMDB.
 */
final class Trending {
    /** One title: its name, where its wide picture is kept, and its own title logo when it has one (else ""). */
    static final class Item {
        final String title, picture, logo;

        Item(String title, String picture, String logo) {
            this.title = title;
            this.picture = picture;
            this.logo = logo;
        }
    }

    /** Each app and its TMDB watch providers (several where the service was renamed). */
    private static final Map<String, String> PROVIDERS = new HashMap<>();

    static {
        PROVIDERS.put("com.netflix.ninja", "8");
        PROVIDERS.put("com.amazon.amazonvideo.livingroom", "119");
        PROVIDERS.put("in.startv.hotstar", "2336");
        PROVIDERS.put("com.graymatrix.did", "232");
        PROVIDERS.put("com.suntv.sunnxt", "309");
    }

    private static final long KEEP_MS = 6 * 3600_000L, RETRY_MS = 15 * 60_000L;
    private static final int MAX = 8;

    private Trending() {
    }

    static boolean enabled(Context c) {
        return !c.getString(R.string.tmdb_key).isEmpty();
    }

    static boolean covers(String pkg) {
        return PROVIDERS.containsKey(pkg);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("trending2", Context.MODE_PRIVATE);
    }

    /** The last list for an app, whose pictures are on disk; empty before the first answer. */
    static List<Item> cached(Context c, String pkg) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(pkg, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String logo = o.optString("l");
                if (!logo.isEmpty() && !new File(logo).exists()) logo = "";
                if (new File(o.getString("p")).exists()) out.add(new Item(o.getString("t"), o.getString("p"), logo));
            }
        } catch (Exception ignored) {
            // nothing kept yet
        }
        return out;
    }

    /** Asks TMDB again when the kept list is old enough; call off the main thread. True when the list changed. */
    static boolean refresh(Context c, String pkg) {
        if (!enabled(c) || !covers(pkg)) return false;
        SharedPreferences p = prefs(c);
        long age = System.currentTimeMillis() - p.getLong("at_" + pkg, 0);
        boolean ok = p.getBoolean("ok_" + pkg, false);
        if (age >= 0 && age < (ok ? KEEP_MS : RETRY_MS)) return false;
        p.edit().putLong("at_" + pkg, System.currentTimeMillis()).putBoolean("ok_" + pkg, false).apply();
        try {
            String region = java.util.Locale.getDefault().getCountry();
            if (region.isEmpty()) region = "IN";
            // "Trending" means new: series with an episode in the last two months, films from the last six months
            // (most popular across the whole catalogue brings up shows from years ago).
            java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT);
            long now = System.currentTimeMillis();
            String series = "&air_date.gte=" + day.format(new java.util.Date(now - 60L * 86_400_000L));
            String films = "&primary_release_date.gte=" + day.format(new java.util.Date(now - 180L * 86_400_000L));
            List<JSONObject> found = new ArrayList<>();
            for (String kind : new String[]{"tv", "movie"}) {
                JSONObject r = new JSONObject(get(c, "https://api.themoviedb.org/3/discover/" + kind
                        + "?watch_region=" + region + "&with_watch_providers=" + PROVIDERS.get(pkg).replace("|", "%7C")
                        + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&page=1"
                        + (kind.equals("tv") ? series : films) + "&api_key="
                        + c.getString(R.string.tmdb_key)));
                JSONArray a = r.getJSONArray("results");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    if (!o.optString("backdrop_path").isEmpty()) found.add(o.put("kind", kind));
                }
            }
            found.sort((x, y) -> Double.compare(y.optDouble("popularity"), x.optDouble("popularity")));
            File dir = new File(c.getCacheDir(), "trending");
            dir.mkdirs();
            JSONArray keep = new JSONArray();
            for (JSONObject o : found) {
                if (keep.length() >= MAX) break;
                String path = o.getString("backdrop_path");
                File f = new File(dir, path.replaceAll("[^A-Za-z0-9.]", ""));
                if (!f.exists()) {
                    byte[] img = bytes("https://image.tmdb.org/t/p/w1280" + path);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(img);
                    }
                }
                String title = o.has("name") ? o.getString("name") : o.optString("title");
                keep.put(new JSONObject().put("t", title).put("p", f.getAbsolutePath()).put("l", titleLogo(c, dir, o)));
            }
            if (keep.length() == 0) return false;
            p.edit().putString(pkg, keep.toString()).putBoolean("ok_" + pkg, true).apply();
            return true;
        } catch (Exception e) {
            return false;   // no network, or a bad key: the logos stay, and it asks again later
        }
    }

    /**
     * The title's own logo (its name drawn in the show's own lettering, as on its poster), English or wordless, as a
     * PNG with a clear background; "" when TMDB has none.
     */
    private static String titleLogo(Context c, File dir, JSONObject o) {
        try {
            JSONArray logos = new JSONObject(get(c, "https://api.themoviedb.org/3/" + o.getString("kind") + "/" + o.getLong("id")
                    + "/images?include_image_language=en,null&api_key=" + c.getString(R.string.tmdb_key))).optJSONArray("logos");
            for (int i = 0; logos != null && i < logos.length(); i++) {
                String path = logos.getJSONObject(i).getString("file_path");
                if (!path.endsWith(".png")) continue;   // SVG logos cannot be drawn here
                File f = new File(dir, "logo" + path.replaceAll("[^A-Za-z0-9.]", ""));
                if (!f.exists()) {
                    byte[] img = bytes("https://image.tmdb.org/t/p/w500" + path);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(img);
                    }
                }
                return f.getAbsolutePath();
            }
        } catch (Exception ignored) {
            // no logo: the name in words
        }
        return "";
    }

    /** A title logo, decoded with its clear background. */
    static Bitmap logo(String path) {
        return path.isEmpty() ? null : BitmapFactory.decodeFile(path);
    }

    /** A kept picture, decoded no wider than the screen needs. */
    static Bitmap picture(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; a photo shows no difference
        return BitmapFactory.decodeFile(path, o);
    }

    private static String get(Context c, String url) throws Exception {
        return new String(bytes(url), "UTF-8");
    }

    private static byte[] bytes(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(12000);
        h.setRequestProperty("User-Agent", "Stage/1.0 (Android TV home screen; https://github.com/hypermix94/vibe-tv)");
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            h.disconnect();
        }
    }
}
