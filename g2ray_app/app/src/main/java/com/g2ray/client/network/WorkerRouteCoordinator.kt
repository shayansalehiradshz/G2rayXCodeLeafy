package com.g2ray.client.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory

enum class WorkerRouteEndpoint {
    WAKE,
    HEALTH
}

enum class WorkerRoutePhase {
    WAKING,
    SETTLING
}

data class WorkerRouteProgress(
    val phase: WorkerRoutePhase,
    val message: String,
    val attempt: Int
)

sealed interface WorkerRouteOutcome {
    data class Ready(
        val workerBaseUrl: String,
        val response: WorkerWakeResponse? = null
    ) : WorkerRouteOutcome

    data class Failed(
        val message: String,
        val controlPlaneUnavailable: Boolean = false,
        val httpCode: Int? = null,
        val response: WorkerWakeResponse? = null,
        val retryAllowed: Boolean = wakeHttpFailureRetryAllowed(httpCode),
        val retryAfterMs: Long? = null
    ) : WorkerRouteOutcome

    data class TimedOut(val message: String) : WorkerRouteOutcome
}

object WorkerUrlNormalizer {
    fun normalize(input: String, endpoint: WorkerRouteEndpoint): String {
        var urlString = input.trim()
        require(urlString.isNotBlank()) { "Worker URL is empty" }
        require(!urlString.contains(Regex("\\s"))) { "Worker URL contains spaces" }

        if (urlString.startsWith("http://", ignoreCase = true)) {
            throw IllegalArgumentException("Worker URL must use HTTPS")
        }
        if (!urlString.startsWith("https://", ignoreCase = true)) {
            urlString = "https://$urlString"
        }

        val uri = URI(urlString)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.authority.isNullOrBlank()) {
            "Worker URL must contain a valid HTTPS hostname"
        }
        val path = uri.path?.removeSuffix("/") ?: ""
        val validPaths = setOf("", "/", "/wake", "/api/wake", "/health", "/api/health")
        require(path in validPaths) {
            "Invalid Worker URL: unexpected path ${uri.path}. Paste only the Worker domain or /wake URL."
        }

        val baseUrl = "${uri.scheme}://${uri.authority}"
        return when (endpoint) {
            WorkerRouteEndpoint.WAKE -> "$baseUrl/wake"
            WorkerRouteEndpoint.HEALTH -> "$baseUrl/api/health"
        }
    }

    fun baseUrl(input: String): String = normalize(input, WorkerRouteEndpoint.WAKE).removeSuffix("/wake")
}

object WorkerNetwork {
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 45L

    fun createHttpClient(
        loggingEnabled: Boolean,
        protectSocket: ((Socket) -> Boolean)? = null,
        dnsLookup: ((String) -> List<InetAddress>)? = null
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            if (loggingEnabled) {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
            } else {
                level = HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .connectionPool(ControlPlaneConnectionPools.create())
            .addInterceptor(logging)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(55, TimeUnit.SECONDS)
            .apply {
                if (dnsLookup != null) {
                    dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            return dnsLookup(hostname)
                        }
                    })
                }
                if (protectSocket != null) {
                    socketFactory(VpnBypassSocketFactory(protect = protectSocket))
                }
            }
            .build()
    }

    fun createApiService(
        loggingEnabled: Boolean,
        protectSocket: ((Socket) -> Boolean)? = null,
        dnsLookup: ((String) -> List<InetAddress>)? = null
    ): WorkerApiService {
        val client = createHttpClient(loggingEnabled, protectSocket, dnsLookup)
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        return Retrofit.Builder()
            .baseUrl("https://worker.invalid/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(WorkerApiService::class.java)
    }
}

/** Drops only Worker/GitHub keep-alive sockets when Android changes upstreams. */
internal object ControlPlaneConnectionPools {
    private val pools = CopyOnWriteArrayList<WeakReference<ConnectionPool>>()
    private val evictionRequests = Channel<Unit>(Channel.CONFLATED)
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        maintenanceScope.launch {
            for (request in evictionRequests) {
                // Network callbacks run on Android's main thread. TLS socket
                // closure may write close_notify; perform it here instead.
                pools.forEach { reference -> reference.get()?.let { pool -> runCatching { pool.evictAll() } } }
                pools.removeAll { it.get() == null }
            }
        }
    }

    fun create(): ConnectionPool {
        return ConnectionPool().also { pool ->
            pools += WeakReference(pool)
        }
    }

    fun evictAll() {
        evictionRequests.trySend(Unit)
    }
}

/** Protects Worker control-plane sockets before they connect through an active VPN. */
internal class VpnBypassSocketFactory(
    private val delegate: SocketFactory = SocketFactory.getDefault(),
    private val protect: (Socket) -> Boolean
) : SocketFactory() {
    override fun createSocket(): Socket = protectedSocket()

    override fun createSocket(host: String, port: Int): Socket =
        protectedSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = protectedSocket().apply {
        bind(InetSocketAddress(localHost, localPort))
        connect(InetSocketAddress(host, port))
    }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        protectedSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = protectedSocket().apply {
        bind(InetSocketAddress(localAddress, localPort))
        connect(InetSocketAddress(address, port))
    }

    private fun protectedSocket(): Socket {
        val socket = delegate.createSocket()
        if (runCatching { protect(socket) }.getOrDefault(false)) {
            return socket
        }
        runCatching { socket.close() }
        throw IOException("Could not route the recovery control-plane socket outside the VPN")
    }
}

class WorkerRouteCoordinator(
    private val apiService: WorkerApiService,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private data class WorkerCall(
        val response: Response<WorkerWakeResponse>,
        val errorBody: WorkerWakeResponse?,
        val workerBaseUrl: String,
        val previousRetryDeadlineMs: Long? = null
    )

    private val errorAdapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(WorkerWakeResponse::class.java)

    suspend fun ensureRouteReady(
        workerBaseUrls: List<String>,
        secret: String,
        codespaceId: String? = null,
        maxWaitMs: Long = 3 * 60 * 1000L,
        maxAttempts: Int = 40,
        expectedCodespaceName: String? = null,
        onProgress: (WorkerRouteProgress) -> Unit = {}
    ): WorkerRouteOutcome {
        val normalizedBases = workerBaseUrls
            .mapNotNull { runCatching { WorkerUrlNormalizer.baseUrl(it) }.getOrNull() }
            .distinct()
        if (normalizedBases.isEmpty()) {
            return WorkerRouteOutcome.Failed("No valid Worker URL is configured.", retryAllowed = false)
        }
        if (secret.isBlank()) {
            return WorkerRouteOutcome.Failed("The active wake key is empty.", retryAllowed = false)
        }

        var orderedBases = normalizedBases
        var endpoint = WorkerRouteEndpoint.WAKE
        var attempts = 0
        val startedAt = nowMs()

        while (attempts < maxAttempts && nowMs() - startedAt < maxWaitMs) {
            attempts++
            onProgress(
                WorkerRouteProgress(
                    phase = if (endpoint == WorkerRouteEndpoint.WAKE) WorkerRoutePhase.WAKING else WorkerRoutePhase.SETTLING,
                    message = if (endpoint == WorkerRouteEndpoint.WAKE) {
                        "Contacting Codespace Worker..."
                    } else {
                        "Checking Codespace route..."
                    },
                    attempt = attempts
                )
            )

            val call = try {
                withTimeoutOrNull((maxWaitMs - (nowMs() - startedAt)).coerceAtLeast(1L)) {
                    callWithFailover(orderedBases, endpoint, secret, codespaceId)
                } ?: return WorkerRouteOutcome.TimedOut("Worker recovery deadline exceeded.")
            } catch (error: IOException) {
                return WorkerRouteOutcome.Failed(
                    message = error.localizedMessage ?: "All configured Worker endpoints are unreachable.",
                    controlPlaneUnavailable = true
                )
            }
            orderedBases = listOf(call.workerBaseUrl) + orderedBases.filterNot { it == call.workerBaseUrl }
            val response = call.response
            val body = if (response.isSuccessful) response.body() else call.errorBody

            if (response.isSuccessful && !expectedCodespaceName.isNullOrBlank() &&
                body?.codespace != expectedCodespaceName
            ) {
                return WorkerRouteOutcome.Failed(
                    "Worker responded for a different or unidentified Codespace. Check this account's Worker selection.",
                    response = body,
                    retryAllowed = false
                )
            }

            if (response.isSuccessful) {
                if (permanentWakeFailure(body)) {
                    return WorkerRouteOutcome.Failed(
                        body?.next_action ?: body?.message ?: "Codespace wake needs configuration changes.",
                        response = body,
                        retryAllowed = false
                    )
                }
                if (body?.route_ready == true) {
                    return WorkerRouteOutcome.Ready(call.workerBaseUrl, body)
                }
                if (shouldPoll(response.code(), body)) {
                    val waitSeconds = pollDelaySeconds(body)
                    onProgress(
                        WorkerRouteProgress(
                            phase = WorkerRoutePhase.SETTLING,
                            message = "Codespace route is settling. Retrying in ${waitSeconds}s...",
                            attempt = attempts
                        )
                    )
                    sleep(minOf(waitSeconds * 1000L, (maxWaitMs - (nowMs() - startedAt)).coerceAtLeast(0L)))
                    endpoint = WorkerRouteEndpoint.HEALTH
                    continue
                }
                return WorkerRouteOutcome.Failed(
                    body?.next_action ?: body?.message ?: "Worker returned an unknown route state.",
                    response = body
                )
            }

            if (response.code() == 429 && body?.reason == "wake_recently_succeeded") {
                val waitSeconds = (body.poll_after_seconds ?: 2).coerceIn(1, 5)
                sleep(minOf(waitSeconds * 1000L, (maxWaitMs - (nowMs() - startedAt)).coerceAtLeast(0L)))
                endpoint = WorkerRouteEndpoint.HEALTH
                continue
            }

            val message = body?.next_action
                ?: body?.reason
                ?: body?.error
                ?: when (response.code()) {
                    401 -> "Wrong wake secret."
                    402 -> "GitHub quota or billing blocked the Codespace."
                    403 -> "Worker GitHub token lacks Codespaces permission."
                    404 -> "Codespace name is wrong or the token cannot access it."
                    429 -> "Too many wake attempts. Wait and retry."
                    else -> "Worker failed with HTTP ${response.code()}."
                }
            val requestId = response.headers()["x-g2ray-request-id"]?.takeIf { it.isNotBlank() }
            val retryAllowed = !permanentWakeFailure(body) &&
                (isRateLimited(response, body) || wakeHttpFailureRetryAllowed(response.code()))
            return WorkerRouteOutcome.Failed(
                message = if (requestId == null) message else "$message Request ID: $requestId",
                controlPlaneUnavailable = response.code() in 500..599,
                httpCode = response.code(),
                response = body,
                retryAllowed = retryAllowed,
                retryAfterMs = if (retryAllowed) {
                    listOfNotNull(
                        failureRetryAfterMs(response, body),
                        call.previousRetryDeadlineMs?.let { (it - nowMs()).coerceAtLeast(0L) }
                    ).maxOrNull()
                } else null
            )
        }

        return WorkerRouteOutcome.TimedOut("Codespace route did not become ready within ${maxWaitMs / 1000}s.")
    }

    private fun isRateLimited(response: Response<WorkerWakeResponse>, body: WorkerWakeResponse?): Boolean =
        response.code() == 429 ||
            (response.code() == 403 && body?.reason in setOf("github_rate_limited", "github_secondary_rate_limited"))

    private fun failureRetryAfterMs(response: Response<WorkerWakeResponse>, body: WorkerWakeResponse?): Long? {
        val retryAfterSeconds = listOfNotNull(
            response.headers()["Retry-After"]?.trim()?.toLongOrNull()?.takeIf { it >= 0L },
            body?.retry_after_seconds?.toLong()?.takeIf { it >= 0L }
        ).maxOrNull()?.toString()
        val resetEpochSeconds = body?.retry_after_epoch?.takeIf {
            body.github_rate_limit_remaining == 0
        }?.toString()
        return if (isRateLimited(response, body) || retryAfterSeconds != null || resetEpochSeconds != null) {
            rateLimitRetryAfterMs(retryAfterSeconds, resetEpochSeconds, nowEpochMs())
        } else null
    }

    private suspend fun callWithFailover(
        baseUrls: List<String>,
        endpoint: WorkerRouteEndpoint,
        secret: String,
        codespaceId: String?
    ): WorkerCall {
        var lastIoError: IOException? = null
        var lastRetryableCall: WorkerCall? = null
        var previousRetryDeadlineMs: Long? = null
        baseUrls.forEachIndexed { index, baseUrl ->
            val url = WorkerUrlNormalizer.normalize(baseUrl, endpoint)
            val response = try {
                if (endpoint == WorkerRouteEndpoint.WAKE) {
                    apiService.wake(url, "Bearer $secret", CodespaceRequest(codespaceId))
                } else {
                    apiService.health(url, "Bearer $secret", CodespaceRequest(codespaceId))
                }
            } catch (error: IOException) {
                lastIoError = error
                return@forEachIndexed
            }
            val call = WorkerCall(response, parseErrorBody(response), baseUrl, previousRetryDeadlineMs)
            val canTryFallback = index + 1 < baseUrls.size &&
                response.code() in 500..599
            if (canTryFallback) {
                // A working backup can bypass an unavailable endpoint, but
                // another failure must not erase its cooldown or restart it.
                val retryDeadlineMs = failureRetryAfterMs(response, call.errorBody)?.let { waitMs ->
                    nowMs().coerceIn(0L, Long.MAX_VALUE - waitMs) + waitMs
                }
                previousRetryDeadlineMs = listOfNotNull(previousRetryDeadlineMs, retryDeadlineMs).maxOrNull()
                lastRetryableCall = call.copy(previousRetryDeadlineMs = previousRetryDeadlineMs)
                return@forEachIndexed
            }
            return call
        }
        lastRetryableCall?.let { return it }
        throw IOException(
            "Failed to connect to every configured Worker endpoint: ${lastIoError?.localizedMessage ?: "network unavailable"}",
            lastIoError
        )
    }

    private fun parseErrorBody(response: Response<WorkerWakeResponse>): WorkerWakeResponse? {
        if (response.isSuccessful) return null
        return runCatching { response.errorBody()?.string()?.let(errorAdapter::fromJson) }.getOrNull()
    }

    companion object {
        private fun permanentWakeFailure(body: WorkerWakeResponse?): Boolean =
            body?.quota_blocked == true ||
                body?.state?.lowercase() in setOf("deleted", "archived", "failed") ||
                body?.error in setOf("missing_wake_secret", "missing_codespace_name", "missing_github_token_binding")

        fun shouldPoll(httpCode: Int, body: WorkerWakeResponse?): Boolean {
            if (permanentWakeFailure(body)) return false
            return httpCode == 202 ||
                body?.route_ready == false ||
                body?.start_accepted == true ||
                body?.next_action_code == "wait_codespace_available"
        }

        fun pollDelaySeconds(body: WorkerWakeResponse?, defaultSeconds: Int = 5): Int {
            return (body?.poll_after_seconds ?: body?.retry_after_seconds ?: defaultSeconds).coerceIn(2, 15)
        }
    }
}
