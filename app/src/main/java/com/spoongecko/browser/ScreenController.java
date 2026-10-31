package com.spoongecko.browser;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/**
 * Owns visual chrome: flash-guard, progress bar, home panel visibility,
 * edge-to-edge insets, fullscreen mode, and AMOLED theme. Holds no
 * application state (no tabs, no history).
 */
public final class ScreenController {

    private final Activity activity;
    private final FrameLayout contentContainer;
    private final View homePanel;
    private final ProgressBar progressBar;
    private final EditText urlBar;
    private final EditText searchBar;
    private final View root;
    private final View urlPanel;

    private final int originalPaddingTop;
    private View flashGuard;

    public ScreenController(Activity activity) {
        this.activity = activity;
        this.root = activity.findViewById(R.id.root);
        this.urlPanel = activity.findViewById(R.id.url_bar_panel);
        this.contentContainer = activity.findViewById(R.id.content_container);
        this.homePanel = activity.findViewById(R.id.home_panel);
        this.progressBar = activity.findViewById(R.id.progress_bar);
        this.urlBar = activity.findViewById(R.id.url_bar);
        this.searchBar = activity.findViewById(R.id.search_bar);
        this.originalPaddingTop = urlPanel.getPaddingTop();
    }

    /**
     * [C2] Window-only immersive setup, deliberately free of any view lookup so
     * it is safe to call before setContentView() (the old code instantiated
     * ScreenController before the content view existed and crashed on the null
     * views in the constructor).
     */
    public static void applyImmersiveWindow(Window w) {
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

    public void makeImmersive() {
        applyImmersiveWindow(activity.getWindow());
    }

    public void installFlashGuard() {
        flashGuard = new View(activity);
        flashGuard.setBackgroundColor(Color.BLACK);
        flashGuard.setClickable(false);
        flashGuard.setFocusable(false);
        contentContainer.addView(flashGuard, 1, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public void removeFlashGuard() {
        activity.runOnUiThread(() -> {
            if (flashGuard != null && flashGuard.getParent() != null) {
                ((ViewGroup) flashGuard.getParent()).removeView(flashGuard);
                flashGuard = null;
            }
        });
    }

    public void showHomePanel(boolean show) {
        homePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        contentContainer.requestApplyInsets();
    }

    public boolean isHomePanelVisible() {
        return homePanel.getVisibility() == View.VISIBLE;
    }

    public void setProgress(int progress) {
        activity.runOnUiThread(() -> {
            if (progressBar == null) return;
            progressBar.setProgress(progress);
            progressBar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
        });
    }

    public void setProgressBarVisible(boolean visible) {
        activity.runOnUiThread(() -> {
            if (progressBar == null) return;
            if (visible) {
                progressBar.setIndeterminate(true);
                progressBar.setVisibility(View.VISIBLE);
            } else {
                progressBar.setVisibility(View.GONE);
            }
        });
    }

    @SuppressWarnings("deprecation")
    public void setupWindowInsets() {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset;
            int imeInset;
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
            // [M6] Always apply the IME inset. It used to be applied only while
            // the home panel was visible; combined with
            // SYSTEM_UI_FLAG_LAYOUT_STABLE (which stops the window itself from
            // resizing) the keyboard covered inputs inside web pages.
            contentContainer.setPadding(0, 0, 0, imeInset);
            return insets;
        });
    }

    @SuppressWarnings("deprecation")
    public void setFullScreenMode(boolean fullScreen) {
        if (fullScreen) {
            urlPanel.setVisibility(View.GONE);
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            activity.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            urlPanel.setVisibility(View.VISIBLE);
            activity.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        contentContainer.requestApplyInsets();
    }

    public void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
            imm.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
        }
    }
}
