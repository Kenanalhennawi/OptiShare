package com.kenan.optishare.device;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Local trust database keyed only by the peer's persistent cryptographic fingerprint. */
public final class TrustedDeviceStore {
    private static final String PREFS = "optishare_trusted_devices_v1";
    private static final String KEY_TRUSTED = "trusted";
    private final SharedPreferences prefs;

    public TrustedDeviceStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isTrusted(String fingerprint) {
        return fingerprint != null && prefs.getStringSet(KEY_TRUSTED, Collections.emptySet())
                .contains(fingerprint);
    }

    public boolean autoAccept(String fingerprint) {
        return isTrusted(fingerprint) && prefs.getBoolean("auto:" + fingerprint, false);
    }

    public void trust(String fingerprint, String name) {
        if (fingerprint == null || fingerprint.trim().isEmpty()) return;
        Set<String> copy = new java.util.HashSet<>(
                prefs.getStringSet(KEY_TRUSTED, Collections.emptySet()));
        copy.add(fingerprint);
        prefs.edit()
                .putStringSet(KEY_TRUSTED, copy)
                .putString("name:" + fingerprint, cleanName(name, fingerprint))
                .apply();
    }

    public void setAutoAccept(String fingerprint, boolean enabled) {
        if (!isTrusted(fingerprint)) return;
        prefs.edit().putBoolean("auto:" + fingerprint, enabled).apply();
    }

    public void forget(String fingerprint) {
        if (fingerprint == null) return;
        Set<String> copy = new java.util.HashSet<>(
                prefs.getStringSet(KEY_TRUSTED, Collections.emptySet()));
        copy.remove(fingerprint);
        prefs.edit()
                .putStringSet(KEY_TRUSTED, copy)
                .remove("name:" + fingerprint)
                .remove("auto:" + fingerprint)
                .apply();
    }

    public List<Entry> list() {
        List<Entry> result = new ArrayList<>();
        for (String fingerprint : prefs.getStringSet(KEY_TRUSTED, Collections.emptySet())) {
            result.add(new Entry(
                    fingerprint,
                    prefs.getString("name:" + fingerprint,
                            "Device " + DeviceIdentityKey.shortFingerprint(fingerprint)),
                    prefs.getBoolean("auto:" + fingerprint, false)));
        }
        Collections.sort(result, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return result;
    }

    private static String cleanName(String name, String fingerprint) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) clean = "Device " + DeviceIdentityKey.shortFingerprint(fingerprint);
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    public static final class Entry {
        public final String fingerprint;
        public final String name;
        public final boolean autoAccept;
        Entry(String fingerprint, String name, boolean autoAccept) {
            this.fingerprint = fingerprint;
            this.name = name;
            this.autoAccept = autoAccept;
        }
    }
}
