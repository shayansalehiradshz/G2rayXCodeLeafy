package com.g2ray.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.G2RayProfile
import com.g2ray.client.model.StoredG2RayProfile
import com.g2ray.client.model.Transport
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class G2RayVpnServiceTest {
    @Test
    fun legacyTimestampWithoutNetworkProvenanceCannotVerifyAStandbyRoute() {
        val legacy = storedProfile("legacy", "203.0.113.2", 80).copy(
            lastDelayTestedAt = 9_000L,
            lastDelayNetworkKey = null,
            lastDelaySuccessfulSamples = 3,
            lastDelaySampleCount = 3
        )
        assertFalse(G2RayVpnService.persistedRouteMeasurementFresh(legacy, 10_000L))
    }

    @Test
    fun automaticReconnectHonorsServerWaitWithoutRemovingLocalBackoff() {
        assertEquals(180_000L, G2RayVpnService.reconnectDelayMs(0, 180_000L))
        assertEquals(60_000L, G2RayVpnService.reconnectDelayMs(4, 10_000L))
        assertEquals(5_000L, G2RayVpnService.reconnectDelayMs(0, -1L))
        assertEquals(com.g2ray.client.network.MAX_WAKE_RETRY_DELAY_MS,
            G2RayVpnService.reconnectDelayMs(0, Long.MAX_VALUE))
    }

    @Test
    fun repeatedProbeFailuresEventuallyRecoverEvenWithBackgroundPayload() {
        assertFalse(G2RayVpnService.routeHealthFailureRequiresRecovery(2, false))
        assertTrue(G2RayVpnService.routeHealthFailureRequiresRecovery(3, false))
        assertFalse(G2RayVpnService.routeHealthFailureRequiresRecovery(3, true))
        assertTrue(G2RayVpnService.routeHealthFailureRequiresRecovery(6, true))
    }

    @Test
    fun optionalEndpointDnsFailureDoesNotBreakSelectedRoute() {
        val selected = liveProfile("203.0.113.1")
        val backup = liveProfile("backup.example")
        val workingBackup = liveProfile("203.0.113.2")
        val result = G2RayVpnService.resolveRuntimeProfiles(listOf(selected, backup, workingBackup)) {
            if (it == backup.address) throw java.net.UnknownHostException("DNS unavailable")
            listOf(it)
        }
        assertEquals(listOf(selected, workingBackup), result)
    }

    @Test
    fun selectedEndpointDnsFailureKeepsACompatibleIpBackup() {
        val selected = liveProfile("selected.example")
        val backup = liveProfile("203.0.113.2")
        val result = G2RayVpnService.resolveRuntimeProfiles(listOf(selected, backup)) {
            if (it == selected.address) throw java.net.UnknownHostException("DNS unavailable")
            listOf(it)
        }
        assertEquals(listOf(backup), result)
    }

    @Test
    fun bootstrapDnsAlternativesPreserveTheCompleteTransportTemplate() {
        val selected = liveProfile("front.example").copy(
            xhttpExtraJson = "{\"scMaxEachPostBytes\":60000}",
            finalMaskJson = "{\"tcp\":[]}",
            cipherSuites = "TLS_AES_128_GCM_SHA256"
        )
        val addresses = listOf("203.0.113.1", "203.0.113.2", "203.0.113.3", "203.0.113.4")
        val result = G2RayVpnService.resolveRuntimeProfiles(listOf(selected)) { addresses }
        assertEquals(addresses.take(3).map { selected.copy(address = it) }, result)
    }

    @Test
    fun bootstrapAlternativesDoNotConsumeSlotsReservedForVerifiedBackups() {
        val selected = liveProfile("front.example")
        val backups = listOf(liveProfile("203.0.113.2"), liveProfile("203.0.113.3"))
        val result = G2RayVpnService.resolveRuntimeProfiles(listOf(selected) + backups) {
            if (it == selected.address) listOf("203.0.113.1", "203.0.113.4", "203.0.113.5") else listOf(it)
        }
        assertEquals(listOf("203.0.113.1", "203.0.113.2", "203.0.113.3"), result.map { it.address })
    }

    @Test
    fun bootstrapAddressesAreDeduplicatedBoundedAndHonorSingleRouteMode() {
        val selected = liveProfile("front.example")
        val answers = listOf("", "203.0.113.1", "203.0.113.1", "203.0.113.2", "203.0.113.3", "203.0.113.4")
        val result = G2RayVpnService.resolveRuntimeProfiles(listOf(selected)) { answers }
        assertEquals(listOf("203.0.113.1", "203.0.113.2", "203.0.113.3"), result.map { it.address })
        val single = G2RayVpnService.resolveRuntimeProfiles(listOf(selected), maxProfiles = 1) { answers }
        assertEquals(listOf(selected.copy(address = "203.0.113.1")), single)
    }

    @Test
    fun primaryDnsFailureCannotPromoteAnUnrelatedProfile() {
        val selected = liveProfile("front.example")
        val unrelated = liveProfile("203.0.113.2").copy(uuid = "00000000-0000-4000-8000-000000000002")
        var unrelatedResolved = false
        val error = runCatching {
            G2RayVpnService.resolveRuntimeProfiles(listOf(selected, unrelated)) {
                if (it == selected.address) throw java.net.UnknownHostException("Primary DNS failed")
                unrelatedResolved = true
                listOf(it)
            }
        }.exceptionOrNull()
        assertTrue(error is java.net.UnknownHostException)
        assertEquals("Primary DNS failed", error?.message)
        assertFalse(unrelatedResolved)
    }

    @Test
    fun emptyPrimaryDnsAnswersCanUseACompatibleIpBackup() {
        val selected = liveProfile("front.example")
        val backup = liveProfile("203.0.113.2")
        val result = G2RayVpnService.resolveRuntimeProfiles(listOf(selected, backup)) {
            if (it == selected.address) emptyList() else listOf(it)
        }
        assertEquals(listOf(backup), result)
    }

    @Test(expected = java.net.UnknownHostException::class)
    fun selectedEndpointDnsFailureStillReportsFailure() {
        G2RayVpnService.resolveRuntimeProfiles(listOf(liveProfile("selected.example"))) {
            throw java.net.UnknownHostException("Selected endpoint unavailable")
        }
    }

    @Test
    fun resolvingAnAliasDoesNotDuplicateTheSameLiveEndpoint() {
        val selected = liveProfile("203.0.113.1")
        val result = G2RayVpnService.resolveRuntimeProfiles(listOf(selected, liveProfile("alias.example"))) {
            listOf("203.0.113.1")
        }
        assertEquals(listOf(selected), result)
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun backupEndpointResolutionPreservesCancellation() {
        G2RayVpnService.resolveRuntimeProfiles(listOf(liveProfile("203.0.113.1"), liveProfile("backup.example"))) {
            if (it == "backup.example") throw kotlinx.coroutines.CancellationException("VPN stopped")
            listOf(it)
        }
    }

    @Test
    fun routeHealthClassificationSeparatesPacketEngineXrayAndBindingFailures() {
        val fullFailure = RunningRouteResult(
            success = false,
            error = "full path timeout",
            failureScope = RouteFailureScope.PacketEngine
        )
        val xraySuccess = RunningRouteResult(success = true, latencyMs = 80L)
        val xrayFailure = RunningRouteResult(success = false, error = "SOCKS route timeout")

        val packetEngine = G2RayVpnService.classifyVpnRouteHealth(fullFailure, xraySuccess)
        assertEquals(RouteFailureScope.PacketEngine, packetEngine.failureScope)
        assertTrue(packetEngine.error.orEmpty().contains("Xray/Codespaces route is reachable"))

        val xrayRoute = G2RayVpnService.classifyVpnRouteHealth(fullFailure, xrayFailure)
        assertEquals(RouteFailureScope.XrayRoute, xrayRoute.failureScope)
        assertTrue(xrayRoute.error.orEmpty().contains("SOCKS route timeout"))

        val successfulRouteIgnoresStaleBindingFailure = G2RayVpnService.classifyVpnRouteHealth(
            fullPath = RunningRouteResult(success = true),
            xrayRoute = xraySuccess,
            dialerFailure = "selected network bind failed"
        )
        assertTrue(successfulRouteIgnoresStaleBindingFailure.success)
        assertEquals(RouteFailureScope.None, successfulRouteIgnoresStaleBindingFailure.failureScope)

        val binding = G2RayVpnService.classifyVpnRouteHealth(
            fullPath = fullFailure,
            xrayRoute = xrayFailure,
            dialerFailure = "selected network bind failed"
        )
        assertEquals(RouteFailureScope.DialerBinding, binding.failureScope)
        assertEquals("selected network bind failed", binding.error)
    }

    private fun storedProfile(
        id: String,
        address: String,
        delayMs: Long,
        rawUri: String = "vless://$id@$address:443"
    ): StoredG2RayProfile {
        return StoredG2RayProfile(
            id = id,
            accountId = "account",
            displayName = id,
            rawUri = rawUri,
            transport = Transport.XHTTP,
            address = address,
            port = 443,
            sni = "space-443.app.github.dev",
            hostHeader = "space-443.app.github.dev",
            path = "/",
            isDefault = false,
            createdAt = delayMs,
            updatedAt = delayMs,
            lastDelayMs = delayMs,
            lastDelayTestedAt = System.currentTimeMillis(),
            lastDelayNetworkKey = "test-network"
        )
    }

    @Test
    fun startRequestDuringCleanupIsQueued() {
        assertTrue(G2RayVpnService.shouldQueueStartRequest(cleanupActive = true))
        assertFalse(G2RayVpnService.shouldQueueStartRequest(cleanupActive = false))
    }

    @Test
    fun tunnelEndpointResolverRecognizesOnlyValidNumericAddresses() {
        assertTrue(SocketProtector.isNumericAddress("20.125.70.28"))
        assertTrue(SocketProtector.isNumericAddress("2001:db8::1"))
        assertFalse(SocketProtector.isNumericAddress("999.125.70.28"))
        assertFalse(SocketProtector.isNumericAddress("codespace-443.app.github.dev"))
    }

    @Test
    fun underlyingNetworkSelectionRejectsVpnAndPrefersValidatedPhysicalNetwork() {
        assertEquals(
            Int.MIN_VALUE,
            G2RayVpnService.underlyingNetworkPreferenceScore(
                hasInternet = true,
                isNotVpn = false,
                isValidated = true,
                isNotMetered = true,
                transportScore = 40
            )
        )
        assertTrue(
            G2RayVpnService.underlyingNetworkPreferenceScore(
                hasInternet = true,
                isNotVpn = true,
                isValidated = true,
                isNotMetered = false,
                transportScore = 20
            ) >
                G2RayVpnService.underlyingNetworkPreferenceScore(
                    hasInternet = true,
                    isNotVpn = true,
                    isValidated = false,
                    isNotMetered = true,
                    transportScore = 40
                )
        )
    }

    @Test
    fun underlyingNetworkSnapshotMergePreservesCallbacksAndSkipsLostNetworks() {
        val merged = G2RayVpnService.mergeUnderlyingNetworkCandidates(
            callbackObserved = mapOf(
                "wifi" to "callback-current"
            ),
            callbackLost = setOf("stale-cellular"),
            snapshot = mapOf(
                "wifi" to "older-snapshot",
                "stale-cellular" to "stale-snapshot",
                "ethernet" to "snapshot-current"
            )
        )

        assertEquals("callback-current", merged["wifi"])
        assertEquals("snapshot-current", merged["ethernet"])
        assertFalse(merged.containsKey("stale-cellular"))
    }

    @Test
    fun localSocksConnectionRefusalTriggersImmediateRecovery() {
        assertTrue(
            G2RayVpnService.shouldRecoverImmediatelyForLocalSocksFailure(
                "failed to connect to /127.0.0.1 (port 45277): isConnected failed: ECONNREFUSED"
            )
        )
    }

    @Test
    fun remoteOrGenericFailuresStillRequireTheNormalTwoProbeThreshold() {
        assertFalse(G2RayVpnService.shouldRecoverImmediatelyForLocalSocksFailure("upstream timeout"))
        assertFalse(
            G2RayVpnService.shouldRecoverImmediatelyForLocalSocksFailure(
                "failed to connect to 203.0.113.1:443: ECONNREFUSED"
            )
        )
    }

    @Test
    fun transitionFailuresAreConfirmedBeforeDisruptiveRecovery() {
        assertTrue(
            G2RayVpnService.shouldConfirmTransitionRouteFailure(
                RunningRouteResult(false, "upstream timeout")
            )
        )
        assertFalse(
            G2RayVpnService.shouldConfirmTransitionRouteFailure(
                RunningRouteResult(
                    false,
                    "failed to connect to /127.0.0.1:12345: ECONNREFUSED"
                )
            )
        )
        assertFalse(
            G2RayVpnService.shouldConfirmTransitionRouteFailure(
                RunningRouteResult(true)
            )
        )
    }

    @Test
    fun failedHealthChecksUseFastConfirmationsWithoutIncreasingNormalProbeTraffic() {
        assertEquals(
            5_000L,
            G2RayVpnService.routeHealthCheckDelayMs(
                routeSuccess = false,
                trafficIsAdvancing = false,
                routeFailureCount = 1
            )
        )
        assertEquals(
            30_000L,
            G2RayVpnService.routeHealthCheckDelayMs(
                routeSuccess = true,
                trafficIsAdvancing = false,
                routeFailureCount = 0
            )
        )
        assertEquals(
            30_000L,
            G2RayVpnService.routeHealthCheckDelayMs(
                routeSuccess = false,
                trafficIsAdvancing = true,
                routeFailureCount = 1
            )
        )
    }

    @Test
    fun lifecycleGuardInvalidatesStaleStartOrRecoveryWork() {
        val guard = TunnelLifecycleGuard()
        val first = guard.begin()

        assertTrue(guard.isCurrent(first))
        guard.invalidate()
        assertFalse(guard.isCurrent(first))

        val second = guard.begin()
        assertTrue(guard.isCurrent(second))
        assertFalse(guard.isCurrent(first))
    }

    @Test
    fun periodicHealthChecksPauseWhileAndroidHasNoUnderlyingNetwork() {
        assertTrue(G2RayVpnService.shouldPauseRouteHealthChecks(waitingForUnderlyingNetwork = true))
        assertFalse(G2RayVpnService.shouldPauseRouteHealthChecks(waitingForUnderlyingNetwork = false))
    }

    @Test
    fun networkReturnRecoversAStoppedCoreInsteadOfSilentlySkippingIt() {
        assertEquals(
            NetworkHandoffAction.RecoverCore,
            G2RayVpnService.networkHandoffAction(
                coroutineActive = true,
                hasVpnInterface = true,
                coreState = TunnelCoreState.Error
            )
        )
        assertEquals(
            NetworkHandoffAction.VerifyRoute,
            G2RayVpnService.networkHandoffAction(
                coroutineActive = true,
                hasVpnInterface = true,
                coreState = TunnelCoreState.Connected
            )
        )
        assertEquals(
            NetworkHandoffAction.Defer,
            G2RayVpnService.networkHandoffAction(
                coroutineActive = true,
                hasVpnInterface = true,
                coreState = TunnelCoreState.Starting
            )
        )
        assertEquals(
            NetworkHandoffAction.Ignore,
            G2RayVpnService.networkHandoffAction(
                coroutineActive = false,
                hasVpnInterface = true,
                coreState = TunnelCoreState.Connected
            )
        )
    }

    @Test
    fun recoveryRetriesOnlyWhenTheUnderlyingNetworkChanged() {
        assertTrue(
            G2RayVpnService.shouldRetryAfterNetworkChange(
                recoveryNetworkGeneration = 4,
                currentNetworkGeneration = 5,
                waitingForUnderlyingNetwork = false
            )
        )
        assertTrue(
            G2RayVpnService.shouldRetryAfterNetworkChange(
                recoveryNetworkGeneration = 5,
                currentNetworkGeneration = 5,
                waitingForUnderlyingNetwork = true
            )
        )
        assertFalse(
            G2RayVpnService.shouldRetryAfterNetworkChange(
                recoveryNetworkGeneration = 5,
                currentNetworkGeneration = 5,
                waitingForUnderlyingNetwork = false
            )
        )
    }

    @Test
    fun repeatedAppResumeDoesNotQueueOverlappingRouteReverification() {
        assertTrue(
            G2RayVpnService.shouldStartRouteReverification(
                routeRecoveryInProgress = false,
                routeRecoveryActive = false,
                routeReverifyActive = false,
                networkHandoffActive = false,
                startActive = false,
                cleanupActive = false
            )
        )
        assertFalse(
            G2RayVpnService.shouldStartRouteReverification(
                routeRecoveryInProgress = false,
                routeRecoveryActive = false,
                routeReverifyActive = true,
                networkHandoffActive = false,
                startActive = false,
                cleanupActive = false
            )
        )
        assertFalse(
            G2RayVpnService.shouldStartRouteReverification(
                routeRecoveryInProgress = true,
                routeRecoveryActive = false,
                routeReverifyActive = false,
                networkHandoffActive = false,
                startActive = false,
                cleanupActive = false
            )
        )
        assertFalse(
            G2RayVpnService.shouldStartRouteReverification(
                routeRecoveryInProgress = false,
                routeRecoveryActive = false,
                routeReverifyActive = false,
                networkHandoffActive = true,
                startActive = false,
                cleanupActive = false
            )
        )
    }

    @Test
    fun startupNetworkChangeRequiresTransitionAwareRouteConfirmation() {
        assertTrue(
            G2RayVpnService.shouldReverifyAfterStartupNetworkChange(
                routeSuccess = true,
                startupNetworkGeneration = 7,
                currentNetworkGeneration = 8
            )
        )
        assertFalse(
            G2RayVpnService.shouldReverifyAfterStartupNetworkChange(
                routeSuccess = true,
                startupNetworkGeneration = 7,
                currentNetworkGeneration = 7
            )
        )
        assertFalse(
            G2RayVpnService.shouldReverifyAfterStartupNetworkChange(
                routeSuccess = false,
                startupNetworkGeneration = 7,
                currentNetworkGeneration = 8
            )
        )
    }

    @Test
    fun appResumeRecoversEveryPersistedTunnelStateWithoutStoppingTheStickyService() {
        assertEquals(
            AppResumeAction.VerifyRoute,
            G2RayVpnService.appResumeAction(
                coreState = TunnelCoreState.Connected,
                hasVpnInterface = true,
                hasActiveProfile = true,
                cleanupActive = false
            )
        )
        assertEquals(
            AppResumeAction.KeepAlive,
            G2RayVpnService.appResumeAction(
                coreState = TunnelCoreState.Starting,
                hasVpnInterface = true,
                hasActiveProfile = true,
                cleanupActive = false
            )
        )
        assertEquals(
            AppResumeAction.RecoverCore,
            G2RayVpnService.appResumeAction(
                coreState = TunnelCoreState.Error,
                hasVpnInterface = true,
                hasActiveProfile = true,
                cleanupActive = false
            )
        )
        assertEquals(
            AppResumeAction.RestartTunnel,
            G2RayVpnService.appResumeAction(
                coreState = TunnelCoreState.Disconnected,
                hasVpnInterface = false,
                hasActiveProfile = false,
                cleanupActive = false
            )
        )
        assertEquals(
            AppResumeAction.QueueRestart,
            G2RayVpnService.appResumeAction(
                coreState = TunnelCoreState.Connected,
                hasVpnInterface = true,
                hasActiveProfile = true,
                cleanupActive = true
            )
        )
        assertEquals(
            AppResumeAction.QueueRestart,
            G2RayVpnService.appResumeAction(
                coreState = TunnelCoreState.Stopping,
                hasVpnInterface = true,
                hasActiveProfile = true,
                cleanupActive = false
            )
        )
    }

    @Test
    fun platformChoosesTheBestPhysicalNetworkOnAndroid12AndNewer() {
        assertFalse(G2RayVpnService.shouldUsePlatformBestUnderlyingNetwork(30))
        assertTrue(G2RayVpnService.shouldUsePlatformBestUnderlyingNetwork(31))
        assertTrue(G2RayVpnService.shouldUsePlatformBestUnderlyingNetwork(36))
    }

    @Test
    fun backupSelectionTriesDifferentEdgeAddressesBeforeTransportVariants() {
        val currentRawUri = "vless://current@20.0.0.9:443"
        val selected = G2RayVpnService.selectAlternativeProfiles(
            profiles = listOf(
                storedProfile("current", "20.0.0.9", 1, currentRawUri),
                storedProfile("edge-a-xhttp", "20.0.0.1", 10),
                storedProfile("edge-a-ws-h2", "20.0.0.1", 11),
                storedProfile("edge-a-ws-h1", "20.0.0.1", 12),
                storedProfile("edge-b", "20.0.0.2", 20),
                storedProfile("edge-c", "20.0.0.3", 30)
            ),
            accountId = "account",
            currentRawUri = currentRawUri,
            maxCandidates = 4
        )

        assertEquals(
            listOf("20.0.0.1", "20.0.0.2", "20.0.0.3"),
            selected.take(3).map { it.address }
        )
        assertEquals(4, selected.size)
        assertFalse(selected.any { it.rawUri == currentRawUri })
    }

    @Test
    fun preWakeBackupSelectionIsBoundedToOneMeasuredRoute() {
        val selected = G2RayVpnService.selectAlternativeProfiles(
            profiles = listOf(
                storedProfile("edge-a", "20.0.0.1", 10),
                storedProfile("edge-b", "20.0.0.2", 20)
            ),
            accountId = "account",
            currentRawUri = "vless://current@20.0.0.9:443",
            maxCandidates = 1
        )

        assertEquals(listOf("20.0.0.1"), selected.map { it.address })
    }

    @Test
    fun liveSessionFailureMovesAStaleFastRouteBehindAnUntriedRoute() {
        val staleFast = storedProfile("stale-fast", "20.0.0.1", 10)
        val untried = storedProfile("untried", "20.0.0.2", 80)

        val selected = G2RayVpnService.selectAlternativeProfiles(
            profiles = listOf(staleFast, untried),
            accountId = "account",
            currentRawUri = "vless://current@20.0.0.9:443",
            maxCandidates = 2,
            sessionHealth = mapOf(
                staleFast.rawUri to SessionProfileHealth(
                    consecutiveFailures = 1,
                    observedAtMs = 100L
                )
            )
        )

        assertEquals(listOf("untried", "stale-fast"), selected.map { it.id })
    }

    @Test
    fun liveSessionSuccessOverridesAnOldPersistedFailure() {
        val recovered = storedProfile("recovered", "20.0.0.1", 500).copy(
            lastDelayMs = null,
            lastDelayError = "old timeout",
            delayFailureCount = 4
        )
        val previouslyFast = storedProfile("previously-fast", "20.0.0.2", 20)

        val selected = G2RayVpnService.selectAlternativeProfiles(
            profiles = listOf(previouslyFast, recovered),
            accountId = "account",
            currentRawUri = "vless://current@20.0.0.9:443",
            maxCandidates = 2,
            sessionHealth = mapOf(
                recovered.rawUri to SessionProfileHealth(
                    lastAttemptSucceeded = true,
                    lastSuccessLatencyMs = 45L,
                    observedAtMs = 200L
                )
            )
        )

        assertEquals(listOf("recovered", "previously-fast"), selected.map { it.id })
    }

    @Test
    fun backupSelection_penalizesPersistedTailLatencyAndJitter() {
        val stable = storedProfile("stable", "20.0.0.1", 100).copy(
            lastDelayP95Ms = 110L,
            lastDelayJitterMs = 5L
        )
        val unstable = storedProfile("unstable", "20.0.0.2", 100).copy(
            lastDelayP95Ms = 500L,
            lastDelayJitterMs = 100L
        )

        val selected = G2RayVpnService.selectAlternativeProfiles(
            profiles = listOf(unstable, stable),
            accountId = "account",
            currentRawUri = "vless://current@20.0.0.9:443",
            maxCandidates = 2,
            networkKey = "test-network"
        )

        assertEquals(listOf("stable", "unstable"), selected.map { it.id })
    }

    @Test
    fun sessionProfileHealthLearnsFailuresAndResetsAfterSuccess() {
        val firstFailure = G2RayVpnService.updatedSessionProfileHealth(
            previous = null,
            success = false,
            latencyMs = null,
            observedAtMs = 100L
        )
        val secondFailure = G2RayVpnService.updatedSessionProfileHealth(
            previous = firstFailure,
            success = false,
            latencyMs = null,
            observedAtMs = 200L
        )
        val recovered = G2RayVpnService.updatedSessionProfileHealth(
            previous = secondFailure,
            success = true,
            latencyMs = 42L,
            observedAtMs = 300L
        )

        assertEquals(2, secondFailure.consecutiveFailures)
        assertEquals(null, secondFailure.lastSuccessLatencyMs)
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(42L, recovered.lastSuccessLatencyMs)
        assertEquals(300L, recovered.observedAtMs)
    }

    @Test
    fun staleSessionProfileHealthExpiresSoRecoveredRoutesCanBeTriedAgain() {
        val fresh = SessionProfileHealth(consecutiveFailures = 1, observedAtMs = 900L)
        val stale = SessionProfileHealth(consecutiveFailures = 2, observedAtMs = 100L)

        val observations = G2RayVpnService.freshSessionProfileHealth(
            observations = mapOf("fresh" to fresh, "stale" to stale),
            nowMs = 1_000L,
            maxAgeMs = 500L
        )

        assertEquals(mapOf("fresh" to fresh), observations)
    }

    @Test
    fun onlyReceivedHevTrafficProvesTheRouteStillCarriesResponses() {
        val previous = TrafficBytes(rxBytes = 100L, txBytes = 200L, rxTcpPayloadBytes = 50L)

        assertTrue(
            G2RayVpnService.measuredResponseTrafficAdvanced(
                ActiveVpnEngine.Hev,
                previous,
                TrafficBytes(rxBytes = 101L, txBytes = 200L, rxTcpPayloadBytes = 51L)
            )
        )
        assertFalse(
            G2RayVpnService.measuredResponseTrafficAdvanced(
                ActiveVpnEngine.Hev,
                previous,
                TrafficBytes(rxBytes = 100L, txBytes = 201L, rxTcpPayloadBytes = 50L)
            )
        )
        assertFalse(
            G2RayVpnService.measuredResponseTrafficAdvanced(
                ActiveVpnEngine.XrayNative,
                previous,
                TrafficBytes(rxBytes = 101L, txBytes = 200L, rxTcpPayloadBytes = 51L)
            )
        )
        assertFalse(
            G2RayVpnService.measuredResponseTrafficAdvanced(
                ActiveVpnEngine.Hev,
                previous,
                null
            )
        )
    }

    @Test
    fun failedReverificationDoesNotRestartAHevTunnelWhoseResponsesResumed() {
        val previous = TrafficBytes(rxBytes = 100L, txBytes = 200L, rxTcpPayloadBytes = 50L)
        val resumed = TrafficBytes(rxBytes = 101L, txBytes = 220L, rxTcpPayloadBytes = 51L)

        assertTrue(
            G2RayVpnService.shouldSuppressRouteRecoveryAfterFailedReverification(
                route = RunningRouteResult(
                    success = false,
                    error = "public probe timed out",
                    failureScope = RouteFailureScope.XrayRoute
                ),
                engine = ActiveVpnEngine.Hev,
                previousTraffic = previous,
                currentTraffic = resumed
            )
        )
        assertFalse(
            G2RayVpnService.shouldSuppressRouteRecoveryAfterFailedReverification(
                route = RunningRouteResult(
                    success = false,
                    error = "dialer binding failed",
                    failureScope = RouteFailureScope.DialerBinding
                ),
                engine = ActiveVpnEngine.Hev,
                previousTraffic = previous,
                currentTraffic = resumed
            )
        )
        assertFalse(
            G2RayVpnService.shouldSuppressRouteRecoveryAfterFailedReverification(
                route = RunningRouteResult(
                    success = false,
                    error = "ECONNREFUSED at 127.0.0.1",
                    failureScope = RouteFailureScope.XrayRoute
                ),
                engine = ActiveVpnEngine.Hev,
                previousTraffic = previous,
                currentTraffic = resumed
            )
        )
    }

    @Test
    fun localSynAckAndAckPacketsCannotProveUpstreamHealth() {
        val previous = TrafficBytes(0L, 0L, rxTcpPayloadBytes = 0L)
        val localSynAck = TrafficBytes(44L, 40L, 1L, 1L, rxTcpPayloadBytes = 0L)
        assertFalse(G2RayVpnService.measuredResponseTrafficAdvanced(ActiveVpnEngine.Hev, previous, localSynAck))
        assertFalse(G2RayVpnService.shouldSuppressRouteRecoveryAfterFailedReverification(
            RunningRouteResult(false, "route unavailable", failureScope = RouteFailureScope.XrayRoute),
            ActiveVpnEngine.Hev, previous, localSynAck
        ))
        assertFalse(G2RayVpnService.measuredResponseTrafficAdvanced(
            ActiveVpnEngine.Hev, TrafficBytes(0L, 0L), TrafficBytes(44L, 40L)
        ))
        assertFalse(G2RayVpnService.shouldSuppressRouteRecoveryAfterFailedReverification(
            RunningRouteResult(false, "TUN unavailable", failureScope = RouteFailureScope.PacketEngine),
            ActiveVpnEngine.Hev, previous, localSynAck.copy(rxTcpPayloadBytes = 100L)
        ))
    }

    @Test
    fun alternateTransportGetsAReservedSlotEvenWithSixUnmeasuredXhttpIps() {
        val uuid = "00000000-0000-0000-0000-000000000000"
        fun uri(address: String, transport: String) =
            "vless://$uuid@$address:443?encryption=none&security=tls&sni=example.com&host=example.com&type=$transport&path=%2F&mode=packet-up&alpn=h2"
        val profiles = (1..8).map {
            storedProfile("xhttp-$it", "20.0.0.$it", it.toLong(), uri("20.0.0.$it", "xhttp"))
        } + storedProfile("working-ws", "20.0.0.1", 99L, uri("20.0.0.1", "ws"))
        val selected = G2RayVpnService.selectAlternativeProfiles(
            profiles, "account", uri("20.0.0.99", "xhttp"), maxCandidates = 6
        )
        assertEquals(6, selected.size)
        assertEquals("working-ws", selected[1].id)
    }

    @Test
    fun firstTrafficAfterIdleRequestsOnePromptRouteReverification() {
        assertTrue(
            G2RayVpnService.shouldReverifyAfterIdleTraffic(
                engine = ActiveVpnEngine.Hev,
                idleDurationMs = 60_000L,
                rxBps = 0L,
                txBps = 1L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
        assertFalse(
            G2RayVpnService.shouldReverifyAfterIdleTraffic(
                engine = ActiveVpnEngine.Hev,
                idleDurationMs = 59_999L,
                rxBps = 0L,
                txBps = 1L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
        assertFalse(
            G2RayVpnService.shouldReverifyAfterIdleTraffic(
                engine = ActiveVpnEngine.Hev,
                idleDurationMs = 60_000L,
                rxBps = 0L,
                txBps = 0L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
        assertFalse(
            G2RayVpnService.shouldReverifyAfterIdleTraffic(
                engine = ActiveVpnEngine.Hev,
                idleDurationMs = 60_000L,
                rxBps = 0L,
                txBps = 1L,
                timeSinceLastReverifyMs = 29_999L
            )
        )
        assertFalse(
            G2RayVpnService.shouldReverifyAfterIdleTraffic(
                engine = ActiveVpnEngine.XrayNative,
                idleDurationMs = 60_000L,
                rxBps = 0L,
                txBps = 1L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
    }

    @Test
    fun sustainedHevTransmitWithoutResponsesRequestsAProbeButNeverInfersFailureDirectly() {
        assertFalse(
            G2RayVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.Hev,
                txBytesSinceLastRx = 32L * 1024L,
                txPacketsSinceLastRx = 0L,
                timeSinceLastRxMs = 5_000L,
                timeSinceLastReverifyMs = 10_000L
            )
        )
        assertFalse(
            G2RayVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.Hev,
                txBytesSinceLastRx = 2L * 1024L,
                txPacketsSinceLastRx = 4L,
                timeSinceLastRxMs = 5_000L,
                timeSinceLastReverifyMs = 10_000L
            )
        )
        assertTrue(
            G2RayVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.Hev,
                txBytesSinceLastRx = 2L * 1024L,
                txPacketsSinceLastRx = 4L,
                timeSinceLastRxMs = 10_000L,
                timeSinceLastReverifyMs = 10_000L
            )
        )
        assertFalse(
            G2RayVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.Hev,
                txBytesSinceLastRx = 32L * 1024L - 1L,
                txPacketsSinceLastRx = 3L,
                timeSinceLastRxMs = 10_000L,
                timeSinceLastReverifyMs = 10_000L
            )
        )
        assertFalse(
            G2RayVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.Hev,
                txBytesSinceLastRx = 32L * 1024L,
                txPacketsSinceLastRx = 0L,
                timeSinceLastRxMs = 9_999L,
                timeSinceLastReverifyMs = 10_000L
            )
        )
        assertFalse(
            G2RayVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.Hev,
                txBytesSinceLastRx = 32L * 1024L,
                txPacketsSinceLastRx = 0L,
                timeSinceLastRxMs = 10_000L,
                timeSinceLastReverifyMs = 9_999L
            )
        )
        assertFalse(
            G2RayVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.XrayNative,
                txBytesSinceLastRx = 32L * 1024L,
                txPacketsSinceLastRx = 20L,
                timeSinceLastRxMs = 10_000L,
                timeSinceLastReverifyMs = 10_000L
            )
        )
    }

    @Test
    fun activeStallRouteFailuresRequireConfirmationExceptForHardLocalFailures() {
        val timeout = RunningRouteResult(
            success = false,
            error = "public probes timed out",
            failureScope = RouteFailureScope.XrayRoute
        )
        assertTrue(
            G2RayVpnService.shouldConfirmActiveStallFailure(
                RouteReverifyReason.ActiveStall,
                timeout
            )
        )
        assertFalse(
            G2RayVpnService.shouldConfirmActiveStallFailure(
                RouteReverifyReason.DeviceWake,
                timeout
            )
        )
        assertFalse(
            G2RayVpnService.shouldConfirmActiveStallFailure(
                RouteReverifyReason.ActiveStall,
                timeout.copy(
                    error = "ECONNREFUSED at 127.0.0.1",
                    failureScope = RouteFailureScope.XrayRoute
                )
            )
        )
        assertFalse(
            G2RayVpnService.shouldConfirmActiveStallFailure(
                RouteReverifyReason.ActiveStall,
                timeout.copy(failureScope = RouteFailureScope.DialerBinding)
            )
        )
    }

    @Test
    fun activeStallWindowResetsOnResponsesAndCounterRestarts() {
        val initial = ActiveStallWindow(
            lastRxBytes = 100L,
            lastTxBytes = 100L,
            lastRxPackets = 10L,
            lastTxPackets = 10L,
            txBytesAtLastRxProgress = 100L,
            txPacketsAtLastRxProgress = 10L,
            lastRxProgressAtMs = 1_000L
        )

        val transmitOnly = G2RayVpnService.advanceActiveStallWindow(
            current = initial,
            rxBytes = 100L,
            txBytes = 200L,
            rxPackets = 10L,
            txPackets = 14L,
            nowMs = 2_000L
        )
        assertEquals(100L, transmitOnly.txBytesAtLastRxProgress)
        assertEquals(1_000L, transmitOnly.lastRxProgressAtMs)

        val response = G2RayVpnService.advanceActiveStallWindow(
            current = transmitOnly,
            rxBytes = 101L,
            txBytes = 250L,
            rxPackets = 11L,
            txPackets = 16L,
            nowMs = 3_000L
        )
        assertEquals(250L, response.txBytesAtLastRxProgress)
        assertEquals(16L, response.txPacketsAtLastRxProgress)
        assertEquals(3_000L, response.lastRxProgressAtMs)

        val countersRestarted = G2RayVpnService.advanceActiveStallWindow(
            current = response,
            rxBytes = 0L,
            txBytes = 0L,
            rxPackets = 0L,
            txPackets = 0L,
            nowMs = 4_000L
        )
        assertEquals(0L, countersRestarted.txBytesAtLastRxProgress)
        assertEquals(4_000L, countersRestarted.lastRxProgressAtMs)
    }

    @Test
    fun screenWakeAfterIdleRequestsRouteReverificationOnlyForAVerifiedTunnel() {
        assertTrue(
            G2RayVpnService.shouldReverifyAfterDeviceWake(
                tunnelState = com.g2ray.client.model.TunnelState.Connected,
                tunnelVerified = true,
                screenIdleDurationMs = 60_000L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
        assertFalse(
            G2RayVpnService.shouldReverifyAfterDeviceWake(
                tunnelState = com.g2ray.client.model.TunnelState.Connected,
                tunnelVerified = false,
                screenIdleDurationMs = 60_000L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
        assertFalse(
            G2RayVpnService.shouldReverifyAfterDeviceWake(
                tunnelState = com.g2ray.client.model.TunnelState.Connected,
                tunnelVerified = true,
                screenIdleDurationMs = 59_999L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
        assertFalse(
            G2RayVpnService.shouldReverifyAfterDeviceWake(
                tunnelState = com.g2ray.client.model.TunnelState.Connected,
                tunnelVerified = true,
                screenIdleDurationMs = 60_000L,
                timeSinceLastReverifyMs = 29_999L
            )
        )
    }

    @Test
    fun stoppedHevRuntimeIsDetectedWithoutMisclassifyingNativeOrInactiveEngines() {
        assertTrue(
            G2RayVpnService.packetEngineRuntimeStopped(
                engine = ActiveVpnEngine.Hev,
                packetEngineExpected = true,
                nativeRuntimeRunning = false
            )
        )
        assertFalse(
            G2RayVpnService.packetEngineRuntimeStopped(
                engine = ActiveVpnEngine.Hev,
                packetEngineExpected = true,
                nativeRuntimeRunning = true
            )
        )
        assertFalse(
            G2RayVpnService.packetEngineRuntimeStopped(
                engine = ActiveVpnEngine.Hev,
                packetEngineExpected = false,
                nativeRuntimeRunning = false
            )
        )
        assertFalse(
            G2RayVpnService.packetEngineRuntimeStopped(
                engine = ActiveVpnEngine.XrayNative,
                packetEngineExpected = true,
                nativeRuntimeRunning = false
            )
        )
    }

    @Test
    fun seamlessRoutePoolKeepsSelectedRouteAndOnlyCompatibleDistinctBackups() {
        val selected = liveProfile("20.0.0.1", mode = "packet-up")
        val compatibleFast = liveProfile("20.0.0.2", mode = "packet-up")
        val compatibleSecond = liveProfile("20.0.0.3", mode = "packet-up")
        val duplicateAddress = liveProfile("20.0.0.2", mode = "packet-up")
        val wrongMode = liveProfile("20.0.0.4", mode = "stream-up")
        val wrongCodespace = liveProfile("20.0.0.5", mode = "packet-up").copy(
            uuid = "11111111-1111-4111-8111-111111111111"
        )

        val selectedRoutes = G2RayVpnService.selectSeamlessRouteProfiles(
            selected = selected,
            rankedCandidates = listOf(
                compatibleFast,
                duplicateAddress,
                wrongMode,
                wrongCodespace,
                compatibleSecond
            ),
            maxProfiles = 3
        )

        assertEquals(
            listOf("20.0.0.1", "20.0.0.2", "20.0.0.3"),
            selectedRoutes.map { it.address }
        )
    }

    @Test
    fun seamlessRoutePoolCanBeBoundedToTheSelectedRouteOnly() {
        val selected = liveProfile("20.0.0.1")
        val selectedRoutes = G2RayVpnService.selectSeamlessRouteProfiles(
            selected = selected,
            rankedCandidates = listOf(liveProfile("20.0.0.2")),
            maxProfiles = 1
        )

        assertEquals(listOf("20.0.0.1"), selectedRoutes.map { it.address })
    }

    @Test
    fun seamlessRoutePoolOnlyTrustsFreshSuccessfulPersistedMeasurements() {
        val now = 10_000L
        val fresh = storedProfile("fresh", "20.0.0.1", 80).copy(
            lastDelayTestedAt = 9_000L, lastDelaySuccessfulSamples = 3, lastDelaySampleCount = 3
        )
        val stale = fresh.copy(id = "stale", lastDelayTestedAt = 1_000L)
        val future = fresh.copy(id = "future", lastDelayTestedAt = 11_000L)
        val failing = fresh.copy(id = "failing", delayFailureCount = 1)

        assertTrue(G2RayVpnService.persistedRouteMeasurementFresh(fresh, now, maxAgeMs = 2_000L, networkKey = "test-network"))
        assertFalse(G2RayVpnService.persistedRouteMeasurementFresh(stale, now, maxAgeMs = 2_000L, networkKey = "test-network"))
        assertFalse(G2RayVpnService.persistedRouteMeasurementFresh(future, now, maxAgeMs = 2_000L, networkKey = "test-network"))
        assertFalse(G2RayVpnService.persistedRouteMeasurementFresh(failing, now, maxAgeMs = 2_000L, networkKey = "test-network"))
        assertFalse(G2RayVpnService.persistedRouteMeasurementFresh(
            fresh.copy(lastDelaySuccessfulSamples = 1, lastDelaySampleCount = 3), now, networkKey = "test-network"
        ))
        assertFalse(G2RayVpnService.persistedRouteMeasurementFresh(
            fresh.copy(lastDelaySuccessfulSamples = 1, lastDelaySampleCount = 1), now, networkKey = "test-network"
        ))
    }

    @Test
    fun otherNetworkHistoryCannotQualifyOrOutrankAMeasuredCurrentNetworkRoute() {
        val now = 10_000L
        val oldFast = storedProfile("old-fast", "203.0.113.1", 10).copy(lastDelayTestedAt = now, lastDelayNetworkKey = "old-network")
        val current = storedProfile("current", "203.0.113.2", 200).copy(lastDelayTestedAt = now)
        val chosen = G2RayVpnService.selectAlternativeProfiles(
            listOf(oldFast, current), "account", "vless://selected", maxCandidates = 2,
            networkKey = "test-network", nowMs = now
        )
        assertEquals(listOf("current", "old-fast"), chosen.map { it.id })
        assertEquals(Long.MAX_VALUE, G2RayVpnService.persistedRouteScore(oldFast, "test-network", now))
        assertEquals(Long.MAX_VALUE, G2RayVpnService.persistedRouteScore(current.copy(lastDelayTestedAt = now + 1L), "test-network", now))
        assertFalse(G2RayVpnService.persistedRouteMeasurementFresh(
            oldFast.copy(lastDelaySuccessfulSamples = 3, lastDelaySampleCount = 3), now, networkKey = "test-network"
        ))
    }

    private fun liveProfile(address: String, mode: String = "packet-up"): G2RayProfile {
        return G2RayProfile(
            rawUri = "vless://00000000-0000-4000-8000-000000000000@$address:443",
            name = address,
            uuid = "00000000-0000-4000-8000-000000000000",
            address = address,
            port = 443,
            security = "tls",
            sni = "space-443.app.github.dev",
            hostHeader = "space-443.app.github.dev",
            alpn = listOf("h2"),
            fingerprint = "chrome",
            transport = Transport.XHTTP,
            path = "/",
            mode = mode,
            allowInsecure = false
        )
    }
}
