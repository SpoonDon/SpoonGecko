package com.spoongecko.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * Persists the last visited URL so a cold start after a kill lands the user
 * back where they were.
 *
 * Deliberately tiny: this is not a tab store yet, it is a crash/OOM recovery
 * breadcrumb. Phase 2 replaces it with a real tab list.
 */
public final class SessionStore {

    private static final String PREFS = "spoongecko_session";
    private static final String KEY_LAST_URL = "last_url";

    private SessionStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context context, String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        if (url.startsWith("about:")) {
            return;
        }
        prefs(context).edit().putString(KEY_LAST_URL, url).apply();
    }

    public static String load(Context context) {
        return prefs(context).getString(KEY_LAST_URL, null);
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }
}
