package com.kenan.optishare;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kenan.optishare.device.DeviceIdentity;
import com.kenan.optishare.history.TransferHistoryStore;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class V3Activity extends Activity {
    private DeviceIdentity identity;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        identity = new DeviceIdentity(this);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (identity != null) render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(5, 14, 28), Color.rgb(8, 30, 53), Color.rgb(18, 20, 55)});
        root.setBackground(bg);
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("O", 25, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        GradientDrawable logo = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(35, 181, 255), Color.rgb(102, 72, 246)});
        logo.setCornerRadius(dp(20));
        mark.setBackground(logo);
        header.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout nameBox = new LinearLayout(this);
        nameBox.setOrientation(LinearLayout.VERTICAL);
        nameBox.setPadding(dp(12), 0, 0, 0);
        nameBox.addView(text("OptiShare", 28, Color.WHITE, true));
        nameBox.addView(text(identity.name(), 12, Color.rgb(151, 193, 222), false));
        header.addView(nameBox, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = smallButton("⚙");
        settings.setTextSize(19);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(44)));
        root.addView(header);

        TextView headline = text("Share without friction.", 30, Color.WHITE, true);
        headline.setPadding(0, dp(30), 0, dp(6));
        root.addView(headline);
        root.addView(text("Private, encrypted and resumable nearby transfers — without Internet or a cloud account.", 14,
                Color.rgb(177, 207, 229), false));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setPadding(0, dp(24), 0, 0);
        Button send = bigAction("↑", "Send", "Choose content", Color.rgb(40, 154, 255), Color.rgb(67, 77, 229));
        send.setOnClickListener(v -> openTransfer(V3TransferActivity.MODE_SEND));
        Button receive = bigAction("↓", "Receive", "Become visible", Color.rgb(52, 211, 153), Color.rgb(16, 120, 91));
        receive.setOnClickListener(v -> openTransfer(V3TransferActivity.MODE_RECEIVE));
        hero.addView(send, new LinearLayout.LayoutParams(0, dp(164), 1));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(164), 1);
        rlp.setMargins(dp(10), 0, 0, 0);
        hero.addView(receive, rlp);
        root.addView(hero);

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        Button received = shortcut("↓", "Received", "Browse saved files");
        received.setOnClickListener(v -> startActivity(new Intent(this, ReceivedActivity.class)));
        Button history = shortcut("↕", "History", "Recent sessions");
        history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        shortcuts.addView(received,new LinearLayout.LayoutParams(0,dp(104),1));
        LinearLayout.LayoutParams hlp=new LinearLayout.LayoutParams(0,dp(104),1);hlp.setMargins(dp(10),0,0,0);shortcuts.addView(history,hlp);
        LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,-2);slp.setMargins(0,dp(14),0,0);root.addView(shortcuts,slp);

        addLastTransfer(root);

        TextView features = text("Why OptiShare", 17, Color.WHITE, true);
        features.setPadding(0, dp(24), 0, dp(10));
        root.addView(features);
        LinearLayout featureCard = card();
        featureCard.addView(feature("↻", "Resume automatically", "Continue from the last verified chunk after a drop"));
        featureCard.addView(feature("⌁", "Encrypted locally", "Authenticated device-to-device session — no cloud relay"));
        featureCard.addView(feature("✓", "Verified before publishing", "Received files are checked before appearing as complete"));
        featureCard.addView(feature("◎", "Smart nearby recovery", "Discovery and connection retry automatically when Android is busy"));
        root.addView(featureCard);

        TextView credit = text("OptiShare 3.0 • Designed & developed by Kenan Alhennawi", 11, Color.rgb(106, 150, 184), false);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(dp(8), dp(22), dp(8), 0);
        root.addView(credit);
        setContentView(scroll);
    }

    private void addLastTransfer(LinearLayout root) {
        List<TransferHistoryStore.Entry> entries = new TransferHistoryStore(this).load();
        if (entries.isEmpty()) return;
        TransferHistoryStore.Entry e = entries.get(0);
        TextView section = text("Last transfer", 16, Color.WHITE, true);
        section.setPadding(0,dp(22),0,dp(8)); root.addView(section);
        LinearLayout card = card();
        boolean received = "received".equalsIgnoreCase(e.direction);
        card.addView(text((received?"Received from ":"Sent to ")+e.peer,14,Color.WHITE,true));
        card.addView(text(e.fileCount+" file"+(e.fileCount==1?"":"s")+" • "+formatBytes(e.totalBytes)+(e.success?" • Completed":" • Interrupted"),11,e.success?Color.rgb(80,215,156):Color.rgb(255,128,140),false));
        card.addView(text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(e.time)),10,Color.rgb(135,166,190),false));
        card.setOnClickListener(v->startActivity(new Intent(this,HistoryActivity.class)));
        root.addView(card);
    }

    private void openTransfer(String mode) {
        Intent intent = new Intent(this, V3TransferActivity.class);
        intent.putExtra(V3TransferActivity.EXTRA_MODE, mode);
        startActivity(intent);
    }

    private LinearLayout feature(String icon, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView i = text(icon, 18, Color.rgb(108, 211, 255), true);
        i.setGravity(Gravity.CENTER);
        row.addView(i, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(8), 0, 0, 0);
        labels.addView(text(title, 14, Color.WHITE, true));
        labels.addView(text(subtitle, 11, Color.rgb(139, 169, 193), false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private Button bigAction(String icon, String title, String subtitle, int top, int bottom) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setText(icon + "\n" + title + "\n" + subtitle);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
        bg.setCornerRadius(dp(24));
        b.setBackground(bg);
        return b;
    }

    private Button shortcut(String icon,String title,String subtitle){Button b=new Button(this);b.setAllCaps(false);b.setGravity(Gravity.CENTER);b.setText(icon+"\n"+title+"\n"+subtitle);b.setTextSize(13);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.DEFAULT_BOLD);GradientDrawable g=round(Color.rgb(13,31,50),20);g.setStroke(dp(1),Color.rgb(29,57,82));b.setBackground(g);return b;}
    private Button smallButton(String label){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(Color.WHITE);b.setBackground(round(Color.rgb(20,45,68),14));return b;}
    private LinearLayout card() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(16), dp(15), dp(16), dp(15)); GradientDrawable bg = round(Color.rgb(13, 31, 50), 20); bg.setStroke(dp(1), Color.rgb(29, 57, 82)); l.setBackground(bg); return l; }
    private GradientDrawable round(int color, int radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g; }
    private TextView text(String value, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private static String formatBytes(long b){if(b>=1024L*1024*1024)return String.format(Locale.US,"%.2f GB",b/(1024.0*1024*1024));if(b>=1024L*1024)return String.format(Locale.US,"%.2f MB",b/(1024.0*1024));if(b>=1024)return String.format(Locale.US,"%.1f KB",b/1024.0);return b+" B";}
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
