package com.spoongecko.app

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import org.mozilla.geckoview.GeckoView

class GestureManager(
    context: Context,
    private val geckoView: GeckoView,
    private val getActiveTab: () -> TabInfo?
) {
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null) return false
            val diffX = e2.x - e1.x
            val diffY = e2.y - e1.y
            if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 150 && Math.abs(velocityX) > 200) {
                val tab = getActiveTab() ?: return false
                if (diffX > 0 && e1.x < 150 && tab.canGoBack) {
                    tab.session.goBack(); return true
                } else if (diffX < 0 && e1.x > (geckoView.width - 150) && tab.canGoForward) {
                    tab.session.goForward(); return true
                }
            }
            return false
        }
    })

    fun attach() {
        geckoView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // Crucial: return false so GeckoView still scrolls the page normally!
        }
    }
}
