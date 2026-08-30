package com.kenan.optishare.settings;

import android.content.Context;
import android.content.SharedPreferences;

/** Single source of truth for user-controlled, non-secret application preferences. */
public final class AppSettings {
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String LANGUAGE_SYSTEM = "system";
    public static final String LANGUAGE_ARABIC = "ar";
    public static final String LANGUAGE_ENGLISH = "en";
    public static final String DUPLICATE_KEEP_BOTH = "keep_both";
    public static final String DUPLICATE_ASK = "ask";
    public static final String DUPLICATE_SKIP_IDENTICAL = "skip_identical";
    public static final String ROUTE_AUTOMATIC = "automatic";
    public static final String ROUTE_LAN = "lan";
    public static final String ROUTE_DIRECT = "direct";
    public static final String VISIBILITY_RECEIVE_ONLY = "receive_only";
    public static final String VISIBILITY_FIVE_MINUTES = "five_minutes";

    private static final String PREFS = "optishare_user_settings_v1";
    private final SharedPreferences preferences;

    public AppSettings(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String theme() { return string("theme", THEME_SYSTEM); }
    public void setTheme(String value) { put("theme", allowed(value, THEME_SYSTEM, THEME_DARK, THEME_LIGHT)); }
    public String language() { return string("language", LANGUAGE_SYSTEM); }
    public void setLanguage(String value) { put("language", allowed(value, LANGUAGE_SYSTEM, LANGUAGE_ARABIC, LANGUAGE_ENGLISH)); }
    public String avatar() { return string("avatar", "O"); }
    public void setAvatar(String value) { put("avatar", value == null || value.length() > 4 ? "O" : value); }
    public int avatarSkin() { return integer("avatar_skin", 2, 0, 5); }
    public int avatarHairStyle() { return integer("avatar_hair_style", 1, 0, 4); }
    public int avatarHairColor() { return integer("avatar_hair_color", 1, 0, 5); }
    public int avatarBackground() { return integer("avatar_background", 0, 0, 5); }
    public boolean avatarGlasses() { return bool("avatar_glasses", false); }
    public boolean avatarBeard() { return bool("avatar_beard", false); }
    public void setAvatarDesign(int skin, int hairStyle, int hairColor, int background,
                                boolean glasses, boolean beard) {
        preferences.edit().putInt("avatar_skin", clamp(skin, 0, 5))
                .putInt("avatar_hair_style", clamp(hairStyle, 0, 4))
                .putInt("avatar_hair_color", clamp(hairColor, 0, 5))
                .putInt("avatar_background", clamp(background, 0, 5))
                .putBoolean("avatar_glasses", glasses).putBoolean("avatar_beard", beard).apply();
    }
    public String duplicatePolicy() { return string("duplicate_policy", DUPLICATE_KEEP_BOTH); }
    public void setDuplicatePolicy(String value) { put("duplicate_policy", allowed(value, DUPLICATE_KEEP_BOTH, DUPLICATE_ASK, DUPLICATE_SKIP_IDENTICAL)); }
    public String preferredRoute() { return string("preferred_route", ROUTE_AUTOMATIC); }
    public void setPreferredRoute(String value) { put("preferred_route", allowed(value, ROUTE_AUTOMATIC, ROUTE_LAN, ROUTE_DIRECT)); }
    public String visibility() { return string("visibility", VISIBILITY_RECEIVE_ONLY); }
    public void setVisibility(String value) { put("visibility", allowed(value, VISIBILITY_RECEIVE_ONLY, VISIBILITY_FIVE_MINUTES)); }

    public boolean soundEnabled() { return bool("sound", true); }
    public void setSoundEnabled(boolean value) { put("sound", value); }
    public boolean vibrationEnabled() { return bool("vibration", true); }
    public void setVibrationEnabled(boolean value) { put("vibration", value); }
    public boolean completionSound() { return bool("completion_sound", true); }
    public void setCompletionSound(boolean value) { put("completion_sound", value); }
    public boolean requestVibration() { return bool("request_vibration", true); }
    public void setRequestVibration(boolean value) { put("request_vibration", value); }
    public boolean transferNotifications() { return bool("transfer_notifications", true); }
    public void setTransferNotifications(boolean value) { put("transfer_notifications", value); }
    public boolean completionNotifications() { return bool("completion_notifications", true); }
    public void setCompletionNotifications(boolean value) { put("completion_notifications", value); }
    public boolean autoCopyText() { return bool("auto_copy_text", true); }
    public void setAutoCopyText(boolean value) { put("auto_copy_text", value); }
    public boolean openReceivedAfterTransfer() { return bool("open_received", false); }
    public void setOpenReceivedAfterTransfer(boolean value) { put("open_received", value); }
    public boolean allowApkReceive() { return bool("allow_apk", true); }
    public void setAllowApkReceive(boolean value) { put("allow_apk", value); }
    public boolean resumeAfterDisconnect() { return bool("resume_disconnect", true); }
    public void setResumeAfterDisconnect(boolean value) { put("resume_disconnect", value); }
    public boolean continueAfterFileFailure() { return bool("continue_file_failure", true); }
    public void setContinueAfterFileFailure(boolean value) { put("continue_file_failure", value); }
    public boolean smartRoute() { return bool("smart_route", true); }
    public void setSmartRoute(boolean value) { put("smart_route", value); }
    public boolean speedTestLargeFiles() { return bool("speed_test_large", true); }
    public void setSpeedTestLargeFiles(boolean value) { put("speed_test_large", value); }
    public boolean keepHistory() { return bool("keep_history", true); }
    public void setKeepHistory(boolean value) { put("keep_history", value); }
    public boolean highContrast() { return bool("high_contrast", false); }
    public void setHighContrast(boolean value) { put("high_contrast", value); }

    private boolean bool(String key, boolean fallback) { return preferences.getBoolean(key, fallback); }
    private String string(String key, String fallback) { return preferences.getString(key, fallback); }
    private int integer(String key, int fallback, int min, int max) {
        return clamp(preferences.getInt(key, fallback), min, max);
    }
    private void put(String key, boolean value) { preferences.edit().putBoolean(key, value).apply(); }
    private void put(String key, String value) { preferences.edit().putString(key, value).apply(); }

    private static String allowed(String value, String fallback, String... allowed) {
        if (value != null) for (String item : allowed) if (item.equals(value)) return value;
        return fallback;
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
