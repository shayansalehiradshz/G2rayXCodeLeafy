@file:Suppress("DEPRECATION")

package com.g2ray.client.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface SecureKeyValueStore {
    fun getString(key: String, defaultValue: String? = null): String?
    fun putString(key: String, value: String?)
    fun putStrings(values: Map<String, String?>)
    fun remove(vararg keys: String)
}

class EncryptedPrefsStore internal constructor(prefsFactory: () -> SharedPreferences) : SecureKeyValueStore {
    constructor(context: Context) : this(encryptedPrefsFactory(context.applicationContext))

    // A transient Keystore/open failure is retried by the next explicit load;
    // constructing the application or service must not throw before its guard.
    private val prefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED, prefsFactory)
    private var pendingRollback: Map<String, String?>? = null

    private companion object {
        fun encryptedPrefsFactory(context: Context): () -> SharedPreferences = {
            EncryptedSharedPreferences.create(
                context,
                "g2ray_secure_prefs",
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    @Synchronized
    override fun getString(key: String, defaultValue: String?): String? =
        readyPreferences(key).getString(key, defaultValue)

    override fun putString(key: String, value: String?) {
        putStrings(mapOf(key to value))
    }

    @Synchronized
    override fun putStrings(values: Map<String, String?>) {
        if (values.isEmpty()) return
        val desired = values.toMap()
        val current = readyPreferences()
        val previous = desired.keys.associateWith { current.getString(it, null) }
        try {
            check(commitValues(current, desired)) { "Failed to atomically save secure preferences" }
        } catch (failure: Exception) {
            // commit() can change SharedPreferences' memory even when its
            // disk write fails. Preserve the touched keys, not the whole store.
            pendingRollback = previous
            val restored = try {
                commitValues(current, previous)
            } catch (_: Exception) {
                false
            }
            if (restored) pendingRollback = null
            val detail = if (restored) "Previous values were restored." else "A rollback is pending."
            throw IllegalStateException("Failed to save secure settings. $detail", failure)
        }
    }

    private fun readyPreferences(readKey: String? = null): SharedPreferences {
        val current = prefs
        val previous = pendingRollback
        // A failed optional-cache update must not block unrelated VPN reads.
        // New writes, and reads of affected keys, still require restoration.
        if (previous != null && (readKey == null || readKey in previous)) {
            check(commitValues(current, previous)) { "Secure settings rollback is still pending." }
            pendingRollback = null
        }
        return current
    }

    private fun commitValues(current: SharedPreferences, values: Map<String, String?>): Boolean {
        val editor = current.edit()
        values.forEach { (key, value) ->
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }
        return editor.commit()
    }

    override fun remove(vararg keys: String) {
        putStrings(keys.associateWith { null })
    }
}
