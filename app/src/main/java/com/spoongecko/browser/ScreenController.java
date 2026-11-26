package com.spoongecko.browser;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

/**
 * Owns visual chrome: flash-guard, progress bar, home panel visibility,
 * top/bottom toolbar positioning, edge-to-edge insets, fullscreen mode, and AMOLED theme.
 */
public final class ScreenController {

    private final Activity activity;
    private final FrameLayout contentContainer;
    private final View homePanel;
    private final ProgressBar progressBar;
    private final EditText urlBar;
    private final EditText searchBar;
    private final View suggestionsList;
    private final LinearLayout root;
    private final View urlPanel;

    private final int originalPaddingTop;
    private final int originalPaddingBottom;
    private View flashGuard;
    private boolean bottomToolbar = false;
    private boolean fullScreenMode = false;

    public ScreenController(Activity activity) {
        this.activity = activity;
        this.root = activity.findViewById(R.id.root);
        this.urlPanel = activity.findViewById(R.id.url_bar_panel);
        this.contentContainer = activity.findViewById(R.id.content_container);
        this.homePanel = activity.findViewById(R.id.home_panel);
        this.progressBar = activity.findViewById(R.id.progress_bar);
        this.urlBar = activity.findViewById(R.id.url_bar);
        this.searchBar = activity.findViewById(R.id.search_bar);
        this.suggestionsList = activity.findViewById(R.id.suggestions_list);
        this.originalPaddingTop = urlPanel.getPaddingTop();
        this.originalPaddingBottom = urlPanel.getPaddingBottom();
    }

    @SuppressWarnings("deprecation")
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

    public boolean isBottomToolbar() {
        return bottomToolbar;
    }

    /**
     * Moves the browser toolbar (url_bar_panel + progress_bar + optional find bar) between the
     * top and bottom of the screen without detaching content_container (keeping GeckoView's
     * surface intact).
     */
    public void applyToolbarPosition(boolean bottom) {
        this.bottomToolbar = bottom;

        View findBar = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            final View child = root.getChildAt(i);
            if ("find_in_page_bar".equals(child.getTag())) {
                findBar = child;
                break;
            }
        }

        final int contentIdx = root.indexOfChild(contentContainer);
        final int urlIdx = root.indexOfChild(urlPanel);
        final boolean currentlyBottom = urlIdx > contentIdx;

        if (currentlyBottom != bottom) {
            root.removeView(urlPanel);
            if (progressBar != null) root.removeView(progressBar);
            if (findBar != null) root.removeView(findBar);

            if (bottom) {
                if (findBar != null) root.addView(findBar);
                if (progressBar != null) root.addView(progressBar);
                root.addView(urlPanel);
            } else {
                int insertAt = 0;
                root.addView(urlPanel, insertAt++);
                if (progressBar != null) root.addView(progressBar, insertAt++);
                if (findBar != null) root.addView(findBar, insertAt);
            }
        }

        if (progressBar != null && progressBar.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            final LinearLayout.LayoutParams pLp =
                    (LinearLayout.LayoutParams) progressBar.getLayoutParams();
            final int overlap = Math.round(activity.getResources().getDisplayMetrics().density);
            pLp.topMargin = bottom ? 0 : -overlap;
            pLp.bottomMargin = bottom ? -overlap : 0;
            progressBar.setLayoutParams(pLp);
        }

        if (suggestionsList != null
                && suggestionsList.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            final FrameLayout.LayoutParams sLp =
                    (FrameLayout.LayoutParams) suggestionsList.getLayoutParams();
            final int targetGravity = bottom ? Gravity.BOTTOM : Gravity.TOP;
            if (sLp.gravity != targetGravity) {
                sLp.gravity = targetGravity;
                suggestionsList.setLayoutParams(sLp);
            }
        }

        root.requestApplyInsets();
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
        if (flashGuard == null) return;
        activity.runOnUiThread(() -> {
            if (flashGuard == null) return;
            if (flashGuard.getParent() != null) {
                try {
                    ((ViewGroup) flashGuard.getParent()).removeView(flashGuard);
                } catch (Exception ignored) {
                }
            }
            flashGuard = null;
        });
    }

    public void showHomePanel(boolean show) {
        final int target = show ? View.VISIBLE : View.GONE;
        if (homePanel.getVisibility() == target) return;
        homePanel.setVisibility(target);
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
            int navBottom;
            int imeBottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                topInset = insets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
                navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                navBottom = 0;
                imeBottom = insets.getSystemWindowInsetBottom();
            }

            final int bottomInset = Math.max(imeBottom, fullScreenMode ? 0 : navBottom);
            if (bottomToolbar) {
                urlPanel.setPadding(
                        urlPanel.getPaddingLeft(),
                        originalPaddingBottom,
                        urlPanel.getPaddingRight(),
                        originalPaddingTop + bottomInset);
                contentContainer.setPadding(0, fullScreenMode ? 0 : topInset, 0, 0);
            } else {
                urlPanel.setPadding(
                        urlPanel.getPaddingLeft(),
                        originalPaddingTop + topInset,
                        urlPanel.getPaddingRight(),
                        originalPaddingBottom);
                contentContainer.setPadding(0, 0, 0, bottomInset);
            }
            if (suggestionsList != null && suggestionsList.getVisibility() == View.VISIBLE) {
                suggestionsList.requestLayout();
            }
            return insets;
        });
    }

    @SuppressWarnings("deprecation")
    public void setFullScreenMode(boolean fullScreen) {
        this.fullScreenMode = fullScreen;
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
        root.requestApplyInsets();
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
