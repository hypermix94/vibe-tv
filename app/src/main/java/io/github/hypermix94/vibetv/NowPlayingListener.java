package io.github.hypermix94.vibetv;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the last "now playing" picture and title of every app (NowPlaying), from the apps' media sessions, for the
 * music screen; its notification access also lets the music screen read what is playing. A picture the app hands over is saved as is; an app that only gives a web link has that one
 * image downloaded, once per link. Bound by Android only while notification access is granted
 * (cmd notification allow_listener). Notifications are not read, except those of timers and alarms (Clocks), for the
 * home screen's clock.
 */
public class NowPlayingListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(android.service.notification.StatusBarNotification n) {
        if (Clocks.isTimer(n)) Clocks.posted(n);   // every other notification is ignored
    }

    @Override
    public void onNotificationRemoved(android.service.notification.StatusBarNotification n) {
        Clocks.removed(n);
    }

    private static final String TAG = "NowPlaying";
    private static final String[] BITMAP_KEYS = {
            MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON};
    private static final String[] URI_KEYS = {
            MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI};
    private static final int MAX_DOWNLOAD = 4 * 1024 * 1024;

    private MediaSessionManager sessions;
    private final List<MediaController> watched = new ArrayList<>();
    private final List<MediaController.Callback> callbacks = new ArrayList<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener onChange = this::watch;
    /** The link each app's picture last came from, so the same one is not fetched again. */
    private final Map<String, String> lastLink = new HashMap<>();
    private final ExecutorService fetcher = Executors.newSingleThreadExecutor();

    @Override
    public void onListenerConnected() {
        // Timers and alarms for the home screen's clock: only those notifications are looked at (Clocks.isTimer).
        try {
            for (android.service.notification.StatusBarNotification n : getActiveNotifications()) {
                if (Clocks.isTimer(n)) Clocks.posted(n);
            }
        } catch (RuntimeException ignored) {
            // not bound yet
        }
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, NowPlayingListener.class);
        sessions.addOnActiveSessionsChangedListener(onChange, me);
        watch(sessions.getActiveSessions(me));
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(onChange);
        unwatchAll();
    }

    @Override
    public void onDestroy() {
        fetcher.shutdownNow();
        super.onDestroy();
    }

    private void watch(List<MediaController> controllers) {
        unwatchAll();
        if (controllers == null) return;
        for (MediaController c : controllers) {
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata m) {
                    record(c.getPackageName(), m);
                }

                @Override
                public void onPlaybackStateChanged(android.media.session.PlaybackState state) {
                    musicTimeout();
                }
            };
            c.registerCallback(cb);
            watched.add(c);
            callbacks.add(cb);
            record(c.getPackageName(), c.getMetadata());
        }
        musicTimeout();
    }

    /** The screensaver delay while music plays: the music screen comes up after this long without a button press. */
    private static final int MUSIC_TIMEOUT_MS = 15_000;

    /**
     * While a music app plays, the screensaver (the music screen, in party mode) starts after 15 seconds; when the
     * music stops, the TV's own delay comes back. The usual delay is kept in preferences while it is replaced.
     */
    private void musicTimeout() {
        try {
            android.content.ContentResolver cr = getContentResolver();
            int now = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 600_000);
            android.content.SharedPreferences p = getSharedPreferences("music_timeout", MODE_PRIVATE);
            boolean music = MusicScreen.playingMusic(this) != null;
            if (music && now != MUSIC_TIMEOUT_MS) {
                p.edit().putInt("usual", now).apply();
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, MUSIC_TIMEOUT_MS);
            } else if (!music && now == MUSIC_TIMEOUT_MS) {
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, p.getInt("usual", 600_000));
            }
        } catch (RuntimeException ignored) {
            // no permission to change the delay: the TV's own stays
        }
    }

    private void unwatchAll() {
        for (int i = 0; i < watched.size(); i++) watched.get(i).unregisterCallback(callbacks.get(i));
        watched.clear();
        callbacks.clear();
    }

    private void record(String pkg, MediaMetadata m) {
        if (m == null || pkg.equals(getPackageName())) return;
        MediaDescription desc = m.getDescription();
        CharSequence title = desc != null ? desc.getTitle() : null;
        if (title == null || title.length() == 0) return;   // between items apps send empty metadata
        Bitmap art = null;
        for (String k : BITMAP_KEYS) {
            Bitmap b = m.getBitmap(k);
            if (b != null && (art == null || b.getWidth() > art.getWidth())) art = b;
        }
        if (art == null && desc.getIconBitmap() != null) art = desc.getIconBitmap();
        if (art != null) {
            final Bitmap pic = art;
            lastLink.remove(pkg);
            fetcher.execute(() -> NowPlaying.save(this, pkg, pic, title));
            return;
        }
        String link = null;
        for (String k : URI_KEYS) {
            String u = m.getString(k);
            if (u != null && u.startsWith("https://")) {
                link = u;
                break;
            }
        }
        if (link == null && desc.getIconUri() != null && "https".equals(desc.getIconUri().getScheme())) {
            link = desc.getIconUri().toString();
        }
        if (link == null || link.equals(lastLink.get(pkg))) return;
        lastLink.put(pkg, link);
        final String url = link;
        fetcher.execute(() -> {
            Bitmap pic = download(url);
            if (pic != null) NowPlaying.save(this, pkg, pic, title);
        });
    }

    /** One image from an app's own artwork link: 8 s timeouts, at most 4 MB, or null. */
    private static Bitmap download(String url) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(url).openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(8000);
            http.setInstanceFollowRedirects(true);
            if (http.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = http.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_DOWNLOAD) return null;
                }
                return NowPlaying.decode(out.toByteArray());
            }
        } catch (Exception e) {
            Log.w(TAG, "artwork download failed: " + e);
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }
}
