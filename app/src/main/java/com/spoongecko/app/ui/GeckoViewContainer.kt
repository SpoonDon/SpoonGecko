package com.spoongecko.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Compose wrapper around GeckoView.
 *
 * The view is created once and reused; switching tabs is a cheap
 * setSession() call, which keeps Gecko's compositor warm.
 *
 * GeckoView 157 API note: setSession() takes a single GeckoSession argument.
 * The runtime is NOT passed to the view — it's bound to the session when
 * session.open(runtime) is called (see TabManager). The view inherits the
 * runtime implicitly from whatever session it's given.
 */
@Composable
fun GeckoViewContainer(
    session: GeckoSession?,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx -> GeckoView(ctx) },
        update = { view ->
            val current = view.session
            if (session != null && current !== session) {
                view.setSession(session)
            }
        }
    )
}
