package com.spoongecko.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

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
        } catch (e: Exception) { "[]" }
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

    // --- NEW: CSV EXPORT & IMPORT ---
    fun exportToCsv(uri: Uri, context: Context) {
        val jsonStr = getAllCredentialsAsJson()
        if (jsonStr == "[]") {
            Toast.makeText(context, "Vault is empty", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val arr = JSONArray(jsonStr)
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                val writer = OutputStreamWriter(stream)
                writer.write("host,username,password\n")
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val host = obj.optString("host", "").replace("\"", "\"\"")
                    val user = obj.optString("username", "").replace("\"", "\"\"")
                    val pass = obj.optString("password", "").replace("\"", "\"\"")
                    writer.write("\"$host\",\"$user\",\"$pass\"\n")
                }
                writer.flush()
            }
            Toast.makeText(context, "CSV Exported successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun importFromCsv(uri: Uri, context: Context) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val reader = BufferedReader(InputStreamReader(stream))
                reader.readLine() // Skip header
                var line = reader.readLine()
                var count = 0
                while (line != null) {
                    val parts = parseCsvLine(line)
                    if (parts.size >= 3 && parts[0].isNotEmpty()) {
                        saveCredentials(parts[0], parts[1], parts[2])
                        count++
                    }
                    line = reader.readLine()
                }
                Toast.makeText(context, "Imported $count logins", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (c in line) {
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) {
                result.add(current.toString())
                current.clear()
            } else current.append(c)
        }
        result.add(current.toString())
        return result
    }

    // --- NEW: AUTOFILL HELPER FOR GECKOVIEW ---
    fun getLoginsForDomain(domain: String): List<Autocomplete.LoginEntry> {
        val entries = mutableListOf<Autocomplete.LoginEntry>()
        if (!isReady) return entries
        try {
            val all = encryptedPrefs?.all ?: return entries
            val hosts = mutableSetOf<String>()
            for (key in all.keys) {
                if (key.endsWith("_primary_user")) {
                    hosts.add(key.removeSuffix("_primary_user"))
                }
            }
            for (host in hosts) {
                if (host.contains(domain, ignoreCase = true) || domain.contains(host, ignoreCase = true)) {
                    val user = getUsername(host)
                    val pass = getPassword(host)
                    if (user.isNotEmpty() && pass.isNotEmpty()) {
                        val origin = if (host.startsWith("http")) host else "https://$host"
                        entries.add(Autocomplete.LoginEntry.Builder().origin(origin).username(user).password(pass).build())
                    }
                }
            }
        } catch (_: Exception) {}
        return entries
    }
}
