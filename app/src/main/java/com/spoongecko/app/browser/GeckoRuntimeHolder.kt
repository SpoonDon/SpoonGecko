package com.spoongecko.app.browser

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Process-wide singleton for GeckoRuntime.
 *
 * Why a singleton: the runtime holds the Gecko engine, profile directory,
 * and all storage. If we let it be recreated every time the Activity
 * restarts, cold-start latency explodes and OEM kills wipe more state than
 * necessary. Keeping it process-scoped means when Android restarts our
 * process after a kill, we re-init fast and we can restore session state.
 */
object GeckoRuntimeHolder {

    @Volatile
    private var runtime: GeckoRuntime? = null

    fun init(context: Context): GeckoRuntime {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val settings = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .aboutConfigEnabled(false)
                .consoleOutput(false)
                .remoteDebuggingEnabled(false)
                .build()
            val created = GeckoRuntime.create(context.applicationContext, settings)
            runtime = created
            return created
        }
    }

    fun get(): GeckoRuntime = runtime
        ?: error("GeckoRuntime not initialized. Call GeckoRuntimeHolder.init() from Application.onCreate().")
}
