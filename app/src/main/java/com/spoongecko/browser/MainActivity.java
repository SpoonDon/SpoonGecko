package com.spoongecko.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.mozilla.geckoview.CompositorController;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.net.URLEncoder;

public class MainActivity extends Activity implements ComponentCallbacks2 {

    private static final String TAG = "SpoonGecko";
    private static final String BRAVE_SEARCH_URL = "https://search.brave.com/search?q=";
    private static final String STATE_KEY = "spoongecko_session_state";
    private static final String PREFS_NAME = "spoongecko_prefs";
    private static final String PREF_SESSION = "session_state_b64";

    private static GeckoRuntime sRuntime;
    private static GeckoSession.SessionState sPersistedState; // RAM persistence

    private GeckoSession session;
    private GeckoView geckoView;
    private View homePanel;
    private EditText urlBar;
    private EditText searchBar;
    private TextView forwardButton;
    private TextView menuButton;
    private View flashGuard;
    private FrameLayout contentContainer;

    private PopupWindow menuPopup;
    private Dialog exitDialog;

    private GeckoSession.SessionState latestState;
    private boolean canGoBack = false;
    private boolean canGoForward = false;

    private static synchronized GeckoRuntime ensureRuntime(final Activity activity) {
        if (sRuntime == null) {
            final GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .contentBlocking(new ContentBlocking.Settings.Builder()
                                 .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                                 .build())
                .trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
                .fissionEnabled(true)
                .remoteDebuggingEnabled(false)
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
                .build();
            sRuntime = GeckoRuntime.create(activity.getApplicationContext(), settings);
        }
        return sRuntime;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        makeImmersive();
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.geckoview);
        homePanel = findViewById(R.id.home_panel);
        urlBar = findViewById(R.id.url_bar);
        searchBar = findViewById(R.id.search_bar);
        forwardButton = findViewById(R.id.forward_button);
        menuButton = findViewById(R.id.menu_button);
        contentContainer = findViewById(R.id.content_container);

        geckoView.setBackgroundColor(Color.BLACK);
        homePanel.setBackgroundColor(Color.TRANSPARENT);

        flashGuard = new View(this);
        flashGuard.setBackgroundColor(Color.BLACK);
        flashGuard.setClickable(false);
        flashGuard.setFocusable(false);
        contentContainer.addView(flashGuard, 1, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        new Handler(Looper.getMainLooper()).postDelayed(this::removeFlashGuard, 1500);

        setupWindowInsets();
        setupBackHandler();

        sRuntime = ensureRuntime(this);

        session = new GeckoSession();
        attachDelegates(session);
        session.open(sRuntime);
        geckoView.setSession(session);

        final CompositorController compositorController = session.getCompositorController();
        if (compositorController != null) {
            compositorController.setClearColor(Color.BLACK);
        }

        setupInputHandlers();
        updateForwardButtonState();

        GeckoSession.SessionState restored = loadPersistedState(savedInstanceState);
        if (restored != null) {
            showHomePanel(false);
            session.restoreState(restored);
        } else {
            showHomePanel(true);
        }
    }

    // ---------- back handling ----------

    @android.annotation.TargetApi(Build.VERSION_CODES.TIRAMISU)
    private void setupBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        () -> handleBackAction()
                );
            } catch (Exception e) {
                Log.w(TAG, "register back callback failed", e);
            }
        }
    }

    private void handleBackAction() {
        if (session != null && canGoBack) {
            session.goBack();
        } else {
            showExitConfirmation();
        }
    }

    /** Only called on API < 33 (manifest opt-in makes the framework skip this on 33+). */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBackAction();
    }

    // ---------- window / insets ----------

    private void makeImmersive() {
        final Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            w.getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @SuppressWarnings("deprecation")
    private void setupWindowInsets() {
        final View root = findViewById(R.id.root);
        final View urlPanel = findViewById(R.id.url_bar_panel);
        final int originalPaddingTop = urlPanel.getPaddingTop();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset;
            int imeInset = 0;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                topInset = insets.getInsets(WindowInsets.Type.statusBars()).top;
                imeInset = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                imeInset = insets.getSystemWindowInsetBottom();
            }

            urlPanel.setPadding(
                    urlPanel.getPaddingLeft(),
                    originalPaddingTop + topInset,
                    urlPanel.getPaddingRight(),
                    urlPanel.getPaddingBottom()
            );

            if (homePanel != null && homePanel.getVisibility() == View.VISIBLE) {
                contentContainer.setPadding(0, 0, 0, imeInset);
            } else {
                contentContainer.setPadding(0, 0, 0, 0);
            }

            return insets;
        });
    }

    // ---------- input handlers ----------

    private void setupInputHandlers() {
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                final String input = urlBar.getText().toString().trim();
                if (!TextUtils.isEmpty(input)) {
                    navigateFromInput(input);
                }
                urlBar.clearFocus();
                hideKeyboard();
                return true;
            }
            return false;
        });

        searchBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                final String query = searchBar.getText().toString().trim();
                if (!TextUtils.isEmpty(query)) {
                    navigateToUrl(BRAVE_SEARCH_URL + urlEncode(query));
                    searchBar.setText("");
                    searchBar.clearFocus();
                }
                hideKeyboard();
                return true;
            }
            return false;
        });

        forwardButton.setOnClickListener(v -> {
            if (session != null && canGoForward) {
                session.goForward();
            }
        });

        menuButton.setOnClickListener(v -> showMenu());
    }

    private void navigateFromInput(final String input) {
        if (looksLikeUrl(input)) {
            String url = input;
            if (!url.contains("://")) url = "https://" + url;
            navigateToUrl(url);
        } else {
            navigateToUrl(BRAVE_SEARCH_URL + urlEncode(input));
        }
    }

    private void navigateToUrl(final String url) {
        showHomePanel(false);
        session.loadUri(url);
    }

    private static boolean looksLikeUrl(final String input) {
        if (input == null || input.isEmpty()) return false;
        if (input.contains(" ")) return false;
        if (input.contains("://")) return true;
        return input.contains(".");
    }

    private static String urlEncode(final String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private void hideKeyboard() {
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
            imm.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
        }
    }

    private void showHomePanel(final boolean show) {
        if (homePanel != null) {
            homePanel.setVisibility(show ? View.VISIBLE : View.GONE);
            if (contentContainer != null) {
                contentContainer.requestApplyInsets();
            }
        }
    }

    private void updateForwardButtonState() {
        runOnUiThread(() -> {
            if (forwardButton != null) {
                forwardButton.setAlpha(canGoForward ? 1f : 0.35f);
                forwardButton.setEnabled(canGoForward);
            }
        });
    }

    private void removeFlashGuard() {
        runOnUiThread(() -> {
            if (flashGuard != null && flashGuard.getParent() != null) {
                ((ViewGroup) flashGuard.getParent()).removeView(flashGuard);
                flashGuard = null;
            }
        });
    }

    private int dp(int px) {
        return Math.round(px * getResources().getDisplayMetrics().density);
    }

    // ---------- menu ----------

    private void showMenu() {
        if (menuPopup != null && menuPopup.isShowing()) {
            menuPopup.dismiss();
            return;
        }

        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(R.drawable.bg_glass_dialog);
        final int pad = dp(8);
        container.setPadding(pad, pad, pad, pad);

        final TextView exitItem = new TextView(this);
        exitItem.setText("Exit");
        exitItem.setTextColor(Color.parseColor("#FF6666"));
        exitItem.setTextSize(15);
        exitItem.setGravity(Gravity.CENTER);
        exitItem.setPadding(dp(24), dp(12), dp(24), dp(12));
        exitItem.setBackgroundResource(R.drawable.bg_glass_button);
        exitItem.setOnClickListener(v -> {
            if (menuPopup != null) menuPopup.dismiss();
            showExitConfirmation();
        });
        container.addView(exitItem);

        menuPopup = new PopupWindow(container, dp(140), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menuPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menuPopup.setOutsideTouchable(true);
        menuPopup.setElevation(dp(8));
        menuPopup.showAsDropDown(menuButton, 0, dp(8));
    }

    // ---------- exit confirmation ----------

    private void showExitConfirmation() {
        if (exitDialog != null && exitDialog.isShowing()) return;

        exitDialog = new Dialog(this);
        exitDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        exitDialog.setContentView(R.layout.dialog_exit);

        final Window win = exitDialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setDimAmount(0.7f);
            win.setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.85),
                    WindowManager.LayoutParams.WRAP_CONTENT
            );
        }

        final TextView btnCancel = exitDialog.findViewById(R.id.btn_cancel);
        final TextView btnExit = exitDialog.findViewById(R.id.btn_exit);
        btnCancel.setOnClickListener(v -> exitDialog.dismiss());
        btnExit.setOnClickListener(v -> {
            exitDialog.dismiss();
            persistState();          // flush RAM + disk session
            finishAndRemoveTask();   // ✅ remove task entirely from recents
        });

        exitDialog.show();
    }

    // ---------- persistence ----------

    private GeckoSession.SessionState loadPersistedState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    GeckoSession.SessionState s =
                            savedInstanceState.getParcelable(STATE_KEY, GeckoSession.SessionState.class);
                    if (s != null) return s;
                } else {
                    Parcelable p = savedInstanceState.getParcelable(STATE_KEY);
                    if (p instanceof GeckoSession.SessionState) return (GeckoSession.SessionState) p;
                }
            } catch (Exception e) {
                Log.w(TAG, "read instance state failed", e);
            }
        }
        if (sPersistedState != null) return sPersistedState;
        return loadFromPrefs();
    }

    private GeckoSession.SessionState loadFromPrefs() {
        try {
            final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            final String b64 = prefs.getString(PREF_SESSION, null);
            if (b64 == null) return null;
            final byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
            final Parcel p = Parcel.obtain();
            try {
                p.unmarshall(bytes, 0, bytes.length);
                p.setDataPosition(0);
                return GeckoSession.SessionState.CREATOR.createFromParcel(p);
            } finally {
                p.recycle();
            }
        } catch (Exception e) {
            Log.w(TAG, "load session failed", e);
            return null;
        }
    }

    private void saveToPrefs(final GeckoSession.SessionState state) {
        if (state == null) return;
        try {
            final Parcel p = Parcel.obtain();
            final byte[] bytes;
            try {
                state.writeToParcel(p, 0);
                bytes = p.marshall();
            } finally {
                p.recycle();
            }
            final String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_SESSION, b64)
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "save session failed", e);
        }
    }

    private void persistState() {
        if (latestState != null) {
            sPersistedState = latestState;
            saveToPrefs(latestState);
        }
    }

    // ---------- delegates ----------

    private void attachDelegates(final GeckoSession s) {
        s.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onSessionStateChange(GeckoSession sess, GeckoSession.SessionState sessionState) {
                latestState = sessionState;
                persistState();
            }

            @Override
            public void onPageStart(GeckoSession sess, String url) {
                if (TextUtils.isEmpty(url) || url.startsWith("about:")) return;
                final String displayUrl = url;
                runOnUiThread(() -> {
                    if (urlBar != null && !urlBar.hasFocus()) {
                        urlBar.setText(displayUrl);
                    }
                    showHomePanel(false);
                });
            }
        });

        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFirstContentfulPaint(GeckoSession session) {
                removeFlashGuard();
            }
        });

        s.setContentBlockingDelegate(new ContentBlocking.Delegate() {
            @Override
            public void onContentBlocked(GeckoSession session, ContentBlocking.BlockEvent event) {
                Log.d(TAG, "Blocked tracker: " + event.uri);
            }
        });

        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession session, boolean value) {
                MainActivity.this.canGoBack = value;
            }

            @Override
            public void onCanGoForward(GeckoSession session, boolean value) {
                MainActivity.this.canGoForward = value;
                updateForwardButtonState();
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession source, String uri) {
                return GeckoResult.fromValue(source);
            }
        });
    }

    // ---------- lifecycle ----------

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        persistState();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        persistState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null) {
            session.setActive(true);
            session.setPriorityHint(GeckoSession.PRIORITY_HIGH);
        }
    }

    @Override
    protected void onPause() {
        persistState();
        if (session != null) {
            session.setActive(false);
            session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        persistState();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (latestState != null) {
            outState.putParcelable(STATE_KEY, latestState);
        }
    }

    @Override
    protected void onDestroy() {
        if (menuPopup != null) {
            menuPopup.dismiss();
            menuPopup = null;
        }
        if (exitDialog != null) {
            exitDialog.dismiss();
            exitDialog = null;
        }
        if (geckoView != null) geckoView.setSession(null);
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }
}
