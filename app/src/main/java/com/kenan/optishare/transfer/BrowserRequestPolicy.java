package com.kenan.optishare.transfer;

import java.util.Locale;
import java.util.Map;

/** Pure request checks for the compatibility HTTP receiver. */
final class BrowserRequestPolicy {
    private BrowserRequestPolicy() { }

    static boolean allowedOrigin(Map<String, String> headers) {
        if (headers == null) return true;
        String origin = value(headers, "origin");
        if (origin == null || origin.isEmpty() || "null".equals(origin)) return true;
        String host = value(headers, "host");
        if (host == null || host.isEmpty()) return false;
        return constantTimeAscii(origin, "http://" + host);
    }

    private static String value(Map<String, String> headers, String key) {
        String direct = headers.get(key);
        if (direct != null) return direct.trim();
        for (Map.Entry<String, String> item : headers.entrySet()) {
            if (item.getKey() != null && key.equals(item.getKey().toLowerCase(Locale.US))) {
                return item.getValue() == null ? null : item.getValue().trim();
            }
        }
        return null;
    }

    private static boolean constantTimeAscii(String left, String right) {
        byte[] a = left.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] b = right.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int diff = a.length ^ b.length;
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) diff |= (i < a.length ? a[i] : 0) ^ (i < b.length ? b[i] : 0);
        return diff == 0;
    }
}
