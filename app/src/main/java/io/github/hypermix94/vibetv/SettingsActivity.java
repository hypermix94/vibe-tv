package io.github.hypermix94.vibetv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * vibe-tv's settings: your name, the weather city, the screensaver delay, the dock and the Apps grid, the clock's
 * seconds, the greeting character and the All apps preview panel. Up and Down pick a setting; Left and Right change a
 * choice or switch one on and off; OK types into a text setting with the on-screen keyboard. Changes apply at once
 * and the home screen rebuilds with them.
 */
public class SettingsActivity extends Activity {
    private static final Interpolator EASE_OUT = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private static final int[] SCREENSAVER = {1, 2, 5, 10, 30};

    /** One setting: its row, its value's words, and how it changes. */
    private abstract class Row {
        final String label;
        LinearLayout view;
        View valueView;

        Row(String label) {
            this.label = label;
        }

        abstract View value();

        void left() {
        }

        void right() {
        }

        void ok() {
            right();
        }
    }

    private float s;
    private FrameLayout root;
    private final List<Row> rows = new ArrayList<>();
    private int focus;
    private EditText editing;
    private Row editingRow;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = getResources().getDisplayMetrics().widthPixels / 1920f;
        rows.add(new TextRow("Your name", "set_name", Prefs.name(this), "Shown in the greeting"));
        rows.add(new CityRow());
        rows.add(new ChoiceRow("Screensaver after", SCREENSAVER, Prefs.screensaverMinutes(this), " min") {
            @Override
            void apply(int v) {
                if (!Prefs.setScreensaverMinutes(SettingsActivity.this, v)) toast("The TV did not allow this change");
            }
        });
        rows.add(new SwitchRow("Music screen", "set_music_screen", Prefs.musicScreen(this)) {
            @Override
            void changed() {
                if (!Prefs.applyScreensaver(SettingsActivity.this)) toast("The TV did not allow this change");
            }
        });
        rows.add(new SwitchRow("Photo screensaver", "set_photos", Prefs.photos(this)) {
            @Override
            void changed() {
                if (!Prefs.applyScreensaver(SettingsActivity.this)) toast("The TV did not allow this change");
            }
        });
        rows.add(new ChoiceRow("Dock holds up to", new int[]{4, 5, 6, 7, 8, 9, 10}, Prefs.dockMax(SettingsActivity.this), " apps") {
            @Override
            void apply(int v) {
                Prefs.edit(SettingsActivity.this).putInt("set_dock_max", v).apply();
            }
        });
        rows.add(new ChoiceRow("Icons per row in Apps", new int[]{6, 7, 8, 9, 10}, Prefs.columns(SettingsActivity.this), "") {
            @Override
            void apply(int v) {
                Prefs.edit(SettingsActivity.this).putInt("set_columns", v).apply();
            }
        });
        rows.add(new SwitchRow("Seconds on the clock", "set_seconds", Prefs.seconds(this)));
        rows.add(new SwitchRow("Animated greeting character", "set_character", Prefs.greetingCharacter(this)));
        rows.add(new SwitchRow("Preview panel in All apps", "set_preview", Prefs.previewPanel(this)));
        setContentView(build());
        paint(false);
    }

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E0F12);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        TextView title = Cards.text(this, s, 64, R.font.outfit_medium, Color.WHITE);
        title.setText("Home settings");
        title.setLetterSpacing(-0.03f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.bottomMargin = d(40);
        column.addView(title, titleLp);
        for (Row r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(d(32), 0, d(32), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x00000000);
            bg.setCornerRadius(d(18));
            row.setBackground(bg);
            TextView label = Cards.text(this, s, 30, R.font.outfit_regular, Color.WHITE);
            label.setText(r.label);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            r.valueView = r.value();
            row.addView(r.valueView, new LinearLayout.LayoutParams(-2, -2));
            row.setContentDescription(r.label);
            column.addView(row, new LinearLayout.LayoutParams(-1, d(86)));
            r.view = row;
        }
        // More rows than fit on screen: they scroll, keeping the focused one in view.
        scroll = new android.widget.ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(d(112), d(96), 0, d(96));
        scroll.addView(column, new FrameLayout.LayoutParams(d(1200), -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        column.setAlpha(0f);
        column.setTranslationY(d(24));
        column.animate().alpha(1f).translationY(0).setDuration(600).setInterpolator(EASE_OUT).start();
        return root;
    }

    private android.widget.ScrollView scroll;

    private void paint(boolean animate) {
        View focused = rows.get(focus).view;
        scroll.post(() -> {   // the focused row in the middle of the screen, where there is room to scroll
            int y = scroll.getPaddingTop() + focused.getTop() + focused.getHeight() / 2 - scroll.getHeight() / 2;
            y = Math.max(0, y);
            if (animate) scroll.smoothScrollTo(0, y);
            else scroll.scrollTo(0, y);
        });
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean on = i == focus;
            ((GradientDrawable) r.view.getBackground()).setColor(on ? Cards.PLATE : 0x00000000);
            r.view.animate().scaleX(on ? 1.02f : 1f).scaleY(on ? 1.02f : 1f).setDuration(animate ? 300 : 0).start();
            if (on && animate) r.view.announceForAccessibility(r.label);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (editing != null) {
            if (k == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) finishEditing();
            return k == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(e);
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        Row r = rows.get(focus);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                focus = Math.max(0, focus - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focus = Math.min(rows.size() - 1, focus + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                r.left();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                r.right();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                r.ok();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
        paint(true);
        return true;
    }

    // ---- kinds of setting ------------------------------------------------------------------------

    /** Typed words: OK brings up the keyboard, Done or Back saves. */
    private class TextRow extends Row {
        final String key, hint;
        String value;   // the saved words
        EditText field;

        TextRow(String label, String key, String value, String hint) {
            super(label);
            this.key = key;
            this.value = value;
            this.hint = hint;
        }

        @Override
        View value() {
            field = new EditText(SettingsActivity.this);
            field.setBackground(null);
            field.setSingleLine(true);
            field.setTextColor(0xD9FFFFFF);
            field.setHintTextColor(0x66FFFFFF);
            field.setHint(hint);
            field.setText(value);
            field.setTextSize(TypedValue.COMPLEX_UNIT_PX, d(28));
            field.setTypeface(getResources().getFont(R.font.outfit_regular));
            field.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            field.setMinWidth(d(360));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            field.setFocusable(false);
            field.setOnEditorActionListener((v, a, ev) -> {
                finishEditing();
                return true;
            });
            return field;
        }

        @Override
        void ok() {
            editing = field;
            editingRow = this;
            field.setFocusable(true);
            field.setFocusableInTouchMode(true);
            field.requestFocus();
            field.setSelection(field.length());
            getSystemService(InputMethodManager.class).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }

        void save(String words) {
            Prefs.edit(SettingsActivity.this).putString(key, words.trim()).apply();
        }
    }

    /** Cities to pick from without typing: name, latitude, longitude. */
    private static final String[][] CITIES = {
            {"Bengaluru", "12.9716", "77.5946"}, {"Chennai", "13.0827", "80.2707"}, {"Mumbai", "19.0760", "72.8777"},
            {"Delhi", "28.6139", "77.2090"}, {"Hyderabad", "17.3850", "78.4867"}, {"Kolkata", "22.5726", "88.3639"},
            {"Pune", "18.5204", "73.8567"}, {"Kochi", "9.9312", "76.2673"}, {"Coimbatore", "11.0168", "76.9558"},
            {"Dubai", "25.2048", "55.2708"}, {"Singapore", "1.3521", "103.8198"}, {"London", "51.5072", "-0.1276"},
            {"New York", "40.7128", "-74.0060"}, {"Toronto", "43.6532", "-79.3832"}, {"Los Angeles", "34.0522", "-118.2437"},
            {"Sydney", "-33.8688", "151.2093"}, {"Tokyo", "35.6762", "139.6503"}, {"Paris", "48.8566", "2.3522"},
            {"Berlin", "52.5200", "13.4050"},
    };

    /**
     * The weather city, picked with Left and Right: Automatic (worked out from the internet connection), a list of
     * cities, or "Type a city" for anywhere else (OK brings up the keyboard; the name is looked up on Open-Meteo).
     */
    private class CityRow extends TextRow {
        /** -1 Automatic, 0.. a listed city, CITIES.length "Type a city". */
        int pick;

        CityRow() {
            super("Weather city", "set_place", Prefs.weatherPlace(SettingsActivity.this), "No weather");
            boolean auto = Apps.prefs(SettingsActivity.this).getBoolean("set_place_auto", false);
            pick = CITIES.length;
            if (auto) pick = -1;
            for (int i = 0; i < CITIES.length; i++) if (CITIES[i][0].equalsIgnoreCase(value)) pick = i;
        }

        @Override
        View value() {
            View v = super.value();
            field.setText(words());
            return v;
        }

        private String words() {
            String name = pick == -1 ? "Automatic \u00b7 " + Prefs.weatherPlace(SettingsActivity.this)
                    : pick < CITIES.length ? CITIES[pick][0] : (value.isEmpty() ? "Type a city\u2026" : value);
            return "\u2039  " + name + "  \u203a";
        }

        @Override
        void left() {
            step(-1);
        }

        @Override
        void right() {
            step(1);
        }

        private void step(int by) {
            int next = Math.max(-1, Math.min(CITIES.length, pick + by));
            if (next == pick) return;
            pick = next;
            if (pick == -1) {
                field.setText("\u2039  Finding where you are\u2026  \u203a");
                new Thread(() -> {
                    String[] here = whereAmI();
                    runOnUiThread(() -> {
                        if (here == null) {
                            toast("Could not tell where this TV is");
                        } else {
                            store(here, true);
                        }
                        field.setText(words());
                    });
                }, "locate").start();
                return;
            }
            if (pick < CITIES.length) store(CITIES[pick], false);
            field.setText(words());
            field.setTranslationX(d(by * 10));
            field.animate().translationX(0).setDuration(250).setInterpolator(EASE_OUT).start();
        }

        private void store(String[] place, boolean auto) {
            Prefs.edit(SettingsActivity.this).putString("set_place", place[0]).putString("set_lat", place[1])
                    .putString("set_lon", place[2]).putBoolean("set_place_auto", auto).apply();
            getSharedPreferences("header_weather", MODE_PRIVATE).edit().clear().apply();   // fetch afresh
            value = place[0];
        }

        /** OK: only "Type a city" types; on any other choice OK does nothing. */
        @Override
        void ok() {
            if (pick != CITIES.length) return;
            field.setText(value);
            super.ok();
        }

        @Override
        void save(String words) {
            String city = words.trim();
            if (city.isEmpty()) {
                Prefs.edit(SettingsActivity.this).putString("set_place", "").putString("set_lat", "").putString("set_lon", "")
                        .putBoolean("set_place_auto", false).apply();
                value = "";
                field.setText(words());
                return;
            }
            field.setText("Looking up\u2026");
            new Thread(() -> {
                String[] found = geocode(city);
                runOnUiThread(() -> {
                    if (found == null) toast("Could not find " + city);
                    else store(found, false);
                    field.setText(words());
                });
            }, "geocode").start();
        }
    }

    /** The TV's city from its internet connection (ipwho.is, no key), as {place, latitude, longitude}; or null. */
    private static String[] whereAmI() {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL("https://ipwho.is/?fields=success,city,latitude,longitude").openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(10000);
            if (http.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            JSONObject r = new JSONObject(out.toString("UTF-8"));
            if (!r.optBoolean("success") || r.optString("city").isEmpty()) return null;
            return new String[]{r.optString("city"), String.valueOf(r.optDouble("latitude")), String.valueOf(r.optDouble("longitude"))};
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    /** One of a few values: Left and Right step through them. */
    private abstract class ChoiceRow extends Row {
        final int[] choices;
        final String unit;
        int index;
        TextView text;

        ChoiceRow(String label, int[] choices, int current, String unit) {
            super(label);
            this.choices = choices;
            this.unit = unit;
            index = 0;
            for (int i = 0; i < choices.length; i++) if (choices[i] <= current) index = i;
        }

        abstract void apply(int v);

        @Override
        View value() {
            text = Cards.text(SettingsActivity.this, s, 28, R.font.outfit_regular, 0xD9FFFFFF);
            show();
            return text;
        }

        void show() {
            text.setText("‹  " + choices[index] + unit + "  ›");
        }

        @Override
        void left() {
            step(-1);
        }

        @Override
        void right() {
            step(1);
        }

        private void step(int by) {
            int next = Math.max(0, Math.min(choices.length - 1, index + by));
            if (next == index) return;
            index = next;
            show();
            apply(choices[index]);
            text.setTranslationX(d(by * 10));
            text.animate().translationX(0).setDuration(250).setInterpolator(EASE_OUT).start();
        }
    }

    /** On or off: a small switch; Left, Right or OK flips it. */
    private class SwitchRow extends Row {
        final String key;
        boolean on;
        FrameLayout track;
        View knob;

        SwitchRow(String label, String key, boolean on) {
            super(label);
            this.key = key;
            this.on = on;
        }

        @Override
        View value() {
            track = new FrameLayout(SettingsActivity.this);
            GradientDrawable t = new GradientDrawable();
            t.setCornerRadius(d(20));
            track.setBackground(t);
            knob = new View(SettingsActivity.this);
            GradientDrawable kd = new GradientDrawable();
            kd.setShape(GradientDrawable.OVAL);
            kd.setColor(Color.WHITE);
            knob.setBackground(kd);
            track.addView(knob, new FrameLayout.LayoutParams(d(32), d(32), Gravity.CENTER_VERTICAL));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(72), d(40));
            track.setLayoutParams(lp);
            track.setPadding(d(4), 0, d(4), 0);
            show(false);
            FrameLayout wrap = new FrameLayout(SettingsActivity.this);
            wrap.addView(track, new FrameLayout.LayoutParams(d(72), d(40), Gravity.CENTER));
            return wrap;
        }

        void show(boolean animate) {
            ((GradientDrawable) track.getBackground()).setColor(on ? 0xFF1ED760 : 0x40FFFFFF);
            knob.animate().translationX(on ? d(32) : 0).setDuration(animate ? 250 : 0).setInterpolator(EASE_OUT).start();
        }

        @Override
        void left() {
            set(false);
        }

        @Override
        void right() {
            set(true);
        }

        @Override
        void ok() {
            set(!on);
        }

        private void set(boolean v) {
            if (v == on) return;
            on = v;
            show(true);
            Prefs.edit(SettingsActivity.this).putBoolean(key, on).commit();
            changed();
        }

        /** After the switch is saved. */
        void changed() {
        }
    }

    private void finishEditing() {
        EditText f = editing;
        Row r = editingRow;
        editing = null;
        editingRow = null;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(f.getWindowToken(), 0);
        f.clearFocus();
        f.setFocusable(false);
        if (r instanceof TextRow) ((TextRow) r).save(f.getText().toString());
    }

    /** A city name to {place, latitude, longitude}, or null. */
    private static String[] geocode(String city) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name="
                    + URLEncoder.encode(city, "UTF-8")).openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(10000);
            if (http.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            JSONArray results = new JSONObject(out.toString("UTF-8")).optJSONArray("results");
            if (results == null || results.length() == 0) return null;
            JSONObject r = results.getJSONObject(0);
            return new String[]{r.optString("name"), String.valueOf(r.optDouble("latitude")), String.valueOf(r.optDouble("longitude"))};
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private void toast(String words) {
        android.widget.Toast.makeText(this, words, android.widget.Toast.LENGTH_LONG).show();
    }

    private int d(float designPx) {
        return Cards.d(s, designPx);
    }
}
