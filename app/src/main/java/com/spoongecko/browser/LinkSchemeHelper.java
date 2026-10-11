package com.spoongecko.browser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * Routes non-web URIs (magnet:, tel:, mailto:, market:, intent:, custom app
 * schemes) to the OS so an external app can handle them.
 *
 * Never throws. Never loops back into SpoonGecko. If nothing can handle the
 * URI, shows a brief toast instead of leaving GeckoView stuck on a broken
 * navigation — this is the fix for the GeckoView equivalent of the "magnet
 * link freeze" that plagued the WebView-based Spoon Browser.
 */
public final class LinkSchemeHelper {

    private LinkSchemeHelper() {}

    /** True if the URI was handed off to an external app (or a fallback URL was). */
    public static boolean dispatch(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isEmpty()) return false;

        Intent intent;
        if ("intent".equalsIgnoreCase(scheme)) {
            try {
                intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
            } catch (Exception e) {
                toast(ctx, "Cannot open link");
                return false;
            }
        } else {
            intent = new Intent(Intent.ACTION_VIEW, uri);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            if (intent.resolveActivity(ctx.getPackageManager()) != null) {
                ctx.startActivity(intent);
                return true;
            }
            // Intent URIs often carry an HTTP fallback for when no app is installed.
            String fallback = intent.getStringExtra("browser_fallback_url");
            if (fallback != null && !fallback.isEmpty()) {
                Intent fb = new Intent(Intent.ACTION_VIEW, Uri.parse(fallback));
                fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (fb.resolveActivity(ctx.getPackageManager()) != null) {
                    ctx.startActivity(fb);
                    return true;
                }
            }
            toast(ctx, "No app can open this link");
            return false;
        } catch (Exception e) {
            toast(ctx, "Cannot open link");
            return false;
        }
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }
}
