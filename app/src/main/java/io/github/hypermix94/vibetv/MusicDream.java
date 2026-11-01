package io.github.hypermix94.vibetv;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/**
 * The screensaver. If Spotify, YouTube Music or YouTube is playing, it shows MusicScreen (the cover, lyrics and party
 * mode) inside itself. Otherwise it shows Scenery's pictures of nature, landscapes, the sea and space, a new one every
 * 90 seconds with a slow crossfade and drift, the time at the bottom left and the weather at the bottom right; and if
 * music starts meanwhile, it switches to the music screen.
 */
public class MusicDream extends DreamService {
    private static final long CHANGE_MS = 90_000L;
    private static final long MUSIC_CHECK_MS = 5_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ImageView[] layers;
    private int front;
    private TextView time, date, temp, sky, credit;
    private ImageView skyIcon;
    private Scenery scenery;
    private HeaderWeather weather;
    private boolean running, dreaming;
    private MusicScreen music;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        if (openMusic()) return;
        showScenery();
    }

    private void showScenery() {
        setInteractive(false);   // any key ends the screensaver
        setContentView(buildScreen());
        scenery = new Scenery(this);
        weather = new HeaderWeather(this, getString(R.string.weather_latitude).trim(),
                getString(R.string.weather_longitude).trim());
    }

    /**
     * Shows the music screen inside this screensaver when music plays. It lives here rather than in an activity:
     * Android keeps a screensaver above every activity, and ending the screensaver after the idle time puts the TV to
     * sleep. With the TV's sleep timer off it runs as long as the music does.
     */
    private boolean openMusic() {
        if (MusicScreen.playingMusic(this) == null) return false;
        MusicScreen screen = new MusicScreen(this, new MusicScreen.Host() {
            @Override
            public android.view.Window window() {
                return getWindow();
            }

            @Override
            public void close() {
                finish();   // a key closed it: that key counts as activity, so the TV stays on
            }
        });
        View view = screen.create();
        if (view == null) return false;
        if (music != null) music.destroy();
        music = screen;
        running = false;
        main.removeCallbacksAndMessages(null);
        layers = null;
        setInteractive(true);   // Left, Right and OK work the music screen; other keys close it
        setContentView(view);
        if (dreaming) music.resume();
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (music != null) return music.dispatchKeyEvent(e);
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onDetachedFromWindow() {
        if (music != null) music.destroy();
        music = null;
        super.onDetachedFromWindow();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        if (music != null) {
            music.resume();
            return;
        }
        if (layers == null) return;
        running = true;
        tick.run();
        nextPicture();
        main.postDelayed(watchMusic, MUSIC_CHECK_MS);
        showWeather(weather.cached());
        weather.refresh(this::showWeather);
    }

    @Override
    public void onDreamingStopped() {
        dreaming = false;
        if (music != null) music.pause();
        running = false;
        main.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            boolean h24 = DateFormat.is24HourFormat(MusicDream.this);
            String hm = DateFormat.format(h24 ? "HH:mm" : "h:mm", now).toString();
            android.text.SpannableString t = new android.text.SpannableString(
                    h24 ? hm : hm + " " + DateFormat.format("a", now).toString().toUpperCase(java.util.Locale.ROOT));
            if (!h24) t.setSpan(new android.text.style.RelativeSizeSpan(0.4f), hm.length(), t.length(), 0);
            time.setText(t);
            date.setText(DateFormat.format("EEEE, d MMMM", now));
            long ms = System.currentTimeMillis();
            main.postDelayed(this, 60_000L - ms % 60_000L + 50);
        }
    };

    private final Runnable watchMusic = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (!openMusic()) main.postDelayed(this, MUSIC_CHECK_MS);
        }
    };

    private void nextPicture() {
        new Thread(() -> {
            Scenery.Picture p = scenery.next();
            main.post(() -> {
                if (!running) return;
                if (p != null) show(p);
                main.postDelayed(this::nextPicture, p != null ? CHANGE_MS : 30_000L);
                if (weather != null) weather.refresh(this::showWeather);
            });
        }, "scenery").start();
    }

    /** Crossfades to the picture, which drifts slowly while it is up. */
    private void show(Scenery.Picture p) {
        ImageView old = layers[front];
        front = 1 - front;
        ImageView next = layers[front];
        next.animate().cancel();
        next.setImageBitmap(p.bitmap);
        next.setScaleX(1.08f);
        next.setScaleY(1.08f);
        next.setTranslationX(dp(14));
        next.setAlpha(0f);
        next.bringToFront();
        next.animate().alpha(1f).setDuration(2500).withEndAction(() -> old.setImageDrawable(null)).start();
        next.animate().scaleX(1f).scaleY(1f).translationX(-dp(14)).setDuration(CHANGE_MS + 4000)
                .setInterpolator(new android.view.animation.LinearInterpolator()).start();
        // The shade and text stay above the pictures.
        ((View) time.getParent().getParent()).bringToFront();
        credit.setText(p.credit);
    }

    private void showWeather(HeaderWeather.Reading r) {
        if (r == null || temp == null) return;
        temp.setText(r.temp + "°");
        String place = getString(R.string.weather_place).trim();
        sky.setText(place.isEmpty() ? r.words() : r.words() + " · " + place);
        skyIcon.setImageResource(r.iconRes());
        ((View) temp.getParent().getParent()).setVisibility(View.VISIBLE);
    }

    private View buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        layers = new ImageView[2];
        for (int i = 0; i < 2; i++) {
            layers[i] = new ImageView(this);
            layers[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(layers[i], new FrameLayout.LayoutParams(-1, -1));
        }

        FrameLayout over = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x99000000, 0x33000000, 0x00000000}));
        over.addView(shade, new FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM));

        // Bottom left: the time, the date under it.
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        time = text(64, R.font.outfit_light);
        date = text(18, R.font.outfit_regular);
        date.setAlpha(0.85f);
        clock.addView(time);
        clock.addView(date);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        clockLp.setMargins(dp(56), 0, 0, dp(44));
        over.addView(clock, clockLp);

        // Bottom right: the weather, hidden until there is a reading.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.setVisibility(View.GONE);
        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        skyIcon = new ImageView(this);
        now.addView(skyIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        temp = text(44, R.font.outfit_light);
        LinearLayout.LayoutParams tempLp = new LinearLayout.LayoutParams(-2, -2);
        tempLp.leftMargin = dp(10);
        now.addView(temp, tempLp);
        sky = text(18, R.font.outfit_regular);
        sky.setAlpha(0.85f);
        right.addView(now);
        right.addView(sky);
        FrameLayout.LayoutParams rightLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        rightLp.setMargins(0, 0, dp(56), dp(44));
        over.addView(right, rightLp);

        // Bottom middle, small: who took the photo.
        credit = text(11, R.font.outfit_regular);
        credit.setAlpha(0.6f);
        credit.setMaxLines(1);
        credit.setEllipsize(android.text.TextUtils.TruncateAt.END);
        credit.setMaxWidth(dp(420));
        FrameLayout.LayoutParams creditLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        creditLp.bottomMargin = dp(20);
        over.addView(credit, creditLp);

        root.addView(over, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private TextView text(float sp, int font) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        Typeface face = getResources().getFont(font);
        t.setTypeface(face);
        t.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
