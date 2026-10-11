package com.spoongecko.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Rolling log of the last N page visits. Powers the "Recent" section on the
 * home page. This is intentionally a lightweight stand-in for a full history
 * store — when a proper History screen is built, migrate this data rather
 * than inventing a second source of truth.
 */
public final class VisitStore {

    public static final class Visit {
        public final String title;
        public final String url;
        public final long timestamp;
        public Visit(String title, String url, long timestamp) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.timestamp = timestamp;
        }
    }

    private static final String PREFS = "spoon_gecko_history";
    private static final String KEY_VISITS = "recent_visits";
    private static final int MAX_VISITS = 50;
    private static final int HOME_DISPLAY_LIMIT = 6;

    private VisitStore() {}

    /** Record a visit. No-op for internal / non-web URLs. */
    public static void record(Context ctx, String title, String url) {
        if (url == null || url.isEmpty()) return;
        String lower = url.toLowerCase();
        if (lower.startsWith("about:")  || lower.startsWith("data:") ||
            lower.startsWith("file:")   || lower.startsWith("blob:") ||
            lower.startsWith("magnet:") || lower.startsWith("tel:")  ||
            lower.startsWith("mailto:") || lower.startsWith("market:") ||
            lower.startsWith("intent:")) return;

        List<Visit> visits = getAll(ctx);
        for (int i = visits.size() - 1; i >= 0; i--) {
            if (visits.get(i).url.equalsIgnoreCase(url)) visits.remove(i);
        }
        String t = (title == null || title.isEmpty()) ? url : title;
        visits.add(0, new Visit(t, url, System.currentTimeMillis()));
        while (visits.size() > MAX_VISITS) visits.remove(visits.size() - 1);
        save(ctx, visits);
    }

    public static List<Visit> getAll(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_VISITS, null);
        List<Visit> out = new ArrayList<>();
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String t = o.optString("title", "");
                String u = o.optString("url", "");
                long ts = o.optLong("ts", 0L);
                if (!u.isEmpty()) out.add(new Visit(t, u, ts));
            }
        } catch (JSONException ignored) {}
        return out;
    }

    public static List<Visit> getRecentForHome(Context ctx) {
        List<Visit> all = getAll(ctx);
        if (all.size() <= HOME_DISPLAY_LIMIT) return all;
        return new ArrayList<>(all.subList(0, HOME_DISPLAY_LIMIT));
    }

    public static void clearAll(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().remove(KEY_VISITS).apply();
    }

    private static void save(Context ctx, List<Visit> visits) {
        JSONArray arr = new JSONArray();
        for (Visit v : visits) {
            JSONObject o = new JSONObject();
            try {
                o.put("title", v.title);
                o.put("url", v.url);
                o.put("ts", v.timestamp);
                arr.put(o);
            } catch (JSONException ignored) {}
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putString(KEY_VISITS, arr.toString()).apply();
    }
}
