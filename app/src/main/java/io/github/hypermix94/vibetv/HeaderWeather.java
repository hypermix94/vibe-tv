package io.github.hypermix94.vibetv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Consumer;

/**
 * The screensaver's weather: the current temperature and conditions and today's high and low for one place
 * (strings.xml weather_place, weather_latitude, weather_longitude), from Open-Meteo (no account or key). Asked at
 * most every 30 minutes while the screensaver shows; the last reading is kept, so it shows at once.
 */
final class HeaderWeather {
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final long RETRY_MS = 5 * 60 * 1000L;
    private static final String PREFS = "header_weather";

    /** One answer from Open-Meteo, in whole degrees Celsius. */
    static final class Reading {
        final int temp, code, high, low;
        final boolean day;

        Reading(int temp, int code, boolean day, int high, int low) {
            this.temp = temp;
            this.code = code;
            this.day = day;
            this.high = high;
            this.low = low;
        }

        String words() {
            if (code == 0) return day ? "Sunny" : "Clear";
            if (code <= 2) return "Partly cloudy";
            if (code == 3) return "Cloudy";
            if (code <= 48) return "Foggy";
            if (code <= 57) return "Drizzle";
            if (code <= 67) return "Rain";
            if (code <= 77) return "Snow";
            if (code <= 82) return "Showers";
            if (code <= 86) return "Snow showers";
            return "Thunderstorm";
        }

        /** A colour icon for the conditions (WMO weather codes, as Open-Meteo reports them). */
        int iconRes() {
            if (code == 0) return day ? R.drawable.ic_weather_sun : R.drawable.ic_weather_moon;
            if (code <= 2) return day ? R.drawable.ic_weather_cloud_sun : R.drawable.ic_weather_cloud_moon;
            if (code == 3) return R.drawable.ic_weather_cloud;
            if (code <= 48) return R.drawable.ic_weather_fog;
            if (code <= 67) return R.drawable.ic_weather_rain;
            if (code <= 77) return R.drawable.ic_weather_snow;
            if (code <= 82) return R.drawable.ic_weather_rain;
            if (code <= 86) return R.drawable.ic_weather_snow;
            return R.drawable.ic_weather_storm;
        }

        String encode() {
            return temp + "," + code + "," + (day ? 1 : 0) + "," + high + "," + low;
        }

        static Reading decode(String s) {
            try {
                String[] p = s.split(",");
                return new Reading(Integer.parseInt(p[0]), Integer.parseInt(p[1]), "1".equals(p[2]),
                        Integer.parseInt(p[3]), Integer.parseInt(p[4]));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private final Context context;
    private final String latitude, longitude;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;

    HeaderWeather(Context context, String latitude, String longitude) {
        this.context = context.getApplicationContext();
        this.latitude = latitude;
        this.longitude = longitude;
    }

    boolean enabled() {
        return !latitude.isEmpty() && !longitude.isEmpty();
    }

    /** The last reading, or null before the first answer. */
    Reading cached() {
        return Reading.decode(prefs().getString("reading", ""));
    }

    /** Fetches a new reading when the last one is old enough, and hands it to {@code show} on the main thread. */
    void refresh(Consumer<Reading> show) {
        if (!enabled() || busy) return;
        SharedPreferences p = prefs();
        long age = System.currentTimeMillis() - p.getLong("at", 0L);
        long wait = p.getBoolean("ok", false) ? REFRESH_MS : RETRY_MS;
        if (age >= 0 && age < wait && cached() != null) return;   // no reading yet: ask now
        busy = true;
        new Thread(() -> {
            Reading r = fetch();
            main.post(() -> {
                busy = false;
                SharedPreferences.Editor e = prefs().edit()
                        .putLong("at", System.currentTimeMillis()).putBoolean("ok", r != null);
                if (r != null) e.putString("reading", r.encode());
                e.apply();
                if (r != null) show.accept(r);
            });
        }, "header-weather").start();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Reading fetch() {
        HttpURLConnection http = null;
        try {
            URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude
                    + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto");
            http = (HttpURLConnection) url.openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(8000);
            if (http.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
            }
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            JSONObject now = json.getJSONObject("current");
            JSONObject daily = json.getJSONObject("daily");
            return new Reading((int) Math.round(now.getDouble("temperature_2m")), now.getInt("weather_code"),
                    now.optInt("is_day", 1) == 1,
                    (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                    (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)));
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }
}
