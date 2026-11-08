package com.spoongecko.browser;

import android.util.Base64;

import org.mozilla.geckoview.WebRequestError;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Builds the page GeckoView shows when a load fails.
 *
 * GeckoSession.NavigationDelegate.onLoadError() must return a URI to display;
 * returning null "halts the load entirely", i.e. the user used to get a blank
 * page for every failure — including a router with a self-signed certificate.
 * The page returned here is a data: URI, which GeckoView treats as a trusted
 * error page and exposes document.addCertException() to (see the contract in
 * the onLoadError Javadoc). The override button is only ever rendered for
 * addresses on the user's own network.
 */
final class ErrorPages {

    private ErrorPages() {}

    static final String TITLE_CERT = "Connection isn\u2019t private";
    static final String TITLE_SSL = "Secure connection failed";
    static final String TITLE_LOCAL_BLOCKED = "Local network access is off";
    static final String TITLE_UNREACHABLE = "Can\u2019t reach this page";
    static final String TITLE_GENERIC = "Can\u2019t open this page";

    /** Titles of our own error pages (used to keep them out of the history). */
    private static final Set<String> TITLES = new HashSet<>(Arrays.asList(
            TITLE_CERT, TITLE_SSL, TITLE_LOCAL_BLOCKED, TITLE_UNREACHABLE, TITLE_GENERIC));

    static final String DATA_PREFIX = "data:text/html;charset=utf-8;base64,";

    /**
     * ERROR_LOCAL_NETWORK_ACCESS_DENIED only exists in newer GeckoView builds.
     * Read it reflectively so this compiles — and degrades gracefully — on a
     * build that doesn't have it yet.
     */
    private static final int LNP_CODE = lookupLocalNetworkDeniedCode();

    private static int lookupLocalNetworkDeniedCode() {
        try {
            return WebRequestError.class.getField("ERROR_LOCAL_NETWORK_ACCESS_DENIED").getInt(null);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    static boolean isErrorTitle(String title) {
        return title != null && TITLES.contains(title);
    }

    static boolean isErrorPageUri(String uri) {
        return uri != null && uri.startsWith(DATA_PREFIX);
    }

    /**
     * True when this failure is Android's Local Network Protection refusing
     * the connection because the app lacks the permission. permissionMissing
     * must be (Android 17+ && permission not granted): with the permission
     * granted a failure is just a failure and must never trigger a prompt.
     */
    static boolean isLocalNetworkBlocked(boolean permissionMissing, String uri, WebRequestError e) {
        if (!permissionMissing || e == null) return false;
        if (LNP_CODE != Integer.MIN_VALUE && e.code == LNP_CODE) return true;
        // Builds without the dedicated code report a plain network error; towards an
        // address Android gates behind the permission that is almost certainly this.
        return e.category == WebRequestError.ERROR_CATEGORY_NETWORK
                && LocalAddress.needsLocalNetworkPermission(LocalAddress.hostOf(uri));
    }

    /** The data: URI to hand back from onLoadError(). */
    static String build(String uri, WebRequestError e, boolean localNetworkBlocked) {
        return dataUri(ErrorPageHtml.render(model(uri, e, localNetworkBlocked)));
    }

    static String dataUri(String html) {
        return DATA_PREFIX + Base64.encodeToString(html.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    // ------------------------------------------------------------------
    // What to say
    // ------------------------------------------------------------------

    static ErrorPageHtml.Model model(String uri, WebRequestError e, boolean localNetworkBlocked) {
        final ErrorPageHtml.Model m = new ErrorPageHtml.Model();
        final String url = uri == null ? "" : uri;
        m.failedUrl = url.length() > 500 ? url.substring(0, 500) + "\u2026" : url;

        final String host = LocalAddress.hostOf(url);
        final String shownHost = host == null || host.isEmpty() ? "this address" : host;
        final boolean eligible = LocalAddress.isOverrideEligible(host);   // IP literal / local name
        final boolean https = url.regionMatches(true, 0, "https://", 0, 8);
        final int code = e == null ? WebRequestError.ERROR_UNKNOWN : e.code;
        final int category = e == null ? WebRequestError.ERROR_CATEGORY_UNKNOWN : e.category;
        final String alt = eligible ? LocalAddress.alternateScheme(url) : null;
        final String altLabel = alt == null ? null
                : (https ? "Try the unencrypted http:// address" : "Try the secure https:// address");

        if (localNetworkBlocked) {
            m.title = TITLE_LOCAL_BLOCKED;
            m.lead = "Android is blocking SpoonGecko from reaching devices on your local network, "
                    + "such as your router.";
            m.paragraphs.add("Turn on \u201cNearby devices\u201d for SpoonGecko in Settings \u203a Apps "
                    + "\u203a SpoonGecko \u203a Permissions, then try again.");
        } else if (code == WebRequestError.ERROR_BAD_HSTS_CERT) {
            m.title = TITLE_CERT;
            m.lead = "SpoonGecko couldn\u2019t verify the security certificate of " + shownHost + ".";
            m.paragraphs.add("This site insists on a secure connection, so the warning can\u2019t be bypassed.");
            addCertificateDetails(m, e, host);
        } else if (code == WebRequestError.ERROR_SECURITY_BAD_CERT) {
            m.title = TITLE_CERT;
            m.lead = "SpoonGecko couldn\u2019t verify that the connection to " + shownHost + " is private.";
            if (eligible) {
                m.paragraphs.add("Routers, modems and other devices on a home network usually use a "
                        + "self-signed certificate, which a browser can\u2019t verify. If this is your own "
                        + "device and you trust the network you\u2019re on, you can continue.");
                m.paragraphs.add("Other people on the same network may be able to read what you send, "
                        + "so don\u2019t enter passwords for other accounts here.");
                m.offerProceed = true;
                m.proceedHost = shownHost;
            } else {
                m.paragraphs.add("For your safety SpoonGecko only lets you continue past this warning for "
                        + "devices on your own network (IP addresses and local names), not for public websites.");
            }
            addCertificateDetails(m, e, host);
        } else if (code == WebRequestError.ERROR_SECURITY_SSL) {
            m.title = TITLE_SSL;
            m.lead = "A secure connection to " + shownHost + " couldn\u2019t be set up.";
            m.paragraphs.add(eligible
                    ? "Many routers only support unencrypted http://, or use security settings that are too "
                      + "old for a modern browser."
                    : "The site may be misconfigured, or something on the network is interfering.");
            if (https) { m.altUrl = alt; m.altLabel = altLabel; }
        } else if (category == WebRequestError.ERROR_CATEGORY_NETWORK) {
            m.title = TITLE_UNREACHABLE;
            final boolean mayBeWrongScheme = code == WebRequestError.ERROR_CONNECTION_REFUSED
                    || code == WebRequestError.ERROR_NET_RESET
                    || code == WebRequestError.ERROR_NET_INTERRUPT;
            if (code == WebRequestError.ERROR_UNKNOWN_HOST) {
                m.lead = "SpoonGecko couldn\u2019t find " + shownHost + ".";
            } else if (code == WebRequestError.ERROR_OFFLINE) {
                m.lead = "This device isn\u2019t connected to a network.";
            } else if (code == WebRequestError.ERROR_CONNECTION_REFUSED) {
                m.lead = shownHost + " refused the connection.";
            } else if (code == WebRequestError.ERROR_NET_TIMEOUT) {
                m.lead = shownHost + " took too long to respond.";
            } else if (code == WebRequestError.ERROR_PORT_BLOCKED) {
                m.lead = "SpoonGecko doesn\u2019t allow connections to this port.";
            } else if (mayBeWrongScheme) {
                m.lead = "The connection to " + shownHost + " was interrupted.";
            } else {
                m.lead = "Something went wrong while connecting to " + shownHost + ".";
            }
            if (eligible) {
                m.paragraphs.add("Check that you\u2019re connected to the same Wi\u2011Fi network as the device "
                        + "and that the address is correct.");
                if (mayBeWrongScheme) { m.altUrl = alt; m.altLabel = altLabel; }
            }
        } else {
            m.title = TITLE_GENERIC;
            m.lead = "SpoonGecko couldn\u2019t open this page.";
        }

        m.details.add("Error: category " + category + ", code " + code);
        return m;
    }

    // ------------------------------------------------------------------
    // Certificate facts (what the server presented — not a verdict)
    // ------------------------------------------------------------------

    private static void addCertificateDetails(ErrorPageHtml.Model m, WebRequestError e, String host) {
        if (e == null || e.certificate == null) return;
        m.details.addAll(describeCertificate(e.certificate, host));
    }

    static List<String> describeCertificate(X509Certificate c, String host) {
        final List<String> out = new ArrayList<>();
        try {
            final String subject = c.getSubjectX500Principal().getName();
            final String issuer = c.getIssuerX500Principal().getName();
            out.add("Issued to: " + subject);
            out.add("Issued by: " + issuer + (subject.equals(issuer) ? "  (self-signed)" : ""));
        } catch (Exception ignored) { /* skip line */ }
        try {
            final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            final Date now = new Date();
            String status = "";
            if (now.after(c.getNotAfter())) status = "  (expired)";
            else if (now.before(c.getNotBefore())) status = "  (not valid yet)";
            out.add("Valid: " + f.format(c.getNotBefore()) + " to " + f.format(c.getNotAfter()) + " UTC" + status);
        } catch (Exception ignored) { /* skip line */ }
        try {
            final Collection<List<?>> sans = c.getSubjectAlternativeNames();
            if (sans != null && !sans.isEmpty()) {
                final StringBuilder names = new StringBuilder();
                boolean listed = false;
                final byte[] hostIp = LocalAddress.ipBytes(host);
                for (List<?> san : sans) {
                    if (san == null || san.size() < 2 || !(san.get(0) instanceof Integer)) continue;
                    final int type = (Integer) san.get(0);
                    final String v = String.valueOf(san.get(1));
                    if (type != 2 && type != 7) continue;               // DNS name / IP address
                    if (names.length() > 0) names.append(", ");
                    names.append(type == 2 ? "DNS:" : "IP:").append(v);
                    if (host != null) {
                        if (type == 2 && v.equalsIgnoreCase(host)) listed = true;
                        if (type == 7 && hostIp != null && Arrays.equals(hostIp, LocalAddress.ipBytes(v.toLowerCase(Locale.ROOT)))) listed = true;
                    }
                }
                if (names.length() > 0) {
                    out.add("Names: " + names);
                    if (host != null && !listed) out.add("This certificate doesn\u2019t list " + host);
                }
            }
        } catch (Exception ignored) { /* skip line */ }
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            final StringBuilder fp = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) fp.append(':');
                fp.append(String.format(Locale.ROOT, "%02X", d[i] & 0xff));
            }
            out.add("SHA-256: " + fp);
        } catch (Exception ignored) { /* skip line */ }
        return out;
    }
}
