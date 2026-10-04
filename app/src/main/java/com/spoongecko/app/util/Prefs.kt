package com.spoongecko.app.util

import android.content.Context

object Prefs {
    private const val FILE = "spoongecko_prefs"
    private const val KEY_LAST_URL = "last_url"
    private const val KEY_KEEPALIVE_ENABLED = "keepalive_enabled"
    private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun saveLastUrl(context: Context, url: String) {
        sp(context).edit().putString(KEY_LAST_URL, url).apply()
    }

    fun loadLastUrl(context: Context): String? =
        sp(context).getString(KEY_LAST_URL, null)

    fun isKeepAliveEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_KEEPALIVE_ENABLED, true)

    fun setKeepAliveEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_KEEPALIVE_ENABLED, enabled).apply()
    }

    fun isBatteryPromptShown(context: Context): Boolean =
        sp(context).getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)

    fun setBatteryPromptShown(context: Context) {
        sp(context).edit().putBoolean(KEY_BATTERY_PROMPT_SHOWN, true).apply()
    }
}
