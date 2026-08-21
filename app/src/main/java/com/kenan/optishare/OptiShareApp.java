package com.kenan.optishare;

import android.app.Application;
import android.content.Context;

public final class OptiShareApp extends Application {
    private static volatile Context appContext;

    @Override public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }

    public static Context context() {
        Context value = appContext;
        if (value == null) throw new IllegalStateException("OptiShare application not initialized");
        return value;
    }
}
