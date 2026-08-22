package com.kenan.optishare.protocol;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persists confirmed byte offsets so an interrupted transfer can continue after reconnect or app restart. */
public final class ResumeStore {
    private static final String PREFS = "optishare_resume_v2";
    private static final String SEP = "|";
    private final SharedPreferences prefs;

    public ResumeStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized ResumeState load(String sessionId) {
        String prefix = sessionId + SEP;
        Map<String, Long> offsets = new LinkedHashMap<>();
        long updated = prefs.getLong(prefix + "updated", 0L);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix + "file" + SEP)) continue;
            Object value = entry.getValue();
            if (value instanceof Long) {
                offsets.put(key.substring((prefix + "file" + SEP).length()), (Long) value);
            }
        }
        return new ResumeState(sessionId, updated, offsets);
    }

    public synchronized void save(ResumeState state) {
        String prefix = state.getSessionId() + SEP;
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong(prefix + "updated", state.getUpdatedAtMillis());
        for (Map.Entry<String, Long> entry : state.getConfirmedOffsets().entrySet()) {
            editor.putLong(prefix + "file" + SEP + entry.getKey(), entry.getValue());
        }
        if (!editor.commit()) {
            throw new IllegalStateException("Could not persist resume state");
        }
    }

    public synchronized void clear(String sessionId) {
        String prefix = sessionId + SEP;
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
        editor.apply();
    }
}
