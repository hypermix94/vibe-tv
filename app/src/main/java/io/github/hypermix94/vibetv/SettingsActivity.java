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
        title.setText("Settings");
        title.setLetterSpacing(-0.03f);
        column.addView(title);
        TextView sub = Cards.text(this, s, 26, R.font.outfit_regular, 0x99FFFFFF);
        sub.setText("vibe-tv · Left and Right change a setting, OK types");
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = d(12);
        subLp.bottomMargin = d(36);
        column.addView(sub, subLp);
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
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(d(1200), -2, Gravity.TOP | Gravity.START);
        lp.setMargins(d(112), d(96), 0, 0);
        root.addView(column, lp);
        column.setAlpha(0f);
        column.setTranslationY(d(24));
        column.animate().alpha(1f).translationY(0).setDuration(600).setInterpolator(EASE_OUT).start();
        return root;
    }

    private void paint(boolean animate) {
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
        String value;
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

    /** The weather city: typed, then looked up for its coordinates (Open-Meteo, no key). */
    private class CityRow extends TextRow {
        CityRow() {
            super("Weather city", "set_place", Prefs.weatherPlace(SettingsActivity.this), "No weather");
        }

        @Override
        void save(String words) {
            String city = words.trim();
            if (city.isEmpty()) {
                Prefs.edit(SettingsActivity.this).putString("set_place", "").putString("set_lat", "").putString("set_lon", "").apply();
                return;
            }
            field.setText("Looking up…");
            new Thread(() -> {
                String[] found = geocode(city);
                runOnUiThread(() -> {
                    if (found == null) {
                        field.setText(Prefs.weatherPlace(SettingsActivity.this));
                        toast("Could not find " + city);
                        return;
                    }
                    Prefs.edit(SettingsActivity.this).putString("set_place", found[0])
                            .putString("set_lat", found[1]).putString("set_lon", found[2]).apply();
                    getSharedPreferences("header_weather", MODE_PRIVATE).edit().clear().apply();   // fetch afresh
                    field.setText(found[0]);
                });
            }, "geocode").start();
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
            Prefs.edit(SettingsActivity.this).putBoolean(key, on).apply();
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
