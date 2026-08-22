package com.kenan.optishare.device;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.UUID;

public final class DeviceIdentity {
    private static final String PREFS = "optishare_identity_v2";
    private static final String KEY_ID = "device_id";
    private static final String KEY_NAME = "device_name";
    private final SharedPreferences prefs;

    public DeviceIdentity(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_ID)) {
            prefs.edit().putString(KEY_ID, UUID.randomUUID().toString()).apply();
        }
        if (!prefs.contains(KEY_NAME)) {
            String model = Build.MODEL == null || Build.MODEL.trim().isEmpty() ? "Android device" : Build.MODEL.trim();
            prefs.edit().putString(KEY_NAME, model).apply();
        }
    }

    public String id() { return prefs.getString(KEY_ID, "unknown"); }
    public String name() { return prefs.getString(KEY_NAME, "Android device"); }

    public void setName(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("Device name cannot be empty");
        if (clean.length() > 48) clean = clean.substring(0, 48);
        prefs.edit().putString(KEY_NAME, clean).apply();
    }
}
