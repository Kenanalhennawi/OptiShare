package com.kenan.optishare.history;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TransferHistoryStore {
    public static final class Entry {
        public final long time;
        public final String direction;
        public final String peer;
        public final int fileCount;
        public final long totalBytes;
        public final boolean success;
        public final long durationMs;
        public final double averageBytesPerSecond;
        public final String route;
        public final int reconnects;

        public Entry(long time, String direction, String peer, int fileCount, long totalBytes, boolean success) {
            this(time, direction, peer, fileCount, totalBytes, success, 0L, 0d, "unknown", 0);
        }

        public Entry(long time, String direction, String peer, int fileCount, long totalBytes, boolean success,
                     long durationMs, double averageBytesPerSecond, String route, int reconnects) {
            this.time = time;
            this.direction = direction;
            this.peer = peer;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
            this.success = success;
            this.durationMs = Math.max(0L, durationMs);
            this.averageBytesPerSecond = Math.max(0d, averageBytesPerSecond);
            this.route = route == null || route.trim().isEmpty() ? "unknown" : route;
            this.reconnects = Math.max(0, reconnects);
        }
    }

    private static final String PREFS = "optishare_history_v2";
    private static final String KEY = "entries";
    private static final int MAX = 100;
    private final SharedPreferences prefs;

    public TransferHistoryStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void add(Entry entry) {
        List<Entry> entries = new ArrayList<>(load());
        entries.add(0, entry);
        if (entries.size() > MAX) entries = new ArrayList<>(entries.subList(0, MAX));
        JSONArray array = new JSONArray();
        try {
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("time", e.time);
                o.put("direction", e.direction);
                o.put("peer", e.peer);
                o.put("fileCount", e.fileCount);
                o.put("totalBytes", e.totalBytes);
                o.put("success", e.success);
                o.put("durationMs", e.durationMs);
                o.put("averageBytesPerSecond", e.averageBytesPerSecond);
                o.put("route", e.route);
                o.put("reconnects", e.reconnects);
                array.put(o);
            }
        } catch (Exception ignored) { }
        prefs.edit().putString(KEY, array.toString()).apply();
    }

    public synchronized List<Entry> load() {
        String raw = prefs.getString(KEY, "[]");
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                result.add(new Entry(
                        o.optLong("time", 0),
                        o.optString("direction", "unknown"),
                        o.optString("peer", "Unknown device"),
                        o.optInt("fileCount", 0),
                        o.optLong("totalBytes", 0),
                        o.optBoolean("success", false),
                        o.optLong("durationMs", 0),
                        o.optDouble("averageBytesPerSecond", 0d),
                        o.optString("route", "unknown"),
                        o.optInt("reconnects", 0)));
            }
        } catch (Exception ignored) { }
        return Collections.unmodifiableList(result);
    }

    public synchronized void clear() {
        prefs.edit().remove(KEY).apply();
    }
}
