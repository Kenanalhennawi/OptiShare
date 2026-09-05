package com.kenan.optishare;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import com.kenan.optishare.history.TransferHistoryStore;
import com.kenan.optishare.settings.Appearance;
import com.kenan.optishare.settings.LocaleSupport;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Local transfer-session history. No file contents or cloud data are stored here. */
public final class HistoryActivity extends ComponentActivity {
    private TransferHistoryStore store;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleSupport.wrap(base));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new TransferHistoryStore(this);
        render();
    }

    private void render() {
        boolean light = Appearance.light(this);
        int text = light ? Color.rgb(12, 30, 48) : Color.WHITE;
        int secondary = light ? Color.rgb(73, 95, 114) : Color.rgb(158, 198, 226);
        getWindow().setStatusBarColor(light ? Color.rgb(239, 247, 255) : Color.rgb(3, 14, 34));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(light
                ? vivid(new int[]{Color.rgb(249, 252, 255), Color.rgb(235, 245, 255), Color.rgb(247, 241, 255)}, 0, Color.TRANSPARENT)
                : vivid(new int[]{Color.rgb(3, 14, 34), Color.rgb(8, 43, 74), Color.rgb(35, 22, 74)}, 0, Color.TRANSPARENT));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(34));
        wrapper.addView(page, new LinearLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels, dp(840)), -2));
        scroll.addView(wrapper, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹", light);
        back.setContentDescription(getString(R.string.back_plain));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(13), 0, 0, 0);
        titles.addView(label(getString(R.string.transfer_history), 25, text, true));
        titles.addView(label(getString(R.string.transfer_history_summary), 12, secondary, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(header);

        List<TransferHistoryStore.Entry> entries = store.load();
        if (entries.isEmpty()) {
            LinearLayout empty = card(light);
            empty.setGravity(Gravity.CENTER);
            TextView icon = label("✓", 28, Color.WHITE, true);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(oval(new int[]{Color.rgb(86, 220, 255), Color.rgb(45, 125, 238), Color.rgb(112, 57, 220)}));
            empty.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView title = label(getString(R.string.history_empty), 17, text, true);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(14), 0, 0);
            empty.addView(title);
            TextView help = label(getString(R.string.history_empty_summary), 13, secondary, false);
            help.setGravity(Gravity.CENTER);
            help.setPadding(dp(8), dp(6), dp(8), 0);
            empty.addView(help);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(22), 0, 0);
            page.addView(empty, lp);
        } else {
            for (TransferHistoryStore.Entry entry : entries) {
                LinearLayout card = historyCard(entry, light, text, secondary);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, dp(12), 0, 0);
                page.addView(card, lp);
            }
            Button clear = button(getString(R.string.clear_history), light);
            clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(R.string.clear_history)
                    .setMessage(R.string.clear_history_confirm)
                    .setPositiveButton(R.string.clear_history, (dialog, which) -> {
                        store.clear();
                        render();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show());
            LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(-1, dp(52));
            clearLp.setMargins(0, dp(18), 0, 0);
            page.addView(clear, clearLp);
        }

        setContentView(scroll);
        scroll.setAlpha(0f);
        scroll.setTranslationY(dp(20));
        scroll.animate().alpha(1f).translationY(0f).setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private LinearLayout historyCard(TransferHistoryStore.Entry entry, boolean light, int text, int secondary) {
        LinearLayout card = card(light);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        boolean incoming = entry.direction != null
                && (entry.direction.toLowerCase(Locale.US).contains("receive")
                || entry.direction.toLowerCase(Locale.US).contains("incoming"));
        TextView badge = label(incoming ? "↓" : "↑", 22, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(oval(incoming
                ? new int[]{Color.rgb(110, 255, 205), Color.rgb(24, 196, 137), Color.rgb(8, 108, 84)}
                : new int[]{Color.rgb(116, 222, 255), Color.rgb(42, 132, 245), Color.rgb(89, 52, 207)}));
        top.addView(badge, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(13), 0, 0, 0);
        String direction = getString(incoming ? R.string.history_received : R.string.history_sent);
        String status = getString(entry.success ? R.string.history_success : R.string.history_failed);
        copy.addView(label(direction + " • " + status, 16,
                entry.success ? (light ? Color.rgb(0, 119, 80) : Color.rgb(104, 238, 188))
                        : Color.rgb(239, 92, 106), true));
        String peer = entry.peer == null || entry.peer.trim().isEmpty()
                ? getString(R.string.route_unknown) : entry.peer;
        String when = entry.time <= 0 ? getString(R.string.unknown_time)
                : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(entry.time));
        copy.addView(label(getString(R.string.history_peer_time, peer, when), 12, secondary, false));
        top.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(top);

        TextView files = label(getString(R.string.history_files_size,
                entry.fileCount, human(entry.totalBytes)), 13, text, true);
        files.setPadding(0, dp(13), 0, 0);
        card.addView(files);

        if (entry.durationMs > 0 || entry.averageBytesPerSecond > 0) {
            String performance = getString(R.string.history_performance,
                    route(entry.route), duration(entry.durationMs),
                    human((long) entry.averageBytesPerSecond));
            TextView perf = label(performance, 12, secondary, false);
            perf.setPadding(0, dp(5), 0, 0);
            card.addView(perf);
        }
        if (entry.reconnects > 0) {
            TextView reconnects = label(getString(R.string.history_reconnects, entry.reconnects),
                    12, secondary, false);
            reconnects.setPadding(0, dp(4), 0, 0);
            card.addView(reconnects);
        }
        return card;
    }

    private String route(String route) {
        String value = route == null ? "" : route.toLowerCase(Locale.US);
        if (value.contains("direct") || value.contains("p2p")) return getString(R.string.route_wifi_direct);
        if (value.contains("lan") || value.contains("wifi") || value.contains("wi-fi"))
            return getString(R.string.route_same_wifi);
        return getString(R.string.route_unknown);
    }

    private static String duration(long millis) {
        long total = Math.max(0L, millis / 1000L);
        if (total >= 3600) return String.format(Locale.US, "%dh %02dm %02ds",
                total / 3600, (total % 3600) / 60, total % 60);
        if (total >= 60) return String.format(Locale.US, "%dm %02ds", total / 60, total % 60);
        return total + "s";
    }

    private static String human(long bytes) {
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.2f GB", bytes / (1024d * 1024d * 1024d));
        if (bytes >= 1024L * 1024)
            return String.format(Locale.US, "%.2f MB", bytes / (1024d * 1024d));
        if (bytes >= 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024d);
        return bytes + " B";
    }

    private LinearLayout card(boolean light) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(light
                ? vivid(new int[]{Color.WHITE, Color.rgb(244, 249, 255), Color.rgb(239, 243, 255)},
                22, Color.rgb(204, 221, 236))
                : vivid(new int[]{Color.rgb(25, 66, 98), Color.rgb(12, 39, 68), Color.rgb(35, 24, 75)},
                22, Color.rgb(68, 108, 141)));
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(8));
        return card;
    }

    private Button button(String value, boolean light) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(light ? Color.rgb(13, 39, 62) : Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(light
                ? vivid(new int[]{Color.WHITE, Color.rgb(226, 240, 252), Color.rgb(211, 226, 244)},
                18, Color.rgb(190, 211, 229))
                : vivid(new int[]{Color.rgb(78, 139, 180), Color.rgb(33, 78, 116), Color.rgb(55, 37, 108)},
                18, Color.rgb(104, 151, 185)));
        if (Build.VERSION.SDK_INT >= 21) {
            button.setElevation(dp(12));
            button.setTranslationZ(dp(4));
        }
        press(button);
        return button;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private GradientDrawable oval(int[] colors) {
        GradientDrawable value = vivid(colors, 40, Color.argb(130, 255, 255, 255));
        value.setShape(GradientDrawable.OVAL);
        return value;
    }

    private GradientDrawable vivid(int[] colors, int radius, int stroke) {
        GradientDrawable value = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        value.setCornerRadius(dp(radius));
        if (Color.alpha(stroke) > 0) value.setStroke(dp(1), stroke);
        return value;
    }

    private void press(View view) {
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.975f).scaleY(.975f).translationY(dp(4)).setDuration(75).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP
                    || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(175).start();
            return false;
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
