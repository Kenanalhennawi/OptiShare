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
        public final long durationMs;

        public MediaItem(Uri uri, String name, long size, long dateAdded) {
            this(uri, name, size, dateAdded, 0L);
        }

        public MediaItem(Uri uri, String name, long size, long dateAdded, long durationMs) {
            this.uri = uri;
            this.name = name == null ? "media" : name;
            this.size = Math.max(0, size);
            this.dateAdded = Math.max(0, dateAdded);
            this.durationMs = Math.max(0, durationMs);
        }
    }

    private final Context context;

    public MediaRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /** type is image, video or audio. */
    public List<MediaItem> load(String type, int limit, int offset) {
        List<MediaItem> result = new ArrayList<>();
        if (limit <= 0) return result;
        limit = Math.min(limit, 2000);
        offset = Math.max(0, offset);

        Uri collection;
        boolean video = "video".equals(type);
        boolean audio = "audio".equals(type);
        if (audio) collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        else if (video) collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        else collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String[] projection;
        if (video) {
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.Video.Media.DURATION
            };
        } else if (audio) {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DURATION
            };
        } else {
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED
            };
        }

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
            int nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
            int durationCol = -1;
            if (video) durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
            else if (audio) durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);

            int skipped = 0;
            while (cursor.moveToNext()) {
                if (skipped++ < offset) continue;
                long id = cursor.getLong(idCol);
                String name = nameCol < 0 || cursor.isNull(nameCol)
                        ? "media" : cursor.getString(nameCol);
                long size = sizeCol < 0 || cursor.isNull(sizeCol) ? 0L : cursor.getLong(sizeCol);
                long date = dateCol < 0 || cursor.isNull(dateCol) ? 0L : cursor.getLong(dateCol);
                long duration = durationCol < 0 || cursor.isNull(durationCol)
                        ? 0L : cursor.getLong(durationCol);
                result.add(new MediaItem(
                        ContentUris.withAppendedId(collection, id),
                        name,
                        size,
                        date,
                        duration));
                if (result.size() >= limit) break;
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }
}
