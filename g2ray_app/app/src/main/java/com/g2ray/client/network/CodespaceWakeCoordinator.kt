package com.g2ray.client.network

import com.g2ray.client.model.CodespaceWakeMode
import kotlinx.coroutines.withTimeoutOrNull

enum class WakeControlBackend {
    WORKER,
    GITHUB_DIRECT
}

enum class CodespaceWakePhase {
    WAKING,
    SETTLING
}

data class CodespaceWakeProgress(
    val backend: WakeControlBackend,
    val phase: CodespaceWakePhase,
    val message: String,
    val attempt: Int,
    val retryAfterMs: Long? = null
)

data class CodespaceWakeRequest(
    val mode: CodespaceWakeMode,
    val githubCodespaceName: String,
    val workerCodespaceId: String? = null,
    val workerBaseUrls: List<String> = emptyList(),
    val workerSecret: String? = null,
    val githubToken: String? = null
) {
    val workerConfigured: Boolean
        get() = workerBaseUrls.isNotEmpty() && !workerSecret.isNullOrBlank()

    val githubDirectConfigured: Boolean
        get() = githubCodespaceName.isNotBlank() && !githubToken.isNullOrBlank()
}

sealed interface CodespaceWakeOutcome {
    data class Ready(
        val backend: WakeControlBackend,
        val routeReady: Boolean,
        val workerResponse: WorkerWakeResponse? = null,
        val githubResponse: GitHubCodespaceResponse? = null
    ) : CodespaceWakeOutcome

    data class Failed(
        val message: String,
        val backend: WakeControlBackend? = null,
        val controlPlaneUnavailable: Boolean = false,
        val httpCode: Int? = null,
        val workerResponse: WorkerWakeResponse? = null,
        val githubResponse: GitHubCodespaceResponse? = null,
        val retryAllowed: Boolean = wakeHttpFailureRetryAllowed(httpCode),
        val retryAfterMs: Long? = null
    ) : CodespaceWakeOutcome

    data class TimedOut(
        val message: String,
        val backend: WakeControlBackend,
        val retryAfterMs: Long? = null
    ) : CodespaceWakeOutcome
}

object WakeBackendSelector {
    fun order(
        mode: CodespaceWakeMode,
        workerConfigured: Boolean,
        githubDirectConfigured: Boolean
    ): List<WakeControlBackend> = when (mode) {
        CodespaceWakeMode.AUTO -> buildList {
            if (workerConfigured) add(WakeControlBackend.WORKER)
            if (githubDirectConfigured) add(WakeControlBackend.GITHUB_DIRECT)
        }
        CodespaceWakeMode.WORKER -> if (workerConfigured) {
            listOf(WakeControlBackend.WORKER)
        } else {
            emptyList()
        }
        CodespaceWakeMode.GITHUB_DIRECT -> if (githubDirectConfigured) {
            listOf(WakeControlBackend.GITHUB_DIRECT)
        } else {
            emptyList()
        }
    }
}

internal data class WorkerWakeInvocation(
    val workerBaseUrls: List<String>,
    val secret: String,
    val codespaceId: String?,
    val maxWaitMs: Long,
    val expectedCodespaceName: String? = null,
    val preflightBeforeWake: Boolean = false
)

internal data class GitHubWakeInvocation(
    val codespaceName: String,
    val token: String,
    val maxWaitMs: Long,
    val reconcileUncertainWorkerWake: Boolean = false
)

class CodespaceWakeCoordinator internal constructor(
    private val workerWake: suspend (
        WorkerWakeInvocation,
        (WorkerRouteProgress) -> Unit
    ) -> WorkerRouteOutcome,
    private val githubWake: suspend (
        GitHubWakeInvocation,
        (GitHubCodespaceProgress) -> Unit
    ) -> GitHubCodespaceOutcome,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    constructor(
        workerCoordinator: WorkerRouteCoordinator,
        githubCoordinator: GitHubCodespaceCoordinator
    ) : this(
        workerWake = { request, progress ->
            workerCoordinator.ensureRouteReady(
                workerBaseUrls = request.workerBaseUrls,
                secret = request.secret,
                codespaceId = request.codespaceId,
                expectedCodespaceName = request.expectedCodespaceName,
                preflightBeforeWake = request.preflightBeforeWake,
                maxWaitMs = request.maxWaitMs,
                onProgress = progress
            )
        },
        githubWake = { request, progress ->
            githubCoordinator.ensureAvailable(
                codespaceName = request.codespaceName,
                token = request.token,
                maxWaitMs = request.maxWaitMs,
                reconcileUncertainWorkerWake = request.reconcileUncertainWorkerWake,
                onProgress = progress
            )
        }
    )

    suspend fun ensureReady(
        request: CodespaceWakeRequest,
        maxWaitMs: Long = 3 * 60 * 1000L,
        onProgress: (CodespaceWakeProgress) -> Unit = {}
    ): CodespaceWakeOutcome {
        var activeBackend = WakeBackendSelector.order(
            request.mode, request.workerConfigured, request.githubDirectConfigured
        ).firstOrNull() ?: WakeControlBackend.WORKER
        val retryCooldown = WakeRetryCooldown(nowMs)
        return withTimeoutOrNull(maxWaitMs.coerceAtLeast(1L)) {
            ensureReadyWithinDeadline(request, maxWaitMs, retryCooldown) { progress ->
                activeBackend = progress.backend
                onProgress(progress)
            }
        } ?: CodespaceWakeOutcome.TimedOut("Codespace wake deadline exceeded.", activeBackend, retryCooldown.remainingMs())
    }

    private suspend fun ensureReadyWithinDeadline(
        request: CodespaceWakeRequest,
        maxWaitMs: Long,
        retryCooldown: WakeRetryCooldown,
        onProgress: (CodespaceWakeProgress) -> Unit
    ): CodespaceWakeOutcome {
        val order = WakeBackendSelector.order(
            mode = request.mode,
            workerConfigured = request.workerConfigured,
            githubDirectConfigured = request.githubDirectConfigured
        )
        if (order.isEmpty()) {
            return CodespaceWakeOutcome.Failed(missingConfigurationMessage(request.mode), retryAllowed = false)
        }

        var reconcileUncertainWorkerWake = false
        for (backend in order) {
            when (backend) {
                WakeControlBackend.WORKER -> {
                    val outcome = workerWake(
                        WorkerWakeInvocation(
                            workerBaseUrls = request.workerBaseUrls,
                            secret = request.workerSecret.orEmpty(),
                            codespaceId = request.workerCodespaceId ?: request.githubCodespaceName,
                            maxWaitMs = maxWaitMs,
                            expectedCodespaceName = request.githubCodespaceName.takeIf { it.isNotBlank() },
                            preflightBeforeWake = request.mode == CodespaceWakeMode.AUTO && request.githubDirectConfigured
                        )
                    ) { progress ->
                        retryCooldown.record(progress.retryAfterMs)
                        onProgress(
                            CodespaceWakeProgress(
                                backend = backend,
                                phase = if (progress.phase == WorkerRoutePhase.WAKING) {
                                    CodespaceWakePhase.WAKING
                                } else {
                                    CodespaceWakePhase.SETTLING
                                },
                                message = progress.message,
                                attempt = progress.attempt,
                                retryAfterMs = progress.retryAfterMs?.let { retryCooldown.remainingMs() }
                            )
                        )
                    }
                    when (outcome) {
                        is WorkerRouteOutcome.Ready -> return CodespaceWakeOutcome.Ready(
                            backend = backend,
                            routeReady = true,
                            workerResponse = outcome.response
                        )
                        is WorkerRouteOutcome.TimedOut -> {
                            retryCooldown.record(outcome.retryAfterMs)
                            return CodespaceWakeOutcome.TimedOut(
                                message = outcome.message,
                                backend = backend,
                                retryAfterMs = retryCooldown.remainingMs()
                            )
                        }
                        is WorkerRouteOutcome.Failed -> {
                            if (outcome.retryAllowed) retryCooldown.record(outcome.retryAfterMs)
                            val directFallbackAvailable = request.mode == CodespaceWakeMode.AUTO &&
                                request.githubDirectConfigured &&
                                outcome.controlPlaneUnavailable &&
                                !(outcome.wakeMayBeInFlight && (retryCooldown.remainingMs() ?: 0L) > 0L)
                            if (directFallbackAvailable) {
                                reconcileUncertainWorkerWake = outcome.wakeMayBeInFlight
                                onProgress(
                                    CodespaceWakeProgress(
                                        backend = WakeControlBackend.GITHUB_DIRECT,
                                        phase = CodespaceWakePhase.WAKING,
                                        message = "Worker is unreachable. Falling back to GitHub directly...",
                                        attempt = 0,
                                        retryAfterMs = retryCooldown.remainingMs()
                                    )
                                )
                                continue
                            }
                            return CodespaceWakeOutcome.Failed(
                                message = outcome.message,
                                backend = backend,
                                controlPlaneUnavailable = outcome.controlPlaneUnavailable,
                                httpCode = outcome.httpCode,
                                workerResponse = outcome.response,
                                retryAllowed = outcome.retryAllowed,
                                retryAfterMs = if (outcome.retryAllowed) retryCooldown.remainingMs() else null
                            )
                        }
                    }
                }
                WakeControlBackend.GITHUB_DIRECT -> {
                    val outcome = githubWake(
                        GitHubWakeInvocation(
                            codespaceName = request.githubCodespaceName,
                            token = request.githubToken.orEmpty(),
                            maxWaitMs = maxWaitMs,
                            reconcileUncertainWorkerWake = reconcileUncertainWorkerWake
                        )
                    ) { progress ->
                        onProgress(
                            CodespaceWakeProgress(
                                backend = backend,
                                phase = if (progress.phase == GitHubCodespacePhase.WAITING) {
                                    CodespaceWakePhase.SETTLING
                                } else {
                                    CodespaceWakePhase.WAKING
                                },
                                message = progress.message,
                                attempt = progress.attempt
                            )
                        )
                    }
                    return when (outcome) {
                        is GitHubCodespaceOutcome.Ready -> CodespaceWakeOutcome.Ready(
                            backend = backend,
                            routeReady = false,
                            githubResponse = outcome.response
                        )
                        is GitHubCodespaceOutcome.TimedOut -> CodespaceWakeOutcome.TimedOut(
                            message = outcome.message,
                            backend = backend,
                            retryAfterMs = retryCooldown.remainingMs()
                        )
                        is GitHubCodespaceOutcome.Failed -> {
                            if (outcome.retryAllowed) retryCooldown.record(outcome.retryAfterMs)
                            CodespaceWakeOutcome.Failed(
                                message = outcome.message,
                                backend = backend,
                                controlPlaneUnavailable = outcome.controlPlaneUnavailable,
                                httpCode = outcome.httpCode,
                                githubResponse = outcome.response,
                                retryAllowed = outcome.retryAllowed,
                                retryAfterMs = if (outcome.retryAllowed) retryCooldown.remainingMs() else null
                            )
                        }
                    }
                }
            }
        }

        return CodespaceWakeOutcome.Failed("No configured Codespace wake method succeeded.", retryAllowed = false)
    }

    private fun missingConfigurationMessage(mode: CodespaceWakeMode): String = when (mode) {
        CodespaceWakeMode.AUTO -> "Configure either the shared Worker or a GitHub token for this Codespace."
        CodespaceWakeMode.WORKER -> "Worker mode is selected, but the shared Worker URL or wake key is missing."
        CodespaceWakeMode.GITHUB_DIRECT -> "Direct GitHub mode is selected, but this Codespace has no GitHub token."
    }
}
