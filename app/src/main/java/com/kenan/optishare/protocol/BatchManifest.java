package com.kenan.optishare.protocol;

import com.kenan.optishare.model.TransferItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class BatchManifest {
    public static final class Entry {
        public final String id;
        public final String name;
        public final String mime;
        public final long size;
        public final TransferItem.Category category;
        public final byte[] sha256;

        public Entry(String id, String name, String mime, long size, TransferItem.Category category, byte[] sha256) {
            this.id = id;
            this.name = TransferItem.safeName(name);
            this.mime = mime == null ? "application/octet-stream" : mime;
            this.size = size;
            this.category = category == null ? TransferItem.Category.OTHER : category;
            this.sha256 = sha256 == null ? new byte[0] : sha256.clone();
        }
    }

    private final String sessionId;
    private final long createdAt;
    private final List<Entry> entries;

    public BatchManifest(List<Entry> entries) {
        this(UUID.randomUUID().toString(), System.currentTimeMillis(), entries);
    }

    public BatchManifest(String sessionId, long createdAt, List<Entry> entries) {
        if (sessionId == null || sessionId.trim().isEmpty()) throw new IllegalArgumentException("sessionId");
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries == null ? Collections.emptyList() : entries));
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
