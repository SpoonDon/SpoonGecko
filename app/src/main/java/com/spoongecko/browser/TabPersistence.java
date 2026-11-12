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
import java.util.IdentityHashMap;
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
 *
 * [PERF] Three things keep the write volume down:
 *   - Event-driven saves (page-state / page-stop callbacks, which fire in
 *     bursts) go through saveSoon(): at most one save per SAVE_DEBOUNCE_MS,
 *     always taken from the state at the moment it runs.
 *   - Every save is compared with the previous one; when no tab's state, URL
 *     or title changed (e.g. onPause followed by onStop and onTrimMemory) the
 *     serialization and the SharedPreferences write are skipped entirely.
 *   - When a write does happen, only the tabs whose SessionState object
 *     changed are serialized again; the others reuse the string produced by
 *     the previous write (see {@link #serialized}).
 * Lifecycle saves (pause / stop / exit) still go through save(), which is
 * immediate, so nothing is ever lost when the app is backgrounded.
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

    /** Process-wide instance, so the debounce state is shared by every caller. */
    private static TabPersistence sharedInstance;

    /** What the most recent write was built from; used to skip identical writes. */
    private static volatile Snapshot lastSubmitted;

    /**
     * [PERF] The strings produced by the previous write, keyed by the exact
     * SessionState object they came from. While browsing, only the active
     * tab's state changes, so every other tab can reuse its string instead of
     * being serialized again on each save.
     *
     * Identity-keyed on purpose: the state objects are replaced (never
     * mutated) when a tab changes, and an identity map never evaluates
     * equals()/hashCode() of the state itself. Touched only by the single IO
     * thread, and replaced wholesale at the end of every successful write, so
     * it holds at most one entry per open tab and cannot grow.
     */
    private static IdentityHashMap<GeckoSession.SessionState, String> serialized =
            new IdentityHashMap<>();

    public static final class Restored {
        public final List<GeckoSession.SessionState> states;
        public final List<String> urls;
        public final List<String> titles;
        public final int activeIndex;
        /** Per tab: was "Desktop site" on. Same length as states. */
        public final List<Boolean> desktop;

        public Restored(List<GeckoSession.SessionState> states, List<String> urls,
                        List<String> titles, int activeIndex, List<Boolean> desktop) {
            this.states = states;
            this.urls = urls;
            this.titles = titles;
            this.activeIndex = activeIndex;
            this.desktop = desktop;
        }

        public Restored(List<GeckoSession.SessionState> states, List<String> urls,
                        List<String> titles, int activeIndex) {
            this(states, urls, titles, activeIndex, new ArrayList<Boolean>());
        }
    }

    /** Immutable copy of everything one write consists of. */
    private static final class Snapshot {
        final List<GeckoSession.SessionState> states;
        final List<String> urls;
        final List<String> titles;
        final List<Boolean> desktop;
        final int active;

        Snapshot(List<GeckoSession.SessionState> states, List<String> urls,
                 List<String> titles, List<Boolean> desktop, int active) {
            this.states = states;
            this.urls = urls;
            this.titles = titles;
            this.desktop = desktop;
            this.active = active;
        }

        /**
         * SessionState instances are immutable and replaced (never mutated)
         * when a tab's state changes, so reference equality is exact.
         */
        boolean sameAs(Snapshot o) {
            if (o == null || active != o.active || states.size() != o.states.size()) {
                return false;
            }
            for (int i = 0; i < states.size(); i++) {
                if (states.get(i) != o.states.get(i)) return false;
                if (!urls.get(i).equals(o.urls.get(i))) return false;
                if (!titles.get(i).equals(o.titles.get(i))) return false;
                if (!desktop.get(i).equals(o.desktop.get(i))) return false;
            }
            return true;
        }
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Main thread only.
    private Runnable pendingSave;
    private volatile boolean shutdown;

    public TabPersistence(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    private static synchronized TabPersistence shared(Context ctx) {
        if (sharedInstance == null) sharedInstance = new TabPersistence(ctx);
        return sharedInstance;
    }

    /** Immediate save — use at lifecycle moments (pause, stop, trim, exit). */
    public static void save(Context ctx, TabManager tabManager) {
        shared(ctx).persistNow(tabManager);
    }

    /** Coalesced save — use for event storms (onSessionStateChange, onPageStop). */
    public static void saveSoon(Context ctx, TabManager tabManager) {
        shared(ctx).scheduleSave(tabManager);
    }

    /** Drops a queued coalesced save. Call before the tab set is torn down. */
    public static void cancelPendingSave() {
        final TabPersistence p;
        synchronized (TabPersistence.class) {
            p = sharedInstance;
        }
        if (p != null) p.cancelPending();
    }

    /**
     * Coalesced save: the first call schedules a save SAVE_DEBOUNCE_MS later,
     * further calls in that window are absorbed. The snapshot is taken when
     * the save actually runs, so it always reflects the latest state. Main
     * thread only.
     */
    public void scheduleSave(final TabManager tabManager) {
        if (shutdown || tabManager == null || pendingSave != null) return;
        pendingSave = () -> {
            pendingSave = null;
            // Zero tabs means the tab set is being torn down — never overwrite
            // the saved tabs with an empty list from a delayed callback.
            if (tabManager.count() > 0) persistNow(tabManager);
        };
        main.postDelayed(pendingSave, SAVE_DEBOUNCE_MS);
    }

    private void cancelPending() {
        if (pendingSave != null) {
            main.removeCallbacks(pendingSave);
            pendingSave = null;
        }
    }

    /** Immediate save — snapshot on the caller thread, heavy work on IO. */
    public void persistNow(TabManager tabManager) {
        if (shutdown || tabManager == null) return;
        cancelPending();   // this save supersedes a queued one

        final List<GeckoSession.SessionState> states = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        final List<Boolean> desktop = new ArrayList<>();
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
            desktop.add(t.desktopMode);
            savedIndex++;
        }

        final Snapshot snap = new Snapshot(states, urls, titles, desktop, savedActive);
        if (snap.sameAs(lastSubmitted)) return;           // [PERF] nothing changed
        lastSubmitted = snap;
        IO.execute(() -> write(snap));
    }

    private void write(Snapshot snap) {
        try {
            // [PERF] Strings from the previous write, and the ones for this write.
            final IdentityHashMap<GeckoSession.SessionState, String> previous = serialized;
            final IdentityHashMap<GeckoSession.SessionState, String> current =
                    new IdentityHashMap<>();

            JSONArray arr = new JSONArray();
            for (int i = 0; i < snap.states.size(); i++) {
                GeckoSession.SessionState s = snap.states.get(i);
                JSONObject obj = new JSONObject();
                // toString() is the documented serialization; it can return
                // null for an empty/failed state, in which case JSONObject
                // drops the "s" key — restore then yields an empty tab.
                // [PERF] A state object that was already serialized by the
                // previous write reuses that string (same output, no work).
                String text = null;
                if (s != null) {
                    text = previous.get(s);
                    if (text == null) text = s.toString();
                    if (text != null) current.put(s, text);
                }
                obj.put("s", text);
                obj.put("u", snap.urls.get(i));
                obj.put("t", snap.titles.get(i));
                if (snap.desktop.get(i)) obj.put("d", true);   // only written when on: keeps the data small
                arr.put(obj);
            }
            JSONObject root = new JSONObject();
            root.put("format", 2);
            root.put("active", snap.active);
            root.put("tabs", arr);
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_TABS, root.toString()).apply();
            serialized = current;   // only after a successful write; drops strings of closed/replaced states
        } catch (Exception e) {
            Log.w(TAG, "persistTabs failed", e);
            lastSubmitted = null;   // let the next save retry instead of being skipped
        }
    }

    /** Called once at startup; synchronous by design. */
    public static Restored load(Context ctx) {
        List<GeckoSession.SessionState> out = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<Boolean> desktop = new ArrayList<>();
        int activeIndex = 0;
        try {
            String raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_TABS, null);
            if (raw == null) return new Restored(out, urls, titles, activeIndex, desktop);
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
                desktop.add(o.optBoolean("d", false));       // absent in data saved by older versions
            }
            activeIndex = root.optInt("active", 0);
        } catch (Exception e) {
            Log.w(TAG, "loadTabs failed", e);
        }
        return new Restored(out, urls, titles, activeIndex, desktop);
    }

    /** Stops accepting new saves and lets queued writes drain. */
    public void shutdown() {
        shutdown = true;
        cancelPending();
        IO.shutdown();
        try {
            // Best effort: don't hang teardown on a slow write.
            IO.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
