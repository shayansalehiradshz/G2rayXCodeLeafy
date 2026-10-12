package com.g2ray.client.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocksRunningRouteProbeTest {
    @Test
    fun healthEndpointsAreActuallyIndependent() {
        assertEquals(3, SocksRunningRouteProbe.HEALTH_URLS.size)
        assertEquals(
            SocksRunningRouteProbe.HEALTH_URLS.size,
            SocksRunningRouteProbe.HEALTH_URLS.distinct().size
        )
    }

    @Test
    fun healthPolicy_requiresTwoProvidersWhenThreeAreConfigured() {
        assertFalse(SocksRunningRouteProbe.hasEnoughHealthyProbes(1, totalProbes = 3))
        assertTrue(SocksRunningRouteProbe.hasEnoughHealthyProbes(2, totalProbes = 3))
    }

    @Test
    fun healthPolicy_doesNotRejectAOneEndpointTestHarness() {
        assertTrue(SocksRunningRouteProbe.hasEnoughHealthyProbes(1, totalProbes = 1))
    }

    @Test
    fun probeQuorum_returnsAfterTwoSuccessesAndCancelsTheSlowProbe() = runTest {
        val slowProbeStarted = CompletableDeferred<Unit>()
        val slowProbeCancelled = CompletableDeferred<Unit>()

        val result = SocksRunningRouteProbe.probeUntilDecided(
            urls = listOf("provider-a", "provider-b", "slow-provider")
        ) { url ->
            if (url == "slow-provider") {
                slowProbeStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    slowProbeCancelled.complete(Unit)
                }
            } else {
                slowProbeStarted.await()
                SocksRunningRouteProbe.RouteProbeOutcome(success = true)
            }
        }

        assertTrue(result.success)
        assertTrue(slowProbeCancelled.isCompleted)
    }

    @Test
    fun probeQuorum_returnsWhenSuccessIsImpossibleAndCancelsTheSlowProbe() = runTest {
        val slowProbeStarted = CompletableDeferred<Unit>()
        val slowProbeCancelled = CompletableDeferred<Unit>()

        val result = SocksRunningRouteProbe.probeUntilDecided(
            urls = listOf("provider-a", "provider-b", "slow-provider")
        ) { url ->
            if (url == "slow-provider") {
                slowProbeStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    slowProbeCancelled.complete(Unit)
                }
            } else {
                slowProbeStarted.await()
                SocksRunningRouteProbe.RouteProbeOutcome(error = "$url: unavailable")
            }
        }

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("Only 0/3"))
        assertTrue(result.error.orEmpty().contains("provider-a: unavailable"))
        assertTrue(result.error.orEmpty().contains("provider-b: unavailable"))
        assertTrue(slowProbeCancelled.isCompleted)
    }
}
