package com.spoongecko.browser;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.PanZoomController;

/**
 * GeckoView subclass that forwards PanZoomController input-result details for touch-down
 * gestures so PullToRefreshLayout can detect inner scrollable containers without
 * double-dispatching or stalling move events.
 */
public class BrowserGeckoView extends GeckoView {

    public interface InputResultListener {
        boolean wantsInputResult(long downTime);
        void onInputResultDetail(long downTime, int actionMasked,
                                 PanZoomController.InputResultDetail detail);
        void onInputResultUnavailable(long downTime);
    }

    private InputResultListener inputResultListener;

    public BrowserGeckoView(Context context) {
        super(context);
    }

    public BrowserGeckoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setInputResultListener(InputResultListener listener) {
        this.inputResultListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getSession() == null) {
            return super.onTouchEvent(event);
        }
        final int action = event.getActionMasked();
        final long downTime = event.getDownTime();
        final InputResultListener listener = inputResultListener;
        if (listener != null
                && action == MotionEvent.ACTION_DOWN
                && listener.wantsInputResult(downTime)) {
            final GeckoResult<PanZoomController.InputResultDetail> result =
                    super.onTouchEventForDetailResult(event);
            if (result != null) {
                result.accept(detail -> {
                    final InputResultListener current = inputResultListener;
                    if (current == null) return;
                    if (detail != null) {
                        current.onInputResultDetail(downTime, action, detail);
                    } else {
                        current.onInputResultUnavailable(downTime);
                    }
                });
                return true;
            } else {
                listener.onInputResultUnavailable(downTime);
            }
        }
        return super.onTouchEvent(event);
    }
}
