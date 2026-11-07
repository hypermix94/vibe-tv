package io.github.hypermix94.vibetv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Pictures for the screensaver: Wikimedia Commons' featured pictures (chosen by its community, free to show with
 * credit) of nature, landscapes, the sea and space, taken in turn. Only wide photos are used, at 1920 px. Each
 * category's list of files is kept for a week and the last pictures are kept on the TV, so the screensaver still has
 * pictures when the internet is down. Runs on a background thread.
 */
final class Scenery {
    /** One picture and its credit line. */
    static final class Picture {
        final Bitmap bitmap;
        final String credit;

        Picture(Bitmap bitmap, String credit) {
            this.bitmap = bitmap;
            this.credit = credit;
        }
    }

    /** The themes in turn, each a set of Commons categories (nature, landscape, ocean, space). */
    private static final String[][] THEMES = {
            {"Featured_pictures_of_forests", "Featured_pictures_of_waterfalls", "Featured_pictures_of_lakes"},
            {"Featured_pictures_of_landscapes", "Featured_pictures_of_mountains", "Featured_pictures_of_sunsets"},
            {"Featured_pictures_of_coasts"},
            {"Featured_pictures_of_astronomy"},
    };
    private static final long LIST_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int KEEP = 16;
    private static final String API = "https://commons.wikimedia.org/w/api.php?format=json&action=query";
    private static final String AGENT = "Stage/1.0 (Android TV screensaver; https://github.com/hypermix94/vibe-tv)";

    private final File dir;
    private final Random random = new Random();
    private int theme = new Random().nextInt(THEMES.length);

    Scenery(Context context) {
        dir = new File(context.getFilesDir(), "scenery");
    }

    /** The next picture: a new one from the next theme, or one kept from before when that fails. Null when none. */
    Picture next() {
        String[] cats = THEMES[theme];
        theme = (theme + 1) % THEMES.length;
        Picture p = null;
        try {
            p = fresh(cats[random.nextInt(cats.length)]);
        } catch (Exception ignored) {
            // offline or refused: fall back to a kept picture
        }
        return p != null ? p : kept();
    }

    private Picture fresh(String category) throws Exception {
        List<String> files = list(category);
        if (files.isEmpty()) return null;
        for (int tries = 0; tries < 6; tries++) {
            String title = files.get(random.nextInt(files.size()));
            JSONObject page = firstPage(get(API + "&prop=imageinfo&iiprop=url|size|mime|extmetadata&iiurlwidth=1920"
                    + "&iiextmetadatafilter=Artist|LicenseShortName&titles=" + enc(title)));
            JSONObject info = page != null ? page.optJSONArray("imageinfo").optJSONObject(0) : null;
            if (info == null || !"image/jpeg".equals(info.optString("mime"))) continue;
            int w = info.optInt("width"), h = info.optInt("height");
            if (w < 1600 || w < h * 1.3f) continue;   // wide photos only: they fill the screen
            byte[] jpeg = getBytes(info.optString("thumburl", info.optString("url")));
            if (jpeg == null) continue;
            Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (b == null) continue;
            String credit = credit(info.optJSONObject("extmetadata"));
            keep(title, jpeg, credit);
            return new Picture(b, credit);
        }
        return null;
    }

    /** "Photo: Name · CC BY-SA 4.0 · Wikimedia Commons". */
    private static String credit(JSONObject meta) {
        String who = meta != null ? plain(meta.optJSONObject("Artist")) : "";
        String licence = meta != null ? plain(meta.optJSONObject("LicenseShortName")) : "";
        StringBuilder s = new StringBuilder();
        if (!who.isEmpty()) s.append("Photo: ").append(who.length() > 48 ? who.substring(0, 47) + "…" : who).append(" · ");
        if (!licence.isEmpty()) s.append(licence).append(" · ");
        return s.append("Wikimedia Commons").toString();
    }

    private static String plain(JSONObject field) {
        if (field == null) return "";
        return Html.fromHtml(field.optString("value", ""), Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
    }

    /** The category's file names, from the week's list on the TV or asked afresh. */
    private List<String> list(String category) throws Exception {
        File f = new File(dir, category + ".txt");
        if (f.isFile() && System.currentTimeMillis() - f.lastModified() < LIST_AGE_MS) {
            String s = read(f);
            if (s != null && !s.isEmpty()) return new ArrayList<>(Arrays.asList(s.split("\n")));
        }
        String body = get(API + "&list=categorymembers&cmtype=file&cmlimit=500&cmtitle=" + enc("Category:" + category));
        JSONArray members = new JSONObject(body).getJSONObject("query").getJSONArray("categorymembers");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < members.length(); i++) {
            String t = members.getJSONObject(i).optString("title");
            if (t.toLowerCase(java.util.Locale.ROOT).endsWith(".jpg") || t.toLowerCase(java.util.Locale.ROOT).endsWith(".jpeg")) out.add(t);
        }
        if (!out.isEmpty() && (dir.isDirectory() || dir.mkdirs())) write(f, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
        return out;
    }

    private static JSONObject firstPage(String body) throws Exception {
        JSONObject pages = new JSONObject(body).getJSONObject("query").getJSONObject("pages");
        java.util.Iterator<String> keys = pages.keys();
        return keys.hasNext() ? pages.getJSONObject(keys.next()) : null;
    }

    // ---- pictures kept on the TV -------------------------------------------------------------------

    private void keep(String title, byte[] jpeg, String credit) {
        File kept = new File(dir, "kept");
        if (!kept.isDirectory() && !kept.mkdirs()) return;
        String name = Integer.toHexString(title.hashCode());
        write(new File(kept, name + ".jpg"), jpeg);
        write(new File(kept, name + ".txt"), credit.getBytes(StandardCharsets.UTF_8));
        File[] all = kept.listFiles((d, n) -> n.endsWith(".jpg"));
        if (all != null && all.length > KEEP) {
            Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < all.length - KEEP; i++) {
                String base = all[i].getName().replace(".jpg", "");
                all[i].delete();
                new File(kept, base + ".txt").delete();
            }
        }
    }

    private Picture kept() {
        File[] all = new File(dir, "kept").listFiles((d, n) -> n.endsWith(".jpg"));
        if (all == null || all.length == 0) return null;
        File f = all[random.nextInt(all.length)];
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        String credit = read(new File(f.getPath().replace(".jpg", ".txt")));
        return b != null ? new Picture(b, credit != null ? credit : "Wikimedia Commons") : null;
    }

    // ---- network and files ---------------------------------------------------------------------------

    private static String get(String url) throws Exception {
        byte[] b = getBytes(url);
        if (b == null) throw new java.io.IOException("no answer");
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(url).openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(15000);
            http.setRequestProperty("User-Agent", AGENT);
            if (http.getResponseCode() != 200) return null;
            try (InputStream in = http.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 6 * 1024 * 1024) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String read(File f) {
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, byte[] data) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        } catch (Exception ignored) {
            // kept next time
        }
    }
}
