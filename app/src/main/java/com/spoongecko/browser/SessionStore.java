package com.spoongecko.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the open tab list across cold starts.
 *
 * Old single-URL sessions are migrated transparently: if we find the legacy
 * "last_url" key and no "open_tabs" key, we surface the legacy URL as a
 * one-element tab list.
 */
public final class SessionStore {

    private static final String PREFS = "spoon_gecko_session";
    private static final String KEY_TABS = "open_tabs";
    private static final String KEY_CURRENT = "current_tab";
    private static final String LEGACY_KEY_URL = "last_url";

    private SessionStore() {}

    public static void save(Context ctx, List<PersistedTab> tabs, int currentIndex) {
        JSONArray arr = new JSONArray();
        if (tabs != null) {
            for (PersistedTab t : tabs) {
                try { arr.put(t.toJson()); } catch (JSONException ignored) {}
            }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit()
           .putString(KEY_TABS, arr.toString())
           .putInt(KEY_CURRENT, Math.max(0, currentIndex))
           .apply();
    }

    public static List<PersistedTab> loadTabs(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<PersistedTab> out = new ArrayList<>();

        String raw = sp.getString(KEY_TABS, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    out.add(PersistedTab.fromJson(arr.getJSONObject(i)));
                }
            } catch (JSONException ignored) {}
        }

        // One-shot migration from the pre-multitab format.
        if (out.isEmpty()) {
            String legacy = sp.getString(LEGACY_KEY_URL, null);
            if (legacy != null && !legacy.isEmpty()) {
                out.add(new PersistedTab(legacy, ""));
            }
        }
        return out;
    }

    public static int loadCurrentIndex(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_CURRENT, 0);
    }

    public static void clear(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
