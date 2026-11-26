package com.spoongecko.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoPreferenceController;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;

import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Central browser preferences backed by SharedPreferences ("spoongecko_prefs").
 */
final class BrowserSettings {

    private static final String TAG = "SpoonGecko";
    static final String PREFS_NAME = "spoongecko_prefs";

    private static final String KEY_SEARCH_ENGINE = "pref_search_engine";
    private static final String KEY_SEARCH_SUGGESTIONS = "pref_search_suggestions";
    private static final String KEY_DEFAULT_DESKTOP = "pref_default_desktop";
    private static final String KEY_BOTTOM_TOOLBAR = "pref_bottom_toolbar";
    private static final String KEY_AUTO_DOWNLOAD_LINKS = "pref_auto_download_links";
    private static final String KEY_PDF_VIEWER = "pref_pdf_viewer";
    private static final String KEY_AUTOPLAY_POLICY = "pref_autoplay_policy";
    private static final String KEY_TRACKING_LEVEL = "pref_tracking_level";
    private static final String KEY_DOH_MODE = "pref_doh_mode";
    private static final String KEY_DNS_PROVIDER = "pref_dns_provider";
    private static final String KEY_CUSTOM_DOH_URI = "pref_custom_doh_uri";
    private static final String KEY_DNS_BOOTSTRAP_IP = "pref_dns_bootstrap_ip";
    private static final String KEY_DNS_ALLOW_LOCAL = "pref_dns_allow_local";

    private static final Pattern NUMERIC_IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

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

    enum DnsProvider {
        DEFAULT("default", "Default", "Browser default", "", ""),
        CLOUDFLARE("cloudflare", "Cloudflare", "Cloudflare (1.1.1.1)",
                "https://mozilla.cloudflare-dns.com/dns-query", "1.1.1.1"),
        CLOUDFLARE_SECURITY("cloudflare_sec", "Cloudflare Security",
                "Cloudflare Security (Malware filter)",
                "https://security.cloudflare-dns.com/dns-query", "1.1.1.2"),
        QUAD9("quad9", "Quad9", "Quad9 (9.9.9.9)",
                "https://dns.quad9.net/dns-query", "9.9.9.9"),
        ADGUARD("adguard", "AdGuard DNS", "AdGuard DNS (Ad & tracker filter)",
                "https://dns.adguard-dns.com/dns-query", "94.140.14.14"),
        MULLVAD("mullvad", "Mullvad DNS", "Mullvad DNS",
                "https://dns.mullvad.net/dns-query", "194.242.2.2"),
        GOOGLE("google", "Google", "Google Public DNS (8.8.8.8)",
                "https://dns.google/dns-query", "8.8.8.8"),
        CUSTOM("custom", "Custom", "Custom DoH URL", "", "");

        final String key;
        final String shortName;
        final String displayName;
        final String uri;
        final String defaultBootstrapIp;

        DnsProvider(String key, String shortName, String displayName,
                    String uri, String defaultBootstrapIp) {
            this.key = key;
            this.shortName = shortName;
            this.displayName = displayName;
            this.uri = uri;
            this.defaultBootstrapIp = defaultBootstrapIp;
        }

        static DnsProvider fromKey(String key) {
            if (key != null) {
                for (DnsProvider p : values()) {
                    if (p.key.equals(key)) return p;
                }
            }
            return DEFAULT;
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

    static boolean isBottomToolbar(Context ctx) {
        return prefs(ctx).getBoolean(KEY_BOTTOM_TOOLBAR, false);
    }

    static void setBottomToolbar(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_BOTTOM_TOOLBAR, enabled).apply();
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

    static DnsProvider getDnsProvider(Context ctx) {
        return DnsProvider.fromKey(prefs(ctx).getString(KEY_DNS_PROVIDER, DnsProvider.DEFAULT.key));
    }

    static String getCustomDohUri(Context ctx) {
        return prefs(ctx).getString(KEY_CUSTOM_DOH_URI, "");
    }

    static String getDnsBootstrapIp(Context ctx) {
        return prefs(ctx).getString(KEY_DNS_BOOTSTRAP_IP, "");
    }

    static boolean isDnsAllowLocalEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_DNS_ALLOW_LOCAL, true);
    }

    static void setDnsConfig(Context ctx, DohMode mode, DnsProvider provider,
                             String customUri, String bootstrapIp, boolean allowLocal) {
        prefs(ctx).edit()
                .putString(KEY_DOH_MODE, (mode != null ? mode : DohMode.FIRST).key)
                .putString(KEY_DNS_PROVIDER, (provider != null ? provider : DnsProvider.DEFAULT).key)
                .putString(KEY_CUSTOM_DOH_URI, customUri == null ? "" : customUri.trim())
                .putString(KEY_DNS_BOOTSTRAP_IP, bootstrapIp == null ? "" : bootstrapIp.trim())
                .putBoolean(KEY_DNS_ALLOW_LOCAL, allowLocal)
                .apply();
    }

    static String effectiveDohUri(Context ctx) {
        final DnsProvider p = getDnsProvider(ctx);
        if (p == DnsProvider.CUSTOM) {
            final String custom = getCustomDohUri(ctx).trim();
            return isValidDohUrl(custom) ? custom : "";
        }
        return p.uri;
    }

    static String effectiveBootstrapIp(Context ctx) {
        final String explicit = getDnsBootstrapIp(ctx).trim();
        if (!explicit.isEmpty() && isValidBootstrapIp(explicit)) {
            return explicit;
        }
        final DnsProvider p = getDnsProvider(ctx);
        return p != DnsProvider.CUSTOM ? p.defaultBootstrapIp : "";
    }

    static boolean isValidDohUrl(String raw) {
        if (raw == null) return false;
        final String s = raw.trim();
        if (s.isEmpty() || s.contains(" ") || s.contains("\n")) return false;
        if (!s.toLowerCase(Locale.ROOT).startsWith("https://")) return false;
        final Uri uri = Uri.parse(s);
        final String host = uri.getHost();
        return host != null && !host.trim().isEmpty();
    }

    static boolean isValidBootstrapIp(String raw) {
        if (raw == null) return true;
        final String s = raw.trim();
        if (s.isEmpty()) return true;
        if (NUMERIC_IPV4.matcher(s).matches()) return true;
        // IPv6 literal check without performing DNS resolution
        if (!s.contains(":")) return false;
        try {
            final InetAddress addr = InetAddress.getByName(s);
            return addr != null && s.indexOf('%') < 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String dohSummary(Context ctx) {
        final DohMode mode = getDohMode(ctx);
        if (mode == DohMode.OFF) {
            return mode.displayName;
        }
        final DnsProvider provider = getDnsProvider(ctx);
        if (provider == DnsProvider.CUSTOM) {
            final String url = getCustomDohUri(ctx).trim();
            final Uri parsed = url.isEmpty() ? null : Uri.parse(url);
            final String host = parsed != null && parsed.getHost() != null ? parsed.getHost() : url;
            return mode.displayName + " \u00b7 " + (host.isEmpty() ? "Custom" : host);
        }
        return mode.displayName + " \u00b7 " + provider.shortName;
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
        applyDnsPrefs(ctx);
    }

    static void applyDnsPrefs(Context ctx) {
        final DohMode mode = getDohMode(ctx);
        final String uri = effectiveDohUri(ctx);
        final String bootstrap = effectiveBootstrapIp(ctx);
        final boolean allowLocal = isDnsAllowLocalEnabled(ctx);
        setPrefQuietly("network.trr.mode", mode.trrMode);
        setPrefQuietly("network.trr.uri", uri);
        setPrefQuietly("network.trr.custom_uri", uri);
        setPrefQuietly("network.trr.bootstrapAddr", bootstrap);
        setPrefQuietly("network.trr.allow-rfc1918", allowLocal);
    }

    private static void setPrefQuietly(String pref, Object value) {
        try {
            if (value instanceof Boolean) {
                GeckoPreferenceController.setGeckoPref(
                        pref, (Boolean) value, GeckoPreferenceController.PREF_BRANCH_USER)
                        .exceptionally(e -> { Log.w(TAG, "pref failed: " + pref, e); return null; });
            } else if (value instanceof Integer) {
                GeckoPreferenceController.setGeckoPref(
                        pref, (Integer) value, GeckoPreferenceController.PREF_BRANCH_USER)
                        .exceptionally(e -> { Log.w(TAG, "pref failed: " + pref, e); return null; });
            } else if (value instanceof String) {
                GeckoPreferenceController.setGeckoPref(
                        pref, (String) value, GeckoPreferenceController.PREF_BRANCH_USER)
                        .exceptionally(e -> { Log.w(TAG, "pref failed: " + pref, e); return null; });
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not set Gecko pref " + pref, e);
        }
    }
}
