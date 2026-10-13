package com.spoongecko.browser;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.NestedScrollingChild;
import androidx.core.view.NestedScrollingChildHelper;
import androidx.core.view.ViewCompat;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.PanZoomController;

/**
 * GeckoView that supports nested scrolls, for use inside a
 * SwipeRefreshLayout.
 *
 * This is a Java port of Mozilla's NestedGeckoView.kt from Android
 * Components. It intercepts touch events and asks GeckoView's
 * PanZoomController how each event will be handled. The resulting
 * InputResultDetail tells us:
 *
 *   - who handled the touch (browser, website, or nobody)
 *   - whether the content can scroll to the top
 *   - whether the content can be overscrolled vertically
 *
 * From that we decide whether the parent SwipeRefreshLayout is allowed
 * to intercept the touch for pull-to-refresh. This is reliable for any
 * scroll container (SPA shells, overflow:auto, sticky headers) because
 * it is synchronised with Gecko's own APZ, unlike polling
 * document.scrollingElement.scrollTop from a content script.
 */
public class NestedGeckoView extends GeckoView implements NestedScrollingChild {

    private int mLastY;
    private final int[] mScrollOffset = new int[2];
    private final int[] mScrollConsumed = new int[2];
    private boolean mGestureCanReachParent = true;
    private float mInitialDownY;
    private int mNestedOffsetY;
    private final NestedScrollingChildHelper mChildHelper;

    private PanZoomController.InputResultDetail mInputResultDetail;

    public NestedGeckoView(Context context) {
        super(context);
        mChildHelper = new NestedScrollingChildHelper(this);
        setNestedScrollingEnabled(true);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final MotionEvent event = MotionEvent.obtain(ev);
        final int action = ev.getActionMasked();
        final int eventY = (int) event.getY();

        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                final boolean allowScroll = !shouldPinOnScreen()
                        && mInputResultDetail != null
                        && isTouchHandledByBrowser();

                int deltaY = mLastY - eventY;

                if (allowScroll && dispatchNestedPreScroll(0, deltaY,
                        mScrollConsumed, mScrollOffset)) {
                    deltaY -= mScrollConsumed[1];
                    event.offsetLocation(0, -mScrollOffset[1]);
                    mNestedOffsetY += mScrollOffset[1];
                }
                mLastY = eventY - mScrollOffset[1];

                if (allowScroll && dispatchNestedScroll(0, mScrollOffset[1], 0,
                        deltaY, mScrollOffset)) {
                    mLastY -= mScrollOffset[1];
                    event.offsetLocation(0, mScrollOffset[1]);
                    mNestedOffsetY += mScrollOffset[1];
                }

                final boolean hasDragGestureStarted = event.getY() != mInitialDownY;
                if (mGestureCanReachParent && hasDragGestureStarted) {
                    updateInputResult(event);
                    event.recycle();
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_DOWN: {
                final ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                updateInputResult(event);
                mNestedOffsetY = 0;
                mLastY = eventY;
                mInitialDownY = event.getY();
                event.recycle();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                // Reset to the "allow pull-to-refresh" state. This is done
                // here (not on the next ACTION_DOWN) because other features
                // poll getInputResultDetail() between gestures and must not
                // see stale data from the previous gesture.
                mInputResultDetail = null;
                stopNestedScroll();
                final ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
                mGestureCanReachParent = true;
                break;
            }
        }

        final boolean eventHandled = super.onTouchEvent(event);
        event.recycle();
        return eventHandled;
    }

    private void updateInputResult(MotionEvent event) {
        final int eventAction = event.getActionMasked();
        final float eventY = event.getY();

        super.onTouchEventForDetailResult(event).accept(result -> {
            // The APZ response is async. If the gesture has already moved
            // past the point where the parent could still intercept, drop
            // the result.
            if (!mGestureCanReachParent) {
                return;
            }
            mInputResultDetail = result;

            if (eventAction == MotionEvent.ACTION_DOWN) {
                mGestureCanReachParent = canOverscrollTop();
                if (mGestureCanReachParent && isTouchUnhandled()) {
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                }
            } else if (eventAction == MotionEvent.ACTION_MOVE) {
                if (mInitialDownY < eventY) {
                    // Finger moved down (content scrolls up). Allow the
                    // parent to intercept only if the website did not
                    // consume the event.
                    if (!isTouchHandledByWebsite()) {
                        final ViewParent parent = getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(false);
                        }
                    }
                } else if (mInitialDownY > eventY) {
                    // Finger moved up (content scrolls down). Once the
                    // content has started scrolling down, the parent must
                    // never intercept.
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    mGestureCanReachParent = false;
                } else {
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                }
            }
        });

        startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL);
    }

    /**
     * Returns true if the content can be overscrolled at the top, which is
     * the condition for pull-to-refresh.
     *
     * Mirrors Mozilla's InputResultDetail.canOverscrollTop():
     *   - the website must not have consumed the touch
     *   - the content must not be able to scroll to the top
     *     (i.e. we are already at the top)
     *   - the content must be able to overscroll vertically
     */
    public boolean canOverscrollTop() {
        if (mInputResultDetail == null) {
            // No touch information yet. Allow pull-to-refresh, matching
            // Mozilla's initial state (InputResultDetail.newInstance(true)).
            return true;
        }
        final int inputResult = mInputResultDetail.handledResult();
        final int scrollDirections = mInputResultDetail.scrollableDirections();
        final int overscrollDirections = mInputResultDetail.overscrollDirections();

        return inputResult != PanZoomController.INPUT_RESULT_HANDLED_CONTENT
                && (scrollDirections & PanZoomController.SCROLLABLE_FLAG_TOP) == 0
                && (overscrollDirections & PanZoomController.OVERSCROLL_FLAG_VERTICAL) != 0;
    }

    private boolean isTouchHandledByBrowser() {
        return mInputResultDetail != null
                && mInputResultDetail.handledResult() == PanZoomController.INPUT_RESULT_HANDLED;
    }

    private boolean isTouchHandledByWebsite() {
        return mInputResultDetail != null
                && mInputResultDetail.handledResult() == PanZoomController.INPUT_RESULT_HANDLED_CONTENT;
    }

    private boolean isTouchUnhandled() {
        return mInputResultDetail != null
                && mInputResultDetail.handledResult() == PanZoomController.INPUT_RESULT_UNHANDLED;
    }

    private boolean shouldPinOnScreen() {
        return false;
    }

    // ----------------------------------------------------------------
    // NestedScrollingChild implementation
    // ----------------------------------------------------------------

    @Override
    public void setNestedScrollingEnabled(boolean enabled) {
        mChildHelper.setNestedScrollingEnabled(enabled);
    }

    @Override
    public boolean isNestedScrollingEnabled() {
        return mChildHelper.isNestedScrollingEnabled();
    }

    @Override
    public boolean startNestedScroll(int axes) {
        return mChildHelper.startNestedScroll(axes);
    }

    @Override
    public void stopNestedScroll() {
        mChildHelper.stopNestedScroll();
    }

    @Override
    public boolean hasNestedScrollingParent() {
        return mChildHelper.hasNestedScrollingParent();
    }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed,
                                        int dxUnconsumed, int dyUnconsumed,
                                        @Nullable int[] offsetInWindow) {
        return mChildHelper.dispatchNestedScroll(dxConsumed, dyConsumed,
                dxUnconsumed, dyUnconsumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy,
                                           @Nullable int[] consumed,
                                           @Nullable int[] offsetInWindow) {
        return mChildHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
        return mChildHelper.dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
        return mChildHelper.dispatchNestedPreFling(velocityX, velocityY);
    }
}
