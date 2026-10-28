package com.spoongecko.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
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
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.CompositorController;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity
        implements ComponentCallbacks2, TabManager.Listener {

    private static final String TAG = "SpoonGecko";
    private static final String BRAVE_SEARCH_URL = "https://search.brave.com/search?q=";
    private static final String PREFS_NAME = "spoongecko_prefs";
    private static final String PREF_TABS = "tabs_json";

    private static GeckoRuntime sRuntime;
    // RAM persistence across config changes (activity recreate)
    private static List<GeckoSession.SessionState> sPersistedStates;
    private static int sPersistedActiveIndex = 0;

    private TabManager tabManager;
    private GeckoView geckoView;
    private FrameLayout contentContainer;
    private View homePanel;
    private EditText urlBar;
    private EditText searchBar;
    private TextView backButton;
    private TextView forwardButton;
    private TextView menuButton;
    private ListView suggestionsList;
    private View flashGuard;

    private HistoryStore history;
    private PopupWindow menuPopup;
    private Dialog exitDialog;

    private SuggestionsAdapter suggestionsAdapter;
    private boolean suppressSuggestionUpdates = false;

    // ---------- runtime ----------

    private static synchronized GeckoRuntime ensureRuntime(Activity activity) {
        if (sRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
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
        contentContainer = findViewById(R.id.content_container);
        homePanel = findViewById(R.id.home_panel);
        urlBar = findViewById(R.id.url_bar);
        searchBar = findViewById(R.id.search_bar);
        backButton = findViewById(R.id.back_button);
        forwardButton = findViewById(R.id.forward_button);
        menuButton = findViewById(R.id.menu_button);
        suggestionsList = findViewById(R.id.suggestions_list);

        geckoView.setBackgroundColor(Color.BLACK);
        history = new HistoryStore(this);

        // flash guard for cold start
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
        setupInputHandlers();
        setupSuggestions();

        sRuntime = ensureRuntime(this);
        tabManager = new TabManager(sRuntime);
        tabManager.setListener(this);

        restoreTabs(savedInstanceState);

        applyTheme();

        updateToolbarState();
        updateMenuButtonCount();
    }

    // ---------- tabs restore ----------

    private void restoreTabs(Bundle savedInstanceState) {
        // 1) RAM copy
        if (sPersistedStates != null && !sPersistedStates.isEmpty()) {
            for (GeckoSession.SessionState s : sPersistedStates) {
                attachDelegatesForNewTab(tabManager.createTabWithState(s));
            }
            tabManager.switchTo(Math.min(sPersistedActiveIndex, tabManager.count() - 1));
            sPersistedStates = null;
            showHomePanel(false);
            return;
        }

        // 2) Disk copy
        List<GeckoSession.SessionState> diskStates = loadTabsFromPrefs();
        if (!diskStates.isEmpty()) {
            for (GeckoSession.SessionState s : diskStates) {
                attachDelegatesForNewTab(tabManager.createTabWithState(s));
            }
            showHomePanel(false);
            return;
        }

        // 3) Fresh start
        attachDelegatesForNewTab(tabManager.createTab());
        showHomePanel(true);
    }

    // ---------- back handling ----------

    @android.annotation.TargetApi(Build.VERSION_CODES.TIRAMISU)
    private void setupBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        this::handleBackAction);
            } catch (Exception e) { Log.w(TAG, "register back callback failed", e); }
        }
    }

    private void handleBackAction() {
        if (suggestionsList.getVisibility() == View.VISIBLE) {
            hideSuggestions();
            urlBar.clearFocus();
            return;
        }
        Tab t = tabManager.getActiveTab();
        if (t != null && t.canGoBack) {
            t.session.goBack();
            return;
        }
        showExitConfirmation();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() { handleBackAction(); }

    // ---------- insets ----------

    private void makeImmersive() {
        Window w = getWindow();
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
            int topInset, imeInset = 0;
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
                    urlPanel.getPaddingBottom());
            if (homePanel.getVisibility() == View.VISIBLE) {
                contentContainer.setPadding(0, 0, 0, imeInset);
            } else {
                contentContainer.setPadding(0, 0, 0, 0);
            }
            return insets;
        });
    }

    // ---------- input ----------

    private void setupInputHandlers() {
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String input = urlBar.getText().toString().trim();
                if (!TextUtils.isEmpty(input)) navigateFromInput(input);
                urlBar.clearFocus();
                hideKeyboard();
                hideSuggestions();
                return true;
            }
            return false;
        });

        searchBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String q = searchBar.getText().toString().trim();
                if (!TextUtils.isEmpty(q)) {
                    navigateToUrl(BRAVE_SEARCH_URL + urlEncode(q));
                    searchBar.setText("");
                    searchBar.clearFocus();
                }
                hideKeyboard();
                return true;
            }
            return false;
        });

        backButton.setOnClickListener(v -> {
            Tab t = tabManager.getActiveTab();
            if (t != null && t.canGoBack) t.session.goBack();
        });
        forwardButton.setOnClickListener(v -> {
            Tab t = tabManager.getActiveTab();
            if (t != null && t.canGoForward) t.session.goForward();
        });
        menuButton.setOnClickListener(v -> showMenu());
    }

    private void setupSuggestions() {
        suggestionsAdapter = new SuggestionsAdapter();
        suggestionsList.setAdapter(suggestionsAdapter);
        suggestionsList.setOnItemClickListener((parent, view, pos, id) -> {
            HistoryStore.Entry e = suggestionsAdapter.getItem(pos);
            suppressSuggestionUpdates = true;
            urlBar.setText(e.url);
            suppressSuggestionUpdates = false;
            navigateToUrl(e.url);
            urlBar.clearFocus();
            hideKeyboard();
            hideSuggestions();
        });

        urlBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (suppressSuggestionUpdates) return;
                String q = s.toString().trim();
                if (q.length() < 2) { hideSuggestions(); return; }
                List<HistoryStore.Entry> results = history.suggest(q, 8);
                if (results.isEmpty()) { hideSuggestions(); return; }
                suggestionsAdapter.setData(results);
                suggestionsList.setVisibility(View.VISIBLE);
            }
        });

        urlBar.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) hideSuggestions();
        });
    }

    private void showSuggestions() {
        if (suggestionsAdapter.getCount() > 0) suggestionsList.setVisibility(View.VISIBLE);
    }
    private void hideSuggestions() { suggestionsList.setVisibility(View.GONE); }

    // ---------- navigation ----------

    private void navigateFromInput(String input) {
        if (looksLikeUrl(input)) {
            String url = input;
            if (!url.contains("://")) url = "https://" + url;
            navigateToUrl(url);
        } else {
            navigateToUrl(BRAVE_SEARCH_URL + urlEncode(input));
        }
    }

    private void navigateToUrl(String url) {
        Tab t = tabManager.getActiveTab();
        if (t == null) return;
        showHomePanel(false);
        t.session.loadUri(url);
    }

    private static boolean looksLikeUrl(String input) {
        if (input == null || input.isEmpty()) return false;
        if (input.contains(" ")) return false;
        if (input.contains("://")) return true;
        return input.contains(".");
    }

    private static String urlEncode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
            imm.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
        }
    }

    private void showHomePanel(boolean show) {
        homePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        contentContainer.requestApplyInsets();
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

    // ---------- toolbar / menu ----------

    private void updateToolbarState() {
        Tab t = tabManager.getActiveTab();
        runOnUiThread(() -> {
            boolean back = t != null && t.canGoBack;
            boolean fwd  = t != null && t.canGoForward;
            backButton.setAlpha(back ? 1f : 0.35f);
            backButton.setEnabled(back);
            forwardButton.setAlpha(fwd ? 1f : 0.35f);
            forwardButton.setEnabled(fwd);
            if (t != null && !urlBar.hasFocus() && !t.url.isEmpty()) {
                urlBar.setText(t.url);
            }
        });
    }

    private void updateMenuButtonCount() {
        runOnUiThread(() -> {
            int n = tabManager.count();
            menuButton.setText(n > 1 ? n + "" : "⋮");
            menuButton.setTextSize(n > 1 ? 15 : 22);
        });
    }

    private void showMenu() {
        if (menuPopup != null && menuPopup.isShowing()) { menuPopup.dismiss(); return; }
        int theme = ThemeManager.get(this);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackground(ThemeManager.glassDialog(this, theme));
        int pad = dp(6);
        container.setPadding(pad, pad, pad, pad);

        addMenuItem(container, "New tab", false, this::newTab);
        addMenuItem(container, "Tabs (" + tabManager.count() + ")", false, this::showTabList);
        addMenuItem(container, "History", false, this::showHistory);
        addMenuItem(container, "Reload", false, this::reloadActive);
        addMenuItem(container, "Home", false, this::goHome);
        addMenuItem(container, "Share", false, this::shareActive);
        addMenuItem(container, "Theme: " + ThemeManager.name(theme), false, this::cycleTheme);
        addMenuItem(container, "Exit", true, this::showExitConfirmation);

        menuPopup = new PopupWindow(container, dp(190), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menuPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menuPopup.setOutsideTouchable(true);
        menuPopup.setElevation(dp(8));
        menuPopup.showAsDropDown(menuButton, 0, dp(8));
    }

    private void addMenuItem(LinearLayout parent, String label, boolean red, Runnable action) {
        int theme = ThemeManager.get(this);
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextSize(14);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), dp(12), dp(16), dp(12));
        item.setTextColor(red ? 0xFFFF6666 : ThemeManager.textPrimary(theme));
        item.setBackground(ThemeManager.glassButton(this, theme));
        item.setClickable(true);
        item.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(2);
        parent.addView(item, lp);
        item.setOnClickListener(v -> {
            if (menuPopup != null) menuPopup.dismiss();
            action.run();
        });
    }

    // ---------- actions ----------

    private void newTab() {
        Tab t = tabManager.createTab();
        attachDelegatesForNewTab(t);
        urlBar.setText("");
        showHomePanel(true);
        updateToolbarState();
        updateMenuButtonCount();
        hideSuggestions();
    }

    private void reloadActive() {
        Tab t = tabManager.getActiveTab();
        if (t != null) t.session.reload();
    }

    private void goHome() {
        Tab t = tabManager.getActiveTab();
        if (t == null) return;
        t.session.loadUri("about:blank");
        urlBar.setText("");
        showHomePanel(true);
    }

    private void shareActive() {
        Tab t = tabManager.getActiveTab();
        if (t == null || t.url.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, t.url);
        startActivity(Intent.createChooser(i, "Share"));
    }

    private void cycleTheme() {
        int next = ThemeManager.next(ThemeManager.get(this));
        ThemeManager.set(this, next);
        applyTheme();
        updateToolbarState();
    }

    // ---------- theming ----------

    private void applyTheme() {
        int theme = ThemeManager.get(this);
        findViewById(R.id.root).setBackgroundColor(ThemeManager.windowBg(theme));
        findViewById(R.id.url_bar_panel).setBackground(ThemeManager.toolbarBg(this, theme));

        int textPrimary = ThemeManager.textPrimary(theme);
        int textMuted = ThemeManager.textMuted(theme);

        for (TextView b : new TextView[]{backButton, forwardButton, menuButton}) {
            b.setTextColor(textPrimary);
            b.setBackground(ThemeManager.glassButton(this, theme));
        }

        urlBar.setTextColor(textPrimary);
        urlBar.setHintTextColor(textMuted);
        urlBar.setBackground(ThemeManager.glassInput(this, theme));

        searchBar.setTextColor(textPrimary);
        searchBar.setHintTextColor(textMuted);
        searchBar.setBackground(ThemeManager.glassSearch(this, theme));

        suggestionsList.setBackgroundColor(theme == ThemeManager.LIGHT ? 0xEEFFFFFF : 0xEE000000);
    }

    // ---------- tab list ----------

    private void showTabList() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_list);
        styleDialog(d, 0.9f);

        TextView title = d.findViewById(R.id.dlg_title);
        title.setText("Tabs");
        ListView list = d.findViewById(R.id.dlg_list);
        TextView neg = d.findViewById(R.id.dlg_negative);
        TextView pos = d.findViewById(R.id.dlg_positive);

        TabListAdapter adapter = new TabListAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos1, id) -> {
            tabManager.switchTo(pos1);
            d.dismiss();
        });

        neg.setText("Close");
        neg.setOnClickListener(v -> d.dismiss());
        pos.setText("New tab");
        pos.setOnClickListener(v -> { newTab(); d.dismiss(); });

        d.show();
    }

    // ---------- history ----------

    private void showHistory() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_list);
        styleDialog(d, 0.9f);

        TextView title = d.findViewById(R.id.dlg_title);
        title.setText("History");
        ListView list = d.findViewById(R.id.dlg_list);
        TextView neg = d.findViewById(R.id.dlg_negative);
        TextView pos = d.findViewById(R.id.dlg_positive);

        List<HistoryStore.Entry> entries = history.recent(100);
        ItemAdapter adapter = new ItemAdapter(entries);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos1, id) -> {
            navigateToUrl(adapter.getItem(pos1).url);
            d.dismiss();
        });

        neg.setText("Close");
        neg.setOnClickListener(v -> d.dismiss());
        pos.setText("Clear");
        pos.setOnClickListener(v -> {
            history.clear();
            adapter.setData(new ArrayList<>());
            d.dismiss();
        });

        d.show();
    }

    private void styleDialog(Dialog d, float widthFraction) {
        Window w = d.getWindow();
        if (w == null) return;
        int theme = ThemeManager.get(this);
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setDimAmount(0.7f);
        w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * widthFraction),
                WindowManager.LayoutParams.WRAP_CONTENT);
        // theme the two action buttons
        TextView neg = d.findViewById(R.id.dlg_negative);
        TextView pos = d.findViewById(R.id.dlg_positive);
        TextView title = d.findViewById(R.id.dlg_title);
        if (title != null) title.setTextColor(ThemeManager.textPrimary(theme));
        if (neg != null) {
            neg.setTextColor(ThemeManager.textPrimary(theme));
            neg.setBackground(ThemeManager.glassButton(this, theme));
        }
        if (pos != null) {
            pos.setTextColor(ThemeManager.textPrimary(theme));
            pos.setBackground(ThemeManager.glassButton(this, theme));
        }
    }

    // ---------- exit ----------

    private void showExitConfirmation() {
        if (exitDialog != null && exitDialog.isShowing()) return;
        exitDialog = new Dialog(this);
        exitDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        exitDialog.setContentView(R.layout.dialog_exit);
        Window w = exitDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.7f);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.85),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        TextView cancel = exitDialog.findViewById(R.id.btn_cancel);
        TextView exit = exitDialog.findViewById(R.id.btn_exit);
        cancel.setOnClickListener(v -> exitDialog.dismiss());
        exit.setOnClickListener(v -> {
            exitDialog.dismiss();
            persistAllTabs();
            finishAndRemoveTask();
        });
        exitDialog.show();
    }

    // ---------- persistence ----------

    private void persistAllTabs() {
        try {
            JSONArray arr = new JSONArray();
            for (Tab t : tabManager.getTabs()) {
                if (t.latestState == null) continue;
                Parcel p = Parcel.obtain();
                byte[] bytes;
                try {
                    t.latestState.writeToParcel(p, 0);
                    bytes = p.marshall();
                } finally { p.recycle(); }
                JSONObject obj = new JSONObject();
                obj.put("s", Base64.encodeToString(bytes, Base64.NO_WRAP));
                arr.put(obj);
            }
            JSONObject root = new JSONObject();
            root.put("active", tabManager.getActiveIndex());
            root.put("tabs", arr);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(PREF_TABS, root.toString()).apply();
        } catch (Exception e) { Log.w(TAG, "persistTabs failed", e); }
    }

    private List<GeckoSession.SessionState> loadTabsFromPrefs() {
        List<GeckoSession.SessionState> out = new ArrayList<>();
        try {
            String raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_TABS, null);
            if (raw == null) return out;
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.getJSONArray("tabs");
            for (int i = 0; i < arr.length(); i++) {
                String b64 = arr.getJSONObject(i).getString("s");
                byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
                Parcel p = Parcel.obtain();
                try {
                    p.unmarshall(bytes, 0, bytes.length);
                    p.setDataPosition(0);
                    out.add(GeckoSession.SessionState.CREATOR.createFromParcel(p));
                } finally { p.recycle(); }
            }
            sPersistedActiveIndex = root.optInt("active", 0);
        } catch (Exception e) { Log.w(TAG, "loadTabs failed", e); }
        return out;
    }

    // ---------- delegates per tab ----------

    private void attachDelegatesForNewTab(final Tab tab) {
        final GeckoSession s = tab.session;

        s.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onSessionStateChange(GeckoSession sess, GeckoSession.SessionState st) {
                tab.latestState = st;
                if (tab == tabManager.getActiveTab()) persistAllTabs();
            }

            @Override
            public void onPageStart(GeckoSession sess, String url) {
                if (url == null || url.startsWith("about:")) return;
                tab.url = url;
                runOnUiThread(() -> {
                    if (tab == tabManager.getActiveTab() && !urlBar.hasFocus()) {
                        urlBar.setText(url);
                    }
                    showHomePanel(false);
                });
            }

            @Override
            public void onPageStop(GeckoSession sess, boolean success) {
                if (tab == tabManager.getActiveTab()) persistAllTabs();
            }
        });

        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession sess, String title) {
                tab.title = title == null ? "" : title;
                if (tab == tabManager.getActiveTab()) updateMenuButtonCount();
            }

            @Override
            public void onFirstContentfulPaint(GeckoSession session) {
                removeFlashGuard();
                if (!TextUtils.isEmpty(tab.url)) history.recordVisit(tab.url, tab.title);
            }
        });

        s.setContentBlockingDelegate(new ContentBlocking.Delegate() {
            @Override
            public void onContentBlocked(GeckoSession session, ContentBlocking.BlockEvent event) {
                Log.d(TAG, "Blocked: " + event.uri);
            }
        });

        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession sess, boolean value) {
                tab.canGoBack = value;
                if (tab == tabManager.getActiveTab()) updateToolbarState();
            }
            @Override
            public void onCanGoForward(GeckoSession sess, boolean value) {
                tab.canGoForward = value;
                if (tab == tabManager.getActiveTab()) updateToolbarState();
            }
            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession source, String uri) {
                return GeckoResult.fromValue(source);
            }
        });
    }

    // ---------- TabManager.Listener ----------

    @Override
    public void onTabsChanged() {
        updateMenuButtonCount();
        // if all tabs closed, spawn a fresh one
        if (tabManager.count() == 0) {
            Tab t = tabManager.createTab();
            attachDelegatesForNewTab(t);
            showHomePanel(true);
        }
    }

    @Override
    public void onActiveTabChanged(Tab tab) {
        if (tab == null) return;
        geckoView.setSession(tab.session);
        CompositorController cc = tab.session.getCompositorController();
        if (cc != null) cc.setClearColor(Color.BLACK);
        hideSuggestions();
        showHomePanel(tab.url.isEmpty());
        updateToolbarState();
        updateMenuButtonCount();
    }

    // ---------- lifecycle ----------

    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); persistAllTabs(); }
    @Override public void onLowMemory() { super.onLowMemory(); persistAllTabs(); }

    @Override protected void onResume() {
        super.onResume();
        Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t != null) { t.session.setActive(true); t.session.setPriorityHint(GeckoSession.PRIORITY_HIGH); }
    }

    @Override protected void onPause() {
        persistAllTabs();
        Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t != null) { t.session.setActive(false); t.session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT); }
        super.onPause();
    }

    @Override protected void onStop() { persistAllTabs(); super.onStop(); }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // capture all session states for RAM persistence across recreation
        List<GeckoSession.SessionState> states = new ArrayList<>();
        for (Tab t : tabManager.getTabs()) if (t.latestState != null) states.add(t.latestState);
        sPersistedStates = states;
        sPersistedActiveIndex = tabManager.getActiveIndex();
    }

    @Override protected void onDestroy() {
        if (menuPopup != null) { menuPopup.dismiss(); menuPopup = null; }
        if (exitDialog != null) { exitDialog.dismiss(); exitDialog = null; }
        if (geckoView != null) geckoView.setSession(null);
        if (tabManager != null) tabManager.closeAll();
        if (history != null) history.close();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) { super.onConfigurationChanged(newConfig); }

    // ============================================================
    //  Adapters
    // ============================================================

    private class SuggestionsAdapter extends BaseAdapter {
        private List<HistoryStore.Entry> data = new ArrayList<>();
        void setData(List<HistoryStore.Entry> d) { data = d; notifyDataSetChanged(); }
        @Override public int getCount() { return data.size(); }
        @Override public HistoryStore.Entry getItem(int p) { return data.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int pos, View cv, ViewGroup parent) {
            View v = cv != null ? cv : getLayoutInflater().inflate(R.layout.row_item, parent, false);
            HistoryStore.Entry e = data.get(pos);
            int theme = ThemeManager.get(MainActivity.this);
            TextView title = v.findViewById(R.id.row_title);
            TextView sub = v.findViewById(R.id.row_subtitle);
            title.setText(e.title == null || e.title.isEmpty() ? e.url : e.title);
            title.setTextColor(ThemeManager.textPrimary(theme));
            sub.setText(e.url);
            sub.setTextColor(ThemeManager.textMuted(theme));
            return v;
        }
    }

    private class TabListAdapter extends BaseAdapter {
        @Override public int getCount() { return tabManager.count(); }
        @Override public Tab getItem(int p) { return tabManager.getTabs().get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int pos, View cv, ViewGroup parent) {
            View v = cv != null ? cv : getLayoutInflater().inflate(R.layout.row_tab, parent, false);
            Tab t = getItem(pos);
            int theme = ThemeManager.get(MainActivity.this);
            TextView title = v.findViewById(R.id.row_title);
            TextView sub = v.findViewById(R.id.row_subtitle);
            TextView close = v.findViewById(R.id.row_close);
            title.setText(t.title == null || t.title.isEmpty() ? "New tab" : t.title);
            title.setTextColor(ThemeManager.textPrimary(theme));
            sub.setText(t.url.isEmpty() ? "(empty)" : t.url);
            sub.setTextColor(ThemeManager.textMuted(theme));
            close.setTextColor(ThemeManager.textMuted(theme));
            close.setOnClickListener(x -> { tabManager.closeTab(pos); notifyDataSetChanged(); });
            if (pos == tabManager.getActiveIndex()) {
                v.setBackgroundColor(theme == ThemeManager.LIGHT ? 0x14000000 : 0x22FFFFFF);
            } else {
                v.setBackgroundColor(Color.TRANSPARENT);
            }
            return v;
        }
    }

    private class ItemAdapter extends BaseAdapter {
        private List<HistoryStore.Entry> data;
        ItemAdapter(List<HistoryStore.Entry> d) { data = d; }
        void setData(List<HistoryStore.Entry> d) { data = d; notifyDataSetChanged(); }
        @Override public int getCount() { return data.size(); }
        @Override public HistoryStore.Entry getItem(int p) { return data.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int pos, View cv, ViewGroup parent) {
            View v = cv != null ? cv : getLayoutInflater().inflate(R.layout.row_item, parent, false);
            HistoryStore.Entry e = data.get(pos);
            int theme = ThemeManager.get(MainActivity.this);
            TextView title = v.findViewById(R.id.row_title);
            TextView sub = v.findViewById(R.id.row_subtitle);
            title.setText(e.title == null || e.title.isEmpty() ? e.url : e.title);
            title.setTextColor(ThemeManager.textPrimary(theme));
            sub.setText(e.url);
            sub.setTextColor(ThemeManager.textMuted(theme));
            return v;
        }
    }
}
