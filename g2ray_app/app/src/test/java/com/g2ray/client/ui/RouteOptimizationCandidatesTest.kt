package com.g2ray.client.ui

import com.g2ray.client.model.StoredG2RayProfile
import com.g2ray.client.model.Transport
import com.g2ray.client.parser.VlessUriParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouteOptimizationCandidatesTest {
    private fun profile(type: String, address: String = "20.85.77.1") = VlessUriParser.parse(
        "vless://11111111-1111-4111-8111-111111111111@$address:443?encryption=none&security=tls" +
            "&sni=demo-443.app.github.dev&host=demo-443.app.github.dev&type=$type&path=%2F&mode=packet-up" +
            (if (type == "xhttp") "&alpn=h2" else "") + "#test"
    ).getOrThrow()

    @Test
    fun largeDiscoverySharesBudgetBetweenWsAndXhttp() {
        val existing = listOf(profile("xhttp"), profile("ws"))
        val generated = G2RayViewModel.generateRouteCandidates(existing, (2..25).map { "20.85.77.$it" })
        assertEquals(12, generated.size)
        assertEquals(6, generated.count { it.transport == Transport.XHTTP })
        assertEquals(6, generated.count { it.transport == Transport.WS })
        assertTrue(generated.none { it.address == "20.85.77.1" })
        generated.forEach {
            assertEquals(existing.first().uuid, it.uuid)
            assertEquals(existing.first().sni, it.sni)
            assertEquals(existing.first().hostHeader, it.hostHeader)
        }
    }

    @Test
    fun duplicatesAndKnownEndpointsDoNotConsumeNewCandidateBudget() {
        val xhttp = profile("xhttp")
        val existing = listOf(xhttp, profile("ws"), profile("xhttp", "20.85.77.2"))
        val generated = G2RayViewModel.generateRouteCandidates(
            existing, listOf("20.85.77.1", "20.85.77.2", "20.85.77.2", "20.85.77.3"), 3
        )
        assertEquals(3, generated.size)
        assertEquals(Transport.WS, generated.first().transport)
        assertEquals(3, generated.map(VlessUriParser::semanticRouteKey).distinct().size)
    }

    @Test
    fun selectedDeadRouteCannotBeTheOnlyAutomaticReadinessCandidate() {
        val selected = profile("xhttp")
        val otherXhttp = profile("xhttp", "20.85.77.2")
        val ws = profile("ws", "20.85.77.3")
        val result = G2RayViewModel.optimizationGuardCandidates(selected, listOf(selected, otherXhttp, ws))
        assertEquals(listOf(selected, ws, otherXhttp), result)
        assertEquals(listOf(selected), G2RayViewModel.optimizationGuardCandidates(selected, listOf(selected, selected)))
    }

    @Test
    fun directGithubWakePermitsVerificationButOldOrFutureWakeDoesNot() {
        val now = 1_000_000L
        assertTrue(G2RayViewModel.hasOptimizationReadinessEvidence(now, emptyList(), now))
        assertFalse(G2RayViewModel.hasOptimizationReadinessEvidence(now - 300_001, emptyList(), now))
        assertFalse(G2RayViewModel.hasOptimizationReadinessEvidence(now + 1, emptyList(), now))
        assertFalse(G2RayViewModel.hasOptimizationReadinessEvidence(null, emptyList(), now))
    }

    @Test
    fun freshSuccessfulPingPermitsAutomaticOptimizationButFailedOrStalePingDoesNot() {
        val now = 1_000_000L
        val stored = StoredG2RayProfile(
            id = "p", accountId = "a", displayName = "Test", rawUri = profile("ws").rawUri,
            transport = Transport.WS, address = "203.0.113.1", port = 443,
            sni = "demo-443.app.github.dev", hostHeader = "demo-443.app.github.dev", path = "/",
            isDefault = false, createdAt = 1, updatedAt = 1,
            lastDelayMs = 280, lastDelayTestedAt = now, lastDelayNetworkKey = "network-a"
        )
        assertTrue(G2RayViewModel.hasOptimizationReadinessEvidence(null, listOf(stored), now, "network-a"))
        assertFalse(G2RayViewModel.hasOptimizationReadinessEvidence(null, listOf(stored.copy(delayFailureCount = 1)), now, "network-a"))
        assertFalse(G2RayViewModel.hasOptimizationReadinessEvidence(null, listOf(stored.copy(lastDelayMs = null)), now, "network-a"))
        assertFalse(G2RayViewModel.hasOptimizationReadinessEvidence(null, listOf(stored), now + ProfileHealth.DELAY_FRESHNESS_MS + 1, "network-a"))
        assertFalse(G2RayViewModel.hasOptimizationReadinessEvidence(null, listOf(stored), now, "network-b"))
    }
}
