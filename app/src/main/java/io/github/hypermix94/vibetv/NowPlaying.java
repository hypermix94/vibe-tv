package io.github.hypermix94.vibetv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The last thing each app played, as NowPlayingListener saw it: a picture (at most 960 px wide) and a title, in the
 * app's own files. The music screen uses the picture when the music app hands over only a link to it.
 */
final class NowPlaying {
    static final int MAX_WIDTH = 960;

    private NowPlaying() {
    }

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "nowplaying");
    }

    static File artFile(Context c, String pkg) {
        return new File(dir(c), pkg + ".jpg");
    }

    private static File titleFile(Context c, String pkg) {
        return new File(dir(c), pkg + ".txt");
    }

    /** When this app's picture last changed, or 0 when there is none. */
    static long artTime(Context c, String pkg) {
        File f = artFile(c, pkg);
        return f.isFile() ? f.lastModified() : 0L;
    }

    /** The saved picture in RGB_565 (half the memory of ARGB; it is a photo with no transparency), or null. */
    static Bitmap loadArt(Context c, String pkg) {
        return loadFile(artFile(c, pkg));
    }

    static Bitmap loadFile(File f) {
        if (f == null || !f.isFile()) return null;
        // A picture the owner pushed can be any size: sample it down to about screen width first.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inSampleSize = 1;
        while (bounds.outWidth / (o.inSampleSize * 2) >= 1280) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    static String loadTitle(Context c, String pkg) {
        File f = titleFile(c, pkg);
        if (!f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 1024)];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Saves the picture, scaled down to MAX_WIDTH, and the title. Written to a temporary file first, then renamed. */
    static void save(Context c, String pkg, Bitmap art, CharSequence title) {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) return;
        float scale = Math.min(1f, (float) MAX_WIDTH / art.getWidth());
        Bitmap small = scale < 1f
                ? Bitmap.createScaledBitmap(art, Math.round(art.getWidth() * scale), Math.round(art.getHeight() * scale), true)
                : art;
        File tmp = new File(d, pkg + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            small.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(artFile(c, pkg))) return;
        try (OutputStream out = new FileOutputStream(titleFile(c, pkg))) {
            out.write((title != null ? title.toString() : "").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The picture alone is still worth showing.
        }
    }

    /** Decodes downloaded image bytes at no more than about twice MAX_WIDTH, to keep memory low. */
    static Bitmap decode(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2;
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}
