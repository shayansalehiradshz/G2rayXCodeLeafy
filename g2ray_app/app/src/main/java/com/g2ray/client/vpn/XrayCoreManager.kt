package com.g2ray.client.vpn

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.g2ray.client.BuildConfig
import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.G2RayProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

data class RunningRouteResult(
    val success: Boolean,
    val error: String? = null,
    val latencyMs: Long? = null,
    val failureScope: RouteFailureScope = if (success) RouteFailureScope.None else RouteFailureScope.Unknown,
    val retryAllowed: Boolean = true,
    val retryAfterMs: Long? = null,
    // Set by VpnService after a successful full-path check. Publication must
    // still match this generation after waiting for the main dispatcher.
    val verifiedNetworkGeneration: Long? = null
)

enum class RouteFailureScope {
    None,
    Configuration,
    PacketEngine,
    XrayRoute,
    EndpointResolution,
    DialerBinding,
    Unknown
}

fun interface RunningRouteProbe {
    suspend fun probe(socksPort: Int): RunningRouteResult

    fun reset() = Unit
}

/** Confirms that Xray accepted the loopback SOCKS listener before VPN traffic uses it. */
fun interface LocalSocksListenerProbe {
    suspend fun awaitReady(socksPort: Int): Boolean
}

class TcpLocalSocksListenerProbe(
    private val timeoutMs: Long = 4_000L,
    private val retryDelayMs: Long = 50L,
    private val connectTimeoutMs: Int = 200
) : LocalSocksListenerProbe {
    override suspend fun awaitReady(socksPort: Int): Boolean {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (true) {
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress("127.0.0.1", socksPort),
                        connectTimeoutMs.coerceIn(50, 1_000)
                    )
                }
                return true
            } catch (_: IOException) {
                // Xray creates this listener asynchronously; retry only within the bounded startup window.
            }

            val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) return false
            delay(minOf(retryDelayMs.coerceAtLeast(10L), remainingMs))
        }
    }
}

class SocksRunningRouteProbe : RunningRouteProbe {
    override suspend fun probe(socksPort: Int): RunningRouteResult {
        val client = clientFor(socksPort)
        return withTimeoutOrNull(ROUTE_PROBE_TIMEOUT_MS) {
            probeUntilDecided(HEALTH_URLS) { url -> probeOne(client, url) }
        } ?: RunningRouteResult(success = false, error = "Tunnel health probe timed out")
    }

    private suspend fun probeOne(client: OkHttpClient, url: String): RouteProbeOutcome {
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
                    val outcome = RouteProbeOutcome(
                        error = "$url: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    )
                    continuation.resume(outcome, onCancellation = { _, _, _ -> })
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = evaluateRouteProbeResponse(url, response)
                    continuation.resume(outcome, onCancellation = { _, _, _ -> })
                }
            })
        }
    }

    private fun clientFor(socksPort: Int): OkHttpClient {
        val existing = cachedClient
        if (cachedSocksPort == socksPort && existing != null) {
            return existing
        }

        synchronized(this) {
            val current = cachedClient
            if (cachedSocksPort == socksPort && current != null) {
                return current
            }
            current?.let(::closeClient)
            return OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", socksPort)))
                // A stale pooled socket should fail this bounded health probe
                // once, then let the quorum/recovery loop decide. OkHttp's
                // transparent retry can otherwise hide a mobile-NAT reset.
                .retryOnConnectionFailure(false)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS)
                .build()
                .also {
                    cachedClient = it
                    cachedSocksPort = socksPort
                }
        }
    }

    override fun reset() {
        synchronized(this) {
            cachedClient?.let(::closeClient)
            cachedClient = null
            cachedSocksPort = -1
        }
    }

    private fun closeClient(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    internal companion object {
        const val MIN_SUCCESSFUL_PROBES = 2
        const val ROUTE_PROBE_TIMEOUT_MS = 4_500L

        fun hasEnoughHealthyProbes(successfulProbes: Int, totalProbes: Int): Boolean {
            return successfulProbes >= minOf(MIN_SUCCESSFUL_PROBES, totalProbes)
        }

        internal suspend fun probeUntilDecided(
            urls: List<String>,
            probe: suspend (String) -> RouteProbeOutcome
        ): RunningRouteResult = coroutineScope {
            if (urls.isEmpty()) {
                return@coroutineScope RunningRouteResult(
                    success = false,
                    error = "No tunnel health endpoints are configured"
                )
            }

            val requiredSuccesses = minOf(MIN_SUCCESSFUL_PROBES, urls.size)
            val outcomes = Channel<RouteProbeOutcome>(capacity = urls.size)
            val jobs = urls.map { url ->
                launch {
                    val outcome = try {
                        probe(url)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        RouteProbeOutcome(
                            error = "$url: ${e.localizedMessage ?: e.javaClass.simpleName}"
                        )
                    }
                    outcomes.send(outcome)
                }
            }

            var completedProbes = 0
            var successfulProbes = 0
            val errors = mutableListOf<String>()
            try {
                while (completedProbes < urls.size) {
                    val outcome = outcomes.receive()
                    completedProbes++
                    if (outcome.success) {
                        successfulProbes++
                    } else {
                        outcome.error?.let(errors::add)
                    }

                    if (successfulProbes >= requiredSuccesses) {
                        return@coroutineScope RunningRouteResult(success = true)
                    }

                    val remainingProbes = urls.size - completedProbes
                    if (successfulProbes + remainingProbes < requiredSuccesses) {
                        return@coroutineScope failedProbeResult(
                            successfulProbes = successfulProbes,
                            totalProbes = urls.size,
                            errors = errors
                        )
                    }
                }

                failedProbeResult(
                    successfulProbes = successfulProbes,
                    totalProbes = urls.size,
                    errors = errors
                )
            } finally {
                jobs.forEach { it.cancel() }
                jobs.joinAll()
                outcomes.cancel()
            }
        }

        private fun failedProbeResult(
            successfulProbes: Int,
            totalProbes: Int,
            errors: List<String>
        ): RunningRouteResult {
            return RunningRouteResult(
                success = false,
                error = buildString {
                    append("Only $successfulProbes/$totalProbes independent tunnel health probes succeeded")
                    if (errors.isNotEmpty()) {
                        append(": ")
                        append(errors.joinToString("; "))
                    }
                }
            )
        }

        val HEALTH_URLS = listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://www.gstatic.com/generate_204",
            "https://www.apple.com/library/test/success.html"
        )
    }

    @Volatile
    private var cachedSocksPort: Int = -1

    @Volatile
    private var cachedClient: OkHttpClient? = null

    internal data class RouteProbeOutcome(
        val success: Boolean = false,
        val error: String? = null
    )
}

class XrayCoreManager : TunnelCore {
    companion object {
        const val MISSING_CORE_MESSAGE =
            "VPN core is not bundled. Place the official libXray.aar in app/libs/ and rebuild the app."

        val DEFAULT_PREFLIGHT_URLS = listOf(
            "https://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://www.gstatic.com/generate_204"
        )

        internal fun summarizeDelayValues(
            values: List<Long>,
            totalSamples: Int = values.size,
            error: String? = null
        ): XrayDelaySummary {
            val sorted = values.filter { it in 1..9999 }.sorted()
            if (sorted.isEmpty()) {
                return XrayDelaySummary(false, null, null, null, 0, totalSamples, error ?: "No valid delay samples")
            }
            val median = sorted[sorted.lastIndex / 2]
            val p95Index = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)
            val p95 = sorted[p95Index]
            val jitter = sorted.map { kotlin.math.abs(it - median) }.average().toLong()
            return XrayDelaySummary(true, median, p95, jitter, sorted.size, totalSamples, error)
        }

        private const val START_PORT_ATTEMPTS = 3
        private const val DELAY_PORT_ATTEMPTS = 2
        private const val STATE_QUERY_FAILURES_BEFORE_ERROR = 2
    }

    constructor() : this(ReflectionXrayBridge(), SocksRunningRouteProbe())

    constructor(bridge: XrayBridge) : this(bridge, SocksRunningRouteProbe())

    constructor(
        bridge: XrayBridge,
        runningRouteProbe: RunningRouteProbe,
        portAllocator: () -> Int = ::findFreeLocalPort,
        localSocksListenerProbe: LocalSocksListenerProbe = TcpLocalSocksListenerProbe(),
        stopWaitTimeoutMs: Long = 2_000L,
        stopPollIntervalMs: Long = 50L,
        routePerformanceProbe: RoutePerformanceProbe = SocksRoutePerformanceProbe()
    ) {
        this.bridge = bridge
        this.runningRouteProbe = runningRouteProbe
        this.portAllocator = portAllocator
        this.localSocksListenerProbe = localSocksListenerProbe
        this.stopWaitTimeoutMs = stopWaitTimeoutMs
        this.stopPollIntervalMs = stopPollIntervalMs
        this.routePerformanceProbe = routePerformanceProbe
    }

    private val bridge: XrayBridge
    private val runningRouteProbe: RunningRouteProbe
    private val portAllocator: () -> Int
    private val localSocksListenerProbe: LocalSocksListenerProbe
    private val stopWaitTimeoutMs: Long
    private val stopPollIntervalMs: Long
    private val routePerformanceProbe: RoutePerformanceProbe
    @Volatile
    private var healthSocksPort: Int? = null
    private val _state = MutableStateFlow(TunnelCoreState.Disconnected)
    override val state: StateFlow<TunnelCoreState> = _state.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private var startupRetryAllowed = true
    private var consecutiveStateQueryFailures = 0
    private val nativeRuntime by lazy {
        if (bridge is ReflectionXrayBridge) NativeRuntimeCoordinator.process else NativeRuntimeCoordinator()
    }
    private var nativeLease: NativeRuntimeCoordinator.Lease? = null

    fun isAvailable(): Boolean = bridge.isAvailable()

    fun capabilities(): XrayRuntimeCapabilities = bridge.capabilities()

    fun canMeasureDelay(): Boolean {
        val capabilities = bridge.capabilities()
        return capabilities.classFound && capabilities.pingFound
    }

    fun measureDelay(
        profile: G2RayProfile,
        datDir: String,
        timeoutSeconds: Int = 8,
        testUrl: String = "https://www.google.com/generate_204",
        dpiCompatibilityEnabled: Boolean = false,
        checkActive: () -> Unit = {}
    ): XrayPingResult {
        checkActive()
        val lease = nativeRuntime.tryAcquireProbe()
            ?: return XrayPingResult(false, null, "VPN owns the native runtime; delay test deferred")
        return lease.use { measureDelayOwned(profile, datDir, timeoutSeconds, testUrl, dpiCompatibilityEnabled, checkActive) }
    }

    private fun measureDelayOwned(
        profile: G2RayProfile,
        datDir: String,
        timeoutSeconds: Int = 8,
        testUrl: String = "https://www.google.com/generate_204",
        dpiCompatibilityEnabled: Boolean = false,
        checkActive: () -> Unit = {}
    ): XrayPingResult {
        if (!canMeasureDelay()) {
            return XrayPingResult(success = false, delayMs = null, error = "libXray ping is not available in this build.")
        }

        var lastResult = XrayPingResult(
            success = false,
            delayMs = null,
            error = "Delay test could not reserve a local port"
        )
        for (attempt in 0 until DELAY_PORT_ATTEMPTS) {
            ensureOptionalProbeActive(checkActive)
            var configFile: File? = null
            val result = try {
                val localPort = portAllocator()
                configFile = File.createTempFile("g2ray-delay-", ".json", File(datDir).also { it.mkdirs() })
                configFile.writeText(
                    XrayConfigBuilder.buildDelayTestConfig(
                        profile = profile,
                        socksPort = localPort,
                        dpiCompatibilityEnabled = dpiCompatibilityEnabled
                    )
                )
                val requestJson = JSONObject().apply {
                    put("datDir", datDir)
                    put("configPath", configFile.absolutePath)
                    put("timeout", timeoutSeconds.coerceIn(2, 30))
                    put("url", testUrl)
                    put("proxy", "socks5://127.0.0.1:$localPort")
                }.toString()
                val request = Base64.encodeToString(requestJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                bridge.ping(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                XrayPingResult(success = false, delayMs = null, error = e.localizedMessage ?: e.javaClass.simpleName)
            } finally {
                configFile?.let { file -> runCatching { file.delete() } }
            }
            ensureOptionalProbeActive(checkActive)
            lastResult = result
            if (result.success || !isAddressInUse(result.error) || attempt + 1 >= DELAY_PORT_ATTEMPTS) {
                return result
            }
        }
        return lastResult
    }

    fun measureDelayAny(
        profile: G2RayProfile,
        datDir: String,
        timeoutSeconds: Int = 5,
        testUrls: List<String> = DEFAULT_PREFLIGHT_URLS,
        dpiCompatibilityEnabled: Boolean = false,
        checkActive: () -> Unit = {}
    ): XrayPingResult {
        if (!canMeasureDelay()) {
            return XrayPingResult(success = false, delayMs = null, error = "libXray ping is not available in this build.")
        }

        val errors = mutableListOf<String>()
        val urls = testUrls.filter { it.isNotBlank() }.ifEmpty { DEFAULT_PREFLIGHT_URLS }.take(2)
        for ((index, url) in urls.withIndex()) {
            val result = measureDelay(
                profile = profile,
                datDir = datDir,
                timeoutSeconds = if (index == 0) timeoutSeconds.coerceIn(2, 4) else timeoutSeconds.coerceIn(2, 3),
                testUrl = url,
                dpiCompatibilityEnabled = dpiCompatibilityEnabled,
                checkActive = checkActive
            )
            if (result.success) {
                return result
            }
            errors += "${url}: ${result.error ?: "no response"}"
        }

        return XrayPingResult(
            success = false,
            delayMs = null,
            error = errors.joinToString("; ").ifBlank { "No preflight endpoint responded" }
        )
    }

    fun measureDelaySummary(
        profile: G2RayProfile,
        datDir: String,
        timeoutSeconds: Int = 4,
        testUrls: List<String> = DEFAULT_PREFLIGHT_URLS,
        sampleCount: Int = 3,
        dpiCompatibilityEnabled: Boolean = false,
        checkActive: () -> Unit = {}
    ): XrayDelaySummary {
        if (!canMeasureDelay()) {
            return XrayDelaySummary(false, null, null, null, 0, 0, "libXray ping is not available in this build.")
        }

        val urls = testUrls.filter { it.isNotBlank() }.ifEmpty { DEFAULT_PREFLIGHT_URLS }
        val requestedSamples = sampleCount.coerceIn(3, 5)
        val delays = mutableListOf<Long>()
        val errors = mutableListOf<String>()
        repeat(requestedSamples) { index ->
            checkActive()
            val url = urls[index % urls.size]
            val result = measureDelay(
                profile = profile,
                datDir = datDir,
                timeoutSeconds = timeoutSeconds.coerceIn(2, 8),
                testUrl = url,
                dpiCompatibilityEnabled = dpiCompatibilityEnabled,
                checkActive = checkActive
            )
            checkActive()
            val delay = result.delayMs?.takeIf { result.success && it in 1..9999 }
            if (delay != null) {
                delays += delay
            } else {
                errors += "$url: ${result.error ?: "no response"}"
            }
        }

        if (delays.isEmpty()) {
            return XrayDelaySummary(
                success = false,
                medianMs = null,
                p95Ms = null,
                jitterMs = null,
                successfulSamples = 0,
                totalSamples = requestedSamples,
                error = errors.joinToString("; ").ifBlank { "No preflight endpoint responded" }
            )
        }

        return summarizeDelayValues(delays, requestedSamples, errors.joinToString("; ").takeIf { it.isNotBlank() })
    }

    fun measureDelayQuick(
        profile: G2RayProfile,
        datDir: String,
        timeoutSeconds: Int = 3,
        testUrl: String = DEFAULT_PREFLIGHT_URLS.first(),
        dpiCompatibilityEnabled: Boolean = false,
        checkActive: () -> Unit = {}
    ): XrayDelaySummary {
        val result = measureDelay(
            profile = profile,
            datDir = datDir,
            timeoutSeconds = timeoutSeconds.coerceIn(2, 4),
            testUrl = testUrl,
            dpiCompatibilityEnabled = dpiCompatibilityEnabled,
            checkActive = checkActive
        )
        val delay = result.delayMs?.takeIf { result.success && it in 1..9999 }
        return if (delay != null) {
            summarizeDelayValues(listOf(delay), totalSamples = 1)
        } else {
            XrayDelaySummary(
                success = false,
                medianMs = null,
                p95Ms = null,
                jitterMs = null,
                successfulSamples = 0,
                totalSamples = 1,
                error = result.error ?: "No preflight endpoint responded"
            )
        }
    }

    /** Three independent response samples per finalist, batched without overlapping native runtimes. */
    fun measureDelaySummaryBatch(
        profiles: List<G2RayProfile>,
        datDir: String,
        timeoutSeconds: Int = 3,
        testUrls: List<String> = SocksRunningRouteProbe.HEALTH_URLS,
        dpiCompatibilityEnabled: Boolean = false,
        checkActive: () -> Unit = {}
    ): List<XrayDelaySummary> {
        require(profiles.size <= 5) { "At most five profiles can be confirmed together" }
        if (profiles.isEmpty()) return emptyList()
        val urls = testUrls.filter { it.isNotBlank() }.distinct().ifEmpty { SocksRunningRouteProbe.HEALTH_URLS }
        val delays = profiles.map { mutableListOf<Long>() }
        val errors = profiles.map { mutableListOf<String>() }
        var batchSupported = true
        repeat(3) { round ->
            ensureOptionalProbeActive(checkActive)
            val url = urls[round % urls.size]
            val batch = if (batchSupported) measureDelayQuickBatch(
                profiles, datDir, timeoutSeconds, url,
                retryTestUrl = null, finalTestUrl = null,
                dpiCompatibilityEnabled = dpiCompatibilityEnabled,
                checkActive = checkActive
            ) else null
            ensureOptionalProbeActive(checkActive)
            if (batch == null) batchSupported = false
            val results = batch ?: profiles.map { profile ->
                ensureOptionalProbeActive(checkActive)
                measureDelayQuick(
                    profile, datDir, timeoutSeconds, url, dpiCompatibilityEnabled, checkActive
                )
            }
            ensureOptionalProbeActive(checkActive)
            results.forEachIndexed { index, result ->
                val delay = result.medianMs?.takeIf { result.success && it in 1..9999 }
                if (delay != null) delays[index] += delay
                else errors[index] += "$url: ${result.error ?: "no response"}"
            }
        }
        return profiles.indices.map { index ->
            summarizeDelayValues(delays[index], 3, errors[index].joinToString("; ").takeIf { it.isNotBlank() })
        }
    }

    /**
     * Measures up to five profiles in one modern libXray temporary instance.
     *
     * libXray documents pingBatch as the safe way to run these probes
     * concurrently. The nullable result deliberately means "fall back": old
     * AARs, unsupported methods, malformed responses, and transport errors all
     * return null so callers can keep the established one-profile ping path.
     * This must only be called while the live VPN core is stopped; the caller
     * already enforces that lifecycle boundary because libXray shares process
     * global outbound state between instances.
     */
    fun measureDelayQuickBatch(
        profiles: List<G2RayProfile>,
        datDir: String,
        timeoutSeconds: Int = 3,
        testUrl: String = DEFAULT_PREFLIGHT_URLS.first(),
        retryTestUrl: String? = DEFAULT_PREFLIGHT_URLS.getOrNull(1),
        finalTestUrl: String? = DEFAULT_PREFLIGHT_URLS.getOrNull(2),
        dpiCompatibilityEnabled: Boolean = false,
        checkActive: () -> Unit = {}
    ): List<XrayDelaySummary>? {
        checkActive()
        val lease = nativeRuntime.tryAcquireProbe() ?: return profiles.map {
            XrayDelaySummary(false, null, null, null, 0, 0, "VPN owns the native runtime; batch test deferred")
        }
        return lease.use {
            measureDelayQuickBatchOwned(profiles, datDir, timeoutSeconds, testUrl, retryTestUrl, finalTestUrl, dpiCompatibilityEnabled, checkActive)
        }
    }

    private fun measureDelayQuickBatchOwned(
        profiles: List<G2RayProfile>,
        datDir: String,
        timeoutSeconds: Int = 3,
        testUrl: String = DEFAULT_PREFLIGHT_URLS.first(),
        retryTestUrl: String? = DEFAULT_PREFLIGHT_URLS.getOrNull(1),
        finalTestUrl: String? = DEFAULT_PREFLIGHT_URLS.getOrNull(2),
        dpiCompatibilityEnabled: Boolean = false,
        checkActive: () -> Unit = {}
    ): List<XrayDelaySummary>? {
        fun checkProbeActive() = ensureOptionalProbeActive(checkActive)
        checkProbeActive()
        if (profiles.isEmpty()) return emptyList()
        if (profiles.size > 5) return null
        val capabilities = bridge.capabilities()
        if (!capabilities.classFound || !capabilities.invokeFound) return null

        val configFiles = mutableListOf<File>()
        return try {
            val configs = profiles.mapIndexed { index, profile ->
                val configFile = File.createTempFile(
                    "g2ray-delay-batch-",
                    ".json",
                    File(datDir).also { it.mkdirs() }
                )
                configFiles += configFile
                configFile.writeText(
                    XrayConfigBuilder.buildDelayTestConfig(
                        profile = profile,
                        // pingBatch reads only outbounds; keep this a valid
                        // local SOCKS port without reserving a real listener.
                        socksPort = 19_090 + index,
                        dpiCompatibilityEnabled = dpiCompatibilityEnabled
                    )
                )
                XrayPingBatchConfig(
                    configPath = configFile.absolutePath,
                    outboundTag = "proxy"
                )
            }

            checkProbeActive()
            val batch = bridge.pingBatch(
                configs = configs,
                timeoutSeconds = timeoutSeconds.coerceIn(2, 4),
                testUrl = testUrl
            )
            checkProbeActive()
            if (!batch.success || batch.results.size != profiles.size) {
                return null
            }

            val mergedResults = batch.results.toMutableList()
            val sampleCounts = IntArray(profiles.size) { 1 }
            val attemptedUrls = linkedSetOf(testUrl.lowercase())

            fun resultIsUsable(result: XrayPingResult): Boolean {
                return result.success && result.delayMs?.let { it in 1..9999 } == true
            }

            fun retryFailedProfiles(candidateUrl: String?) {
                val retryUrl = candidateUrl
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && attemptedUrls.add(it.lowercase()) }
                    ?: return
                val retryIndexes = mergedResults.indices.filter { index ->
                    !resultIsUsable(mergedResults[index])
                }
                if (retryIndexes.isEmpty()) return
                checkProbeActive()
                val retryBatch = bridge.pingBatch(
                    configs = retryIndexes.map(configs::get),
                    timeoutSeconds = timeoutSeconds.coerceIn(2, 4),
                    testUrl = retryUrl
                )
                checkProbeActive()
                if (retryBatch.success && retryBatch.results.size == retryIndexes.size) {
                    retryIndexes.forEachIndexed { retryIndex, originalIndex ->
                        val retryResult = retryBatch.results[retryIndex]
                        val previousError = mergedResults[originalIndex].error?.takeIf { it.isNotBlank() }
                        sampleCounts[originalIndex] += 1
                        mergedResults[originalIndex] = if (resultIsUsable(retryResult)) {
                            retryResult.copy(
                                error = previousError
                            )
                        } else {
                            retryResult.copy(
                                error = listOfNotNull(
                                    previousError,
                                    retryResult.error?.takeIf { it.isNotBlank() }
                                ).distinct().joinToString("; ").ifBlank {
                                    "No preflight endpoint responded"
                                }
                            )
                        }
                    }
                }
            }

            retryFailedProfiles(retryTestUrl)
            retryFailedProfiles(finalTestUrl)

            mergedResults.mapIndexed { index, result ->
                val delay = result.delayMs?.takeIf { result.success && it in 1..9999 }
                if (delay != null) {
                    summarizeDelayValues(
                        values = listOf(delay),
                        totalSamples = sampleCounts[index],
                        error = result.error
                    )
                } else {
                    XrayDelaySummary(
                        success = false,
                        medianMs = null,
                        p95Ms = null,
                        jitterMs = null,
                        successfulSamples = 0,
                        totalSamples = sampleCounts[index],
                        error = result.error ?: "No preflight endpoint responded"
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            configFiles.forEach { file -> runCatching { file.delete() } }
        }
    }

    private fun ensureOptionalProbeActive(checkActive: () -> Unit) {
        checkActive()
        if (nativeRuntime.hasWaitingVpnStart()) {
            throw CancellationException("VPN startup superseded optional delay probing")
        }
    }

    suspend fun measureRoutePerformance(
        profile: G2RayProfile,
        datDir: String,
        dpiCompatibilityEnabled: Boolean = false
    ): RoutePerformanceSummary {
        if (nativeLease != null) {
            return RoutePerformanceSummary(false, error = "Native runtime is already owned by this manager")
        }
        val lease = nativeRuntime.tryAcquireProbe()
            ?: return RoutePerformanceSummary(false, error = "VPN owns the native runtime; throughput test deferred")
        nativeLease = lease
        try {
            return measureRoutePerformanceOwned(profile, datDir, dpiCompatibilityEnabled)
        } finally {
            releaseStoppedNativeLease()
            if (nativeLease != null) withContext(kotlinx.coroutines.NonCancellable) { stop() }
        }
    }

    private suspend fun measureRoutePerformanceOwned(
        profile: G2RayProfile,
        datDir: String,
        dpiCompatibilityEnabled: Boolean = false
    ): RoutePerformanceSummary {
        if (!bridge.isAvailable()) {
            return RoutePerformanceSummary(false, error = MISSING_CORE_MESSAGE)
        }

        var lastResult = RoutePerformanceSummary(false, error = "Performance test could not reserve a local port")
        repeat(DELAY_PORT_ATTEMPTS) { attempt ->
            val localPort = portAllocator()
            var runAttempted = false
            var runtimeStopped = true
            val result = try {
                require(localPort in 1..65_535) { "Could not allocate a local performance-test port" }
                bridge.registerDialerController { true }
                bridge.registerListenerController { true }
                bridge.setTunFd(0)
                val configJson = XrayConfigBuilder.buildDelayTestConfig(
                    profile = profile,
                    socksPort = localPort,
                    dpiCompatibilityEnabled = dpiCompatibilityEnabled
                )
                val request = bridge.newRunFromJsonRequest(datDir, configJson)
                runAttempted = true
                val started = bridge.runFromJson(request)
                if (!started.success) {
                    RoutePerformanceSummary(false, error = started.error ?: "Xray performance-test route did not start")
                } else if (!localSocksListenerProbe.awaitReady(localPort)) {
                    RoutePerformanceSummary(false, error = "Performance-test SOCKS listener did not become ready")
                } else {
                    routePerformanceProbe.probe(localPort)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                RoutePerformanceSummary(false, error = error.localizedMessage ?: error.javaClass.simpleName)
            } finally {
                if (runAttempted) {
                    runCatching { bridge.stop() }
                    runtimeStopped = runCatching { !bridge.getState() }.getOrDefault(false)
                }
            }
            if (!runtimeStopped) {
                return RoutePerformanceSummary(false, error = "Performance-test runtime has not stopped; refusing to replace it")
            }
            lastResult = result
            if (result.success || !isAddressInUse(result.error) || attempt + 1 >= DELAY_PORT_ATTEMPTS) {
                return result
            }
        }
        return lastResult
    }

    override suspend fun start(profile: G2RayProfile, tunFd: Int, datDir: String, protectSocket: (Int) -> Boolean) {
        start(
            profile = profile,
            tunFd = tunFd,
            datDir = datDir,
            protectSocket = protectSocket,
            engine = ActiveVpnEngine.XrayNative
        )
    }

    suspend fun start(
        profile: G2RayProfile,
        tunFd: Int,
        datDir: String,
        protectSocket: (Int) -> Boolean,
        protectListenerSocket: (Int) -> Boolean = protectSocket,
        engine: ActiveVpnEngine,
        fakeDnsEnabled: Boolean = false,
        ipv4Only: Boolean = false,
        dpiCompatibilityEnabled: Boolean = false,
        fallbackProfiles: List<G2RayProfile> = emptyList(),
        routingPolicy: RoutingPolicy = RoutingPolicy()
    ) {
        startupRetryAllowed = true
        if (!bridge.isAvailable()) {
            startupRetryAllowed = false
            _lastError.value = MISSING_CORE_MESSAGE
            _state.value = TunnelCoreState.Error
            return
        }

        if (nativeLease != null) {
            stop()
            if (nativeLease != null) {
                _lastError.value = "Previous native runtime has not stopped; refusing to replace it"
                _state.value = TunnelCoreState.Error
                return
            }
        }
        val leaseStartedAt = SystemClock.elapsedRealtime()
        nativeLease = withTimeoutOrNull(20_000L) { nativeRuntime.acquireVpn() }
        logCoreStartupPhase("core_lease", engine, leaseStartedAt, nativeLease != null)
        if (nativeLease == null) {
            _lastError.value = "Native runtime is still busy; retrying must wait for its owner to stop"
            _state.value = TunnelCoreState.Error
            return
        }

        _lastError.value = null
        _state.value = TunnelCoreState.Starting
        healthSocksPort = null
        try {
            val setupStartedAt = SystemClock.elapsedRealtime()
            runningRouteProbe.reset()
            bridge.registerDialerController(protectSocket)
            bridge.registerListenerController(protectListenerSocket)
            // HEV owns the Android TUN FD and forwards it to this loopback
            // SOCKS inbound. Xray receives the FD only for native TUN mode.
            bridge.setTunFd(if (engine == ActiveVpnEngine.XrayNative) tunFd else 0)
            logCoreStartupPhase("core_setup", engine, setupStartedAt, true)

            var lastStartupError = "Xray failed to start"
            for (attempt in 0 until START_PORT_ATTEMPTS) {
                val allocatedHealthSocksPort = portAllocator()
                require(allocatedHealthSocksPort in 1..65535) { "Could not allocate a local tunnel health port" }
                val configJson = XrayConfigBuilder.buildVpnConfig(
                    profile = profile,
                    healthSocksPort = allocatedHealthSocksPort,
                    engine = engine,
                    fakeDnsEnabled = fakeDnsEnabled,
                    ipv4Only = ipv4Only,
                    dpiCompatibilityEnabled = dpiCompatibilityEnabled,
                    tunFd = tunFd.takeIf { engine == ActiveVpnEngine.XrayNative },
                    fallbackProfiles = fallbackProfiles,
                    routingPolicy = routingPolicy
                )
                val request = bridge.newRunFromJsonRequest(datDir, configJson)
                val nativeStartedAt = SystemClock.elapsedRealtime()
                val result = bridge.runFromJson(request)
                logCoreStartupPhase("core_native_start", engine, nativeStartedAt, result.success)
                val listenerStartedAt = SystemClock.elapsedRealtime()
                val listenerReady = result.success && localSocksListenerProbe.awaitReady(allocatedHealthSocksPort)
                logCoreStartupPhase("core_listener", engine, listenerStartedAt, listenerReady)
                if (listenerReady) {
                    healthSocksPort = allocatedHealthSocksPort
                    consecutiveStateQueryFailures = 0
                    _state.value = TunnelCoreState.Connected
                    return
                }

                lastStartupError = result.error ?: "Local SOCKS listener did not become ready after starting Xray"
                // Xray's config decoder/builder rejects these before any route
                // is dialed. Retrying ports or waking a server cannot fix them.
                startupRetryAllowed = !lastStartupError.contains("infra/conf", ignoreCase = true)
                runCatching { bridge.stop() }
                healthSocksPort = null
                if (runCatching { bridge.getState() }.getOrDefault(true)) {
                    _lastError.value = "Xray did not stop after failed startup; refusing to replace its runtime"
                    _state.value = TunnelCoreState.Error
                    return
                }
                if (attempt + 1 >= START_PORT_ATTEMPTS || (!result.success && !isAddressInUse(lastStartupError))) {
                    _lastError.value = lastStartupError
                    _state.value = TunnelCoreState.Error
                    return
                }
                delay(100L * (attempt + 1))
            }
        } catch (e: CancellationException) {
            runCatching { bridge.stop() }
            healthSocksPort = null
            consecutiveStateQueryFailures = 0
            _lastError.value = null
            _state.value = TunnelCoreState.Disconnected
            throw e
        } catch (e: Exception) {
            runCatching { bridge.stop() }
            healthSocksPort = null
            startupRetryAllowed = e !is org.json.JSONException &&
                e !is IllegalArgumentException && e !is NoSuchMethodException
            _lastError.value = e.localizedMessage ?: e.javaClass.simpleName
            _state.value = TunnelCoreState.Error
        } finally {
            if (_state.value != TunnelCoreState.Connected) releaseStoppedNativeLease()
        }
    }

    fun activeSocksPort(): Int? = healthSocksPort

    private fun logCoreStartupPhase(phase: String, engine: ActiveVpnEngine, startedAt: Long, success: Boolean) {
        if (BuildConfig.DEBUG) {
            Log.i("G2RayVpnService", "startup_phase=$phase engine=$engine " +
                "elapsed_ms=${(SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)} success=$success")
        }
    }

    internal fun startupFailureResult(): RunningRouteResult = RunningRouteResult(
        success = false,
        error = _lastError.value ?: "Xray failed to start",
        failureScope = if (startupRetryAllowed) RouteFailureScope.Unknown else RouteFailureScope.Configuration,
        retryAllowed = startupRetryAllowed
    )

    fun resetRouteProbeConnections() {
        runningRouteProbe.reset()
    }

    fun refreshRunningState(): Boolean {
        if (_state.value != TunnelCoreState.Connected) {
            return false
        }

        return try {
            if (bridge.getState()) {
                consecutiveStateQueryFailures = 0
                true
            } else {
                _lastError.value = "Xray stopped unexpectedly"
                _state.value = TunnelCoreState.Error
                false
            }
        } catch (e: Exception) {
            consecutiveStateQueryFailures++
            _lastError.value = "Failed to check Xray state: ${e.localizedMessage ?: e.javaClass.simpleName}"
            if (consecutiveStateQueryFailures >= STATE_QUERY_FAILURES_BEFORE_ERROR) {
                _state.value = TunnelCoreState.Error
                false
            } else {
                true
            }
        }
    }

    suspend fun verifyRunningRoute(): RunningRouteResult {
        val socksPort = healthSocksPort
            ?: return RunningRouteResult(success = false, error = "Tunnel health probe is not initialized")
        if (!refreshRunningState()) {
            return RunningRouteResult(
                success = false,
                error = _lastError.value ?: "Xray stopped unexpectedly"
            )
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        return runningRouteProbe.probe(socksPort).copy(
            latencyMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
        )
    }

    override suspend fun stop() {
        _state.value = TunnelCoreState.Stopping
        runningRouteProbe.reset()
        if (bridge is ReflectionXrayBridge && nativeLease == null) {
            healthSocksPort = null
            _state.value = TunnelCoreState.Disconnected
            return
        }
        if (!bridge.isAvailable()) {
            healthSocksPort = null
            consecutiveStateQueryFailures = 0
            _lastError.value = null
            _state.value = TunnelCoreState.Disconnected
            return
        }
        val stopResult = runCatching {
            bridge.stop()
        }.getOrElse { XrayCallResult(success = false, error = it.localizedMessage ?: it.javaClass.simpleName) }
        if (!stopResult.success) {
            healthSocksPort = null
            _lastError.value = stopResult.error ?: "Xray did not accept the stop request"
            _state.value = TunnelCoreState.Error
            return
        }
        healthSocksPort = null
        val stopped: Boolean = withTimeoutOrNull<Boolean>(stopWaitTimeoutMs.coerceAtLeast(0L)) {
            var coreStopped = false
            while (!coreStopped) {
                coreStopped = runCatching { !bridge.getState() }.getOrDefault(false)
                if (!coreStopped) {
                    delay(stopPollIntervalMs.coerceIn(10L, 250L))
                }
            }
            coreStopped
        } ?: false
        if (stopped) {
            nativeLease?.close()
            nativeLease = null
            consecutiveStateQueryFailures = 0
            _lastError.value = null
            _state.value = TunnelCoreState.Disconnected
        } else {
            _lastError.value = "Xray did not stop within the expected time"
            _state.value = TunnelCoreState.Error
        }
    }

    private fun isAddressInUse(error: String?): Boolean {
        val value = error.orEmpty()
        return value.contains("address already in use", ignoreCase = true) ||
            value.contains("EADDRINUSE", ignoreCase = true)
    }

    private fun releaseStoppedNativeLease() {
        if (runCatching { !bridge.getState() }.getOrDefault(false)) {
            nativeLease?.close()
            nativeLease = null
        }
    }

}

private fun findFreeLocalPort(): Int {
    return ServerSocket(0).use { socket ->
        socket.reuseAddress = true
        socket.localPort
    }
}
