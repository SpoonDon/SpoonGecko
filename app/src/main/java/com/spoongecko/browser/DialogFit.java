package com.spoongecko.browser;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

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
}
