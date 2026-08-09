package com.spoongecko.app

import android.content.Context
import android.net.Uri

data class VaultCredential(
    val host: String,
    val username: String,
    val password: String
)
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
    private val appContext = context.applicationContext

    init {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            encryptedPrefs = EncryptedSharedPreferences.create(
                appContext,
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

    // MAGIC FIX: Strips "https://" and "www." so CSV imports match GeckoView's domain requests
    private fun cleanHost(host: String): String {
        return try {
            val uri = Uri.parse(if (host.contains("://")) host else "http://$host")
            uri.host?.removePrefix("www.") ?: host
        } catch (e: Exception) {
            host
        }
    }

    @Synchronized
    fun saveCredentials(host: String, username: String, password: String) {
        if (!isReady || host.isEmpty() || username.isEmpty()) return
        val cleanUser = username.trim()
        val cleanDomain = cleanHost(host)
        
        encryptedPrefs?.edit()
            ?.putString("${cleanDomain}_${cleanUser}_user", cleanUser)
            ?.putString("${cleanDomain}_${cleanUser}_pass", password)
            ?.putString("${cleanDomain}_primary_user", cleanUser)
            ?.apply()
    }

    @Synchronized
    fun getUsername(host: String): String {
        if (!isReady) return ""
        val cleanDomain = cleanHost(host)
        return encryptedPrefs?.getString("${cleanDomain}_primary_user", "") ?: ""
    }

    @Synchronized
    fun getPassword(host: String): String {
        if (!isReady) return ""
        val cleanDomain = cleanHost(host)
        val username = getUsername(cleanDomain)
        if (username.isEmpty()) return ""
        return encryptedPrefs?.getString("${cleanDomain}_${username}_pass", "") ?: ""
    }

    @Synchronized
    fun getAllCredentialsAsJson(): String {
        val array = JSONArray()
        if (!isReady) return array.toString()
        try {
            val all = encryptedPrefs?.all ?: return array.toString()
            val processed = mutableSetOf<String>()
            
            for (key in all.keys) {
                if (key.endsWith("_user") && !key.endsWith("_primary_user")) {
                    val username = all[key] as? String ?: continue
                    if (username.isEmpty()) continue
                    val suffix = "_${username}_user"
                    if (key.endsWith(suffix) && key.length > suffix.length) {
                        val host = key.substring(0, key.length - suffix.length)
                        val uniqueKey = "$host|$username"
                        if (processed.contains(uniqueKey)) continue
                        processed.add(uniqueKey)
                        
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
        val cleanDomain = cleanHost(host)
        val editor = encryptedPrefs?.edit() ?: return
        editor.remove("${cleanDomain}_${username}_pass")
        editor.remove("${cleanDomain}_${username}_user")
        if (username == (encryptedPrefs?.getString("${cleanDomain}_primary_user", "") ?: "")) {
            editor.remove("${cleanDomain}_primary_user")
        }
        editor.commit()
    }

    @Synchronized
    fun editCredentialPassword(host: String, username: String, newPassword: String) {
        if (!isReady || host.isEmpty() || username.isEmpty()) return
        val cleanDomain = cleanHost(host)
        encryptedPrefs?.edit()
            ?.putString("${cleanDomain}_${username.trim()}_pass", newPassword)
            ?.apply()
    }

    // --- AUTOFILL SUPPORT ---
    fun getLoginsForDomain(domain: String): List<Autocomplete.LoginEntry> {
        val entries = mutableListOf<Autocomplete.LoginEntry>()
        if (!isReady) return entries
        val cleanDomain = cleanHost(domain)
        
        try {
            val all = encryptedPrefs?.all ?: return entries
            for (key in all.keys) {
                if (key.startsWith("${cleanDomain}_") && key.endsWith("_user") && !key.endsWith("_primary_user")) {
                    val username = all[key] as? String ?: continue
                    if (username.isEmpty()) continue
                    val password = encryptedPrefs?.getString("${cleanDomain}_${username}_pass", "") ?: ""
                    if (password.isNotEmpty()) {
                        val origin = "https://$cleanDomain"
                        entries.add(Autocomplete.LoginEntry.Builder()
                            .origin(origin)
                            .username(username)
                            .password(password)
                            .build())
                    }
                }
            }
        } catch (_: Exception) {}
        return entries
    }

    // --- CSV IMPORT / EXPORT ---
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
            Toast.makeText(context, "CSV Exported successfully", Toast.LENGTH_LONG).show()
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
                Toast.makeText(context, "Imported $count logins successfully!", Toast.LENGTH_LONG).show()
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
}
