package com.g2ray.client.vpn

import android.content.Context
import android.os.SystemClock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** An explicitly provisioned owned fixture, not a public speed-test assumption. */
internal fun verifySyntheticIntegrity(context: Context, client: OkHttpClient, record: (String) -> Unit) {
    val fixture = JSONObject(File(context.filesDir, "integrity-fixture.private.json").readText())
    val base = fixture.getString("base_url").toHttpUrl()
    require(base.isHttps && base.port == 443 && base.host.endsWith(".app.github.dev") &&
        base.encodedPath == "/" && base.query == null && base.username.isEmpty() && base.password.isEmpty())
    val token = fixture.getString("token")
    require(token.matches(Regex("[a-f0-9]{64}")))
    val samples = fixture.getInt("samples").also { require(it in 1..5) }
    val chunk = ByteArray(32768) { ((it * 31 + 17) % 251).toByte() }
    fun digest(length: Int): String {
        val hash = MessageDigest.getInstance("SHA-256")
        var remaining = length
        while (remaining > 0) {
            val count = minOf(remaining, chunk.size)
            hash.update(chunk, 0, count)
            remaining -= count
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    val failed = mutableListOf<String>()
    for (sample in 0 until samples) {
        for (direction in listOf("download", "upload")) {
            val size = if (direction == "download") 8 * 1024 * 1024 else 2 * 1024 * 1024
            val expected = digest(size)
            val started = SystemClock.elapsedRealtime()
            var bytes = 0L
            val row = JSONObject().put("event", "owned_integrity").put("sample", sample).put("direction", direction)
            try {
                val request = Request.Builder().header("Authorization", "Bearer $token")
                    .header("Accept-Encoding", "identity").header("Cache-Control", "no-store")
                if (direction == "download") {
                    request.url(base.newBuilder().addPathSegment("download").addQueryParameter("bytes", size.toString()).build())
                    client.newCall(request.build()).execute().use { response ->
                        row.put("headers_ms", SystemClock.elapsedRealtime() - started).put("protocol", response.protocol.toString())
                        assertEquals(200, response.code)
                        assertEquals(expected, response.header("X-Content-SHA256"))
                        val body = requireNotNull(response.body)
                        assertEquals(size.toLong(), body.contentLength())
                        val hash = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(32768)
                        body.byteStream().use { stream ->
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= size) { "Oversized synthetic response" }
                                hash.update(buffer, 0, count)
                            }
                        }
                        assertEquals(size.toLong(), bytes)
                        assertEquals(expected, hash.digest().joinToString("") { "%02x".format(it) })
                    }
                } else {
                    val body = object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaType()
                        override fun contentLength() = size.toLong()
                        override fun isOneShot() = true
                        override fun writeTo(sink: BufferedSink) {
                            var remaining = size
                            while (remaining > 0) {
                                val count = minOf(remaining, chunk.size)
                                sink.write(chunk, 0, count)
                                bytes += count
                                remaining -= count
                            }
                        }
                    }
                    request.url(base.newBuilder().addPathSegment("upload").build())
                        .header("X-Content-SHA256", expected).post(body)
                    client.newCall(request.build()).execute().use { response ->
                        row.put("headers_ms", SystemClock.elapsedRealtime() - started).put("protocol", response.protocol.toString())
                        assertEquals(200, response.code)
                        val responseBody = requireNotNull(response.body)
                        require(responseBody.contentLength() in 1..1024) { "Unexpected integrity receipt length" }
                        val reply = JSONObject(responseBody.string())
                        assertEquals(size.toLong(), reply.getLong("bytes"))
                        assertTrue(reply.getBoolean("matched"))
                        assertEquals(expected, reply.getString("sha256"))
                        assertEquals(size.toLong(), bytes)
                    }
                }
                row.put("verified_hash", true)
            } catch (error: Exception) {
                failed += "$sample/$direction/${error.javaClass.simpleName}"
                row.put("verified_hash", false).put("error", error.javaClass.simpleName)
            } catch (error: AssertionError) {
                failed += "$sample/$direction/AssertionError"
                row.put("verified_hash", false).put("error", "AssertionError")
            } finally {
                row.put("bytes", bytes).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                record(row.toString())
            }
        }
    }
    assertTrue("Owned integrity failures: ${failed.joinToString()}", failed.isEmpty())
}
