package com.spoongecko.browser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.io.File;
import java.io.FileInputStream;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://duckduckgo.com/";
    private static final int REQ_NOTIFICATIONS = 1001;

    private static final String FLAGS_PREFS = "spoongecko_flags";
    private static final String KEY_ASKED_BATTERY = "asked_battery";

    private GeckoView geckoView;
    private GeckoSession session;

    private EditText urlBar;
    private ProgressBar progressBar;
    private ImageButton backButton;
    private ImageButton forwardButton;
    private ImageButton reloadButton;
    private ImageButton menuButton;

    private String currentUrl = HOME_URL;
    private boolean canGoBack = false;

    // -------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashLogger.breadcrumb(this, "activity.onCreate begin");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.gecko_view);
        urlBar = findViewById(R.id.url_bar);
        progressBar = findViewById(R.id.progress_bar);
        backButton = findViewById(R.id.btn_back);
        forwardButton = findViewById(R.id.btn_forward);
        reloadButton = findViewById(R.id.btn_reload);
        menuButton = findViewById(R.id.btn_menu);

        wireToolbar();

        if (!createSession()) {
            return;
        }

        String restored = SessionStore.load(this);
        currentUrl = TextUtils.isEmpty(restored) ? HOME_URL : restored;
        urlBar.setText(currentUrl);

        CrashLogger.breadcrumb(this, "session.loadUri begin");
        session.loadUri(currentUrl);
        CrashLogger.breadcrumb(this, "session.loadUri issued");

        CrashLogger.breadcrumb(this, "keepalive.start begin");
        KeepAliveService.start(this);
        CrashLogger.breadcrumb(this, "keepalive.start issued");

        requestNotificationPermissionIfNeeded();
        CrashLogger.breadcrumb(this, "activity.onCreate done");
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        CrashLogger.breadcrumb(this, "activity.onPostResume");
        // Deferred to onPostResume so the dialog does not compete with
        // GeckoView's first paint or the POST_NOTIFICATIONS prompt.
        maybeOfferBatteryExemption();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        CrashLogger.breadcrumb(this, "activity.onNewIntent");
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null) return;
        String url = data.toString();
        currentUrl = url;
        urlBar.setText(url);
        if (session != null) session.loadUri(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        CrashLogger.breadcrumb(this, "activity.onResume");
    }

    @Override
    protected void onPause() {
        CrashLogger.breadcrumb(this, "activity.onPause");
        super.onPause();
        SessionStore.save(this, currentUrl);
    }

    @Override
    protected void onStop() {
        CrashLogger.breadcrumb(this, "activity.onStop");
        super.onStop();
        SessionStore.save(this, currentUrl);
    }

    @Override
    protected void onDestroy() {
        CrashLogger.breadcrumb(this, "activity.onDestroy finishing=" + isFinishing());
        if (isFinishing()) {
            if (geckoView != null) geckoView.releaseSession();
            if (session != null) {
                session.close();
                session = null;
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (session != null && canGoBack) {
            session.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            CrashLogger.breadcrumb(this, "notifications granted=" + granted);
        }
    }

    // ------------------------------------------------------------------ session

    private boolean createSession() {
        GeckoRuntime runtime = GeckoApp.getRuntime();
        if (runtime == null) {
            Toast.makeText(this, R.string.error_runtime, Toast.LENGTH_LONG).show();
            finish();
            return false;
        }

        CrashLogger.breadcrumb(this, "session.new");
        session = new GeckoSession();
        session.setProgressDelegate(progressDelegate);
        session.setNavigationDelegate(navigationDelegate);
        session.setContentDelegate(contentDelegate);

        CrashLogger.breadcrumb(this, "session.open begin");
        session.open(runtime);
        CrashLogger.breadcrumb(this, "session.open ok");

        geckoView.setSession(session);
        CrashLogger.breadcrumb(this, "session.attached");
        return true;
    }

    private final GeckoSession.ProgressDelegate progressDelegate =
            new GeckoSession.ProgressDelegate() {
                @Override
                public void onPageStart(GeckoSession s, String url) {
                    CrashLogger.breadcrumb(MainActivity.this, "page.start " + url);
                    currentUrl = url;
                    urlBar.setText(url);
                    progressBar.setVisibility(View.VISIBLE);
                }

                @Override
                public void onPageStop(GeckoSession s, boolean success) {
                    CrashLogger.breadcrumb(MainActivity.this,
                            "page.stop ok=" + success);
                    progressBar.setVisibility(View.GONE);
                    SessionStore.save(MainActivity.this, currentUrl);
                }

                @Override
                public void onProgressChange(GeckoSession s, int progress) {
                    progressBar.setProgress(progress);
                }
            };

    private final GeckoSession.NavigationDelegate navigationDelegate =
            new GeckoSession.NavigationDelegate() {
                @Override
                public void onCanGoBack(GeckoSession s, boolean value) {
                    canGoBack = value;
                    backButton.setEnabled(value);
                    backButton.setAlpha(value ? 1f : 0.35f);
                }

                @Override
                public void onCanGoForward(GeckoSession s, boolean value) {
                    forwardButton.setEnabled(value);
                    forwardButton.setAlpha(value ? 1f : 0.35f);
                }

                @Override
                public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                    s.loadUri(uri);
                    return GeckoResult.fromValue(null);
                }
            };

    private final GeckoSession.ContentDelegate contentDelegate =
            new GeckoSession.ContentDelegate() {
                @Override
                public void onTitleChange(GeckoSession s, String title) {
                    setTitle(TextUtils.isEmpty(title)
                            ? getString(R.string.app_name) : title);
                }
            };

    // ------------------------------------------------------------------ toolbar

    private void wireToolbar() {
        backButton.setOnClickListener(v -> { if (session != null) session.goBack(); });
        forwardButton.setOnClickListener(v -> { if (session != null) session.goForward(); });
        reloadButton.setOnClickListener(v -> { if (session != null) session.reload(); });
        menuButton.setOnClickListener(this::showMenu);

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE) {
                navigate(urlBar.getText().toString());
                return true;
            }
            return false;
        });

        backButton.setEnabled(false); backButton.setAlpha(0.35f);
        forwardButton.setEnabled(false); forwardButton.setAlpha(0.35f);
    }

    private void navigate(String raw) {
        if (TextUtils.isEmpty(raw) || session == null) return;
        hideKeyboard();
        urlBar.clearFocus();
        session.loadUri(normalize(raw));
    }

    private String normalize(String raw) {
        String input = raw.trim();
        if (input.isEmpty()) return HOME_URL;
        String lower = input.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("about:") || lower.startsWith("data:")
                || lower.startsWith("file:")) {
            return input;
        }
        boolean looksLikeHost = !input.contains(" ")
                && input.contains(".") && !input.endsWith(".");
        if (looksLikeHost) return "https://" + input;
        return "https://duckduckgo.com/?q=" + Uri.encode(input);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    // --------------------------------------------------------------------- menu

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, R.string.menu_home);
        popup.getMenu().add(0, 2, 1, R.string.menu_share);
        popup.getMenu().add(0, 3, 2, R.string.menu_copy);
        popup.getMenu().add(0, 4, 3, R.string.menu_battery);
        popup.getMenu().add(0, 5, 4, R.string.menu_autostart);
        popup.getMenu().add(0, 6, 5, R.string.menu_about);
        popup.getMenu().add(0, 7, 6, "Show crash log");
        popup.getMenu().add(0, 8, 7, R.string.menu_exit);
        popup.setOnMenuItemClickListener(this::onMenuClick);
        popup.show();
    }

    private boolean onMenuClick(MenuItem item) {
        switch (item.getItemId()) {
            case 1: if (session != null) session.loadUri(HOME_URL); return true;
            case 2: shareCurrent(); return true;
            case 3: copyCurrent(); return true;
            case 4: OemHelper.requestIgnoreBatteryOptimizations(this); return true;
            case 5: OemHelper.openAutostartSettings(this); return true;
            case 6: showAbout(); return true;
            case 7: showCrashLog(); return true;
            case 8: exitApp(); return true;
            default: return false;
        }
    }

    private void shareCurrent() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, currentUrl);
        startActivity(Intent.createChooser(intent, getString(R.string.menu_share)));
    }

    private void copyCurrent() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("url", currentUrl));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_message, versionName()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showCrashLog() {
        File f = CrashLogger.latestReportFile(this);
        if (f == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Crash log")
                    .setMessage("No crash report yet.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String text;
        try {
            byte[] data = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = in.read(data);
                text = new String(data, 0, Math.max(read, 0), "UTF-8");
            }
        } catch (Exception e) {
            text = "Could not read " + f.getName() + ": " + e;
        }
        final String content = text;
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setMessage(content)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("crash", content));
                        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void exitApp() {
        SessionStore.save(this, currentUrl);
        KeepAliveService.stop(this);
        finishAndRemoveTask();
    }

    private String versionName() {
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = getPackageManager().getPackageInfo(
                        getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                info = getPackageManager().getPackageInfo(getPackageName(), 0);
            }
            return info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // -------------------------------------------------------------- survival UX

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    private void maybeOfferBatteryExemption() {
        if (OemHelper.isIgnoringBatteryOptimizations(this)) return;
        SharedPreferences flags = getSharedPreferences(FLAGS_PREFS, MODE_PRIVATE);
        if (flags.getBoolean(KEY_ASKED_BATTERY, false)) return;
        flags.edit().putBoolean(KEY_ASKED_BATTERY, true).apply();

        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_message)
                .setPositiveButton(R.string.battery_allow,
                        (d, w) -> OemHelper.requestIgnoreBatteryOptimizations(this))
                .setNegativeButton(R.string.battery_later, null)
                .show();
    }
}
