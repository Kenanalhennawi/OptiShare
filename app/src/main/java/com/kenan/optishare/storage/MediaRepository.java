package com.kenan.optishare.storage;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

public final class MediaRepository {
    public static final class MediaItem {
        public final Uri uri;
        public final String name;
        public final long size;
        public final long dateAdded;

        public MediaItem(Uri uri, String name, long size, long dateAdded) {
            this.uri = uri;
            this.name = name == null ? "media" : name;
            this.size = Math.max(0, size);
            this.dateAdded = dateAdded;
        }
    }

    private final Context context;

    public MediaRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<MediaItem> load(String type, int limit, int offset) {
        List<MediaItem> result = new ArrayList<>();
        Uri collection = "video".equals(type)
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED
        };
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    collection,
                    projection,
                    null,
                    null,
                    MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (cursor == null) return result;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            int skipped = 0;
            while (cursor.moveToNext()) {
                if (skipped++ < offset) continue;
                long id = cursor.getLong(idCol);
                String name = cursor.isNull(nameCol) ? "media" : cursor.getString(nameCol);
                long size = cursor.isNull(sizeCol) ? 0 : cursor.getLong(sizeCol);
                long date = cursor.isNull(dateCol) ? 0 : cursor.getLong(dateCol);
                result.add(new MediaItem(ContentUris.withAppendedId(collection, id), name, size, date));
                if (result.size() >= limit) break;
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }
}
