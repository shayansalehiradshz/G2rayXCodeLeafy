package com.g2ray.client.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.g2ray.client.BuildConfig
import com.g2ray.client.G2RayApplication
import com.g2ray.client.data.ProfileDelayUpdate
import com.g2ray.client.model.*
import com.g2ray.client.network.CodespaceWakeCoordinator
import com.g2ray.client.network.CodespaceWakeOutcome
import com.g2ray.client.network.CodespaceWakePhase
import com.g2ray.client.network.CodespaceWakeRequest
import com.g2ray.client.network.GitHubCodespaceCoordinator
import com.g2ray.client.network.GitHubNetwork
import com.g2ray.client.network.RouteCandidateDiscovery
import com.g2ray.client.network.WakeBackendSelector
import com.g2ray.client.network.WakeControlBackend
import com.g2ray.client.network.WorkerNetwork
import com.g2ray.client.network.WorkerRouteCoordinator
import com.g2ray.client.network.WorkerRouteEndpoint
import com.g2ray.client.network.WorkerUrlNormalizer
import com.g2ray.client.network.WorkerWakeResponse
import com.g2ray.client.parser.VlessUriParser
import com.g2ray.client.vpn.G2RayVpnService
import com.g2ray.client.vpn.SocketProtector
import com.g2ray.client.vpn.RoutePerformanceSummary
import com.g2ray.client.vpn.VpnEnginePreferences
import com.g2ray.client.vpn.XrayDelaySummary
import com.g2ray.client.vpn.XrayCoreManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ProfileDelayState(
    val testing: Boolean = false,
    val delayMs: Long? = null,
    val p95Ms: Long? = null,
    val jitterMs: Long? = null,
    val successfulSamples: Int = 0,
    val sampleCount: Int = 0,
    val error: String? = null,
    val testedAt: Long? = null
)

private fun failedDelaySummary(message: String): XrayDelaySummary {
    return XrayDelaySummary(
        success = false,
        medianMs = null,
        p95Ms = null,
        jitterMs = null,
        successfulSamples = 0,
        totalSamples = 0,
        error = message.ifBlank { "Delay test failed" }
    )
}

private data class RouteOptimizationProbe(
    val stored: StoredG2RayProfile?,
    val profile: G2RayProfile,
    val generated: Boolean
)

class G2RayViewModel(application: Application) : AndroidViewModel(application) {

    enum class VpnStartPlan {
        DirectProbeThenWake,
        StartDirect,
        Block
    }

    enum class WorkerNetworkFailureAction {
        DirectVpnFallback,
        ReportFailure
    }

    val repository = (application as G2RayApplication).accountRepository
    private val vpnEnginePreferences = VpnEnginePreferences(application)

    val accounts: StateFlow<List<G2RayAccount>> = repository.accounts
    val apiKeys: StateFlow<List<G2RayApiKey>> = repository.apiKeys
    val profiles: StateFlow<List<StoredG2RayProfile>> = repository.profiles
    val activeSelection: StateFlow<ActiveSelection> = repository.activeSelection
    val repositoryLoadError: StateFlow<String?> = repository.loadError
    val workerControlPlane = repository.workerControlPlane

    val activeAccount: StateFlow<G2RayAccount?> = accounts.combine(activeSelection) { accs, sel ->
        accs.find { it.id == sel.accountId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeProfile: StateFlow<StoredG2RayProfile?> = profiles.combine(activeSelection) { profs, sel ->
        profs.find { it.id == sel.profileId && it.accountId == sel.accountId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeCodespaceProfiles: StateFlow<List<StoredG2RayProfile>> = profiles.combine(activeSelection) { profs, sel ->
        val now = System.currentTimeMillis()
        profs.filter { it.accountId == sel.accountId }
            .sortedWith(
                compareBy<StoredG2RayProfile> { ProfileHealth.rank(it, now) }
                    .thenBy { ProfileHealth.delaySortValue(it, now) }
                    .thenBy { it.delayFailureCount }
                    .thenByDescending { it.lastDelayTestedAt ?: 0L }
                    .thenBy { it.createdAt }
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeApiKey: StateFlow<G2RayApiKey?> = apiKeys.combine(activeSelection) { keys, sel ->
        keys.find {
            it.id == sel.apiKeyId && it.accountId == sel.accountId && it.enabled &&
                it.kind == ApiKeyKind.WORKER_WAKE_SECRET
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeGitHubToken: StateFlow<G2RayApiKey?> = apiKeys.combine(activeSelection) { keys, sel ->
        keys.asSequence()
            .filter {
                it.accountId == sel.accountId && it.enabled && it.kind == ApiKeyKind.GITHUB_TOKEN
            }
            .minWithOrNull(compareBy<G2RayApiKey> { it.priority }.thenBy { it.createdAt })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _statusMessage = MutableStateFlow("Idle")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _isWakingUp = MutableStateFlow(false)
    val isWakingUp: StateFlow<Boolean> = _isWakingUp.asStateFlow()

    private val _isVpnCoreAvailable = MutableStateFlow(XrayCoreManager().isAvailable())
    val isVpnCoreAvailable: StateFlow<Boolean> = _isVpnCoreAvailable.asStateFlow()

    private val _profileDelayStates = MutableStateFlow<Map<String, ProfileDelayState>>(emptyMap())
    val profileDelayStates: StateFlow<Map<String, ProfileDelayState>> = _profileDelayStates.asStateFlow()

    private val _vpnEngineMode = MutableStateFlow(vpnEnginePreferences.getMode())
    val vpnEngineMode: StateFlow<VpnEngineMode> = _vpnEngineMode.asStateFlow()

    private val _fastDnsEnabled = MutableStateFlow(vpnEnginePreferences.isFastDnsEnabled())
    val fastDnsEnabled: StateFlow<Boolean> = _fastDnsEnabled.asStateFlow()

    private val _ipv4OnlyEnabled = MutableStateFlow(vpnEnginePreferences.isIpv4OnlyEnabled())
    val ipv4OnlyEnabled: StateFlow<Boolean> = _ipv4OnlyEnabled.asStateFlow()

    private val _dpiCompatibilityEnabled = MutableStateFlow(vpnEnginePreferences.isDpiCompatibilityEnabled())
    val dpiCompatibilityEnabled: StateFlow<Boolean> = _dpiCompatibilityEnabled.asStateFlow()

    private val _autoRouteOptimizationEnabled = MutableStateFlow(
        vpnEnginePreferences.isAutoRouteOptimizationEnabled()
    )
    val autoRouteOptimizationEnabled: StateFlow<Boolean> = _autoRouteOptimizationEnabled.asStateFlow()

    private val _seamlessFailoverEnabled = MutableStateFlow(
        vpnEnginePreferences.isSeamlessFailoverEnabled()
    )
    val seamlessFailoverEnabled: StateFlow<Boolean> = _seamlessFailoverEnabled.asStateFlow()

    private val _isRouteOptimizing = MutableStateFlow(false)
    val isRouteOptimizing: StateFlow<Boolean> = _isRouteOptimizing.asStateFlow()
    private val availableWakeAt = mutableMapOf<String, Long>()

    private val apiService by lazy {
        WorkerNetwork.createApiService(
            loggingEnabled = BuildConfig.DEBUG,
            protectSocket = SocketProtector::protectControlPlane,
            dnsLookup = SocketProtector::lookupControlPlane
        )
    }
    private val workerCoordinator by lazy { WorkerRouteCoordinator(apiService) }
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
    private val routeCandidateDiscovery by lazy {
        val client = WorkerNetwork.createHttpClient(
            loggingEnabled = false,
            protectSocket = SocketProtector::protectControlPlane,
            dnsLookup = SocketProtector::lookupControlPlane
        ).newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        RouteCandidateDiscovery(client, SocketProtector::lookupControlPlane)
    }

    companion object {
        internal fun generateRouteCandidates(
            existing: List<G2RayProfile>,
            addresses: List<String>,
            limit: Int = MAX_GENERATED_ROUTE_PROBES
        ): List<G2RayProfile> {
            val known = existing.mapTo(hashSetOf(), VlessUriParser::semanticRouteKey)
            val templates = preferredRouteTemplates(existing)
            return buildList {
                candidateLoop@ for (address in addresses.distinct()) {
                    // Share the bounded budget between transports, not just the
                    // first template when DNS discovers many new addresses.
                    for (template in templates) {
                        if (size >= limit) break@candidateLoop
                        val candidate = VlessUriParser.withEndpointAddress(template, address).getOrNull()
                            ?: continue
                        if (known.add(VlessUriParser.semanticRouteKey(candidate))) add(candidate)
                    }
                }
            }
        }

        private fun preferredRouteTemplates(profiles: List<G2RayProfile>): List<G2RayProfile> {
            val unique = profiles.sortedBy { VlessUriParser.isIpv4Literal(it.address) }
                .distinctBy(VlessUriParser::transportTemplateKey)
            val xhttp = unique.firstOrNull { it.transport == Transport.XHTTP }
            val ws = unique.filter { it.transport == Transport.WS }.minByOrNull {
                when {
                    it.alpn.isEmpty() -> 0
                    it.alpn == listOf("http/1.1") -> 1
                    else -> 2
                }
            }
            return listOfNotNull(xhttp, ws).ifEmpty { unique.take(1) }
        }

        internal fun optimizationGuardCandidates(
            selected: G2RayProfile,
            ranked: List<G2RayProfile>
        ): List<G2RayProfile> = (listOf(selected) +
            ranked.firstOrNull { it.transport != selected.transport }.let(::listOfNotNull) + ranked)
            .distinctBy(VlessUriParser::semanticRouteKey).take(3)

        internal fun hasOptimizationReadinessEvidence(
            availableWakeAt: Long?,
            profiles: List<StoredG2RayProfile>,
            now: Long
        ): Boolean = availableWakeAt?.let { now - it in 0..OPTIMIZATION_WAKE_FRESHNESS_MS } == true ||
            profiles.any { ProfileHealth.isDelayFresh(it, now) &&
                it.lastDelayMs?.let { delay -> delay in 1..9_999 } == true && it.delayFailureCount == 0 }

        enum class WorkerEndpoint {
            WAKE,
            HEALTH
        }

        fun buildWorkerHttpClientVisibleForTesting(loggingEnabled: Boolean): OkHttpClient {
            return buildWorkerHttpClient(loggingEnabled)
        }

        fun shouldPollWorkerWakeResponseVisibleForTesting(httpCode: Int, body: WorkerWakeResponse?): Boolean {
            return shouldPollWorkerWakeResponse(httpCode, body)
        }

        fun shouldCheckHealthAfterWakeErrorVisibleForTesting(httpCode: Int, body: WorkerWakeResponse?): Boolean {
            return shouldCheckHealthAfterWakeError(httpCode, body)
        }

        fun normalizeWorkerUrlVisibleForTesting(input: String, endpoint: WorkerEndpoint): String {
            return normalizeWorkerUrl(input, endpoint)
        }

        fun mergeRouteStateVisibleForTesting(
            previous: AppConnectionState,
            routeState: RouteState,
            message: String,
            activeAccountId: String?,
            activeProfileId: String?,
            routeReady: Boolean
        ): AppConnectionState {
            return mergeRouteState(previous, routeState, message, activeAccountId, activeProfileId, routeReady)
        }

        fun vpnStartAllowedVisibleForTesting(tunnelState: TunnelState): Boolean {
            return vpnStartAllowed(tunnelState)
        }

        fun vpnStartRequestBusyVisibleForTesting(routeProbeActive: Boolean): Boolean {
            return vpnStartRequestBusy(routeProbeActive)
        }

        fun configurationMutationAllowedVisibleForTesting(
            tunnelState: TunnelState,
            startupBusy: Boolean = false
        ): Boolean {
            return configurationMutationAllowed(tunnelState, startupBusy)
        }

        fun vpnStartPlanVisibleForTesting(workerConfigured: Boolean, routeReady: Boolean): VpnStartPlan {
            return vpnStartPlan(workerConfigured, routeReady)
        }

        fun workerNetworkFailureActionVisibleForTesting(
            pendingVpnStart: Boolean,
            throwable: Throwable
        ): WorkerNetworkFailureAction {
            return workerNetworkFailureAction(pendingVpnStart, throwable)
        }

        fun workerHttpFailureActionVisibleForTesting(
            pendingVpnStart: Boolean,
            httpCode: Int,
            body: WorkerWakeResponse?
        ): WorkerNetworkFailureAction {
            return workerHttpFailureAction(pendingVpnStart, httpCode, body)
        }

        fun delayScanCompletionMessageVisibleForTesting(
            total: Int,
            persistenceFailures: Int,
            successful: Int = total
        ): String {
            return delayScanCompletionMessage(total, persistenceFailures, successful)
        }

        fun routeOptimizationScoreVisibleForTesting(
            summary: XrayDelaySummary,
            transport: Transport,
            performance: RoutePerformanceSummary? = null
        ): Long = routeOptimizationScore(summary, transport, performance)

        fun routeOptimizationComparisonScoreVisibleForTesting(
            summary: XrayDelaySummary,
            transport: Transport,
            performance: RoutePerformanceSummary?,
            hasPerformanceEvidence: Boolean
        ): Long = routeOptimizationComparisonScore(
            summary,
            transport,
            performance,
            hasPerformanceEvidence
        )

        fun shouldAutoOptimizeRoutesVisibleForTesting(
            enabled: Boolean,
            lastAttemptAt: Long,
            now: Long,
            routeWasReady: Boolean,
            profileCount: Int,
            tunnelState: TunnelState,
            busy: Boolean,
            availableWakeAt: Long? = null
        ): Boolean {
            return shouldAutoOptimizeRoutes(
                enabled,
                lastAttemptAt,
                now,
                routeWasReady,
                profileCount,
                tunnelState,
                busy,
                availableWakeAt
            )
        }

        internal fun clearInterruptedDelayStatesVisibleForTesting(
            states: Map<String, ProfileDelayState>,
            ids: Set<String>,
            message: String,
            testedAt: Long
        ): Map<String, ProfileDelayState> {
            return clearInterruptedDelayStates(states, ids, message, testedAt)
        }

        private fun clearInterruptedDelayStates(
            states: Map<String, ProfileDelayState>,
            ids: Set<String>,
            message: String,
            testedAt: Long
        ): Map<String, ProfileDelayState> {
            if (ids.isEmpty()) return states
            return states.mapValues { (id, state) ->
                if (id in ids && state.testing) {
                    state.copy(
                        testing = false,
                        error = message,
                        testedAt = testedAt
                    )
                } else {
                    state
                }
            }
        }

        private fun delayScanCompletionMessage(
            total: Int,
            persistenceFailures: Int,
            successful: Int
        ): String {
            return when {
                persistenceFailures > 0 ->
                    "Quick delay scan measured $total configs, but $persistenceFailures result(s) could not be saved."
                successful <= 0 ->
                    "Ping all finished, but none of $total configs responded. Check route readiness or try a per-config test."
                successful < total ->
                    "Ping all complete: $successful/$total configs responded. Failed configs show their error."
                else ->
                    "Quick delay scan complete for $total configs. Use the per-config test for a 3-sample measurement."
            }
        }

        private fun routeOptimizationScore(
            summary: XrayDelaySummary,
            transport: Transport,
            performance: RoutePerformanceSummary? = null
        ): Long {
            val median = summary.medianMs?.takeIf { summary.success && it in 1..9999 }
                ?: return Long.MAX_VALUE
            val tailPenalty = ((summary.p95Ms ?: median) - median).coerceAtLeast(0L) / 2L
            val jitterPenalty = summary.jitterMs?.coerceAtLeast(0L) ?: 0L
            val failedSamples = (summary.totalSamples - summary.successfulSamples).coerceAtLeast(0)
            val retryPenalty = failedSamples * 300L
            val deprecatedTransportPenalty = if (transport == Transport.WS) 20L else 0L
            val transferPenalty = if (performance?.success == true) {
                val downloadMs = transferTimeMs(256L * 1024L, performance.downloadBps)
                val uploadMs = performance.uploadBps
                    ?.takeIf { it > 0L }
                    ?.let { transferTimeMs(32L * 1024L, it) / 2L }
                    ?: MISSING_UPLOAD_SAMPLE_PENALTY_MS
                downloadMs + uploadMs
            } else {
                0L
            }
            return median + tailPenalty + jitterPenalty + retryPenalty +
                deprecatedTransportPenalty + transferPenalty
        }

        private fun routeOptimizationComparisonScore(
            summary: XrayDelaySummary,
            transport: Transport,
            performance: RoutePerformanceSummary?,
            hasPerformanceEvidence: Boolean
        ): Long {
            val score = routeOptimizationScore(summary, transport, performance)
            if (score == Long.MAX_VALUE) return score
            return score + if (hasPerformanceEvidence && performance?.success != true) {
                UNKNOWN_TRANSFER_PENALTY_MS
            } else {
                0L
            }
        }

        private fun transferTimeMs(bytes: Long, bytesPerSecond: Long?): Long {
            val rate = bytesPerSecond?.takeIf { it > 0L } ?: return 0L
            return ((bytes * 1_000L) / rate).coerceIn(0L, 5_000L)
        }

        private fun shouldAutoOptimizeRoutes(
            enabled: Boolean,
            lastAttemptAt: Long,
            now: Long,
            routeWasReady: Boolean,
            profileCount: Int,
            tunnelState: TunnelState,
            busy: Boolean,
            availableWakeAt: Long? = null
        ): Boolean {
            val stopped = tunnelState in setOf(TunnelState.Disabled, TunnelState.Disconnected, TunnelState.Error)
            val elapsed = now - lastAttemptAt
            val freshWakeAfterAttempt = availableWakeAt?.let {
                it > lastAttemptAt && now - it in 0..OPTIMIZATION_WAKE_FRESHNESS_MS
            } == true
            return enabled && routeWasReady && profileCount > 0 && stopped && !busy &&
                (lastAttemptAt <= 0L || elapsed >= AUTO_ROUTE_OPTIMIZATION_INTERVAL_MS || freshWakeAfterAttempt)
        }

        private const val AUTO_ROUTE_OPTIMIZATION_INTERVAL_MS = 30 * 60 * 1000L
        private const val AUTO_ROUTE_OPTIMIZATION_START_DELAY_MS = 1_500L
        private const val OPTIMIZATION_WAKE_FRESHNESS_MS = 5 * 60 * 1000L
        private const val MAX_ROUTE_OPTIMIZATION_PROBES = 30
        private const val MAX_GENERATED_ROUTE_PROBES = 12
        private const val MAX_MANUAL_THROUGHPUT_PROBES = 3
        private const val MAX_AUTO_THROUGHPUT_PROBES = 2
        private const val THROUGHPUT_RESULT_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        private const val UNKNOWN_TRANSFER_PENALTY_MS = 750L
        private const val MISSING_UPLOAD_SAMPLE_PENALTY_MS = 250L

        private fun mergeRouteState(
            previous: AppConnectionState,
            routeState: RouteState,
            message: String,
            activeAccountId: String?,
            activeProfileId: String?,
            routeReady: Boolean
        ): AppConnectionState {
            return previous.copy(
                routeState = routeState,
                message = message,
                activeAccountId = activeAccountId ?: previous.activeAccountId,
                activeProfileId = activeProfileId ?: previous.activeProfileId,
                routeReady = routeReady
            )
        }

        private fun vpnStartAllowed(tunnelState: TunnelState): Boolean {
            return tunnelState != TunnelState.Starting &&
                tunnelState != TunnelState.Connected &&
                tunnelState != TunnelState.Stopping
        }

        private fun vpnStartRequestBusy(routeProbeActive: Boolean): Boolean = routeProbeActive

        private fun configurationMutationAllowed(tunnelState: TunnelState, startupBusy: Boolean): Boolean {
            return vpnStartAllowed(tunnelState) && !startupBusy
        }

        private fun vpnStartPlan(workerConfigured: Boolean, routeReady: Boolean): VpnStartPlan {
            return when {
                workerConfigured -> VpnStartPlan.DirectProbeThenWake
                else -> VpnStartPlan.StartDirect
            }
        }

        private fun workerNetworkFailureAction(
            pendingVpnStart: Boolean,
            throwable: Throwable
        ): WorkerNetworkFailureAction {
            return if (pendingVpnStart && throwable is IOException) {
                WorkerNetworkFailureAction.DirectVpnFallback
            } else {
                WorkerNetworkFailureAction.ReportFailure
            }
        }

        /**
         * A Worker 5xx is a control-plane failure, not proof that an already
         * running VLESS route is down. When a VPN start is pending, let the
         * service make the authoritative route check directly. Never use this
         * escape hatch for quota, authentication, rate-limit, or not-found
         * responses because those describe a meaningful configuration state.
         */
        private fun workerHttpFailureAction(
            pendingVpnStart: Boolean,
            httpCode: Int,
            body: WorkerWakeResponse?
        ): WorkerNetworkFailureAction {
            return if (pendingVpnStart && body?.quota_blocked != true && httpCode in 500..599) {
                WorkerNetworkFailureAction.DirectVpnFallback
            } else {
                WorkerNetworkFailureAction.ReportFailure
            }
        }

        private fun buildWorkerHttpClient(loggingEnabled: Boolean): OkHttpClient {
            return WorkerNetwork.createHttpClient(
                loggingEnabled = loggingEnabled,
                protectSocket = SocketProtector::protectControlPlane,
                dnsLookup = SocketProtector::lookupControlPlane
            )
        }

        private fun shouldPollWorkerWakeResponse(httpCode: Int, body: WorkerWakeResponse?): Boolean {
            return WorkerRouteCoordinator.shouldPoll(httpCode, body)
        }

        private fun shouldCheckHealthAfterWakeError(httpCode: Int, body: WorkerWakeResponse?): Boolean {
            return httpCode == 429 && body?.reason == "wake_recently_succeeded"
        }

        private fun workerPollDelaySeconds(body: WorkerWakeResponse?, defaultSeconds: Int = 5): Int {
            return WorkerRouteCoordinator.pollDelaySeconds(body, defaultSeconds)
        }

        private fun healthAfterWakeCooldownDelaySeconds(body: WorkerWakeResponse?): Int {
            return (body?.poll_after_seconds ?: 2).coerceIn(1, 5)
        }

        private fun normalizeWorkerUrl(input: String, endpoint: WorkerEndpoint): String {
            return WorkerUrlNormalizer.normalize(
                input,
                if (endpoint == WorkerEndpoint.WAKE) WorkerRouteEndpoint.WAKE else WorkerRouteEndpoint.HEALTH
            )
        }
    }

    private var currentWakeJob: Job? = null
    private var currentVpnStartJob: Job? = null
    private var configurationMutationJob: Job? = null
    private var currentDelayProbeJob: Job? = null
    private var routeOptimizationJob: Job? = null
    @Volatile
    private var routeOptimizationProbeStarted = false
    private var pendingVpnProfileUri: String? = null
    private var pendingVpnAccountId: String? = null

    private fun configurationMutationAllowedNow(): Boolean {
        val startupBusy = currentVpnStartJob?.isActive == true ||
            currentWakeJob?.isActive == true ||
            currentDelayProbeJob?.isActive == true ||
            routeOptimizationJob?.isActive == true
        return configurationMutationAllowed(VpnStateRepository.state.value.tunnelState, startupBusy)
    }

    private fun <T> launchConfigurationMutation(
        operation: String,
        mutation: () -> T,
        onResult: (Result<T>) -> Unit = {}
    ) {
        if (configurationMutationJob?.isActive == true) {
            val result = Result.failure<T>(
                IllegalStateException("Wait for the current settings change to finish.")
            )
            _statusMessage.value = result.exceptionOrNull()?.message.orEmpty()
            onResult(result)
            return
        }

        lateinit var job: kotlinx.coroutines.Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val result = withContext(Dispatchers.IO) {
                runCatching { mutation() }
            }
            result.exceptionOrNull()?.let {
                _statusMessage.value = it.localizedMessage ?: "Failed to $operation."
            }
            onResult(result)
        }
        configurationMutationJob = job
        job.invokeOnCompletion {
            if (configurationMutationJob === job) {
                configurationMutationJob = null
            }
        }
        job.start()
    }

    private fun workerKeyFor(accountId: String): G2RayApiKey? {
        val selection = repository.activeSelection.value
        return repository.apiKeys.value.firstOrNull {
            it.id == selection.apiKeyId && it.accountId == accountId && it.enabled &&
                it.kind == ApiKeyKind.WORKER_WAKE_SECRET
        } ?: repository.enabledApiKey(accountId, ApiKeyKind.WORKER_WAKE_SECRET)
    }

    private fun githubTokenFor(accountId: String): G2RayApiKey? {
        return repository.enabledApiKey(accountId, ApiKeyKind.GITHUB_TOKEN)
    }

    private fun wakeRequestFor(account: G2RayAccount): CodespaceWakeRequest {
        val controlPlane = workerControlPlane.value
        val workerKey = workerKeyFor(account.id)
        val githubToken = githubTokenFor(account.id)
        return CodespaceWakeRequest(
            mode = account.wakeMode,
            githubCodespaceName = account.codespaceName.orEmpty(),
            workerCodespaceId = account.workerCodespaceId ?: account.codespaceName,
            workerBaseUrls = controlPlane?.workerBaseUrls()?.takeIf { it.isNotEmpty() }
                ?: account.workerBaseUrls(),
            workerSecret = controlPlane?.encryptedWakeSecret?.takeIf { it.isNotBlank() }
                ?: workerKey?.encryptedValue,
            githubToken = githubToken?.encryptedValue
        )
    }

    fun wakeControlConfigured(account: G2RayAccount): Boolean {
        val request = wakeRequestFor(account)
        return WakeBackendSelector.order(
            mode = request.mode,
            workerConfigured = request.workerConfigured,
            githubDirectConfigured = request.githubDirectConfigured
        ).isNotEmpty()
    }

    private fun publishRouteState(
        routeState: RouteState,
        message: String,
        activeAccountId: String?,
        routeReady: Boolean,
        activeProfileId: String? = activeProfile.value?.id
    ) {
        VpnStateRepository.state.update { previous ->
            mergeRouteState(
                previous = previous,
                routeState = routeState,
                message = message,
                activeAccountId = activeAccountId,
                activeProfileId = activeProfileId,
                routeReady = routeReady
            )
        }
    }

    fun wakeUpCodespace() {
        if (currentWakeJob?.isActive == true) return

        currentWakeJob = viewModelScope.launch {
            val account = activeAccount.value

            if (account == null) {
                _statusMessage.value = "No active account selected."
                clearPendingVpnStart()
                return@launch
            }

            val request = wakeRequestFor(account)
            if (!wakeControlConfigured(account)) {
                _statusMessage.value = when (account.wakeMode) {
                    CodespaceWakeMode.AUTO -> "Configure the shared Worker or add a GitHub token for this Codespace."
                    CodespaceWakeMode.WORKER -> "Worker mode is selected, but the shared Worker is not configured."
                    CodespaceWakeMode.GITHUB_DIRECT -> "Direct GitHub mode is selected, but this Codespace has no GitHub token."
                }
                clearPendingVpnStart()
                return@launch
            }

            _isWakingUp.value = true
            _statusMessage.value = "Checking Codespace wake method..."
            publishRouteState(
                routeState = RouteState.Waking,
                message = "Checking Codespace wake method...",
                activeAccountId = account.id,
                routeReady = false
            )

            try {
                val outcome = codespaceWakeCoordinator.ensureReady(
                    request = request
                ) { progress ->
                    _statusMessage.value = progress.message
                    publishRouteState(
                        routeState = if (progress.phase == CodespaceWakePhase.WAKING) {
                            RouteState.Waking
                        } else {
                            RouteState.Settling
                        },
                        message = progress.message,
                        activeAccountId = account.id,
                        routeReady = false
                    )
                }
                when (outcome) {
                    is CodespaceWakeOutcome.Ready -> {
                        availableWakeAt[account.id] = System.currentTimeMillis()
                        withContext(Dispatchers.IO) {
                            outcome.workerResponse?.let { response ->
                                repository.recordCodespaceHealth(
                                    accountId = account.id,
                                    routeReady = response.route_ready,
                                    quotaResetEstimateUtc = response.quota_reset_estimate_utc,
                                    retentionExpiresAt = response.retention_expires_at,
                                    message = response.message ?: "Route ready"
                                )
                            }
                            outcome.githubResponse?.let { response ->
                                repository.recordCodespaceHealth(
                                    accountId = account.id,
                                    routeReady = null,
                                    quotaResetEstimateUtc = null,
                                    retentionExpiresAt = response.retention_expires_at,
                                    message = "Codespace is ${response.state ?: "available"}; route verification is pending."
                                )
                            }
                            when (outcome.backend) {
                                WakeControlBackend.WORKER -> workerKeyFor(account.id)?.let {
                                    repository.updateApiKeyLastUsed(it.id)
                                }
                                WakeControlBackend.GITHUB_DIRECT -> githubTokenFor(account.id)?.let {
                                    repository.updateApiKeyLastUsed(it.id)
                                }
                            }
                        }
                        if (outcome.routeReady) {
                            _statusMessage.value = "Codespace route is ready. Starting VPN..."
                            publishRouteState(
                                routeState = RouteState.Ready,
                                message = "Codespace route ready",
                                activeAccountId = account.id,
                                activeProfileId = activeProfile.value?.id,
                                routeReady = true
                            )
                        } else {
                            _statusMessage.value = if (pendingVpnProfileUri != null) {
                                "Codespace is available. Starting VPN to verify the selected route..."
                            } else {
                                "Codespace is available. Start VPN to verify the selected route."
                            }
                            publishRouteState(
                                routeState = RouteState.Settling,
                                message = "Codespace available; route verification pending",
                                activeAccountId = account.id,
                                activeProfileId = activeProfile.value?.id,
                                routeReady = false
                            )
                        }
                        startPendingVpnIfRequested()
                    }
                    is CodespaceWakeOutcome.Failed -> {
                        withContext(Dispatchers.IO) {
                            outcome.workerResponse?.let { response ->
                                repository.recordCodespaceHealth(
                                    accountId = account.id,
                                    routeReady = response.route_ready,
                                    quotaResetEstimateUtc = response.quota_reset_estimate_utc,
                                    retentionExpiresAt = response.retention_expires_at,
                                    message = response.next_action ?: response.message ?: outcome.message
                                )
                            }
                        }
                        if (pendingVpnProfileUri != null && outcome.controlPlaneUnavailable) {
                            val msg = "Wake control is unreachable. Trying the selected VPN route directly..."
                            _statusMessage.value = msg
                            publishRouteState(
                                routeState = RouteState.Unknown,
                                message = msg,
                                activeAccountId = account.id,
                                routeReady = false
                            )
                            startPendingVpnIfRequested()
                        } else {
                            _statusMessage.value = "Error: ${outcome.message}"
                            publishRouteState(
                                routeState = RouteState.Error,
                                message = outcome.message,
                                activeAccountId = account.id,
                                routeReady = false
                            )
                            clearPendingVpnStart()
                        }
                    }
                    is CodespaceWakeOutcome.TimedOut -> {
                        if (pendingVpnProfileUri != null) {
                            val msg = "${outcome.message} Trying the selected VPN route directly..."
                            _statusMessage.value = msg
                            publishRouteState(
                                routeState = RouteState.Settling,
                                message = msg,
                                activeAccountId = account.id,
                                routeReady = false
                            )
                            startPendingVpnIfRequested()
                        } else {
                            _statusMessage.value = outcome.message
                            publishRouteState(
                                routeState = RouteState.Error,
                                message = outcome.message,
                                activeAccountId = account.id,
                                routeReady = false
                            )
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _statusMessage.value = "Wake cancelled."
                publishRouteState(
                    routeState = RouteState.Unknown,
                    message = "Wake cancelled",
                    activeAccountId = account.id,
                    routeReady = false
                )
                clearPendingVpnStart()
            } catch (e: Exception) {
                if (pendingVpnProfileUri != null && e is IOException) {
                    val msg = "Wake control is unreachable. Starting the selected VPN directly to verify its route..."
                    _statusMessage.value = msg
                    publishRouteState(
                        routeState = RouteState.Unknown,
                        message = msg,
                        activeAccountId = account.id,
                        routeReady = false
                    )
                    startPendingVpnIfRequested()
                    return@launch
                }
                val err = "Failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
                _statusMessage.value = err
                publishRouteState(
                    routeState = RouteState.Error,
                    message = err,
                    activeAccountId = account.id,
                    routeReady = false
                )
                clearPendingVpnStart()
            } finally {
                _isWakingUp.value = false
            }
        }
        currentWakeJob?.invokeOnCompletion { cause ->
            if (cause == null) viewModelScope.launch { autoOptimizeRoutesIfNeeded() }
        }
    }

    fun cancelWakeUp() {
        currentVpnStartJob?.cancel()
        currentWakeJob?.cancel()
        clearPendingVpnStart()
        _isWakingUp.value = false
        _statusMessage.value = "Wake cancelled."
        val activeAccId = activeAccount.value?.id
        publishRouteState(
            routeState = RouteState.Unknown,
            message = "Wake cancelled",
            activeAccountId = activeAccId,
            routeReady = false
        )
    }

    fun startVpn(context: Context = getApplication()) {
        if (vpnStartRequestBusy(currentVpnStartJob?.isActive == true)) {
            _statusMessage.value = "VPN startup is already in progress."
            return
        }
        val probesToDrain = listOfNotNull(routeOptimizationJob, currentDelayProbeJob).filter { it.isActive }
        if (probesToDrain.isNotEmpty()) {
            _statusMessage.value = "Finishing the current bounded probe, then starting VPN..."
            currentVpnStartJob = viewModelScope.launch {
                probesToDrain.forEach { it.cancel() }
                probesToDrain.forEach { it.join() }
                currentVpnStartJob = null
                startVpn(context)
            }
            return
        }
        if (!isVpnCoreAvailable.value) {
            reportVpnStartBlocked(XrayCoreManager.MISSING_CORE_MESSAGE)
            return
        }
        if (configurationMutationJob?.isActive == true) {
            reportVpnStartBlocked("Wait for the current settings change to finish before starting VPN.")
            return
        }

        if (!vpnStartAllowed(VpnStateRepository.state.value.tunnelState)) {
            _statusMessage.value = "VPN is already running or stopping."
            return
        }

        val profile = activeProfile.value
        if (profile == null) {
            _statusMessage.value = "No active VLESS config selected."
            VpnStateRepository.state.update { previous ->
                previous.copy(
                    tunnelState = TunnelState.Error,
                    message = "No active VLESS config selected."
                )
            }
            return
        }

        val account = activeAccount.value
        val wakeConfigured = account?.let(::wakeControlConfigured) == true
        when (vpnStartPlan(wakeConfigured, VpnStateRepository.state.value.routeReady)) {
            VpnStartPlan.DirectProbeThenWake -> {
                pendingVpnProfileUri = profile.rawUri
                pendingVpnAccountId = account?.id
                startVpnWithDirectRouteProbe(profile, account)
            }
            VpnStartPlan.StartDirect -> startVpnService(profile.rawUri, context, account?.id)
            VpnStartPlan.Block -> {
                reportVpnStartBlocked(
                    "Codespace route is not ready and no wake method is configured. Set up the shared Worker or a direct GitHub token."
                )
            }
        }
    }

    fun reportVpnStartBlocked(message: String) {
        _statusMessage.value = message
        VpnStateRepository.state.update { previous ->
            previous.copy(
                tunnelState = TunnelState.Error,
                message = message
            )
        }
    }

    fun setVpnEngineMode(mode: VpnEngineMode) {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before changing its engine."
            return
        }
        launchConfigurationMutation(
            operation = "save VPN engine",
            mutation = { vpnEnginePreferences.setMode(mode) }
        ) { result ->
            result.onSuccess {
                _vpnEngineMode.value = mode
                _statusMessage.value = "VPN engine set to ${mode.label}."
            }
            result.onFailure {
                _statusMessage.value = "Could not save VPN engine: ${it.localizedMessage ?: it.javaClass.simpleName}"
            }
        }
    }

    fun setFastDnsEnabled(enabled: Boolean) {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before changing Fast DNS."
            return
        }
        launchConfigurationMutation(
            operation = "save Fast DNS",
            mutation = { vpnEnginePreferences.setFastDnsEnabled(enabled) }
        ) { result ->
            result.onSuccess {
                _fastDnsEnabled.value = enabled
                _statusMessage.value = if (enabled) {
                    "Fast DNS enabled for the next VPN connection."
                } else {
                    "Fast DNS disabled."
                }
            }
            result.onFailure {
                _statusMessage.value = "Could not save Fast DNS: ${it.localizedMessage ?: it.javaClass.simpleName}"
            }
        }
    }

    fun setIpv4OnlyEnabled(enabled: Boolean) {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before changing IPv4-only mode."
            return
        }
        launchConfigurationMutation(
            operation = "save IPv4-only mode",
            mutation = { vpnEnginePreferences.setIpv4OnlyEnabled(enabled) }
        ) { result ->
            result.onSuccess {
                _ipv4OnlyEnabled.value = enabled
                _statusMessage.value = if (enabled) {
                    "IPv4-only compatibility enabled for the next VPN connection."
                } else {
                    "Dual-stack VPN restored for the next connection."
                }
            }
            result.onFailure {
                _statusMessage.value = "Could not save IPv4-only mode: ${it.localizedMessage ?: it.javaClass.simpleName}"
            }
        }
    }

    fun setDpiCompatibilityEnabled(enabled: Boolean) {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before changing DPI compatibility."
            return
        }
        launchConfigurationMutation(
            operation = "save DPI compatibility",
            mutation = { vpnEnginePreferences.setDpiCompatibilityEnabled(enabled) }
        ) { result ->
            result.onSuccess {
                _dpiCompatibilityEnabled.value = enabled
                _statusMessage.value = if (enabled) {
                    "DPI compatibility enabled for the next VPN connection."
                } else {
                    "DPI compatibility disabled."
                }
            }
            result.onFailure {
                _statusMessage.value = "Could not save DPI compatibility: ${it.localizedMessage ?: it.javaClass.simpleName}"
            }
        }
    }

    fun setAutoRouteOptimizationEnabled(enabled: Boolean) {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before changing route optimization."
            return
        }
        launchConfigurationMutation(
            operation = "save automatic route optimization",
            mutation = { vpnEnginePreferences.setAutoRouteOptimizationEnabled(enabled) }
        ) { result ->
            result.onSuccess {
                _autoRouteOptimizationEnabled.value = enabled
                if (!enabled) {
                    routeOptimizationJob?.takeIf { !routeOptimizationProbeStarted }?.cancel()
                }
                _statusMessage.value = if (enabled) {
                    "Automatic phone-side route optimization enabled."
                } else {
                    "Automatic route optimization disabled."
                }
            }
            result.onFailure {
                _statusMessage.value = "Could not save route optimization: ${it.localizedMessage ?: it.javaClass.simpleName}"
            }
        }
    }

    fun setSeamlessFailoverEnabled(enabled: Boolean) {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before changing seamless failover."
            return
        }
        launchConfigurationMutation(
            operation = "save seamless failover",
            mutation = { vpnEnginePreferences.setSeamlessFailoverEnabled(enabled) }
        ) { result ->
            result.onSuccess {
                _seamlessFailoverEnabled.value = enabled
                _statusMessage.value = if (enabled) {
                    "Seamless phone-side route failover enabled."
                } else {
                    "Seamless route failover disabled; the selected config will be used alone."
                }
            }
            result.onFailure {
                _statusMessage.value = "Could not save seamless failover: ${it.localizedMessage ?: it.javaClass.simpleName}"
            }
        }
    }

    fun stopVpn(context: Context = getApplication()) {
        currentVpnStartJob?.cancel()
        clearPendingVpnStart()
        val intent = Intent(context, G2RayVpnService::class.java).apply {
            action = G2RayVpnService.ACTION_STOP
        }
        context.startService(intent)
        _statusMessage.value = "Stopping VPN..."
    }

    private fun startPendingVpnIfRequested() {
        val profileUri = pendingVpnProfileUri ?: return
        val accountId = pendingVpnAccountId
        pendingVpnProfileUri = null
        pendingVpnAccountId = null
        if (!vpnStartAllowed(VpnStateRepository.state.value.tunnelState)) {
            return
        }
        startVpnService(profileUri, getApplication<Application>().applicationContext, accountId)
    }

    private fun startVpnWithDirectRouteProbe(
        profile: StoredG2RayProfile,
        account: G2RayAccount?
    ) {
        if (currentVpnStartJob?.isActive == true) {
            _statusMessage.value = "VPN startup is already in progress."
            return
        }
        if (currentWakeJob?.isActive == true) {
            _statusMessage.value = "Codespace wake is already in progress."
            return
        }

        currentVpnStartJob = viewModelScope.launch {
            try {
                _statusMessage.value = "Checking the selected profile before waking the Codespace..."
                publishRouteState(
                    routeState = RouteState.Settling,
                    message = "Checking active VPN route",
                    activeAccountId = account?.id,
                    routeReady = false
                )
                val parsedProfile = VlessUriParser.parse(profile.rawUri).getOrThrow()
                val result = withContext(Dispatchers.IO) {
                    XrayCoreManager().measureDelayAny(
                        profile = parsedProfile,
                        datDir = getApplication<Application>().filesDir.absolutePath,
                        // A viable distant Codespaces edge can exceed two
                        // seconds. Prefer a short accurate direct check over
                        // needlessly waking an already-running Codespace.
                        timeoutSeconds = 4,
                        dpiCompatibilityEnabled = dpiCompatibilityEnabled.value
                    )
                }
                if (result.success) {
                    _statusMessage.value = "Active VPN route is ready. Starting VPN..."
                    publishRouteState(
                        routeState = RouteState.Ready,
                        message = "Active VPN route ready",
                        activeAccountId = account?.id,
                        routeReady = true
                    )
                    startPendingVpnIfRequested()
                } else {
                    _statusMessage.value = "Direct profile check failed. Waking the Codespace..."
                    wakeUpCodespace()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _statusMessage.value = "Direct profile check could not complete. Waking the Codespace..."
                wakeUpCodespace()
            } finally {
                currentVpnStartJob = null
            }
        }
    }

    private fun clearPendingVpnStart() {
        pendingVpnProfileUri = null
        pendingVpnAccountId = null
    }

    private fun startVpnService(profileUri: String, context: Context, accountId: String?) {
        val intent = Intent(context.applicationContext, G2RayVpnService::class.java).apply {
            action = G2RayVpnService.ACTION_START
            putExtra(G2RayVpnService.EXTRA_RAW_PROFILE_URI, profileUri)
            putExtra(G2RayVpnService.EXTRA_ACCOUNT_ID, accountId)
            putExtra(G2RayVpnService.EXTRA_VPN_ENGINE_MODE, vpnEngineMode.value.storageValue)
        }
        ContextCompat.startForegroundService(context.applicationContext, intent)
        _statusMessage.value = "Starting VPN..."
    }

    // Account logic
    fun saveAccount(
        id: String?,
        displayName: String,
        workerUrl: String,
        fallbackWorkerUrl: String? = null,
        codespaceName: String?,
        workerCodespaceId: String? = null,
        wakeMode: CodespaceWakeMode = CodespaceWakeMode.AUTO,
        githubToken: String = "",
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        if (!configurationMutationAllowedNow()) {
            onResult(Result.failure(IllegalStateException("Stop VPN before changing Codespace accounts.")))
            return
        }
        val trimmedName = displayName.trim()
        val trimmedCodespace = codespaceName?.trim()?.takeIf { it.isNotEmpty() }
        val trimmedWorkerCodespaceId = workerCodespaceId?.trim()?.takeIf { it.isNotEmpty() } ?: trimmedCodespace
        val trimmedUrl = workerUrl.trim().ifBlank { workerControlPlane.value?.workerBaseUrl.orEmpty() }
        val trimmedFallbackUrl = fallbackWorkerUrl?.trim()?.takeIf { it.isNotEmpty() }

        if (trimmedName.isEmpty()) {
            onResult(Result.failure(IllegalArgumentException("Account name is empty")))
            return
        }
        if (trimmedCodespace == null) {
            onResult(Result.failure(IllegalArgumentException("GitHub Codespace name is empty")))
            return
        }
        if (wakeMode == CodespaceWakeMode.GITHUB_DIRECT && githubToken.trim().isEmpty()) {
            onResult(Result.failure(IllegalArgumentException("Direct GitHub mode requires a GitHub token")))
            return
        }
        if (trimmedWorkerCodespaceId == null || !trimmedWorkerCodespaceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) {
            onResult(Result.failure(IllegalArgumentException("Codespace selection name must use letters, numbers, _ or - only")))
            return
        }

        val normalizedUrl = if (trimmedUrl.isBlank()) {
            ""
        } else {
            try {
                WorkerUrlNormalizer.baseUrl(trimmedUrl)
            } catch (e: Exception) {
                onResult(Result.failure(IllegalArgumentException("Invalid Worker URL: ${e.message}")))
                return
            }
        }
        val normalizedFallbackUrl = try {
            trimmedFallbackUrl?.let(WorkerUrlNormalizer::baseUrl)
        } catch (e: Exception) {
            onResult(Result.failure(IllegalArgumentException("Invalid backup Worker URL: ${e.message}")))
            return
        }
        if (normalizedUrl.isBlank() && normalizedFallbackUrl != null) {
            onResult(Result.failure(IllegalArgumentException("Set a primary Worker URL before a backup URL")))
            return
        }
        if (normalizedFallbackUrl == normalizedUrl) {
            onResult(Result.failure(IllegalArgumentException("Backup Worker URL must be different from the primary URL")))
            return
        }

        launchConfigurationMutation(
            operation = "save Codespace account",
            mutation = {
                repository.saveAccountWithGitHubToken(
                    id = id,
                    displayName = trimmedName,
                    workerBaseUrl = normalizedUrl,
                    codespaceName = trimmedCodespace,
                    fallbackWorkerBaseUrl = normalizedFallbackUrl,
                    workerCodespaceId = trimmedWorkerCodespaceId,
                    wakeMode = wakeMode,
                    githubToken = githubToken
                )
                Unit
            },
            onResult = onResult
        )
    }

    fun deleteAccount(id: String) {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before changing Codespace accounts."
            return
        }
        launchConfigurationMutation(
            operation = "delete account",
            mutation = { repository.deleteAccount(id) }
        )
    }

    fun selectAccount(id: String) {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before switching Codespaces."
            return
        }
        launchConfigurationMutation(
            operation = "select Codespace",
            mutation = { repository.selectAccount(id) }
        ) { result ->
            if (result.isSuccess) {
                val account = repository.accounts.value.firstOrNull { it.id == id }
                _statusMessage.value = "Selected ${account?.displayName ?: "Codespace"}."
                publishRouteState(
                    routeState = RouteState.Unknown,
                    message = "Codespace selection changed",
                    activeAccountId = id,
                    routeReady = false,
                    activeProfileId = repository.activeSelection.value.profileId
                )
            }
        }
    }

    fun saveWorkerControlPlane(
        workerUrl: String,
        fallbackWorkerUrl: String?,
        wakeSecret: String,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        if (!configurationMutationAllowedNow()) {
            onResult(Result.failure(IllegalStateException("Stop VPN before changing Worker settings.")))
            return
        }
        val primary = workerUrl.trim()
        val fallback = fallbackWorkerUrl?.trim()?.takeIf { it.isNotEmpty() }
        val secret = wakeSecret.trim()
        if (secret.isEmpty()) {
            onResult(Result.failure(IllegalArgumentException("Wake key is empty")))
            return
        }
        val normalizedPrimary: String
        val normalizedFallback: String?
        try {
            normalizedPrimary = WorkerUrlNormalizer.baseUrl(primary)
            normalizedFallback = fallback?.let(WorkerUrlNormalizer::baseUrl)
            require(normalizedPrimary != normalizedFallback) { "Backup Worker URL must be different from the primary URL" }
        } catch (e: Exception) {
            onResult(Result.failure(e))
            return
        }
        launchConfigurationMutation(
            operation = "save shared Worker",
            mutation = {
                repository.setWorkerControlPlane(normalizedPrimary, normalizedFallback, secret)
            }
        ) { result ->
            if (result.isSuccess) {
                _statusMessage.value = "Shared Worker control plane saved."
            }
            onResult(result)
        }
    }

    // API Key logic
    fun saveApiKey(
        id: String?,
        accountId: String,
        label: String,
        secret: String,
        enabled: Boolean,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        if (!configurationMutationAllowedNow()) {
            onResult(Result.failure(IllegalStateException("Stop VPN before changing wake keys.")))
            return
        }
        val trimmedLabel = label.trim()
        val trimmedSecret = secret.trim()

        if (trimmedLabel.isEmpty()) {
            onResult(Result.failure(IllegalArgumentException("Key label is empty")))
            return
        }

        if (id == null) {
            if (trimmedSecret.isEmpty()) {
                onResult(Result.failure(IllegalArgumentException("Wake Secret is empty")))
                return
            }
            launchConfigurationMutation(
                operation = "save wake key",
                mutation = {
                    repository.addApiKey(accountId, trimmedLabel, trimmedSecret, enabled)
                    Unit
                },
                onResult = onResult
            )
        } else {
            launchConfigurationMutation(
                operation = "update wake key",
                mutation = {
                    repository.updateApiKey(id, trimmedLabel, enabled, trimmedSecret.takeIf { it.isNotEmpty() })
                    Unit
                },
                onResult = onResult
            )
        }
    }

    fun deleteApiKey(id: String) {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before changing wake keys."
            return
        }
        launchConfigurationMutation(
            operation = "delete wake key",
            mutation = { repository.deleteApiKey(id) }
        )
    }

    fun selectApiKey(id: String) {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before switching wake keys."
            return
        }
        launchConfigurationMutation(
            operation = "select wake key",
            mutation = { repository.selectApiKey(id) }
        )
    }

    // Profiles / VLESS
    fun importProfilesText(
        accountId: String,
        input: String,
        onResult: (Result<Int>) -> Unit = {}
    ) {
        if (!configurationMutationAllowedNow()) {
            onResult(Result.failure(IllegalStateException("Stop VPN before importing configs.")))
            return
        }
        launchConfigurationMutation(
            operation = "import configs",
            mutation = {
                val results = VlessUriParser.parseAllProfiles(input)
                require(results.isNotEmpty()) { "No valid vless:// profiles found in input" }

                val successes = results.mapNotNull { it.getOrNull() }
                if (successes.isEmpty()) {
                    throw results.firstNotNullOfOrNull { it.exceptionOrNull() }
                        ?: IllegalArgumentException("Failed to parse profile")
                }

                val expectedCodespace = repository.accounts.value
                    .firstOrNull { it.id == accountId }
                    ?.codespaceName
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val compatibleProfiles = if (expectedCodespace == null) {
                    successes
                } else {
                    successes.filter { profile ->
                        profile.sni?.contains(expectedCodespace, ignoreCase = true) == true ||
                            profile.hostHeader?.contains(expectedCodespace, ignoreCase = true) == true
                    }
                }
                require(compatibleProfiles.isNotEmpty()) {
                    "None of the imported configs belongs to the selected Codespace. Choose the matching Codespace before importing."
                }
                repository.importProfiles(accountId, compatibleProfiles)
                compatibleProfiles.size
            },
            onResult = onResult
        )
    }

    fun deleteProfile(id: String) {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before changing configs."
            return
        }
        if (_profileDelayStates.value[id]?.testing == true) {
            _statusMessage.value = "Wait for the delay test to finish before deleting this config."
            return
        }
        launchConfigurationMutation(
            operation = "delete config",
            mutation = { repository.deleteProfile(id) }
        ) { result ->
            if (result.isSuccess) {
                _profileDelayStates.value = _profileDelayStates.value - id
            }
        }
    }

    fun deleteUnavailableProfiles() {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before changing configs."
            return
        }
        val ids = activeCodespaceProfiles.value
            .filter {
                ProfileHealth.isUnavailableForCleanup(it) &&
                    _profileDelayStates.value[it.id]?.testing != true
            }
            .mapTo(linkedSetOf()) { it.id }
        if (ids.isEmpty()) {
            _statusMessage.value = "No configs have failed two consecutive delay checks."
            return
        }
        launchConfigurationMutation(
            operation = "delete unavailable configs",
            mutation = { repository.deleteProfiles(ids) }
        ) { result ->
            if (result.isSuccess) {
                _profileDelayStates.value = _profileDelayStates.value - ids
                _statusMessage.value = "Deleted ${ids.size} repeatedly unavailable configs."
            }
        }
    }

    fun selectProfile(id: String) {
        if (!configurationMutationAllowedNow()) {
            _statusMessage.value = "Stop VPN before switching configs."
            return
        }
        launchConfigurationMutation(
            operation = "select config",
            mutation = { repository.selectProfile(id) }
        )
    }

    fun autoOptimizeRoutesIfNeeded() {
        val selection = repository.activeSelection.value
        val accountId = selection.accountId ?: return
        val account = repository.accounts.value.firstOrNull { it.id == accountId } ?: return
        val accountProfileCount = repository.profiles.value.count { it.accountId == accountId }
        val now = System.currentTimeMillis()
        val busy = currentVpnStartJob?.isActive == true ||
            currentWakeJob?.isActive == true ||
            configurationMutationJob?.isActive == true ||
            currentDelayProbeJob?.isActive == true ||
            routeOptimizationJob?.isActive == true
        if (!shouldAutoOptimizeRoutes(
                enabled = autoRouteOptimizationEnabled.value,
                lastAttemptAt = vpnEnginePreferences.getLastRouteOptimizationAttemptAt(accountId),
                now = now,
                routeWasReady = VpnStateRepository.state.value.routeReady || account.lastRouteReady == true ||
                    hasOptimizationReadinessEvidence(
                        availableWakeAt[accountId],
                        repository.profiles.value.filter { it.accountId == accountId },
                        now
                    ),
                profileCount = accountProfileCount,
                tunnelState = VpnStateRepository.state.value.tunnelState,
                busy = busy,
                availableWakeAt = availableWakeAt[accountId]
            )
        ) {
            return
        }
        launchRouteOptimization(accountId = accountId, manual = false)
    }

    fun optimizeRoutes() {
        if (!isVpnCoreAvailable.value) {
            _statusMessage.value = XrayCoreManager.MISSING_CORE_MESSAGE
            return
        }
        if (VpnStateRepository.state.value.tunnelState !in setOf(
                TunnelState.Disabled,
                TunnelState.Disconnected,
                TunnelState.Error
            )
        ) {
            _statusMessage.value = "Stop VPN before optimizing routes."
            return
        }
        if (currentDelayProbeJob?.isActive == true) {
            _statusMessage.value = "Wait for the real-delay test to finish before optimizing routes."
            return
        }
        val accountId = repository.activeSelection.value.accountId
        if (accountId == null) {
            _statusMessage.value = "Select a Codespace before optimizing routes."
            return
        }
        launchRouteOptimization(accountId = accountId, manual = true)
    }

    private fun launchRouteOptimization(accountId: String, manual: Boolean) {
        if (routeOptimizationJob?.isActive == true) {
            if (manual) _statusMessage.value = "Route optimization is already running."
            return
        }
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            if (!manual) delay(AUTO_ROUTE_OPTIMIZATION_START_DELAY_MS)
            if (repository.activeSelection.value.accountId != accountId) return@launch
            if (VpnStateRepository.state.value.tunnelState !in setOf(
                    TunnelState.Disabled,
                    TunnelState.Disconnected,
                    TunnelState.Error
                )
            ) {
                return@launch
            }

            _isRouteOptimizing.value = true
            routeOptimizationProbeStarted = true
            try {
                runRouteOptimization(accountId, manual)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                clearInterruptedDelayTests(
                    ids = repository.profiles.value.filter { it.accountId == accountId }.map { it.id },
                    message = "Route optimization was interrupted; saved health was preserved."
                )
                _statusMessage.value = "Route optimization failed safely: ${error.localizedMessage ?: error.javaClass.simpleName}"
            }
        }
        routeOptimizationJob = job
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                clearInterruptedDelayTests(
                    ids = repository.profiles.value.filter { it.accountId == accountId }.map { it.id },
                    message = "Route optimization interrupted; saved health was preserved."
                )
            }
            if (routeOptimizationJob === job) {
                routeOptimizationJob = null
                routeOptimizationProbeStarted = false
                _isRouteOptimizing.value = false
            } else if (routeOptimizationJob == null) {
                routeOptimizationProbeStarted = false
                _isRouteOptimizing.value = false
            }
        }
        job.start()
    }

    private suspend fun runRouteOptimization(accountId: String, manual: Boolean) {
        val selection = repository.activeSelection.value
        val storedProfiles = repository.profiles.value.filter { it.accountId == accountId }
        val parsedProfiles = storedProfiles.mapNotNull { stored ->
            VlessUriParser.parse(stored.rawUri).getOrNull()?.let { stored to it }
        }
        if (parsedProfiles.isEmpty()) {
            _statusMessage.value = "No valid configs are available for route optimization."
            return
        }

        if (!manual) {
            val selected = parsedProfiles.firstOrNull { it.first.id == selection.profileId }
                ?: parsedProfiles.first()
            _statusMessage.value = "Checking saved routes before automatic optimization..."
            val ranked = parsedProfiles.sortedWith(
                compareBy<Pair<StoredG2RayProfile, G2RayProfile>> { ProfileHealth.rank(it.first) }
                    .thenBy { ProfileHealth.delaySortValue(it.first) }
            ).map { it.second }
            val guards = measureQuickProfiles(optimizationGuardCandidates(selected.second, ranked))
            if (guards.none { it.success && it.medianMs?.let { ms -> ms in 1..9999 } == true }) {
                _statusMessage.value =
                    "Automatic route optimization postponed because none of the checked Codespace routes responded."
                return
            }
        }

        // An unavailable startup route is not a completed discovery attempt.
        // Start the heavy-scan cooldown only after the readiness guard passes.
        withContext(Dispatchers.IO) {
            vpnEnginePreferences.setLastRouteOptimizationAttemptAt(accountId, System.currentTimeMillis())
        }
        _statusMessage.value = "Discovering Codespaces routes from the phone network..."
        val hostnames = parsedProfiles.asSequence()
            .flatMap { (_, profile) -> sequenceOf(profile.sni, profile.hostHeader, profile.address) }
            .filterNotNull()
            .map(String::trim)
            .filter { it.isNotEmpty() && !VlessUriParser.isIpv4Literal(it) }
            .distinctBy { it.lowercase() }
            .toList()
        val existingAddresses = parsedProfiles.map { it.second.address }
        val discovered = routeCandidateDiscovery.discover(hostnames, existingAddresses)

        val generatedProfiles = generateRouteCandidates(
            parsedProfiles.map { it.second }, discovered.map { it.address }
        )

        val now = System.currentTimeMillis()
        val orderedExisting = parsedProfiles.sortedWith(
            compareBy<Pair<StoredG2RayProfile, G2RayProfile>> {
                if (it.first.id == selection.profileId) 0 else 1
            }.thenBy { ProfileHealth.rank(it.first, now) }
                .thenBy { ProfileHealth.delaySortValue(it.first, now) }
                .thenBy { it.first.createdAt }
        )
        val existingLimit = (MAX_ROUTE_OPTIMIZATION_PROBES - generatedProfiles.size).coerceAtLeast(1)
        val probes = buildList {
            orderedExisting.take(existingLimit).forEach { (stored, profile) ->
                add(RouteOptimizationProbe(stored, profile, generated = false))
            }
            generatedProfiles.take(MAX_ROUTE_OPTIMIZATION_PROBES - size).forEach { profile ->
                add(RouteOptimizationProbe(null, profile, generated = true))
            }
        }
        if (probes.isEmpty()) {
            _statusMessage.value = "No bounded route candidates were available to verify."
            return
        }

        val testedIds = probes.mapNotNull { it.stored?.id }
        testedIds.forEach { id ->
            _profileDelayStates.value = _profileDelayStates.value + (id to ProfileDelayState(testing = true))
        }
        _statusMessage.value = "Verifying ${probes.size} routes through libXray from this phone..."
        val summaries = measureQuickProfiles(probes.map { it.profile })
        val delayMeasuredAt = System.currentTimeMillis()
        val measured = probes.zip(summaries)
        val successful = measured.filter { (_, summary) ->
            summary.success && summary.medianMs?.let { it in 1..9999 } == true
        }

        if (successful.isEmpty()) {
            measured.forEach { (probe, summary) ->
                probe.stored?.let { stored ->
                    _profileDelayStates.value = _profileDelayStates.value + (
                        stored.id to ProfileDelayState(
                            testing = false,
                            error = summary.error ?: "Route unavailable; saved health was preserved.",
                            testedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            _statusMessage.value =
                "No route passed from this phone. Saved configs and health were left unchanged; wake the Codespace and retry."
            return
        }

        val generatedSuccesses = successful.mapNotNull { (probe, _) ->
            probe.profile.takeIf { probe.generated }
        }
        val idsBeforeImport = repository.profiles.value.mapTo(hashSetOf()) { it.id }
        val importedGenerated = withContext(Dispatchers.IO) {
            repository.importProfiles(accountId, generatedSuccesses)
        }
        val generatedByUri = importedGenerated.associateBy { it.rawUri.trim() }
        val measuredStored = measured.mapNotNull { (probe, summary) ->
            val stored = probe.stored ?: generatedByUri[probe.profile.rawUri.trim()]
            stored?.let { Triple(it, probe.profile, summary) }
        }.distinctBy { it.first.id }

        val performanceNow = System.currentTimeMillis()
        fun persistedPerformance(stored: StoredG2RayProfile): RoutePerformanceSummary? {
            val testedAt = stored.lastThroughputTestedAt ?: return null
            val age = performanceNow - testedAt
            if (age !in 0..THROUGHPUT_RESULT_MAX_AGE_MS) return null
            val download = stored.lastDownloadBps?.takeIf { it > 0L } ?: return null
            return RoutePerformanceSummary(
                success = true,
                downloadBps = download,
                uploadBps = stored.lastUploadBps?.takeIf { it > 0L },
                error = stored.lastThroughputError
            )
        }
        val effectivePerformance = measuredStored.mapNotNull { (stored, _, _) ->
            persistedPerformance(stored)?.let { stored.id to it }
        }.toMap().toMutableMap()
        val performanceLimit = if (manual) {
            MAX_MANUAL_THROUGHPUT_PROBES
        } else {
            MAX_AUTO_THROUGHPUT_PROBES
        }
        val performanceTargets = measuredStored.asSequence()
            .filter { (_, _, summary) ->
                summary.success && summary.medianMs?.let { it in 1..9999 } == true
            }
            .sortedWith(
                compareBy<Triple<StoredG2RayProfile, G2RayProfile, XrayDelaySummary>> {
                    if (it.first.id == selection.profileId) 0 else 1
                }.thenBy { (_, profile, summary) -> routeOptimizationScore(summary, profile.transport) }
            )
            .filter { (stored, _, _) -> manual || persistedPerformance(stored) == null }
            .take(performanceLimit)
            .toList()
        val measuredPerformance = linkedMapOf<String, RoutePerformanceSummary>()
        val measuredPerformanceAt = linkedMapOf<String, Long>()
        if (performanceTargets.isNotEmpty()) {
            val performanceManager = XrayCoreManager()
            performanceTargets.forEachIndexed { index, (stored, profile, _) ->
                _statusMessage.value =
                    "Measuring real transfer ${index + 1}/${performanceTargets.size} on ${stored.displayName}..."
                val result = try {
                    withContext(Dispatchers.IO) {
                        performanceManager.measureRoutePerformance(
                            profile = profile,
                            datDir = getApplication<Application>().filesDir.absolutePath,
                            dpiCompatibilityEnabled = dpiCompatibilityEnabled.value
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    RoutePerformanceSummary(false, error = error.localizedMessage ?: error.javaClass.simpleName)
                }
                measuredPerformance[stored.id] = result
                measuredPerformanceAt[stored.id] = System.currentTimeMillis()
                if (result.success) {
                    effectivePerformance[stored.id] = result
                } else {
                    effectivePerformance.remove(stored.id)
                }
            }
        }

        val updates = measuredStored.mapNotNull { (stored, _, summary) ->
            val delayMs = summary.medianMs?.takeIf { summary.success && it in 1..9999 }
            val performance = measuredPerformance[stored.id]
            val preserveFreshAccurateHistory = delayMs != null &&
                stored.lastDelaySampleCount >= 3 &&
                stored.lastDelaySuccessfulSamples * 2 >= stored.lastDelaySampleCount &&
                ProfileHealth.isDelayFresh(stored)
            if (preserveFreshAccurateHistory && performance == null) return@mapNotNull null
            ProfileDelayUpdate(
                id = stored.id,
                success = delayMs != null,
                delayMs = if (preserveFreshAccurateHistory) stored.lastDelayMs else delayMs,
                error = if (delayMs == null) summary.error ?: "Delay test failed" else null,
                p95Ms = if (preserveFreshAccurateHistory) stored.lastDelayP95Ms else summary.p95Ms,
                jitterMs = if (preserveFreshAccurateHistory) stored.lastDelayJitterMs else summary.jitterMs,
                successfulSamples = if (preserveFreshAccurateHistory) {
                    stored.lastDelaySuccessfulSamples
                } else {
                    summary.successfulSamples
                },
                sampleCount = if (preserveFreshAccurateHistory) stored.lastDelaySampleCount else summary.totalSamples,
                downloadBps = performance?.downloadBps,
                uploadBps = performance?.uploadBps,
                throughputTestedAt = measuredPerformanceAt[stored.id],
                throughputError = performance?.error,
                delayTestedAt = if (preserveFreshAccurateHistory) stored.lastDelayTestedAt else delayMeasuredAt
            )
        }
        withContext(Dispatchers.IO) { repository.recordProfileDelays(updates) }

        measuredStored.forEach { (stored, _, summary) ->
            val delayMs = summary.medianMs?.takeIf { summary.success && it in 1..9999 }
            _profileDelayStates.value = _profileDelayStates.value + (
                stored.id to ProfileDelayState(
                    testing = false,
                    delayMs = delayMs,
                    p95Ms = summary.p95Ms,
                    jitterMs = summary.jitterMs,
                    successfulSamples = summary.successfulSamples,
                    sampleCount = summary.totalSamples,
                    error = if (delayMs == null) summary.error ?: "Delay test failed" else null,
                    testedAt = System.currentTimeMillis()
                )
            )
        }

        val hasPerformanceEvidence = effectivePerformance.values.any { it.success }
        fun comparisonScore(
            stored: StoredG2RayProfile,
            profile: G2RayProfile,
            summary: XrayDelaySummary
        ): Long = routeOptimizationComparisonScore(
            summary = summary,
            transport = profile.transport,
            performance = effectivePerformance[stored.id],
            hasPerformanceEvidence = hasPerformanceEvidence
        )
        val best = measuredStored.asSequence()
            .filter { (_, _, summary) -> summary.success && summary.medianMs?.let { it in 1..9999 } == true }
            .minByOrNull { (stored, profile, summary) ->
                comparisonScore(stored, profile, summary)
            }
        val current = measuredStored.firstOrNull { it.first.id == selection.profileId }
            ?.takeIf { (_, _, summary) -> summary.success && summary.medianMs?.let { it in 1..9999 } == true }
        val chosen = when {
            best == null -> current
            current == null || current.first.id == best.first.id -> best
            else -> {
                val currentScore = comparisonScore(current.first, current.second, current.third)
                val bestScore = comparisonScore(best.first, best.second, best.third)
                val requiredGain = maxOf(25L, currentScore / 10L)
                if (bestScore + requiredGain < currentScore) best else current
            }
        }
        if (
            chosen != null &&
            chosen.first.id != selection.profileId &&
            repository.activeSelection.value.accountId == accountId
        ) {
            withContext(Dispatchers.IO) { repository.selectProfile(chosen.first.id) }
        }
        val addedCount = importedGenerated.count { it.id !in idsBeforeImport }
        val selectedDelay = chosen?.third?.medianMs
        _statusMessage.value = buildString {
            append("Route optimization verified ${successful.size}/${probes.size} configs")
            if (addedCount > 0) append(", added $addedCount")
            if (measuredPerformance.isNotEmpty()) {
                append(", transfer-tested ${measuredPerformance.values.count { it.success }}/${measuredPerformance.size}")
            }
            if (chosen != null) {
                append(if (chosen.first.id == selection.profileId) ", kept " else ", selected ")
                append(chosen.first.displayName)
                selectedDelay?.let { append(" (${it} ms)") }
            }
            append('.')
        }
    }

    private suspend fun measureQuickProfiles(
        profiles: List<G2RayProfile>,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<XrayDelaySummary> {
        if (profiles.isEmpty()) return emptyList()
        val manager = XrayCoreManager()
        val datDir = getApplication<Application>().filesDir.absolutePath
        return buildList {
            var completed = 0
            profiles.chunked(5).forEach { chunk ->
                val batch = try {
                    withContext(Dispatchers.IO) {
                        manager.measureDelayQuickBatch(
                            profiles = chunk,
                            datDir = datDir,
                            dpiCompatibilityEnabled = dpiCompatibilityEnabled.value,
                            checkActive = { coroutineContext.ensureActive() }
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A malformed/older AAR or a native invocation failure
                    // must fall through to the established one-profile path.
                    null
                }
                if (batch != null && batch.size == chunk.size) {
                    // Failed items are retried against independent endpoints
                    // inside the batch helper. Keeping those retries concurrent
                    // avoids a long per-config timeout tail.
                    addAll(batch)
                } else {
                    chunk.forEach { profile ->
                        val summary = try {
                            withContext(Dispatchers.IO) {
                                manager.measureDelayQuick(
                                    profile = profile,
                                    datDir = datDir,
                                    dpiCompatibilityEnabled = dpiCompatibilityEnabled.value
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failedDelaySummary(error.localizedMessage ?: error.javaClass.simpleName)
                        }
                        add(summary)
                    }
                }
                completed += chunk.size
                onProgress?.invoke(completed, profiles.size)
            }
        }
    }

    fun measureProfileDelay(profileId: String) {
        if (currentDelayProbeJob?.isActive == true || routeOptimizationJob?.isActive == true) {
            _statusMessage.value = "Wait for the current route test to finish."
            return
        }
        if (VpnStateRepository.state.value.tunnelState in setOf(TunnelState.Starting, TunnelState.Connected, TunnelState.Stopping)) {
            _profileDelayStates.value = _profileDelayStates.value + (
                profileId to ProfileDelayState(
                    error = "Stop VPN before running real-delay test.",
                    testedAt = System.currentTimeMillis()
                )
            )
            return
        }

        val stored = profiles.value.firstOrNull { it.id == profileId }
        if (stored == null) {
            _profileDelayStates.value = _profileDelayStates.value + (
                profileId to ProfileDelayState(error = "Profile not found", testedAt = System.currentTimeMillis())
            )
            return
        }

        if (_profileDelayStates.value[profileId]?.testing == true) {
            return
        }

        _profileDelayStates.value = _profileDelayStates.value + (profileId to ProfileDelayState(testing = true))

        val delayJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var completed = false
            try {
                val summary = try {
                    withContext(Dispatchers.IO) {
                        val profile = VlessUriParser.parse(stored.rawUri).getOrThrow()
                        XrayCoreManager().measureDelaySummary(
                            profile = profile,
                            datDir = getApplication<Application>().filesDir.absolutePath,
                            dpiCompatibilityEnabled = dpiCompatibilityEnabled.value
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failedDelaySummary(error.localizedMessage ?: error.javaClass.simpleName)
                }
                val cleanDelay = summary.medianMs?.takeIf { summary.success && it in 1..9999 }
                val error = if (cleanDelay != null) null else (summary.error ?: "Delay test failed")

                val persistenceError = try {
                    withContext(Dispatchers.IO) {
                        repository.recordProfileDelay(
                            id = profileId,
                            success = cleanDelay != null,
                            delayMs = cleanDelay,
                            error = error,
                            p95Ms = summary.p95Ms,
                            jitterMs = summary.jitterMs,
                            successfulSamples = summary.successfulSamples,
                            sampleCount = summary.totalSamples
                        )
                    }
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    error
                }
                if (persistenceError != null) {
                    _statusMessage.value = persistenceError.localizedMessage ?: "Failed to save delay result."
                }

                _profileDelayStates.value = _profileDelayStates.value + (
                    profileId to ProfileDelayState(
                        testing = false,
                        delayMs = cleanDelay,
                        p95Ms = summary.p95Ms,
                        jitterMs = summary.jitterMs,
                        successfulSamples = summary.successfulSamples,
                        sampleCount = summary.totalSamples,
                        error = error,
                        testedAt = System.currentTimeMillis()
                    )
                )
                completed = true
            } finally {
                if (!completed) {
                    clearInterruptedDelayTests(
                        ids = listOf(profileId),
                        message = "Delay test interrupted; run it again when ready."
                    )
                }
            }
        }
        currentDelayProbeJob = delayJob
        delayJob.invokeOnCompletion { cause ->
            if (currentDelayProbeJob === delayJob) currentDelayProbeJob = null
            if (cause != null) {
                clearInterruptedDelayTests(
                    ids = listOf(profileId),
                    message = "Delay test interrupted; run it again when ready."
                )
            }
        }
        delayJob.start()
    }

    fun refreshActiveCodespaceDelays() {
        if (currentDelayProbeJob?.isActive == true || routeOptimizationJob?.isActive == true) {
            _statusMessage.value = "Wait for the current route test to finish."
            return
        }
        if (VpnStateRepository.state.value.tunnelState in setOf(TunnelState.Starting, TunnelState.Connected, TunnelState.Stopping)) {
            _statusMessage.value = "Stop VPN before refreshing real delays."
            return
        }
        val candidates = activeCodespaceProfiles.value
        if (candidates.isEmpty()) {
            _statusMessage.value = "No configs are saved for this Codespace."
            return
        }
        val pending = candidates.filter { _profileDelayStates.value[it.id]?.testing != true }
        if (pending.isEmpty()) {
            _statusMessage.value = "A delay scan is already running for these configs."
            return
        }
        pending.forEach { stored ->
            _profileDelayStates.value = _profileDelayStates.value + (stored.id to ProfileDelayState(testing = true))
        }

        val delayJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var completed = false
            var failureMessage: String? = null
            try {
                _statusMessage.value = "Pinging ${pending.size} configs..."
                val summariesById = linkedMapOf<String, XrayDelaySummary>()
                val validProfiles = mutableListOf<Pair<StoredG2RayProfile, G2RayProfile>>()

                pending.forEach { stored ->
                    runCatching { VlessUriParser.parse(stored.rawUri).getOrThrow() }
                        .onSuccess { profile -> validProfiles += stored to profile }
                        .onFailure { error ->
                            summariesById[stored.id] = failedDelaySummary(
                                error.localizedMessage ?: error.javaClass.simpleName
                            )
                        }
                }

                val validSummaries = measureQuickProfiles(
                    profiles = validProfiles.map { it.second },
                    onProgress = { done, total ->
                        _statusMessage.value = "Pinging $done/$total configs..."
                    }
                )
                validProfiles.forEachIndexed { index, storedProfile ->
                    summariesById[storedProfile.first.id] = validSummaries.getOrNull(index)
                        ?: failedDelaySummary("No delay result was returned for this config")
                }

                // Never use a keyed lookup that can throw here. A partial native
                // response must become a visible per-config failure, not a
                // cancelled coroutine that leaves the button stuck.
                val results = pending.map { stored ->
                    stored to (summariesById[stored.id]
                        ?: failedDelaySummary("No delay result was returned for this config"))
                }

                val updates = results.map { (stored, summary) ->
                    val delay = summary.medianMs?.takeIf { summary.success && it in 1..9999 }
                    ProfileDelayUpdate(
                        id = stored.id,
                        success = delay != null,
                        delayMs = delay,
                        error = if (delay == null) summary.error ?: "Delay test failed" else null,
                        p95Ms = summary.p95Ms,
                        jitterMs = summary.jitterMs,
                        successfulSamples = summary.successfulSamples,
                        sampleCount = summary.totalSamples
                    )
                }
                val persistenceError = try {
                    withContext(Dispatchers.IO) {
                        repository.recordProfileDelays(updates)
                    }
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    error
                }
                val persistenceFailures = if (persistenceError == null) 0 else results.size

                results.forEachIndexed { index, (stored, summary) ->
                    val delay = summary.medianMs?.takeIf { summary.success && it in 1..9999 }
                    val error = if (delay == null) summary.error ?: "Delay test failed" else null
                    _profileDelayStates.value = _profileDelayStates.value + (
                        stored.id to ProfileDelayState(
                            testing = false,
                            delayMs = delay,
                            p95Ms = summary.p95Ms,
                            jitterMs = summary.jitterMs,
                            successfulSamples = summary.successfulSamples,
                            sampleCount = summary.totalSamples,
                            error = error,
                            testedAt = System.currentTimeMillis()
                        )
                    )
                    _statusMessage.value = "Checked ${index + 1}/${results.size} configs."
                }
                val successful = results.count { (_, summary) ->
                    summary.success && summary.medianMs?.let { it in 1..9999 } == true
                }
                _statusMessage.value = delayScanCompletionMessage(
                    total = results.size,
                    persistenceFailures = persistenceFailures,
                    successful = successful
                )
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failureMessage = "Ping all failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
                _statusMessage.value = failureMessage.orEmpty()
            } finally {
                if (!completed) {
                    clearInterruptedDelayTests(
                        ids = pending.map { it.id },
                        message = failureMessage ?: "Ping all was interrupted; try again.",
                    )
                }
            }
        }
        currentDelayProbeJob = delayJob
        delayJob.invokeOnCompletion { cause ->
            if (currentDelayProbeJob === delayJob) currentDelayProbeJob = null
            if (cause != null) {
                clearInterruptedDelayTests(
                    ids = pending.map { it.id },
                    message = "Delay scan interrupted; refresh the configs to try again."
                )
            }
            if (cause == null) viewModelScope.launch { autoOptimizeRoutesIfNeeded() }
        }
        delayJob.start()
    }

    private fun clearInterruptedDelayTests(ids: Collection<String>, message: String) {
        _profileDelayStates.value = clearInterruptedDelayStates(
            states = _profileDelayStates.value,
            ids = ids.toSet(),
            message = message,
            testedAt = System.currentTimeMillis()
        )
    }
}
