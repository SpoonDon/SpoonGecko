package com.spoongecko.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * [H2] Tab persistence.
 *
 * The old static implementation serialized every tab (Parcel -> Base64 ->
 * JSON -> SharedPreferences) synchronously on the calling thread — which was
 * the main thread, on every onSessionStateChange (navigation, scrolling, form
 * data...). Under load this froze the UI and risked ANRs.
 *
 * The replacement:
 *   - scheduleSave(): debounced (500 ms), coalesces the state-change storm.
 *   - persistNow():  takes a cheap snapshot on the caller thread, then does
 *                    all marshalling/writing on a single background thread.
 *   - load():        still synchronous and called once at startup.
 *
 * [M5] The saved "active" index is now the index within the *saved array*
 * (tabs without a session state are skipped), so restoring selects the
 * tab the user actually had open instead of drifting past the end.
 */
public final class TabPersistence {

    private static final String TAG = "SpoonGecko";
    private static final String PREFS_NAME = "spoongecko_prefs";
    private static final String PREF_TABS = "tabs_json";
    private static final long SAVE_DEBOUNCE_MS = 500L;

    public static final class Restored {
        public final List<GeckoSession.SessionState> states;
        public final int activeIndex;
        public Restored(List<GeckoSession.SessionState> states, int activeIndex) {
            this.states = states;
            this.activeIndex = activeIndex;
        }
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spoongecko-tab-persist");
        t.setDaemon(true);
        return t;
    });

    private Runnable pendingSave;
    private boolean shutdown;

    public TabPersistence(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    /** Debounced save — safe to call from onSessionStateChange storms. */
    public void scheduleSave(TabManager tabManager) {
        if (shutdown) return;
        if (pendingSave != null) main.removeCallbacks(pendingSave);
        pendingSave = () -> {
            pendingSave = null;
            persistNow(tabManager);
        };
        main.postDelayed(pendingSave, SAVE_DEBOUNCE_MS);
    }

    /** Immediate save — snapshot on the caller thread, heavy work on IO. */
    public void persistNow(TabManager tabManager) {
        if (shutdown || tabManager == null) return;
        final List<GeckoSession.SessionState> states = new ArrayList<>();
        final List<Tab> tabs = tabManager.getTabs();
        final int active = tabManager.getActiveIndex();

        int savedIndex = 0;
        int savedActive = 0;
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            if (t.latestState == null) continue;          // not saved: index shifts
            if (i == active) savedActive = savedIndex;    // [M5] index in saved array
            states.add(t.latestState);
            savedIndex++;
        }

        final int activeToWrite = savedActive;
        io.execute(() -> write(states, activeToWrite));
    }

    private void write(List<GeckoSession.SessionState> states, int active) {
        try {
            JSONArray arr = new JSONArray();
            for (GeckoSession.SessionState s : states) {
                Parcel p = Parcel.obtain();
                try {
                    s.writeToParcel(p, 0);
                    JSONObject obj = new JSONObject();
                    obj.put("s", Base64.encodeToString(p.marshall(), Base64.NO_WRAP));
                    arr.put(obj);
                } finally {
                    p.recycle();
                }
            }
            JSONObject root = new JSONObject();
            root.put("active", active);
            root.put("tabs", arr);
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_TABS, root.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "persistTabs failed", e);
        }
    }

    /** Called once at startup; synchronous by design. */
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
                } finally {
                    p.recycle();
                }
            }
            activeIndex = root.optInt("active", 0);
        } catch (Exception e) {
            Log.w(TAG, "loadTabs failed", e);
        }
        return new Restored(out, activeIndex);
    }

    /** Stops accepting new saves and lets queued writes drain. */
    public void shutdown() {
        shutdown = true;
        if (pendingSave != null) {
            main.removeCallbacks(pendingSave);
            pendingSave = null;
        }
        io.shutdown();
        try {
            // Best effort: don't hang teardown on a slow write.
            io.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
