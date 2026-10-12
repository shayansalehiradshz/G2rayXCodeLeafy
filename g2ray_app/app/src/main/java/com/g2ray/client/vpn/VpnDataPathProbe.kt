package com.g2ray.client.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

interface VpnDataPathProbe {
    suspend fun probe(): RunningRouteResult
    suspend fun reset() = Unit
}

/**
 * Uses an ordinary, unprotected socket. Once VpnService is established this
 * request must traverse Android TUN -> packet engine -> Xray -> Codespaces.
 */
class OkHttpVpnDataPathProbe : VpnDataPathProbe {
    @Volatile
    private var cachedClient: OkHttpClient? = null

    override suspend fun probe(): RunningRouteResult {
        val startedAtMs = android.os.SystemClock.elapsedRealtime()
        val result = withTimeoutOrNull(FULL_PATH_PROBE_TIMEOUT_MS) {
            SocksRunningRouteProbe.probeUntilDecided(SocksRunningRouteProbe.HEALTH_URLS) { url ->
                probeOne(client(), url)
            }
        } ?: RunningRouteResult(
            success = false,
            error = "Full VPN data-path probe timed out",
            failureScope = RouteFailureScope.PacketEngine
        )
        return result.copy(
            error = result.error?.let { "Full VPN data path: $it" },
            latencyMs = (android.os.SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L),
            failureScope = if (result.success) RouteFailureScope.None else RouteFailureScope.PacketEngine
        )
    }

    override suspend fun reset() {
        val retired = synchronized(this) {
            cachedClient.also { cachedClient = null }
        } ?: return
        // TLS close can send close_notify. Never do socket cleanup on the UI
        // thread, or abandon a detached client when recovery is cancelled.
        withContext(NonCancellable + Dispatchers.IO) {
            closeClient(retired)
        }
    }

    private fun client(): OkHttpClient {
        cachedClient?.let { return it }
        return synchronized(this) {
            cachedClient ?: OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
                .also { cachedClient = it }
        }
    }

    private suspend fun probeOne(client: OkHttpClient, url: String): SocksRunningRouteProbe.RouteProbeOutcome {
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build()
            )
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(
                        SocksRunningRouteProbe.RouteProbeOutcome(
                            error = "$url: ${e.localizedMessage ?: e.javaClass.simpleName}"
                        ),
                        onCancellation = { _, _, _ -> }
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val code = response.use { it.code }
                    continuation.resume(
                        if (code in 200..299) {
                            SocksRunningRouteProbe.RouteProbeOutcome(success = true)
                        } else {
                            SocksRunningRouteProbe.RouteProbeOutcome(error = "$url: HTTP $code")
                        },
                        onCancellation = { _, _, _ -> }
                    )
                }
            })
        }
    }

    private fun closeClient(client: OkHttpClient) {
        try {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    private companion object {
        const val FULL_PATH_PROBE_TIMEOUT_MS = 4_500L
    }
}
