package com.g2ray.client.vpn

import android.os.SystemClock
import android.util.Log
import com.g2ray.client.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
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
class OkHttpVpnDataPathProbe(
    private val defaultNetworkIsVpn: () -> Boolean? = { null }
) : VpnDataPathProbe {
    @Volatile
    private var cachedClient: OkHttpClient? = null

    override suspend fun probe(): RunningRouteResult {
        val startedAtMs = SystemClock.elapsedRealtime()
        val trace = if (BuildConfig.DEBUG) VpnProbeTimingTrace(SystemClock::elapsedRealtime) else null
        val defaultVpnAtStart = runCatching { defaultNetworkIsVpn() }.getOrNull()
        var selectedVpn = defaultVpnAtStart
        val result = withTimeoutOrNull(FULL_PATH_PROBE_TIMEOUT_MS) {
            // Establishing TUN and selecting it as this app's default route
            // are asynchronous. A direct physical-network reply is not proof
            // of VPN health. Include selection wait in the existing deadline.
            while (selectedVpn != true) {
                delay(VPN_SELECTION_CHECK_INTERVAL_MS)
                selectedVpn = runCatching { defaultNetworkIsVpn() }.getOrNull()
            }
            SocksRunningRouteProbe.probeUntilDecided(SocksRunningRouteProbe.HEALTH_URLS) { url ->
                probeOne(client(), url, trace)
            }
        } ?: RunningRouteResult(
            success = false,
            error = "Full VPN data-path probe timed out",
            failureScope = RouteFailureScope.PacketEngine
        )
        val finishedAtMs = SystemClock.elapsedRealtime()
        val defaultVpnAtEnd = runCatching { defaultNetworkIsVpn() }.getOrNull()
        val checkedResult = if (result.success && defaultVpnAtEnd != true) {
            result.copy(success = false, error = "Android stopped using VPN default routing during health verification")
        } else result
        if (trace != null) {
            trace.summaries().forEach { timing ->
                Log.i("G2RayVpnProbe", "default_vpn_start=${defaultVpnAtStart ?: "unknown"} " +
                    "default_vpn_end=${defaultVpnAtEnd ?: "unknown"} $timing")
            }
        }
        return checkedResult.copy(
            error = checkedResult.error?.let { "Full VPN data path: $it" },
            latencyMs = (finishedAtMs - startedAtMs).coerceAtLeast(0L),
            failureScope = if (checkedResult.success) RouteFailureScope.None else RouteFailureScope.PacketEngine
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
                .apply {
                    if (BuildConfig.DEBUG) eventListenerFactory { call ->
                        call.request().tag(VpnProbeTimingTrace::class.java)
                            ?.listenerFor(call.request().url.host) ?: EventListener.NONE
                    }
                }
                .build()
                .also { cachedClient = it }
        }
    }

    private suspend fun probeOne(
        client: OkHttpClient,
        url: String,
        trace: VpnProbeTimingTrace?
    ): SocksRunningRouteProbe.RouteProbeOutcome {
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(
                Request.Builder()
                    .url(url)
                    .tag(VpnProbeTimingTrace::class.java, trace)
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
                    continuation.resume(
                        evaluateRouteProbeResponse(url, response),
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
        const val VPN_SELECTION_CHECK_INTERVAL_MS = 50L
    }
}
