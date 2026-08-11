package com.spoongecko.app

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

object GeckoRuntimeManager {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun getRuntime(context: Context): GeckoRuntime {
        return runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(
                context.applicationContext, 
                GeckoRuntimeSettings.Builder().build()
            ).also { runtime = it }
        }
    }

    fun shutdown() {
        runtime?.shutdown()
        runtime = null
    }
}
