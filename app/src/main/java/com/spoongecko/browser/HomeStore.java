package com.spoongecko.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the user's quick-link tiles shown on the SpoonGecko home page.
 * Stored as a JSON array in SharedPreferences (spoon_gecko_home / quick_links).
 * First run seeds a small default set. Cap at MAX_QUICK_LINKS to keep the
 * grid from growing forever.
 */
public final class HomeStore {

    public static final class QuickLink {
        public final String title;
        public final String url;
        public QuickLink(String title, String url) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
        }
    }

    private static final String PREFS = "spoon_gecko_home";
    private static final String KEY_QUICK_LINKS = "quick_links";
    private static final int MAX_QUICK_LINKS = 11; // 4 cols x 3 rows, minus one slot for the "+" tile

    private HomeStore() {}

    public static List<QuickLink> getQuickLinks(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_QUICK_LINKS, null);
        List<QuickLink> out = new ArrayList<>();
        if (raw == null) {
            out.add(new QuickLink("YouTube",   "https://m.youtube.com"));
            out.add(new QuickLink("Wikipedia", "https://en.m.wikipedia.org"));
            out.add(new QuickLink("Reddit",    "https://www.reddit.com"));
            out.add(new QuickLink("GitHub",    "https://github.com"));
            saveQuickLinks(ctx, out);
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String t = o.optString("title", "");
                String u = o.optString("url", "");
                if (!u.isEmpty()) out.add(new QuickLink(t, u));
            }
        } catch (JSONException ignored) {
            // Corrupt blob — treat as empty; user can add tiles again.
        }
        return out;
    }

    public static void saveQuickLinks(Context ctx, List<QuickLink> links) {
        JSONArray arr = new JSONArray();
        int n = Math.min(links.size(), MAX_QUICK_LINKS);
        for (int i = 0; i < n; i++) {
            QuickLink q = links.get(i);
            JSONObject o = new JSONObject();
            try {
                o.put("title", q.title);
                o.put("url", q.url);
                arr.put(o);
            } catch (JSONException ignored) {}
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putString(KEY_QUICK_LINKS, arr.toString()).apply();
    }

    public static void addQuickLink(Context ctx, String title, String url) {
        if (url == null || url.isEmpty()) return;
        List<QuickLink> links = getQuickLinks(ctx);
        for (int i = links.size() - 1; i >= 0; i--) {
            if (links.get(i).url.equalsIgnoreCase(url)) links.remove(i);
        }
        links.add(0, new QuickLink(title, url));
        saveQuickLinks(ctx, links);
    }

    public static void removeQuickLink(Context ctx, String url) {
        if (url == null) return;
        List<QuickLink> links = getQuickLinks(ctx);
        for (int i = links.size() - 1; i >= 0; i--) {
            if (links.get(i).url.equalsIgnoreCase(url)) links.remove(i);
        }
        saveQuickLinks(ctx, links);
    }
}
