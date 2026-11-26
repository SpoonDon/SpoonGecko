package com.spoongecko.browser;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import org.mozilla.geckoview.PanZoomController;

/**
 * Pull-to-refresh container for BrowserGeckoView.
 *
 * Strictly prevents false refresh triggers when scrolling up partway and reversing downward,
 * during horizontal swipes, during multi-touch/pinch zoom, or while scrolling inner web containers:
 *  - A pull can only begin if the page is already at top (scrollY <= 2) at ACTION_DOWN.
 *  - Any upward finger movement (scrolling down), horizontal drag, multi-pointer touch, or page
 *    scroll away from top permanently disarms the gesture until the finger lifts.
 *  - GeckoView's InputResultDetail must confirm neither page JS nor an inner scrollable container
 *    with content above (SCROLLABLE_FLAG_TOP) owns the touch.
 */
public class PullToRefreshLayout extends FrameLayout {

    public interface CanPullCallback {
        boolean canStartPull();
    }

    public interface OnRefreshListener {
        void onRefresh();
    }

    private static final long REFRESH_TIMEOUT_MS = 2500L;

    private final int touchSlop;
    private final float disarmUpSlop;
    private final float pullStartSlop;
    private final float triggerDistance;
    private final float maxDropPx;
    private final float hiddenY;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BrowserGeckoView geckoView;
    private FrameLayout indicatorPill;
    private GradientDrawable indicatorBg;
    private ImageView arrowIcon;
    private ProgressBar spinner;

    private CanPullCallback canPullCallback;
    private OnRefreshListener refreshListener;

    private long activeDownTime = -1L;
    private boolean gotDownDetail = false;
    private boolean detailSafe = false;
    private boolean armed = false;
    private boolean disarmed = true;
    private boolean pulling = false;
    private boolean refreshing = false;

    private float downX;
    private float downY;
    private float lastY;
    private float pullStartY;
    private float currentRawPull;

    private final Runnable refreshTimeoutTask = () -> setRefreshing(false);

    public PullToRefreshLayout(Context context) {
        this(context, null);
    }

    public PullToRefreshLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PullToRefreshLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        disarmUpSlop = Math.max(touchSlop * 0.45f, dpF(4f));
        pullStartSlop = Math.max(touchSlop * 1.75f, dpF(14f));
        triggerDistance = dpF(88f);
        maxDropPx = dpF(76f);
        hiddenY = -dpF(64f);
        buildIndicator(context);
    }

    private void buildIndicator(Context context) {
        final int size = dp(42);
        indicatorPill = new FrameLayout(context);
        indicatorPill.setElevation(dpF(6f));
        indicatorPill.setClickable(false);
        indicatorPill.setFocusable(false);
        indicatorPill.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        indicatorBg = new GradientDrawable();
        indicatorBg.setShape(GradientDrawable.OVAL);
        indicatorBg.setColor(0xEE18181A);
        indicatorBg.setStroke(dp(1), 0x44FFFFFF);
        indicatorPill.setBackground(indicatorBg);

        arrowIcon = new ImageView(context);
        arrowIcon.setImageResource(R.drawable.ic_retry);
        arrowIcon.setImageTintList(ColorStateList.valueOf(0xCCFFFFFF));
        arrowIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        final LayoutParams iconLp = new LayoutParams(dp(20), dp(20), Gravity.CENTER);
        indicatorPill.addView(arrowIcon, iconLp);

        spinner = new ProgressBar(context);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
        spinner.setVisibility(GONE);
        final LayoutParams spinLp = new LayoutParams(dp(22), dp(22), Gravity.CENTER);
        indicatorPill.addView(spinner, spinLp);

        indicatorPill.setTranslationY(hiddenY);
        indicatorPill.setAlpha(0f);
        indicatorPill.setVisibility(INVISIBLE);

        final LayoutParams pillLp = new LayoutParams(size, size, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        addView(indicatorPill, pillLp);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child instanceof BrowserGeckoView) {
                attachGeckoView((BrowserGeckoView) child);
                break;
            }
        }
        indicatorPill.bringToFront();
    }

    public void attachGeckoView(BrowserGeckoView view) {
        this.geckoView = view;
        if (view == null) return;
        view.setInputResultListener(new BrowserGeckoView.InputResultListener() {
            @Override
            public boolean wantsInputResult(long downTime) {
                return armed && !disarmed && !pulling && !refreshing && downTime == activeDownTime;
            }

            @Override
            public void onInputResultDetail(long downTime, int actionMasked,
                                            PanZoomController.InputResultDetail detail) {
                handleInputResultDetail(downTime, actionMasked, detail);
            }
        });
        indicatorPill.bringToFront();
    }

    public void setCanPullCallback(CanPullCallback callback) {
        this.canPullCallback = callback;
    }

    public void setOnRefreshListener(OnRefreshListener listener) {
        this.refreshListener = listener;
    }

    public void onPageScrolledAwayFromTop() {
        disarmed = true;
        if (pulling) {
            pulling = false;
            animateHideIndicator();
        }
    }

    public void setRefreshing(boolean isRefreshing) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> setRefreshing(isRefreshing));
            return;
        }
        if (isRefreshing) {
            startRefreshing();
        } else {
            main.removeCallbacks(refreshTimeoutTask);
            if (refreshing) {
                refreshing = false;
                if (!pulling) animateHideIndicator();
            }
        }
    }

    public void cancelPendingPull() {
        disarmed = true;
        armed = false;
        if (pulling) {
            pulling = false;
            animateHideIndicator();
        }
        setRefreshing(false);
    }

    private void handleInputResultDetail(long downTime, int actionMasked,
                                         PanZoomController.InputResultDetail detail) {
        if (downTime != activeDownTime || disarmed || pulling || refreshing) return;
        final int handled = detail.handledResult();
        final boolean jsOwned = handled == PanZoomController.INPUT_RESULT_HANDLED_CONTENT
                || handled == PanZoomController.INPUT_RESULT_IGNORED;
        final boolean canScrollUp = (detail.scrollableDirections()
                & PanZoomController.SCROLLABLE_FLAG_TOP) != 0;

        if (jsOwned || canScrollUp) {
            disarmed = true;
            detailSafe = false;
            return;
        }
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            gotDownDetail = true;
            detailSafe = true;
        }
    }

    private boolean canPullNow() {
        return canPullCallback == null || canPullCallback.canStartPull();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                activeDownTime = ev.getDownTime();
                gotDownDetail = false;
                detailSafe = false;
                pulling = false;
                currentRawPull = 0f;
                downX = ev.getX();
                downY = ev.getY();
                lastY = downY;
                armed = !refreshing && ev.getPointerCount() == 1 && canPullNow();
                disarmed = !armed;
                return false;

            case MotionEvent.ACTION_POINTER_DOWN:
                disarmed = true;
                if (pulling) {
                    pulling = false;
                    animateHideIndicator();
                }
                return false;

            case MotionEvent.ACTION_MOVE: {
                if (pulling) return true;
                if (disarmed || refreshing) return false;
                if (ev.getPointerCount() > 1 || !canPullNow()) {
                    disarmed = true;
                    return false;
                }
                final float x = ev.getX();
                final float y = ev.getY();
                final float dx = x - downX;
                final float dy = y - downY;
                final float stepY = y - lastY;
                lastY = y;

                // Any upward finger movement (scrolling down) or direction reversal disarms permanently.
                if (dy < -disarmUpSlop || stepY < -disarmUpSlop) {
                    disarmed = true;
                    return false;
                }

                // Horizontal swipes belong to the page (carousels, image strips, etc.).
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.max(0f, dy) * 0.85f) {
                    disarmed = true;
                    return false;
                }

                // Wait for GeckoView's InputResultDetail on ACTION_DOWN before hijacking the touch.
                if (!gotDownDetail || !detailSafe) {
                    return false;
                }

                if (dy > pullStartSlop && dy > Math.abs(dx) * 1.4f && stepY > 0f) {
                    pulling = true;
                    pullStartY = y;
                    currentRawPull = 0f;
                    final ViewParent p = getParent();
                    if (p != null) p.requestDisallowInterceptTouchEvent(true);
                    updatePullVisual(0f);
                    return true;
                }
                return false;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                disarmed = true;
                return pulling;

            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!pulling) return super.onTouchEvent(ev);
        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
                pulling = false;
                disarmed = true;
                animateHideIndicator();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() > 1 || !canPullNow()) {
                    pulling = false;
                    disarmed = true;
                    animateHideIndicator();
                    return true;
                }
                currentRawPull = Math.max(0f, ev.getY() - pullStartY);
                updatePullVisual(currentRawPull);
                return true;

            case MotionEvent.ACTION_UP:
                pulling = false;
                disarmed = true;
                if (currentRawPull >= triggerDistance && canPullNow()) {
                    startRefreshing();
                } else {
                    animateHideIndicator();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                pulling = false;
                disarmed = true;
                animateHideIndicator();
                return true;

            default:
                return true;
        }
    }

    private void updatePullVisual(float rawPull) {
        indicatorPill.animate().cancel();
        if (indicatorPill.getVisibility() != VISIBLE) {
            indicatorPill.setVisibility(VISIBLE);
        }
        arrowIcon.setVisibility(VISIBLE);
        spinner.setVisibility(GONE);

        final float progress = Math.min(1f, rawPull / triggerDistance);
        final float dampedDrop = (float) (maxDropPx * (1.0 - Math.exp(-rawPull / (triggerDistance * 1.15f))));
        final float restStartY = -dpF(48f);
        indicatorPill.setTranslationY(restStartY + dampedDrop);
        indicatorPill.setAlpha(Math.min(1f, progress * 1.4f));
        final float scale = 0.72f + 0.28f * progress;
        indicatorPill.setScaleX(scale);
        indicatorPill.setScaleY(scale);
        arrowIcon.setRotation(progress * 260f);

        final boolean ready = rawPull >= triggerDistance;
        indicatorBg.setColor(ready ? 0xF5252529 : 0xEE18181A);
        indicatorBg.setStroke(dp(1), ready ? 0xAAFFFFFF : 0x44FFFFFF);
        arrowIcon.setImageTintList(ColorStateList.valueOf(ready ? Color.WHITE : 0xAAFFFFFF));
    }

    private void startRefreshing() {
        refreshing = true;
        indicatorPill.animate().cancel();
        indicatorPill.setVisibility(VISIBLE);
        arrowIcon.setVisibility(GONE);
        spinner.setVisibility(VISIBLE);
        indicatorBg.setColor(0xF5252529);
        indicatorBg.setStroke(dp(1), 0x88FFFFFF);

        indicatorPill.animate()
                .translationY(dpF(18f))
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150L)
                .start();

        main.removeCallbacks(refreshTimeoutTask);
        main.postDelayed(refreshTimeoutTask, REFRESH_TIMEOUT_MS);

        if (refreshListener != null) refreshListener.onRefresh();
    }

    private void animateHideIndicator() {
        indicatorPill.animate().cancel();
        indicatorPill.animate()
                .translationY(hiddenY)
                .alpha(0f)
                .scaleX(0.75f)
                .scaleY(0.75f)
                .setDuration(180L)
                .withEndAction(() -> {
                    if (!pulling && !refreshing) {
                        indicatorPill.setVisibility(INVISIBLE);
                    }
                })
                .start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private float dpF(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
