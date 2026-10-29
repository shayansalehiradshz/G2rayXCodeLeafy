package com.g2ray.client.network

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class WorkerRouteCoordinatorTest {
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun stalledWorkerWakeIsCancelledThenReconciledThroughHealth() = runTest {
        var cancelled = false
        val api = FakeWorkerApiService(
            wakeHandler = { try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true } },
            healthHandler = { url ->
                Response.success(WorkerWakeResponse(ok = true, codespace = "demo",
                    route_ready = !url.endsWith("?route=false")))
            }
        )
        val result = WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime }).ensureRouteReady(
            listOf("worker.example"), "secret", maxWaitMs = 30_000L,
            expectedCodespaceName = "demo", preflightBeforeWake = true)
        assertTrue(result is WorkerRouteOutcome.Ready)
        assertTrue(cancelled)
        assertEquals(18_000L, testScheduler.currentTime)
        assertEquals(listOf("https://worker.example/api/health?route=false", "https://worker.example/wake",
            "https://worker.example/api/health"), api.calls)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cancelledWakeIoFailureDoesNotTriggerHealthOrAnotherWake() = runTest {
        var returned = false
        val api = FakeWorkerApiService(
            wakeHandler = { kotlinx.coroutines.currentCoroutineContext().cancel(); throw IOException("cancelled") },
            healthHandler = { error("Cancelled wake must not poll") }
        )
        val job = launch {
            WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime }).ensureRouteReady(
                listOf("worker.example", "backup.example"), "secret")
            returned = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(false, returned)
        assertEquals(listOf("https://worker.example/wake"), api.calls)
    }
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun lostWorkerWakeResponseChecksHealthWithoutRepeatingWake() = runTest {
        var healthChecks = 0
        val api = FakeWorkerApiService(
            wakeHandler = { throw IOException("response lost") },
            healthHandler = {
                healthChecks++
                if (healthChecks == 1) Response.success(WorkerWakeResponse(ok = true, codespace = "demo", state = "Starting", pending_operation = true, route_ready = false, poll_after_seconds = 2))
                else Response.success(WorkerWakeResponse(ok = true, codespace = "demo", state = "Available", pending_operation = false, route_ready = true))
            }
        )
        val result = WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime }).ensureRouteReady(
            listOf("worker.example", "backup.example"), "secret", expectedCodespaceName = "demo")
        assertTrue(result is WorkerRouteOutcome.Ready)
        assertEquals(listOf("https://worker.example/wake", "https://worker.example/api/health", "https://worker.example/api/health"), api.calls)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun lostWorkerWakeWithStoppedHostReturnsRetryableWithoutAnotherPost() = runTest {
        val api = FakeWorkerApiService(
            wakeHandler = { throw IOException("response lost") },
            healthHandler = { Response.success(WorkerWakeResponse(ok = true, codespace = "demo", state = "Shutdown", pending_operation = false, route_ready = false, poll_after_seconds = 2)) }
        )
        val result = WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime }).ensureRouteReady(
            listOf("worker.example"), "secret", expectedCodespaceName = "demo") as WorkerRouteOutcome.Failed
        assertTrue(result.retryAllowed)
        assertTrue(result.wakeMayBeInFlight)
        assertEquals(3_000L, result.retryAfterMs)
        assertEquals(listOf("https://worker.example/wake", "https://worker.example/api/health", "https://worker.example/api/health"), api.calls)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun ambiguousWorkerFailurePreservesRetryDelayWithoutRepeatingWake() = runTest {
        val api = FakeWorkerApiService(
            wakeHandler = { url ->
                if (url.contains("primary.example")) workerError(503, """{"error":"busy"}""",
                    mapOf("Retry-After" to "180")) else throw IOException("response lost")
            },
            healthHandler = { Response.success(WorkerWakeResponse(ok = true, codespace = "demo", state = "Shutdown",
                pending_operation = false, route_ready = false, poll_after_seconds = 2)) }
        )
        val result = WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime }).ensureRouteReady(
            listOf("primary.example", "backup.example"), "secret", expectedCodespaceName = "demo") as WorkerRouteOutcome.Failed
        assertEquals(175_000L, result.retryAfterMs)
        assertTrue(result.wakeMayBeInFlight)
        assertEquals(1, api.calls.count { it.endsWith("/wake") })
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun unreachableHealthAfterLostWakeCarriesUncertaintyToGitHub() = runTest {
        var reconciles = false
        val api = FakeWorkerApiService(
            wakeHandler = { throw IOException("response lost") },
            healthHandler = { url ->
                if (url.endsWith("?route=false")) Response.success(WorkerWakeResponse(ok = true, codespace = "demo"))
                else throw java.net.ConnectException("health unreachable")
            }
        )
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { request, progress -> WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime })
                .ensureRouteReady(request.workerBaseUrls, request.secret, maxWaitMs = request.maxWaitMs,
                    expectedCodespaceName = request.expectedCodespaceName,
                    preflightBeforeWake = request.preflightBeforeWake, onProgress = progress) },
            githubWake = { request, _ ->
                reconciles = request.reconcileUncertainWorkerWake
                GitHubCodespaceOutcome.Ready(GitHubCodespaceResponse(state = "Available"))
            }, nowMs = { testScheduler.currentTime }
        )
        val result = coordinator.ensureReady(CodespaceWakeRequest(
            com.g2ray.client.model.CodespaceWakeMode.AUTO, "demo", workerBaseUrls = listOf("worker.example"),
            workerSecret = "secret", githubToken = "token"
        ))
        assertTrue(result is CodespaceWakeOutcome.Ready)
        assertTrue(reconciles)
        assertEquals(listOf("https://worker.example/api/health?route=false", "https://worker.example/wake", "https://worker.example/api/health"), api.calls)
    }

    @Test
    fun uncertainWorkerWakeDoesNotBypassRetryDelayThroughGitHub() = runTest {
        var directCalls = 0
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { _, _ -> WorkerRouteOutcome.Failed("Worker unavailable", controlPlaneUnavailable = true,
                retryAfterMs = 180_000L, wakeMayBeInFlight = true) },
            githubWake = { _, _ -> directCalls++; error("Retry delay must be honored") },
            nowMs = { 0L }
        )
        val result = coordinator.ensureReady(CodespaceWakeRequest(
            com.g2ray.client.model.CodespaceWakeMode.AUTO, "demo", workerBaseUrls = listOf("worker.example"),
            workerSecret = "secret", githubToken = "token"
        )) as CodespaceWakeOutcome.Failed
        assertEquals(0, directCalls)
        assertEquals(180_000L, result.retryAfterMs)
    }
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun autoPreflightTimeoutFallsBackWithoutSendingWorkerWake() = runTest {
        var healthCancelled = false
        var directCalls = 0
        val api = FakeWorkerApiService(
            wakeHandler = { error("Preflight timeout must not send a Worker wake") },
            healthHandler = {
                try { kotlinx.coroutines.awaitCancellation() } finally { healthCancelled = true }
            }
        )
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { request, progress -> WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime })
                .ensureRouteReady(request.workerBaseUrls, request.secret, maxWaitMs = request.maxWaitMs,
                    preflightBeforeWake = request.preflightBeforeWake, onProgress = progress) },
            githubWake = { _, _ ->
                directCalls++
                GitHubCodespaceOutcome.Ready(GitHubCodespaceResponse(state = "Available"))
            },
            nowMs = { testScheduler.currentTime }
        )
        val result = coordinator.ensureReady(CodespaceWakeRequest(
            com.g2ray.client.model.CodespaceWakeMode.AUTO, "demo", workerBaseUrls = listOf("worker.example"),
            workerSecret = "secret", githubToken = "token"
        ), maxWaitMs = 30_000L)
        assertTrue(result is CodespaceWakeOutcome.Ready)
        assertEquals(1, directCalls)
        assertEquals(8_000L, testScheduler.currentTime)
        assertTrue(healthCancelled)
        assertEquals(listOf("https://worker.example/api/health?route=false"), api.calls)
    }

    @Test
    fun healthyPreflightUsesWorkerWakeAndPreservesIdentity() = runTest {
        val api = FakeWorkerApiService(
            wakeHandler = { Response.success(WorkerWakeResponse(ok = true, codespace = "demo", route_ready = true)) },
            healthHandler = { Response.success(WorkerWakeResponse(ok = true, codespace = "demo", state = "Shutdown")) }
        )
        val result = WorkerRouteCoordinator(api, nowMs = { 0L }).ensureRouteReady(
            listOf("worker.example"), "secret", expectedCodespaceName = "demo", preflightBeforeWake = true
        )
        assertTrue(result is WorkerRouteOutcome.Ready)
        assertEquals(listOf("https://worker.example/api/health?route=false", "https://worker.example/wake"), api.calls)
    }

    @Test
    fun preflightRejectsWrongCodespaceAndDoesNotWake() = runTest {
        val api = FakeWorkerApiService(
            wakeHandler = { error("Wrong identity must not be started") },
            healthHandler = { Response.success(WorkerWakeResponse(ok = true, codespace = "other")) }
        )
        val result = WorkerRouteCoordinator(api, nowMs = { 0L }).ensureRouteReady(
            listOf("worker.example"), "secret", expectedCodespaceName = "demo", preflightBeforeWake = true
        ) as WorkerRouteOutcome.Failed
        assertEquals(false, result.retryAllowed)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun preflightRateLimitDoesNotWakeOrFallBack() = runTest {
        val api = FakeWorkerApiService(
            wakeHandler = { error("Rate limited Worker must not wake") },
            healthHandler = { workerError(429, """{"reason":"github_rate_limited","retry_after_seconds":180}""",
                mapOf("Retry-After" to "180")) }
        )
        val result = WorkerRouteCoordinator(api, nowMs = { 0L }).ensureRouteReady(
            listOf("worker.example"), "secret", preflightBeforeWake = true
        ) as WorkerRouteOutcome.Failed
        assertEquals(false, result.controlPlaneUnavailable)
        assertEquals(180_000L, result.retryAfterMs)
        assertEquals(1, api.calls.size)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun preflightTimeoutPreservesEarlierAliasRetryDelay() = runTest {
        val api = FakeWorkerApiService(
            wakeHandler = { error("Cooldown must prevent wake") },
            healthHandler = { url ->
                if (url.contains("primary.example")) workerError(503, """{"error":"busy"}""",
                    mapOf("Retry-After" to "180")) else kotlinx.coroutines.awaitCancellation()
            }
        )
        val result = WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime }).ensureRouteReady(
            listOf("primary.example", "backup.example"), "secret", maxWaitMs = 30_000,
            preflightBeforeWake = true
        ) as WorkerRouteOutcome.TimedOut
        assertEquals(172_000L, result.retryAfterMs)
        assertEquals(2, api.calls.size)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cancellingPreflightDoesNotStartEitherBackend() = runTest {
        var cancelled = false
        var directCalls = 0
        val api = FakeWorkerApiService(
            wakeHandler = { error("Cancelled preflight must not wake") },
            healthHandler = { try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true } }
        )
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { request, progress -> WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime })
                .ensureRouteReady(request.workerBaseUrls, request.secret, maxWaitMs = request.maxWaitMs,
                    preflightBeforeWake = request.preflightBeforeWake, onProgress = progress) },
            githubWake = { _, _ -> directCalls++; error("Cancelled preflight must not use GitHub") },
            nowMs = { testScheduler.currentTime }
        )
        val job = launch {
            coordinator.ensureReady(CodespaceWakeRequest(
                com.g2ray.client.model.CodespaceWakeMode.AUTO, "demo", workerBaseUrls = listOf("worker.example"),
                workerSecret = "secret", githubToken = "token"
            ))
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(0, directCalls)
        assertEquals(listOf("https://worker.example/api/health?route=false"), api.calls)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun acceptedWorkerWakeSettlesWithoutDirectFallback() = runTest {
        var directCalls = 0
        val api = FakeWorkerApiService(
            wakeHandler = { Response.success(202, WorkerWakeResponse(ok = true, codespace = "demo",
                start_accepted = true, route_ready = false, poll_after_seconds = 2)) },
            healthHandler = { url ->
                if (url.endsWith("?route=false")) Response.success(WorkerWakeResponse(ok = true, codespace = "demo"))
                else kotlinx.coroutines.awaitCancellation()
            }
        )
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { request, progress -> WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime })
                .ensureRouteReady(request.workerBaseUrls, request.secret, maxWaitMs = request.maxWaitMs,
                    expectedCodespaceName = request.expectedCodespaceName,
                    preflightBeforeWake = request.preflightBeforeWake, onProgress = progress) },
            githubWake = { _, _ -> directCalls++; error("Accepted wake must not use GitHub") },
            nowMs = { testScheduler.currentTime }
        )
        val result = coordinator.ensureReady(CodespaceWakeRequest(
            com.g2ray.client.model.CodespaceWakeMode.AUTO, "demo", workerBaseUrls = listOf("worker.example"),
            workerSecret = "secret", githubToken = "token"
        ), maxWaitMs = 5_000L)
        assertTrue(result is CodespaceWakeOutcome.TimedOut)
        assertEquals(0, directCalls)
        assertEquals(listOf("https://worker.example/api/health?route=false", "https://worker.example/wake",
            "https://worker.example/api/health"), api.calls)
    }
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun successfulWakeSuppressionDoesNotBecomeAGlobalRecoveryCooldown() = runTest {
        val api = FakeWorkerApiService(
            wakeHandler = {
                workerError(429, """{"reason":"wake_recently_succeeded","retry_after_seconds":180}""",
                    mapOf("Retry-After" to "180"))
            },
            healthHandler = {
                Response.success(WorkerWakeResponse(ok = true, route_ready = false, poll_after_seconds = 2))
            }
        )
        val hints = mutableListOf<Long>()
        val result = WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime }).ensureRouteReady(
            listOf("worker.example"), "secret", maxWaitMs = 2_500L,
            onProgress = { it.retryAfterMs?.let(hints::add) }
        ) as WorkerRouteOutcome.TimedOut
        assertEquals(null, result.retryAfterMs)
        assertTrue(hints.isEmpty())
        assertEquals(listOf("https://worker.example/wake", "https://worker.example/api/health"), api.calls)
        assertEquals(2_500L, testScheduler.currentTime)
    }

    @Test
    fun malformedWorkerUrlsDoNotEchoPastedCredentials() {
        for (url in listOf(
            "https://user:private-token@worker.example:65536/wake",
            "https://user:private-token@worker.example/%ZZ",
            "https://worker.example/private-token"
        )) {
            val error = runCatching { WorkerUrlNormalizer.baseUrl(url) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(false, error?.message?.contains("private-token"))
        }
    }

    @Test
    fun invalidPrimaryDoesNotDiscardAValidCanonicalBackup() = runTest {
        val api = FakeWorkerApiService(wakeHandler = {
            Response.success(WorkerWakeResponse(ok = true, route_ready = true))
        })
        val result = WorkerRouteCoordinator(api, nowMs = { 0L }).ensureRouteReady(
            listOf("https://worker.example:65536", "HTTPS://BACKUP.example:443/wake"), "secret"
        ) as WorkerRouteOutcome.Ready
        assertEquals("https://backup.example", result.workerBaseUrl)
        assertEquals(listOf("https://backup.example/wake"), api.calls)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun callerCancellationStillStopsTheBackupWithoutReturningATimeout() = runTest {
        var cancelled = false
        var returned = false
        val api = FakeWorkerApiService(wakeHandler = { url ->
            if (url.contains("primary.example")) {
                workerError(503, """{"error":"temporary"}""", mapOf("Retry-After" to "600"))
            } else {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        })
        val job = launch {
            WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime }).ensureRouteReady(
                listOf("primary.example", "backup.example"), "secret", preflightBeforeWake = true
            )
            returned = true
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(false, returned)
        assertEquals(2, api.calls.size)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun successfulDirectFallbackDoesNotWaitForTheUnavailableWorker() = runTest {
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { _, _ -> WorkerRouteOutcome.Failed(
                "Worker unavailable", controlPlaneUnavailable = true, httpCode = 503, retryAfterMs = 180_000L
            ) },
            githubWake = { _, _ -> GitHubCodespaceOutcome.Ready(GitHubCodespaceResponse(state = "Available")) },
            nowMs = { testScheduler.currentTime }
        )
        val result = coordinator.ensureReady(CodespaceWakeRequest(
            com.g2ray.client.model.CodespaceWakeMode.AUTO, "demo",
            workerBaseUrls = listOf("worker.example"), workerSecret = "secret", githubToken = "token"
        )) as CodespaceWakeOutcome.Ready
        assertEquals(WakeControlBackend.GITHUB_DIRECT, result.backend)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun aReturnedDirectTimeoutRetainsTheRemainingWorkerCooldown() = runTest {
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { _, _ -> WorkerRouteOutcome.Failed(
                "Worker unavailable", controlPlaneUnavailable = true, httpCode = 503, retryAfterMs = 180_000L
            ) },
            githubWake = { _, _ ->
                kotlinx.coroutines.delay(60_000L)
                GitHubCodespaceOutcome.TimedOut("GitHub deadline exceeded")
            },
            nowMs = { testScheduler.currentTime }
        )
        val result = coordinator.ensureReady(CodespaceWakeRequest(
            com.g2ray.client.model.CodespaceWakeMode.AUTO, "demo",
            workerBaseUrls = listOf("worker.example"), workerSecret = "secret", githubToken = "token"
        ), maxWaitMs = 300_000L) as CodespaceWakeOutcome.TimedOut
        assertEquals(120_000L, result.retryAfterMs)
        assertEquals(WakeControlBackend.GITHUB_DIRECT, result.backend)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun healthDeadlinePreservesTheReportedCooldownAndCancelsTheHungAlias() = runTest {
        var cancelled = false
        val api = FakeWorkerApiService(wakeHandler = { url ->
            if (url.contains("primary.example")) {
                workerError(503, """{"error":"temporary"}""", mapOf("Retry-After" to "600"))
            } else {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        })
        val result = WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime }).ensureRouteReady(
            listOf("primary.example", "backup.example"), "secret", maxWaitMs = 1_500L, preflightBeforeWake = true
        ) as WorkerRouteOutcome.TimedOut
        assertEquals(599_500L, result.retryAfterMs)
        assertEquals(500L, testScheduler.currentTime)
        assertTrue(cancelled)
        assertEquals(2, api.calls.size)
        assertTrue(api.calls.none { it.endsWith("/wake") })
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun sharedWakeDeadlineKeepsTheWorkerCooldownEvenBeforeAnOutcomeReturns() = runTest {
        var cancelled = false
        val api = FakeWorkerApiService(wakeHandler = {
            Response.success(202, WorkerWakeResponse(ok = true, start_accepted = true,
                route_ready = false, poll_after_seconds = 2))
        }, healthHandler = { url ->
            if (url.contains("primary.example")) {
                workerError(503, """{"error":"temporary"}""", mapOf("Retry-After" to "600"))
            } else {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        })
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { request, progress ->
                WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime }).ensureRouteReady(
                    request.workerBaseUrls, request.secret, maxWaitMs = request.maxWaitMs, onProgress = progress
                )
            },
            githubWake = { _, _ -> error("Worker mode must not use direct GitHub") },
            nowMs = { testScheduler.currentTime }
        )
        val result = coordinator.ensureReady(CodespaceWakeRequest(
            com.g2ray.client.model.CodespaceWakeMode.WORKER, "demo",
            workerBaseUrls = listOf("primary.example", "backup.example"), workerSecret = "secret"
        ), maxWaitMs = 2_500L) as CodespaceWakeOutcome.TimedOut
        assertEquals(599_500L, result.retryAfterMs)
        assertEquals(2_500L, testScheduler.currentTime)
        assertEquals(WakeControlBackend.WORKER, result.backend)
        assertTrue(cancelled)
        assertEquals(3, api.calls.size)
        assertEquals(1, api.calls.count { it.endsWith("/wake") })
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun failedDirectFallbackConsumesTheWorkerCooldown() = runTest {
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { _, _ -> WorkerRouteOutcome.Failed(
                "Worker unavailable", controlPlaneUnavailable = true, httpCode = 503, retryAfterMs = 180_000L
            ) },
            githubWake = { _, _ ->
                kotlinx.coroutines.delay(60_000L)
                GitHubCodespaceOutcome.Failed("GitHub unavailable", controlPlaneUnavailable = true)
            },
            nowMs = { testScheduler.currentTime }
        )
        val result = coordinator.ensureReady(CodespaceWakeRequest(
            com.g2ray.client.model.CodespaceWakeMode.AUTO, "demo",
            workerBaseUrls = listOf("worker.example"), workerSecret = "secret", githubToken = "token"
        ), maxWaitMs = 300_000L) as CodespaceWakeOutcome.Failed
        assertEquals(120_000L, result.retryAfterMs)
        assertTrue(result.retryAllowed)
        assertEquals(WakeControlBackend.GITHUB_DIRECT, result.backend)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun sharedWakeDeadlinePreservesCooldownWhenDirectFallbackHangs() = runTest {
        var cancelled = false
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { _, _ -> WorkerRouteOutcome.Failed(
                "Worker unavailable", controlPlaneUnavailable = true, httpCode = 503, retryAfterMs = 180_000L
            ) },
            githubWake = { _, _ ->
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
            nowMs = { testScheduler.currentTime }
        )
        val result = coordinator.ensureReady(CodespaceWakeRequest(
            com.g2ray.client.model.CodespaceWakeMode.AUTO, "demo",
            workerBaseUrls = listOf("worker.example"), workerSecret = "secret", githubToken = "token"
        ), maxWaitMs = 500L) as CodespaceWakeOutcome.TimedOut
        assertEquals(179_500L, result.retryAfterMs)
        assertEquals(WakeControlBackend.GITHUB_DIRECT, result.backend)
        assertTrue(cancelled)
    }

    @Test
    fun invalidWorkerAuthorityIsATerminalConfigurationResult() = runTest {
        val api = WorkerNetwork.createApiService(loggingEnabled = false)
        for (url in listOf("https://worker.example:65536", "https://worker.example:0", "https://:443")) {
            val result = WorkerRouteCoordinator(api, nowMs = { 0L })
                .ensureRouteReady(listOf(url), "secret") as WorkerRouteOutcome.Failed
            assertEquals(false, result.retryAllowed)
        }
    }

    @Test
    fun workerAliasesCanonicalizeHostSchemeAndDefaultPort() {
        assertEquals("https://worker.example", WorkerUrlNormalizer.baseUrl("HTTPS://WORKER.example:443/wake"))
        assertEquals("https://worker.example:8443", WorkerUrlNormalizer.baseUrl("https://WORKER.example:8443/api/health"))
        assertEquals("https://[2001:db8::1]:8443", WorkerUrlNormalizer.baseUrl("https://[2001:db8::1]:8443/wake"))
    }

    @Test
    fun workerUrlDoesNotAcceptCredentialsInItsAuthority() {
        for (url in listOf("https://user:password@worker.example/wake", "https://@worker.example")) {
            val error = runCatching { WorkerUrlNormalizer.baseUrl(url) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }

    @Test
    fun slowUnreachableAliasesConsumeRatherThanRestartTheOriginalCooldown() = runTest {
        for ((elapsed, expectedRemaining) in listOf(60_000L to 120_000L, 190_000L to 0L)) {
            var elapsedMs = 0L
            val api = FakeWorkerApiService(wakeHandler = { url ->
                if (url.contains("primary.example")) {
                    workerError(503, """{"error":"temporary"}""", mapOf("Retry-After" to "180"))
                } else {
                    elapsedMs = elapsed
                    throw java.net.ConnectException("backup connection failed")
                }
            })
            val result = WorkerRouteCoordinator(api, nowMs = { elapsedMs }).ensureRouteReady(
                listOf("primary.example", "backup.example", "last.example"), "secret", maxWaitMs = 300_000L,
                preflightBeforeWake = true
            ) as WorkerRouteOutcome.Failed
            assertTrue(result.retryAllowed)
            assertEquals(expectedRemaining, result.retryAfterMs)
            assertEquals(3, api.calls.size)
        }
    }

    @Test
    fun failedWorkerAliasesPreserveTheLaterRetryBoundary() = runTest {
        for ((backupHint, networkFailure, expectedMs) in listOf(
            Triple(null, false, 180_000L),
            Triple("120", false, 180_000L),
            Triple("240", false, 240_000L),
            Triple(null, true, 180_000L)
        )) {
            val api = FakeWorkerApiService(wakeHandler = { url ->
                if (url.contains("primary.example")) {
                    workerError(503, """{"error":"temporary"}""", mapOf("Retry-After" to "180"))
                } else {
                    if (networkFailure) throw java.net.ConnectException("blocked backup")
                    workerError(503, """{"error":"temporary"}""",
                        backupHint?.let { mapOf("Retry-After" to it) }.orEmpty())
                }
            })
            val result = WorkerRouteCoordinator(api, nowMs = { 0L }).ensureRouteReady(
                listOf("primary.example", "backup.example"), "secret", preflightBeforeWake = true
            ) as WorkerRouteOutcome.Failed
            assertTrue(result.retryAllowed)
            assertEquals(expectedMs, result.retryAfterMs)
            assertEquals(2, api.calls.size)
        }
    }

    @Test
    fun terminalBackupFailureDoesNotBecomeRetryableBecauseAnotherAliasHadACooldown() = runTest {
        val api = FakeWorkerApiService(wakeHandler = { url ->
            if (url.contains("primary.example")) {
                workerError(503, """{"error":"temporary"}""", mapOf("Retry-After" to "180"))
            } else {
                workerError(401, """{"error":"unauthorized"}""", emptyMap())
            }
        })
        val result = WorkerRouteCoordinator(api, nowMs = { 0L }).ensureRouteReady(
            listOf("primary.example", "backup.example"), "secret", preflightBeforeWake = true
        ) as WorkerRouteOutcome.Failed
        assertEquals(false, result.retryAllowed)
        assertEquals(null, result.retryAfterMs)
        assertEquals(2, api.calls.size)
    }

    @Test
    fun timeSpentContactingAFailedAliasDoesNotRestartTheEarlierCooldown() = runTest {
        var elapsedMs = 0L
        val api = FakeWorkerApiService(wakeHandler = { url ->
            if (url.contains("primary.example")) {
                workerError(503, """{"error":"temporary"}""", mapOf("Retry-After" to "180"))
            } else {
                elapsedMs = 60_000L
                workerError(503, """{"error":"temporary"}""", emptyMap())
            }
        })
        val result = WorkerRouteCoordinator(api, nowMs = { elapsedMs }).ensureRouteReady(
            listOf("primary.example", "backup.example"), "secret", preflightBeforeWake = true
        ) as WorkerRouteOutcome.Failed
        assertEquals(120_000L, result.retryAfterMs)
        assertEquals(2, api.calls.size)
    }

    @Test
    fun failedDirectFallbackCannotDiscardTheWorkerRetryBoundary() = runTest {
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { _, _ -> WorkerRouteOutcome.Failed(
                "Worker unavailable", controlPlaneUnavailable = true, httpCode = 503, retryAfterMs = 180_000L
            ) },
            githubWake = { _, _ -> GitHubCodespaceOutcome.Failed("GitHub unavailable", controlPlaneUnavailable = true) },
            nowMs = { 0L }
        )
        val result = coordinator.ensureReady(CodespaceWakeRequest(
            com.g2ray.client.model.CodespaceWakeMode.AUTO, "demo",
            workerBaseUrls = listOf("worker.example"), workerSecret = "secret", githubToken = "token"
        )) as CodespaceWakeOutcome.Failed
        assertTrue(result.retryAllowed)
        assertEquals(180_000L, result.retryAfterMs)
        assertEquals(WakeControlBackend.GITHUB_DIRECT, result.backend)
    }

    @Test
    fun primaryResetAndBothRetryHintsUseTheLatestValidBoundary() = runTest {
        val api = FakeWorkerApiService(wakeHandler = {
            workerError(429, """{"reason":"github_rate_limited","retry_after_seconds":180,"retry_after_epoch":1600,"github_rate_limit_remaining":0}""",
                mapOf("Retry-After" to "120"))
        })
        val result = WorkerRouteCoordinator(api, nowMs = { 0L }, nowEpochMs = { 1_000_000L })
            .ensureRouteReady(listOf("worker.example"), "secret") as WorkerRouteOutcome.Failed
        assertTrue(result.retryAllowed)
        assertEquals(600_000L, result.retryAfterMs)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun aNonExhaustedPrimaryLimitDoesNotExtendTheSecondaryCooldown() = runTest {
        val api = FakeWorkerApiService(wakeHandler = {
            workerError(429, """{"reason":"github_secondary_rate_limited","retry_after_seconds":180,"retry_after_epoch":1600,"github_rate_limit_remaining":5}""",
                mapOf("Retry-After" to "120"))
        })
        val result = WorkerRouteCoordinator(api, nowMs = { 0L }, nowEpochMs = { 1_000_000L })
            .ensureRouteReady(listOf("worker.example"), "secret") as WorkerRouteOutcome.Failed
        assertEquals(180_000L, result.retryAfterMs)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun malformedHeaderCannotHideAValidBodyHint() = runTest {
        val api = FakeWorkerApiService(wakeHandler = {
            workerError(429, """{"retry_after_seconds":180}""", mapOf("Retry-After" to "invalid"))
        })
        val result = WorkerRouteCoordinator(api, nowMs = { 0L })
            .ensureRouteReady(listOf("worker.example"), "secret") as WorkerRouteOutcome.Failed
        assertEquals(180_000L, result.retryAfterMs)
    }

    @Test
    fun quotaAndUnstartableStatesInSuccessfulResponsesRemainTerminal() = runTest {
        for (body in listOf(
            WorkerWakeResponse(ok = false, quota_blocked = true, route_ready = false),
            WorkerWakeResponse(ok = true, state = "Failed", route_ready = false),
            WorkerWakeResponse(ok = true, state = "Archived", route_ready = false),
            WorkerWakeResponse(ok = true, state = "Deleted", route_ready = false)
        )) {
            val api = FakeWorkerApiService(wakeHandler = { Response.success(body) })
            val result = WorkerRouteCoordinator(api, nowMs = { 0L })
                .ensureRouteReady(listOf("worker.example", "backup.example"), "secret") as WorkerRouteOutcome.Failed
            assertEquals(false, result.retryAllowed)
            assertEquals(1, api.calls.size)
        }
    }

    @Test
    fun identityMismatchStaysTerminalThroughAutomaticBackendSelection() = runTest {
        val api = FakeWorkerApiService(wakeHandler = {
            Response.success(WorkerWakeResponse(ok = true, route_ready = true, codespace = "wrong-space"))
        })
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { _, _ -> WorkerRouteCoordinator(api, nowMs = { 0L }).ensureRouteReady(
                listOf("worker.example"), "secret", expectedCodespaceName = "demo"
            ) },
            githubWake = { _, _ -> error("Identity errors must not switch backends") }
        )
        val result = coordinator.ensureReady(CodespaceWakeRequest(
            com.g2ray.client.model.CodespaceWakeMode.AUTO, "demo",
            workerBaseUrls = listOf("worker.example"), workerSecret = "secret", githubToken = "token"
        )) as CodespaceWakeOutcome.Failed
        assertEquals(false, result.retryAllowed)
        assertEquals(1, api.calls.size)
    }

    private fun workerError(code: Int, json: String, headers: Map<String, String>): Response<WorkerWakeResponse> {
        val response = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://worker.example/wake").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("Worker response")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return Response.error(json.toResponseBody("application/json".toMediaType()), response)
    }

    @Test
    fun knownWorkerConfigurationErrorsDoNotBecomeEndlessTransientRetries() = runTest {
        for (error in listOf("missing_wake_secret", "missing_codespace_name", "missing_github_token_binding")) {
            val api = FakeWorkerApiService(wakeHandler = {
                Response.error(500, """{"error":"$error"}"""
                    .toResponseBody("application/json".toMediaType()))
            })
            val result = WorkerRouteCoordinator(api, nowMs = { 0L })
                .ensureRouteReady(listOf("worker.example"), "secret") as WorkerRouteOutcome.Failed
            assertEquals(error, false, result.retryAllowed)
            assertEquals(1, api.calls.size)
        }
    }

    @Test
    fun aHealthyDifferentCodespaceCannotSatisfyTheRequestedWake() = runTest {
        val api = FakeWorkerApiService(wakeHandler = {
            Response.success(WorkerWakeResponse(ok = true, route_ready = true, codespace = "wrong-space"))
        })
        val result = WorkerRouteCoordinator(api, nowMs = { 0L }).ensureRouteReady(
            listOf("worker.example"), "secret", expectedCodespaceName = "selected-space"
        )
        assertTrue(result is WorkerRouteOutcome.Failed)
        assertEquals(false, (result as WorkerRouteOutcome.Failed).retryAllowed)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun workerRateLimitPreservesRetryHintThroughAutoWithoutBypassingIt() = runTest {
        val api = FakeWorkerApiService(wakeHandler = {
            Response.error(429, """{"reason":"github_rate_limited","retry_after_seconds":180}"""
                .toResponseBody("application/json".toMediaType()))
        })
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { _, _ -> WorkerRouteCoordinator(api, nowMs = { 0L })
                .ensureRouteReady(listOf("worker.example"), "secret") },
            githubWake = { _, _ -> error("Rate limits must not trigger direct-GitHub bypass") },
            nowMs = { 0L }
        )
        val result = coordinator.ensureReady(CodespaceWakeRequest(
            com.g2ray.client.model.CodespaceWakeMode.AUTO, "demo",
            workerBaseUrls = listOf("worker.example"), workerSecret = "secret", githubToken = "token"
        )) as CodespaceWakeOutcome.Failed
        assertTrue(result.retryAllowed)
        assertEquals(180_000L, result.retryAfterMs)
        assertEquals(WakeControlBackend.WORKER, result.backend)
        assertEquals(1, api.calls.size)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun deadlineCancelsAnInFlightWorkerRequestNotJustItsPollLoop() = runTest {
        var cancelled = false
        val api = FakeWorkerApiService(wakeHandler = {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                cancelled = true
            }
        })
        val result = WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime }).ensureRouteReady(
            listOf("worker.example"), "secret", maxWaitMs = 500L
        )
        assertTrue(result is WorkerRouteOutcome.TimedOut)
        assertTrue(cancelled)
        assertEquals(500L, testScheduler.currentTime)
    }

    private class FakeWorkerApiService(
        private val wakeHandler: suspend (String) -> Response<WorkerWakeResponse>,
        private val healthHandler: suspend (String) -> Response<WorkerWakeResponse> = wakeHandler
    ) : WorkerApiService {
        val calls = mutableListOf<String>()
        val codespaceRequests = mutableListOf<CodespaceRequest>()

        override suspend fun wake(url: String, authorization: String, body: CodespaceRequest): Response<WorkerWakeResponse> {
            calls += url
            codespaceRequests += body
            return wakeHandler(url)
        }

        override suspend fun health(url: String, authorization: String, body: CodespaceRequest): Response<WorkerWakeResponse> {
            calls += url
            codespaceRequests += body
            return healthHandler(url)
        }
    }

    @Test
    fun unreachablePrimaryUsesBackupWorker() = runTest {
        val api = FakeWorkerApiService(wakeHandler = { url ->
            if (url.contains("primary.example")) throw java.net.ConnectException("blocked")
            Response.success(WorkerWakeResponse(ok = true, route_ready = true))
        })
        val coordinator = WorkerRouteCoordinator(api, nowMs = { 0L }, sleep = {})

        val result = coordinator.ensureRouteReady(
            listOf("https://primary.example", "https://backup.example"),
            "secret"
        )

        assertTrue(result is WorkerRouteOutcome.Ready)
        assertEquals("https://backup.example", (result as WorkerRouteOutcome.Ready).workerBaseUrl)
        assertEquals(
            listOf("https://primary.example/wake", "https://backup.example/wake"),
            api.calls
        )
    }

    @Test
    fun settlingWakePollsHealthUntilReady() = runTest {
        val api = FakeWorkerApiService(
            wakeHandler = {
                Response.success(202, WorkerWakeResponse(ok = true, start_accepted = true, route_ready = false, poll_after_seconds = 2))
            },
            healthHandler = {
                Response.success(WorkerWakeResponse(ok = true, route_ready = true))
            }
        )
        val sleeps = mutableListOf<Long>()
        val coordinator = WorkerRouteCoordinator(api, nowMs = { 0L }, sleep = { sleeps += it })

        val result = coordinator.ensureRouteReady(listOf("worker.example"), "secret")

        assertTrue(result is WorkerRouteOutcome.Ready)
        assertEquals(listOf(2_000L), sleeps)
        assertEquals(
            listOf("https://worker.example/wake", "https://worker.example/api/health"),
            api.calls
        )
    }

    @Test
    fun acceptedWakeKeepsPollingWhileCodespaceStateIsStillStarting() = runTest {
        var healthCalls = 0
        val api = FakeWorkerApiService(
            wakeHandler = {
                Response.success(
                    202,
                    WorkerWakeResponse(
                        ok = true,
                        start_accepted = true,
                        route_ready = false,
                        poll_after_seconds = 2
                    )
                )
            },
            healthHandler = {
                healthCalls += 1
                if (healthCalls == 1) {
                    Response.success(
                        WorkerWakeResponse(
                            ok = true,
                            state = "Starting",
                            route_ready = null,
                            next_action_code = "wait_codespace_available",
                            poll_after_seconds = 2
                        )
                    )
                } else {
                    Response.success(WorkerWakeResponse(ok = true, route_ready = true))
                }
            }
        )
        val sleeps = mutableListOf<Long>()
        val coordinator = WorkerRouteCoordinator(api, nowMs = { 0L }, sleep = { sleeps += it })

        val result = coordinator.ensureRouteReady(listOf("worker.example"), "secret")

        assertTrue(result is WorkerRouteOutcome.Ready)
        assertEquals(2, healthCalls)
        assertEquals(listOf(2_000L, 2_000L), sleeps)
        assertEquals(
            listOf(
                "https://worker.example/wake",
                "https://worker.example/api/health",
                "https://worker.example/api/health"
            ),
            api.calls
        )
    }

    @Test
    fun terminalAuthFailureIsNotReportedAsNetworkOutage() = runTest {
        val body = """{"ok":false,"error":"unauthorized"}"""
            .toResponseBody("application/json".toMediaType())
        val api = FakeWorkerApiService(wakeHandler = { Response.error(401, body) })
        val coordinator = WorkerRouteCoordinator(api, nowMs = { 0L }, sleep = {})

        val result = coordinator.ensureRouteReady(listOf("worker.example"), "wrong")

        assertTrue(result is WorkerRouteOutcome.Failed)
        result as WorkerRouteOutcome.Failed
        assertEquals(false, result.controlPlaneUnavailable)
        assertEquals(401, result.httpCode)
    }

    @Test
    fun terminalAuthFailureDoesNotLeakSecretToBackupWorker() = runTest {
        val body = """{"ok":false,"error":"unauthorized"}"""
            .toResponseBody("application/json".toMediaType())
        val api = FakeWorkerApiService(wakeHandler = { Response.error(401, body) })
        val coordinator = WorkerRouteCoordinator(api, nowMs = { 0L }, sleep = {})

        val result = coordinator.ensureRouteReady(
            listOf("https://primary.example", "https://backup.example"),
            "wrong"
        )

        assertTrue(result is WorkerRouteOutcome.Failed)
        assertEquals(listOf("https://primary.example/wake"), api.calls)
    }

    @Test
    fun primaryWakeServerFailureReconcilesThroughBackupHealth() = runTest {
        val api = FakeWorkerApiService(
            wakeHandler = { url ->
                check(url.contains("primary.example")) { "An uncertain wake must not be repeated" }
                workerError(503, """{"ok":false,"error":"temporary"}""", mapOf("Retry-After" to "180"))
            },
            healthHandler = { url ->
                if (url.contains("primary.example")) {
                    workerError(503, """{"ok":false,"error":"temporary"}""", emptyMap())
                } else Response.success(WorkerWakeResponse(ok = true, route_ready = true))
            }
        )
        val coordinator = WorkerRouteCoordinator(api, nowMs = { 0L }, sleep = {})

        val result = coordinator.ensureRouteReady(
            listOf("https://primary.example", "https://backup.example"),
            "secret"
        )

        assertTrue(result is WorkerRouteOutcome.Ready)
        assertEquals(
            listOf("https://primary.example/wake", "https://primary.example/api/health", "https://backup.example/api/health"),
            api.calls
        )
    }

    @Test
    fun genericWorker502DoesNotSendWakeToAnotherAlias() = runTest {
        val api = FakeWorkerApiService(wakeHandler = { url ->
            if (url.contains("primary.example")) {
                workerError(502, """{"error":"temporary gateway failure"}""", emptyMap())
            } else {
                error("A generic 502 must not trigger a second wake POST")
            }
        }, healthHandler = {
            Response.success(WorkerWakeResponse(ok = true, state = "Starting", route_ready = false))
        })
        val result = WorkerRouteCoordinator(api, nowMs = { 0L }, sleep = {})
            .ensureRouteReady(listOf("primary.example", "backup.example"), "secret")
        assertTrue(result is WorkerRouteOutcome.TimedOut || result is WorkerRouteOutcome.Failed)
        assertEquals(1, api.calls.count { it.endsWith("/wake") })
        assertTrue(api.calls.none { it.contains("backup.example") && it.endsWith("/wake") })
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun genericWorkerServerErrorsNeverRepeatWakeThroughAnotherAlias() = runTest {
        for (code in listOf(500, 502, 503, 504, 599)) {
            var healthChecks = 0
            val api = FakeWorkerApiService(
                wakeHandler = { url ->
                    check(url.contains("primary.example")) { "HTTP $code triggered another wake POST" }
                    workerError(code, """{"error":"temporary server failure"}""", emptyMap())
                },
                healthHandler = {
                    healthChecks++
                    Response.success(WorkerWakeResponse(ok = true, codespace = "demo", state = "Available",
                        route_ready = healthChecks >= 2, poll_after_seconds = 2))
                }
            )
            val result = WorkerRouteCoordinator(api, nowMs = { testScheduler.currentTime }).ensureRouteReady(
                listOf("primary.example", "backup.example"), "secret", expectedCodespaceName = "demo")
            assertTrue("HTTP $code did not reconcile through health", result is WorkerRouteOutcome.Ready)
            assertEquals("HTTP $code repeated the wake POST", 1, api.calls.count { it.endsWith("/wake") })
            assertEquals(2, healthChecks)
        }
    }

    @Test
    fun settlingRouteStopsAtTheConfiguredDeadline() = runTest {
        var elapsedMs = 0L
        val settling = WorkerWakeResponse(
            ok = true,
            start_accepted = true,
            route_ready = false,
            poll_after_seconds = 2
        )
        val api = FakeWorkerApiService(
            wakeHandler = { Response.success(202, settling) },
            healthHandler = { Response.success(settling) }
        )
        val coordinator = WorkerRouteCoordinator(
            api,
            nowMs = { elapsedMs },
            sleep = { elapsedMs += it }
        )

        val result = coordinator.ensureRouteReady(
            workerBaseUrls = listOf("worker.example"),
            secret = "secret",
            maxWaitMs = 3_500L
        )

        assertTrue(result is WorkerRouteOutcome.TimedOut)
        assertEquals(
            listOf("https://worker.example/wake", "https://worker.example/api/health"),
            api.calls
        )
    }

    @Test
    fun workerUrlNormalizationRejectsUnexpectedPaths() {
        assertEquals("https://worker.example", WorkerUrlNormalizer.baseUrl("worker.example/wake"))
        val error = runCatching { WorkerUrlNormalizer.baseUrl("https://worker.example/random") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun selectedCodespaceIdIsSentOnWakeAndHealth() = runTest {
        val api = FakeWorkerApiService(
            wakeHandler = { Response.success(202, WorkerWakeResponse(ok = true, route_ready = false, poll_after_seconds = 2)) },
            healthHandler = { Response.success(WorkerWakeResponse(ok = true, route_ready = true)) }
        )
        val coordinator = WorkerRouteCoordinator(api, nowMs = { 0L }, sleep = {})

        val result = coordinator.ensureRouteReady(
            workerBaseUrls = listOf("worker.example"),
            secret = "shared-secret",
            codespaceId = "quota-standby"
        )

        assertTrue(result is WorkerRouteOutcome.Ready)
        assertEquals(listOf("quota-standby", "quota-standby"), api.codespaceRequests.map { it.codespace_id })
    }
}
