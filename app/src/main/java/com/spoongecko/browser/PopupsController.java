package com.spoongecko.browser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/**
 * The overflow menu and the long-press context menu. Both are built with
 * the same addMenuItem helper and dismissed together when either is shown.
 */
public final class PopupsController {

    public interface Host {
        void onNewTab();
        /** [INCOGNITO] A new tab that leaves no trace: no history, no saved tab, cookies kept apart. */
        void onNewIncognitoTab();
        /** [WINDOW] Another browser window, with its own tabs. */
        void onNewWindow();
        /** [WINDOW] [INCOGNITO] Another browser window whose first tab is an incognito tab. */
        void onNewIncognitoWindow();
        void onReload();
        void onHome();
        void onShare();
        void onShowHistory();
        void onShowSettings();
        int activeDownloadCount();
        void onShowExit();
        /** [FIND] Open the find-in-page bar for the current page. */
        void onFindInPage();
        void onOpenInNewTab(String url);
        /** [DESKTOP] Whether the current tab is showing the desktop site. */
        boolean isDesktopMode();
        /** [DESKTOP] Switch the current tab between the mobile and desktop site. */
        void onToggleDesktopMode();
        /** [DOWNLOAD] Save this address as a file (long-press menu). */
        void onDownloadUrl(String url);
        /** [DOWNLOAD] Show the system list of downloaded files. */
        void onShowDownloads();
        /** [PDF] Menu entry for the page on screen ("Download PDF" / "Save page as PDF"), or null for none. */
        String pdfMenuLabel();
        /** [PDF] The user chose that entry. */
        void onPdfMenuAction();        
        /** [TRANSLATE] Menu entry for page translation, or null when this device cannot translate. */
        String translateMenuLabel();
        /** [TRANSLATE] The user chose that entry. */
        void onTranslateMenuAction();
        /** [BOOKMARKS] Label for bookmarking/unbookmarking the current session page, or null if not bookmarkable. */
        String bookmarkMenuLabel();
        /** [BOOKMARKS] Toggle bookmark state for the current session page. */
        void onToggleBookmark();
        /** [BOOKMARKS] Show the saved bookmarks dialog. */
        void onShowBookmarks();
    }

    private final Activity activity;
    private final GeckoView geckoView;
    private final View menuAnchor;
    private final Host host;

    private PopupWindow menuPopup;
    private PopupWindow contextMenuPopup;

    public PopupsController(Activity activity, GeckoView geckoView, View menuAnchor, Host host) {
        this.activity = activity;
        this.geckoView = geckoView;
        this.menuAnchor = menuAnchor;
        this.host = host;
    }

    public void showMenu() {
        if (menuPopup != null && menuPopup.isShowing()) { menuPopup.dismiss(); return; }
        openMenu();
    }

    private void openMenu() {
        final int pad = dp(6);
        final FrameLayout card = new FrameLayout(activity);
        card.setBackgroundResource(R.drawable.bg_glass_dialog);
        card.setPadding(pad, pad, pad, pad);
        card.setClipToOutline(true);

        final ScrollView scroll = new ScrollView(activity);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setVerticalScrollBarEnabled(false);
        final int innerRadius = dp(14);
        scroll.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), innerRadius);
            }
        });
        scroll.setClipToOutline(true);

        final LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);

        addMenuItem(container, "New tab", false, host::onNewTab);
        addMenuItem(container, "New incognito tab", false, host::onNewIncognitoTab);   // [INCOGNITO]
        addMenuItem(container, "New window", false, host::onNewWindow);                // [WINDOW]
        addMenuItem(container, "New incognito window", false, host::onNewIncognitoWindow);
        addMenuItem(container, "Reload", false, host::onReload);
        addMenuItem(container, "Desktop site: " + (host.isDesktopMode() ? "On" : "Off"), false,
                host::onToggleDesktopMode);
        addMenuItem(container, "Home", false, host::onHome);
        addMenuItem(container, "Share", false, host::onShare);
        final String pdfLabel = host.pdfMenuLabel();   // [PDF]
        if (pdfLabel != null) addMenuItem(container, pdfLabel, false, host::onPdfMenuAction);        
        final String translateLabel = host.translateMenuLabel();   // [TRANSLATE]
        if (translateLabel != null) addMenuItem(container, translateLabel, false, host::onTranslateMenuAction);
        final String bookmarkLabel = host.bookmarkMenuLabel();     // [BOOKMARKS]
        if (bookmarkLabel != null) addMenuItem(container, bookmarkLabel, false, host::onToggleBookmark);
        addMenuItem(container, "Bookmarks", false, host::onShowBookmarks);
        addMenuItem(container, "History", false, host::onShowHistory);
        addMenuItem(container, "Find in page", false, host::onFindInPage);
        final int activeDownloads = host.activeDownloadCount();
        addMenuItem(container, activeDownloads > 0
                ? "Downloads (" + activeDownloads + ")" : "Downloads", false, host::onShowDownloads);
        addMenuItem(container, "Settings", false, host::onShowSettings);
        addMenuItem(container, "Exit", true, host::onShowExit);

        scroll.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // [FIT] Measure items against the padded inner width and track visible window bounds
        // (excluding IME + system bars) so bottom-toolbar / keyboard transitions keep the menu
        // strictly framed inside bg_glass_dialog without items bleeding past the border.
        final int baseMenuWidth = dp(190);
        final int[] lastContentW = {-1};
        final int[] wanted = {0};
        final int[] lastPlacement = {-1, -1, -1, -1}; // x, y, w, h

        final PopupWindow popup = new PopupWindow(card, baseMenuWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        menuPopup = popup;

        final Runnable computeAndApply = () -> {
            if (activity.isFinishing() || activity.isDestroyed() || !menuAnchor.isAttachedToWindow()) {
                return;
            }
            final int windowW = DialogFit.windowWidth(activity);
            final int windowH = DialogFit.windowHeight(activity);
            final Rect visible = DialogFit.visibleBounds(activity);
            final int safeLeft = Math.max(dp(8), visible.left + dp(8));
            final int safeRight = Math.min(windowW - dp(8), visible.right - dp(8));
            final int safeTop = Math.max(dp(16), visible.top + dp(8));
            final int safeBottom = Math.min(windowH - dp(16), visible.bottom - dp(8));

            final int popupW = Math.min(baseMenuWidth, Math.max(dp(120), safeRight - safeLeft));
            final int contentW = Math.max(dp(60), popupW - pad * 2);
            if (contentW != lastContentW[0]) {
                container.measure(
                        View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                wanted[0] = container.getMeasuredHeight() + pad * 2;
                lastContentW[0] = contentW;
            }

            final int[] anchorInWindow = new int[2];
            menuAnchor.getLocationInWindow(anchorInWindow);
            final int spaceBelow = Math.max(0,
                    safeBottom - (anchorInWindow[1] + menuAnchor.getHeight() + dp(8)));
            final int spaceAbove = Math.max(0, anchorInWindow[1] - dp(8) - safeTop);
            final boolean openAbove = spaceBelow < Math.min(wanted[0], dp(220))
                    && spaceAbove > spaceBelow;
            final int available = Math.max(dp(48), openAbove ? spaceAbove : spaceBelow);
            final int popupH = Math.min(wanted[0], available);

            final int x = Math.max(safeLeft, Math.min(safeRight - popupW,
                    anchorInWindow[0] + menuAnchor.getWidth() - popupW));
            final int y = openAbove
                    ? Math.max(safeTop, anchorInWindow[1] - popupH - dp(8))
                    : Math.max(safeTop, Math.min(safeBottom - popupH,
                            anchorInWindow[1] + menuAnchor.getHeight() + dp(8)));

            if (popupH >= wanted[0] && scroll.getScrollY() != 0) {
                scroll.scrollTo(0, 0);
            }

            if (!popup.isShowing()) {
                lastPlacement[0] = x;
                lastPlacement[1] = y;
                lastPlacement[2] = popupW;
                lastPlacement[3] = popupH;
                popup.setWidth(popupW);
                popup.setHeight(popupH);
                popup.showAtLocation(menuAnchor, Gravity.TOP | Gravity.LEFT, x, y);
            } else if (x != lastPlacement[0] || y != lastPlacement[1]
                    || popupW != lastPlacement[2] || popupH != lastPlacement[3]) {
                lastPlacement[0] = x;
                lastPlacement[1] = y;
                lastPlacement[2] = popupW;
                lastPlacement[3] = popupH;
                popup.update(x, y, popupW, popupH);
            }
        };

        computeAndApply.run();
        if (!popup.isShowing()) {
            if (menuPopup == popup) menuPopup = null;
            return;
        }

        // [RESIZE] Keep menu sized and anchored across window resizes AND keyboard show/hide.
        final Runnable stopResizeWatch = DialogFit.watch(activity, computeAndApply);
        final Runnable stopAnchorWatch = DialogFit.watchAnchor(menuAnchor, computeAndApply);
        popup.setOnDismissListener(() -> {
            stopResizeWatch.run();
            stopAnchorWatch.run();
            if (menuPopup == popup) menuPopup = null;
        });
    }

    public void showContextMenu(int x, int y,
                                GeckoSession.ContentDelegate.ContextElement element) {
        if (element == null) return;
        final String link = element.linkUri;
        final String src = element.srcUri;
        final boolean hasLink = link != null && !link.isEmpty();
        final boolean hasSrc = src != null && !src.isEmpty()
                && (element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
                || element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO
                || element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO);

        if (!hasLink && !hasSrc) return;

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(R.drawable.bg_glass_dialog);
        int pad = dp(6);
        container.setPadding(pad, pad, pad, pad);

        if (hasLink) {
            addMenuItem(container, "Open link in new tab", false,
                    () -> host.onOpenInNewTab(link));
            addMenuItem(container, "Download link", false, () -> host.onDownloadUrl(link));
            addMenuItem(container, "Copy link", false, () -> copyToClipboard(activity, link));
            addMenuItem(container, "Share link", false, () -> shareText(activity, link));
        }
        if (hasSrc) {
            addMenuItem(container, "Open in new tab", false,
                    () -> host.onOpenInNewTab(src));
            addMenuItem(container, "Download", false, () -> host.onDownloadUrl(src));
            addMenuItem(container, "Copy URL", false, () -> copyToClipboard(activity, src));
            addMenuItem(container, "Share URL", false, () -> shareText(activity, src));
        }

        if (contextMenuPopup != null && contextMenuPopup.isShowing()) contextMenuPopup.dismiss();
        contextMenuPopup = new PopupWindow(container, dp(220),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        contextMenuPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        contextMenuPopup.setOutsideTouchable(true);
        contextMenuPopup.setElevation(dp(8));
        // [RESIZE] The menu sits at the spot that was pressed. When the app window is resized that
        // spot is somewhere else (or gone), so the menu is closed rather than left in the wrong
        // place; the watcher stops when the popup is dismissed, however that happens.
        final Runnable stopWatching = DialogFit.watch(activity, this::dismissContextMenu);
        contextMenuPopup.setOnDismissListener(stopWatching::run);
        // [F9] onContextMenu delivers *screen* coordinates (screenX/screenY per
        // the ContentDelegate javadoc), but PopupWindow.showAtLocation() takes
        // window-relative offsets. The old code passed them through unchanged
        // (plus a made-up +40dp), so the menu appeared below/right of the press.
        int[] inWindow = new int[2];
        int[] onScreen = new int[2];
        geckoView.getLocationInWindow(inWindow);
        geckoView.getLocationOnScreen(onScreen);
        int winX = x - (onScreen[0] - inWindow[0]);
        int winY = y - (onScreen[1] - inWindow[1]);
        contextMenuPopup.showAtLocation(geckoView,
                Gravity.TOP | Gravity.START, winX, winY);
    }

    public void dismissAll() {
        if (menuPopup != null) { menuPopup.dismiss(); menuPopup = null; }
        dismissContextMenu();
    }

    private void dismissContextMenu() {
        if (contextMenuPopup != null) { contextMenuPopup.dismiss(); contextMenuPopup = null; }
    }

    private void addMenuItem(LinearLayout parent, String label, boolean red, Runnable action) {
        TextView item = new TextView(activity);
        item.setText(label);
        item.setTextSize(14);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), dp(12), dp(16), dp(12));
        item.setTextColor(red ? 0xFFFF6666 : 0xFFFFFFFF);
        item.setBackgroundResource(R.drawable.bg_glass_button);
        item.setClickable(true);
        item.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(2);
        parent.addView(item, lp);
        item.setOnClickListener(v -> {
            dismissAll();
            action.run();
        });
    }

    public static void copyToClipboard(Activity activity, String text) {
        ClipboardManager cm = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("URL", text));
        Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show();
    }

    /**
     * [F4] This used to throw an uncaught ActivityNotFoundException on devices
     * with no share target (also reachable from navigator.share()). It now
     * reports whether the sheet was actually shown.
     */
    public static boolean shareText(Activity activity, String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            activity.startActivity(Intent.createChooser(i, "Share"));
            return true;
        } catch (Exception e) {
            Toast.makeText(activity, "No share app available", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private int dp(int px) {
        return Math.round(px * activity.getResources().getDisplayMetrics().density);
    }
}
