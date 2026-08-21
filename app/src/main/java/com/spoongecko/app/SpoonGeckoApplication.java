package com.spoongecko.app;

import android.app.Application;
import android.content.Context;

import com.google.android.material.color.DynamicColors;

import org.mozilla.geckoview.GeckoRuntime;

public final class SpoonGeckoApplication extends Application {

    private static volatile Context appContext;
    private static volatile GeckoRuntime runtime;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }

    public static Context getAppContext() {
        return appContext;
    }

    static void setAppContext(Context context) {
        if (appContext == null && context != null) {
            appContext = context.getApplicationContext();
        }
    }

    public static GeckoRuntime getRuntime() {
        return runtime;
    }

    static void setRuntime(GeckoRuntime value) {
        runtime = value;
    }
}
