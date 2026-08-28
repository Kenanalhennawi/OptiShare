package com.kenan.optishare.storage;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class ReceivedRepository {
    public static final class Item {
        public final Uri uri;
        public final String name;
        public final long size;
        public final long modified;
        public final String mime;
        public final String category;

        Item(Uri uri, String name, long size, long modified, String mime, String category) {
            this.uri = uri;
            this.name = name == null ? "file" : name;
            this.size = Math.max(0L, size);
            this.modified = Math.max(0L, modified);
            this.mime = mime == null ? "application/octet-stream" : mime;
            this.category = category == null ? "Other" : category;
        }
    }

    private final Context context;

    public ReceivedRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<Item> load() {
        List<Item> result = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? loadMediaStore()
                : loadLegacy();
        Collections.sort(result, Comparator.comparingLong((Item i) -> i.modified).reversed());
        return result;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private List<Item> loadMediaStore() {
        List<Item> result = new ArrayList<>();
        String[] projection = {
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.RELATIVE_PATH
        };
        String selection = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
        String[] args = {Environment.DIRECTORY_DOWNLOADS + "/OptiShare/%"};
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return result;
            int id = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID);
            int name = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME);
            int size = cursor.getColumnIndex(MediaStore.Downloads.SIZE);
            int modified = cursor.getColumnIndex(MediaStore.Downloads.DATE_MODIFIED);
            int mime = cursor.getColumnIndex(MediaStore.Downloads.MIME_TYPE);
            int path = cursor.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH);
            while (cursor.moveToNext()) {
                String relative = path >= 0 && !cursor.isNull(path) ? cursor.getString(path) : "";
                result.add(new Item(
                        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(id)),
                        name >= 0 && !cursor.isNull(name) ? cursor.getString(name) : "file",
                        size >= 0 && !cursor.isNull(size) ? cursor.getLong(size) : 0L,
                        modified >= 0 && !cursor.isNull(modified) ? cursor.getLong(modified) * 1000L : 0L,
                        mime >= 0 && !cursor.isNull(mime) ? cursor.getString(mime) : "application/octet-stream",
                        categoryFromPath(relative)));
            }
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private List<Item> loadLegacy() {
        List<Item> result = new ArrayList<>();
        File root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OptiShare");
        scanLegacy(root, result);
        return result;
    }

    private static void scanLegacy(File file, List<Item> out) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) scanLegacy(child, out);
            return;
        }
        String category = file.getParentFile() == null ? "Other" : file.getParentFile().getName();
        out.add(new Item(Uri.fromFile(file), file.getName(), file.length(), file.lastModified(), guessMime(file.getName()), category));
    }

    private static String categoryFromPath(String path) {
        if (path == null) return "Other";
        String marker = "OptiShare/";
        int start = path.indexOf(marker);
        if (start < 0) return "Other";
        String tail = path.substring(start + marker.length());
        int slash = tail.indexOf('/');
        String category = slash >= 0 ? tail.substring(0, slash) : tail;
        return category.isEmpty() ? "Other" : category;
    }

    private static String guessMime(String name) {
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }
}
