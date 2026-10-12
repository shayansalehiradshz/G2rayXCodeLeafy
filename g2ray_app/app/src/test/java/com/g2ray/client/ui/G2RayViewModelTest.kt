package com.g2ray.client.ui

import com.g2ray.client.model.AppConnectionState
import com.g2ray.client.model.RouteState
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.Transport
import com.g2ray.client.network.VpnBypassSocketFactory
import com.g2ray.client.vpn.XrayDelaySummary
import com.g2ray.client.vpn.RoutePerformanceSummary
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
class G2RayViewModelTest {

    @Test
    fun normalizeWorkerUrl_validFormats_correct() {
        assertEquals("https://example.com/wake", G2RayViewModel.normalizeWorkerUrlVisibleForTesting("example.com", G2RayViewModel.Companion.WorkerEndpoint.WAKE))
        assertEquals("https://example.com/api/health", G2RayViewModel.normalizeWorkerUrlVisibleForTesting("https://example.com/", G2RayViewModel.Companion.WorkerEndpoint.HEALTH))
        assertEquals("https://example.com/wake", G2RayViewModel.normalizeWorkerUrlVisibleForTesting("https://example.com/wake", G2RayViewModel.Companion.WorkerEndpoint.WAKE))
        assertEquals("https://example.com/api/health", G2RayViewModel.normalizeWorkerUrlVisibleForTesting("https://example.com/api/health", G2RayViewModel.Companion.WorkerEndpoint.HEALTH))
        assertEquals("https://example.com/wake", G2RayViewModel.normalizeWorkerUrlVisibleForTesting("https://example.com/api/wake", G2RayViewModel.Companion.WorkerEndpoint.WAKE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeWorkerUrl_http_fails() {
        G2RayViewModel.normalizeWorkerUrlVisibleForTesting("http://example.com", G2RayViewModel.Companion.WorkerEndpoint.WAKE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeWorkerUrl_randomPath_fails() {
        G2RayViewModel.normalizeWorkerUrlVisibleForTesting("https://example.com/random/path", G2RayViewModel.Companion.WorkerEndpoint.WAKE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeWorkerUrl_whitespace_fails() {
        G2RayViewModel.normalizeWorkerUrlVisibleForTesting("https://example .com", G2RayViewModel.Companion.WorkerEndpoint.WAKE)
    }

    @Test
    fun workerHttpClient_readTimeoutCoversAWorkerProgressResponse() {
        val client = G2RayViewModel.buildWorkerHttpClientVisibleForTesting(loggingEnabled = false)

        assertEquals(10_000, client.connectTimeoutMillis)
        assertTrue("read timeout must cover Worker progress response", client.readTimeoutMillis >= 40_000)
        assertTrue(
            "UI Worker calls must bypass an active VPN through a protected socket",
            client.socketFactory is VpnBypassSocketFactory
        )
        assertNotSame(
            "UI Worker DNS must resolve outside a dead VPN before the protected socket is created",
            Dns.SYSTEM,
            client.dns
        )
    }

    @Test
    fun workerWakeResponsePollability_matchesWorkerSettlingStates() {
        assertTrue(
            G2RayViewModel.shouldPollWorkerWakeResponseVisibleForTesting(
                httpCode = 202,
                body = com.g2ray.client.network.WorkerWakeResponse(
                    ok = false,
                    start_accepted = true,
                    route_ready = null,
                    next_action_code = "wait_codespace_available"
                )
            )
        )
        assertTrue(
            G2RayViewModel.shouldPollWorkerWakeResponseVisibleForTesting(
                httpCode = 200,
                body = com.g2ray.client.network.WorkerWakeResponse(
                    ok = true,
                    route_ready = false,
                    next_action_code = "wait_route_or_recover"
                )
            )
        )
        assertFalse(
            G2RayViewModel.shouldPollWorkerWakeResponseVisibleForTesting(
                httpCode = 402,
                body = com.g2ray.client.network.WorkerWakeResponse(
                    ok = false,
                    quota_blocked = true,
                    route_ready = false
                )
            )
        )
    }

    @Test
    fun wakeRecentlySucceededError_switchesToHealthPolling() {
        assertTrue(
            G2RayViewModel.shouldCheckHealthAfterWakeErrorVisibleForTesting(
                httpCode = 429,
                body = com.g2ray.client.network.WorkerWakeResponse(
                    ok = false,
                    reason = "wake_recently_succeeded",
                    retry_after_seconds = 60
                )
            )
        )
        assertFalse(
            G2RayViewModel.shouldCheckHealthAfterWakeErrorVisibleForTesting(
                httpCode = 401,
                body = com.g2ray.client.network.WorkerWakeResponse(
                    ok = false,
                    error = "unauthorized"
                )
            )
        )
    }

    @Test
    fun routeStateUpdate_preservesRunningTunnelStateAndSpeeds() {
        val previous = AppConnectionState(
            routeState = RouteState.Ready,
            tunnelState = TunnelState.Connected,
            message = "VPN connected",
            activeAccountId = "old-account",
            activeProfileId = "profile-1",
            routeReady = true,
            tunnelVerified = true,
            downloadBps = 1234,
            uploadBps = 567,
            totalRxBytes = 9000,
            totalTxBytes = 8000
        )

        val updated = G2RayViewModel.mergeRouteStateVisibleForTesting(
            previous = previous,
            routeState = RouteState.Settling,
            message = "Codespace route settling",
            activeAccountId = "account-2",
            activeProfileId = null,
            routeReady = false
        )

        assertEquals(TunnelState.Connected, updated.tunnelState)
        assertEquals(1234, updated.downloadBps)
        assertEquals(567, updated.uploadBps)
        assertEquals(9000, updated.totalRxBytes)
        assertEquals(8000, updated.totalTxBytes)
        assertEquals("profile-1", updated.activeProfileId)
        assertEquals("account-2", updated.activeAccountId)
        assertFalse(updated.routeReady)
        assertTrue(updated.tunnelVerified)
    }

    @Test
    fun vpnStartAllowed_blocksDuplicateRunningStates() {
        assertFalse(G2RayViewModel.vpnStartAllowedVisibleForTesting(TunnelState.Starting))
        assertFalse(G2RayViewModel.vpnStartAllowedVisibleForTesting(TunnelState.Connected))
        assertFalse(G2RayViewModel.vpnStartAllowedVisibleForTesting(TunnelState.Stopping))
        assertTrue(G2RayViewModel.vpnStartAllowedVisibleForTesting(TunnelState.Disabled))
        assertTrue(G2RayViewModel.vpnStartAllowedVisibleForTesting(TunnelState.Disconnected))
        assertTrue(G2RayViewModel.vpnStartAllowedVisibleForTesting(TunnelState.Error))
    }

    @Test
    fun vpnStartRequestBusy_blocksASecondRequestBeforeItCanReplaceThePendingProfile() {
        assertTrue(G2RayViewModel.vpnStartRequestBusyVisibleForTesting(routeProbeActive = true))
        assertFalse(G2RayViewModel.vpnStartRequestBusyVisibleForTesting(routeProbeActive = false))
    }

    @Test
    fun configurationMutations_areBlockedDuringTunnelLifecycleTransitions() {
        assertFalse(G2RayViewModel.configurationMutationAllowedVisibleForTesting(TunnelState.Starting))
        assertFalse(G2RayViewModel.configurationMutationAllowedVisibleForTesting(TunnelState.Connected))
        assertFalse(G2RayViewModel.configurationMutationAllowedVisibleForTesting(TunnelState.Stopping))
        assertFalse(
            G2RayViewModel.configurationMutationAllowedVisibleForTesting(
                TunnelState.Disconnected,
                startupBusy = true
            )
        )
        assertTrue(G2RayViewModel.configurationMutationAllowedVisibleForTesting(TunnelState.Disconnected))
        assertTrue(G2RayViewModel.configurationMutationAllowedVisibleForTesting(TunnelState.Error))
    }

    @Test
    fun vpnStartPlan_checksTheActiveRouteBeforeWakingWheneverWorkerIsConfigured() {
        assertEquals(
            G2RayViewModel.VpnStartPlan.DirectProbeThenWake,
            G2RayViewModel.vpnStartPlanVisibleForTesting(workerConfigured = true, routeReady = true)
        )
        assertEquals(
            G2RayViewModel.VpnStartPlan.DirectProbeThenWake,
            G2RayViewModel.vpnStartPlanVisibleForTesting(workerConfigured = true, routeReady = false)
        )
        assertEquals(
            G2RayViewModel.VpnStartPlan.StartDirect,
            G2RayViewModel.vpnStartPlanVisibleForTesting(workerConfigured = false, routeReady = true)
        )
        assertEquals(
            G2RayViewModel.VpnStartPlan.Block,
            G2RayViewModel.vpnStartPlanVisibleForTesting(workerConfigured = false, routeReady = false)
        )
    }

    @Test
    fun workerNetworkFailure_fallsBackOnlyForAPendingVpnStart() {
        assertEquals(
            G2RayViewModel.WorkerNetworkFailureAction.DirectVpnFallback,
            G2RayViewModel.workerNetworkFailureActionVisibleForTesting(
                pendingVpnStart = true,
                throwable = SocketTimeoutException("timed out")
            )
        )
        assertEquals(
            G2RayViewModel.WorkerNetworkFailureAction.ReportFailure,
            G2RayViewModel.workerNetworkFailureActionVisibleForTesting(
                pendingVpnStart = false,
                throwable = SocketTimeoutException("timed out")
            )
        )
        assertEquals(
            G2RayViewModel.WorkerNetworkFailureAction.ReportFailure,
            G2RayViewModel.workerNetworkFailureActionVisibleForTesting(
                pendingVpnStart = true,
                throwable = IllegalArgumentException("bad URL")
            )
        )
    }

    @Test
    fun workerTransientHttpFailure_triesTheActiveRouteButNeverMasksQuotaOrAuth() {
        assertEquals(
            G2RayViewModel.WorkerNetworkFailureAction.DirectVpnFallback,
            G2RayViewModel.workerHttpFailureActionVisibleForTesting(
                pendingVpnStart = true,
                httpCode = 503,
                body = com.g2ray.client.network.WorkerWakeResponse(ok = false)
            )
        )
        assertEquals(
            G2RayViewModel.WorkerNetworkFailureAction.ReportFailure,
            G2RayViewModel.workerHttpFailureActionVisibleForTesting(
                pendingVpnStart = true,
                httpCode = 402,
                body = com.g2ray.client.network.WorkerWakeResponse(ok = false, quota_blocked = true)
            )
        )
        assertEquals(
            G2RayViewModel.WorkerNetworkFailureAction.ReportFailure,
            G2RayViewModel.workerHttpFailureActionVisibleForTesting(
                pendingVpnStart = true,
                httpCode = 401,
                body = com.g2ray.client.network.WorkerWakeResponse(ok = false)
            )
        )
        assertEquals(
            G2RayViewModel.WorkerNetworkFailureAction.ReportFailure,
            G2RayViewModel.workerHttpFailureActionVisibleForTesting(
                pendingVpnStart = false,
                httpCode = 503,
                body = com.g2ray.client.network.WorkerWakeResponse(ok = false)
            )
        )
    }

    @Test
    fun delayScanCompletion_reportsPersistenceFailuresHonestly() {
        assertEquals(
            "Quick delay scan complete for 5 configs. Use the per-config test for a 3-sample measurement.",
            G2RayViewModel.delayScanCompletionMessageVisibleForTesting(
                total = 5,
                persistenceFailures = 0
            )
        )
        assertEquals(
            "Quick delay scan measured 5 configs, but 2 result(s) could not be saved.",
            G2RayViewModel.delayScanCompletionMessageVisibleForTesting(
                total = 5,
                persistenceFailures = 2
            )
        )
        assertEquals(
            "Ping all complete: 3/5 configs responded. Failed configs show their error.",
            G2RayViewModel.delayScanCompletionMessageVisibleForTesting(
                total = 5,
                persistenceFailures = 0,
                successful = 3
            )
        )
        assertEquals(
            "Ping all finished, but none of 5 configs responded. Check route readiness or try a per-config test.",
            G2RayViewModel.delayScanCompletionMessageVisibleForTesting(
                total = 5,
                persistenceFailures = 0,
                successful = 0
            )
        )
    }

    @Test
    fun interruptedDelayTests_areReleasedWithoutOverwritingCompletedResults() {
        val states = mapOf(
            "pending" to ProfileDelayState(testing = true),
            "done" to ProfileDelayState(testing = false, delayMs = 42L)
        )

        val cleared = G2RayViewModel.clearInterruptedDelayStatesVisibleForTesting(
            states = states,
            ids = setOf("pending", "done"),
            message = "interrupted",
            testedAt = 123L
        )

        assertFalse(cleared.getValue("pending").testing)
        assertEquals("interrupted", cleared.getValue("pending").error)
        assertEquals(123L, cleared.getValue("pending").testedAt)
        assertFalse(cleared.getValue("done").testing)
        assertEquals(42L, cleared.getValue("done").delayMs)
        assertEquals(null, cleared.getValue("done").error)
    }

    @Test
    fun routeOptimizationScore_penalizesTailJitterRetriesAndDeprecatedWs() {
        val stable = XrayDelaySummary(true, 100L, 110L, 5L, 2, 2, null)
        val unstable = XrayDelaySummary(true, 100L, 300L, 80L, 1, 2, null)

        val stableXhttp = G2RayViewModel.routeOptimizationScoreVisibleForTesting(stable, Transport.XHTTP)
        val stableWs = G2RayViewModel.routeOptimizationScoreVisibleForTesting(stable, Transport.WS)
        val unstableXhttp = G2RayViewModel.routeOptimizationScoreVisibleForTesting(unstable, Transport.XHTTP)

        assertTrue(stableXhttp < stableWs)
        assertTrue(stableWs < unstableXhttp)
    }

    @Test
    fun routeOptimizationScore_prefersUsefulThroughputOverAFragilePingOnlyWinner() {
        val slightlyFasterPing = XrayDelaySummary(true, 80L, 95L, 8L, 3, 3, null)
        val stableFastTransfer = XrayDelaySummary(true, 110L, 125L, 8L, 3, 3, null)

        val slowRoute = G2RayViewModel.routeOptimizationScoreVisibleForTesting(
            slightlyFasterPing,
            Transport.XHTTP,
            RoutePerformanceSummary(true, downloadBps = 180_000L, uploadBps = 80_000L)
        )
        val fastRoute = G2RayViewModel.routeOptimizationScoreVisibleForTesting(
            stableFastTransfer,
            Transport.XHTTP,
            RoutePerformanceSummary(true, downloadBps = 4_000_000L, uploadBps = 1_000_000L)
        )

        assertTrue(fastRoute < slowRoute)
    }

    @Test
    fun routeOptimizationComparison_doesNotRewardUnknownThroughputWhenMeasurementsExist() {
        val unknownButLowerPing = XrayDelaySummary(true, 75L, 90L, 5L, 3, 3, null)
        val measuredRoute = XrayDelaySummary(true, 110L, 125L, 5L, 3, 3, null)

        val unknownScore = G2RayViewModel.routeOptimizationComparisonScoreVisibleForTesting(
            unknownButLowerPing,
            Transport.XHTTP,
            performance = null,
            hasPerformanceEvidence = true
        )
        val measuredScore = G2RayViewModel.routeOptimizationComparisonScoreVisibleForTesting(
            measuredRoute,
            Transport.XHTTP,
            RoutePerformanceSummary(true, downloadBps = 4_000_000L, uploadBps = 1_000_000L),
            hasPerformanceEvidence = true
        )

        assertTrue(measuredScore < unknownScore)
    }

    @Test
    fun autoRouteOptimization_allowsOneAttemptAfterANewAvailableWake() {
        val now = 2_000_000L
        fun allowed(lastAttemptAt: Long) = G2RayViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
            enabled = true,
            lastAttemptAt = lastAttemptAt,
            now = now,
            routeWasReady = true,
            profileCount = 3,
            tunnelState = TunnelState.Disconnected,
            busy = false,
            availableWakeAt = now - 1_000L
        )

        assertTrue(allowed(now - 10_000L))
        assertFalse(allowed(now))
    }

    @Test
    fun autoRouteOptimization_oldAndFutureWakesDoNotBypassCooldown() {
        val now = 2_000_000L
        listOf(now - 900_001L, now - 300_001L, now + 1L).forEach { wakeAt ->
            assertFalse(
                G2RayViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
                    enabled = true,
                    lastAttemptAt = now - 900_000L,
                    now = now,
                    routeWasReady = true,
                    profileCount = 3,
                    tunnelState = TunnelState.Disconnected,
                    busy = false,
                    availableWakeAt = wakeAt
                )
            )
        }
    }

    @Test
    fun autoRouteOptimization_newWakeStillHonorsSessionAndBusyGuards() {
        val now = 2_000_000L
        fun allowed(
            state: TunnelState = TunnelState.Disconnected,
            busy: Boolean = false,
            enabled: Boolean = true,
            ready: Boolean = true,
            profiles: Int = 3
        ) = G2RayViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
            enabled = enabled,
            lastAttemptAt = now - 10_000L,
            now = now,
            routeWasReady = ready,
            profileCount = profiles,
            tunnelState = state,
            busy = busy,
            availableWakeAt = now - 1_000L
        )

        assertFalse(allowed(state = TunnelState.Connected))
        assertFalse(allowed(state = TunnelState.Starting))
        assertFalse(allowed(busy = true))
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(ready = false))
        assertFalse(allowed(profiles = 0))
    }

    @Test
    fun autoRouteOptimization_requiresReadyStoppedRouteAndCooldown() {
        val now = 2_000_000L
        assertTrue(
            G2RayViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
                enabled = true,
                lastAttemptAt = 0L,
                now = now,
                routeWasReady = true,
                profileCount = 3,
                tunnelState = TunnelState.Disconnected,
                busy = false
            )
        )
        assertFalse(
            G2RayViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
                enabled = true,
                lastAttemptAt = now - 1_000L,
                now = now,
                routeWasReady = true,
                profileCount = 3,
                tunnelState = TunnelState.Disconnected,
                busy = false
            )
        )
        assertFalse(
            G2RayViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
                enabled = true,
                lastAttemptAt = 0L,
                now = now,
                routeWasReady = false,
                profileCount = 3,
                tunnelState = TunnelState.Disconnected,
                busy = false
            )
        )
        assertFalse(
            G2RayViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
                enabled = true,
                lastAttemptAt = 0L,
                now = now,
                routeWasReady = true,
                profileCount = 3,
                tunnelState = TunnelState.Connected,
                busy = false
            )
        )
    }
}
