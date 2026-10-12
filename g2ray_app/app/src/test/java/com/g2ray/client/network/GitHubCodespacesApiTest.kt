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
        fun error(code: Int, message: String): Response<GitHubCodespaceResponse> {
            val body = "{\"message\":\"$message\"}"
                .toResponseBody("application/json".toMediaType())
            return Response.error(code, body)
        }
    }
}
