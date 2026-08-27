package com.kenan.optishare.protocol;

import com.kenan.optishare.model.TransferItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable authenticated metadata describing one logical multi-file transfer. */
public final class BatchManifest {
    public static final int MAX_ENTRIES = 10_000;

    public static final class Entry {
        public final String id;
        public final String name;
        public final String mime;
        public final long size;
        public final TransferItem.Category category;
        public final String relativePath;
        public final byte[] sha256;

        public Entry(String id, String name, String mime, long size,
                     TransferItem.Category category, byte[] sha256) {
            this(id, name, mime, size, category, null, sha256);
        }

        public Entry(String id, String name, String mime, long size,
                     TransferItem.Category category, String relativePath, byte[] sha256) {
            if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("file id required");
            if (id.length() > 512) throw new IllegalArgumentException("file id too long");
            if (size < 0) throw new IllegalArgumentException("file size must be >= 0");
            if (sha256 == null || sha256.length != 32) {
                throw new IllegalArgumentException("SHA-256 digest must be exactly 32 bytes");
            }
            String safeName = TransferItem.safeName(name);
            if (safeName.length() > 255) {
                safeName = safeName.substring(0, 255);
            }
            String safeMime = mime == null || mime.trim().isEmpty()
                    ? "application/octet-stream" : mime.trim();
            if (safeMime.length() > 255) throw new IllegalArgumentException("MIME type too long");

            this.id = id;
            this.name = safeName;
            this.mime = safeMime;
            this.size = size;
            this.category = category == null ? TransferItem.Category.OTHER : category;
            this.relativePath = TransferItem.safeRelativePath(relativePath);
            this.sha256 = sha256.clone();
        }
    }

    private final String sessionId;
    private final long createdAt;
    private final List<Entry> entries;

    public BatchManifest(List<Entry> entries) {
        this(UUID.randomUUID().toString(), System.currentTimeMillis(), entries);
    }

    public BatchManifest(String sessionId, long createdAt, List<Entry> entries) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId required");
        }
        if (sessionId.length() > 512) throw new IllegalArgumentException("sessionId too long");
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("batch cannot be empty");
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("too many batch entries");

        Set<String> ids = new HashSet<>();
        ArrayList<Entry> copy = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (entry == null) throw new IllegalArgumentException("null batch entry");
            if (!ids.add(entry.id)) throw new IllegalArgumentException("duplicate file id");
            copy.add(entry);
        }

        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.entries = Collections.unmodifiableList(copy);
    }

    public String getSessionId() { return sessionId; }
    public long getCreatedAt() { return createdAt; }
    public List<Entry> getEntries() { return entries; }

    public long totalBytes() {
        long total = 0;
        for (Entry e : entries) {
            if (Long.MAX_VALUE - total < e.size) return Long.MAX_VALUE;
            total += e.size;
        }
        return total;
    }
}
