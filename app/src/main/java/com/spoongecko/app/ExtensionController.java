package com.spoongecko.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.List;

/**
 * ExtensionController centralises all WebExtension lifecycle operations for SpoonGecko.
 *
 * <p>Design goals:
 * <ul>
 *   <li>Single place for install / uninstall / enable / disable / update logic.</li>
 *   <li>Persists per-extension enabled state across restarts via SharedPreferences.</li>
 *   <li>Respects the {@code EXTENSIONS_ENABLED} build flag; all public methods are
 *       safe no-ops when the feature is disabled.</li>
 *   <li>All GeckoResult callbacks are dispatched to the provided callback so callers
 *       can update UI from the main thread.</li>
 * </ul>
 *
 * <p>Security notes:
 * <ul>
 *   <li>Extension sources are validated: only {@code content://} (file-picker) and
 *       {@code https://} URIs are accepted for install; plain {@code http://} is
 *       rejected to prevent MITM injection of extension code.</li>
 *   <li>This class does not persist or log extension tokens or credentials.</li>
 * </ul>
 */
public final class ExtensionController {

    private static final String TAG = "ExtensionController";
    private static final String PREFS_NAME = "extension_prefs";
    private static final String PREF_PREFIX_ENABLED = "ext_enabled_";

    /** Callbacks used to report outcomes back to the UI layer. */
    public interface Callback {
        void onSuccess(String message);
        void onError(String message);
    }

    private ExtensionController() {}

    // ------------------------------------------------------------------ install

    /**
     * Installs a WebExtension from the given URI string.
     *
     * <p>Accepted schemes: {@code content://} (device storage picker) and
     * {@code https://} (remote URL). {@code http://} is rejected.
     *
     * @param uriString  URI pointing to an .xpi file
     * @param runtime    current GeckoRuntime
     * @param callback   result callback (called on GeckoView worker thread; post to main if needed)
     */
    public static void install(String uriString, GeckoRuntime runtime, Callback callback) {
        if (!BuildConfig.EXTENSIONS_ENABLED) {
            callback.onError("Extension support is disabled in this build.");
            return;
        }
        if (runtime == null) {
            callback.onError("Browser not initialised.");
            return;
        }
        if (!isAllowedSource(uriString)) {
            callback.onError("Install blocked: only content:// and https:// sources are permitted.");
            return;
        }
        runtime.getWebExtensionController()
                .install(uriString)
                .accept(
                        ext -> {
                            Log.i(TAG, "Installed: " + ext.id);
                            callback.onSuccess("Extension installed.");
                        },
                        e -> {
                            String msg = e != null ? e.getMessage() : "Unknown error";
                            Log.e(TAG, "Install failed: " + msg);
                            callback.onError(formatInstallError(msg));
                        });
    }

    // ----------------------------------------------------------------- uninstall

    /**
     * Uninstalls the given extension and removes its stored enabled preference.
     */
    public static void uninstall(WebExtension ext, GeckoRuntime runtime,
                                 Context context, Callback callback) {
        if (!BuildConfig.EXTENSIONS_ENABLED) {
            callback.onError("Extension support is disabled in this build.");
            return;
        }
        if (runtime == null || ext == null) {
            callback.onError("Invalid state.");
            return;
        }
        runtime.getWebExtensionController()
                .uninstall(ext)
                .accept(
                        result -> {
                            clearEnabledPref(context, ext.id);
                            Log.i(TAG, "Uninstalled: " + ext.id);
                            callback.onSuccess("Extension removed.");
                        },
                        e -> {
                            String msg = e != null ? e.getMessage() : "Unknown error";
                            Log.e(TAG, "Uninstall failed: " + msg);
                            callback.onError("Failed to remove extension: " + msg);
                        });
    }

    // ------------------------------------------------------------------- enable

    /**
     * Enables a previously disabled extension and persists the state.
     */
    public static void enable(WebExtension ext, GeckoRuntime runtime,
                              Context context, Callback callback) {
        if (!BuildConfig.EXTENSIONS_ENABLED) {
            callback.onError("Extension support is disabled in this build.");
            return;
        }
        if (runtime == null || ext == null) {
            callback.onError("Invalid state.");
            return;
        }
        runtime.getWebExtensionController()
                .enable(ext, WebExtensionController.EnableSource.USER)
                .accept(
                        updated -> {
                            setEnabledPref(context, ext.id, true);
                            Log.i(TAG, "Enabled: " + ext.id);
                            callback.onSuccess("Extension enabled.");
                        },
                        e -> {
                            String msg = e != null ? e.getMessage() : "Unknown error";
                            Log.e(TAG, "Enable failed: " + msg);
                            callback.onError("Failed to enable extension: " + msg);
                        });
    }

    // ------------------------------------------------------------------ disable

    /**
     * Disables an extension (keeps it installed) and persists the state.
     */
    public static void disable(WebExtension ext, GeckoRuntime runtime,
                               Context context, Callback callback) {
        if (!BuildConfig.EXTENSIONS_ENABLED) {
            callback.onError("Extension support is disabled in this build.");
            return;
        }
        if (runtime == null || ext == null) {
            callback.onError("Invalid state.");
            return;
        }
        runtime.getWebExtensionController()
                .disable(ext, WebExtensionController.EnableSource.USER)
                .accept(
                        updated -> {
                            setEnabledPref(context, ext.id, false);
                            Log.i(TAG, "Disabled: " + ext.id);
                            callback.onSuccess("Extension disabled.");
                        },
                        e -> {
                            String msg = e != null ? e.getMessage() : "Unknown error";
                            Log.e(TAG, "Disable failed: " + msg);
                            callback.onError("Failed to disable extension: " + msg);
                        });
    }

    // -------------------------------------------------------------------- list

    /**
     * Lists installed extensions. The result list is passed to the provided {@link ListCallback}.
     */
    public interface ListCallback {
        void onResult(List<WebExtension> extensions);
        void onError(String message);
    }

    public static void list(GeckoRuntime runtime, ListCallback callback) {
        if (!BuildConfig.EXTENSIONS_ENABLED) {
            callback.onError("Extension support is disabled in this build.");
            return;
        }
        if (runtime == null) {
            callback.onError("Browser not initialised.");
            return;
        }
        runtime.getWebExtensionController()
                .list()
                .accept(
                        callback::onResult,
                        e -> {
                            String msg = e != null ? e.getMessage() : "Unknown error";
                            Log.e(TAG, "List failed: " + msg);
                            callback.onError("Failed to list extensions: " + msg);
                        });
    }

    // ------------------------------------------------------ enabled state helpers

    /**
     * Returns whether the extension with the given id was last persisted as enabled.
     * Defaults to {@code true} (extensions are enabled by default when first installed).
     */
    public static boolean isEnabledInPrefs(Context context, String extensionId) {
        if (extensionId == null) return true;
        return getPrefs(context).getBoolean(PREF_PREFIX_ENABLED + extensionId, true);
    }

    static void setEnabledPref(Context context, String extensionId, boolean enabled) {
        if (extensionId == null) return;
        getPrefs(context).edit().putBoolean(PREF_PREFIX_ENABLED + extensionId, enabled).apply();
    }

    static void clearEnabledPref(Context context, String extensionId) {
        if (extensionId == null) return;
        getPrefs(context).edit().remove(PREF_PREFIX_ENABLED + extensionId).apply();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Returns a human-readable label for an extension, falling back gracefully.
     */
    public static String getDisplayName(WebExtension ext) {
        if (ext == null) return "Unknown";
        if (ext.metaData != null && ext.metaData.name != null && !ext.metaData.name.isEmpty()) {
            return ext.metaData.name;
        }
        return ext.id != null ? ext.id : "Unknown";
    }

    /**
     * Returns the version string for an extension, or an empty string if unavailable.
     */
    public static String getVersion(WebExtension ext) {
        if (ext == null || ext.metaData == null || ext.metaData.version == null) return "";
        return ext.metaData.version;
    }

    /**
     * Returns whether the extension is currently enabled according to GeckoView metadata.
     * Falls back to {@code true} when metadata is unavailable (e.g. right after install).
     */
    public static boolean isEnabled(WebExtension ext) {
        if (ext == null || ext.metaData == null) return true;
        return ext.metaData.enabled;
    }

    // ----------------------------------------------------------------- private

    static boolean isAllowedSource(String uri) {
        if (uri == null) return false;
        return uri.startsWith("content://") || uri.startsWith("https://");
    }

    static String formatInstallError(String raw) {
        if (raw == null) return "Install failed.";
        if (raw.contains("ERROR_CORRUPT_FILE") || raw.contains("corrupt")) {
            return "Install failed: the .xpi file is corrupted.";
        }
        if (raw.contains("ERROR_INCOMPATIBLE") || raw.contains("incompatible")) {
            return "Install failed: extension is not compatible with this browser version.";
        }
        if (raw.contains("ERROR_SIGNEDSTATE") || raw.contains("sign")) {
            return "Install failed: extension must be signed or the source is untrusted.";
        }
        if (raw.contains("ERROR_NETWORK") || raw.contains("network")) {
            return "Install failed: network error. Check your connection and try again.";
        }
        return "Install failed: " + raw;
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
