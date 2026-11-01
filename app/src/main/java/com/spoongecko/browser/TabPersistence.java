package com.spoongecko.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
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
 * [H2] Tab persistence — format 2.
 *
 * Session states are serialized with the DOCUMENTED pair
 * SessionState.toString() / SessionState.fromString() — the same pair Firefox
 * and android-components use.
 *
 * The previous implementation wrote SessionState into a Parcel and stored
 * Base64(bytes). That round-trip is BROKEN on the pinned GeckoView
 * (158.0.20261007201254, verified in the AAR): writeToParcel() writes ONE
 * string, but SessionState.CREATOR.createFromParcel() calls readString()
 * TWICE (a null-check that consumes the value, then the real read). The
 * second read runs past the parcel end and throws, load() swallowed the
 * exception, and every restore silently returned an empty list — so the app
 * always reopened to a fresh startup page after the process was killed.
 *
 * The stored JSON now also keeps each tab's URL and title, so a restored tab
 * immediately shows the right address in the URL bar (and the home panel
 * stays out of the way). Tabs without a session state yet (fresh tabs) are
 * stored with an empty state, so they survive as empty tabs instead of
 * disappearing.
 *
 * Writing still happens on one shared background thread: persistNow() takes
 * its snapshot on the caller thread and marshals off-thread.
 */
public final class TabPersistence {

    private static final String TAG = "SpoonGecko";
    private static final String PREFS_NAME = "spoongecko_prefs";
    private static final String PREF_TABS = "tabs_json";
    private static final long SAVE_DEBOUNCE_MS = 500L;

    /** Single shared writer thread for the whole process. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spoongecko-tab-persist");
        t.setDaemon(true);
        return t;
    });

    public static final class Restored {
        public final List<GeckoSession.SessionState> states;
        public final List<String> urls;
        public final List<String> titles;
        public final int activeIndex;

        public Restored(List<GeckoSession.SessionState> states, List<String> urls,
                        List<String> titles, int activeIndex) {
            this.states = states;
            this.urls = urls;
            this.titles = titles;
            this.activeIndex = activeIndex;
        }
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Runnable pendingSave;
    private volatile boolean shutdown;

    public TabPersistence(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public static void save(Context ctx, TabManager tabManager) {
        new TabPersistence(ctx).persistNow(tabManager);
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
        final List<String> urls = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        final List<Tab> tabs = tabManager.getTabs();
        final int active = tabManager.getActiveIndex();

        int savedIndex = 0;
        int savedActive = 0;
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            if (i == active) savedActive = savedIndex;    // [M5] index in saved array
            states.add(t.latestState);                    // null allowed -> empty tab
            urls.add(t.url == null ? "" : t.url);
            titles.add(t.title == null ? "" : t.title);
            savedIndex++;
        }

        final int activeToWrite = savedActive;
        IO.execute(() -> write(states, urls, titles, activeToWrite));
    }

    private void write(List<GeckoSession.SessionState> states,
                       List<String> urls, List<String> titles, int active) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < states.size(); i++) {
                GeckoSession.SessionState s = states.get(i);
                JSONObject obj = new JSONObject();
                // toString() is the documented serialization; it can return
                // null for an empty/failed state, in which case JSONObject
                // drops the "s" key — restore then yields an empty tab.
                obj.put("s", s != null ? s.toString() : null);
                obj.put("u", urls.get(i));
                obj.put("t", titles.get(i));
                arr.put(obj);
            }
            JSONObject root = new JSONObject();
            root.put("format", 2);
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
        List<String> urls = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        int activeIndex = 0;
        try {
            String raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_TABS, null);
            if (raw == null) return new Restored(out, urls, titles, activeIndex);
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.getJSONArray("tabs");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String s = o.optString("s", "");
                GeckoSession.SessionState state = null;
                if (s != null && !s.isEmpty()) {
                    try {
                        // fromString() — NOT the broken Parcelable CREATOR.
                        state = GeckoSession.SessionState.fromString(s);
                    } catch (Exception e) {
                        Log.w(TAG, "session state parse failed", e);
                    }
                }
                out.add(state);                              // null -> empty tab
                urls.add(o.optString("u", ""));
                titles.add(o.optString("t", ""));
            }
            activeIndex = root.optInt("active", 0);
        } catch (Exception e) {
            Log.w(TAG, "loadTabs failed", e);
        }
        return new Restored(out, urls, titles, activeIndex);
    }

    /** Stops accepting new saves and lets queued writes drain. */
    public void shutdown() {
        shutdown = true;
        if (pendingSave != null) {
            main.removeCallbacks(pendingSave);
            pendingSave = null;
        }
        IO.shutdown();
        try {
            // Best effort: don't hang teardown on a slow write.
            IO.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
