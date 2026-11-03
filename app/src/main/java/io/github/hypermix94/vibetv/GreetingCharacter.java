package io.github.hypermix94.vibetv;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The music screen's spinning disco ball: Google's Noto Animated Emoji (CC BY 4.0), downloaded once into the app's
 * files and decoded at the small size it is shown at. Consumer gets null when there is no animation (offline, or none
 * exists for that emoji), and the button then keeps the plain emoji.
 */
final class GreetingCharacter {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    GreetingCharacter(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The emoji at the end of a greeting line ("Night owl, %1$s? 🦉"), or "" when it has none. */
    static String trailingEmoji(String line) {
        int space = line.lastIndexOf(' ');
        if (space < 0) return "";
        String tail = line.substring(space + 1);
        for (int i = 0; i < tail.length(); ) {
            int cp = tail.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return "";
            i += Character.charCount(cp);
        }
        return tail;
    }

    /** Noto's file name for an emoji: its code points in hex joined by "_", without variation selectors. */
    static String code(String emoji) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < emoji.length(); ) {
            int cp = emoji.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F) continue;
            if (b.length() > 0) b.append('_');
            b.append(Integer.toHexString(cp).toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    /** Loads (downloading once if needed) and starts the animation for {@code emoji}, at {@code sizePx} square. */
    void load(String emoji, int sizePx, Consumer<Drawable> show) {
        String code = code(emoji);
        File dir = new File(context.getFilesDir(), "emoji");
        File file = new File(dir, code + ".webp");
        File none = new File(dir, code + ".none");   // no animation exists for this emoji: do not ask again
        // Animated pictures need Android 9; on Android 8 the greeting shows without its character.
        if (code.isEmpty() || none.isFile() || android.os.Build.VERSION.SDK_INT < 28) {
            show.accept(null);
            return;
        }
        new Thread(() -> {
            Drawable d = null;
            try {
                if (!file.isFile()) download(code, dir, file, none);
                if (file.isFile()) {
                    d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file),
                            (decoder, info, source) -> decoder.setTargetSize(sizePx, sizePx));
                }
            } catch (Exception ignored) {
                d = null;
            }
            final Drawable result = d;
            main.post(() -> {
                if (result instanceof AnimatedImageDrawable) {
                    AnimatedImageDrawable a = (AnimatedImageDrawable) result;
                    a.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    a.start();
                }
                show.accept(result);
            });
        }, "greeting-character").start();
    }

    private static void download(String code, File dir, File file, File none) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        HttpURLConnection http = (HttpURLConnection) new URL(
                "https://fonts.gstatic.com/s/e/notoemoji/latest/" + code + "/512.webp").openConnection();
        try {
            http.setConnectTimeout(8000);
            http.setReadTimeout(15000);
            int status = http.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                new FileOutputStream(none).close();
                return;
            }
            if (status != HttpURLConnection.HTTP_OK) return;
            File tmp = new File(dir, code + ".tmp");
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 3 * 1024 * 1024) return;   // nothing in the set is this big
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(file)) tmp.delete();
        } finally {
            http.disconnect();
        }
    }
}
