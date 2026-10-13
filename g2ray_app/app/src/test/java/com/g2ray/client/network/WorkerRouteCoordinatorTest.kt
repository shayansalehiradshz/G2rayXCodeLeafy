package com.g2ray.client.network

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class WorkerRouteCoordinatorTest {
    @Test
    fun failedDirectFallbackCannotDiscardTheWorkerRetryBoundary() = runTest {
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { _, _ -> WorkerRouteOutcome.Failed(
                "Worker unavailable", controlPlaneUnavailable = true, httpCode = 503, retryAfterMs = 180_000L
            ) },
            githubWake = { _, _ -> GitHubCodespaceOutcome.Failed("GitHub unavailable", controlPlaneUnavailable = true) }
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
                .ensureRouteReady(listOf("worker.example"), "secret") as WorkerRouteOutcome.Failed
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
            githubWake = { _, _ -> error("Rate limits must not trigger direct-GitHub bypass") }
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
            if (url.contains("primary.example")) throw IOException("blocked")
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
    fun primaryServerFailureUsesBackupWorker() = runTest {
        val body = """{"ok":false,"error":"temporary"}"""
            .toResponseBody("application/json".toMediaType())
        val api = FakeWorkerApiService(wakeHandler = { url ->
            if (url.contains("primary.example")) Response.error(503, body)
            else Response.success(WorkerWakeResponse(ok = true, route_ready = true))
        })
        val coordinator = WorkerRouteCoordinator(api, nowMs = { 0L }, sleep = {})

        val result = coordinator.ensureRouteReady(
            listOf("https://primary.example", "https://backup.example"),
            "secret"
        )

        assertTrue(result is WorkerRouteOutcome.Ready)
        assertEquals(
            listOf("https://primary.example/wake", "https://backup.example/wake"),
            api.calls
        )
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
