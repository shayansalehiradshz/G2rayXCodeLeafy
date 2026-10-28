package com.g2ray.client.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class RoutePerformanceSummary(
    val success: Boolean,
    val downloadBps: Long? = null,
    val uploadBps: Long? = null,
    val error: String? = null
)

fun interface RoutePerformanceProbe {
    suspend fun probe(socksPort: Int): RoutePerformanceSummary
}

class SocksRoutePerformanceProbe(
    private val downloadBytes: Int = 384 * 1024,
    private val uploadBytes: Int = 64 * 1024,
    private val clientBuilder: () -> OkHttpClient.Builder = { OkHttpClient.Builder() }
) : RoutePerformanceProbe {
    override suspend fun probe(socksPort: Int): RoutePerformanceSummary = withContext(Dispatchers.IO) {
        require(socksPort in 1..65_535) { "SOCKS port must be valid" }
        val client = clientBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", socksPort)))
            // A redirect is not a route-quality result. Keep the probe on the
            // exact endpoint so an error page or alternate host cannot hide a
            // bad route and produce a misleading throughput sample.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
        try {
            val nonce = System.nanoTime()
            val downloadRequest = Request.Builder()
                .url("https://speed.cloudflare.com/__down?bytes=$downloadBytes&nonce=$nonce")
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-store")
                .build()
            val downloadStarted = System.nanoTime()
            val context = currentCoroutineContext()
            val downloaded = executeCancellable(client.newCall(downloadRequest)) { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download probe returned HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Download probe returned no body")
                val input = body.byteStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (total < downloadBytes) {
                    context.ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size, downloadBytes - total.toInt()))
                    if (read < 0) break
                    total += read
                }
                total
            }
            val downloadElapsed = System.nanoTime() - downloadStarted
            if (downloaded != downloadBytes.toLong()) {
                throw IllegalStateException("Download probe ended early after $downloaded bytes")
            }
            val downloadBps = bytesPerSecond(downloaded, downloadElapsed)
            currentCoroutineContext().ensureActive()

            var uploadBps: Long? = null
            var uploadError: String? = null
            try {
                val uploadPayload = ByteArray(uploadBytes) { index -> (index and 0xff).toByte() }
                val uploadRequest = Request.Builder()
                    .url("https://speed.cloudflare.com/__up?nonce=$nonce")
                    .header("Cache-Control", "no-store")
                    .post(uploadPayload.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                val uploadStarted = System.nanoTime()
                executeCancellable(client.newCall(uploadRequest)) { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Upload probe returned HTTP ${response.code}")
                    }
                }
                uploadBps = bytesPerSecond(uploadBytes.toLong(), System.nanoTime() - uploadStarted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                uploadError = error.localizedMessage ?: error.javaClass.simpleName
            }

            RoutePerformanceSummary(
                success = downloadBps > 0L,
                downloadBps = downloadBps.takeIf { it > 0L },
                uploadBps = uploadBps?.takeIf { it > 0L },
                error = uploadError?.let { "Upload sample unavailable: $it" }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            RoutePerformanceSummary(
                success = false,
                error = error.localizedMessage ?: error.javaClass.simpleName
            )
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    internal companion object {
        internal suspend fun <T> executeCancellable(call: Call, read: (Response) -> T): T = coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use(read)
            } finally {
                cancellation.cancel()
            }
        }

        fun bytesPerSecond(bytes: Long, elapsedNanos: Long): Long {
            if (bytes <= 0L || elapsedNanos <= 0L) return 0L
            return ((bytes.toDouble() * 1_000_000_000.0) / elapsedNanos.toDouble())
                .toLong()
                .coerceAtLeast(0L)
        }
    }
}
