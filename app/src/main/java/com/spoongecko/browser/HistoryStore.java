package com.spoongecko.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Persistent history with dedup-by-URL and a visit counter.
 *
 * Storage: SharedPreferences "spoon_gecko_history" / "entries", JSON array of
 * {ts, url, title, v}. Capped at MAX_ENTRIES — oldest-by-timestamp entries are
 * dropped when the cap is hit. Same URL on a later visit reuses the existing
 * row, bumps the visit count, and moves the timestamp forward.
 */
public final class HistoryStore {

    public static final class Entry {
        public final long timestamp;
        public final String url;
        public final String title;
        public final int visits;

        public Entry(long timestamp, String url, String title, int visits) {
            this.timestamp = timestamp;
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
            this.visits = visits <= 0 ? 1 : visits;
        }

        public String displayTitle() {
            if (!title.isEmpty()) return title;
            if (!url.isEmpty()) return url;
            return "(untitled)";
        }

        public String host() {
            try {
                URI u = new URI(url);
                String h = u.getHost();
                return h == null ? "" : h;
            } catch (Exception e) {
                return "";
            }
        }
    }

    public enum Sort {
        DATE_DESC("Newest first"),
        DATE_ASC("Oldest first"),
        TITLE_ASC("Title A-Z"),
        TITLE_DESC("Title Z-A"),
        HOST_ASC("Host A-Z"),
        VISITS_DESC("Most visited");

        public final String label;
        Sort(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final String PREFS = "spoon_gecko_history";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 2000;

    private HistoryStore() {}

    public static synchronized void record(Context ctx, String url, String title) {
        if (url == null || url.isEmpty()) return;
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("about:") || lower.startsWith("data:")
                || lower.startsWith("file:") || lower.startsWith("blob:")) return;

        List<Entry> entries = load(ctx);
        long now = System.currentTimeMillis();

        for (int i = 0; i < entries.size(); i++) {
            Entry old = entries.get(i);
            if (old.url.equalsIgnoreCase(url)) {
                String newTitle = (title == null || title.isEmpty()) ? old.title : title;
                entries.set(i, new Entry(now, url, newTitle, old.visits + 1));
                save(ctx, entries);
                return;
            }
        }

        entries.add(new Entry(now, url, title, 1));
        while (entries.size() > MAX_ENTRIES) {
            int oldest = 0;
            for (int i = 1; i < entries.size(); i++) {
                if (entries.get(i).timestamp < entries.get(oldest).timestamp) oldest = i;
            }
            entries.remove(oldest);
        }
        save(ctx, entries);
    }

    public static synchronized List<Entry> load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_ENTRIES, null);
        List<Entry> out = new ArrayList<>();
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String url = o.optString("url", "");
                if (url.isEmpty()) continue;
                out.add(new Entry(
                        o.optLong("ts", 0L),
                        url,
                        o.optString("title", ""),
                        o.optInt("v", 1)));
            }
        } catch (JSONException ignored) {}
        return out;
    }

    public static synchronized void update(Context ctx, Entry target,
                                           String newTitle, String newUrl) {
        List<Entry> entries = load(ctx);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.timestamp == target.timestamp && e.url.equals(target.url)) {
                entries.set(i, new Entry(
                        e.timestamp,
                        (newUrl == null || newUrl.isEmpty()) ? e.url : newUrl,
                        newTitle == null ? e.title : newTitle,
                        e.visits));
                break;
            }
        }
        save(ctx, entries);
    }

    public static synchronized void delete(Context ctx, Entry target) {
        List<Entry> entries = load(ctx);
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.timestamp == target.timestamp && e.url.equals(target.url)) {
                entries.remove(i);
            }
        }
        save(ctx, entries);
    }

    public static synchronized void clear(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_ENTRIES).apply();
    }

    public static void sort(List<Entry> list, Sort sort) {
        if (sort == null) sort = Sort.DATE_DESC;
        switch (sort) {
            case DATE_DESC:
                Collections.sort(list, (a, b) -> Long.compare(b.timestamp, a.timestamp));
                break;
            case DATE_ASC:
                Collections.sort(list, (a, b) -> Long.compare(a.timestamp, b.timestamp));
                break;
            case TITLE_ASC:
                Collections.sort(list, (a, b) ->
                        a.displayTitle().compareToIgnoreCase(b.displayTitle()));
                break;
            case TITLE_DESC:
                Collections.sort(list, (a, b) ->
                        b.displayTitle().compareToIgnoreCase(a.displayTitle()));
                break;
            case HOST_ASC:
                Collections.sort(list, (a, b) ->
                        a.host().compareToIgnoreCase(b.host()));
                break;
            case VISITS_DESC:
                Collections.sort(list, (a, b) -> Integer.compare(b.visits, a.visits));
                break;
        }
    }

    private static void save(Context ctx, List<Entry> entries) {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            JSONObject o = new JSONObject();
            try {
                o.put("ts", e.timestamp);
                o.put("url", e.url);
                o.put("title", e.title);
                o.put("v", e.visits);
                arr.put(o);
            } catch (JSONException ignored) {}
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENTRIES, arr.toString()).apply();
    }
}
