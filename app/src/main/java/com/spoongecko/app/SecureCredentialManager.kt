package com.spoongecko.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class SecureCredentialManager(context: Context) {

    private var encryptedPrefs: SharedPreferences? = null
    private var isReady = false

    init {
        try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            encryptedPrefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                "spoon_secure_vault",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            isReady = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun saveCredentials(host: String, username: String, password: String) {
        if (!isReady || host.isEmpty() || username.isEmpty()) return
        val cleanUser = username.trim()
        encryptedPrefs?.edit()
            ?.putString("${host}_${cleanUser}_user", cleanUser)
            ?.putString("${host}_${cleanUser}_pass", password)
            ?.putString("${host}_primary_user", cleanUser)
            ?.apply()
    }

    @Synchronized
    fun getUsername(host: String): String {
        if (!isReady) return ""
        return encryptedPrefs?.getString("${host}_primary_user", "") ?: ""
    }

    @Synchronized
    fun getPassword(host: String): String {
        if (!isReady) return ""
        val username = getUsername(host)
        if (username.isEmpty()) return ""
        return encryptedPrefs?.getString("${host}_${username}_pass", "") ?: ""
    }

    @Synchronized
    fun getAllAccountsForHost(host: String): String {
        if (!isReady || host.isEmpty()) return "[]"
        return try {
            val array = JSONArray()
            val all = encryptedPrefs?.all ?: return "[]"
            for (key in all.keys) {
                if (key.startsWith("${host}_") && key.endsWith("_user") && !key.endsWith("_primary_user")) {
                    val username = all[key] as? String ?: continue
                    if (username.isEmpty()) continue
                    val password = encryptedPrefs?.getString("${host}_${username}_pass", "") ?: ""
                    val obj = JSONObject()
                    obj.put("username", username)
                    obj.put("password", password)
                    array.put(obj)
                }
            }
            array.toString()
        } catch (e: Exception) {
            "[]"
        }
    }

    @Synchronized
    fun getAllCredentialsAsJson(): String {
        val array = JSONArray()
        if (!isReady) return array.toString()
        try {
            val all = encryptedPrefs?.all ?: return array.toString()
            for (key in all.keys) {
                if (key.endsWith("_user") && !key.endsWith("_primary_user")) {
                    val username = all[key] as? String ?: continue
                    if (username.isEmpty()) continue
                    val suffix = "_${username}_user"
                    if (key.endsWith(suffix) && key.length > suffix.length) {
                        val host = key.substring(0, key.length - suffix.length)
                        val password = encryptedPrefs?.getString("${host}_${username}_pass", "") ?: ""
                        val obj = JSONObject()
                        obj.put("host", host)
                        obj.put("username", username)
                        obj.put("password", password)
                        array.put(obj)
                    }
                }
            }
        } catch (e: Exception) { }
        return array.toString()
    }

    @Synchronized
    fun deleteCredentials(host: String, username: String) {
        if (!isReady || host.isEmpty() || username.isEmpty()) return
        val editor = encryptedPrefs?.edit() ?: return
        editor.remove("${host}_${username}_pass")
        editor.remove("${host}_${username}_user")
        if (username == (encryptedPrefs?.getString("${host}_primary_user", "") ?: "")) {
            editor.remove("${host}_primary_user")
        }
        editor.commit()
    }

    @Synchronized
    fun editCredentialPassword(host: String, username: String, newPassword: String) {
        if (!isReady || host.isEmpty() || username.isEmpty()) return
        encryptedPrefs?.edit()
            ?.putString("${host}_${username.trim()}_pass", newPassword)
            ?.apply()
    }

    @Synchronized
    fun clearCredentials(host: String) {
        if (!isReady || host.isEmpty()) return
        val editor = encryptedPrefs?.edit() ?: return
        val all = encryptedPrefs?.all ?: return
        for (key in all.keys) {
            if (key.startsWith("${host}_")) {
                editor.remove(key)
            }
        }
        editor.apply()
    }
}
