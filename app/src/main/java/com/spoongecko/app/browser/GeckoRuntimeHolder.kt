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
                .consoleOutput(true)          // TEMPORARY: revert to false before release
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
