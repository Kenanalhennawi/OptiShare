package com.kenan.optishare.model;

import android.net.Uri;

import java.util.Locale;
import java.util.UUID;

public final class TransferItem {
    public enum Category { PHOTO, VIDEO, MUSIC, APP, DOCUMENT, ARCHIVE, OTHER }

    private final String id;
    private final Uri uri;
    private final String name;
    private final String mimeType;
    private final long size;
    private final Category category;

    public TransferItem(Uri uri, String name, String mimeType, long size, Category category) {
        this(UUID.randomUUID().toString(), uri, name, mimeType, size, category);
    }

    public TransferItem(String id, Uri uri, String name, String mimeType, long size, Category category) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("id");
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("name");
        if (size < 0) throw new IllegalArgumentException("size");
        this.id = id;
        this.uri = uri;
        this.name = name;
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.size = size;
        this.category = category == null ? Category.OTHER : category;
    }

    public String getId() { return id; }
    public Uri getUri() { return uri; }
    public String getName() { return name; }
    public String getMimeType() { return mimeType; }
    public long getSize() { return size; }
    public Category getCategory() { return category; }

    public String categoryFolder() {
        switch (category) {
            case PHOTO: return "Photos";
            case VIDEO: return "Videos";
            case MUSIC: return "Music";
            case APP: return "Apps";
            case DOCUMENT: return "Documents";
            case ARCHIVE: return "Archives";
            default: return "Other";
        }
    }

    public static String safeName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "file.bin";
        String n = raw.replace('/', '_').replace('\\', '_').replace('\u0000', '_').trim();
        return n.isEmpty() ? "file.bin" : n;
    }

    public static String normalizedExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }
}
