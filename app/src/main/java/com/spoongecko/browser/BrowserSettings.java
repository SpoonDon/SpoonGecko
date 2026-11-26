package com.spoongecko.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;

/**
 * Central browser preferences backed by SharedPreferences ("spoongecko_prefs").
 */
final class BrowserSettings {

    private static final String TAG = "SpoonGecko";
    static final String PREFS_NAME = "spoongecko_prefs";

    private static final String KEY_SEARCH_ENGINE = "pref_search_engine";
    private static final String KEY_SEARCH_SUGGESTIONS = "pref_search_suggestions";
    private static final String KEY_DEFAULT_DESKTOP = "pref_default_desktop";
    private static final String KEY_AUTO_DOWNLOAD_LINKS = "pref_auto_download_links";
    private static final String KEY_PDF_VIEWER = "pref_pdf_viewer";
    private static final String KEY_AUTOPLAY_POLICY = "pref_autoplay_policy";
    private static final String KEY_TRACKING_LEVEL = "pref_tracking_level";
    private static final String KEY_DOH_MODE = "pref_doh_mode";

    enum SearchEngine {
        BRAVE("brave", "Brave Search", "https://search.brave.com/search?q="),
        DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q="),
        GOOGLE("google", "Google", "https://www.google.com/search?q="),
        STARTPAGE("startpage", "Startpage", "https://www.startpage.com/sp/search?query="),
        ECOSIA("ecosia", "Ecosia", "https://www.ecosia.org/search?q=");

        final String key;
        final String displayName;
        final String searchUrlPrefix;

        SearchEngine(String key, String displayName, String searchUrlPrefix) {
            this.key = key;
            this.displayName = displayName;
            this.searchUrlPrefix = searchUrlPrefix;
        }

        static SearchEngine fromKey(String key) {
            if (key != null) {
                for (SearchEngine e : values()) {
                    if (e.key.equals(key)) return e;
                }
            }
            return BRAVE;
        }
    }

    enum AutoplayPolicy {
        ALLOW_MUTED("allow_muted", "Allow muted video only"),
        BLOCK_ALL("block_all", "Block all autoplay"),
        ALLOW_ALL("allow_all", "Allow audio and video");

        final String key;
        final String displayName;

        AutoplayPolicy(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }

        static AutoplayPolicy fromKey(String key) {
            if (key != null) {
                for (AutoplayPolicy p : values()) {
                    if (p.key.equals(key)) return p;
                }
            }
            return ALLOW_MUTED;
        }
    }

    enum TrackingLevel {
        STRICT("strict", "Strict (Recommended)", ContentBlocking.EtpLevel.STRICT),
        STANDARD("standard", "Standard", ContentBlocking.EtpLevel.DEFAULT),
        OFF("off", "Off", ContentBlocking.EtpLevel.NONE);

        final String key;
        final String displayName;
        final int etpLevel;

        TrackingLevel(String key, String displayName, int etpLevel) {
            this.key = key;
            this.displayName = displayName;
            this.etpLevel = etpLevel;
        }

        static TrackingLevel fromKey(String key) {
            if (key != null) {
                for (TrackingLevel l : values()) {
                    if (l.key.equals(key)) return l;
                }
            }
            return STRICT;
        }
    }

    enum DohMode {
        FIRST("first", "Automatic (Prefer DoH)", GeckoRuntimeSettings.TRR_MODE_FIRST),
        ONLY("only", "Strict (DoH only)", GeckoRuntimeSettings.TRR_MODE_ONLY),
        OFF("off", "Off (System DNS)", GeckoRuntimeSettings.TRR_MODE_OFF);

        final String key;
        final String displayName;
        final int trrMode;

        DohMode(String key, String displayName, int trrMode) {
            this.key = key;
            this.displayName = displayName;
            this.trrMode = trrMode;
        }

        static DohMode fromKey(String key) {
            if (key != null) {
                for (DohMode m : values()) {
                    if (m.key.equals(key)) return m;
                }
            }
            return FIRST;
        }
    }

    private BrowserSettings() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // Search engine & suggestions
    static SearchEngine getSearchEngine(Context ctx) {
        return SearchEngine.fromKey(prefs(ctx).getString(KEY_SEARCH_ENGINE, SearchEngine.BRAVE.key));
    }

    static void setSearchEngine(Context ctx, SearchEngine engine) {
        if (engine == null) return;
        prefs(ctx).edit().putString(KEY_SEARCH_ENGINE, engine.key).apply();
    }

    static String searchUrlForQuery(Context ctx, String query) {
        return getSearchEngine(ctx).searchUrlPrefix + Uri.encode(query == null ? "" : query);
    }

    static String homeSearchHint(Context ctx) {
        return "Search with " + getSearchEngine(ctx).displayName;
    }

    static boolean isSearchSuggestionsEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SEARCH_SUGGESTIONS, true);
    }

    static void setSearchSuggestionsEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_SEARCH_SUGGESTIONS, enabled).apply();
    }

    // Display & browsing
    static boolean isDefaultDesktopMode(Context ctx) {
        return prefs(ctx).getBoolean(KEY_DEFAULT_DESKTOP, false);
    }

    static void setDefaultDesktopMode(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_DEFAULT_DESKTOP, enabled).apply();
    }

    static boolean isPdfViewerEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PDF_VIEWER, true);
    }

    static void setPdfViewerEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_PDF_VIEWER, enabled).apply();
        PdfSupport.setViewerEnabled(enabled);
    }

    // Downloads
    static boolean isAutoDownloadLinks(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_DOWNLOAD_LINKS, true);
    }

    static void setAutoDownloadLinks(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_DOWNLOAD_LINKS, enabled).apply();
    }

    // Autoplay
    static AutoplayPolicy getAutoplayPolicy(Context ctx) {
        return AutoplayPolicy.fromKey(prefs(ctx).getString(KEY_AUTOPLAY_POLICY, AutoplayPolicy.ALLOW_MUTED.key));
    }

    static void setAutoplayPolicy(Context ctx, AutoplayPolicy policy) {
        if (policy == null) return;
        prefs(ctx).edit().putString(KEY_AUTOPLAY_POLICY, policy.key).apply();
    }

    static int autoplayAnswer(Context ctx, int permissionType) {
        final AutoplayPolicy policy = getAutoplayPolicy(ctx);
        switch (policy) {
            case ALLOW_ALL:
                return GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW;
            case BLOCK_ALL:
                return GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY;
            case ALLOW_MUTED:
            default:
                return permissionType == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                        ? GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        : GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY;
        }
    }

    // Privacy & security
    static TrackingLevel getTrackingLevel(Context ctx) {
        return TrackingLevel.fromKey(prefs(ctx).getString(KEY_TRACKING_LEVEL, TrackingLevel.STRICT.key));
    }

    static void setTrackingLevel(Context ctx, TrackingLevel level) {
        if (level == null) return;
        prefs(ctx).edit().putString(KEY_TRACKING_LEVEL, level.key).apply();
    }

    static DohMode getDohMode(Context ctx) {
        return DohMode.fromKey(prefs(ctx).getString(KEY_DOH_MODE, DohMode.FIRST.key));
    }

    static void setDohMode(Context ctx, DohMode mode) {
        if (mode == null) return;
        prefs(ctx).edit().putString(KEY_DOH_MODE, mode.key).apply();
    }

    static void applyRuntimeSettings(Context ctx, GeckoRuntime runtime) {
        if (runtime == null) return;
        try {
            final GeckoRuntimeSettings settings = runtime.getSettings();
            settings.getContentBlocking().setEnhancedTrackingProtectionLevel(getTrackingLevel(ctx).etpLevel);
            settings.setTrustedRecursiveResolverMode(getDohMode(ctx).trrMode);
        } catch (RuntimeException e) {
            Log.w(TAG, "applying runtime settings failed", e);
        }
    }
}
