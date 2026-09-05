package com.kenan.optishare;

import android.content.ContentUris;
import android.content.Context;
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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.kenan.optishare.settings.Appearance;
import com.kenan.optishare.settings.LocaleSupport;
import com.kenan.optishare.ui.UiText;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A local, privacy-preserving view of files published by OptiShare. */
public final class ReceivedFilesActivity extends ComponentActivity {
    private static final int MAX_ITEMS = 1000;
    private static final String PREFS = "optishare_received_hidden_v1";
    private static final String HIDDEN = "hidden";

    private final List<Item> items = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private RecyclerView list;
    private ReceivedAdapter adapter;
    private Button manageButton;
    private TextView summary;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleSupport.wrap(base));
    }

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

        String key() {
            return uri != null ? "uri:" + uri : "path:" + String.valueOf(path);
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildScreen();
        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        if (list != null) reload();
    }

    private void buildScreen() {
        getWindow().setStatusBarColor(Appearance.background(this));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(16));
        root.setBackground(vivid(new int[]{Color.rgb(3,14,34),Color.rgb(8,45,76),Color.rgb(36,22,76)},0));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹ Back");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(92), dp(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPaddingRelative(dp(12), 0, 0, 0);
        titles.addView(text("Received files", 25, Color.WHITE, true));
        titles.addView(text("Download / OptiShare", 12, Color.rgb(135, 181, 214), false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(header);

        summary = text("", 13, Color.rgb(165, 201, 226), false);
        summary.setPadding(0, dp(16), 0, dp(8));
        root.addView(summary);

        manageButton = button(getString(R.string.select_received_files));
        manageButton.setEnabled(false);
        manageButton.setAlpha(.45f);
        manageButton.setOnClickListener(v -> showDeleteOptions(selectedItems()));
        root.addView(manageButton, new LinearLayout.LayoutParams(-1, dp(48)));

        list = new RecyclerView(this);
        list.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        list.setClipToPadding(false);
        list.setPadding(0, dp(12), 0, dp(18));
        adapter = new ReceivedAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private void reload() {
        List<Item> loaded = loadItems();
        Set<String> hidden = hiddenKeys();
        items.clear();
        for (Item item : loaded) if (!hidden.contains(item.key())) items.add(item);
        selected.retainAll(itemKeys(items));
        updateHeader();
        adapter.notifyDataSetChanged();
    }

    private void updateHeader() {
        long total = 0L;
        for (Item item : items) total += item.size;
        summary.setText(getString(R.string.received_files_summary, items.size(), human(total)));
        int count = selected.size();
        manageButton.setText(count == 0 ? getString(R.string.select_received_files)
                : getString(R.string.delete_selected_files, count));
        manageButton.setEnabled(count > 0);
        manageButton.setAlpha(count > 0 ? 1f : .45f);
    }

    private Set<String> itemKeys(List<Item> values) {
        Set<String> keys = new HashSet<>();
        for (Item item : values) keys.add(item.key());
        return keys;
    }

    private List<Item> selectedItems() {
        List<Item> result = new ArrayList<>();
        for (Item item : items) if (selected.contains(item.key())) result.add(item);
        return result;
    }

    private void toggle(Item item) {
        if (!selected.add(item.key())) selected.remove(item.key());
        updateHeader();
        adapter.notifyDataSetChanged();
    }

    private final class ReceivedAdapter extends RecyclerView.Adapter<ReceivedHolder> {
        @NonNull @Override public ReceivedHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout outer = new LinearLayout(ReceivedFilesActivity.this);
            outer.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams outerLp = new RecyclerView.LayoutParams(-1, -2);
            outerLp.setMargins(0, 0, 0, dp(10));
            outer.setLayoutParams(outerLp);
            outer.setPadding(dp(15), dp(14), dp(15), dp(14));
            outer.setBackground(vivid(new int[]{Color.rgb(24,64,95),Color.rgb(12,38,67),Color.rgb(34,24,74)},22));if (Build.VERSION.SDK_INT >= 21) outer.setElevation(dp(8));

            LinearLayout top = new LinearLayout(ReceivedFilesActivity.this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(ReceivedFilesActivity.this);
            icon.setPadding(dp(14), dp(14), dp(14), dp(14));
            GradientDrawable iconHalo=vivid(new int[]{Color.rgb(82,211,255),Color.rgb(43,120,230),Color.rgb(115,56,218)},30);iconHalo.setShape(GradientDrawable.OVAL);icon.setBackground(iconHalo);
            top.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

            LinearLayout info = new LinearLayout(ReceivedFilesActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPaddingRelative(dp(12), 0, dp(8), 0);
            TextView name = text("", 14, Color.WHITE, true);
            name.setMaxLines(2);
            TextView meta = text("", 11, Color.rgb(139, 177, 205), false);
            info.addView(name);
            info.addView(meta);
            top.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
            CheckBox check = new CheckBox(ReceivedFilesActivity.this);
            top.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48)));
            outer.addView(top);

            LinearLayout actions = new LinearLayout(ReceivedFilesActivity.this);
            actions.setPadding(0, dp(12), 0, 0);
            Button open = button("Open");
            Button share = button("Share");
            Button delete = button("Delete");
            actions.addView(open, actionLp(false));
            actions.addView(share, actionLp(true));
            actions.addView(delete, actionLp(true));
            outer.addView(actions);
            return new ReceivedHolder(outer, icon, name, meta, check, open, share, delete);
        }

        @Override public void onBindViewHolder(@NonNull ReceivedHolder holder, int position) {
            Item item = items.get(position);
            holder.name.setText(item.name);
            String when = item.modifiedMs <= 0 ? getString(R.string.unknown_time) : DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT).format(new Date(item.modifiedMs));
            holder.meta.setText(UiText.get(ReceivedFilesActivity.this,
                    categoryFor(item.mime) + " • " + human(item.size) + " • " + when));
            holder.icon.setImageResource(iconFor(item.mime));
            holder.icon.setColorFilter(Color.WHITE);
            holder.check.setOnCheckedChangeListener(null);
            holder.check.setChecked(selected.contains(item.key()));
            holder.check.setOnCheckedChangeListener((button, checked) -> toggle(item));
            holder.itemView.setOnClickListener(v -> { holder.itemView.animate().scaleX(.975f).scaleY(.975f).setDuration(90).withEndAction(() -> holder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(150).start()).start(); toggle(item); });
            holder.check.setOnClickListener(v -> { });
            boolean actionable = itemUri(item) != null;
            holder.open.setEnabled(actionable);
            holder.share.setEnabled(actionable);
            holder.open.setAlpha(actionable ? 1f : .45f);
            holder.share.setAlpha(actionable ? 1f : .45f);
            holder.open.setOnClickListener(v -> openItem(item));
            holder.share.setOnClickListener(v -> shareItem(item));
            holder.delete.setOnClickListener(v -> showDeleteOptions(Collections.singletonList(item)));
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private final class ReceivedHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView meta;
        final CheckBox check;
        final Button open;
        final Button share;
        final Button delete;

        ReceivedHolder(View view, ImageView icon, TextView name, TextView meta, CheckBox check,
                       Button open, Button share, Button delete) {
            super(view);
            this.icon = icon;
            this.name = name;
            this.meta = meta;
            this.check = check;
            this.open = open;
            this.share = share;
            this.delete = delete;
        }
    }

    private LinearLayout.LayoutParams actionLp(boolean margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private void showDeleteOptions(List<Item> targets) {
        if (targets.isEmpty()) return;
        String[] options = {
                getString(R.string.delete_from_device),
                getString(R.string.remove_from_received_list)
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.received_delete_options)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) confirmPermanentDelete(targets);
                    else hideFromList(targets);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmPermanentDelete(List<Item> targets) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.delete_received_title)
                .setMessage(getString(R.string.confirm_multi_delete, targets.size()))
                .setPositiveButton(R.string.delete, (dialog, which) -> deleteItems(targets))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void hideFromList(List<Item> targets) {
        Set<String> hidden = hiddenKeys();
        for (Item item : targets) hidden.add(item.key());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(HIDDEN, new HashSet<>(hidden)).apply();
        selected.clear();
        reload();
        showSimple(getString(R.string.removed_from_list) + "\n\n"
                + getString(R.string.remove_from_received_list_summary));
    }

    private Set<String> hiddenKeys() {
        Set<String> stored = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(HIDDEN, Collections.emptySet());
        return new HashSet<>(stored == null ? Collections.emptySet() : stored);
    }

    private void deleteItems(List<Item> targets) {
        int failed = 0;
        for (Item item : targets) if (!deleteOne(item)) failed++;
        selected.clear();
        reload();
        if (failed > 0) showSimple(getString(R.string.delete_failed_count, failed));
    }

    private boolean deleteOne(Item item) {
        try {
            if (item.uri != null && "content".equals(item.uri.getScheme())) {
                return getContentResolver().delete(item.uri, null, null) > 0;
            }
            if (item.path != null) {
                File file = new File(item.path);
                File root = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "OptiShare");
                String allowed = root.getCanonicalPath() + File.separator;
                return file.getCanonicalPath().startsWith(allowed) && file.isFile() && file.delete();
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
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
                MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED
        };
        String selection = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
        String[] args = {Environment.DIRECTORY_DOWNLOADS + "/OptiShare/%"};
        try (Cursor cursor = getContentResolver().query(collection, projection, selection, args,
                MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return result;
            int id = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID);
            int name = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
            int mime = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE);
            int size = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
            int modified = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED);
            while (cursor.moveToNext() && result.size() < MAX_ITEMS) {
                result.add(new Item(cursor.getString(name), cursor.getString(mime), cursor.getLong(size),
                        cursor.getLong(modified) * 1000L,
                        ContentUris.withAppendedId(collection, cursor.getLong(id)), null));
            }
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private List<Item> loadLegacy() {
        List<Item> result = new ArrayList<>();
        collectLegacy(new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "OptiShare"), result);
        result.sort((a, b) -> Long.compare(b.modifiedMs, a.modifiedMs));
        return result.size() > MAX_ITEMS
                ? new ArrayList<>(result.subList(0, MAX_ITEMS)) : result;
    }

    private void collectLegacy(File file, List<Item> out) {
        if (file == null || !file.exists() || out.size() >= MAX_ITEMS) return;
        if (file.isFile()) {
            out.add(new Item(file.getName(), guessMime(file.getName()), file.length(),
                    file.lastModified(), null, file.getAbsolutePath()));
            return;
        }
        File[] children = file.listFiles();
        if (children != null) for (File child : children) collectLegacy(child, out);
    }

    private void openItem(Item item) {
        Uri uri = itemUri(item);
        if (uri == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception error) {
            showSimple("No app can open this file type.");
        }
    }

    private void shareItem(Item item) {
        Uri uri = itemUri(item);
        if (uri == null) return;
        try {
            Intent send = new Intent(Intent.ACTION_SEND).setType(item.mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share received file"));
        } catch (Exception error) {
            showSimple("This file cannot be shared right now.");
        }
    }

    private Uri itemUri(Item item) {
        if (item == null) return null;
        if (item.uri != null && "content".equals(item.uri.getScheme())) return item.uri;
        if (item.path == null || item.path.trim().isEmpty()) return null;
        try {
            File file = new File(item.path);
            File root = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "OptiShare");
            String allowed = root.getCanonicalPath() + File.separator;
            if (!file.isFile() || !file.getCanonicalPath().startsWith(allowed)) return null;
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void showSimple(String message) {
        new android.app.AlertDialog.Builder(this).setTitle("OptiShare")
                .setMessage(UiText.get(this, message))
                .setPositiveButton(R.string.ok, null).show();
    }

    private Button button(String label) {
        Button value = new Button(this);
        value.setText(UiText.get(this, label));
        value.setTextColor(Color.WHITE);
        value.setTextSize(12);
        value.setAllCaps(false);
        value.setBackground(vivid(new int[]{Color.rgb(55,211,255),Color.rgb(36,132,244),Color.rgb(126,62,232)},18));if (Build.VERSION.SDK_INT >= 21) value.setElevation(dp(5));
        press(value);
        return value;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(UiText.get(this, value));
        view.setTextColor(Appearance.text(this, color));
        view.setTextSize(size);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private GradientDrawable vivid(int[] colors, int radiusDp) { GradientDrawable value=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors); value.setCornerRadius(dp(radiusDp)); value.setStroke(dp(1),Color.argb(90,255,255,255)); return value; }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(color);
        value.setCornerRadius(dp(radiusDp));
        return value;
    }

    private void press(View view) { view.setOnTouchListener((v,e) -> { if(e.getAction()==android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.96f).scaleY(.96f).translationY(dp(3)).setDuration(75).start(); else if(e.getAction()==android.view.MotionEvent.ACTION_UP||e.getAction()==android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(170).start(); return false; }); }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int iconFor(String mime) {
        String category = categoryFor(mime);
        switch (category) {
            case "Photo": return R.drawable.ic_os_photo;
            case "Video": return R.drawable.ic_os_video;
            case "Music": return R.drawable.ic_os_music;
            case "App": return R.drawable.ic_os_apps;
            case "Document": return R.drawable.ic_os_document;
            case "Archive": return R.drawable.ic_os_folder;
            default: return R.drawable.ic_os_more;
        }
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
