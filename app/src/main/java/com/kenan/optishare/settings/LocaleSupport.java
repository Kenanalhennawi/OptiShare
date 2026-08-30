package com.kenan.optishare.settings;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

public final class LocaleSupport {
    private LocaleSupport() { }

    public static Context wrap(Context context) {
        String language = new AppSettings(context).language();
        if (AppSettings.LANGUAGE_SYSTEM.equals(language)) return context;
        Locale locale = Locale.forLanguageTag(language);
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return context.createConfigurationContext(configuration);
    }
}
