package com.spoongecko.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Parcel;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Synchronous, in-place tab persistence. Behaviour is identical to the previous
 * implementation inside MainActivity: JSON to SharedPreferences using Base64 Parcel
 * marshalling, called from the same threads as before.
 */
public final class TabPersistence {

    private static final String TAG = "SpoonGecko";
    private static final String PREFS_NAME = "spoongecko_prefs";
    private static final String PREF_TABS = "tabs_json";

    public static final class Restored {
        public final List<GeckoSession.SessionState> states;
        public final int activeIndex;
        public Restored(List<GeckoSession.SessionState> states, int activeIndex) {
            this.states = states;
            this.activeIndex = activeIndex;
        }
    }

    private TabPersistence() {}

    public static void save(Context ctx, TabManager tabManager) {
        try {
            JSONArray arr = new JSONArray();
            for (Tab t : tabManager.getTabs()) {
                if (t.latestState == null) continue;
                Parcel p = Parcel.obtain();
                byte[] bytes;
                try {
                    t.latestState.writeToParcel(p, 0);
                    bytes = p.marshall();
                } finally { p.recycle(); }
                JSONObject obj = new JSONObject();
                obj.put("s", Base64.encodeToString(bytes, Base64.NO_WRAP));
                arr.put(obj);
            }
            JSONObject root = new JSONObject();
            root.put("active", tabManager.getActiveIndex());
            root.put("tabs", arr);
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_TABS, root.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "persistTabs failed", e);
        }
    }

    public static Restored load(Context ctx) {
        List<GeckoSession.SessionState> out = new ArrayList<>();
        int activeIndex = 0;
        try {
            String raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_TABS, null);
            if (raw == null) return new Restored(out, activeIndex);
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.getJSONArray("tabs");
            for (int i = 0; i < arr.length(); i++) {
                String b64 = arr.getJSONObject(i).getString("s");
                byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
                Parcel p = Parcel.obtain();
                try {
                    p.unmarshall(bytes, 0, bytes.length);
                    p.setDataPosition(0);
                    out.add(GeckoSession.SessionState.CREATOR.createFromParcel(p));
                } finally { p.recycle(); }
            }
            activeIndex = root.optInt("active", 0);
        } catch (Exception e) {
            Log.w(TAG, "loadTabs failed", e);
        }
        return new Restored(out, activeIndex);
    }
}
