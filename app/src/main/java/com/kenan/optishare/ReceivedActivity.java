package com.kenan.optishare;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kenan.optishare.storage.ReceivedRepository;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ReceivedActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(6, 15, 28));
        scroll.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("Received", 27, Color.WHITE, true));
        titles.addView(text("Everything OptiShare received", 12, Color.rgb(144, 178, 204), false));
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top);

        List<ReceivedRepository.Item> items = new ReceivedRepository(this).load();
        TextView summary = text(items.size() + " file" + (items.size() == 1 ? "" : "s"), 16, Color.rgb(104, 204, 255), true);
        summary.setPadding(0, dp(22), 0, dp(10));
        root.addView(summary);

        if (items.isEmpty()) {
            LinearLayout empty = card();
            empty.setGravity(Gravity.CENTER);
            TextView icon = text("↓", 34, Color.rgb(75, 190, 255), true);
            icon.setGravity(Gravity.CENTER);
            empty.addView(icon);
            TextView title = text("No received files yet", 17, Color.WHITE, true);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(8), 0, dp(4));
            empty.addView(title);
            TextView hint = text("Files you receive will appear here and stay organized by type.", 13, Color.rgb(143, 171, 195), false);
            hint.setGravity(Gravity.CENTER);
            empty.addView(hint);
            root.addView(empty);
        } else {
            String currentCategory = null;
            for (ReceivedRepository.Item item : items) {
                if (!item.category.equals(currentCategory)) {
                    currentCategory = item.category;
                    TextView category = text(currentCategory, 16, Color.WHITE, true);
                    category.setPadding(0, dp(16), 0, dp(7));
                    root.addView(category);
                }
                root.addView(fileCard(item));
            }
        }

        TextView location = text("Saved in Internal storage / Download / OptiShare", 11, Color.rgb(104, 160, 196), false);
        location.setGravity(Gravity.CENTER);
        location.setPadding(0, dp(22), 0, 0);
        root.addView(location);
        setContentView(scroll);
    }

    private LinearLayout fileCard(ReceivedRepository.Item item) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = text(iconFor(item), 20, Color.WHITE, true);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable iconBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(31, 153, 238), Color.rgb(81, 76, 231)});
        iconBg.setCornerRadius(dp(16));
        icon.setBackground(iconBg);
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(8), 0);
        labels.addView(text(item.name, 14, Color.WHITE, true));
        String meta = formatBytes(item.size) + " • " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(item.modified));
        labels.addView(text(meta, 11, Color.rgb(140, 169, 193), false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        if (item.uri != null && !"file".equalsIgnoreCase(item.uri.getScheme())) {
            Button open = button("Open");
            open.setOnClickListener(v -> openItem(item));
            row.addView(open, new LinearLayout.LayoutParams(dp(76), dp(44)));
        }
        card.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);
        return card;
    }

    private void openItem(ReceivedRepository.Item item) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(item.uri, item.mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open with"));
        } catch (Exception ignored) { }
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(15), dp(14), dp(15), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(13, 30, 49));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(29, 55, 79));
        l.setBackground(bg);
        return l;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(24, 49, 73));
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private String iconFor(ReceivedRepository.Item item) {
        if (item.mime.startsWith("image/")) return "▣";
        if (item.mime.startsWith("video/")) return "▶";
        if (item.mime.startsWith("audio/")) return "♫";
        if (item.name.toLowerCase(Locale.US).endsWith(".apk")) return "A";
        return "≡";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
