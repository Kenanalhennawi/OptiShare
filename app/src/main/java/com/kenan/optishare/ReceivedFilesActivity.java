package com.kenan.optishare;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import com.kenan.optishare.ui.UiText;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A local, privacy-preserving view of files published by OptiShare. */
public final class ReceivedFilesActivity extends ComponentActivity {
    private static final int MAX_ITEMS = 1000;

    private static final class Item {
        final String name;
        final String mime;
        final long size;
        final long modifiedMs;
        final Uri uri;
        final String path;

        Item(String name, String mime, long size, long modifiedMs, Uri uri, String path) {
            this.name = name == null || name.trim().isEmpty() ? "Received file" : name;
            this.mime = mime == null || mime.trim().isEmpty() ? "application/octet-stream" : mime;
            this.size = Math.max(0L, size);
            this.modifiedMs = Math.max(0L, modifiedMs);
            this.uri = uri;
            this.path = path;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (getWindow().getDecorView().getTag() != null) render();
    }

    private void render() {
        getWindow().setStatusBarColor(Color.rgb(5, 20, 38));
        getWindow().getDecorView().setTag("rendered");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(5, 20, 38));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹ Back");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(92), dp(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("Received files", 25, Color.WHITE, true));
        titles.addView(text("Download / OptiShare", 12, Color.rgb(135, 181, 214), false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(header);

        List<Item> items = loadItems();
        long totalBytes = 0L;
        for (Item item : items) totalBytes += item.size;
        TextView summary = text(items.size() + " file" + (items.size() == 1 ? "" : "s")
                + " • " + human(totalBytes), 13, Color.rgb(165, 201, 226), false);
        summary.setPadding(0, dp(20), 0, dp(12));
        root.addView(summary);

        if (items.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("Nothing received yet", 17, Color.WHITE, true));
            TextView help = text("Files verified by OptiShare will appear here automatically after they are published to Downloads.",
                    13, Color.rgb(151, 187, 213), false);
            help.setPadding(0, dp(7), 0, 0);
            empty.addView(help);
            root.addView(empty);
        } else {
            for (Item item : items) root.addView(itemCard(item), spaced());
        }

        TextView footer = text("Only local files are listed. Nothing is uploaded to a cloud service.",
                11, Color.rgb(105, 151, 184), false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, 0);
        root.addView(footer);
        setContentView(scroll);
    }

    private View itemCard(Item item) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(iconFor(item.mime), 22, Color.WHITE, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(Color.rgb(28, 103, 157), 16));
        top.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        TextView name = text(item.name, 14, Color.WHITE, true);
        name.setMaxLines(2);
        info.addView(name);
        String category = categoryFor(item.mime);
        String when = item.modifiedMs <= 0 ? "Unknown time" : DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT).format(new Date(item.modifiedMs));
        info.addView(text(category + " • " + human(item.size) + " • " + when,
                11, Color.rgb(139, 177, 205), false));
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(top);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);
        Button open = button("Open");
        Button share = button("Share");
        Button delete = button("Delete");
        boolean actionable = itemUri(item) != null;
        open.setEnabled(actionable);
        share.setEnabled(actionable);
        open.setAlpha(actionable ? 1f : .45f);
        share.setAlpha(actionable ? 1f : .45f);
        open.setOnClickListener(v -> openItem(item));
        share.setOnClickListener(v -> shareItem(item));
        delete.setOnClickListener(v -> confirmDelete(item));
        actions.addView(open, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        sp.setMargins(dp(8), 0, 0, 0);
        actions.addView(share, sp);
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        deleteLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(delete, deleteLp);
        card.addView(actions);
        if (!actionable && item.path != null) {
            TextView legacy = text(item.path, 10, Color.rgb(111, 151, 181), false);
            legacy.setPadding(0, dp(8), 0, 0);
            card.addView(legacy);
        }
        return card;
    }

    private List<Item> loadItems() {
        try {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? loadMediaStore() : loadLegacy();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private List<Item> loadMediaStore() {
        List<Item> result = new ArrayList<>();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED,
                MediaStore.Downloads.RELATIVE_PATH
        };
        String selection = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
        String[] args = { Environment.DIRECTORY_DOWNLOADS + "/OptiShare/%" };
        String order = MediaStore.Downloads.DATE_MODIFIED + " DESC";
        try (Cursor cursor = getContentResolver().query(collection, projection, selection, args, order)) {
            if (cursor == null) return result;
            int id = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID);
            int name = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
            int mime = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE);
            int size = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
            int modified = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED);
            while (cursor.moveToNext() && result.size() < MAX_ITEMS) {
                long rowId = cursor.getLong(id);
                result.add(new Item(cursor.getString(name), cursor.getString(mime), cursor.getLong(size),
                        cursor.getLong(modified) * 1000L, ContentUris.withAppendedId(collection, rowId), null));
            }
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private List<Item> loadLegacy() {
        List<Item> result = new ArrayList<>();
        File root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OptiShare");
        collectLegacy(root, result);
        result.sort((a, b) -> Long.compare(b.modifiedMs, a.modifiedMs));
        if (result.size() > MAX_ITEMS) return new ArrayList<>(result.subList(0, MAX_ITEMS));
        return result;
    }

    private void collectLegacy(File file, List<Item> out) {
        if (file == null || !file.exists() || out.size() >= 1000) return;
        if (file.isFile()) {
            out.add(new Item(file.getName(), guessMime(file.getName()), file.length(), file.lastModified(), null,
                    file.getAbsolutePath()));
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) collectLegacy(child, out);
    }

    private void openItem(Item item) {
        Uri uri = itemUri(item);
        if (uri == null) return;
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(intent); }
        catch (Exception error) { showSimple("No app can open this file type."); }
    }

    private void shareItem(Item item) {
        Uri uri = itemUri(item);
        if (uri == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND).setType(item.mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(intent, "Share received file")); }
        catch (Exception error) { showSimple("This file cannot be shared right now."); }
    }

    private void confirmDelete(Item item) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.delete_received_title)
                .setMessage(getString(R.string.delete_received_message, item.name))
                .setPositiveButton(R.string.delete, (dialog, which) -> deleteItem(item))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteItem(Item item) {
        boolean deleted = false;
        try {
            if (item.uri != null && "content".equals(item.uri.getScheme())) {
                deleted = getContentResolver().delete(item.uri, null, null) > 0;
            } else if (item.path != null) {
                File file = new File(item.path);
                File allowedRoot = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "OptiShare");
                String rootPath = allowedRoot.getCanonicalPath() + File.separator;
                deleted = file.getCanonicalPath().startsWith(rootPath) && file.isFile() && file.delete();
            }
        } catch (Exception error) {
            showSimple(getString(R.string.delete_android_failed, error.getMessage()));
            return;
        }
        if (deleted) render(); else showSimple("The file could not be deleted.");
    }

    private Uri itemUri(Item item) {
        if (item == null) return null;
        if (item.uri != null && "content".equals(item.uri.getScheme())) return item.uri;
        if (item.path == null || item.path.trim().isEmpty()) return null;
        try {
            File file = new File(item.path);
            if (!file.exists() || !file.isFile()) return null;
            File allowedRoot = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "OptiShare");
            String rootPath = allowedRoot.getCanonicalPath() + File.separator;
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(rootPath)) return null;
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void showSimple(String message) {
        new android.app.AlertDialog.Builder(this).setTitle("OptiShare").setMessage(UiText.get(this, message))
                .setPositiveButton(R.string.ok, null).show();
    }

    private LinearLayout card() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setPadding(dp(15), dp(14), dp(15), dp(14));
        value.setBackground(round(Color.rgb(12, 42, 69), 18));
        return value;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(10));
        return lp;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(UiText.get(this,label));
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setBackground(round(Color.rgb(22, 73, 111), 14));
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(UiText.get(this,value));
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String categoryFor(String mime) {
        if (mime == null) return "Other";
        if (mime.startsWith("image/")) return "Photo";
        if (mime.startsWith("video/")) return "Video";
        if (mime.startsWith("audio/")) return "Music";
        if (mime.equals("application/vnd.android.package-archive") || mime.equals("application/x-apks")) return "App";
        if (mime.contains("zip") || mime.contains("rar") || mime.contains("7z") || mime.contains("tar")) return "Archive";
        if (mime.startsWith("text/") || mime.contains("pdf") || mime.contains("document") || mime.contains("sheet")) return "Document";
        return "File";
    }

    private static String iconFor(String mime) {
        String category = categoryFor(mime);
        switch (category) {
            case "Photo": return "▣";
            case "Video": return "▶";
            case "Music": return "♫";
            case "App": return "A";
            case "Archive": return "▤";
            case "Document": return "≡";
            default: return "•";
        }
    }

    private static String guessMime(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".apks")) return "application/x-apks";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private static String human(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", bytes / (1024d * 1024d * 1024d));
        if (bytes >= 1024L * 1024) return String.format(Locale.US, "%.2f MB", bytes / (1024d * 1024d));
        if (bytes >= 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024d);
        return bytes + " B";
    }
}
