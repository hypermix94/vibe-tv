package io.github.hypermix94.vibetv;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.format.DateFormat;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * Timers and alarms, for the line under the home screen's clock. The next alarm comes from Android, which shares it
 * with every app. A running timer only shows as its app's notification, so NowPlayingListener hands over the
 * notifications of clock and timer apps, or ones Android marks as an alarm or a countdown; no other notification is
 * read.
 */
final class Clocks {
    /** Apps whose notifications are timers: the TV's own timers (sleep timer, on/off timers) and clock apps. */
    private static final List<String> TIMER_APPS = Arrays.asList(
            "com.sony.dtv.timers", "com.google.android.deskclock", "com.android.deskclock");

    /** A running timer, as its notification described it. */
    static final class Timer {
        final String key, pkg, words;
        final long endsAt;            // 0 when the notification gives no end time
        final PendingIntent open;

        Timer(String key, String pkg, String words, long endsAt, PendingIntent open) {
            this.key = key;
            this.pkg = pkg;
            this.words = words;
            this.endsAt = endsAt;
            this.open = open;
        }
    }

    private static volatile Timer timer;

    private Clocks() {
    }

    /** True for a notification that is a timer or an alarm. */
    static boolean isTimer(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        boolean countdown = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN);
        return TIMER_APPS.contains(sbn.getPackageName()) || Notification.CATEGORY_ALARM.equals(n.category)
                || Notification.CATEGORY_STOPWATCH.equals(n.category) || countdown;
    }

    /** From NowPlayingListener: a timer notification came or changed. */
    static void posted(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        boolean countdown = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN);
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE), text = x.getCharSequence(Notification.EXTRA_TEXT);
        String words = title != null ? title.toString() : "Timer";
        if (text != null && text.length() > 0) words += " · " + text;
        timer = new Timer(sbn.getKey(), sbn.getPackageName(), words, countdown ? n.when : 0, n.contentIntent);
    }

    /** From NowPlayingListener: a notification went away. */
    static void removed(StatusBarNotification sbn) {
        Timer t = timer;
        if (t != null && t.key.equals(sbn.getKey())) timer = null;
    }

    static Timer timer() {
        Timer t = timer;
        if (t != null && t.endsAt > 0 && t.endsAt < System.currentTimeMillis() - 60_000) timer = null;   // long over
        return timer;
    }

    static AlarmManager.AlarmClockInfo nextAlarm(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        return am != null ? am.getNextAlarmClock() : null;
    }

    /** The line under the clock: a running timer, else the next alarm, else null (show the date). */
    static String line(Context c) {
        Timer t = timer();
        if (t != null) {
            if (t.endsAt <= 0) return t.words;
            long left = Math.max(0, t.endsAt - System.currentTimeMillis());
            long sec = left / 1000, h = sec / 3600, m = sec % 3600 / 60, ss = sec % 60;
            String clock = h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, ss)
                    : String.format(java.util.Locale.ROOT, "%d:%02d", m, ss);
            return "Timer · " + clock;
        }
        AlarmManager.AlarmClockInfo a = nextAlarm(c);
        if (a != null) {
            Calendar at = Calendar.getInstance(), today = Calendar.getInstance();
            at.setTimeInMillis(a.getTriggerTime());
            String time = DateFormat.format(DateFormat.is24HourFormat(c) ? "HH:mm" : "h:mm a", at).toString().toLowerCase();
            boolean sameDay = at.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
                    && at.get(Calendar.YEAR) == today.get(Calendar.YEAR);
            return "Alarm · " + (sameDay ? time : DateFormat.format("EEE", at) + " " + time);
        }
        return null;
    }

    /** What the line is about now, so a change of kind (date, alarm, timer) can animate. */
    static int kind(Context c) {
        return timer() != null ? 2 : nextAlarm(c) != null ? 1 : 0;
    }

    /** OK on the clock: open the timer's own screen, else the alarm's, else the TV's timers app. */
    static void open(Context c) {
        try {
            Timer t = timer();
            if (t != null && t.open != null) {
                t.open.send();
                return;
            }
            AlarmManager.AlarmClockInfo a = nextAlarm(c);
            if (a != null && a.getShowIntent() != null) {
                a.getShowIntent().send();
                return;
            }
        } catch (PendingIntent.CanceledException ignored) {
            // fall through to the timers app
        }
        String pkg = timer() != null ? timer().pkg : "com.sony.dtv.timers";
        Intent launch = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
