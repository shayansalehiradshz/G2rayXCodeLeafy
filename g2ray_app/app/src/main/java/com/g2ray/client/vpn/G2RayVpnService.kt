package com.g2ray.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.g2ray.client.BuildConfig
import com.g2ray.client.G2RayApplication
import com.g2ray.client.data.EncryptedPrefsStore
import com.g2ray.client.MainActivity
import com.g2ray.client.model.AppConnectionState
import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.ApiKeyKind
import com.g2ray.client.model.G2RayProfile
import com.g2ray.client.model.RouteState
import com.g2ray.client.model.StoredG2RayProfile
import com.g2ray.client.model.TunnelTrafficSource
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.VpnEngineMode
import com.g2ray.client.model.VpnEngineSelector
import com.g2ray.client.model.VpnStateRepository
import com.g2ray.client.network.CodespaceWakeCoordinator
import com.g2ray.client.network.CodespaceWakeOutcome
import com.g2ray.client.network.CodespaceWakeRequest
import com.g2ray.client.network.GitHubCodespaceCoordinator
import com.g2ray.client.network.GitHubNetwork
import com.g2ray.client.network.MAX_WAKE_RETRY_DELAY_MS
import com.g2ray.client.network.WakeBackendSelector
import com.g2ray.client.network.WakeControlBackend
import com.g2ray.client.network.WakeRetryCooldown
import com.g2ray.client.network.WorkerNetwork
import com.g2ray.client.network.WorkerRouteCoordinator
import com.g2ray.client.network.awaitWakeRetryDelay
import com.g2ray.client.parser.VlessUriParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException
import kotlin.random.Random

internal class TunnelLifecycleGuard {
    private var generation = 0L

    @Synchronized
    fun begin(): Long {
        generation += 1
        return generation
    }

    @Synchronized
    fun invalidate(): Long {
        generation += 1
        return generation
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == generation
}

internal enum class NetworkHandoffAction {
    Ignore,
    Defer,
    VerifyRoute,
    RecoverCore
}

internal enum class AppResumeAction {
    QueueRestart,
    KeepAlive,
    VerifyRoute,
    RecoverCore,
    RestartTunnel
}

internal enum class RouteReverifyReason {
    ActiveStall,
    IdleTraffic,
    DeviceWake
}

internal data class SessionProfileHealth(
    val consecutiveFailures: Int = 0,
    val lastAttemptSucceeded: Boolean = false,
    val lastSuccessLatencyMs: Long? = null,
    val observedAtMs: Long = 0L
)

internal data class ActiveStallWindow(
    val lastRxBytes: Long,
    val lastTxBytes: Long,
    val lastRxPackets: Long,
    val lastTxPackets: Long,
    val txBytesAtLastRxProgress: Long,
    val txPacketsAtLastRxProgress: Long,
    val lastRxProgressAtMs: Long
)

class G2RayVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val lifecycleGuard = TunnelLifecycleGuard()
    @Volatile
    private var activeLifecycleToken = 0L
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleOperationMutex = Mutex()
    private var startJob: Job? = null
    private var cleanupJob: Job? = null
    private var pendingStartAfterCleanup = false
    private var pendingStartIntent: Intent? = null
    private var pendingCleanupFinalState = TunnelState.Disconnected
    private var pendingCleanupFinalMessage = "Disconnected"
    private var pendingCleanupStopService = false
    @Volatile
    private var clearRememberedProfileAfterCleanup = false
    private var trafficJob: Job? = null
    private var healthJob: Job? = null
    private var routeRecoveryJob: Job? = null
    private var reconnectRetryJob: Job? = null
    @Volatile
    private var desiredConnected = false
    private var routeReverifyJob: Job? = null
    private var networkHandoffJob: Job? = null
    @Volatile
    private var routeRecoveryInProgress = false
    private var activeProfile: G2RayProfile? = null
    private var activeAccountId: String? = null
    @Volatile
    private var underlyingNetwork: Network? = null
    @Volatile
    private var waitingForUnderlyingNetwork = false
    private val underlyingNetworkGeneration = AtomicLong(0)
    private var underlyingNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var usesPlatformBestUnderlyingNetwork = false
    private val availableUnderlyingNetworks = mutableMapOf<Network, NetworkCapabilities>()
    private val lostUnderlyingNetworks = mutableSetOf<Network>()
    private val lastPromptRouteReverifyAtMs = RouteReverifyReason.entries.associateWith { AtomicLong(0L) }
    private val screenOffAtMs = AtomicLong(0L)
    private var idleTransitionReceiverRegistered = false
    private val idleTransitionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val nowMs = SystemClock.elapsedRealtime()
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffAtMs.set(nowMs)
                Intent.ACTION_SCREEN_ON -> {
                    val screenOffAt = screenOffAtMs.getAndSet(0L)
                    if (screenOffAt <= 0L) return
                    val state = VpnStateRepository.state.value
                    if (
                        shouldReverifyAfterDeviceWake(
                            tunnelState = state.tunnelState,
                            tunnelVerified = state.tunnelVerified,
                            screenIdleDurationMs = (nowMs - screenOffAt).coerceAtLeast(0L),
                            timeSinceLastReverifyMs = timeSinceLastPromptReverify(
                                RouteReverifyReason.DeviceWake,
                                nowMs
                            )
                        )
                    ) {
                        requestPromptRouteReverification(RouteReverifyReason.DeviceWake, nowMs)
                    }
                }
            }
        }
    }
    private val core = XrayCoreManager()
    private val fullVpnDataPathProbe: VpnDataPathProbe = OkHttpVpnDataPathProbe()
    private val vpnPreferences by lazy { VpnEnginePreferences(this) }
    private val accountRepository by lazy { (application as G2RayApplication).accountRepository }
    private val workerCoordinator by lazy {
        WorkerRouteCoordinator(
            WorkerNetwork.createApiService(
                loggingEnabled = BuildConfig.DEBUG,
                protectSocket = SocketProtector::protectControlPlane,
                dnsLookup = SocketProtector::lookupControlPlane
            )
        )
    }
    private val githubCoordinator by lazy {
        GitHubCodespaceCoordinator(
            GitHubNetwork.createApiService(
                loggingEnabled = BuildConfig.DEBUG,
                protectSocket = SocketProtector::protectControlPlane,
                dnsLookup = SocketProtector::lookupControlPlane
            )
        )
    }
    private val codespaceWakeCoordinator by lazy {
        CodespaceWakeCoordinator(workerCoordinator, githubCoordinator)
    }
    private var speedSampler: TrafficSpeedSampler? = null
    private var requestedEngineMode = VpnEngineMode.Auto
    private var fastDnsEnabled = false
    private var ipv4Only = false
    private var dpiCompatibilityEnabled = false
    private var seamlessFailoverEnabled = true
    private var activeEngine: ActiveVpnEngine? = null
    private var hevTunnelRunning = false
    private val sessionProfileHealth = mutableMapOf<String, SessionProfileHealth>()

    companion object {
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "g2ray_vpn_channel"
        const val ACTION_START = "com.g2ray.client.vpn.START"
        const val ACTION_STOP = "com.g2ray.client.vpn.STOP"
        const val ACTION_REVERIFY = "com.g2ray.client.vpn.REVERIFY"
        const val EXTRA_RAW_PROFILE_URI = "com.g2ray.client.extra.RAW_PROFILE_URI"
        const val EXTRA_ACCOUNT_ID = "com.g2ray.client.extra.ACCOUNT_ID"
        const val EXTRA_VPN_ENGINE_MODE = "com.g2ray.client.extra.VPN_ENGINE_MODE"
        private const val PREF_LAST_RAW_PROFILE_URI = "last_raw_profile_uri"
        private const val PREF_DESIRED_CONNECTED = "vpn_desired_connected"
        private const val PREF_LAST_ACCOUNT_ID = "last_account_id"
        private const val CORE_STATE_CHECK_INTERVAL_MS = 5_000L
        private const val ROUTE_HEALTH_CHECK_INTERVAL_MS = 30_000L
        private const val ROUTE_HEALTH_CONFIRM_RETRY_MS = 5_000L
        private const val ROUTE_HEALTH_FAILURES_BEFORE_RECOVERY = 3
        private const val ROUTE_HEALTH_PAYLOAD_GRACE_FAILURES = 6
        private const val NETWORK_HANDOFF_RECOVERY_DELAY_MS = 2_500L
        private const val TRANSITION_ROUTE_CONFIRM_DELAY_MS = 1_500L
        private const val IDLE_ACTIVITY_REVERIFY_THRESHOLD_MS = 60_000L
        private const val PASSIVE_ROUTE_REVERIFY_COOLDOWN_MS = 30_000L
        private const val ACTIVE_STALL_ROUTE_REVERIFY_COOLDOWN_MS = 10_000L
        private const val ACTIVE_STALL_MIN_TX_BYTES = 32L * 1024L
        private const val ACTIVE_STALL_INTERACTIVE_MIN_TX_BYTES = 2L * 1024L
        private const val ACTIVE_STALL_INTERACTIVE_MIN_TX_PACKETS = 4L
        private const val MAX_LIVE_ROUTE_PROFILES = 3
        // Chunked video commonly pauses its downlink for several seconds while
        // the local buffer drains. Ten seconds still catches a real request
        // stall promptly without probing healthy playback every chunk boundary.
        private const val ACTIVE_STALL_NO_RX_THRESHOLD_MS = 10_000L
        // Confirm an active-stall failure before tearing down the live tunnel,
        // but keep local socket/core failures immediate.
        private const val ACTIVE_STALL_CONFIRM_DELAY_MS = 2_000L
        private const val MAX_LOCAL_ROUTE_RECOVERY_ATTEMPTS = 1
        private const val PRE_WAKE_ALTERNATIVE_ATTEMPTS = 1
        private const val WORKER_ROUTE_RECOVERY_TIMEOUT_MS = 3 * 60 * 1000L
        private const val DIRECT_ROUTE_SETTLE_TIMEOUT_MS = 2 * 60 * 1000L
        private const val DIRECT_ROUTE_PROBE_INTERVAL_MS = 3_000L
        private const val SESSION_PROFILE_HEALTH_MAX_AGE_MS = 15 * 60 * 1000L
        private const val LIVE_ROUTE_MEASUREMENT_MAX_AGE_MS = 6 * 60 * 60 * 1000L

        internal fun shouldRecoverImmediatelyForLocalSocksFailure(error: String?): Boolean {
            val value = error.orEmpty()
            return value.contains("ECONNREFUSED", ignoreCase = true) &&
                (value.contains("127.0.0.1") || value.contains("localhost", ignoreCase = true))
        }

        internal fun reconnectBackoffMs(attempt: Int): Long =
            minOf(60_000L, 5_000L * (1L shl attempt.coerceIn(0, 4)))

        internal fun reconnectDelayMs(attempt: Int, retryAfterMs: Long?): Long =
            maxOf(reconnectBackoffMs(attempt), retryAfterMs?.coerceIn(0L, MAX_WAKE_RETRY_DELAY_MS) ?: 0L)

        internal fun routeHealthFailureRequiresRecovery(failures: Int, payloadAdvancing: Boolean): Boolean =
            failures >= if (payloadAdvancing) ROUTE_HEALTH_PAYLOAD_GRACE_FAILURES else ROUTE_HEALTH_FAILURES_BEFORE_RECOVERY

        internal fun shouldPauseRouteHealthChecks(waitingForUnderlyingNetwork: Boolean): Boolean {
            return waitingForUnderlyingNetwork
        }

        internal fun packetEngineRuntimeStopped(
            engine: ActiveVpnEngine?,
            packetEngineExpected: Boolean,
            nativeRuntimeRunning: Boolean
        ): Boolean {
            return engine == ActiveVpnEngine.Hev &&
                packetEngineExpected &&
                !nativeRuntimeRunning
        }

        internal fun shouldConfirmTransitionRouteFailure(result: RunningRouteResult): Boolean {
            return !result.success && !shouldRecoverImmediatelyForLocalSocksFailure(result.error)
        }

        internal fun shouldConfirmActiveStallFailure(
            reason: RouteReverifyReason,
            result: RunningRouteResult
        ): Boolean {
            return reason == RouteReverifyReason.ActiveStall &&
                !result.success &&
                result.failureScope != RouteFailureScope.DialerBinding &&
                !shouldRecoverImmediatelyForLocalSocksFailure(result.error)
        }

        internal fun routeHealthCheckDelayMs(
            routeSuccess: Boolean,
            trafficIsAdvancing: Boolean,
            routeFailureCount: Int
        ): Long {
            return if (
                !routeSuccess &&
                !trafficIsAdvancing &&
                routeFailureCount in 1 until ROUTE_HEALTH_FAILURES_BEFORE_RECOVERY
            ) {
                ROUTE_HEALTH_CONFIRM_RETRY_MS
            } else {
                ROUTE_HEALTH_CHECK_INTERVAL_MS
            }
        }

        internal fun networkHandoffAction(
            coroutineActive: Boolean,
            hasVpnInterface: Boolean,
            coreState: TunnelCoreState
        ): NetworkHandoffAction {
            if (!coroutineActive || !hasVpnInterface) return NetworkHandoffAction.Ignore
            return when (coreState) {
                TunnelCoreState.Starting -> NetworkHandoffAction.Defer
                TunnelCoreState.Connected -> NetworkHandoffAction.VerifyRoute
                else -> NetworkHandoffAction.RecoverCore
            }
        }

        internal fun shouldRetryAfterNetworkChange(
            recoveryNetworkGeneration: Long,
            currentNetworkGeneration: Long,
            waitingForUnderlyingNetwork: Boolean
        ): Boolean {
            return waitingForUnderlyingNetwork ||
                currentNetworkGeneration != recoveryNetworkGeneration
        }

        internal fun shouldStartRouteReverification(
            routeRecoveryInProgress: Boolean,
            routeRecoveryActive: Boolean,
            routeReverifyActive: Boolean,
            networkHandoffActive: Boolean,
            startActive: Boolean,
            cleanupActive: Boolean
        ): Boolean {
            return !routeRecoveryInProgress &&
                !routeRecoveryActive &&
                !routeReverifyActive &&
                !networkHandoffActive &&
                !startActive &&
                !cleanupActive
        }

        internal fun shouldReverifyAfterStartupNetworkChange(
            routeSuccess: Boolean,
            startupNetworkGeneration: Long,
            currentNetworkGeneration: Long
        ): Boolean {
            return routeSuccess && currentNetworkGeneration != startupNetworkGeneration
        }

        internal fun appResumeAction(
            coreState: TunnelCoreState,
            hasVpnInterface: Boolean,
            hasActiveProfile: Boolean,
            cleanupActive: Boolean
        ): AppResumeAction {
            if (cleanupActive) return AppResumeAction.QueueRestart
            if (coreState == TunnelCoreState.Stopping) return AppResumeAction.QueueRestart
            if (!hasVpnInterface) {
                return if (coreState == TunnelCoreState.Starting) {
                    AppResumeAction.KeepAlive
                } else {
                    AppResumeAction.RestartTunnel
                }
            }
            return when {
                coreState == TunnelCoreState.Connected -> AppResumeAction.VerifyRoute
                coreState == TunnelCoreState.Starting -> AppResumeAction.KeepAlive
                hasActiveProfile -> AppResumeAction.RecoverCore
                else -> AppResumeAction.RestartTunnel
            }
        }

        internal fun selectAlternativeProfiles(
            profiles: List<StoredG2RayProfile>,
            accountId: String?,
            currentRawUri: String,
            maxCandidates: Int = 6,
            sessionHealth: Map<String, SessionProfileHealth> = emptyMap()
        ): List<StoredG2RayProfile> {
            if (accountId.isNullOrBlank() || maxCandidates <= 0) return emptyList()
            val ranked = profiles
                .asSequence()
                .filter { it.accountId == accountId && it.rawUri != currentRawUri }
                .sortedWith(
                    compareBy<StoredG2RayProfile> {
                        sessionHealth[it.rawUri]?.consecutiveFailures ?: 0
                    }
                        .thenBy {
                            sessionHealth[it.rawUri]?.lastAttemptSucceeded != true
                        }
                        .thenBy {
                            if (sessionHealth[it.rawUri]?.lastAttemptSucceeded == true) {
                                false
                            } else {
                                it.lastDelayError != null
                            }
                        }
                        .thenBy {
                            if (sessionHealth[it.rawUri]?.lastAttemptSucceeded == true) {
                                0
                            } else {
                                it.delayFailureCount
                            }
                        }
                        .thenBy {
                            sessionHealth[it.rawUri]?.lastSuccessLatencyMs
                                ?: persistedRouteScore(it)
                        }
                        .thenBy { it.createdAt }
                )
                .toList()

            val selected = ArrayList<StoredG2RayProfile>(minOf(maxCandidates, ranked.size))
            val selectedIds = HashSet<String>()
            val seenRoutes = HashSet<String>()
            val currentTransport = VlessUriParser.parse(currentRawUri).getOrNull()?.transport
            val alternate = if (maxCandidates > 1 && currentTransport != null) {
                ranked.firstOrNull {
                    VlessUriParser.parse(it.rawUri).getOrNull()?.transport?.let { transport ->
                        transport != currentTransport
                    } == true
                }
            } else null
            val primaryBudget = maxCandidates - if (alternate != null) 1 else 0
            for (profile in ranked) {
                if (profile === alternate) continue
                val routeKey = "${profile.address.lowercase()}:${profile.port}"
                if (seenRoutes.add(routeKey)) {
                    selected += profile
                    selectedIds += profile.id
                    if (selected.size >= primaryBudget) break
                }
            }
            if (alternate != null) {
                selected.add(minOf(1, selected.size), alternate)
                selectedIds += alternate.id
            }
            for (profile in ranked) {
                if (selected.size >= maxCandidates) break
                if (selectedIds.add(profile.id)) {
                    selected += profile
                    if (selected.size >= maxCandidates) break
                }
            }
            return selected
        }

        internal fun selectSeamlessRouteProfiles(
            selected: G2RayProfile,
            rankedCandidates: List<G2RayProfile>,
            maxProfiles: Int = MAX_LIVE_ROUTE_PROFILES
        ): List<G2RayProfile> {
            val boundedLimit = maxProfiles.coerceIn(1, MAX_LIVE_ROUTE_PROFILES)
            val selectedTemplate = VlessUriParser.transportTemplateKey(selected)
            val seenEndpoints = hashSetOf("${selected.address.lowercase()}:${selected.port}")
            return buildList(boundedLimit) {
                add(selected)
                rankedCandidates.forEach { candidate ->
                    if (size >= boundedLimit) return@forEach
                    if (VlessUriParser.transportTemplateKey(candidate) != selectedTemplate) return@forEach
                    val endpoint = "${candidate.address.lowercase()}:${candidate.port}"
                    if (seenEndpoints.add(endpoint)) add(candidate)
                }
            }
        }

        internal fun resolveRuntimeProfiles(
            profiles: List<G2RayProfile>,
            resolve: (String) -> String
        ): List<G2RayProfile> {
            require(profiles.isNotEmpty()) { "A selected VPN profile is required" }
            return profiles.mapIndexedNotNull { index, profile ->
                try {
                    profile.copy(address = resolve(profile.address))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // An optional backup can lose DNS on a mobile handoff even
                    // while the selected IP route works. Only the primary is
                    // required for startup; future recoveries retry backups.
                    if (index == 0) throw error
                    null
                }
            }.distinctBy { "${it.address.lowercase()}:${it.port}" }
        }

        internal fun persistedRouteScore(profile: StoredG2RayProfile): Long {
            val median = profile.lastDelayMs?.takeIf { it in 1..9_999 } ?: return Long.MAX_VALUE
            val p95 = profile.lastDelayP95Ms?.takeIf { it in median..60_000L } ?: median
            val tailPenalty = (p95 - median) / 2L
            val jitterPenalty = profile.lastDelayJitterMs?.takeIf { it in 0..60_000L } ?: 0L
            return median + tailPenalty + jitterPenalty
        }

        internal fun persistedRouteMeasurementFresh(
            profile: StoredG2RayProfile,
            nowMs: Long,
            maxAgeMs: Long = LIVE_ROUTE_MEASUREMENT_MAX_AGE_MS
        ): Boolean {
            val testedAt = profile.lastDelayTestedAt ?: return false
            val ageMs = nowMs - testedAt
            return ageMs in 0..maxAgeMs.coerceAtLeast(0L) &&
                profile.lastDelaySuccessfulSamples >= 2 &&
                profile.lastDelaySuccessfulSamples == profile.lastDelaySampleCount &&
                profile.lastDelayMs?.let { it in 1..9_999 } == true &&
                profile.lastDelayError == null &&
                profile.delayFailureCount == 0
        }

        internal fun updatedSessionProfileHealth(
            previous: SessionProfileHealth?,
            success: Boolean,
            latencyMs: Long?,
            observedAtMs: Long
        ): SessionProfileHealth {
            return if (success) {
                SessionProfileHealth(
                    consecutiveFailures = 0,
                    lastAttemptSucceeded = true,
                    lastSuccessLatencyMs = latencyMs?.coerceAtLeast(0L),
                    observedAtMs = observedAtMs
                )
            } else {
                SessionProfileHealth(
                    consecutiveFailures = (previous?.consecutiveFailures ?: 0)
                        .let { if (it == Int.MAX_VALUE) it else it + 1 },
                    lastAttemptSucceeded = false,
                    lastSuccessLatencyMs = null,
                    observedAtMs = observedAtMs
                )
            }
        }

        internal fun freshSessionProfileHealth(
            observations: Map<String, SessionProfileHealth>,
            nowMs: Long,
            maxAgeMs: Long = SESSION_PROFILE_HEALTH_MAX_AGE_MS
        ): Map<String, SessionProfileHealth> {
            val boundedMaxAgeMs = maxAgeMs.coerceAtLeast(0L)
            return observations.filterValues { observation ->
                val ageMs = (nowMs - observation.observedAtMs).coerceAtLeast(0L)
                ageMs <= boundedMaxAgeMs
            }
        }

        internal fun shouldQueueStartRequest(cleanupActive: Boolean): Boolean = cleanupActive

        internal fun classifyVpnRouteHealth(
            fullPath: RunningRouteResult,
            xrayRoute: RunningRouteResult,
            dialerFailure: String? = null
        ): RunningRouteResult {
            if (fullPath.success) {
                return fullPath.copy(failureScope = RouteFailureScope.None)
            }
            if (xrayRoute.success) {
                return fullPath.copy(
                    error = "${fullPath.error ?: "Full VPN data path failed"}; Xray/Codespaces route is reachable",
                    failureScope = RouteFailureScope.PacketEngine
                )
            }
            if (!dialerFailure.isNullOrBlank()) {
                return RunningRouteResult(
                    success = false,
                    error = dialerFailure,
                    latencyMs = xrayRoute.latencyMs ?: fullPath.latencyMs,
                    failureScope = RouteFailureScope.DialerBinding
                )
            }
            return RunningRouteResult(
                success = false,
                error = buildString {
                    append(fullPath.error ?: "Full VPN data path failed")
                    append("; Xray route: ")
                    append(xrayRoute.error ?: "unreachable")
                },
                latencyMs = xrayRoute.latencyMs ?: fullPath.latencyMs,
                failureScope = RouteFailureScope.XrayRoute
            )
        }

        internal fun measuredResponseTrafficAdvanced(
            engine: ActiveVpnEngine?,
            previousTraffic: TrafficBytes?,
            currentTraffic: TrafficBytes?
        ): Boolean {
            return engine == ActiveVpnEngine.Hev &&
                previousTraffic != null &&
                currentTraffic != null &&
                previousTraffic.rxTcpPayloadBytes != null &&
                currentTraffic.rxTcpPayloadBytes != null &&
                currentTraffic.rxTcpPayloadBytes > previousTraffic.rxTcpPayloadBytes
        }

        internal fun shouldSuppressRouteRecoveryAfterFailedReverification(
            route: RunningRouteResult,
            engine: ActiveVpnEngine?,
            previousTraffic: TrafficBytes?,
            currentTraffic: TrafficBytes?
        ): Boolean {
            return !route.success &&
                route.failureScope != RouteFailureScope.DialerBinding &&
                route.failureScope != RouteFailureScope.PacketEngine &&
                !shouldRecoverImmediatelyForLocalSocksFailure(route.error) &&
                measuredResponseTrafficAdvanced(engine, previousTraffic, currentTraffic)
        }

        internal fun shouldReverifyAfterIdleTraffic(
            engine: ActiveVpnEngine?,
            idleDurationMs: Long,
            rxBps: Long,
            txBps: Long,
            timeSinceLastReverifyMs: Long
        ): Boolean {
            return engine == ActiveVpnEngine.Hev &&
                idleDurationMs >= IDLE_ACTIVITY_REVERIFY_THRESHOLD_MS &&
                (rxBps > 0L || txBps > 0L) &&
                timeSinceLastReverifyMs >= PASSIVE_ROUTE_REVERIFY_COOLDOWN_MS
        }

        internal fun shouldProbeSuspectedActiveStall(
            engine: ActiveVpnEngine?,
            txBytesSinceLastRx: Long,
            txPacketsSinceLastRx: Long,
            timeSinceLastRxMs: Long,
            timeSinceLastReverifyMs: Long
        ): Boolean {
            val bulkRequestStalled = txBytesSinceLastRx >= ACTIVE_STALL_MIN_TX_BYTES
            val interactiveRequestStalled =
                txBytesSinceLastRx >= ACTIVE_STALL_INTERACTIVE_MIN_TX_BYTES &&
                    txPacketsSinceLastRx >= ACTIVE_STALL_INTERACTIVE_MIN_TX_PACKETS
            return engine == ActiveVpnEngine.Hev &&
                (bulkRequestStalled || interactiveRequestStalled) &&
                timeSinceLastRxMs >= ACTIVE_STALL_NO_RX_THRESHOLD_MS &&
                timeSinceLastReverifyMs >= ACTIVE_STALL_ROUTE_REVERIFY_COOLDOWN_MS
        }

        internal fun advanceActiveStallWindow(
            current: ActiveStallWindow,
            rxBytes: Long,
            txBytes: Long,
            rxPackets: Long,
            txPackets: Long,
            nowMs: Long
        ): ActiveStallWindow {
            val boundedRx = rxBytes.coerceAtLeast(0L)
            val boundedTx = txBytes.coerceAtLeast(0L)
            val boundedRxPackets = rxPackets.coerceAtLeast(0L)
            val boundedTxPackets = txPackets.coerceAtLeast(0L)
            val countersRestarted = boundedRx < current.lastRxBytes ||
                boundedTx < current.lastTxBytes ||
                boundedRxPackets < current.lastRxPackets ||
                boundedTxPackets < current.lastTxPackets
            val responseAdvanced = boundedRx > current.lastRxBytes ||
                boundedRxPackets > current.lastRxPackets
            return if (countersRestarted || responseAdvanced) {
                ActiveStallWindow(
                    lastRxBytes = boundedRx,
                    lastTxBytes = boundedTx,
                    lastRxPackets = boundedRxPackets,
                    lastTxPackets = boundedTxPackets,
                    txBytesAtLastRxProgress = boundedTx,
                    txPacketsAtLastRxProgress = boundedTxPackets,
                    lastRxProgressAtMs = nowMs
                )
            } else {
                current.copy(
                    lastRxBytes = boundedRx,
                    lastTxBytes = boundedTx,
                    lastRxPackets = boundedRxPackets,
                    lastTxPackets = boundedTxPackets
                )
            }
        }

        internal fun shouldReverifyAfterDeviceWake(
            tunnelState: TunnelState,
            tunnelVerified: Boolean,
            screenIdleDurationMs: Long,
            timeSinceLastReverifyMs: Long
        ): Boolean {
            return tunnelState == TunnelState.Connected &&
                tunnelVerified &&
                screenIdleDurationMs >= IDLE_ACTIVITY_REVERIFY_THRESHOLD_MS &&
                timeSinceLastReverifyMs >= PASSIVE_ROUTE_REVERIFY_COOLDOWN_MS
        }

        internal fun underlyingNetworkPreferenceScore(
            hasInternet: Boolean,
            isNotVpn: Boolean,
            isValidated: Boolean,
            isNotMetered: Boolean,
            transportScore: Int
        ): Int {
            if (!hasInternet || !isNotVpn) {
                return Int.MIN_VALUE
            }
            return (if (isValidated) 1_000 else 0) +
                (if (isNotMetered) 100 else 0) +
                transportScore
        }

        internal fun shouldUsePlatformBestUnderlyingNetwork(sdkInt: Int): Boolean {
            return sdkInt >= Build.VERSION_CODES.S
        }

        internal fun <K, V> mergeUnderlyingNetworkCandidates(
            callbackObserved: Map<K, V>,
            callbackLost: Set<K>,
            snapshot: Map<K, V>
        ): Map<K, V> {
            return LinkedHashMap<K, V>(callbackObserved).apply {
                snapshot.forEach { (network, capabilities) ->
                    if (network !in callbackLost) {
                        putIfAbsent(network, capabilities)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        SocketProtector.vpnService = this
        SocketProtector.updateUnderlyingNetwork(null)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        registerIdleTransitionMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                desiredConnected = true
                startTunnel(intent)
                START_STICKY
            }
            ACTION_STOP -> {
                stopTunnel()
                START_NOT_STICKY
            }
            ACTION_REVERIFY -> {
                when (
                    appResumeAction(
                        coreState = core.state.value,
                        hasVpnInterface = vpnInterface != null,
                        hasActiveProfile = activeProfile != null,
                        cleanupActive = cleanupJob?.isActive == true
                    )
                ) {
                    AppResumeAction.KeepAlive -> Unit
                    AppResumeAction.QueueRestart -> restartTunnelAfterAppResume()
                    AppResumeAction.VerifyRoute -> requestRouteReverification(RouteReverifyReason.DeviceWake)
                    AppResumeAction.RecoverCore -> scheduleRouteRecovery(
                        core.lastError.value ?: "VPN core stopped while the app was in the background"
                    )
                    AppResumeAction.RestartTunnel -> restartTunnelAfterAppResume()
                }
                START_STICKY
            }
            null -> {
                desiredConnected = true
                startTunnel(null)
                START_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun requestRouteReverification(
        reason: RouteReverifyReason = RouteReverifyReason.DeviceWake
    ): Boolean {
        val token = activeLifecycleToken
        val tun = vpnInterface ?: return false
        if (!isTunnelCurrent(token, tun)) return false

        lateinit var job: Job
        synchronized(this) {
            if (
                !shouldStartRouteReverification(
                    routeRecoveryInProgress = routeRecoveryInProgress,
                    routeRecoveryActive = routeRecoveryJob?.isActive == true || reconnectRetryJob?.isActive == true,
                    routeReverifyActive = routeReverifyJob?.isActive == true,
                    networkHandoffActive = networkHandoffJob?.isActive == true,
                    startActive = startJob?.isActive == true,
                    cleanupActive = cleanupJob?.isActive == true
                )
            ) {
                return false
            }
            job = scope.launch(start = CoroutineStart.LAZY) {
                lifecycleOperationMutex.withLock {
                    if (
                        !isTunnelCurrent(token, tun) ||
                        routeRecoveryInProgress ||
                        routeRecoveryJob?.isActive == true ||
                        networkHandoffJob?.isActive == true
                    ) {
                        return@withLock
                    }

                    val trafficBeforeProbe = currentMeasuredTrafficSnapshot()
                    var route = verifyRouteAfterTransition()
                    var trafficAfterProbe = currentMeasuredTrafficSnapshot()
                    if (
                        !isTunnelCurrent(token, tun) ||
                        routeRecoveryInProgress ||
                        routeRecoveryJob?.isActive == true
                    ) {
                        return@withLock
                    }

                    // A video/CDN request can legitimately pause between
                    // chunks. If the first quorum probe fails, give the path
                    // a brief grace period and confirm with fresh sockets
                    // before scheduling a disruptive tunnel recovery.
                    if (shouldConfirmActiveStallFailure(reason, route)) {
                        delay(ACTIVE_STALL_CONFIRM_DELAY_MS)
                        if (
                            !isTunnelCurrent(token, tun) ||
                            routeRecoveryInProgress ||
                            routeRecoveryJob?.isActive == true
                        ) {
                            return@withLock
                        }
                        route = diagnoseVpnRoute(resetConnections = true)
                        trafficAfterProbe = currentMeasuredTrafficSnapshot()
                    }

                    val liveResponsesResumed = shouldSuppressRouteRecoveryAfterFailedReverification(
                        route = route,
                        engine = activeEngine,
                        previousTraffic = trafficBeforeProbe,
                        currentTraffic = trafficAfterProbe
                    )
                    if (route.success || liveResponsesResumed) {
                        withContext(Dispatchers.Main) {
                            if (
                                isTunnelCurrent(token, tun) &&
                                !routeRecoveryInProgress &&
                                routeRecoveryJob?.isActive != true
                            ) {
                                markTunnelVerified(
                                    if (route.success) {
                                        "VPN route reverified"
                                    } else {
                                        "VPN route verified by live traffic"
                                    }
                                )
                            }
                        }
                    } else {
                        scheduleRouteRecovery(
                            route.error ?: "VPN route stopped responding after app resume",
                            failureScope = route.failureScope
                        )
                    }
                }
            }
            routeReverifyJob = job
        }
        job.invokeOnCompletion {
            synchronized(this) {
                if (routeReverifyJob === job) {
                    routeReverifyJob = null
                }
            }
        }
        job.start()
        return true
    }

    private fun timeSinceLastPromptReverify(reason: RouteReverifyReason, nowMs: Long): Long {
        val lastMs = lastPromptRouteReverifyAtMs.getValue(reason).get()
        return if (lastMs <= 0L) Long.MAX_VALUE else (nowMs - lastMs).coerceAtLeast(0L)
    }

    private fun requestPromptRouteReverification(reason: RouteReverifyReason, nowMs: Long) {
        val timestamp = lastPromptRouteReverifyAtMs.getValue(reason)
        val cooldownMs = when (reason) {
            RouteReverifyReason.ActiveStall -> ACTIVE_STALL_ROUTE_REVERIFY_COOLDOWN_MS
            RouteReverifyReason.IdleTraffic,
            RouteReverifyReason.DeviceWake -> PASSIVE_ROUTE_REVERIFY_COOLDOWN_MS
        }
        while (true) {
            val lastMs = timestamp.get()
            val elapsedMs = if (lastMs <= 0L) {
                Long.MAX_VALUE
            } else {
                (nowMs - lastMs).coerceAtLeast(0L)
            }
            if (elapsedMs < cooldownMs) {
                return
            }
            if (timestamp.compareAndSet(lastMs, nowMs)) {
                if (!requestRouteReverification(reason)) {
                    timestamp.compareAndSet(nowMs, lastMs)
                }
                return
            }
        }
    }

    private suspend fun verifyRouteAfterTransition(): RunningRouteResult {
        var route = diagnoseVpnRoute(resetConnections = true)
        if (shouldConfirmTransitionRouteFailure(route)) {
            delay(TRANSITION_ROUTE_CONFIRM_DELAY_MS)
            route = diagnoseVpnRoute(resetConnections = true)
        }
        return route
    }

    private suspend fun diagnoseVpnRoute(resetConnections: Boolean): RunningRouteResult {
        if (resetConnections) {
            fullVpnDataPathProbe.reset()
        }
        val fullPath = fullVpnDataPathProbe.probe()
        val xrayRoute = if (fullPath.success) {
            RunningRouteResult(success = true, latencyMs = fullPath.latencyMs)
        } else {
            if (resetConnections) {
                core.resetRouteProbeConnections()
            }
            core.verifyRunningRoute()
        }
        return classifyVpnRouteHealth(
            fullPath = fullPath,
            xrayRoute = xrayRoute,
            dialerFailure = SocketProtector.consumeDialerFailure()
        )
    }

    private fun restartTunnelAfterAppResume() {
        if (cleanupJob?.isActive == true) {
            pendingStartAfterCleanup = true
            pendingStartIntent = null
            return
        }
        if (
            vpnInterface == null &&
            core.state.value != TunnelCoreState.Connected &&
            core.state.value != TunnelCoreState.Starting &&
            core.state.value != TunnelCoreState.Stopping
        ) {
            startTunnel(null)
            return
        }
        pendingStartAfterCleanup = true
        pendingStartIntent = null
        cleanupTunnel(
            finalState = TunnelState.Starting,
            finalMessage = "Restoring VPN after app resume...",
            publishStopping = false,
            stopService = false
        )
    }

    private fun startTunnel(intent: Intent?) {
        if (shouldQueueStartRequest(cleanupJob?.isActive == true)) {
            pendingStartAfterCleanup = true
            pendingStartIntent = intent?.let(::Intent)
            publishState(
                TunnelState.Starting,
                "Finishing previous VPN cleanup, then reconnecting...",
                tunnelVerified = false
            )
            return
        }
        if (startJob?.isActive == true) {
            return
        }
        if (handleExistingTunnelStart(intent)) {
            return
        }

        val requestedRawProfile = intent?.getStringExtra(EXTRA_RAW_PROFILE_URI)
        val requestedAccountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID)
        val requestedMode = intent?.getStringExtra(EXTRA_VPN_ENGINE_MODE)
            ?.let(VpnEngineMode::fromStorage)

        createNotificationChannel()
        startForegroundCompat(buildNotification("Starting VPN"))
        publishState(TunnelState.Starting, "Checking config route, then starting full-device VPN...")
        val lifecycleToken = lifecycleGuard.begin()
        activeLifecycleToken = lifecycleToken

        startJob = scope.launch {
            lifecycleOperationMutex.withLock {
                try {
                    requestedEngineMode = requestedMode ?: vpnPreferences.getMode()
                    fastDnsEnabled = vpnPreferences.isFastDnsEnabled()
                    ipv4Only = vpnPreferences.isIpv4OnlyEnabled()
                    dpiCompatibilityEnabled = vpnPreferences.isDpiCompatibilityEnabled()
                    seamlessFailoverEnabled = vpnPreferences.isSeamlessFailoverEnabled()

                    val rawProfile = requestedRawProfile ?: restoreLastRawProfile()
                        ?: throw IllegalStateException(
                            "No valid active VLESS profile. Import and select a profile first."
                        )
                    if (intent == null && EncryptedPrefsStore(this@G2RayVpnService)
                            .getString(PREF_DESIRED_CONNECTED, "true") == "false"
                    ) {
                        withContext(Dispatchers.Main) { stopTunnel() }
                        return@withLock
                    }
                    activeAccountId = requestedAccountId ?: restoreLastAccountId()
                    val profile = VlessUriParser.parse(rawProfile).getOrElse {
                        throw IllegalStateException(
                            "No valid active VLESS profile. Import and select a profile first.",
                            it
                        )
                    }
                    if (!lifecycleGuard.isCurrent(lifecycleToken)) {
                        return@withLock
                    }
                    rememberLastProfileContext(rawProfile, activeAccountId)

                    val tun = establishTunInterface()
                    ?: throw IllegalStateException("Android refused to create the VPN interface")
                    if (!lifecycleGuard.isCurrent(lifecycleToken)) {
                        tun.close()
                        return@withLock
                    }
                    vpnInterface = tun
                    activeProfile = profile
                    registerUnderlyingNetworkMonitor()
                    if (!awaitUnderlyingNetwork(lifecycleToken, tun)) {
                        return@withLock
                    }
                    if (!isTunnelCurrent(lifecycleToken, tun)) {
                        return@withLock
                    }

                    val startupNetworkGeneration = underlyingNetworkGeneration.get()
                    var route = startBestEngineAndVerify(profile, tun, lifecycleToken)
                    if (!isTunnelCurrent(lifecycleToken, tun)) {
                        return@withLock
                    }
                    if (
                        shouldReverifyAfterStartupNetworkChange(
                            routeSuccess = route.success,
                            startupNetworkGeneration = startupNetworkGeneration,
                            currentNetworkGeneration = underlyingNetworkGeneration.get()
                        )
                    ) {
                        route = verifyRouteAfterTransition()
                    }
                    if (!route.success) {
                        markTunnelUnverified("${route.error ?: "Tunnel route check failed"} Recovering...")
                        route = recoverRouteWithWorker(
                            profile,
                            tun,
                            lifecycleToken,
                            route.error ?: "Tunnel route check failed",
                            route.failureScope
                        )
                    }

                    withContext(Dispatchers.Main) {
                        if (!isTunnelCurrent(lifecycleToken, tun)) {
                            return@withContext
                        }
                        if (route.success) {
                            markTunnelVerified("VPN connected and route verified")
                        } else {
                            if (route.retryAllowed) {
                                schedulePersistentReconnect(route.error ?: core.lastError.value ?: "Tunnel route check failed", route.retryAfterMs)
                            } else failAndStop(route.error ?: "VPN needs configuration changes")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (lifecycleGuard.isCurrent(lifecycleToken)) {
                        withContext(Dispatchers.Main) {
                            if (lifecycleGuard.isCurrent(lifecycleToken)) {
                                if (e is IOException && vpnInterface != null && activeProfile != null) {
                                    schedulePersistentReconnect(e.localizedMessage ?: "Network unavailable")
                                } else failAndStop(e.localizedMessage ?: e.javaClass.simpleName)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleExistingTunnelStart(intent: Intent?): Boolean {
        val coreState = core.state.value
        val hasExistingRuntime = vpnInterface != null ||
            coreState == TunnelCoreState.Connected ||
            coreState == TunnelCoreState.Starting ||
            coreState == TunnelCoreState.Stopping
        if (!hasExistingRuntime) return false

        when (
            appResumeAction(
                coreState = coreState,
                hasVpnInterface = vpnInterface != null,
                hasActiveProfile = activeProfile != null,
                cleanupActive = cleanupJob?.isActive == true
            )
        ) {
            AppResumeAction.KeepAlive -> {
                publishState(
                    TunnelState.Starting,
                    "VPN startup is already in progress...",
                    tunnelVerified = false
                )
            }

            AppResumeAction.VerifyRoute -> {
                val verified = VpnStateRepository.state.value.tunnelVerified
                if (verified) {
                    publishState(
                        TunnelState.Connected,
                        "VPN already running",
                        tunnelVerified = true
                    )
                } else {
                    markTunnelUnverified("VPN route is still being verified...")
                    requestRouteReverification(RouteReverifyReason.DeviceWake)
                }
            }

            AppResumeAction.RecoverCore -> {
                markTunnelUnverified("VPN core stopped. Recovering now...")
                scheduleRouteRecovery(
                    core.lastError.value ?: "VPN core stopped while the tunnel interface remained active"
                )
            }

            AppResumeAction.QueueRestart,
            AppResumeAction.RestartTunnel -> {
                pendingStartAfterCleanup = true
                pendingStartIntent = intent?.let(::Intent)
                cleanupTunnel(
                    finalState = TunnelState.Starting,
                    finalMessage = "Repairing incomplete VPN state...",
                    publishStopping = false,
                    stopService = false
                )
            }
        }
        return true
    }

    private fun establishTunInterface(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("G2Ray")
            .setMtu(XrayConfigBuilder.VPN_TUN_MTU)
            .addAddress("198.18.0.1", 15)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setBlocking(false)

        if (!ipv4Only) {
            builder
                .addAddress("fc00::1", 64)
                .addRoute("::", 0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { builder.setMetered(false) }
        }
        return builder.establish()
    }

    private fun stopTunnel() {
        desiredConnected = false
        pendingStartAfterCleanup = false
        pendingStartIntent = null
        cleanupTunnel(
            finalState = TunnelState.Disconnected,
            finalMessage = "Disconnected",
            publishStopping = true,
            stopService = true,
            clearRememberedProfile = true
        )
    }

    private fun failAndStop(message: String) {
        desiredConnected = false
        updateNotification("VPN failed")
        cleanupTunnel(
            finalState = TunnelState.Error,
            finalMessage = message,
            publishStopping = false,
            stopService = true
        )
    }

    private fun cleanupTunnel(
        finalState: TunnelState,
        finalMessage: String,
        publishStopping: Boolean,
        stopService: Boolean,
        clearRememberedProfile: Boolean = false
    ) {
        pendingCleanupFinalState = finalState
        pendingCleanupFinalMessage = finalMessage
        pendingCleanupStopService = pendingCleanupStopService || stopService
        clearRememberedProfileAfterCleanup =
            clearRememberedProfileAfterCleanup || clearRememberedProfile

        if (cleanupJob?.isActive == true) {
            return
        }

        activeLifecycleToken = lifecycleGuard.invalidate()
        startJob?.cancel()
        startJob = null
        trafficJob?.cancel()
        trafficJob = null
        healthJob?.cancel()
        healthJob = null
        routeRecoveryJob?.cancel()
        routeRecoveryJob = null
        reconnectRetryJob?.cancel()
        reconnectRetryJob = null
        // Cancellation may happen before recovery enters its try/finally block.
        routeRecoveryInProgress = false
        routeReverifyJob?.cancel()
        routeReverifyJob = null
        networkHandoffJob?.cancel()
        networkHandoffJob = null
        unregisterUnderlyingNetworkMonitor()
        synchronized(sessionProfileHealth) {
            sessionProfileHealth.clear()
        }
        val tunToClose = vpnInterface
        vpnInterface = null
        activeProfile = null
        speedSampler = null

        if (publishStopping) {
            publishState(TunnelState.Stopping, "Stopping VPN...")
        }

        cleanupJob = scope.launch {
            lifecycleOperationMutex.withLock {
                if (!desiredConnected) {
                    runCatching { EncryptedPrefsStore(this@G2RayVpnService).putString(PREF_DESIRED_CONNECTED, "false") }
                }
                runCatching { fullVpnDataPathProbe.reset() }
                runCatching { stopActivePacketEngine() }
                runCatching { core.stop() }
                runCatching {
                    tunToClose?.close()
                }
            }

            withContext(Dispatchers.Main) {
                if (clearRememberedProfileAfterCleanup) {
                    withContext(Dispatchers.IO) {
                        clearLastRawProfile()
                        clearRememberedProfileAfterCleanup = false
                    }
                }
                val stateAfterCleanup = pendingCleanupFinalState
                val messageAfterCleanup = pendingCleanupFinalMessage
                val shouldStopService = pendingCleanupStopService
                pendingCleanupStopService = false
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
                publishState(stateAfterCleanup, messageAfterCleanup)
                val restartAfterCleanup = pendingStartAfterCleanup
                val restartIntent = pendingStartIntent
                pendingStartAfterCleanup = false
                pendingStartIntent = null
                cleanupJob = null
                if (restartAfterCleanup) {
                    startTunnel(restartIntent)
                } else if (shouldStopService) {
                    stopSelf()
                }
            }
        }
    }

    private fun publishState(
        tunnelState: TunnelState,
        message: String,
        tunnelVerified: Boolean = tunnelState == TunnelState.Connected
    ) {
        val resetSpeed = tunnelState != TunnelState.Connected || !tunnelVerified
        val routeState = when {
            tunnelState == TunnelState.Error -> RouteState.Error
            tunnelState == TunnelState.Connected && tunnelVerified -> RouteState.Ready
            tunnelState == TunnelState.Disconnected || tunnelState == TunnelState.Disabled -> RouteState.Unknown
            else -> RouteState.Settling
        }
        val hasMeasuredTunnelTraffic = tunnelState == TunnelState.Connected &&
            tunnelVerified && activeEngine == ActiveVpnEngine.Hev && hevTunnelRunning
        VpnStateRepository.state.update { previous ->
            previous.copy(
                routeState = routeState,
                tunnelState = tunnelState,
                message = message,
                routeReady = tunnelState == TunnelState.Connected && tunnelVerified,
                tunnelVerified = tunnelVerified,
                downloadBps = if (resetSpeed || !hasMeasuredTunnelTraffic) 0 else previous.downloadBps,
                uploadBps = if (resetSpeed || !hasMeasuredTunnelTraffic) 0 else previous.uploadBps,
                totalRxBytes = if (resetSpeed || !hasMeasuredTunnelTraffic) 0 else previous.totalRxBytes,
                totalTxBytes = if (resetSpeed || !hasMeasuredTunnelTraffic) 0 else previous.totalTxBytes,
                activeVpnEngine = when (tunnelState) {
                    TunnelState.Disabled, TunnelState.Disconnected, TunnelState.Error -> null
                    else -> activeEngine
                },
                tunnelTrafficSource = if (hasMeasuredTunnelTraffic) {
                    TunnelTrafficSource.HevTun
                } else {
                    TunnelTrafficSource.Unavailable
                }
            )
        }
    }

    private fun isTunnelCurrent(token: Long, tun: ParcelFileDescriptor): Boolean {
        return lifecycleGuard.isCurrent(token) &&
            cleanupJob?.isActive != true &&
            vpnInterface === tun
    }

    private fun publishSpeed(speed: TrafficSpeed) {
        VpnStateRepository.state.updateTunnelTraffic(speed)
    }

    private fun startTrafficUpdates() {
        if (activeEngine != ActiveVpnEngine.Hev || !hevTunnelRunning) {
            return
        }
        if (trafficJob?.isActive == true) {
            return
        }
        val sampler = TrafficSpeedSampler(HevTunnelByteSource())
        speedSampler = sampler
        trafficJob = scope.launch {
            var notificationTick = 0
            var lastTrafficAtMs = SystemClock.elapsedRealtime()
            val initialSpeed = sampler.sample()
            var payloadBytes = HevTunController.statsOrNull()?.downloadTcpPayloadBytes ?: 0L
            var activeStallWindow = ActiveStallWindow(
                lastRxBytes = payloadBytes,
                lastTxBytes = initialSpeed.totalTxBytes,
                lastRxPackets = 0L,
                lastTxPackets = initialSpeed.txPackets,
                txBytesAtLastRxProgress = initialSpeed.totalTxBytes,
                txPacketsAtLastRxProgress = initialSpeed.txPackets,
                lastRxProgressAtMs = lastTrafficAtMs
            )
            while (isActive && core.state.value == TunnelCoreState.Connected) {
                delay(1000)
                val speed = sampler.sample()
                val nowMs = SystemClock.elapsedRealtime()
                if (!speed.sampleValid) {
                    // Native HEV counters can be momentarily unavailable during
                    // a handoff. Unknown is not zero traffic: rebase detection
                    // and preserve the last honest UI/notification measurement.
                    lastTrafficAtMs = nowMs
                    activeStallWindow = ActiveStallWindow(
                        lastRxBytes = payloadBytes,
                        lastTxBytes = speed.totalTxBytes,
                        lastRxPackets = 0L,
                        lastTxPackets = speed.txPackets,
                        txBytesAtLastRxProgress = speed.totalTxBytes,
                        txPacketsAtLastRxProgress = speed.txPackets,
                        lastRxProgressAtMs = nowMs
                    )
                    continue
                }
                val idleDurationMs = (nowMs - lastTrafficAtMs).coerceAtLeast(0L)
                if (
                    shouldReverifyAfterIdleTraffic(
                        engine = activeEngine,
                        idleDurationMs = idleDurationMs,
                        rxBps = speed.rxBps,
                        txBps = speed.txBps,
                        timeSinceLastReverifyMs = timeSinceLastPromptReverify(
                            RouteReverifyReason.IdleTraffic,
                            nowMs
                        )
                    )
                ) {
                    requestPromptRouteReverification(RouteReverifyReason.IdleTraffic, nowMs)
                }
                payloadBytes = HevTunController.statsOrNull()?.downloadTcpPayloadBytes ?: payloadBytes
                activeStallWindow = advanceActiveStallWindow(
                    current = activeStallWindow,
                    rxBytes = payloadBytes,
                    txBytes = speed.totalTxBytes,
                    rxPackets = 0L,
                    txPackets = speed.txPackets,
                    nowMs = nowMs
                )
                if (
                    !waitingForUnderlyingNetwork &&
                    shouldProbeSuspectedActiveStall(
                        engine = activeEngine,
                        txBytesSinceLastRx =
                            (speed.totalTxBytes - activeStallWindow.txBytesAtLastRxProgress)
                                .coerceAtLeast(0L),
                        txPacketsSinceLastRx =
                            (speed.txPackets - activeStallWindow.txPacketsAtLastRxProgress)
                                .coerceAtLeast(0L),
                        timeSinceLastRxMs =
                            (nowMs - activeStallWindow.lastRxProgressAtMs).coerceAtLeast(0L),
                        timeSinceLastReverifyMs = timeSinceLastPromptReverify(
                            RouteReverifyReason.ActiveStall,
                            nowMs
                        )
                    )
                ) {
                    // Counters only identify suspicion. The existing fresh
                    // quorum probe decides whether recovery is warranted.
                    requestPromptRouteReverification(RouteReverifyReason.ActiveStall, nowMs)
                }
                if (speed.rxBps > 0L || speed.txBps > 0L) {
                    lastTrafficAtMs = nowMs
                }
                withContext(Dispatchers.Main) {
                    notificationTick++
                    if (notificationTick >= 4) {
                        notificationTick = 0
                        updateNotification(SpeedFormatter.formatNotification(speed))
                    }
                    publishSpeed(speed)
                }
            }
        }
    }

    private fun startCoreHealthMonitor() {
        if (healthJob?.isActive == true) {
            return
        }
        healthJob = scope.launch {
            var routeFailureCount = 0
            var nextRouteHealthCheckAt = SystemClock.elapsedRealtime() + ROUTE_HEALTH_CHECK_INTERVAL_MS
            var previousMeasuredTraffic = currentMeasuredTrafficSnapshot()
            while (isActive && core.state.value == TunnelCoreState.Connected) {
                delay(CORE_STATE_CHECK_INTERVAL_MS)
                if (!isActive || routeRecoveryInProgress || core.state.value != TunnelCoreState.Connected) {
                    break
                }
                // Startup, handoff and prompt re-verification share these probe
                // clients. Skip this poll while they own them instead of
                // cancelling one another's requests or queuing stale checks.
                if (!lifecycleOperationMutex.tryLock()) {
                    continue
                }
                try {
                    if (shouldPauseRouteHealthChecks(waitingForUnderlyingNetwork)) {
                        routeFailureCount = 0
                        previousMeasuredTraffic = currentMeasuredTrafficSnapshot()
                        nextRouteHealthCheckAt = SystemClock.elapsedRealtime() + ROUTE_HEALTH_CHECK_INTERVAL_MS
                        continue
                    }
                    if (
                        packetEngineRuntimeStopped(
                            engine = activeEngine,
                            packetEngineExpected = hevTunnelRunning,
                            nativeRuntimeRunning = HevTunController.isRunning()
                        )
                    ) {
                        scheduleRouteRecovery(
                            reason = "HEV packet engine stopped unexpectedly",
                            failureScope = RouteFailureScope.PacketEngine
                        )
                        break
                    }
                    if (!core.refreshRunningState()) {
                        scheduleRouteRecovery(core.lastError.value ?: "Xray stopped unexpectedly")
                        break
                    }
                    val dialerFailure = SocketProtector.consumeDialerFailure()
                    if (!dialerFailure.isNullOrBlank()) {
                        scheduleRouteRecovery(
                            dialerFailure,
                            failureScope = RouteFailureScope.DialerBinding
                        )
                        break
                    }

                    if (SystemClock.elapsedRealtime() >= nextRouteHealthCheckAt) {
                        val networkGeneration = underlyingNetworkGeneration.get()
                        val route = diagnoseVpnRoute(resetConnections = false)
                        if (!isActive || routeRecoveryInProgress || core.state.value != TunnelCoreState.Connected) {
                            break
                        }
                        val currentMeasuredTraffic = currentMeasuredTrafficSnapshot()
                        val trafficIsAdvancing = shouldSuppressRouteRecoveryAfterFailedReverification(
                            route, activeEngine, previousMeasuredTraffic, currentMeasuredTraffic
                        )
                        previousMeasuredTraffic = currentMeasuredTraffic
                        if (
                            shouldPauseRouteHealthChecks(waitingForUnderlyingNetwork) ||
                            networkGeneration != underlyingNetworkGeneration.get()
                        ) {
                            routeFailureCount = 0
                            nextRouteHealthCheckAt =
                                SystemClock.elapsedRealtime() + ROUTE_HEALTH_CHECK_INTERVAL_MS
                            continue
                        }
                        if (route.success) {
                            routeFailureCount = 0
                        } else {
                            routeFailureCount++
                            if (
                                shouldRecoverImmediatelyForLocalSocksFailure(route.error) ||
                                routeHealthFailureRequiresRecovery(routeFailureCount, trafficIsAdvancing)
                            ) {
                                scheduleRouteRecovery(
                                    route.error ?: "Tunnel route stopped responding",
                                    failureScope = route.failureScope
                                )
                                break
                            } else {
                                // Confirm a possible stale probe socket before recovery.
                                core.resetRouteProbeConnections()
                                fullVpnDataPathProbe.reset()
                            }
                        }
                        nextRouteHealthCheckAt = SystemClock.elapsedRealtime() +
                            routeHealthCheckDelayMs(
                                routeSuccess = route.success,
                                trafficIsAdvancing = trafficIsAdvancing,
                                routeFailureCount = routeFailureCount
                            )
                    }
                } finally {
                    lifecycleOperationMutex.unlock()
                }
            }
        }
    }

    private fun currentMeasuredTrafficSnapshot(): TrafficBytes? {
        val state = VpnStateRepository.state.value
        if (activeEngine != ActiveVpnEngine.Hev || state.tunnelTrafficSource != TunnelTrafficSource.HevTun) {
            return null
        }
        // Keep both sides of a comparison in HEV's absolute counter domain.
        // UI totals are baseline-relative, so mixing them after one transient
        // native read failure could look like traffic advanced when it did not.
        return HevTunnelByteSource().snapshot()
    }

    private fun registerIdleTransitionMonitor() {
        if (idleTransitionReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    idleTransitionReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(idleTransitionReceiver, filter)
            }
        }.onSuccess {
            idleTransitionReceiverRegistered = true
            if (getSystemService(PowerManager::class.java)?.isInteractive == false) {
                screenOffAtMs.compareAndSet(0L, SystemClock.elapsedRealtime())
            }
        }
    }

    private fun unregisterIdleTransitionMonitor() {
        if (!idleTransitionReceiverRegistered) return
        idleTransitionReceiverRegistered = false
        runCatching { unregisterReceiver(idleTransitionReceiver) }
        screenOffAtMs.set(0L)
    }

    private fun markTunnelVerified(message: String) {
        if (vpnInterface == null || core.state.value != TunnelCoreState.Connected) {
            return
        }
        speedSampler = null
        reconnectRetryJob?.cancel()
        reconnectRetryJob = null
        publishState(TunnelState.Connected, message, tunnelVerified = true)
        updateNotification(
            if (activeEngine == ActiveVpnEngine.Hev) {
                "VPN connected (HEV route verified)"
            } else {
                "VPN connected (native route verified)"
            }
        )
        startTrafficUpdates()
        startCoreHealthMonitor()
    }

    private fun markTunnelUnverified(message: String) {
        trafficJob?.cancel()
        trafficJob = null
        speedSampler = null
        publishState(TunnelState.Starting, message, tunnelVerified = false)
        updateNotification(message)
    }

    /**
     * Starts the requested packet engine and proves the full-device data path,
     * then lets Auto fall back once to native Xray TUN only when HEV itself
     * failed to become healthy. A shared upstream route failure is handled by
     * normal route recovery so exact HEV traffic counters remain available.
     */
    private suspend fun startBestEngineAndVerify(
        profile: G2RayProfile,
        tun: ParcelFileDescriptor,
        lifecycleToken: Long,
        preferredEngine: ActiveVpnEngine? = null
    ): RunningRouteResult {
        if (!isTunnelCurrent(lifecycleToken, tun)) {
            return RunningRouteResult(false, "VPN lifecycle changed before engine startup")
        }
        val selectedEngine = preferredEngine ?: VpnEngineSelector.resolve(
            requestedEngineMode,
            HevTunController.isAvailable()
        )
        val firstResult = startEngineAndVerify(profile, tun, selectedEngine, lifecycleToken)
        if (
            firstResult.success ||
            !VpnEngineSelector.shouldFallbackToNative(
                mode = requestedEngineMode,
                active = selectedEngine,
                activePacketEngineReady = hevTunnelRunning
            )
        ) {
            return firstResult
        }

        if (!isTunnelCurrent(lifecycleToken, tun)) {
            return RunningRouteResult(false, "VPN lifecycle changed before engine fallback")
        }
        stopActivePacketEngine()
        runCatching { core.stop() }
        delay(250)
        if (!isTunnelCurrent(lifecycleToken, tun)) {
            return RunningRouteResult(false, "VPN lifecycle changed during engine fallback")
        }
        return startEngineAndVerify(profile, tun, ActiveVpnEngine.XrayNative, lifecycleToken)
    }

    private suspend fun startEngineAndVerify(
        profile: G2RayProfile,
        tun: ParcelFileDescriptor,
        engine: ActiveVpnEngine,
        lifecycleToken: Long
    ): RunningRouteResult {
        if (!isTunnelCurrent(lifecycleToken, tun)) {
            return RunningRouteResult(false, "VPN lifecycle changed before engine startup")
        }
        activeEngine = engine
        hevTunnelRunning = false
        val runtimeProfiles = resolveRuntimeProfiles(liveRouteProfilesFor(profile), SocketProtector::resolveTunnelEndpoint)
        val runtimeProfile = runtimeProfiles.first()
        SocketProtector.clearDialerFailure()
        core.start(
            profile = runtimeProfile,
            tunFd = tun.fd,
            datDir = filesDir.absolutePath,
            protectSocket = SocketProtector::protectAndBindDialer,
            protectListenerSocket = SocketProtector::protect,
            engine = engine,
            fakeDnsEnabled = fastDnsEnabled,
            ipv4Only = ipv4Only,
            dpiCompatibilityEnabled = dpiCompatibilityEnabled,
            fallbackProfiles = runtimeProfiles.drop(1)
        )
        if (!isTunnelCurrent(lifecycleToken, tun)) {
            return RunningRouteResult(false, "VPN lifecycle changed while Xray was starting")
        }
        if (core.state.value != TunnelCoreState.Connected) {
            return RunningRouteResult(false, core.lastError.value ?: "Xray failed to start")
        }

        if (engine == ActiveVpnEngine.Hev) {
            val socksPort = core.activeSocksPort()
                ?: return RunningRouteResult(false, "HEV SOCKS listener was not initialized")
            try {
                HevTunController.start(
                    filesDir = filesDir,
                    tunFd = tun.fd,
                    socksPort = socksPort,
                    mtu = XrayConfigBuilder.VPN_TUN_MTU,
                    ipv4Only = ipv4Only
                )
                hevTunnelRunning = true
                // Give the native forwarding loop a moment to attach before the
                // control-plane probe decides whether Auto should fall back.
                delay(250)
                if (!isTunnelCurrent(lifecycleToken, tun)) {
                    return RunningRouteResult(false, "VPN lifecycle changed while HEV was starting")
                }
                if (HevTunController.statsOrNull() == null) {
                    stopActivePacketEngine()
                    return RunningRouteResult(false, "HEV TUN started but did not expose a healthy native statistics channel")
                }
            } catch (e: CancellationException) {
                stopActivePacketEngine()
                throw e
            } catch (e: Exception) {
                stopActivePacketEngine()
                return RunningRouteResult(
                    false,
                    "HEV TUN could not start: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }
        }

        fullVpnDataPathProbe.reset()
        var fullPath = fullVpnDataPathProbe.probe()
        if (!fullPath.success && engine == ActiveVpnEngine.Hev) {
            delay(400)
            fullVpnDataPathProbe.reset()
            fullPath = fullVpnDataPathProbe.probe()
        }
        if (fullPath.success) return fullPath
        // A successful TUN proof already includes SOCKS/core/remote transport.
        // Probe SOCKS separately only to diagnose a failed full-device path.
        val xrayRoute = core.verifyRunningRoute()
        val diagnosed = classifyVpnRouteHealth(
            fullPath = fullPath,
            xrayRoute = xrayRoute,
            dialerFailure = SocketProtector.consumeDialerFailure()
        )
        if (!diagnosed.success && diagnosed.failureScope == RouteFailureScope.PacketEngine && engine == ActiveVpnEngine.Hev) {
            stopActivePacketEngine()
        }
        return diagnosed
    }

    private fun liveRouteProfilesFor(selected: G2RayProfile): List<G2RayProfile> {
        if (!seamlessFailoverEnabled) return listOf(selected)
        val accountId = activeAccountId ?: accountRepository.activeSelection.value.accountId
        val sessionHealth = sessionProfileHealthSnapshot()
        val nowMs = System.currentTimeMillis()
        val storedCandidates = selectAlternativeProfiles(
            profiles = accountRepository.profiles.value,
            accountId = accountId,
            currentRawUri = selected.rawUri,
            maxCandidates = 10,
            sessionHealth = sessionHealth
        )
        val verifiedCandidates = storedCandidates.mapNotNull { stored ->
            val observed = sessionHealth[stored.rawUri]
            val verified = observed?.lastAttemptSucceeded == true ||
                persistedRouteMeasurementFresh(stored, nowMs)
            if (!verified) return@mapNotNull null
            VlessUriParser.parse(stored.rawUri).getOrNull()
        }
        return selectSeamlessRouteProfiles(selected, verifiedCandidates)
    }

    private fun stopActivePacketEngine() {
        if (hevTunnelRunning) {
            HevTunController.stop()
        }
        hevTunnelRunning = false
    }

    private data class WakeRecoveryConfig(
        val request: CodespaceWakeRequest,
        val workerApiKeyId: String?,
        val githubApiKeyId: String?
    )

    private fun activeWakeRecoveryConfig(accountId: String? = activeAccountId): WakeRecoveryConfig? {
        val selection = accountRepository.activeSelection.value
        val account = accountRepository.accounts.value.firstOrNull { it.id == accountId }
            ?: accountRepository.accounts.value.firstOrNull { it.id == selection.accountId }
            ?: return null
        val workerKey = accountRepository.apiKeys.value.firstOrNull {
            it.id == selection.apiKeyId && it.accountId == account.id && it.enabled &&
                it.kind == ApiKeyKind.WORKER_WAKE_SECRET
        } ?: accountRepository.enabledApiKey(account.id, ApiKeyKind.WORKER_WAKE_SECRET)
        val githubKey = accountRepository.enabledApiKey(account.id, ApiKeyKind.GITHUB_TOKEN)
        val controlPlane = accountRepository.workerControlPlane.value
        val workerBaseUrls = controlPlane?.workerBaseUrls()?.takeIf { it.isNotEmpty() }
            ?: account.workerBaseUrls()
        val secret = controlPlane?.encryptedWakeSecret?.takeIf { it.isNotBlank() }
            ?: workerKey?.encryptedValue
        val request = CodespaceWakeRequest(
            mode = account.wakeMode,
            githubCodespaceName = account.codespaceName.orEmpty(),
            workerCodespaceId = account.workerCodespaceId ?: account.codespaceName,
            workerBaseUrls = workerBaseUrls,
            workerSecret = secret,
            githubToken = githubKey?.encryptedValue
        )
        if (
            WakeBackendSelector.order(
                mode = request.mode,
                workerConfigured = request.workerConfigured,
                githubDirectConfigured = request.githubDirectConfigured
            ).isEmpty()
        ) {
            return null
        }
        return WakeRecoveryConfig(
            request = request,
            workerApiKeyId = workerKey?.id,
            githubApiKeyId = githubKey?.id
        )
    }

    private suspend fun restartRouteOnce(
        profile: G2RayProfile,
        tun: ParcelFileDescriptor,
        lifecycleToken: Long
    ): RunningRouteResult {
        if (!isTunnelCurrent(lifecycleToken, tun)) {
            return RunningRouteResult(false, "VPN lifecycle changed during route recovery")
        }
        runCatching { stopActivePacketEngine() }
        runCatching { core.stop() }
        delay(250)
        if (!isTunnelCurrent(lifecycleToken, tun)) {
            return RunningRouteResult(false, "VPN lifecycle changed during route recovery")
        }
        val route = startBestEngineAndVerify(profile, tun, lifecycleToken)
        if (isTunnelCurrent(lifecycleToken, tun)) {
            recordSessionProfileHealth(profile.rawUri, route)
        }
        return route
    }

    private suspend fun restartHevDataPathOnce(
        tun: ParcelFileDescriptor,
        lifecycleToken: Long
    ): RunningRouteResult {
        if (
            activeEngine != ActiveVpnEngine.Hev ||
            !isTunnelCurrent(lifecycleToken, tun) ||
            core.state.value != TunnelCoreState.Connected
        ) {
            return RunningRouteResult(
                false,
                "HEV-only repair is not available for the current tunnel",
                failureScope = RouteFailureScope.PacketEngine
            )
        }
        val socksPort = core.activeSocksPort() ?: return RunningRouteResult(
            false,
            "HEV-only repair could not access Xray's local SOCKS listener",
            failureScope = RouteFailureScope.PacketEngine
        )

        stopActivePacketEngine()
        delay(150)
        if (!isTunnelCurrent(lifecycleToken, tun)) {
            return RunningRouteResult(false, "VPN lifecycle changed during HEV repair")
        }
        return try {
            HevTunController.start(
                filesDir = filesDir,
                tunFd = tun.fd,
                socksPort = socksPort,
                mtu = XrayConfigBuilder.VPN_TUN_MTU,
                ipv4Only = ipv4Only
            )
            hevTunnelRunning = true
            delay(250)
            if (!isTunnelCurrent(lifecycleToken, tun)) {
                return RunningRouteResult(false, "VPN lifecycle changed while HEV was restarting")
            }
            if (HevTunController.statsOrNull() == null) {
                stopActivePacketEngine()
                return RunningRouteResult(
                    false,
                    "HEV restarted but its native statistics channel is unavailable",
                    failureScope = RouteFailureScope.PacketEngine
                )
            }
            fullVpnDataPathProbe.reset()
            val fullPath = fullVpnDataPathProbe.probe()
            val diagnosed = classifyVpnRouteHealth(
                fullPath = fullPath,
                xrayRoute = RunningRouteResult(success = true),
                dialerFailure = SocketProtector.consumeDialerFailure()
            )
            if (!diagnosed.success) {
                stopActivePacketEngine()
                diagnosed
            } else {
                diagnosed
            }
        } catch (error: CancellationException) {
            stopActivePacketEngine()
            throw error
        } catch (error: Exception) {
            stopActivePacketEngine()
            RunningRouteResult(
                false,
                "HEV-only repair failed: ${error.localizedMessage ?: error.javaClass.simpleName}",
                failureScope = RouteFailureScope.PacketEngine
            )
        }
    }

    private fun recordSessionProfileHealth(rawUri: String, route: RunningRouteResult) {
        val key = rawUri.trim()
        if (key.isEmpty()) return
        val nowMs = SystemClock.elapsedRealtime()
        synchronized(sessionProfileHealth) {
            val fresh = freshSessionProfileHealth(sessionProfileHealth, nowMs)
            sessionProfileHealth.clear()
            sessionProfileHealth.putAll(fresh)
            sessionProfileHealth[key] = updatedSessionProfileHealth(
                previous = sessionProfileHealth[key],
                success = route.success,
                latencyMs = route.latencyMs,
                observedAtMs = nowMs
            )
        }
    }

    private fun sessionProfileHealthSnapshot(): Map<String, SessionProfileHealth> {
        val nowMs = SystemClock.elapsedRealtime()
        return synchronized(sessionProfileHealth) {
            val fresh = freshSessionProfileHealth(sessionProfileHealth, nowMs)
            sessionProfileHealth.clear()
            sessionProfileHealth.putAll(fresh)
            fresh
        }
    }

    private suspend fun tryAlternativeStoredProfiles(
        currentProfile: G2RayProfile,
        tun: ParcelFileDescriptor,
        lifecycleToken: Long,
        maxCandidates: Int = 6
    ): RunningRouteResult? {
        val accountId = activeAccountId ?: accountRepository.activeSelection.value.accountId
        val candidates = selectAlternativeProfiles(
            profiles = accountRepository.profiles.value,
            accountId = accountId,
            currentRawUri = currentProfile.rawUri,
            maxCandidates = maxCandidates,
            sessionHealth = sessionProfileHealthSnapshot()
        )

        var lastFailure: RunningRouteResult? = null
        for (stored in candidates) {
            if (!isTunnelCurrent(lifecycleToken, tun)) {
                return RunningRouteResult(false, "VPN lifecycle changed while selecting a backup route")
            }
            val parsed = VlessUriParser.parse(stored.rawUri).getOrNull() ?: continue
            markTunnelUnverified("Trying backup route ${stored.displayName}...")
            val route = restartRouteOnce(parsed, tun, lifecycleToken)
            if (route.success) {
                activeProfile = parsed
                rememberLastProfileContext(stored.rawUri, accountId)
                runCatching { accountRepository.selectProfile(stored.id) }
                return route
            }
            lastFailure = route
        }
        return lastFailure
    }

    private suspend fun waitForDirectRouteAfterStart(
        profile: G2RayProfile,
        tun: ParcelFileDescriptor,
        lifecycleToken: Long,
        initialError: String
    ): RunningRouteResult {
        val startedAt = SystemClock.elapsedRealtime()
        var attempt = 0
        var lastError = initialError
        while (
            isTunnelCurrent(lifecycleToken, tun) &&
            SystemClock.elapsedRealtime() - startedAt < DIRECT_ROUTE_SETTLE_TIMEOUT_MS
        ) {
            if (!awaitUnderlyingNetwork(lifecycleToken, tun)) {
                return RunningRouteResult(false, "VPN lifecycle changed while waiting for an underlying network")
            }
            attempt++
            markTunnelUnverified("Codespace is available; verifying the selected route (attempt $attempt)...")
            val route = if (attempt == 1 || attempt % 5 == 0) {
                restartRouteOnce(profile, tun, lifecycleToken)
            } else {
                diagnoseVpnRoute(resetConnections = true)
            }
            if (route.success) return route
            lastError = route.error ?: lastError
            delay(DIRECT_ROUTE_PROBE_INTERVAL_MS)
        }
        return RunningRouteResult(
            false,
            "$lastError The Codespace became available, but the selected route did not become usable within ${DIRECT_ROUTE_SETTLE_TIMEOUT_MS / 1000}s."
        )
    }

    private suspend fun recoverRouteWithWorker(
        profile: G2RayProfile,
        tun: ParcelFileDescriptor,
        lifecycleToken: Long,
        initialError: String,
        initialFailureScope: RouteFailureScope = RouteFailureScope.Unknown
    ): RunningRouteResult {
        val retryCooldown = WakeRetryCooldown(SystemClock::elapsedRealtime)
        return withTimeoutOrNull(WORKER_ROUTE_RECOVERY_TIMEOUT_MS) {
            recoverRouteWithinDeadline(profile, tun, lifecycleToken, initialError, initialFailureScope, retryCooldown)
        } ?: RunningRouteResult(
            false, "Tunnel recovery deadline reached; automatic retry remains enabled",
            retryAfterMs = retryCooldown.remainingMs()
        )
    }

    private suspend fun recoverRouteWithinDeadline(
        profile: G2RayProfile,
        tun: ParcelFileDescriptor,
        lifecycleToken: Long,
        initialError: String,
        initialFailureScope: RouteFailureScope,
        retryCooldown: WakeRetryCooldown
    ): RunningRouteResult {
        var recoveryError = initialError
        while (isTunnelCurrent(lifecycleToken, tun)) {
            if (!awaitUnderlyingNetwork(lifecycleToken, tun)) {
                break
            }
            val recoveryNetworkGeneration = underlyingNetworkGeneration.get()
            val route = recoverRouteWithWorkerAttempt(
                profile = profile,
                tun = tun,
                lifecycleToken = lifecycleToken,
                initialError = recoveryError,
                initialFailureScope = initialFailureScope,
                retryCooldown = retryCooldown
            )
            if (route.success || !route.retryAllowed || route.retryAfterMs != null) return route
            if (
                !shouldRetryAfterNetworkChange(
                    recoveryNetworkGeneration = recoveryNetworkGeneration,
                    currentNetworkGeneration = underlyingNetworkGeneration.get(),
                    waitingForUnderlyingNetwork = waitingForUnderlyingNetwork
                )
            ) {
                return route
            }
            recoveryError = route.error ?: recoveryError
        }
        return RunningRouteResult(false, "VPN lifecycle changed while waiting for an underlying network")
    }

    private suspend fun recoverRouteWithWorkerAttempt(
        profile: G2RayProfile,
        tun: ParcelFileDescriptor,
        lifecycleToken: Long,
        initialError: String,
        initialFailureScope: RouteFailureScope,
        retryCooldown: WakeRetryCooldown
    ): RunningRouteResult {
        var lastError = initialError
        if (initialFailureScope == RouteFailureScope.PacketEngine && activeEngine == ActiveVpnEngine.Hev) {
            markTunnelUnverified("The Android packet path stalled; restarting HEV without waking Codespaces...")
            val hevRepair = restartHevDataPathOnce(tun, lifecycleToken)
            if (hevRepair.success) return hevRepair
            lastError = hevRepair.error ?: lastError
        }
        repeat(MAX_LOCAL_ROUTE_RECOVERY_ATTEMPTS) { attempt ->
            if (!awaitUnderlyingNetwork(lifecycleToken, tun)) {
                return RunningRouteResult(false, "VPN lifecycle changed while waiting for an underlying network")
            }
            val route = restartRouteOnce(profile, tun, lifecycleToken)
            if (route.success) return route
            lastError = route.error ?: lastError
            if (route.failureScope == RouteFailureScope.DialerBinding) {
                return route
            }
            if (attempt + 1 < MAX_LOCAL_ROUTE_RECOVERY_ATTEMPTS) delay(1_500)
        }

        // If only the selected phone-to-edge path failed, a measured backup
        // can recover immediately. One backup replaces the redundant second
        // restart, so a genuine Codespace cold start is not made longer.
        val fastAlternative = tryAlternativeStoredProfiles(
            currentProfile = profile,
            tun = tun,
            lifecycleToken = lifecycleToken,
            maxCandidates = PRE_WAKE_ALTERNATIVE_ATTEMPTS
        )
        if (fastAlternative?.success == true) return fastAlternative
        lastError = fastAlternative?.error ?: lastError

        val wake = activeWakeRecoveryConfig(activeAccountId)
        if (wake == null) {
            return tryAlternativeStoredProfiles(profile, tun, lifecycleToken)
                ?: RunningRouteResult(false, "$lastError No Codespace wake method is configured.")
        }
        val outcome = codespaceWakeCoordinator.ensureReady(
            request = wake.request,
            maxWaitMs = WORKER_ROUTE_RECOVERY_TIMEOUT_MS
        ) { progress ->
            retryCooldown.record(progress.retryAfterMs)
            markTunnelUnverified(progress.message)
        }
        when (outcome) {
            is CodespaceWakeOutcome.Ready -> {
                val keyId = when (outcome.backend) {
                    WakeControlBackend.WORKER -> wake.workerApiKeyId
                    WakeControlBackend.GITHUB_DIRECT -> wake.githubApiKeyId
                }
                keyId?.let { runCatching { accountRepository.updateApiKeyLastUsed(it) } }
                if (!outcome.routeReady) {
                    val directRoute = waitForDirectRouteAfterStart(
                        profile = profile,
                        tun = tun,
                        lifecycleToken = lifecycleToken,
                        initialError = lastError
                    )
                    if (directRoute.success) return directRoute
                    lastError = directRoute.error ?: lastError
                    return tryAlternativeStoredProfiles(profile, tun, lifecycleToken)
                        ?: directRoute
                }
            }
            is CodespaceWakeOutcome.Failed -> {
                if (outcome.retryAllowed) retryCooldown.record(outcome.retryAfterMs)
                if (outcome.controlPlaneUnavailable) {
                    val alternative = tryAlternativeStoredProfiles(profile, tun, lifecycleToken)
                        ?: RunningRouteResult(false, outcome.message)
                    return if (alternative.success) alternative else alternative.copy(
                        retryAllowed = alternative.retryAllowed && outcome.retryAllowed,
                        retryAfterMs = listOfNotNull(alternative.retryAfterMs, retryCooldown.remainingMs()).maxOrNull()
                    )
                }
                return RunningRouteResult(
                    false, outcome.message,
                    retryAllowed = outcome.retryAllowed,
                    retryAfterMs = if (outcome.retryAllowed) retryCooldown.remainingMs() else null
                )
            }
            is CodespaceWakeOutcome.TimedOut -> {
                retryCooldown.record(outcome.retryAfterMs)
                val alternative = tryAlternativeStoredProfiles(profile, tun, lifecycleToken)
                    ?: RunningRouteResult(false, outcome.message)
                return if (alternative.success) alternative else alternative.copy(
                    retryAfterMs = listOfNotNull(alternative.retryAfterMs, retryCooldown.remainingMs()).maxOrNull()
                )
            }
        }

        repeat(MAX_LOCAL_ROUTE_RECOVERY_ATTEMPTS) { attempt ->
            if (!awaitUnderlyingNetwork(lifecycleToken, tun)) {
                return RunningRouteResult(false, "VPN lifecycle changed while waiting for an underlying network")
            }
            val route = restartRouteOnce(profile, tun, lifecycleToken)
            if (route.success) return route
            lastError = route.error ?: lastError
            if (attempt + 1 < MAX_LOCAL_ROUTE_RECOVERY_ATTEMPTS) delay(1_500)
        }
        return tryAlternativeStoredProfiles(profile, tun, lifecycleToken)
            ?: RunningRouteResult(false, lastError)
    }

    private suspend fun awaitUnderlyingNetwork(
        lifecycleToken: Long,
        tun: ParcelFileDescriptor
    ): Boolean {
        var announced = false
        while (waitingForUnderlyingNetwork && isTunnelCurrent(lifecycleToken, tun)) {
            if (!announced) {
                markTunnelUnverified("No underlying network. VPN will reconnect automatically when connectivity returns...")
                announced = true
            }
            delay(500)
        }
        return isTunnelCurrent(lifecycleToken, tun)
    }

    private fun scheduleRouteRecovery(
        reason: String,
        delayMs: Long = 0L,
        failureScope: RouteFailureScope = RouteFailureScope.Unknown
    ) {
        synchronized(this) {
            if (startJob?.isActive == true || routeRecoveryInProgress || routeRecoveryJob?.isActive == true || reconnectRetryJob?.isActive == true || cleanupJob?.isActive == true) {
                return
            }
            routeRecoveryInProgress = true
        }

        val lifecycleToken = activeLifecycleToken
        val profile = activeProfile
        val tun = vpnInterface
        if (profile == null || tun == null) {
            routeRecoveryInProgress = false
            failAndStop("Tunnel recovery could not access the active VPN interface")
            return
        }

        if (!isTunnelCurrent(lifecycleToken, tun)) {
            routeRecoveryInProgress = false
            return
        }
        markTunnelUnverified("$reason Reconnecting tunnel...")
        routeRecoveryJob = scope.launch {
            lifecycleOperationMutex.withLock {
                try {
                    if (delayMs > 0) {
                        delay(delayMs)
                    }
                    if (!isTunnelCurrent(lifecycleToken, tun)) {
                        return@withLock
                    }

                    val route = recoverRouteWithWorker(
                        profile,
                        tun,
                        lifecycleToken,
                        reason,
                        failureScope
                    )
                    routeRecoveryInProgress = false
                    withContext(Dispatchers.Main) {
                        if (isTunnelCurrent(lifecycleToken, tun)) {
                            if (route.success) {
                                markTunnelVerified("VPN reconnected and route verified")
                            } else if (!route.retryAllowed) {
                                failAndStop(route.error ?: "VPN needs configuration changes")
                            } else if (waitingForUnderlyingNetwork && route.retryAfterMs == null) {
                                markTunnelUnverified("No underlying network. Waiting to reconnect...")
                            } else {
                                schedulePersistentReconnect(route.error ?: "Tunnel recovery failed", route.retryAfterMs)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isTunnelCurrent(lifecycleToken, tun)) {
                        withContext(Dispatchers.Main) {
                            schedulePersistentReconnect(error.localizedMessage ?: "Transient recovery failure")
                        }
                    }
                } finally {
                    routeRecoveryInProgress = false
                }
            }
        }
    }

    private fun schedulePersistentReconnect(reason: String, retryAfterMs: Long? = null) {
        if (!desiredConnected || reconnectRetryJob?.isActive == true) return
        val token = activeLifecycleToken
        val tun = vpnInterface ?: return
        if (!isTunnelCurrent(token, tun)) return
        healthJob?.cancel()
        healthJob = null
        markTunnelUnverified("$reason Retrying automatically...")
        reconnectRetryJob = scope.launch {
            var attempt = 0
            var retryWaitMs = retryAfterMs
            var retryReason = reason
            while (isActive && desiredConnected && isTunnelCurrent(token, tun)) {
                val backoff = reconnectBackoffMs(attempt)
                val retryDelay = reconnectDelayMs(attempt, retryWaitMs)
                attempt = (attempt + 1).coerceAtMost(4)
                markTunnelUnverified("$retryReason Retrying automatically in ${(retryDelay + 999L) / 1000L}s...")
                awaitWakeRetryDelay(
                    retryDelay + Random.nextLong(0L, backoff / 5L + 1L), SystemClock::elapsedRealtime
                )
                if (!desiredConnected || !isTunnelCurrent(token, tun)) break
                lifecycleOperationMutex.withLock {
                    val profile = activeProfile ?: return@withLock
                    val route = try {
                        recoverRouteWithWorker(profile, tun, token, reason)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        RunningRouteResult(false, error.localizedMessage ?: "Recovery temporarily unavailable")
                    }
                    retryWaitMs = route.retryAfterMs
                    retryReason = route.error ?: reason
                    withContext(Dispatchers.Main) {
                        if (!desiredConnected || !isTunnelCurrent(token, tun)) return@withContext
                        if (route.success) {
                            reconnectRetryJob = null
                            markTunnelVerified("VPN automatically reconnected and route verified")
                        } else if (!route.retryAllowed) {
                            failAndStop(route.error ?: "VPN needs configuration changes")
                        } else {
                            markTunnelUnverified("${route.error ?: reason} Retrying automatically...")
                        }
                    }
                    if (route.success || !route.retryAllowed) return@launch
                }
            }
        }
    }

    private fun registerUnderlyingNetworkMonitor() {
        if (underlyingNetworkCallback != null) {
            return
        }
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("Android connectivity service is unavailable")
        // Android 12+ selects the best physical network asynchronously. Mark
        // the route unavailable before registering the callback so startup
        // cannot race ahead and hand libXray an unbound outer socket.
        applyUnderlyingNetwork(null, triggerVerification = false)
        synchronized(availableUnderlyingNetworks) {
            availableUnderlyingNetworks.clear()
            lostUnderlyingNetworks.clear()
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (underlyingNetworkCallback !== this) {
                    return
                }
                if (usesPlatformBestUnderlyingNetwork) {
                    applyUnderlyingNetwork(network, triggerVerification = true)
                    return
                }
                synchronized(availableUnderlyingNetworks) {
                    lostUnderlyingNetworks.remove(network)
                }
                if (underlyingNetwork != null) return
                // The request itself guarantees INTERNET + NOT_VPN. Use this
                // provisional network immediately on older Android releases;
                // onCapabilitiesChanged will score and replace it if needed.
                applyUnderlyingNetwork(network, triggerVerification = true)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (underlyingNetworkCallback !== this) {
                    return
                }
                if (usesPlatformBestUnderlyingNetwork) {
                    applyUnderlyingNetwork(
                        network = network.takeIf {
                            underlyingNetworkScore(networkCapabilities) != Int.MIN_VALUE
                        },
                        triggerVerification = true
                    )
                    return
                }
                synchronized(availableUnderlyingNetworks) {
                    if (underlyingNetworkScore(networkCapabilities) == Int.MIN_VALUE) {
                        availableUnderlyingNetworks.remove(network)
                    } else {
                        lostUnderlyingNetworks.remove(network)
                        availableUnderlyingNetworks[network] = networkCapabilities
                    }
                }
                applyUnderlyingNetwork(
                    network = preferredUnderlyingNetwork(),
                    triggerVerification = true
                )
            }

            override fun onLost(network: Network) {
                if (underlyingNetworkCallback !== this) {
                    return
                }
                if (usesPlatformBestUnderlyingNetwork) {
                    if (underlyingNetwork == network) {
                        applyUnderlyingNetwork(null, triggerVerification = true)
                    }
                    return
                }
                synchronized(availableUnderlyingNetworks) {
                    lostUnderlyingNetworks.add(network)
                    availableUnderlyingNetworks.remove(network)
                }
                applyUnderlyingNetwork(
                    network = preferredUnderlyingNetwork(),
                    triggerVerification = true
                )
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        underlyingNetworkCallback = callback
        usesPlatformBestUnderlyingNetwork = false
        val callbackRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                usesPlatformBestUnderlyingNetwork = true
                registerPlatformBestNetworkCallback(connectivityManager, request, callback)
            } else {
                connectivityManager.registerNetworkCallback(request, callback)
            }
        }.isSuccess
        if (!callbackRegistered) {
            underlyingNetworkCallback = null
            usesPlatformBestUnderlyingNetwork = false
            seedUnderlyingNetworks(connectivityManager)
        } else if (!usesPlatformBestUnderlyingNetwork) {
            mergeUnderlyingNetworkSnapshot(connectivityManager)
        }
        if (!callbackRegistered || !usesPlatformBestUnderlyingNetwork) {
            applyUnderlyingNetwork(
                network = preferredUnderlyingNetwork(),
                triggerVerification = false
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerPlatformBestNetworkCallback(
        connectivityManager: ConnectivityManager,
        request: NetworkRequest,
        callback: ConnectivityManager.NetworkCallback
    ) {
        connectivityManager.registerBestMatchingNetworkCallback(
            request,
            callback,
            Handler(Looper.getMainLooper())
        )
    }

    @Suppress("DEPRECATION")
    private fun seedUnderlyingNetworks(connectivityManager: ConnectivityManager) {
        val snapshot = snapshotUnderlyingNetworks(connectivityManager)
        synchronized(availableUnderlyingNetworks) {
            availableUnderlyingNetworks.clear()
            lostUnderlyingNetworks.clear()
            availableUnderlyingNetworks.putAll(snapshot)
        }
    }

    private fun mergeUnderlyingNetworkSnapshot(connectivityManager: ConnectivityManager) {
        val snapshot = snapshotUnderlyingNetworks(connectivityManager)
        synchronized(availableUnderlyingNetworks) {
            val merged = mergeUnderlyingNetworkCandidates(
                callbackObserved = availableUnderlyingNetworks,
                callbackLost = lostUnderlyingNetworks,
                snapshot = snapshot
            )
            availableUnderlyingNetworks.clear()
            availableUnderlyingNetworks.putAll(merged)
        }
    }

    @Suppress("DEPRECATION")
    private fun snapshotUnderlyingNetworks(
        connectivityManager: ConnectivityManager
    ): Map<Network, NetworkCapabilities> {
        return buildMap {
            connectivityManager.allNetworks.forEach { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                    ?: return@forEach
                if (underlyingNetworkScore(capabilities) != Int.MIN_VALUE) {
                    put(network, capabilities)
                }
            }
        }
    }

    private fun preferredUnderlyingNetwork(): Network? {
        return synchronized(availableUnderlyingNetworks) {
            availableUnderlyingNetworks
                .asSequence()
                .map { (network, capabilities) ->
                    network to underlyingNetworkScore(capabilities)
                }
                .maxByOrNull { (network, score) ->
                    score + if (network == underlyingNetwork) 3 else 0
                }
                ?.first
        }
    }

    private fun underlyingNetworkScore(capabilities: NetworkCapabilities): Int {
        val transportScore = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 40
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 30
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 20
            else -> 10
        }
        return underlyingNetworkPreferenceScore(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isNotVpn = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isNotMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            transportScore = transportScore
        )
    }

    @Synchronized
    private fun applyUnderlyingNetwork(network: Network?, triggerVerification: Boolean) {
        val previous = underlyingNetwork
        val wasWaiting = waitingForUnderlyingNetwork
        if (previous == network) {
            waitingForUnderlyingNetwork = network == null
            SocketProtector.updateUnderlyingNetwork(network)
            return
        }

        underlyingNetwork = network
        waitingForUnderlyingNetwork = network == null
        SocketProtector.updateUnderlyingNetwork(network)
        SocketProtector.clearDialerFailure()
        if (previous != network) {
            // Serialized transition verification resets probe connections on
            // the new network. A separate reset job could retire a newer client.
            synchronized(sessionProfileHealth) {
                sessionProfileHealth.clear()
            }
        }
        if (triggerVerification) {
            underlyingNetworkGeneration.incrementAndGet()
        }
        val underlyingNetworksAccepted = runCatching {
            setUnderlyingNetworks(network?.let { arrayOf(it) } ?: emptyArray())
        }.getOrElse { error ->
            Log.w("G2RayVpnService", "Could not publish the selected underlying network", error)
            false
        }
        if (!underlyingNetworksAccepted) {
            Log.w("G2RayVpnService", "Android rejected the selected underlying network metadata")
        }

        if (!triggerVerification) {
            return
        }
        if (network == null) {
            networkHandoffJob?.cancel()
            networkHandoffJob = null
            scope.launch {
                withContext(Dispatchers.Main) {
                    markTunnelUnverified("Underlying network changed. Waiting to reconnect...")
                }
            }
            return
        }

        val shouldVerify = wasWaiting || previous != null
        if (!shouldVerify || startJob?.isActive == true) {
            return
        }
        networkHandoffJob?.cancel()
        networkHandoffJob = scope.launch {
            delay(NETWORK_HANDOFF_RECOVERY_DELAY_MS)
            lifecycleOperationMutex.withLock {
                when (
                    networkHandoffAction(
                        coroutineActive = isActive,
                        hasVpnInterface = vpnInterface != null,
                        coreState = core.state.value
                    )
                ) {
                    NetworkHandoffAction.Ignore -> return@withLock
                    NetworkHandoffAction.Defer -> return@withLock
                    NetworkHandoffAction.RecoverCore -> {
                        scheduleRouteRecovery(
                            core.lastError.value ?: "Xray stopped while the underlying network was unavailable"
                        )
                    }
                    NetworkHandoffAction.VerifyRoute -> {
                        val route = verifyRouteAfterTransition()
                        if (route.success) {
                            if (wasWaiting) {
                                withContext(Dispatchers.Main) {
                                    markTunnelVerified("VPN connected after network handoff")
                                }
                            }
                        } else {
                            scheduleRouteRecovery(
                                route.error ?: "Tunnel route failed after network handoff",
                                failureScope = route.failureScope
                            )
                        }
                    }
                }
            }
        }
    }

    private fun unregisterUnderlyingNetworkMonitor() {
        val callback = underlyingNetworkCallback
        underlyingNetworkCallback = null
        usesPlatformBestUnderlyingNetwork = false
        networkHandoffJob?.cancel()
        networkHandoffJob = null
        underlyingNetwork = null
        synchronized(availableUnderlyingNetworks) {
            availableUnderlyingNetworks.clear()
            lostUnderlyingNetworks.clear()
        }
        SocketProtector.updateUnderlyingNetwork(null)
        waitingForUnderlyingNetwork = false
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        if (callback != null && connectivityManager != null) {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        runCatching { setUnderlyingNetworks(null) }
    }

    private fun rememberLastProfileContext(rawProfile: String, accountId: String?) {
        runCatching {
            EncryptedPrefsStore(this).putStrings(
                mapOf(
                    PREF_LAST_RAW_PROFILE_URI to rawProfile,
                    PREF_LAST_ACCOUNT_ID to accountId,
                    PREF_DESIRED_CONNECTED to "true"
                )
            )
        }
    }

    private fun restoreLastRawProfile(): String? {
        return runCatching {
            EncryptedPrefsStore(this).getString(PREF_LAST_RAW_PROFILE_URI, null)
        }.getOrNull()
    }

    private fun restoreLastAccountId(): String? {
        return runCatching {
            EncryptedPrefsStore(this).getString(PREF_LAST_ACCOUNT_ID, null)
        }.getOrNull()
    }

    private fun clearLastRawProfile() {
        runCatching {
            EncryptedPrefsStore(this).remove(PREF_LAST_RAW_PROFILE_URI, PREF_LAST_ACCOUNT_ID)
        }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("G2Ray VPN")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "G2Ray VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active VPN connection status"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        val state = VpnStateRepository.state.value.tunnelState
        val shouldCleanup = state == TunnelState.Starting ||
            state == TunnelState.Connected ||
            state == TunnelState.Stopping ||
            vpnInterface != null

        if (shouldCleanup) {
            cleanupTunnel(
                finalState = TunnelState.Disconnected,
                finalMessage = "Disconnected",
                publishStopping = false,
                stopService = false
            )
        }

        unregisterUnderlyingNetworkMonitor()
        unregisterIdleTransitionMonitor()

        cleanupJob?.invokeOnCompletion {
            scope.cancel()
        }
        if (cleanupJob?.isActive != true) {
            scope.cancel()
        }
        SocketProtector.vpnService = null
        super.onDestroy()
    }
}
