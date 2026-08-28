package com.kenan.optishare.transfer;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small per-address admission limiter protecting expensive ECDH handshakes on local networks. */
public final class HandshakeRateLimiter {
    static final int MAX_ATTEMPTS = 12;
    static final long WINDOW_MS = 10_000L;
    private static final int MAX_ADDRESSES = 256;
    private final LinkedHashMap<String, Bucket> buckets = new LinkedHashMap<>(16, 0.75f, true);

    public synchronized boolean allow(String address, long nowMs) {
        if (address == null || address.trim().isEmpty()) return false;
        String key = address.trim();
        Bucket bucket = buckets.get(key);
        if (bucket == null || nowMs < bucket.startedAt || nowMs - bucket.startedAt >= WINDOW_MS) {
            buckets.put(key, new Bucket(nowMs, 1));
            trim(nowMs);
            return true;
        }
        if (bucket.count >= MAX_ATTEMPTS) return false;
        bucket.count++;
        return true;
    }

    private void trim(long nowMs) {
        Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Bucket> entry = iterator.next();
            if (nowMs - entry.getValue().startedAt >= WINDOW_MS) iterator.remove();
        }
        iterator = buckets.entrySet().iterator();
        while (buckets.size() > MAX_ADDRESSES && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static final class Bucket {
        final long startedAt;
        int count;
        Bucket(long startedAt, int count) { this.startedAt = startedAt; this.count = count; }
    }
}
