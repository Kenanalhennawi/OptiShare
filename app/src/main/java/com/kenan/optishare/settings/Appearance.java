package com.kenan.optishare.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/** Shared palette decisions for programmatic Android views. */
public final class Appearance {
    private Appearance() { }

    public static boolean light(Context context) {
        AppSettings settings = new AppSettings(context);
        if (AppSettings.THEME_LIGHT.equals(settings.theme())) return true;
        if (AppSettings.THEME_DARK.equals(settings.theme())) return false;
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
    }

    public static int background(Context context) { return light(context) ? Color.rgb(244, 248, 252) : Color.rgb(5, 20, 38); }
    public static int surface(Context context) { return light(context) ? Color.WHITE : Color.rgb(12, 42, 69); }
    public static int secondarySurface(Context context) { return light(context) ? Color.rgb(224, 235, 244) : Color.rgb(22, 73, 111); }

    public static int text(Context context, int requested) {
        if (!light(context)) return requested;
        if (requested == Color.WHITE) return Color.rgb(12, 30, 48);
        int luminance = (Color.red(requested) * 299 + Color.green(requested) * 587
                + Color.blue(requested) * 114) / 1000;
        return luminance > 115 ? Color.rgb(67, 88, 107) : requested;
    }
}
