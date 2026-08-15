package com.spoongecko.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.List;

public final class ExtensionController {

    private static final String TAG = "ExtensionController";
    private static final String PREFS_NAME = "extension_prefs";
    private static final String PREF_PREFIX_ENABLED = "ext_enabled_";

    public interface Callback {
        void onSuccess(String message);
        void onError(String message);
    }

    public interface ListCallback {
        void onResult(List<WebExtension> extensions);
        void onError(String message);
    }

    private ExtensionController() {}

    public static void install(Context context, String uriString, GeckoRuntime runtime, Callback callback) {
        if (!BuildConfig.EXTENSIONS_ENABLED) {
            callback.onError(context.getString(R.string.extension_disabled_build));
            return;
        }
        if (runtime == null) {
            callback.onError(context.getString(R.string.extension_browser_not_initialised));
            return;
        }
        if (!isAllowedSource(uriString)) {
            callback.onError(context.getString(R.string.extension_install_blocked));
            return;
        }
        runtime.getWebExtensionController()
                .install(uriString, WebExtensionController.INSTALLATION_METHOD_FROM_FILE)
                .accept(
                        ext -> {
                            Log.i(TAG, "Installed: " + ext.id);
                            callback.onSuccess(context.getString(R.string.extension_installed));
                        },
                        e -> {
                            String msg = e != null ? e.getMessage() : "Unknown error";
                            Log.e(TAG, "Install failed: " + msg);
                            callback.onError(formatInstallError(context, msg));
                        });
    }

    public static void uninstall(WebExtension ext, GeckoRuntime runtime,
                                 Context context, Callback callback) {
        if (!BuildConfig.EXTENSIONS_ENABLED) {
            callback.onError(context.getString(R.string.extension_disabled_build));
            return;
        }
        if (runtime == null || ext == null) {
            callback.onError(context.getString(R.string.extension_invalid_state));
            return;
        }
        runtime.getWebExtensionController()
                .uninstall(ext)
                .accept(
                        result -> {
                            clearEnabledPref(context, ext.id);
                            Log.i(TAG, "Uninstalled: " + ext.id);
                            callback.onSuccess(context.getString(R.string.extension_removed));
                        },
                        e -> {
                            String msg = e != null ? e.getMessage() : "Unknown error";
                            Log.e(TAG, "Uninstall failed: " + msg);
                            callback.onError(context.getString(R.string.extension_failed_remove, msg));
                        });
    }

    public static void enable(WebExtension ext, GeckoRuntime runtime,
                              Context context, Callback callback) {
        if (!BuildConfig.EXTENSIONS_ENABLED) {
            callback.onError(context.getString(R.string.extension_disabled_build));
            return;
        }
        if (runtime == null || ext == null) {
            callback.onError(context.getString(R.string.extension_invalid_state));
            return;
        }
        runtime.getWebExtensionController()
                .enable(ext, WebExtensionController.EnableSource.USER)
                .accept(
                        updated -> {
                            setEnabledPref(context, ext.id, true);
                            Log.i(TAG, "Enabled: " + ext.id);
                            callback.onSuccess(context.getString(R.string.extension_enabled));
                        },
                        e -> {
                            String msg = e != null ? e.getMessage() : "Unknown error";
                            Log.e(TAG, "Enable failed: " + msg);
                            callback.onError(context.getString(R.string.extension_failed_enable, msg));
                        });
    }

    public static void disable(WebExtension ext, GeckoRuntime runtime,
                               Context context, Callback callback) {
        if (!BuildConfig.EXTENSIONS_ENABLED) {
            callback.onError(context.getString(R.string.extension_disabled_build));
            return;
        }
        if (runtime == null || ext == null) {
            callback.onError(context.getString(R.string.extension_invalid_state));
            return;
        }
        runtime.getWebExtensionController()
                .disable(ext, WebExtensionController.EnableSource.USER)
                .accept(
                        updated -> {
                            setEnabledPref(context, ext.id, false);
                            Log.i(TAG, "Disabled: " + ext.id);
                            callback.onSuccess(context.getString(R.string.extension_disabled));
                        },
                        e -> {
                            String msg = e != null ? e.getMessage() : "Unknown error";
                            Log.e(TAG, "Disable failed: " + msg);
                            callback.onError(context.getString(R.string.extension_failed_disable, msg));
                        });
    }

    public static void list(Context context, GeckoRuntime runtime, ListCallback callback) {
        if (!BuildConfig.EXTENSIONS_ENABLED) {
            callback.onError(context.getString(R.string.extension_disabled_build));
            return;
        }
        if (runtime == null) {
            callback.onError(context.getString(R.string.extension_browser_not_initialised));
            return;
        }
        runtime.getWebExtensionController()
                .list()
                .accept(
                        callback::onResult,
                        e -> {
                            String msg = e != null ? e.getMessage() : "Unknown error";
                            Log.e(TAG, "List failed: " + msg);
                            callback.onError(context.getString(R.string.extension_failed_list, msg));
                        });
    }

    public static boolean isEnabledInPrefs(Context context, String extensionId) {
        if (context == null || extensionId == null) return true;
        return getPrefs(context).getBoolean(PREF_PREFIX_ENABLED + extensionId, true);
    }

    static void setEnabledPref(Context context, String extensionId, boolean enabled) {
        if (context == null || extensionId == null) return;
        getPrefs(context).edit().putBoolean(PREF_PREFIX_ENABLED + extensionId, enabled).apply();
    }

    static void clearEnabledPref(Context context, String extensionId) {
        if (context == null || extensionId == null) return;
        getPrefs(context).edit().remove(PREF_PREFIX_ENABLED + extensionId).apply();
    }

    public static String getDisplayName(WebExtension ext) {
        if (ext == null) return "Unknown";
        if (ext.metaData != null && ext.metaData.name != null && !ext.metaData.name.isEmpty()) {
            return ext.metaData.name;
        }
        return ext.id != null ? ext.id : "Unknown";
    }

    public static String getVersion(WebExtension ext) {
        if (ext == null || ext.metaData == null || ext.metaData.version == null) return "";
        return ext.metaData.version;
    }

    public static boolean isEnabled(WebExtension ext) {
        if (ext == null || ext.metaData == null) return true;
        Boolean enabled = ext.metaData.enabled;
        return enabled == null ? true : enabled;
    }

    static boolean isAllowedSource(String uri) {
        if (uri == null) return false;
        return uri.startsWith("https://") || uri.startsWith("file://");
    }

    static String formatInstallError(Context context, String raw) {
        if (raw == null) return context.getString(R.string.extension_failed_install_unknown);
        if (raw.contains("ERROR_CORRUPT_FILE") || raw.contains("corrupt")) {
            return context.getString(R.string.extension_failed_install_corrupt);
        }
        if (raw.contains("ERROR_INCOMPATIBLE") || raw.contains("incompatible")) {
            return context.getString(R.string.extension_failed_install_incompatible);
        }
        if (raw.contains("ERROR_SIGNEDSTATE") || raw.contains("signed")) {
            return context.getString(R.string.extension_failed_install_signed_state);
        }
        if (raw.contains("ERROR_NETWORK") || raw.contains("network")) {
            return context.getString(R.string.extension_failed_install_network);
        }
        return context.getString(R.string.extension_failed_install_generic, raw);
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
