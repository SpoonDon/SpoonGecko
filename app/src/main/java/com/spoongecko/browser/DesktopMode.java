package com.spoongecko.browser;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;

/**
 * "Desktop site" for a single tab.
 *
 * Desktop mode needs BOTH halves GeckoView offers, and they do different
 * things:
 *   - the user agent: what the server sees. Sites like YouTube pick their
 *     whole layout (and whether to pause in the background) from it; the
 *     desktop mode UA drops the "Android"/"Mobile" tokens, so www.youtube.com
 *     is served instead of m.youtube.com.
 *   - the viewport: what the page is laid out against. Desktop mode makes
 *     Gecko ignore the page's mobile {@code <meta viewport>} and lay it out at
 *     a 980 CSS px width, which is what desktop layouts expect.
 * Setting only the user agent gives desktop HTML squeezed into a phone-width
 * layout; setting only the viewport gives the mobile site, just zoomed out.
 *
 * Both settings take effect for the NEXT load, so callers reload afterwards.
 */
final class DesktopMode {

    private DesktopMode() {}

    static void apply(GeckoSession session, boolean desktop) {
        final GeckoSessionSettings s = session.getSettings();
        s.setUserAgentMode(desktop
                ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
        s.setViewportMode(desktop
                ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
        s.setUserAgentOverride(null);   // never let a stale override win over the mode
    }

    /**
     * Loads {@code url} again under the tab's new user agent.
     *
     * Not GeckoSession.reload(): reload with cache flags is unreliable in
     * GeckoView (Bug 1623712), so this is a normal load of the same address.
     *   - BYPASS_CACHE: servers rarely send "Vary: User-Agent", so without it
     *     the cached copy for the OLD user agent would be shown again.
     *   - REPLACE_HISTORY: the switch must not add a second back-button entry
     *     for the same page. (Both flags are what Firefox for Android itself
     *     used for this toggle.)
     */
    static void reloadWithNewAgent(GeckoSession session, String url) {
        session.load(new GeckoSession.Loader().uri(url).flags(
                GeckoSession.LOAD_FLAGS_BYPASS_CACHE | GeckoSession.LOAD_FLAGS_REPLACE_HISTORY));
    }

    /**
     * YouTube treats "?app=desktop" as "show the desktop site whatever the
     * browser is", and keeps that parameter on the URLs it hands out. If it
     * were left in place, switching a tab back to the mobile site would reload
     * the same desktop page and the toggle would look broken.
     *
     * @return the URL to LOAD instead of reloading, or null when a plain
     *         reload is right (any other site, or no such parameter).
     */
    static String mobileReloadTarget(String url) {
        if (url == null) return null;
        final String host = LocalAddress.hostOf(url);
        if (host == null || !(host.equals("youtube.com") || host.endsWith(".youtube.com"))) {
            return null;
        }
        final int hashAt = url.indexOf('#');
        final String beforeFragment = hashAt < 0 ? url : url.substring(0, hashAt);
        final String fragment = hashAt < 0 ? "" : url.substring(hashAt);
        final int q = beforeFragment.indexOf('?');
        if (q < 0) return null;

        final String base = beforeFragment.substring(0, q);
        final StringBuilder kept = new StringBuilder();
        boolean removed = false;
        for (String part : beforeFragment.substring(q + 1).split("&", -1)) {
            if (part.equalsIgnoreCase("app=desktop")) { removed = true; continue; }
            if (part.isEmpty()) continue;
            if (kept.length() > 0) kept.append('&');
            kept.append(part);
        }
        if (!removed) return null;
        return base + (kept.length() > 0 ? "?" + kept : "") + fragment;
    }
}
