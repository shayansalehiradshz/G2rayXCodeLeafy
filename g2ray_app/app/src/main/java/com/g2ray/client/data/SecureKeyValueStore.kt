@file:Suppress("DEPRECATION")

package com.g2ray.client.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface SecureKeyValueStore {
    fun getString(key: String, defaultValue: String? = null): String?
    fun putString(key: String, value: String?)
    fun putStrings(values: Map<String, String?>)
    fun remove(vararg keys: String)
}

class EncryptedPrefsStore(context: Context) : SecureKeyValueStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "g2ray_secure_prefs",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun getString(key: String, defaultValue: String?): String? =
        prefs.getString(key, defaultValue)

    override fun putString(key: String, value: String?) {
        val ok = prefs.edit().putString(key, value).commit()
        check(ok) { "Failed to save secure preference: $key" }
    }

    override fun putStrings(values: Map<String, String?>) {
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }
        check(editor.commit()) { "Failed to atomically save secure preferences" }
    }

    override fun remove(vararg keys: String) {
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        check(editor.commit()) { "Failed to remove secure preferences" }
    }
}
