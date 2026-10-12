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
    fun aHealthyDifferentCodespaceCannotSatisfyTheRequestedWake() = runTest {
        val api = FakeWorkerApiService(wakeHandler = {
            Response.success(WorkerWakeResponse(ok = true, route_ready = true, codespace = "wrong-space"))
        })
        val result = WorkerRouteCoordinator(api, nowMs = { 0L }).ensureRouteReady(
            listOf("worker.example"), "secret", expectedCodespaceName = "selected-space"
        )
        assertTrue(result is WorkerRouteOutcome.Failed)
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
