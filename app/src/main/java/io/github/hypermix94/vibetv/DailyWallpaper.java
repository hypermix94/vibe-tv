package io.github.hypermix94.vibetv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;

/**
 * The home screen's wallpaper: Wikimedia Commons' Picture of the Day, one for the whole day and every app, so it is a
 * different set from the screensaver's nature, sea and space photographs. Many days' pictures are tall; those are
 * skipped for the most recent wide one. Kept on the TV, so it is fetched once a day and still shows offline.
 * Runs on a background thread.
 */
final class DailyWallpaper {
    private static final String AGENT = "vibe-tv/1.0 (Android TV home screen; https://github.com/hypermix94/vibe-tv)";
    private static final int KEEP = 3;

    /** The picture and its credit line. */
    static final class Picture {
        final Bitmap bitmap;
        final String credit;

        Picture(Bitmap bitmap, String credit) {
            this.bitmap = bitmap;
            this.credit = credit;
        }
    }

    private DailyWallpaper() {
    }

    /** Today's picture: kept, or fetched; the last one kept when today's cannot be had. Null when there is none. */
    static Picture today(Context context) {
        File dir = new File(context.getFilesDir(), "wallpaper");
        String day = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Calendar.getInstance().getTime());
        File jpg = new File(dir, day + ".jpg"), txt = new File(dir, day + ".txt");
        if (!jpg.isFile() && (dir.isDirectory() || dir.mkdirs())) fetch(jpg, txt);
        if (!jpg.isFile()) {
            File[] all = dir.listFiles((d, n) -> n.endsWith(".jpg"));
            if (all == null || all.length == 0) return null;
            Arrays.sort(all);
            jpg = all[all.length - 1];
            txt = new File(jpg.getPath().replace(".jpg", ".txt"));
        }
        Bitmap b = BitmapFactory.decodeFile(jpg.getPath());
        return b != null ? new Picture(b, read(txt)) : null;
    }

    /** Walks back from today to the most recent wide Picture of the Day and saves it at 1920 px. */
    private static void fetch(File jpg, File txt) {
        Calendar day = Calendar.getInstance();
        SimpleDateFormat path = new SimpleDateFormat("yyyy/MM/dd", Locale.ROOT);
        for (int back = 0; back < 21; back++) {
            try {
                byte[] body = get("https://api.wikimedia.org/feed/v1/wikipedia/en/featured/" + path.format(day.getTime()));
                JSONObject image = body != null ? new JSONObject(new String(body, "UTF-8")).optJSONObject("image") : null;
                if (image != null) {
                    JSONObject full = image.optJSONObject("image");
                    int w = full != null ? full.optInt("width") : 0, h = full != null ? full.optInt("height") : 1;
                    JSONObject thumb = image.optJSONObject("thumbnail");
                    if (w >= h * 1.4f && thumb != null) {
                        String url = thumb.optString("source").replaceAll("/\\d+px-", "/1920px-");
                        byte[] picture = get(url);
                        if (picture != null) {
                            write(jpg, picture);
                            JSONObject artist = image.optJSONObject("artist");
                            String who = artist != null ? artist.optString("text", "").replaceAll("\\s+", " ").trim() : "";
                            if (who.length() > 40) who = "";
                            write(txt, ((who.isEmpty() ? "" : "Photo: " + who + " · ")
                                    + "Picture of the Day · Wikimedia Commons").getBytes("UTF-8"));
                            prune(jpg.getParentFile());
                            return;
                        }
                    }
                }
            } catch (Exception ignored) {
                // try the day before
            }
            day.add(Calendar.DAY_OF_MONTH, -1);
        }
    }

    private static void prune(File dir) {
        File[] all = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (all == null || all.length <= KEEP) return;
        Arrays.sort(all);
        for (int i = 0; i < all.length - KEEP; i++) {
            all[i].delete();
            new File(all[i].getPath().replace(".jpg", ".txt")).delete();
        }
    }

    private static byte[] get(String url) {
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
                while ((n = in.read(buf)) > 0 && out.size() < 8 * 1024 * 1024) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static String read(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static void write(File f, byte[] data) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        } catch (Exception ignored) {
            // fetched again next time
        }
    }
}
