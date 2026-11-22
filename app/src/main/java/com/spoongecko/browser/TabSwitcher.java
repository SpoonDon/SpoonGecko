package com.spoongecko.browser;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [TABS] Chrome-style tab switcher.
 *
 * A full-screen grid of tab cards, opened from the tab button. Each card shows the page title, a
 * letter badge for the site, a close button and a preview of the page. Tap a card to switch to
 * that tab, swipe it sideways (or tap its X) to close it. The top bar has "Close all" and
 * "New tab". The tab being viewed is outlined.
 *
 * Closing the LAST tab, by any route, leaves the browser at once and without a question (see
 * {@link Host#closeAllTabsAndExit()}). "Close all" asks first, in the app's own dialog style,
 * because it can close many tabs with one tap.
 *
 * Design rules, so that nothing that already works is touched:
 *  - It only uses the existing {@link TabManager} API (switchTo / closeTab / closeAll). Tabs,
 *    sessions, delegates, extensions, media playback and saving of tabs are all handled by the
 *    code that already exists; this class never creates or opens a GeckoSession.
 *  - It is a separate window (a Dialog), so no view of the main screen is added, moved or
 *    reordered. Closing it leaves the main screen exactly as it was.
 *  - Previews are small screenshots held in memory only (see {@link Tab#thumbnail}). Nothing is
 *    written to disk and nothing is fetched from the network (no favicon downloads).
 */
public final class TabSwitcher {

    private static final String TAG = "SpoonGecko";

    /** Height of a card's preview as a multiple of its width. */
    private static final float THUMB_RATIO = 1.15f;
    /** Width, in pixels, of a stored preview. Small on purpose: one is kept per tab. */
    private static final int THUMB_WIDTH_PX = 320;
    /** The switcher opens at once if the page screenshot has not arrived within this time. */
    private static final long CAPTURE_TIMEOUT_MS = 350L;

    private static final int[] AVATAR_COLORS = {
            0xFF5E5CE6, 0xFF0A84FF, 0xFF30B0C7, 0xFF32A852,
            0xFFD99A00, 0xFFE8590C, 0xFFD6336C, 0xFF7048E8
    };

    /** What the switcher needs from the screen that hosts it. */
    public interface Host {
        /** Open a new empty tab and show it (the same as the menu's "New tab"). */
        void openNewTab();
        /** Tabs were closed here: save the tab list. */
        void tabsEdited();
        /** True while the home page (search box) covers the page area. */
        boolean isHomePanelShown();
        /**
         * Close every tab that is left and leave the browser, with no question asked. Called when
         * the last tab is closed. The saved tab list is emptied, so the next start opens the home
         * page rather than bringing back a tab the user closed.
         */
        void closeAllTabsAndExit();
    }

    private final Activity activity;
    private final TabManager tabManager;
    private final GeckoView geckoView;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Dialog dialog;
    private Dialog confirm;
    private CardAdapter adapter;
    private TextView titleText;
    /** A preview is being taken before the switcher opens. */
    private boolean opening;

    public TabSwitcher(Activity activity, TabManager tabManager, GeckoView geckoView, Host host) {
        this.activity = activity;
        this.tabManager = tabManager;
        this.geckoView = geckoView;
        this.host = host;
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    /** Opens the switcher. A fresh preview of the visible page is taken first (briefly). */
    public void open() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (opening || (dialog != null && dialog.isShowing())) return;
        opening = true;
        final OpenOnce once = new OpenOnce();
        boolean started = false;
        try {
            started = startCapture(tabManager.getActiveTab(), once);
        } catch (RuntimeException e) {
            Log.w(TAG, "tab preview could not start", e);
        }
        if (started) handler.postDelayed(once, CAPTURE_TIMEOUT_MS);
        else once.run();
    }

    /** Shows the switcher exactly once, whether the preview arrived, failed or timed out. */
    private final class OpenOnce implements Runnable {
        private boolean done;

        @Override public void run() {
            if (done) return;
            done = true;
            opening = false;
            show();
        }
    }

    /**
     * Takes a screenshot of the page on screen for the active tab's card.
     *
     * @return true if a capture was started (then {@code onDone} is called when it ends);
     *         false if there is nothing to capture, and the caller carries on at once.
     */
    private boolean startCapture(final Tab tab, final Runnable onDone) {
        if (tab == null || tab.awaitingOpen) return false;
        if (tab.url == null || tab.url.isEmpty() || host.isHomePanelShown()) {
            tab.thumbnail = null;      // the card shows its placeholder
            return false;
        }
        if (!tab.session.isOpen() || geckoView.getSession() != tab.session) return false;
        geckoView.capturePixels()
                .accept(bitmap -> activity.runOnUiThread(() -> {
                    if (bitmap != null) {
                        final Bitmap small = makeThumbnail(bitmap);
                        if (small != null) tab.thumbnail = small;
                    }
                    onDone.run();
                    refresh();          // in case the switcher was already open (slow capture)
                }))
                .exceptionally(e -> {
                    Log.w(TAG, "tab preview failed", e);
                    activity.runOnUiThread(onDone);
                    return null;
                });
        return true;
    }

    /**
     * Scales a full-size screenshot down to a card preview: the top of the page, filling the
     * card. 16-bit colour and a small width keep each preview to a couple of hundred KB.
     */
    private static Bitmap makeThumbnail(Bitmap src) {
        try {
            final int sw = src.getWidth();
            final int sh = src.getHeight();
            if (sw <= 0 || sh <= 0) return null;
            final int tw = Math.min(THUMB_WIDTH_PX, sw);
            final int th = Math.round(tw * THUMB_RATIO);
            final float scale = Math.max(tw / (float) sw, th / (float) sh);
            final int srcW = Math.min(sw, Math.round(tw / scale));
            final int srcH = Math.min(sh, Math.round(th / scale));
            final int srcX = (sw - srcW) / 2;
            final Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565);
            final Canvas canvas = new Canvas(out);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(src, new Rect(srcX, 0, srcX + srcW, srcH),
                    new Rect(0, 0, tw, th), new Paint(Paint.FILTER_BITMAP_FLAG));
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "tab preview could not be scaled", e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void show() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog != null && dialog.isShowing()) return;

        final Dialog d = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_tab_switcher);

        final Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            w.setStatusBarColor(Color.BLACK);
            w.setNavigationBarColor(Color.BLACK);
        }

        // Keep the content clear of the system bars, whether or not this window is edge-to-edge.
        final View root = d.findViewById(R.id.ts_root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            final int[] i = systemInsets(insets);
            v.setPadding(i[0], i[1], i[2], i[3]);
            return insets;
        });

        titleText = d.findViewById(R.id.ts_title);
        final GridView grid = d.findViewById(R.id.ts_grid);
        adapter = new CardAdapter();
        grid.setAdapter(adapter);
        // Cards are sized from the column width: redo them when it changes (rotation, split screen).
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (adapter != null && (r - l) != (or - ol)) adapter.notifyDataSetChanged();
        });

        d.findViewById(R.id.ts_back).setOnClickListener(v -> d.dismiss());
        d.findViewById(R.id.ts_new).setOnClickListener(v -> {
            d.dismiss();
            host.openNewTab();
        });
        d.findViewById(R.id.ts_close_all).setOnClickListener(v -> confirmCloseAll());

        d.setOnDismissListener(x -> {
            if (dialog == d) {
                dialog = null;
                adapter = null;
                titleText = null;
            }
        });

        dialog = d;
        updateTitle();
        d.show();

        // Start with the tab being viewed in sight.
        final int activeAt = tabManager.getActiveIndex();
        if (activeAt > 0) grid.setSelection(activeAt);
    }

    /** Left, top, right, bottom space taken by the system bars and display cut-outs. */
    private static int[] systemInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return systemInsetsR(insets);
        return legacyInsets(insets);
    }

    @SuppressWarnings("deprecation")
    private static int[] legacyInsets(WindowInsets insets) {
        return new int[]{insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()};
    }

    private static int[] systemInsetsR(WindowInsets insets) {
        final android.graphics.Insets i = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        return new int[]{i.left, i.top, i.right, i.bottom};
    }

    /** Closes the switcher window (and the "close all" question) without touching any tab. */
    public void dismiss() {
        handler.removeCallbacksAndMessages(null);
        opening = false;
        final Dialog c = confirm;
        confirm = null;
        if (c != null) {
            try { c.dismiss(); } catch (RuntimeException ignored) { }
        }
        final Dialog d = dialog;
        dialog = null;
        adapter = null;
        titleText = null;
        if (d != null) {
            try { d.dismiss(); } catch (RuntimeException ignored) { }
        }
    }

    /** The tab list or the active tab changed: redraw the cards. No-op when not showing. */
    public void refresh() {
        if (dialog == null || adapter == null) return;
        adapter.reload();
        updateTitle();
    }

    private void updateTitle() {
        if (titleText == null) return;
        final int n = tabManager.count();
        titleText.setText(n == 1 ? "1 tab" : n + " tabs");
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void select(Tab t) {
        final int idx = tabManager.getTabs().indexOf(t);
        if (idx >= 0) tabManager.switchTo(idx);
        final Dialog d = dialog;
        if (d != null) d.dismiss();
    }

    /**
     * Closes one tab. Same rule as the Back button's closeToOpener(): when the tab being viewed
     * is closed, another tab is switched to FIRST, so the session that gets closed is never the
     * one still attached to the GeckoView.
     */
    private void closeTab(Tab t) {
        final List<Tab> all = tabManager.getTabs();
        final int at = all.indexOf(t);
        if (at < 0) {
            reload();
            return;
        }
        if (all.size() == 1) {
            closeEverythingAndExit();      // the last tab: leave the browser, no question asked
            return;
        }
        if (all.size() > 1 && t == tabManager.getActiveTab()) {
            tabManager.switchTo(at + 1 < all.size() ? at + 1 : at - 1);
        }
        final int now = tabManager.getTabs().indexOf(t);
        if (now >= 0) tabManager.closeTab(now);   // the last tab is replaced by a new empty one
        host.tabsEdited();
        reload();
    }

    /**
     * "Close all": with one tab it is simply closing the last tab (no question); with several it
     * asks first, in the same style as the exit dialog (layout dialog_confirm).
     */
    private void confirmCloseAll() {
        final int n = tabManager.count();
        if (n == 0 || activity.isFinishing() || activity.isDestroyed()) return;
        if (n == 1) {
            closeEverythingAndExit();
            return;
        }
        if (confirm != null && confirm.isShowing()) return;

        final Dialog c = new Dialog(activity);
        c.requestWindowFeature(Window.FEATURE_NO_TITLE);
        c.setContentView(R.layout.dialog_confirm);
        final Window w = c.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.7f);
        }
        // [RESIZE] 85% of the app window's width, and again whenever the window is resized.
        DialogFit.fitAndFollow(activity, c, 0.85f, 0f, null);
        final TextView title = c.findViewById(R.id.confirm_title);
        final TextView message = c.findViewById(R.id.confirm_message);
        final TextView ok = c.findViewById(R.id.confirm_ok);
        title.setText("Close all " + n + " tabs?");
        message.setText("All tabs will be closed and SpoonGecko will exit.");
        ok.setText("Close all");
        c.findViewById(R.id.confirm_cancel).setOnClickListener(v -> c.dismiss());
        ok.setOnClickListener(v -> {
            c.dismiss();
            closeEverythingAndExit();
        });
        c.setOnDismissListener(x -> {
            if (confirm == c) confirm = null;
        });
        confirm = c;
        c.show();
    }

    /** Hides the switcher, then hands over to the screen, which closes the tabs and exits. */
    private void closeEverythingAndExit() {
        final Dialog d = dialog;
        if (d != null) d.dismiss();
        host.closeAllTabsAndExit();
    }

    private void reload() {
        if (adapter != null) adapter.reload();
        updateTitle();
    }

    // ------------------------------------------------------------------
    // Cards
    // ------------------------------------------------------------------

    private final class CardAdapter extends BaseAdapter {
        private List<Tab> data = new ArrayList<>(tabManager.getTabs());

        void reload() {
            data = new ArrayList<>(tabManager.getTabs());
            notifyDataSetChanged();
        }

        @Override public int getCount() { return data.size(); }
        @Override public Tab getItem(int position) { return data.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView
                    : activity.getLayoutInflater().inflate(R.layout.item_tab_card, parent, false);
            final Tab t = data.get(position);
            final boolean isActive = t == tabManager.getActiveTab();

            // A recycled card may still be mid-animation from the tab it showed before.
            v.animate().cancel();
            v.setTranslationX(0f);
            v.setAlpha(1f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setForeground(activity.getDrawable(isActive
                    ? R.drawable.bg_tab_card_border_active : R.drawable.bg_tab_card_border));

            final String site = hostOf(t.url);
            final boolean blank = t.url == null || t.url.isEmpty();

            final TextView title = v.findViewById(R.id.card_title);
            title.setText(blank ? "New tab"
                    : !isBlank(t.title) ? t.title
                    : !site.isEmpty() ? site : t.url);

            final TextView icon = v.findViewById(R.id.card_icon);
            final GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            if (blank || site.isEmpty()) {
                dot.setColor(0xFF3A3A3C);
                icon.setText("");
            } else {
                final char c = site.charAt(0);
                dot.setColor(AVATAR_COLORS[(site.hashCode() & 0x7fffffff) % AVATAR_COLORS.length]);
                icon.setText(Character.isLetterOrDigit(c)
                        ? String.valueOf(c).toUpperCase(Locale.ROOT) : "\u2022");
            }
            icon.setBackground(dot);

            // Preview: sized from the column width so every card has the same proportions.
            final View preview = v.findViewById(R.id.card_preview);
            final int colW = parent instanceof GridView ? ((GridView) parent).getColumnWidth() : 0;
            final int h = Math.round((colW > 0 ? colW : dp(160)) * THUMB_RATIO);
            final ViewGroup.LayoutParams lp = preview.getLayoutParams();
            if (lp.height != h) {
                lp.height = h;
                preview.setLayoutParams(lp);
            }
            final ImageView thumb = v.findViewById(R.id.card_thumb);
            final TextView placeholder = v.findViewById(R.id.card_placeholder);
            if (t.thumbnail != null && !blank) {
                thumb.setImageBitmap(t.thumbnail);
                thumb.setVisibility(View.VISIBLE);
                placeholder.setVisibility(View.GONE);
            } else {
                thumb.setImageDrawable(null);
                thumb.setVisibility(View.GONE);
                placeholder.setText(blank ? "SpoonGecko" : !site.isEmpty() ? site : t.url);
                placeholder.setVisibility(View.VISIBLE);
            }

            v.findViewById(R.id.card_close).setOnClickListener(x -> closeWithAnimation(v, t));
            v.setOnClickListener(x -> select(t));
            v.setOnTouchListener(new SwipeToClose(t));
            return v;
        }
    }

    private void closeWithAnimation(final View card, final Tab t) {
        card.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(120)
                .withEndAction(() -> closeTab(t)).start();
    }

    /**
     * Horizontal swipe on a card closes its tab; a tap selects it; a vertical drag is left to the
     * grid, which scrolls (it takes the touch over and this listener gets ACTION_CANCEL).
     */
    private final class SwipeToClose implements View.OnTouchListener {
        private final Tab tab;
        private final int slop;
        private float downX;
        private float downY;
        private boolean dragging;

        SwipeToClose(Tab tab) {
            this.tab = tab;
            this.slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    final float dx = e.getRawX() - downX;
                    final float dy = e.getRawY() - downY;
                    if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        final ViewParent p = v.getParent();
                        if (p != null) p.requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        v.setTranslationX(dx);
                        v.setAlpha(Math.max(0.2f, 1f - Math.abs(dx) / Math.max(1, v.getWidth())));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    final float dx = e.getRawX() - downX;
                    final float dy = e.getRawY() - downY;
                    if (dragging) {
                        if (Math.abs(dx) > v.getWidth() * 0.4f) flingAway(v, dx > 0);
                        else settle(v);
                    } else if (Math.abs(dx) <= slop && Math.abs(dy) <= slop) {
                        v.performClick();
                    }
                    dragging = false;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) settle(v);
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        }

        private void flingAway(View v, boolean toRight) {
            final float off = v.getWidth() * (toRight ? 1.2f : -1.2f);
            v.animate().translationX(off).alpha(0f).setDuration(140)
                    .withEndAction(() -> closeTab(tab)).start();
        }

        private void settle(View v) {
            v.animate().translationX(0f).alpha(1f).setDuration(120).start();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** "www." is dropped: the badge and the label look better as "example.com". */
    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            final String h = Uri.parse(url).getHost();
            if (h == null) return "";
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
