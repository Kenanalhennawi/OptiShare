package com.kenan.optishare.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable snapshot of a resumable transfer session. */
public final class ResumeState {
    private final String sessionId;
    private final long updatedAtMillis;
    private final Map<String, Long> confirmedOffsets;

    public ResumeState(String sessionId, long updatedAtMillis, Map<String, Long> confirmedOffsets) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        this.sessionId = sessionId;
        this.updatedAtMillis = updatedAtMillis;
        this.confirmedOffsets = Collections.unmodifiableMap(new LinkedHashMap<>(confirmedOffsets));
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    public Map<String, Long> getConfirmedOffsets() {
        return confirmedOffsets;
    }

    public long getConfirmedOffset(String fileId) {
        Long value = confirmedOffsets.get(fileId);
        return value == null ? 0L : Math.max(0L, value);
    }

    public ResumeState withConfirmedOffset(String fileId, long offset, long nowMillis) {
        if (fileId == null || fileId.trim().isEmpty()) {
            throw new IllegalArgumentException("fileId is required");
        }
        if (offset < 0L) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        Map<String, Long> next = new LinkedHashMap<>(confirmedOffsets);
        long current = getConfirmedOffset(fileId);
        if (offset < current) {
            throw new IllegalArgumentException("confirmed offset cannot move backwards");
        }
        next.put(fileId, offset);
        return new ResumeState(sessionId, nowMillis, next);
    }
}
