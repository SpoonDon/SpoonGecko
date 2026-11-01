package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Owns everything related to WebExtensions:
 *  - creates the shared GeckoRuntime (extension settings enabled)
 *  - installs / uninstalls / enables / disables / updates extensions
 *  - shows the install / update / optional-permission prompts
 *  - renders browser/page action buttons in the toolbar and their popups
 *  - backs up and restores the set of installed extensions
 *  - schedules automatic update checks (WorkManager, 12 h period)
 *
 * All GeckoView calls happen on the main thread (GeckoView requires a thread
 * with a Looper, and delegates are called back on the UI thread).
 */
public final class ExtensionManager {

    private static final String TAG = "SpoonGecko";

    /** Implemented by MainActivity: how the manager opens tabs for extensions. */
    public interface Host {
        GeckoSession openExtensionTab(String url);
    }

    /** Implemented by UI components that want to refresh when extensions change. */
    public interface Listener {
        void onExtensionsChanged();
    }

    /** Result of a "check all extensions for updates" run. */
    public interface UpdateSummaryCallback {
        void onUpdateCheckFinished(int updated, int failed, int checked);
    }

    /** Result of a restore-from-backup run. */
    public interface RestoreSummaryCallback {
        void onRestoreFinished(int restored, int failed, String failures);
    }

    private interface PermissionOutcome {
        void onOutcome(boolean allow);
    }

    private static final String PREFS_NAME = "spoongecko_prefs";
    private static final String KEY_AUTO_UPDATE = "ext_autoupdate";
    private static final String KEY_LAST_CHECK = "ext_last_check";
    private static final String KEY_INSTALL_URLS = "ext_install_urls";
    private static final String UNIQUE_WORK_NAME = "spoongecko-extension-updates";
    private static final long STARTUP_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final long BACKGROUND_CHECK_PERIOD_HOURS = 12L;

    private static final ExtensionManager INSTANCE = new ExtensionManager();

    public static ExtensionManager get() { return INSTANCE; }

    private ExtensionManager() {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, WebExtension> installed = new LinkedHashMap<>();
    private final Map<String, WebExtension.Action> toolbarActions = new LinkedHashMap<>();
    private final Map<Activity, List<Dialog>> dialogsByActivity = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();

    private Context appContext;
    private GeckoRuntime runtime;
    private WebExtensionController controller;
    private Host host;
    private Activity currentActivity;
    private LinearLayout toolbarContainer;
    private GeckoSession activeSession;
    private int toolbarGeneration = 0;

    // ------------------------------------------------------------------
    // Runtime
    // ------------------------------------------------------------------

    /**
     * Creates the process-wide GeckoRuntime exactly once. The settings were
     * moved here from MainActivity so that background update checks can create
     * the same runtime without any UI.
     */
    public static synchronized GeckoRuntime ensureRuntime(Context context) {
        ExtensionManager em = INSTANCE;
        if (em.runtime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .contentBlocking(new ContentBlocking.Settings.Builder()
                            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                            .build())
                    .trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
                    .fissionEnabled(true)
                    .remoteDebuggingEnabled(false)
                    // --- WebExtensions ---
                    .extensionsWebAPIEnabled(true)   // AMO pages may trigger installs
                    .extensionsProcessEnabled(true)  // run extensions in their own process
                    .build();
            em.runtime = GeckoRuntime.create(context.getApplicationContext(), settings);
        }
        return em.runtime;
    }

    public boolean isInitialized() { return controller != null; }

    /** Idempotent: registers all delegates and refreshes the extension list. */
    public synchronized void initialize(Context context) {
        if (appContext == null) appContext = context.getApplicationContext();
        if (controller == null) {
            controller = ensureRuntime(appContext).getWebExtensionController();
            controller.setPromptDelegate(promptDelegate);
            controller.setAddonManagerDelegate(addonManagerDelegate);
            controller.setExtensionProcessDelegate(extensionProcessDelegate);
        }
        refresh();
        applyAutoUpdateSchedule();
    }

    // ------------------------------------------------------------------
    // List / state
    // ------------------------------------------------------------------

    public void setHost(Host h) { this.host = h; }

    public void addListener(Listener l) { if (l != null) listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    public List<WebExtension> getInstalled() { return new ArrayList<>(installed.values()); }

    public WebExtension find(String id) { return id != null ? installed.get(id) : null; }

    /** Re-reads the extension list from GeckoView and re-attaches delegates. */
    public void refresh() {
        final WebExtensionController c = controller;
        if (c == null) return;
        c.list().accept(extensions -> {
            installed.clear();
            if (extensions != null) {
                for (WebExtension ext : extensions) {
                    installed.put(ext.id, ext);
                    attachDelegates(ext);
                }
            }
            rebuildToolbar();
            notifyListeners();
        }, error -> Log.w(TAG, "listing extensions failed", error));
    }

    private void attachDelegates(WebExtension ext) {
        if (ext == null) return;
        try {
            ext.setTabDelegate(tabDelegate);
            ext.setActionDelegate(actionDelegate);
        } catch (Exception e) {
            Log.w(TAG, "attachDelegates failed for " + ext.id, e);
        }
    }

    private void notifyListeners() {
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) {
                try { l.onExtensionsChanged(); } catch (Exception e) {
                    Log.w(TAG, "listener failed", e);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Install / uninstall / toggle / update
    // ------------------------------------------------------------------

    public void installFromUrl(String url, GeckoResult.Consumer<WebExtension> onDone) {
        if (controller == null || url == null || url.trim().isEmpty()) return;
        final String target = url.trim();
        controller.install(target, WebExtensionController.INSTALLATION_METHOD_MANAGER)
                .accept(ext -> {
                    if (ext != null) {
                        recordInstallUrl(ext.id, target);
                        attachDelegates(ext);
                    }
                    if (onDone != null) onDone.accept(ext);
                }, error -> {
                    Log.w(TAG, "install failed: " + target, error);
                    toast("Install failed: " + installErrorText(error));
                    if (onDone != null) onDone.accept(null);
                });
    }

    /** Installs a local .xpi copied into the app cache dir (file:// install). */
    public void installFromFile(File xpi, GeckoResult.Consumer<WebExtension> onDone) {
        if (controller == null || xpi == null || !xpi.exists()) return;
        final String uri = Uri.fromFile(xpi).toString();
        controller.install(uri, WebExtensionController.INSTALLATION_METHOD_FROM_FILE)
                .accept(ext -> {
                    if (ext != null) attachDelegates(ext);
                    xpi.delete();
                    if (onDone != null) onDone.accept(ext);
                }, error -> {
                    Log.w(TAG, "install failed: " + uri, error);
                    toast("Install failed: " + installErrorText(error));
                    xpi.delete();
                    if (onDone != null) onDone.accept(null);
                });
    }

    /** Turns an extension on/off (persisted by GeckoView across restarts). */
    public void setExtensionEnabled(WebExtension ext, boolean enabled,
                                    GeckoResult.Consumer<WebExtension> onDone) {
        if (controller == null || ext == null) return;
        GeckoResult<WebExtension> op = enabled
                ? controller.enable(ext, WebExtensionController.EnableSource.USER)
                : controller.disable(ext, WebExtensionController.EnableSource.USER);
        op.accept(updated -> {
            if (updated != null) {
                installed.put(updated.id, updated);
                attachDelegates(updated);
            }
            rebuildToolbar();
            notifyListeners();
            if (onDone != null) onDone.accept(updated);
        }, error -> {
            Log.w(TAG, "enable/disable failed", error);
            toast("Could not change the extension state");
            notifyListeners();
            if (onDone != null) onDone.accept(null);
        });
    }

    public void uninstall(WebExtension ext, GeckoResult.Consumer<Void> onDone) {
        if (controller == null || ext == null) return;
        controller.uninstall(ext).accept(v -> {
            installed.remove(ext.id);
            toolbarActions.remove(ext.id);
            rebuildToolbar();
            notifyListeners();
            if (onDone != null) onDone.accept(v);
        }, error -> {
            Log.w(TAG, "uninstall failed", error);
            toast("Uninstall failed");
            if (onDone != null) onDone.accept(null);
        });
    }

    /** Updates one extension. GeckoResult resolves null when already current. */
    public void updateExtension(WebExtension ext, GeckoResult.Consumer<WebExtension> onDone) {
        if (controller == null || ext == null) return;
        controller.update(ext).accept(updated -> {
            if (updated != null) {
                installed.put(updated.id, updated);
                attachDelegates(updated);
                toast(displayName(updated) + " updated" + versionSuffix(updated));
            } else {
                toast(displayName(ext) + " is up to date");
            }
            notifyListeners();
            if (onDone != null) onDone.accept(updated);
        }, error -> {
            Log.w(TAG, "update failed", error);
            toast("Update failed: " + installErrorText(error));
            if (onDone != null) onDone.accept(null);
        });
    }

    /** Checks every installed extension for updates, one after another. */
    public void checkForUpdatesAll(final UpdateSummaryCallback callback) {
        final WebExtensionController c = controller;
        if (c == null) {
            if (callback != null) callback.onUpdateCheckFinished(0, 0, 0);
            return;
        }
        final List<WebExtension> snapshot = new ArrayList<>(installed.values());
        updateNext(c, snapshot, 0, 0, 0, callback);
    }

    private void updateNext(final WebExtensionController c, final List<WebExtension> list,
                            final int index, final int updated, final int failed,
                            final UpdateSummaryCallback callback) {
        if (index >= list.size()) {
            if (appContext != null) {
                prefs().edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
            }
            refresh();   // also notifies listeners
            if (callback != null) callback.onUpdateCheckFinished(updated, failed, list.size());
            return;
        }
        final WebExtension ext = list.get(index);
        if (ext.isBuiltIn) {
            updateNext(c, list, index + 1, updated, failed, callback);
            return;
        }
        c.update(ext).accept(res -> {
            if (res != null) {
                attachDelegates(res);
                toast(displayName(res) + " updated" + versionSuffix(res));
            }
            updateNext(c, list, index + 1, res != null ? updated + 1 : updated, failed, callback);
        }, error -> {
            Log.w(TAG, "update failed for " + ext.id, error);
            updateNext(c, list, index + 1, updated, failed + 1, callback);
        });
    }

    // ------------------------------------------------------------------
    // Automatic updates
    // ------------------------------------------------------------------

    /** Silent check at startup, at most every 6 hours. */
    public void maybeCheckForUpdatesOnStartup() {
        if (!isAutoUpdateEnabled() || appContext == null) return;
        long last = prefs().getLong(KEY_LAST_CHECK, 0L);
        if (System.currentTimeMillis() - last < STARTUP_CHECK_INTERVAL_MS) return;
        main.postDelayed(() -> {
            if (controller != null) checkForUpdatesAll(null);
        }, 4000);
    }

    public boolean isAutoUpdateEnabled() {
        if (appContext == null) return true;
        return prefs().getBoolean(KEY_AUTO_UPDATE, true);
    }

    public void setAutoUpdateEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply();
        applyAutoUpdateSchedule();
        notifyListeners();
    }

    /** Periodic background check every 12 h (like Firefox for Android). */
    private void applyAutoUpdateSchedule() {
        final Context c = appContext;
        if (c == null) return;
        try {
            WorkManager wm = WorkManager.getInstance(c);
            if (isAutoUpdateEnabled()) {
                PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                        ExtensionUpdateWorker.class,
                        BACKGROUND_CHECK_PERIOD_HOURS, TimeUnit.HOURS)
                        .build();
                wm.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP, request);
            } else {
                wm.cancelUniqueWork(UNIQUE_WORK_NAME);
            }
        } catch (Exception e) {
            Log.w(TAG, "scheduling extension updates failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Backup / restore
    // ------------------------------------------------------------------

    /** Serialises the installed set (id, name, version, url, enabled) to JSON. */
    public String buildBackupJson() {
        try {
            JSONArray arr = new JSONArray();
            for (WebExtension ext : installed.values()) {
                if (ext.isBuiltIn) continue;
                JSONObject o = new JSONObject();
                o.put("id", ext.id);
                o.put("name", displayName(ext));
                o.put("version", ext.metaData != null && ext.metaData.version != null
                        ? ext.metaData.version : "");
                o.put("enabled", ext.metaData == null || ext.metaData.enabled);
                String url = getInstallUrl(ext);
                o.put("url", url != null ? url : "");
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("app", "SpoonGecko");
            root.put("format", 1);
            root.put("created", System.currentTimeMillis());
            root.put("extensions", arr);
            return root.toString(2);
        } catch (Exception e) {
            Log.w(TAG, "buildBackup failed", e);
            return null;
        }
    }

    /** Where to re-download this extension from (recorded URL first, then AMO). */
    public String getInstallUrl(WebExtension ext) {
        try {
            JSONObject map = new JSONObject(prefs().getString(KEY_INSTALL_URLS, "{}"));
            String recorded = map.optString(ext.id, "");
            if (recorded.startsWith("http")) return recorded;
        } catch (Exception ignored) {}
        if (ext.metaData != null && ext.metaData.downloadUrl != null
                && ext.metaData.downloadUrl.startsWith("http")) {
            return ext.metaData.downloadUrl;
        }
        return null;
    }

    private void recordInstallUrl(String id, String url) {
        if (id == null || url == null || !url.startsWith("http")) return;  // file:// is not portable
        try {
            JSONObject map = new JSONObject(prefs().getString(KEY_INSTALL_URLS, "{}"));
            map.put(id, url);
            prefs().edit().putString(KEY_INSTALL_URLS, map.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "recordInstallUrl failed", e);
        }
    }

    /** Reinstalls the extensions from a backup, restoring the enabled state. */
    public void restoreFromBackup(final JSONArray entries, final RestoreSummaryCallback callback) {
        if (controller == null) {
            if (callback != null) callback.onRestoreFinished(0, 0, "Extensions not initialised");
            return;
        }
        restoreNext(entries, 0, 0, 0, "", callback);
    }

    private void restoreNext(final JSONArray entries, final int index, final int restored,
                             final int failed, final String failures,
                             final RestoreSummaryCallback callback) {
        if (index >= entries.length()) {
            refresh();
            if (callback != null) callback.onRestoreFinished(restored, failed, failures.trim());
            return;
        }
        JSONObject o = entries.optJSONObject(index);
        if (o == null) {
            restoreNext(entries, index + 1, restored, failed, failures, callback);
            return;
        }
        final String id = o.optString("id", "");
        final String url = o.optString("url", "");
        final boolean wantEnabled = o.optBoolean("enabled", true);
        final String name = o.optString("name", id);

        WebExtension existing = installed.get(id);
        if (existing != null) {
            boolean isEnabled = existing.metaData != null && existing.metaData.enabled;
            if (isEnabled != wantEnabled) setExtensionEnabled(existing, wantEnabled, null);
            restoreNext(entries, index + 1, restored + 1, failed, failures, callback);
            return;
        }
        if (!url.startsWith("http")) {
            restoreNext(entries, index + 1, restored, failed + 1,
                    failures + name + ": no download URL\n", callback);
            return;
        }
        controller.install(url, WebExtensionController.INSTALLATION_METHOD_MANAGER)
                .accept(ext -> {
                    if (ext != null) {
                        recordInstallUrl(ext.id, url);
                        attachDelegates(ext);
                        installed.put(ext.id, ext);
                        if (!wantEnabled) setExtensionEnabled(ext, false, null);
                    }
                    restoreNext(entries, index + 1, ext != null ? restored + 1 : restored,
                            failed, failures, callback);
                }, error -> restoreNext(entries, index + 1, restored, failed + 1,
                        failures + name + ": " + installErrorText(error) + "\n", callback));
    }

    // ------------------------------------------------------------------
    // Delegates: install / update prompts (MUST be registered for installs
    // to work at all — GeckoView fails the install without a prompt delegate)
    // ------------------------------------------------------------------

    private final WebExtensionController.PromptDelegate promptDelegate =
            new WebExtensionController.PromptDelegate() {

        @Override
        public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                WebExtension extension, String[] permissions, String[] origins,
                String[] dataCollectionPermissions) {
            Activity activity = currentActivity;
            if (!isUiAvailable(activity)) {
                // Nothing can be shown (e.g. background install attempt):
                // refuse instead of leaving the install hanging forever.
                return GeckoResult.fromValue(
                        new WebExtension.PermissionPromptResponse(false, false, false));
            }
            final GeckoResult<WebExtension.PermissionPromptResponse> result = new GeckoResult<>();
            showPermissionPrompt(activity, extension,
                    "Add " + displayName(extension) + "?", "Add",
                    permissions, origins, dataCollectionPermissions,
                    allow -> result.complete(new WebExtension.PermissionPromptResponse(
                            allow, allow, allow)));
            return result;
        }

        @Override
        public GeckoResult<AllowOrDeny> onUpdatePrompt(
                WebExtension extension, String[] newPermissions, String[] newOrigins,
                String[] newDataCollectionPermissions) {
            Activity activity = currentActivity;
            if (!isUiAvailable(activity)) return GeckoResult.deny();
            final GeckoResult<AllowOrDeny> result = new GeckoResult<>();
            showPermissionPrompt(activity, extension,
                    displayName(extension) + " was updated", "Update",
                    newPermissions, newOrigins, newDataCollectionPermissions,
                    allow -> result.complete(allow ? AllowOrDeny.ALLOW : AllowOrDeny.DENY));
            return result;
        }

        @Override
        public GeckoResult<AllowOrDeny> onOptionalPrompt(
                WebExtension extension, String[] permissions, String[] origins,
                String[] dataCollectionPermissions) {
            Activity activity = currentActivity;
            if (!isUiAvailable(activity)) return GeckoResult.deny();
            final GeckoResult<AllowOrDeny> result = new GeckoResult<>();
            showPermissionPrompt(activity, extension,
                    displayName(extension) + " requests permissions", "Grant",
                    permissions, origins, dataCollectionPermissions,
                    allow -> result.complete(allow ? AllowOrDeny.ALLOW : AllowOrDeny.DENY));
            return result;
        }
    };

    /** Fired for every extension state change (install, update, toggle, ...). */
    private final WebExtensionController.AddonManagerDelegate addonManagerDelegate =
            new WebExtensionController.AddonManagerDelegate() {
        @Override public void onInstalling(WebExtension ext) {}
        @Override public void onInstalled(WebExtension ext) { refresh(); }
        @Override public void onInstallationFailed(WebExtension ext,
                WebExtension.InstallException error) {
            toast("Install failed: " + installErrorText(error));
            refresh();
        }
        @Override public void onReady(WebExtension ext) { refresh(); }
        @Override public void onDisabling(WebExtension ext) {}
        @Override public void onDisabled(WebExtension ext) { refresh(); }
        @Override public void onEnabling(WebExtension ext) {}
        @Override public void onEnabled(WebExtension ext) { refresh(); }
        @Override public void onUninstalling(WebExtension ext) {}
        @Override public void onUninstalled(WebExtension ext) {
            installed.remove(ext.id);
            toolbarActions.remove(ext.id);
            rebuildToolbar();
            notifyListeners();
        }
        @Override public void onOptionalPermissionsChanged(WebExtension ext) { refresh(); }
    };

    /** Extension process crashed too often — offer to re-enable it. */
    private final WebExtensionController.ExtensionProcessDelegate extensionProcessDelegate =
            new WebExtensionController.ExtensionProcessDelegate() {
        @Override public void onDisabledProcessSpawning() {
            Activity activity = currentActivity;
            if (!isUiAvailable(activity) || controller == null) return;
            LinearLayout content = dialogContent(activity, 20);
            TextView title = new TextView(activity);
            title.setText("Extensions disabled");
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(17);
            title.setGravity(Gravity.CENTER);
            content.addView(title);
            TextView msg = new TextView(activity);
            msg.setText("The extension process was disabled after repeated crashes. "
                    + "Re-enable extensions?");
            msg.setTextColor(0xB3FFFFFF);
            msg.setTextSize(13);
            msg.setPadding(0, dp(activity, 8), 0, 0);
            content.addView(msg);

            TextView reenable = dialogButton(activity, "Re-enable", false, null);
            TextView keep = dialogButton(activity, "Keep disabled", true, null);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(activity, 44), 1f);
            lp1.topMargin = dp(activity, 12);
            lp1.setMarginEnd(dp(activity, 4));
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(activity, 44), 1f);
            lp2.topMargin = dp(activity, 12);
            lp2.setMarginStart(dp(activity, 4));
            row.addView(reenable, lp1);
            row.addView(keep, lp2);
            content.addView(row);

            final AlertDialog dialog = glassDialog(activity, content);
            reenable.setOnClickListener(v -> {
                dialog.dismiss();
                try { controller.enableExtensionProcessSpawning(); } catch (Exception ignored) {}
            });
            keep.setOnClickListener(v -> dialog.dismiss());
        }
    };

    /** Extensions that open tabs (browser.tabs.create) or their options page. */
    private final WebExtension.TabDelegate tabDelegate = new WebExtension.TabDelegate() {
        @Override
        public GeckoResult<GeckoSession> onNewTab(WebExtension extension,
                                                  WebExtension.CreateTabDetails details) {
            GeckoSession session = null;
            Host h = host;
            if (h != null) {
                try { session = h.openExtensionTab(details.url); } catch (Exception e) {
                    Log.w(TAG, "openExtensionTab failed", e);
                }
            }
            if (session == null && runtime != null) {
                // Fallback when no host is available (e.g. cold background process).
                try {
                    session = new GeckoSession();
                    session.open(runtime);
                    if (details.url != null) session.loadUri(details.url);
                } catch (Exception e) {
                    Log.w(TAG, "fallback tab failed", e);
                    session = null;
                }
            }
            return session != null ? GeckoResult.fromValue(session) : null;
        }

        @Override
        public void onOpenOptionsPage(WebExtension extension) {
            String url = extension.metaData != null ? extension.metaData.optionsPageUrl : null;
            if (url != null) openInBrowserTab(url);
        }
    };

    /** Toolbar buttons + popups for browser/page actions. */
    private final WebExtension.ActionDelegate actionDelegate = new WebExtension.ActionDelegate() {
        @Override
        public void onBrowserAction(WebExtension extension, GeckoSession session,
                                    WebExtension.Action action) {
            if (action == null) return;
            if (session == null) {
                toolbarActions.put(extension.id, action);
            } else if (session == activeSession) {
                WebExtension.Action merged = action;
                try {
                    WebExtension.Action def = toolbarActions.get(extension.id);
                    if (def != null) merged = action.withDefault(def);
                } catch (IllegalArgumentException ignored) {}
                toolbarActions.put(extension.id, merged);
            }
            rebuildToolbar();
        }

        @Override
        public void onPageAction(WebExtension extension, GeckoSession session,
                                 WebExtension.Action action) {
            if (action == null || session != null) return;
            toolbarActions.put(extension.id, action);
            rebuildToolbar();
        }

        @Override
        public GeckoResult<GeckoSession> onTogglePopup(WebExtension extension,
                                                       WebExtension.Action action) {
            return openActionPopup(extension, action);
        }

        @Override
        public GeckoResult<GeckoSession> onOpenPopup(WebExtension extension,
                                                     WebExtension.Action action) {
            return openActionPopup(extension, action);
        }
    };

    // ------------------------------------------------------------------
    // Toolbar
    // ------------------------------------------------------------------

    public void attachToolbar(LinearLayout container) {
        toolbarContainer = container;
        rebuildToolbar();
    }

    private void rebuildToolbar() {
        final LinearLayout container = toolbarContainer;
        if (container == null) return;
        final int generation = ++toolbarGeneration;
        Runnable rebuild = () -> {
            try {
                Context c = container.getContext();
                container.removeAllViews();
                boolean any = false;
                for (Map.Entry<String, WebExtension.Action> entry : toolbarActions.entrySet()) {
                    WebExtension ext = installed.get(entry.getKey());
                    if (ext != null && ext.metaData != null && !ext.metaData.enabled) continue;
                    final WebExtension.Action action = entry.getValue();
                    final ImageButton button = new ImageButton(c);
                    button.setBackgroundResource(R.drawable.bg_glass_button);
                    button.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    button.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
                    button.setContentDescription(action.title != null
                            ? action.title : "Extension action");
                    button.setAlpha(action.enabled == null || action.enabled ? 1f : 0.4f);
                    button.setImageResource(R.drawable.ic_extension);
                    if (action.icon != null) {
                        action.icon.getBitmap(48).accept(bitmap -> {
                            if (bitmap != null && generation == toolbarGeneration) {
                                button.setImageBitmap(bitmap);
                            }
                        });
                    }
                    button.setOnClickListener(v -> {
                        try {
                            action.click();   // opens the popup via onTogglePopup if defined
                        } catch (Exception e) {
                            Log.w(TAG, "action click failed", e);
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40));
                    lp.setMarginEnd(dp(c, 6));
                    container.addView(button, lp);
                    any = true;
                }
                container.setVisibility(any ? View.VISIBLE : View.GONE);
            } catch (Exception e) {
                Log.w(TAG, "rebuildToolbar failed", e);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) rebuild.run();
        else main.post(rebuild);
    }

    /**
     * Popup display follows GeckoView's contract: the embedder supplies an
     * already-open GeckoSession; GeckoView then calls loadUri(popupUri) on it.
     */
    private GeckoResult<GeckoSession> openActionPopup(WebExtension extension,
                                                      WebExtension.Action action) {
        final Activity activity = currentActivity;
        if (!isUiAvailable(activity) || runtime == null) return null;
        try {
            final GeckoSession popupSession = new GeckoSession();
            final Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            GeckoView surface = new GeckoView(activity);
            surface.setBackgroundColor(Color.TRANSPARENT);
            dialog.setContentView(surface);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            int width = Math.min(dp(activity, 340),
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.9));
            int height = dp(activity, 420);

            surface.setSession(popupSession);
            popupSession.open(runtime);
            dialog.show();
            if (dialog.getWindow() != null) dialog.getWindow().setLayout(width, height);
            // [P3] One tracker owns the dismiss event: it closes the popup
            // session AND removes the dialog from dialogsByActivity.
            trackDialog(activity, dialog, () -> {
                try { popupSession.close(); } catch (Exception ignored) {}
            });
            return GeckoResult.fromValue(popupSession);
        } catch (Exception e) {
            Log.w(TAG, "could not open extension popup", e);
            return null;
        }
    }

    /** Notifies extensions about the active tab (tabs.onActivated). */
    public void setActiveSession(GeckoSession session) {
        GeckoSession old = activeSession;
        activeSession = session;
        try {
            if (controller != null) {
                if (old != null && old != session) controller.setTabActive(old, false);
                if (session != null) controller.setTabActive(session, true);
            }
        } catch (Exception e) {
            Log.w(TAG, "setTabActive failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Activity / dialog plumbing
    // ------------------------------------------------------------------

    public void setCurrentActivity(Activity a) { this.currentActivity = a; }

    /** Dismiss any dialog owned by a dying activity (prevents window leaks). */
    public void onActivityDestroyed(Activity a) {
        List<Dialog> list = dialogsByActivity.remove(a);
        if (list != null) {
            for (Dialog d : list) {
                try { if (d.isShowing()) d.dismiss(); } catch (Exception ignored) {}
            }
        }
        if (currentActivity == a) currentActivity = null;
        if (toolbarContainer != null && toolbarContainer.getContext() == a) {
            toolbarContainer = null;
        }
    }

    /**
     * [P3] Tracks a dialog for its owning activity. Dismissed dialogs used to
     * stay in dialogsByActivity until the whole activity died; they are now
     * dropped as soon as they close. The optional runnable runs on dismiss.
     */
    private void trackDialog(Activity owner, Dialog d, Runnable onDismiss) {
        List<Dialog> list = dialogsByActivity.get(owner);
        if (list == null) {
            list = new ArrayList<>();
            dialogsByActivity.put(owner, list);
        }
        list.add(d);
        d.setOnDismissListener(x -> {
            List<Dialog> l = dialogsByActivity.get(owner);
            if (l != null) l.remove(d);
            if (onDismiss != null) onDismiss.run();
        });
    }

    private boolean isUiAvailable(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    /** Opens a URL in a browser tab (or externally when no browser is alive). */
    public void openInBrowserTab(String url) {
        if (url == null || url.isEmpty()) return;
        Host h = host;
        if (h != null) {
            h.openExtensionTab(url);
        } else if (appContext != null) {
            try {
                appContext.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Log.w(TAG, "open url failed", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    private void showPermissionPrompt(final Activity activity, WebExtension extension,
                                     String title, String verb, String[] permissions,
                                     String[] origins, String[] dataCollection,
                                     final PermissionOutcome outcome) {
        Context c = activity;
        LinearLayout content = dialogContent(c, 20);

        TextView titleView = new TextView(c);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(17);
        titleView.setGravity(Gravity.CENTER);
        content.addView(titleView);

        TextView meta = new TextView(c);
        meta.setText(displayName(extension) + versionSuffix(extension));
        meta.setTextColor(0x99FFFFFF);
        meta.setTextSize(13);
        meta.setGravity(Gravity.CENTER);
        meta.setPadding(0, dp(c, 4), 0, dp(c, 8));
        content.addView(meta);

        String[] all = concat(permissions, origins, dataCollection);
        if (all.length == 0) {
            TextView none = new TextView(c);
            none.setText("No special permissions required.");
            none.setTextColor(0xB3FFFFFF);
            none.setTextSize(13);
            content.addView(none);
        } else {
            TextView header = new TextView(c);
            header.setText("It would like permission to:");
            header.setTextColor(0xB3FFFFFF);
            header.setTextSize(13);
            content.addView(header);
            for (String p : all) {
                TextView row = new TextView(c);
                row.setText("• " + p);
                row.setTextColor(0x99FFFFFF);
                row.setTextSize(13);
                row.setPadding(dp(c, 8), dp(c, 2), 0, 0);
                content.addView(row);
            }
        }

        TextView cancel = dialogButton(c, "Cancel", false, null);
        TextView confirm = dialogButton(c, verb, false, null);
        confirm.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(c, 44), 1f);
        lp1.topMargin = dp(c, 12);
        lp1.setMarginEnd(dp(c, 4));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(c, 44), 1f);
        lp2.topMargin = dp(c, 12);
        lp2.setMarginStart(dp(c, 4));
        row.addView(cancel, lp1);
        row.addView(confirm, lp2);
        content.addView(row);

        final AlertDialog dialog = glassDialog(activity, content);
        cancel.setOnClickListener(v -> {
            dialog.dismiss();
            outcome.onOutcome(false);
        });
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            outcome.onOutcome(true);
        });
    }

    private LinearLayout dialogContent(Context c, int padDp) {
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_glass_dialog);
        int pad = dp(c, padDp);
        content.setPadding(pad, pad, pad, dp(c, 12));
        return content;
    }

    private TextView dialogButton(Context c, String label, boolean red, LayoutParams unused) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextColor(red ? 0xFFFF8888 : 0xFFFFFFFF);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(red ? R.drawable.bg_glass_button_red : R.drawable.bg_glass_button);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private AlertDialog glassDialog(Activity activity, View content) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setView(content);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
        trackDialog(activity, dialog, null);
        return dialog;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void toast(final String message) {
        final Context c = appContext;
        if (c == null) return;
        main.post(() -> Toast.makeText(c, message, Toast.LENGTH_LONG).show());
    }

    public static String displayName(WebExtension ext) {
        if (ext == null) return "extension";
        if (ext.metaData != null && ext.metaData.name != null && !ext.metaData.name.isEmpty()) {
            return ext.metaData.name;
        }
        return ext.id != null ? ext.id : "extension";
    }

    private static String versionSuffix(WebExtension ext) {
        if (ext != null && ext.metaData != null && ext.metaData.version != null
                && !ext.metaData.version.isEmpty()) {
            return " to " + ext.metaData.version;
        }
        return "";
    }

    static String[] concat(String[] a, String[] b, String[] c) {
        int n = (a != null ? a.length : 0) + (b != null ? b.length : 0)
                + (c != null ? c.length : 0);
        String[] out = new String[n];
        int i = 0;
        if (a != null) for (String s : a) out[i++] = s;
        if (b != null) for (String s : b) out[i++] = s;
        if (c != null) for (String s : c) out[i++] = s;
        return out;
    }

    public static String installErrorText(Throwable error) {
        if (error instanceof WebExtension.InstallException) {
            switch (((WebExtension.InstallException) error).code) {
                case WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE:
                    return "network error";
                case WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE:
                case WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_HASH:
                    return "the downloaded file is corrupt";
                case WebExtension.InstallException.ErrorCodes.ERROR_FILE_ACCESS:
                    return "could not read the file";
                case WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED:
                    return "only Mozilla-signed add-ons can be installed";
                case WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_ID:
                    return "the add-on id does not match the download source";
                case WebExtension.InstallException.ErrorCodes.ERROR_INVALID_DOMAIN:
                    return "the add-on may not be installed from this domain";
                case WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED:
                    return "the add-on is blocklisted";
                case WebExtension.InstallException.ErrorCodes.ERROR_SOFT_BLOCKED:
                    return "the add-on is soft-blocked";
                case WebExtension.InstallException.ErrorCodes.ERROR_INCOMPATIBLE:
                    return "the add-on is not compatible with this browser";
                case WebExtension.InstallException.ErrorCodes.ERROR_UNSUPPORTED_ADDON_TYPE:
                case WebExtension.InstallException.ErrorCodes.ERROR_UNEXPECTED_ADDON_TYPE:
                    return "unsupported add-on type";
                case WebExtension.InstallException.ErrorCodes.ERROR_UNEXPECTED_ADDON_VERSION:
                    return "unsupported add-on version";
                case WebExtension.InstallException.ErrorCodes.ERROR_ADMIN_INSTALL_ONLY:
                    return "can only be installed by an administrator";
                case WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED:
                    return "cancelled";
                default:
                    return "error " + ((WebExtension.InstallException) error).code;
            }
        }
        String m = error.getMessage();
        return m != null ? m : error.getClass().getSimpleName();
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // LayoutParams alias used by dialogButton signature
    private abstract static class LayoutParams { private LayoutParams() {} }
}
