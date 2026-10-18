package com.g2ray.client.parser

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/** Offline-only QR payload. Never follows a URL or fetches untrusted subscription content. */
internal object ConfigImportPayload {
    const val PREFIX = "g2ray://import/"
    private const val LIMIT = 1_000_000

    fun expand(input: String): String {
        require(input.length <= LIMIT) { "Config import text is too large" }
        val trimmed = input.trim()
        val expanded = if (trimmed.startsWith(PREFIX)) {
            val encoded = trimmed.removePrefix(PREFIX)
            require(encoded.length <= 16_384 && encoded.matches(Regex("[A-Za-z0-9_=-]+"))) { "Invalid all-config QR payload" }
            val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = gzip.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= LIMIT) { "Expanded config bundle is too large" }
                    out.write(buffer, 0, count)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } else input
        return expanded
    }

    fun profileText(input: String): String {
        val expanded = expand(input)
        val trimmed = expanded.trim()
        if (!trimmed.startsWith('{')) return expanded
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return expanded
        val configs = json.optJSONArray("configs") ?: return expanded
        require(json.optInt("schema_version") in 1..2 && configs.length() <= 500) { "Unsupported config bundle" }
        return (0 until configs.length()).joinToString("\n") { configs.getString(it) }
    }
}
