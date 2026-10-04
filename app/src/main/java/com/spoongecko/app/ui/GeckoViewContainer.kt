package com.spoongecko.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.spoongecko.app.browser.GeckoRuntimeHolder
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Compose wrapper around GeckoView.
 *
 * The view is created once and reused; switching tabs is a cheap
 * setSession() call, which keeps Gecko's compositor warm.
 */
@Composable
fun GeckoViewContainer(
    session: GeckoSession?,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GeckoView(ctx).apply {
                // Attach the runtime with a null session first; swap later.
                setSession(null, GeckoRuntimeHolder.get())
            }
        },
        update = { view ->
            if (view.session !== session) {
                view.setSession(session)
            }
        }
    )
}
