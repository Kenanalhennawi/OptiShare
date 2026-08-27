package com.kenan.optishare;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicInteger;

public final class OptiShareApp extends Application {
    private static volatile Context appContext;
    private static final AtomicInteger startedActivities = new AtomicInteger();

    @Override public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {
                startedActivities.incrementAndGet();
            }

            @Override public void onActivityStopped(Activity activity) {
                startedActivities.updateAndGet(value -> Math.max(0, value - 1));
            }

            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    public static Context context() {
        Context value = appContext;
        if (value == null) throw new IllegalStateException("OptiShare application not initialized");
        return value;
    }

    public static boolean isForeground() {
        return startedActivities.get() > 0;
    }
}
