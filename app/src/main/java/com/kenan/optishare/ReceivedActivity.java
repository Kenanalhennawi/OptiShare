package com.kenan.optishare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.kenan.optishare.storage.ReceivedRepository;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Consumer-facing library for completed received files. */
public final class ReceivedActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (getWindow().getDecorView().getRootView() != null) render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        GradientDrawable page = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(5,14,28),Color.rgb(8,29,51),Color.rgb(18,19,54)});
        root.setBackground(page);
        scroll.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹"); back.setTextSize(22); back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL); titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("Received Library", 27, Color.WHITE, true));
        titles.addView(text("Everything received with OptiShare", 12, Color.rgb(144, 178, 204), false));
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top);

        List<ReceivedRepository.Item> items = new ReceivedRepository(this).load();
        long bytes = 0; for (ReceivedRepository.Item i : items) bytes += i.size;
        TextView summary = text(items.size() + " file" + (items.size() == 1 ? "" : "s") + " • " + formatBytes(bytes), 15, Color.rgb(104, 204, 255), true);
        summary.setPadding(0, dp(22), 0, dp(10)); root.addView(summary);

        if (items.isEmpty()) {
            LinearLayout empty = card(); empty.setGravity(Gravity.CENTER);
            TextView icon = text("↓", 38, Color.rgb(75, 190, 255), true); icon.setGravity(Gravity.CENTER); empty.addView(icon);
            TextView title = text("No received files yet", 18, Color.WHITE, true); title.setGravity(Gravity.CENTER); title.setPadding(0, dp(8), 0, dp(4)); empty.addView(title);
            TextView hint = text("Completed transfers will appear here automatically, organized by type.", 13, Color.rgb(143, 171, 195), false); hint.setGravity(Gravity.CENTER); empty.addView(hint);
            root.addView(empty);
        } else {
            String currentCategory = null;
            for (ReceivedRepository.Item item : items) {
                if (!item.category.equals(currentCategory)) {
                    currentCategory = item.category;
                    TextView category = text(currentCategory, 16, Color.WHITE, true); category.setPadding(0, dp(16), 0, dp(7)); root.addView(category);
                }
                root.addView(fileCard(item));
            }
        }

        TextView location = text("Saved under Download / OptiShare and organized by type", 11, Color.rgb(104, 160, 196), false);
        location.setGravity(Gravity.CENTER); location.setPadding(0, dp(22), 0, 0); root.addView(location);
        setContentView(scroll);
    }

    private LinearLayout fileCard(ReceivedRepository.Item item) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout previewBox = new LinearLayout(this); previewBox.setGravity(Gravity.CENTER);
        previewBox.setBackground(round(Color.rgb(19,46,69),16));
        if (item.mime.startsWith("image/") || item.mime.startsWith("video/")) {
            ImageView preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Glide.with(this).load(item.uri).centerCrop().into(preview);
            previewBox.addView(preview,new LinearLayout.LayoutParams(dp(68),dp(68)));
        } else {
            TextView icon = text(iconFor(item), 22, Color.WHITE, true); icon.setGravity(Gravity.CENTER); previewBox.addView(icon,new LinearLayout.LayoutParams(dp(68),dp(68)));
        }
        row.addView(previewBox,new LinearLayout.LayoutParams(dp(68),dp(68)));

        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL); labels.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(item.name, 14, Color.WHITE, true); name.setMaxLines(2); labels.addView(name);
        String meta = formatBytes(item.size) + " • " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(item.modified));
        labels.addView(text(meta, 11, Color.rgb(140, 169, 193), false));
        labels.addView(text(item.category,10,Color.rgb(96,203,255),true));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button open = button("Open"); open.setOnClickListener(v -> openItem(item));
        Button share = button("Share"); share.setOnClickListener(v -> shareItem(item));
        Button delete = button("Delete"); delete.setOnClickListener(v -> confirmDelete(item));
        actions.addView(open,new LinearLayout.LayoutParams(0,dp(44),1));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(44),1);sp.setMargins(dp(7),0,0,0);actions.addView(share,sp);
        LinearLayout.LayoutParams dp1=new LinearLayout.LayoutParams(0,dp(44),1);dp1.setMargins(dp(7),0,0,0);actions.addView(delete,dp1);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.setMargins(0,dp(10),0,0);card.addView(actions,ap);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, dp(9)); card.setLayoutParams(lp);
        return card;
    }

    private Uri shareableUri(ReceivedRepository.Item item) {
        if (item.uri == null) return null;
        if (!"file".equalsIgnoreCase(item.uri.getScheme())) return item.uri;
        try {
            File file = new File(item.uri.getPath());
            return FileProvider.getUriForFile(this, getPackageName()+".files", file);
        } catch (Exception e) { return null; }
    }

    private void openItem(ReceivedRepository.Item item) {
        Uri uri = shareableUri(item); if (uri == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW); intent.setDataAndType(uri, item.mime); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open with"));
        } catch (Exception e) { showMessage("Cannot open file", "No compatible app is installed for this file type."); }
    }

    private void shareItem(ReceivedRepository.Item item) {
        Uri uri = shareableUri(item); if (uri == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_SEND); intent.setType(item.mime); intent.putExtra(Intent.EXTRA_STREAM, uri); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share file"));
        } catch (Exception e) { showMessage("Cannot share file", "Android could not share this file."); }
    }

    private void confirmDelete(ReceivedRepository.Item item) {
        new AlertDialog.Builder(this).setTitle("Delete received file?").setMessage(item.name + " will be removed from this device.")
                .setPositiveButton("Delete",(d,w)->{deleteItem(item);render();}).setNegativeButton("Cancel",null).show();
    }

    private void deleteItem(ReceivedRepository.Item item) {
        try {
            if (item.uri != null && "file".equalsIgnoreCase(item.uri.getScheme())) {
                String p=item.uri.getPath(); if(p!=null)new File(p).delete();
            } else if (item.uri != null) getContentResolver().delete(item.uri,null,null);
        } catch (Exception e) { showMessage("Delete failed", "Android did not allow OptiShare to delete this file."); }
    }

    private LinearLayout card() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(15), dp(14), dp(15), dp(14)); GradientDrawable bg = round(Color.rgb(13, 30, 49),18); bg.setStroke(dp(1), Color.rgb(29, 55, 79)); l.setBackground(bg); return l; }
    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(12); b.setBackground(round(Color.rgb(24,49,73),14)); return b; }
    private TextView text(String value, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private String iconFor(ReceivedRepository.Item item) { if (item.mime.startsWith("audio/")) return "♫"; if (item.name.toLowerCase(Locale.US).endsWith(".apk")) return "A"; if(item.name.toLowerCase(Locale.US).endsWith(".zip")||item.name.toLowerCase(Locale.US).endsWith(".rar"))return"ZIP"; return "≡"; }
    private static String formatBytes(long bytes) { if (bytes < 1024) return bytes + " B"; if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0); if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0)); return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0)); }
    private void showMessage(String title,String message){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show();}
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
