package io.github.hypermix94.vibetv;

import android.content.Context;

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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyrics for the song a music app reports, from LRCLIB (lrclib.net: an open lyrics library, no account or key). The
 * music apps do not share their own lyrics with other apps, so the song is looked up by its title, artist, album and
 * length. Timed lyrics (LRC) when the library has them, else plain lines, else none. Each answer is kept in the
 * app's files, so a song is looked up once; the library answers "busy" at times, so that is retried a few times.
 * Lyrics in the song's own script are preferred: of the library's versions of a Tamil song, one in Tamil script wins
 * over a romanised one.
 */
final class Lyrics {
    /** One line and when it starts, in milliseconds; -1 for plain (untimed) lyrics. */
    static final class Line {
        final long at;
        final String text;

        Line(long at, String text) {
            this.at = at;
            this.text = text;
        }
    }

    /** The lines, timed or not; empty when the library has nothing for the song. */
    final List<Line> lines;
    final boolean timed;

    private Lyrics(List<Line> lines, boolean timed) {
        this.lines = lines;
        this.timed = timed;
    }

    static final Lyrics NONE = new Lyrics(new ArrayList<>(), false);

    private static final long MISS_AGE_MS = 3L * 24 * 60 * 60 * 1000;
    /** Versions of a song that keep its words: " - Slowed", "(Sped Up)", " - Remastered 2011", "(Live)" and so on. */
    private static final String VERSION =
            "(slowed|sped ?up|reverb|nightcore|remaster(ed)?|live|radio edit|acoustic|remix|version|edit|mono|stereo)";
    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]");

    /** Looks the song up (cache first); null when the library could not be asked (offline or busy). Background thread. */
    static Lyrics find(Context context, String title, String artist, String album, long durationMs) {
        String song = cleanTitle(title), who = firstArtist(artist);
        if (song.isEmpty()) return NONE;
        File dir = new File(context.getFilesDir(), "lyrics");
        File cache = new File(dir, Integer.toHexString((song + "|" + who).toLowerCase(Locale.ROOT).hashCode()) + ".v4.txt");
        String raw = read(cache);
        // A song the library did not have is asked about again after a few days: lyrics get added all the time.
        if (raw != null && raw.startsWith("N") && System.currentTimeMillis() - cache.lastModified() > MISS_AGE_MS) raw = null;
        if (raw == null) {
            raw = fetch(song, who, album, durationMs);
            if (raw == null) return null;   // could not ask (offline or busy): the caller asks again shortly
            if (dir.isDirectory() || dir.mkdirs()) write(cache, raw);
        }
        return parse(raw);
    }

    /** "Kadhalikathey - From "Imaikkaa Nodigal"" and "Song (feat. X)" are listed as just the song. */
    static String cleanTitle(String title) {
        if (title == null) return "";
        String t = title.replaceAll("(?i)\\s*[-–]\\s*from\\s+.*$", "")
                .replaceAll("(?i)\\s*[(\\[](feat|ft|from|with)\\b.*?[)\\]]", "")
                .replaceAll("(?i)\\s*[-–]\\s*[^-–]*\\b" + VERSION + "\\b.*$", "")
                .replaceAll("(?i)\\s*[(\\[][^)\\]]*\\b" + VERSION + "\\b[^)\\]]*[)\\]]", "");
        return t.trim();
    }

    static String firstArtist(String artist) {
        if (artist == null) return "";
        return artist.split(",|&| feat\\.| ft\\.")[0].trim();
    }

    /** "S" + synced LRC, "P" + plain lyrics, or "N" for none; null when the library could not be asked. */
    private static String fetch(String song, String who, String album, long durationMs) {
        try {
            String q = "track_name=" + enc(song) + "&artist_name=" + enc(who)
                    + (album != null && !album.isEmpty() ? "&album_name=" + enc(album) : "")
                    + (durationMs > 0 ? "&duration=" + Math.round(durationMs / 1000.0) : "");
            String got = get("https://lrclib.net/api/get?" + q);
            String exact = got != null && !got.isEmpty() ? pick(new JSONObject(got)) : null;
            if (exact != null && exact.startsWith("S") && latinShare(exact) < 0.6f) return exact;
            // Not an exact match, or romanised: search all versions and take the best.
            String list = get("https://lrclib.net/api/search?track_name=" + enc(song) + "&artist_name=" + enc(who));
            if (list == null) return exact;
            JSONArray results = new JSONArray(list);
            if (results.length() == 0) {
                // Nothing under that artist (credited differently, or a cover): a free-text search, keeping only
                // results with the same song name so a different song is never shown.
                String loose = get("https://lrclib.net/api/search?q=" + enc(song + " " + who));
                JSONArray all = loose != null && !loose.isEmpty() ? new JSONArray(loose) : new JSONArray();
                results = new JSONArray();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject o = all.getJSONObject(i);
                    if (cleanTitle(o.optString("trackName")).equalsIgnoreCase(song)) results.put(o);
                }
            }
            String best = exact;
            int bestRank = rank(exact);
            for (int i = 0; i < results.length(); i++) {
                String p = pick(results.getJSONObject(i));
                int r = rank(p);
                if (r > bestRank) {
                    best = p;
                    bestRank = r;
                }
            }
            return best != null ? best : "N";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Timed beats plain, the song's own script beats romanised: 3 timed in its script, 2 timed, 1 plain in its
     * script, 0 plain, -1 none. (An English song's own script is Latin, so for those every version ranks the same.)
     */
    private static int rank(String p) {
        if (p == null) return -1;
        boolean native_ = latinShare(p) < 0.6f;
        return p.startsWith("S") ? (native_ ? 3 : 2) : (native_ ? 1 : 0);
    }

    /** The share of letters that are Latin (a to z, with or without accents). */
    static float latinShare(String text) {
        int letters = 0, latin = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) latin++;
        }
        return letters == 0 ? 1f : latin / (float) letters;
    }

    private static String pick(JSONObject o) {
        String synced = o.optString("syncedLyrics", "");
        if (!synced.isEmpty() && !"null".equals(synced)) return "S" + synced;
        String plain = o.optString("plainLyrics", "");
        if (!plain.isEmpty() && !"null".equals(plain)) return "P" + plain;
        return null;
    }

    private static Lyrics parse(String raw) {
        if (raw.isEmpty() || raw.charAt(0) == 'N') return NONE;
        boolean timed = raw.charAt(0) == 'S';
        List<Line> out = new ArrayList<>();
        for (String row : raw.substring(1).split("\n")) {
            if (timed) {
                Matcher m = STAMP.matcher(row);
                long at = -1;
                int end = 0;
                while (m.find()) {   // a line can carry several stamps; the first is enough here
                    if (at < 0) {
                        String frac = m.group(3) != null ? m.group(3) : "0";
                        long ms = Long.parseLong((frac + "00").substring(0, 3));
                        at = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000 + ms;
                    }
                    end = m.end();
                }
                String text = row.substring(end).trim();
                if (at >= 0 && !text.isEmpty()) out.add(new Line(at, text));
            } else if (!row.trim().isEmpty()) {
                out.add(new Line(-1, row.trim()));
            }
        }
        return out.isEmpty() ? NONE : new Lyrics(out, timed);
    }

    /** The body of a 200 answer; "" for 404 (not in the library); null when busy or unreachable, after retries. */
    private static String get(String url) throws InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection http = null;
            try {
                http = (HttpURLConnection) new URL(url).openConnection();
                http.setConnectTimeout(8000);
                http.setReadTimeout(10000);
                http.setRequestProperty("User-Agent", "vibe-tv (https://github.com/hypermix94/vibe-tv)");
                int status = http.getResponseCode();
                if (status == 404) return "";
                if (status == 200) {
                    try (InputStream in = http.getInputStream()) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0 && out.size() < 512 * 1024) out.write(buf, 0, n);
                        return out.toString("UTF-8");
                    }
                }
            } catch (Exception ignored) {
                // try again below
            } finally {
                if (http != null) http.disconnect();
            }
            Thread.sleep(1500L * (attempt + 1));   // "server busy": wait a little longer each time
        }
        return null;
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

    private static void write(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // looked up again next time
        }
    }
}
