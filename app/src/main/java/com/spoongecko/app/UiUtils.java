package com.spoongecko.app;

import android.content.Context;

public final class UiUtils {

    private UiUtils() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
