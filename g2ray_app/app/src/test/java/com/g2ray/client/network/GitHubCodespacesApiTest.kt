package com.g2ray.client.network

import com.g2ray.client.model.CodespaceWakeMode
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class GitHubCodespacesApiTest {
    @Test
    fun deletedArchivedAndFailedCodespacesDoNotRetryForever() = runTest {
        for (state in listOf("Deleted", "Archived", "Failed")) {
            val api = FakeGitHubApi(ArrayDeque(listOf(Response.success(GitHubCodespaceResponse(state = state)))))
            val result = GitHubCodespaceCoordinator(api, nowMs = { 0L }).ensureAvailable("demo", "token")
                as GitHubCodespaceOutcome.Failed
            assertEquals(state, false, result.retryAllowed)
            assertEquals(1, api.getCalls)
            assertEquals(0, api.startCalls)
        }
    }

    @Test
    fun retryAfterOnStatusServerFailureIsNotBypassedByInternalPolling() = runTest {
        val api = FakeGitHubApi(ArrayDeque(listOf(error(503, "Try later", mapOf("Retry-After" to "180")))))
        val result = GitHubCodespaceCoordinator(api, nowMs = { 0L })
            .ensureAvailable("demo", "token") as GitHubCodespaceOutcome.Failed
        assertTrue(result.retryAllowed)
        assertEquals(180_000L, result.retryAfterMs)
        assertEquals(1, api.getCalls)
        assertEquals(0, api.startCalls)
    }

    @Test
    fun retryAfterOnStartServerFailureIsNotBypassedByInternalPolling() = runTest {
        val api = FakeGitHubApi(
            ArrayDeque(listOf(Response.success(GitHubCodespaceResponse(state = "Shutdown")))),
            ArrayDeque(listOf(error(503, "Try later", mapOf("Retry-After" to "180"))))
        )
        val result = GitHubCodespaceCoordinator(api, nowMs = { 0L })
            .ensureAvailable("demo", "token") as GitHubCodespaceOutcome.Failed
        assertTrue(result.retryAllowed)
        assertEquals(180_000L, result.retryAfterMs)
        assertEquals(1, api.getCalls)
        assertEquals(1, api.startCalls)
    }

    @Test
    fun secondaryRateLimitPreservesAutomaticVpnRetryThroughDirectWake() = runTest {
        val api = FakeGitHubApi(ArrayDeque(listOf(error(403, "You have exceeded a secondary rate limit"))))
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { _, _ -> error("Worker must not be used in direct mode") },
            githubWake = { _, _ -> GitHubCodespaceCoordinator(api, nowMs = { 0L }).ensureAvailable("demo", "token") }
        )
        val outcome = coordinator.ensureReady(CodespaceWakeRequest(
            CodespaceWakeMode.GITHUB_DIRECT, "demo", githubToken = "token"
        )) as CodespaceWakeOutcome.Failed
        assertEquals(403, outcome.httpCode)
        assertTrue(outcome.retryAllowed)
        assertEquals(60_000L, outcome.retryAfterMs)
        assertEquals(1, api.getCalls)
        assertEquals(0, api.startCalls)
    }

    @Test
    fun primaryRateLimitUsesEpochResetAndKeepsRetryEnabled() = runTest {
        val api = FakeGitHubApi(ArrayDeque(listOf(error(403, "API rate limit exceeded", mapOf(
            "X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1600"
        )))))
        val result = GitHubCodespaceCoordinator(api, nowMs = { 0L }, nowEpochMs = { 1_000_000L })
            .ensureAvailable("demo", "token") as GitHubCodespaceOutcome.Failed
        assertTrue(result.retryAllowed)
        assertEquals(600_000L, result.retryAfterMs)
        assertEquals(0, api.startCalls)
    }

    @Test
    fun overlappingRateLimitsWaitForBothRetryAfterAndPrimaryReset() = runTest {
        val api = FakeGitHubApi(ArrayDeque(listOf(error(403, "API rate limit exceeded", mapOf(
            "X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1600", "Retry-After" to "120"
        )))))
        val result = GitHubCodespaceCoordinator(api, nowMs = { 0L }, nowEpochMs = { 1_000_000L })
            .ensureAvailable("demo", "token") as GitHubCodespaceOutcome.Failed
        assertTrue(result.retryAllowed)
        assertEquals(600_000L, result.retryAfterMs)
    }

    @Test
    fun permissionAuthQuotaAndMissingCodespaceErrorsRemainTerminal() = runTest {
        for (code in listOf(400, 401, 402, 403, 404, 405, 410, 415, 422)) {
            val api = FakeGitHubApi(ArrayDeque(listOf(error(code, "Request rejected"))))
            val result = GitHubCodespaceCoordinator(api, nowMs = { 0L })
                .ensureAvailable("demo", "token") as GitHubCodespaceOutcome.Failed
            assertEquals("HTTP $code", false, result.retryAllowed)
            assertEquals(null, result.retryAfterMs)
            assertEquals(1, api.getCalls)
            assertEquals(0, api.startCalls)
        }
    }

    private class FakeGitHubApi(
        private val getResponses: ArrayDeque<Response<GitHubCodespaceResponse>>,
        private val startResponses: ArrayDeque<Response<GitHubCodespaceResponse>> = ArrayDeque()
    ) : GitHubCodespacesApiService {
        var getCalls = 0
        var startCalls = 0

        override suspend fun getCodespace(
            codespaceName: String,
            authorization: String
        ): Response<GitHubCodespaceResponse> {
            getCalls++
            return getResponses.removeFirst()
        }

        override suspend fun startCodespace(
            codespaceName: String,
            authorization: String
        ): Response<GitHubCodespaceResponse> {
            startCalls++
            return startResponses.removeFirst()
        }
    }

    @Test
    fun availableCodespaceDoesNotReceiveDuplicateStartRequest() = runTest {
        val api = FakeGitHubApi(
            getResponses = ArrayDeque(listOf(Response.success(GitHubCodespaceResponse(state = "Available"))))
        )
        val coordinator = GitHubCodespaceCoordinator(api, nowMs = { 0L }, sleep = {})

        val outcome = coordinator.ensureAvailable("demo", "token")

        assertTrue(outcome is GitHubCodespaceOutcome.Ready)
        assertEquals(1, api.getCalls)
        assertEquals(0, api.startCalls)
    }

    @Test
    fun runningCodespaceWithoutPendingOperationIsReady() = runTest {
        val api = FakeGitHubApi(
            getResponses = ArrayDeque(
                listOf(Response.success(GitHubCodespaceResponse(state = "running", pending_operation = false)))
            )
        )
        val outcome = GitHubCodespaceCoordinator(api, nowMs = { 0L }, sleep = {}).ensureAvailable("demo", "token")

        assertTrue(outcome is GitHubCodespaceOutcome.Ready)
        assertEquals(1, api.getCalls)
        assertEquals(0, api.startCalls)
    }

    @Test
    fun availableCodespaceWithPendingOperationIsPolledUntilSettled() = runTest {
        val api = FakeGitHubApi(
            getResponses = ArrayDeque(
                listOf(
                    Response.success(GitHubCodespaceResponse(state = "Available", pending_operation = true)),
                    Response.success(GitHubCodespaceResponse(state = "Available", pending_operation = false))
                )
            )
        )
        val outcome = GitHubCodespaceCoordinator(api, nowMs = { 0L }, sleep = {})
            .ensureAvailable("demo", "token")

        assertTrue(outcome is GitHubCodespaceOutcome.Ready)
        assertEquals(2, api.getCalls)
        assertEquals(0, api.startCalls)
    }

    @Test
    fun startResponseWithPendingOperationIsNotReportedReadyEarly() = runTest {
        val api = FakeGitHubApi(
            getResponses = ArrayDeque(
                listOf(Response.success(GitHubCodespaceResponse(state = "Shutdown"))) +
                    listOf(Response.success(GitHubCodespaceResponse(state = "Available", pending_operation = false)))
            ),
            startResponses = ArrayDeque(
                listOf(Response.success(202, GitHubCodespaceResponse(state = "Available", pending_operation = true)))
            )
        )
        val outcome = GitHubCodespaceCoordinator(api, nowMs = { 0L }, sleep = {})
            .ensureAvailable("demo", "token")

        assertTrue(outcome is GitHubCodespaceOutcome.Ready)
        assertEquals(2, api.getCalls)
        assertEquals(1, api.startCalls)
    }

    @Test
    fun shutdownCodespaceStartsOnceThenPollsUntilAvailable() = runTest {
        var elapsed = 0L
        val api = FakeGitHubApi(
            getResponses = ArrayDeque(
                listOf(
                    Response.success(GitHubCodespaceResponse(state = "Shutdown")),
                    Response.success(GitHubCodespaceResponse(state = "Starting")),
                    Response.success(GitHubCodespaceResponse(state = "Available"))
                )
            ),
            startResponses = ArrayDeque(
                listOf(Response.success(202, GitHubCodespaceResponse(state = "Starting")))
            )
        )
        val coordinator = GitHubCodespaceCoordinator(
            api,
            nowMs = { elapsed },
            sleep = { elapsed += it }
        )

        val outcome = coordinator.ensureAvailable("demo", "token")

        assertTrue(outcome is GitHubCodespaceOutcome.Ready)
        assertEquals(3, api.getCalls)
        assertEquals(1, api.startCalls)
    }

    @Test
    fun transientStartServerErrorIsRetriedInsteadOfPollingShutdownUntilTimeout() = runTest {
        var elapsed = 0L
        val api = FakeGitHubApi(
            getResponses = ArrayDeque(
                listOf(
                    Response.success(GitHubCodespaceResponse(state = "Shutdown")),
                    Response.success(GitHubCodespaceResponse(state = "Shutdown")),
                    Response.success(GitHubCodespaceResponse(state = "Available"))
                )
            ),
            startResponses = ArrayDeque(
                listOf(
                    error(503, "temporarily unavailable"),
                    Response.success(202, GitHubCodespaceResponse(state = "Starting"))
                )
            )
        )
        val coordinator = GitHubCodespaceCoordinator(
            api,
            nowMs = { elapsed },
            sleep = { elapsed += it }
        )

        val outcome = coordinator.ensureAvailable("demo", "token")

        assertTrue(outcome is GitHubCodespaceOutcome.Ready)
        assertEquals(3, api.getCalls)
        assertEquals(2, api.startCalls)
    }

    @Test
    fun repeatedStartServerErrorsFailAsControlPlaneUnavailable() = runTest {
        var elapsed = 0L
        val api = FakeGitHubApi(
            getResponses = ArrayDeque(
                List(3) { Response.success(GitHubCodespaceResponse(state = "Shutdown")) }
            ),
            startResponses = ArrayDeque(
                List(3) { error(503, "temporarily unavailable") }
            )
        )
        val coordinator = GitHubCodespaceCoordinator(
            api,
            nowMs = { elapsed },
            sleep = { elapsed += it }
        )

        val outcome = coordinator.ensureAvailable("demo", "token")

        assertTrue(outcome is GitHubCodespaceOutcome.Failed)
        assertTrue((outcome as GitHubCodespaceOutcome.Failed).controlPlaneUnavailable)
        assertEquals(503, outcome.httpCode)
        assertEquals(3, api.startCalls)
    }

    @Test
    fun authenticationAndQuotaFailuresAreNotReportedAsNetworkOutages() = runTest {
        val unauthorized = GitHubCodespaceCoordinator(
            FakeGitHubApi(ArrayDeque(listOf(error(401, "invalid token")))),
            nowMs = { 0L },
            sleep = {}
        ).ensureAvailable("demo", "bad")
        val quota = GitHubCodespaceCoordinator(
            FakeGitHubApi(ArrayDeque(listOf(error(402, "quota")))),
            nowMs = { 0L },
            sleep = {}
        ).ensureAvailable("demo", "token")

        assertTrue(unauthorized is GitHubCodespaceOutcome.Failed)
        assertEquals(false, (unauthorized as GitHubCodespaceOutcome.Failed).controlPlaneUnavailable)
        assertEquals(401, unauthorized.httpCode)
        assertTrue((quota as GitHubCodespaceOutcome.Failed).message.contains("quota", ignoreCase = true))
        assertEquals(402, quota.httpCode)
    }

    @Test
    fun transitionalStateTimesOutWithoutRepeatedStartRequests() = runTest {
        var elapsed = 0L
        val api = FakeGitHubApi(
            getResponses = ArrayDeque(
                listOf(Response.success(GitHubCodespaceResponse(state = "Shutdown"))) +
                    List(10) { Response.success(GitHubCodespaceResponse(state = "Starting")) }
            ),
            startResponses = ArrayDeque(
                listOf(Response.success(202, GitHubCodespaceResponse(state = "Starting")))
            )
        )
        val coordinator = GitHubCodespaceCoordinator(
            api,
            nowMs = { elapsed },
            sleep = { elapsed += it }
        )

        val outcome = coordinator.ensureAvailable("demo", "token", maxWaitMs = 6_000L, maxAttempts = 10)

        assertTrue(outcome is GitHubCodespaceOutcome.TimedOut)
        assertEquals(1, api.startCalls)
    }

    @Test
    fun autoModeFallsBackToDirectOnlyWhenWorkerControlPlaneIsUnavailable() = runTest {
        var workerCalls = 0
        var githubCalls = 0
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { _, _ ->
                workerCalls++
                WorkerRouteOutcome.Failed("offline", controlPlaneUnavailable = true)
            },
            githubWake = { _, _ ->
                githubCalls++
                GitHubCodespaceOutcome.Ready(GitHubCodespaceResponse(state = "Available"))
            }
        )

        val outcome = coordinator.ensureReady(
            CodespaceWakeRequest(
                mode = CodespaceWakeMode.AUTO,
                githubCodespaceName = "demo",
                workerBaseUrls = listOf("https://worker.example"),
                workerSecret = "wake",
                githubToken = "token"
            )
        )

        assertTrue(outcome is CodespaceWakeOutcome.Ready)
        assertEquals(WakeControlBackend.GITHUB_DIRECT, (outcome as CodespaceWakeOutcome.Ready).backend)
        assertEquals(1, workerCalls)
        assertEquals(1, githubCalls)
    }

    @Test
    fun selectorHonorsExplicitBackendModes() {
        assertEquals(
            listOf(WakeControlBackend.WORKER, WakeControlBackend.GITHUB_DIRECT),
            WakeBackendSelector.order(CodespaceWakeMode.AUTO, true, true)
        )
        assertEquals(
            listOf(WakeControlBackend.GITHUB_DIRECT),
            WakeBackendSelector.order(CodespaceWakeMode.GITHUB_DIRECT, true, true)
        )
        assertTrue(WakeBackendSelector.order(CodespaceWakeMode.WORKER, false, true).isEmpty())
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun wakeDeadlineIncludesTimeAlreadySpentOnWorkerBeforeDirectFallback() = runTest {
        var directCancelled = false
        val coordinator = CodespaceWakeCoordinator(
            workerWake = { _, _ ->
                kotlinx.coroutines.delay(400L)
                WorkerRouteOutcome.Failed("offline", controlPlaneUnavailable = true)
            },
            githubWake = { _, progress ->
                progress(GitHubCodespaceProgress(GitHubCodespacePhase.WAITING, "waiting", 1))
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    directCancelled = true
                }
            }
        )
        val result = coordinator.ensureReady(
            CodespaceWakeRequest(
                CodespaceWakeMode.AUTO, "demo",
                workerBaseUrls = listOf("https://worker.example"),
                workerSecret = "wake", githubToken = "token"
            ),
            maxWaitMs = 500L
        )
        assertTrue(result is CodespaceWakeOutcome.TimedOut)
        assertEquals(WakeControlBackend.GITHUB_DIRECT, (result as CodespaceWakeOutcome.TimedOut).backend)
        assertTrue(directCancelled)
        assertEquals(500L, testScheduler.currentTime)
    }

    private companion object {
        fun error(code: Int, message: String, headers: Map<String, String> = emptyMap()): Response<GitHubCodespaceResponse> {
            val body = "{\"message\":\"$message\"}"
                .toResponseBody("application/json".toMediaType())
            val response = okhttp3.Response.Builder()
                .request(okhttp3.Request.Builder().url("https://api.github.com/user/codespaces/demo").build())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message("API response")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            return Response.error(body, response)
        }
    }
}
