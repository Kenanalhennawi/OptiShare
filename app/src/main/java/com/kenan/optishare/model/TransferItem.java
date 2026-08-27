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
    private final String relativePath;

    public TransferItem(Uri uri, String name, String mimeType, long size, Category category) {
        this(UUID.randomUUID().toString(), uri, name, mimeType, size, category, null);
    }

    public TransferItem(Uri uri, String name, String mimeType, long size, Category category,
                        String relativePath) {
        this(UUID.randomUUID().toString(), uri, name, mimeType, size, category, relativePath);
    }

    public TransferItem(String id, Uri uri, String name, String mimeType, long size,
                        Category category) {
        this(id, uri, name, mimeType, size, category, null);
    }

    public TransferItem(String id, Uri uri, String name, String mimeType, long size,
                        Category category, String relativePath) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("id");
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("name");
        if (size < 0) throw new IllegalArgumentException("size");
        this.id = id;
        this.uri = uri;
        this.name = name;
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.size = size;
        this.category = category == null ? Category.OTHER : category;
        this.relativePath = safeRelativePath(relativePath);
    }

    public String getId() { return id; }
    public Uri getUri() { return uri; }
    public String getName() { return name; }
    public String getMimeType() { return mimeType; }
    public long getSize() { return size; }
    public Category getCategory() { return category; }
    public String getRelativePath() { return relativePath; }

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

    public static String safeRelativePath(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String normalized = raw.replace('\\', '/').trim();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        String[] parts = normalized.split("/");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
                throw new IllegalArgumentException("Invalid relative path");
            }
            String safe = safeName(value);
            if (safe.length() > 255) safe = safe.substring(0, 255);
            if (out.length() > 0) out.append('/');
            out.append(safe);
            if (out.length() > 2048) throw new IllegalArgumentException("Relative path too long");
        }
        return out.length() == 0 ? null : out.toString();
    }

    public static String normalizedExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }
}
