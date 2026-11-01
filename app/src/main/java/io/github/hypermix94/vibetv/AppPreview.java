package io.github.hypermix94.vibetv;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.storage.StorageManager;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The All apps page's preview: the focused app's icon and name, a one-line description, and what is worth knowing at a
 * glance: where it lives on the home screen, when it was last opened, what it last played, how much space it takes,
 * its version and when it was installed. It crossfades as the remote moves; sizes are worked out in the background.
 */
final class AppPreview extends FrameLayout {
    private final Context context;
    private final float s;
    private final LinearLayout content;
    private final ImageView icon;
    private final TextView name, description;
    private final TextView[] values = new TextView[6];
    private final Map<String, String> sizes = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String showing = "";

    private static final String[] LABELS = {"On the home screen", "Last opened", "Last played", "Size", "Version", "Installed"};

    AppPreview(Context c, float s) {
        super(c);
        this.context = c;
        this.s = s;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61B1A19);
        bg.setCornerRadius(d(18));
        bg.setStroke(Math.max(1, d(1)), Cards.BORDER);
        setBackground(bg);
        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(d(40), d(40), d(40), d(40));
        addView(content, new LayoutParams(-1, -1));

        icon = new ImageView(c);
        icon.setOutlineProvider(Cards.rounded(s, 26));
        icon.setClipToOutline(true);
        content.addView(icon, new LinearLayout.LayoutParams(d(112), d(112)));
        name = Cards.text(c, s, 44, R.font.outfit_medium, Color.WHITE);
        name.setTypeface(android.graphics.Typeface.create(c.getResources().getFont(R.font.outfit_medium), 600, false));
        name.setLetterSpacing(-0.02f);
        name.setMaxLines(2);
        name.setPadding(0, d(6), 0, d(6));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = d(22);
        content.addView(name, nameLp);
        description = Cards.text(c, s, 24, R.font.outfit_regular, 0xB8FFFFFF);
        description.setMaxLines(2);
        description.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
        descLp.topMargin = d(4);
        content.addView(description, descLp);

        for (int i = 0; i < LABELS.length; i++) {
            TextView label = Cards.text(c, s, 19, R.font.outfit_regular, 0x8CFFFFFF);
            label.setText(LABELS[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = d(i == 0 ? 34 : 18);
            content.addView(label, lp);
            values[i] = Cards.text(c, s, 25, R.font.outfit_regular, Color.WHITE);
            values[i].setSingleLine(true);
            values[i].setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2);
            vp.topMargin = d(4);
            content.addView(values[i], vp);
        }
    }

    /** Shows {@code a}; {@code where} is "In the dock", "On Home" or "Not on Home". */
    void show(Apps.App a, String where, boolean animate) {
        if (a == null || a.pkg.equals(showing)) return;
        showing = a.pkg;
        icon.setImageDrawable(a.icon);
        name.setText(a.label);
        description.setText(describe(a.pkg));
        values[0].setText(where);
        values[1].setText(lastOpened(a.pkg));
        String played = NowPlaying.loadTitle(context, a.pkg);
        values[2].setText(played.isEmpty() ? "Nothing recorded yet" : played);
        values[3].setText(sizes.containsKey(a.pkg) ? sizes.get(a.pkg) : "Working it out…");
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(a.pkg, 0);
            values[4].setText(info.versionName != null ? info.versionName : "—");
            values[5].setText(DateFormat.format("d MMMM yyyy", info.firstInstallTime));
        } catch (PackageManager.NameNotFoundException e) {
            values[4].setText("—");
            values[5].setText("—");
        }
        if (!sizes.containsKey(a.pkg)) {
            String pkg = a.pkg;
            worker.execute(() -> {
                String size = size(pkg);
                main.post(() -> {
                    sizes.put(pkg, size);
                    if (pkg.equals(showing)) values[3].setText(size);
                });
            });
        }
        if (animate) {
            content.setAlpha(0.25f);
            content.setTranslationY(d(12));
            content.animate().alpha(1f).translationY(0).setDuration(380)
                    .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f)).start();
        }
    }

    /** The app's own one-line description, else its kind (Video, Audio, Game...), else what it is. */
    private String describe(String pkg) {
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence own = ai.loadDescription(pm);
            if (own != null && own.length() > 0) return own.toString();
            CharSequence kind = ApplicationInfo.getCategoryTitle(context, ai.category);
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (kind != null) return kind + (system ? " · came with the TV" : " app");
            return system ? "Came with the TV" : "An app you installed";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private String lastOpened(String pkg) {
        long at = Apps.prefs(context).getLong("opened_" + pkg, 0);
        if (at == 0) return "Not opened from vibe-tv yet";
        return DateFormat.format("d MMMM, " + (DateFormat.is24HourFormat(context) ? "HH:mm" : "h:mm a"), at).toString();
    }

    /** App, data and cache together when the TV allows it (usage access); else the app's own files. */
    private String size(String pkg) {
        try {
            StorageStatsManager stats = context.getSystemService(StorageStatsManager.class);
            StorageStats st = stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle());
            long total = st.getAppBytes() + st.getDataBytes() + st.getCacheBytes();
            return Formatter.formatShortFileSize(context, total) + " (app and data)";
        } catch (Exception e) {
            try {
                ApplicationInfo ai = context.getPackageManager().getApplicationInfo(pkg, 0);
                return Formatter.formatShortFileSize(context, new File(ai.sourceDir).length()) + " (app only)";
            } catch (PackageManager.NameNotFoundException n) {
                return "—";
            }
        }
    }

    private int d(float designPx) {
        return Cards.d(s, designPx);
    }
}
