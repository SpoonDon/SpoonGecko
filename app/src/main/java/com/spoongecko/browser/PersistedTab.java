package com.spoongecko.browser;

import org.json.JSONException;
import org.json.JSONObject;

/** Serializable URL+title pair stored across cold starts. */
public class PersistedTab {

    public final String url;
    public final String title;

    public PersistedTab(String url, String title) {
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("url", url);
        o.put("title", title);
        return o;
    }

    public static PersistedTab fromJson(JSONObject o) {
        return new PersistedTab(o.optString("url", ""), o.optString("title", ""));
    }
}
