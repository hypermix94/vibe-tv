package io.github.hypermix94.vibetv;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.audiofx.Visualizer;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The music screen, shown by MusicDream (the screensaver) while music plays, styled after Spotify's TV player: the
 * song's cover over a deep blur of itself that moves with the bass, the title, artist and a wavy timeline.
 * OK shows the lyrics on the right with the cover moved left (timed where the open library has them, the current line
 * white, the rest dimmed, scrolling in time), or a light-hearted note when there are none; OK again hides them. Any
 * other key closes it, like a screensaver. It lives inside the screensaver because Android keeps a screensaver above
 * every activity, and ending the screensaver after the idle time would put the TV to sleep.
 * Reads the music through NowPlayingListener's notification access.
 */
final class MusicScreen extends android.content.ContextWrapper {

    /** What shows the screen: MusicDream, the screensaver. */
    interface Host {
        Window window();

        /** A key asked to close the screen. */
        void close();
    }

    private final Host host;

    MusicScreen(Context base, Host host) {
        super(base);
        this.host = host;
    }

    private Window getWindow() {
        return host.window();
    }

    private void finish() {
        host.close();
    }

    /** The apps whose music shows here. */
    static final List<String> MUSIC_APPS = Arrays.asList(
            "com.spotify.tv.android", "com.spotify.music", "com.google.android.youtube.tvmusic",
            "com.google.android.apps.youtube.music", "com.apple.android.music", "com.amazon.mp3",
            "com.jio.media.jiobeats", "com.gaana", "com.bsbportal.music", "com.aspiro.tidal", "deezer.android.tv",
            "deezer.android.app", "com.soundcloud.android", "com.pandora.android.atv", "com.pandora.android",
            "com.clearchannel.iheartradio.controller");

    /** Video apps play sound too, but they are for watching: the music screen never covers them. */
    private static final List<String> VIDEO_APPS = Arrays.asList(
            "com.google.android.youtube.tv", "com.google.android.youtube", "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom", "in.startv.hotstar", "com.graymatrix.did");

    /** A music app: one of the known ones, or any app that calls itself an audio app; never a video app. */
    static boolean isMusicApp(Context c, String pkg) {
        if (VIDEO_APPS.contains(pkg)) return false;
        if (MUSIC_APPS.contains(pkg)) return true;
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0).category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Material's standard ease: quick to start, long gentle settle. */
    private static final android.view.animation.Interpolator EASE =
            new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);
    /** Plain jokes that read the same in any country: no local drinks, slang or film references. */
    private static final String[] NO_LYRICS = {
            "The lyrics went on a coffee break. \u2615",
            "No words found, but the beat is all yours. \uD83C\uDFA7",
            "No lyrics here. Sing whatever you like! \uD83C\uDFA4",
            "Even the lyrics are speechless for this one. \uD83E\uDD10",
            "This song is all feeling, no words on file. \u2728",
            "The lyrics are stuck in traffic. Hum along for now. \uD83D\uDE97\uD83D\uDCA8",
            "The songwriter forgot to write this one down. \uD83D\uDE05",
            "No lyrics, so tonight you are the singer. \uD83C\uDF1F",
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaController playing;
    private float dp;
    private FrameLayout root;
    private ImageView backdrop, cover;
    private LinearLayout side, lyricsColumn;
    private android.widget.ScrollView lyricsScroll;
    private FrameLayout lyricsPane;
    private TextView title, artist, elapsed, remaining, noLyrics, lyricsButton, partyButton;
    /** The two pills: the same height, their content centred; the highlight changes colour only, never size. */
    private FrameLayout lyricsPill, partyPill;
    private PartyView party;
    private boolean partyOn;
    /** Which bottom button Left and Right have picked: 0 Lyrics, 1 Party. */
    private int picked;
    private WaveProgress wave;
    private GlideText titleGlide, artistGlide;
    /** The elapsed and remaining times under the timeline. */
    private LinearLayout times;
    private boolean lyricsOn;
    private Lyrics lyrics = Lyrics.NONE;
    /** True while the current song's lyrics are being looked up: "Finding the lyrics…", not the no-lyrics joke. */
    private boolean lyricsLoading;
    private volatile String songKey = "";   // read by the lyrics thread too
    private int currentLine = -1;
    private final List<TextView> lineViews = new ArrayList<>();

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata m) {
            show(m);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            keepAwake(state != null && state.getState() == PlaybackState.STATE_PLAYING);
        }

        @Override
        public void onSessionDestroyed() {
            keepAwake(false);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            main.postDelayed(this, 250);
        }
    };

    /** The screen for the music playing now, or null when nothing plays any more. */
    View create() {
        dp = getResources().getDisplayMetrics().density;
        playing = playingMusic(this);
        if (playing == null) return null;
        View screen = buildScreen();
        playing.registerCallback(callback);
        show(playing.getMetadata());
        keepAwake(true);
        return screen;
    }

    void resume() {
        main.post(tick);
        listen();   // the background breathes with the bass; party mode uses the same levels
    }

    void pause() {
        stopListening();
        if (party != null) party.stop();
        main.removeCallbacks(tick);
    }

    void destroy() {
        pause();
        holdThroughDay(false);   // the TV's own 24-hour limit comes back with the screen gone
        if (playing != null) playing.unregisterCallback(callback);
        main.removeCallbacksAndMessages(null);
    }

    /**
     * Left and Right pick a button (Lyrics or Party), OK presses it; any other key closes the screen, the way a key
     * ends a screensaver.
     */
    boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean side = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (side) {
                picked = k == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1;
                styleButtons();
            } else if (ok) {
                if (picked == 0) setLyricsShown(!lyricsOn);
                else setPartyOn(!partyOn);
            } else {
                finish();
            }
        }
        return true;
    }

    /** The session of a music app that is playing right now, or null. */
    static MediaController playingMusic(Context context) {
        try {
            MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
            for (MediaController c : sessions.getActiveSessions(new ComponentName(context, NowPlayingListener.class))) {
                PlaybackState s = c.getPlaybackState();
                if (isMusicApp(context, c.getPackageName()) && s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    return c;
                }
            }
        } catch (SecurityException e) {
            // Notification access is off: no music screen.
        }
        return null;
    }

    /** Holds the screen on while the music plays, so the sleep timer cannot switch the TV off mid-song. */
    private void keepAwake(boolean on) {
        if (getWindow() == null) return;
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        holdThroughDay(on);
    }

    private static final String ATTENTIVE = "attentive_timeout";
    private static final int ATTENTIVE_DEFAULT = 86_400_000;   // the TV's own value: 24 hours

    /**
     * Android switches the TV off after 24 hours without a button press even while something keeps the screen on
     * (attentive_timeout). While music plays that is turned off; it goes back to the TV's own value as soon as the
     * music pauses or the screen closes. Needs WRITE_SECURE_SETTINGS (granted over adb); without it, nothing changes.
     */
    private void holdThroughDay(boolean music) {
        android.content.ContentResolver cr = getContentResolver();
        android.content.SharedPreferences p = getSharedPreferences("music_screen", MODE_PRIVATE);
        try {
            if (music) {
                int now = android.provider.Settings.Secure.getInt(cr, ATTENTIVE, ATTENTIVE_DEFAULT);
                if (now >= 0 && !p.contains(ATTENTIVE)) p.edit().putInt(ATTENTIVE, now).apply();
                android.provider.Settings.Secure.putInt(cr, ATTENTIVE, -1);
            } else if (p.contains(ATTENTIVE)) {
                android.provider.Settings.Secure.putInt(cr, ATTENTIVE, p.getInt(ATTENTIVE, ATTENTIVE_DEFAULT));
                p.edit().remove(ATTENTIVE).apply();
            }
        } catch (SecurityException ignored) {
            // no permission: the TV keeps its 24-hour limit
        }
    }

    // ---- layout -----------------------------------------------------------------------------------

    private int px(float v) {
        return Math.round(v * dp);
    }

    private View buildScreen() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Spotify's look: one steady, solid field of the cover's main colour.
        backdrop = new ImageView(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        party = new PartyView(this);
        // The cover pumps with the bass, eased every frame by the party view so it swells rather than jumps.
        party.setOnFrame(() -> {
            if (!partyOn) return;
            float pump = party.pump();
            cover.setScaleX(pump);
            cover.setScaleY(pump);
        });
        party.setAlpha(0f);
        party.setVisibility(View.INVISIBLE);
        root.addView(party, new FrameLayout.LayoutParams(-1, -1));

        // Lyrics on the right, hidden until OK.
        lyricsPane = new FrameLayout(this);
        lyricsPane.setAlpha(0f);
        lyricsPane.setTranslationX(px(40));
        lyricsPane.setClipChildren(true);
        FrameLayout.LayoutParams paneLp = new FrameLayout.LayoutParams(px(480), -1, Gravity.END);
        paneLp.rightMargin = px(75);
        root.addView(lyricsPane, paneLp);
        // The song's lines run far below the screen: a ScrollView measures them all at full height, and is scrolled
        // to the current line in code (no focus, no scroll bar).
        lyricsScroll = new android.widget.ScrollView(this);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        lyricsScroll.setFocusable(false);
        lyricsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        lyricsScroll.setVerticalFadingEdgeEnabled(true);   // lines fade out at the top and bottom, clear of the buttons
        lyricsScroll.setFadingEdgeLength(px(110));
        lyricsPane.addView(lyricsScroll, new FrameLayout.LayoutParams(-1, -1));
        lyricsColumn = new LinearLayout(this);
        lyricsColumn.setOrientation(LinearLayout.VERTICAL);
        // Room above the first line and below the last, so either can sit at the reading spot.
        lyricsColumn.setPadding(0, px(200), 0, px(400));
        lyricsPane.setPadding(0, px(30), 0, px(80));
        lyricsScroll.addView(lyricsColumn, new FrameLayout.LayoutParams(-1, -2));
        noLyrics = text(26f, R.font.outfit_medium, Color.WHITE, false);
        noLyrics.setLineSpacing(0, 1.1f);
        noLyrics.setVisibility(View.GONE);
        lyricsPane.addView(noLyrics, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));

        // The cover, title, artist and progress: in the middle, moving right when the lyrics show.
        side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.START);   // title and artist left-aligned under the cover, like Spotify
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 5 * dp);
            }
        });
        cover.setClipToOutline(true);
        // A soft, even shadow: Android's light sits above the screen, so a tall elevation throws the shadow far down.
        cover.setElevation(px(8));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            cover.setOutlineSpotShadowColor(0x33000000);
            cover.setOutlineAmbientShadowColor(0x66000000);
        }
        side.addView(cover, new LinearLayout.LayoutParams(px(260), px(260)));
        title = text(21f, R.font.outfit_medium, Color.WHITE, true);
        title.setTypeface(lyricFace());
        // A long name glides slowly to its end, comes back, rests two seconds and goes again.
        title.setFadingEdgeLength(px(24));
        titleGlide = new GlideText(title);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = px(18);
        side.addView(title, titleLp);
        artist = text(13f, R.font.outfit_regular, 0xB3FFFFFF, true);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(-1, -2);
        artistLp.topMargin = px(3);
        artist.setFadingEdgeLength(px(24));
        artistGlide = new GlideText(artist);
        side.addView(artist, artistLp);

        // The timeline: a flowing wave for the part played, in the cover's second colour.
        wave = new WaveProgress(this);
        LinearLayout.LayoutParams waveLp = new LinearLayout.LayoutParams(px(260), px(18));
        waveLp.topMargin = px(10);
        side.addView(wave, waveLp);
        times = new LinearLayout(this);
        elapsed = text(10f, R.font.outfit_regular, 0x99FFFFFF, true);
        remaining = text(10f, R.font.outfit_regular, 0x99FFFFFF, true);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1f));
        remaining.setGravity(Gravity.END);
        times.addView(remaining, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams timesLp = new LinearLayout.LayoutParams(px(260), -2);
        timesLp.topMargin = px(5);
        side.addView(times, timesLp);
        root.addView(side, new FrameLayout.LayoutParams(px(260), -2, Gravity.CENTER));

        // The two buttons along the bottom: Left and Right pick one, OK presses it.
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        lyricsButton = text(13f, R.font.outfit_medium, Color.WHITE, true);
        lyricsButton.setText("Lyrics");
        lyricsPill = pill(lyricsButton, -2);
        lyricsButton.setPadding(px(18), 0, px(18), 0);
        // Party: just a disco ball, spinning (Noto's animated mirror ball, as for the greeting's characters), in a
        // round pill; the plain emoji until the animation is ready, or for good offline.
        partyButton = text(16f, R.font.outfit_regular, Color.WHITE, true);
        partyButton.setText("\uD83E\uDEA9");
        partyPill = pill(partyButton, px(PILL_H));
        ImageView ball = new ImageView(this);
        partyPill.addView(ball, new FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER));
        new GreetingCharacter(this).load("\uD83E\uDEA9", px(22), d -> {
            if (d == null) return;
            ball.setImageDrawable(d);
            partyButton.setVisibility(View.GONE);
        });
        buttons.addView(lyricsPill, new LinearLayout.LayoutParams(-2, px(PILL_H)));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(px(PILL_H), px(PILL_H));
        gap.leftMargin = px(12);
        buttons.addView(partyPill, gap);
        FrameLayout.LayoutParams buttonsLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        buttonsLp.bottomMargin = px(26);
        root.addView(buttons, buttonsLp);
        styleButtons();
        return root;
    }

    private static final int PILL_H = 34;

    /** A pill of the shared height with its content centred in it. */
    private FrameLayout pill(View content, int width) {
        FrameLayout f = new FrameLayout(this);
        f.setMinimumWidth(width > 0 ? width : px(PILL_H));
        f.addView(content, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return f;
    }

    /** The picked pill is solid white with dark text, the other frosted glass; party on adds a glowing pink ring. */
    private void styleButtons() {
        FrameLayout[] pills = {lyricsPill, partyPill};
        TextView[] labels = {lyricsButton, partyButton};
        for (int i = 0; i < pills.length; i++) {
            boolean on = i == picked;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? 0xF2FFFFFF : 0x2EFFFFFF);
            bg.setCornerRadius(px(PILL_H / 2f));
            if (pills[i] == partyPill && partyOn) bg.setStroke(px(2), 0xFFFF4FD8);
            pills[i].setBackground(bg);
            labels[i].setTextColor(on ? 0xFF111111 : 0xE6FFFFFF);
            labels[i].setShadowLayer(0, 0, 0, 0);   // no shadow inside a pill
        }
    }

    private android.graphics.Typeface lyricFace;

    /** Outfit at weight 700 (the font is variable), for the lyric lines. */
    private android.graphics.Typeface lyricFace() {
        if (lyricFace == null) {
            android.graphics.Typeface base = getResources().getFont(R.font.outfit_medium);
            lyricFace = android.os.Build.VERSION.SDK_INT >= 28 ? android.graphics.Typeface.create(base, 700, false) : base;
        }
        return lyricFace;
    }

    private TextView text(float sp, int font, int colour, boolean single) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(getResources().getFont(font));
        t.setTextColor(colour);
        t.setShadowLayer(3 * dp, 0, 1 * dp, 0x66000000);   // a light shadow: readable over bright party lights
        if (single) {
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        return t;
    }

    // ---- song -------------------------------------------------------------------------------------

    /** The song's cover, name and artist, its colour behind, and its lyrics looked up. */
    private void show(MediaMetadata m) {
        if (m == null || cover == null) return;
        MediaDescription d = m.getDescription();
        String name = d != null && d.getTitle() != null ? d.getTitle().toString() : "";
        CharSequence by = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (by == null && d != null) by = d.getSubtitle();
        String who = by != null ? by.toString() : "";
        titleGlide.setText(name);
        artistGlide.setText(who);
        Bitmap art = null;
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap b = m.getBitmap(k);
            if (b != null && (art == null || b.getWidth() > art.getWidth())) art = b;
        }
        if (art == null && playing != null) art = NowPlaying.loadArt(this, playing.getPackageName());   // a downloaded cover
        cover.setImageBitmap(art);
        backdrop.setImageDrawable(new android.graphics.drawable.ColorDrawable(art != null ? primary(art) : 0xFF181818));
        if (art != null) {
            int[] colours = vivid(art);
            party.setColours(colours);
            wave.setColour(bright(colours.length > 1 ? colours[1] : colours[0]));
        }

        String key = name + "|" + who;
        if (key.equals(songKey)) return;
        songKey = key;
        lyrics = Lyrics.NONE;
        lyricsLoading = true;
        showLyricsLines();
        String album = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        new Thread(() -> {
            // The library is often busy: keep "Finding the lyrics" up and ask again every 20 seconds, for a few minutes.
            Lyrics found = null;
            for (int attempt = 0; attempt < 10 && key.equals(songKey); attempt++) {
                found = Lyrics.find(this, name, who, album, duration);
                if (found != null) break;
                try {
                    Thread.sleep(20_000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (found == null) found = Lyrics.NONE;
            Lyrics result = found;
            main.post(() -> {
                if (!key.equals(songKey)) return;   // the song changed in the meantime
                lyrics = result;
                lyricsLoading = false;
                showLyricsLines();
            });
        }, "lyrics").start();
    }

    /**
     * The cover's main colour, as Spotify uses it: the hue that covers most of the cover (colourful pixels count
     * more, so a white border does not win), averaged, then toned down so white text reads on it.
     */
    static int primary(Bitmap art) {
        Bitmap tiny = Bitmap.createScaledBitmap(art, 16, 16, true);
        int[] px = new int[256];
        tiny.getPixels(px, 0, 16, 0, 0, 16, 16);
        float[] weight = new float[18], r = new float[18], g = new float[18], b = new float[18];
        float[] hsv = new float[3];
        for (int c : px) {
            Color.colorToHSV(c, hsv);
            int bucket = Math.min(17, (int) (hsv[0] / 20f));
            float w = 0.05f + hsv[1] * hsv[2];
            weight[bucket] += w;
            r[bucket] += Color.red(c) * w;
            g[bucket] += Color.green(c) * w;
            b[bucket] += Color.blue(c) * w;
        }
        int best = 0;
        for (int i = 1; i < 18; i++) if (weight[i] > weight[best]) best = i;
        float w = weight[best];
        Color.colorToHSV(Color.rgb((int) (r[best] / w), (int) (g[best] / w), (int) (b[best] / w)), hsv);
        hsv[1] = Math.min(0.8f, hsv[1] * 1.1f);
        hsv[2] = Math.min(hsv[2], 0.45f);   // deep enough for white text
        return Color.HSVToColor(hsv);
    }

    // ---- party mode -------------------------------------------------------------------------------

    /** Party mode: the disco lights fade in over the background and start listening to the music. */
    private void setPartyOn(boolean on) {
        // The cover swells out past its place on the beat: nothing around it may cut it off.
        for (android.view.ViewParent v = cover.getParent(); v instanceof android.view.ViewGroup; v = v.getParent()) {
            ((android.view.ViewGroup) v).setClipChildren(false);
            ((android.view.ViewGroup) v).setClipToPadding(false);
        }
        if (on && lyricsOn) setLyricsShown(false);   // party mode is the cover alone
        partyOn = on;
        styleButtons();   // the ball's ring shows party mode is on
        if (on) {
            party.setVisibility(View.VISIBLE);
            party.start();
            party.animate().alpha(1f).setDuration(600).setInterpolator(EASE).withEndAction(null).start();
        } else {
            party.animate().alpha(0f).setDuration(500).setInterpolator(EASE).withEndAction(() -> {
                party.stop();
                party.setVisibility(View.INVISIBLE);
            }).start();
        }
    }

    /** Up to three of the cover's most colourful, different-looking colours (from a 12 px copy). */
    private static int[] vivid(Bitmap art) {
        Bitmap tiny = Bitmap.createScaledBitmap(art, 12, 12, true);
        int[] px = new int[144];
        tiny.getPixels(px, 0, 12, 0, 0, 12, 12);
        Integer[] order = new Integer[144];
        float[][] hsv = new float[144][3];
        for (int i = 0; i < 144; i++) {
            Color.colorToHSV(px[i], hsv[i]);
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Float.compare(hsv[b][1] * hsv[b][2], hsv[a][1] * hsv[a][2]));
        List<Integer> out = new ArrayList<>();
        List<Float> hues = new ArrayList<>();
        for (Integer i : order) {
            if (out.size() == 3 || hsv[i][1] * hsv[i][2] < 0.15f) break;
            boolean far = true;
            for (float h : hues) {
                float d = Math.abs(h - hsv[i][0]) % 360f;
                if (Math.min(d, 360f - d) < 40f) far = false;
            }
            if (!far) continue;
            hues.add(hsv[i][0]);
            out.add(px[i]);
        }
        if (out.isEmpty()) return new int[]{0xFFFF2E8A, 0xFF2A6BFF, 0xFF1FD1C1};   // a grey cover: classic disco
        int[] c = new int[out.size()];
        for (int i = 0; i < c.length; i++) c[i] = out.get(i);
        return c;
    }

    private Visualizer visualizer;
    /** Each band's loudest recent level, slowly forgotten, so quiet and loud songs both fill the range. */
    private final float[] peak = {1f, 1f, 1f};

    /**
     * Android's Visualizer on the TV's whole output (session 0), while the music screen is up: the loudness by
     * frequency of what is already playing, about 20 times a second, as bass, mids and treble for the moving background and party mode's lights. It needs
     * RECORD_AUDIO (granted over adb); no microphone is used. Without it the lights still dance, on their own.
     */
    private void listen() {
        if (visualizer != null) return;
        try {
            visualizer = new Visualizer(0);
            visualizer.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            visualizer.setCaptureSize(Math.min(range[1], 1024));
            visualizer.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            int rate = Math.min(Visualizer.getMaxCaptureRate(), 20_000);   // millihertz: about 20 a second
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] wave, int samplingRate) {
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                    bands(fft, samplingRate / 1000f);
                }
            }, rate, false, true);
            visualizer.setEnabled(true);
        } catch (RuntimeException e) {
            stopListening();
        }
    }

    private void stopListening() {
        if (visualizer == null) return;
        try {
            visualizer.setEnabled(false);
            visualizer.release();
        } catch (RuntimeException ignored) {
            // already gone
        }
        visualizer = null;
    }

    /** FFT bytes (re, im pairs) into bass (to 250 Hz), mids (to 2 kHz) and treble (to 8 kHz), each 0 to 1. */
    private void bands(byte[] fft, float hz) {
        int bins = fft.length / 2;
        float binHz = hz / 2f / bins;
        float[] sum = new float[3];
        int[] count = new int[3];
        for (int k = 1; k < bins; k++) {
            float f = k * binHz;
            int band = f < 250 ? 0 : f < 2000 ? 1 : f < 8000 ? 2 : -1;
            if (band < 0) break;
            sum[band] += (float) Math.hypot(fft[2 * k], fft[2 * k + 1]);
            count[band]++;
        }
        float[] level = new float[3];
        for (int b = 0; b < 3; b++) {
            float avg = count[b] > 0 ? sum[b] / count[b] : 0f;
            peak[b] = Math.max(avg, peak[b] * 0.995f);
            level[b] = peak[b] > 0.5f ? Math.min(1f, avg / peak[b]) : 0f;
        }
        party.setLevels(level[0], level[1], level[2]);
        breathe(level[0]);
    }

    /** The normal screen stays still (party mode pumps the cover itself, every frame). */
    private void breathe(float bass) {
        if (!partyOn && cover.getScaleX() != 1f) {   // party mode off: the cover settles back
            cover.setScaleX(1f);
            cover.setScaleY(1f);
        }
    }

    /** A cover colour made bright enough to read on the dark background. */
    private static int bright(int colour) {
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        hsv[1] = Math.max(0.45f, hsv[1]);
        hsv[2] = Math.max(0.9f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    // ---- lyrics -----------------------------------------------------------------------------------

    private void showLyricsLines() {
        lyricsColumn.removeAllViews();
        lineViews.clear();
        currentLine = -1;
        if (lyrics.lines.isEmpty()) {
            noLyrics.setText(lyricsLoading ? "Finding the lyrics\u2026"
                    : NO_LYRICS[new Random().nextInt(NO_LYRICS.length)]);
            noLyrics.setVisibility(View.VISIBLE);
            lyricsScroll.setVisibility(View.GONE);
            return;
        }
        noLyrics.setVisibility(View.GONE);
        lyricsScroll.setVisibility(View.VISIBLE);
        for (Lyrics.Line line : lyrics.lines) {
            TextView t = text(28f, R.font.outfit_medium, 0x61FFFFFF, false);
            t.setTypeface(lyricFace());   // bold, like Spotify's lyrics
            t.setShadowLayer(0, 0, 0, 0);   // flat: a shadow muddies the dimmed lines
            t.setText(line.text.isEmpty() ? "\u266A" : line.text);   // a break in the singing: a note
            t.setLineSpacing(0, 1.08f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = px(18);
            lyricsColumn.addView(t, lp);
            lineViews.add(t);
        }
        lyricsScroll.scrollTo(0, 0);
    }

    /**
     * The smooth switch: the cover glides to the left while the lyrics slide in from the right and fade up, on one
     * ease-in-out curve; hiding plays it back the other way.
     */
    private void setLyricsShown(boolean on) {
        if (on && partyOn) setPartyOn(false);   // lyrics and party mode never show together
        lyricsOn = on;
        float sideX = on ? -(root.getWidth() / 2f - px(75) - side.getWidth() / 2f) : 0f;   // the album glides left
        side.animate().translationX(sideX).setDuration(650).setInterpolator(EASE).start();
        lyricsPane.animate().alpha(on ? 1f : 0f).translationX(on ? 0f : px(40))
                .setStartDelay(on ? 120 : 0).setDuration(on ? 600 : 400).setInterpolator(EASE).start();
        lyricsButton.setText(on ? "Hide lyrics" : "Lyrics");
        // With the lyrics up the words carry the song along: the timeline steps out of the way.
        for (View v : new View[]{wave, times}) {
            v.animate().alpha(on ? 0f : 1f).setDuration(on ? 300 : 500).setInterpolator(EASE).start();
        }
        currentLine = -1;   // place the lines afresh
        updateProgress();
    }

    /** Where the song is now: the progress bar, the times, and the current lyric line. */
    private void updateProgress() {
        if (playing == null) return;
        PlaybackState s = playing.getPlaybackState();
        MediaMetadata m = playing.getMetadata();
        if (s == null || m == null) return;
        long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        pos = Math.max(0, duration > 0 ? Math.min(pos, duration) : pos);
        wave.setProgress(duration > 0 ? pos / (float) duration : 0f);
        wave.setMoving(s.getState() == PlaybackState.STATE_PLAYING);
        elapsed.setText(clock(pos));
        remaining.setText(duration > 0 ? "-" + clock(duration - pos) : "");
        if (!lyricsOn || lineViews.isEmpty()) return;
        int now;
        if (lyrics.timed) {
            now = -1;
            for (int i = 0; i < lyrics.lines.size() && lyrics.lines.get(i).at <= pos + 300; i++) now = i;
        } else {
            // Plain lyrics: follow the song's progress through the lines.
            now = duration > 0 ? (int) (pos / (float) duration * lineViews.size()) : 0;
        }
        boolean before = lyrics.timed && now < 0;   // the intro, before the first line is sung: nothing lit yet
        now = Math.max(0, Math.min(now, lineViews.size() - 1));
        if (now == currentLine && !before) return;
        currentLine = before ? -1 : now;
        for (int i = 0; i < lineViews.size(); i++) {
            int colour = !lyrics.timed ? 0xB3FFFFFF
                    : !before && i == now ? Color.WHITE : !before && i < now ? 0x38FFFFFF : 0x61FFFFFF;
            lineViews.get(i).setTextColor(colour);
        }
        // Keep the current line about a third of the way down the screen, gliding there.
        TextView line = lineViews.get(now);
        int target = Math.max(0, Math.round(line.getTop() - lyricsPane.getHeight() * 0.36f));
        android.animation.ObjectAnimator glide = android.animation.ObjectAnimator.ofInt(lyricsScroll, "scrollY", target);
        glide.setDuration(600);
        glide.setInterpolator(EASE);
        glide.start();
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
