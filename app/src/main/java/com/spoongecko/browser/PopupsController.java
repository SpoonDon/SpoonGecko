package com.spoongecko.browser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
        void onReload();
        void onHome();
        void onShare();
        void onShowHistory();
        void onShowExtensions();
        void onShowExit();
        void onOpenInNewTab(String url);
        /** [DESKTOP] Whether the current tab is showing the desktop site. */
        boolean isDesktopMode();
        /** [DESKTOP] Switch the current tab between the mobile and desktop site. */
        void onToggleDesktopMode();
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

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(R.drawable.bg_glass_dialog);
        int pad = dp(6);
        container.setPadding(pad, pad, pad, pad);

        addMenuItem(container, "New tab", false, host::onNewTab);
        addMenuItem(container, "Reload", false, host::onReload);
        addMenuItem(container, "Desktop site: " + (host.isDesktopMode() ? "On" : "Off"), false,
                host::onToggleDesktopMode);
        addMenuItem(container, "Home", false, host::onHome);
        addMenuItem(container, "Share", false, host::onShare);
        addMenuItem(container, "Extensions", false, host::onShowExtensions);
        addMenuItem(container, "History", false, host::onShowHistory);
        addMenuItem(container, "Exit", true, host::onShowExit);

        // [FIT] With the extra row the menu can be taller than the space below the
        // button (landscape phones). Scroll instead of letting the last rows
        // (including Exit) fall off the screen.
        final int menuWidth = dp(190);
        container.measure(
                View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int wanted = container.getMeasuredHeight();
        final int[] anchorOnScreen = new int[2];
        menuAnchor.getLocationOnScreen(anchorOnScreen);
        final int available = activity.getResources().getDisplayMetrics().heightPixels
                - (anchorOnScreen[1] + menuAnchor.getHeight()) - dp(16);

        View content = container;
        int height = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (available > dp(120) && wanted > available) {
            ScrollView scroll = new ScrollView(activity);
            container.setBackground(null);
            scroll.setBackgroundResource(R.drawable.bg_glass_dialog);
            scroll.addView(container);
            content = scroll;
            height = available;
        }

        menuPopup = new PopupWindow(content, menuWidth, height, true);
        menuPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menuPopup.setOutsideTouchable(true);
        menuPopup.setElevation(dp(8));
        menuPopup.showAsDropDown(menuAnchor, 0, dp(8));
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
            addMenuItem(container, "Copy link", false, () -> copyToClipboard(activity, link));
            addMenuItem(container, "Share link", false, () -> shareText(activity, link));
        }
        if (hasSrc) {
            addMenuItem(container, "Open in new tab", false,
                    () -> host.onOpenInNewTab(src));
            addMenuItem(container, "Copy URL", false, () -> copyToClipboard(activity, src));
            addMenuItem(container, "Share URL", false, () -> shareText(activity, src));
        }

        if (contextMenuPopup != null && contextMenuPopup.isShowing()) contextMenuPopup.dismiss();
        contextMenuPopup = new PopupWindow(container, dp(220),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        contextMenuPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        contextMenuPopup.setOutsideTouchable(true);
        contextMenuPopup.setElevation(dp(8));
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
