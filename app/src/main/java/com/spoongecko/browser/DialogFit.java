package com.spoongecko.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/**
 * [RESIZE] Keeps dialogs and popups the right size when the app's window changes size.
 *
 * A dialog is a window of its own. Android does not resize it when the app window is resized
 * (split screen, a freeform window, fold/unfold, rotation), so a size worked out once, when the
 * dialog opened, left it cut off after a resize. Every dialog that sizes itself from the screen
 * uses this class instead: it sizes the dialog from the app window's CURRENT size, and does it
 * again whenever that size changes, for as long as the dialog is on screen.
 *
 * It only watches the size of the app window; it never touches what a dialog shows.
 */
final class DialogFit {

    private DialogFit() { }

    /**
     * Wraps scrollable dialog content in a non-scrolling bg_glass_dialog FrameLayout with 6dp
     * border padding and a rounded-clipped ScrollView, so scrolling never clips or moves the
     * outer glass border.
     */
    static FrameLayout wrapGlassScroll(Context context, View content) {
        final float density = context.getResources().getDisplayMetrics().density;
        final int pad = Math.round(6f * density);
        final FrameLayout card = new FrameLayout(context);
        card.setBackgroundResource(R.drawable.bg_glass_dialog);
        card.setPadding(pad, pad, pad, pad);
        card.setClipToOutline(true);

        final ScrollView scroll = new ScrollView(context);
        clipRoundedScroll(scroll, 14);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** Clips a ScrollView cleanly to rounded inner corners and hides raw scrollbars inside glass cards. */
    static void clipRoundedScroll(ScrollView scroll, int cornerDp) {
        final int radius = Math.round(cornerDp * scroll.getResources().getDisplayMetrics().density);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        scroll.setClipToOutline(true);
    }

    /** Width of the app window in pixels: its real size, which a split screen or freeform window changes. */
    static int windowWidth(Activity activity) {
        final int w = activity.getWindow().getDecorView().getWidth();
        return w > 0 ? w : activity.getResources().getDisplayMetrics().widthPixels;
    }

    /** Height of the app window in pixels. */
    static int windowHeight(Activity activity) {
        final int h = activity.getWindow().getDecorView().getHeight();
        return h > 0 ? h : activity.getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * Sets the dialog's window size from the app window: {@code widthFraction} of its width, and
     * {@code heightFraction} of its height (0 or less: as tall as the content needs).
     */
    static void apply(Activity activity, Dialog dialog, float widthFraction, float heightFraction) {
        final Window w = dialog.getWindow();
        if (w == null) return;
        w.setLayout((int) (windowWidth(activity) * widthFraction),
                heightFraction > 0f ? (int) (windowHeight(activity) * heightFraction)
                        : WindowManager.LayoutParams.WRAP_CONTENT);
    }

    /**
     * Sizes the dialog now (see {@link #apply}) and keeps it sized while it is showing.
     *
     * @param alsoOnResize optional extra work for a resize (for example a list whose height
     *                     depends on the window); may be null
     */
    static void fitAndFollow(final Activity activity, final Dialog dialog,
                             final float widthFraction, final float heightFraction,
                             final Runnable alsoOnResize) {
        apply(activity, dialog, widthFraction, heightFraction);
        followResize(activity, dialog, new Runnable() {
            @Override public void run() {
                apply(activity, dialog, widthFraction, heightFraction);
                if (alsoOnResize != null) alsoOnResize.run();
            }
        });
    }

    /**
     * Runs {@code onResize} (on the UI thread, after the app window has settled) whenever the app
     * window changes size, for as long as {@code dialog} is on screen. Watching stops by itself
     * when the dialog goes away, and does not use the dialog's own dismiss listener, so the
     * dialog's code stays free to set one.
     */
    static void followResize(Activity activity, final Dialog dialog, final Runnable onResize) {
        final Runnable stop = watch(activity, new Runnable() {
            @Override public void run() {
                if (dialog.isShowing()) onResize.run();
            }
        });
        final Window w = dialog.getWindow();
        if (w == null) return;
        w.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }

            @Override public void onViewDetachedFromWindow(View v) {
                stop.run();
                v.removeOnAttachStateChangeListener(this);
            }
        });
    }

    /**
     * Calls {@code onResize} whenever the app window changes size. The call is posted and
     * coalesced: dragging the edge of a freeform window reports every pixel, and the windows of
     * dialogs and popups must not be changed in the middle of the app window's own layout pass.
     * A change of position without a change of size is ignored, and so is anything that leaves the
     * window's size as it was (the keyboard, the status bar).
     *
     * @return a stopper; call it when the thing that is being kept fitted goes away (it can be
     *         called more than once)
     */
    static Runnable watch(Activity activity, final Runnable onResize) {
        final View appDecor = activity.getWindow().getDecorView();
        final boolean[] queued = {false};
        final boolean[] stopped = {false};
        final View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if ((right - left) == (oldRight - oldLeft) && (bottom - top) == (oldBottom - oldTop)) {
                    return;
                }
                if (queued[0] || stopped[0]) return;
                queued[0] = true;
                v.post(new Runnable() {
                    @Override public void run() {
                        queued[0] = false;
                        if (!stopped[0]) onResize.run();
                    }
                });
            }
        };
        appDecor.addOnLayoutChangeListener(listener);
        return new Runnable() {
            @Override public void run() {
                stopped[0] = true;
                appDecor.removeOnLayoutChangeListener(listener);
            }
        };
    }

    /**
     * Window-relative bounds excluding system bars, display cutouts, and the soft keyboard (IME).
     * Unlike {@link #windowHeight}, this shrinks while the IME is visible even when the activity
     * uses {@code fitsSystemWindows="false"} for edge-to-edge rendering.
     */
    @SuppressWarnings("deprecation")
    static Rect visibleBounds(Activity activity) {
        final View decor = activity.getWindow().getDecorView();
        final int w = windowWidth(activity);
        final int h = windowHeight(activity);
        int left = 0, top = 0, right = w, bottom = h;
        final WindowInsets insets = decor.getRootWindowInsets();
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                final Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                final Insets ime = insets.getInsets(WindowInsets.Type.ime());
                left = bars.left;
                top = bars.top;
                right = Math.max(left, w - bars.right);
                bottom = Math.max(top, h - Math.max(bars.bottom, ime.bottom));
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = Math.max(left, w - insets.getSystemWindowInsetRight());
                bottom = Math.max(top, h - insets.getSystemWindowInsetBottom());
            }
        }
        return new Rect(left, top, right, bottom);
    }

    /**
     * Runs {@code onLayoutChanged} (coalesced on the UI thread after layout) whenever the anchor's
     * window layout changes, including soft keyboard show/hide and toolbar repositioning.
     */
    static Runnable watchAnchor(final View anchor, final Runnable onLayoutChanged) {
        final ViewTreeObserver vto = anchor.getViewTreeObserver();
        final boolean[] queued = {false};
        final boolean[] stopped = {false};
        final ViewTreeObserver.OnGlobalLayoutListener listener =
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (queued[0] || stopped[0]) return;
                        queued[0] = true;
                        anchor.post(new Runnable() {
                            @Override public void run() {
                                queued[0] = false;
                                if (!stopped[0] && anchor.isAttachedToWindow()) {
                                    onLayoutChanged.run();
                                }
                            }
                        });
                    }
                };
        vto.addOnGlobalLayoutListener(listener);
        return new Runnable() {
            @Override public void run() {
                stopped[0] = true;
                if (vto.isAlive()) {
                    vto.removeOnGlobalLayoutListener(listener);
                } else {
                    final ViewTreeObserver current = anchor.getViewTreeObserver();
                    if (current.isAlive()) current.removeOnGlobalLayoutListener(listener);
                }
            }
        };
    }
}
