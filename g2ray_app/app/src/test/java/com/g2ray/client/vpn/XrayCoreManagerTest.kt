package com.g2ray.client.vpn

import com.g2ray.client.model.G2RayProfile
import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
class XrayCoreManagerTest {

    @Test
    fun start_availableBridge_setsTunFdRegistersProtectionAndRunsVpnConfig() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val manager = readyCore(bridge, FakeRunningRouteProbe(success = true))
        val profile = sampleProfile()

        manager.start(profile, tunFd = 42, datDir = "/data/user/0/com.g2ray.client/files") { fd ->
            fd == 7
        }

        assertEquals("lastError=${manager.lastError.value}", TunnelCoreState.Connected, manager.state.value)
        assertEquals(null, manager.lastError.value)
        assertEquals(42, bridge.tunFd)
        assertEquals("/data/user/0/com.g2ray.client/files", bridge.datDir)
        assertTrue(bridge.configJson.contains("\"protocol\": \"tun\""))
        assertTrue(bridge.configJson.contains("\"protocol\": \"vless\""))
        val tunFd = JSONObject(bridge.configJson).getJSONObject("env").get("xray.tun.fd")
        assertTrue(tunFd is String)
        assertEquals("42", tunFd)
        assertTrue(bridge.dialerProtected)
        assertTrue(bridge.listenerProtected)
        assertTrue(bridge.configJson.contains("\"health-socks\""))
    }

    @Test
    fun start_forwardsFastDnsAndIpv4OnlyIntoTheRuntimeConfig() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val manager = readyCore(bridge, FakeRunningRouteProbe(success = true))

        manager.start(
            profile = sampleProfile(),
            tunFd = 42,
            datDir = "/tmp",
            protectSocket = { true },
            engine = ActiveVpnEngine.XrayNative,
            fakeDnsEnabled = true,
            ipv4Only = true
        )

        val root = JSONObject(bridge.configJson)
        assertEquals("240.0.0.0/12", root.getJSONObject("fakeDns").getString("ipPool"))
        assertFalse(root.has("fakedns"))
        val gateway = root.getJSONArray("inbounds")
            .getJSONObject(0)
            .getJSONObject("settings")
            .getJSONArray("gateway")
        assertEquals(1, gateway.length())
        assertEquals("198.18.0.1/15", gateway.getString(0))
    }

    @Test
    fun start_registersDifferentDialerAndListenerPolicies() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val manager = readyCore(bridge, FakeRunningRouteProbe(success = true))

        manager.start(
            profile = sampleProfile(),
            tunFd = 42,
            datDir = "/tmp",
            protectSocket = { false },
            protectListenerSocket = { true },
            engine = ActiveVpnEngine.XrayNative
        )

        assertFalse(bridge.dialerProtected)
        assertTrue(bridge.listenerProtected)
    }

    @Test
    fun verifyRunningRoute_usesTheLiveXrayHealthSocksInbound() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val routeProbe = FakeRunningRouteProbe(success = true)
        val manager = readyCore(bridge, routeProbe)

        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }
        val result = manager.verifyRunningRoute()

        assertTrue(result.success)
        assertEquals(19090, routeProbe.lastPort)
    }

    @Test
    fun start_hevEngineUsesLoopbackSocksInsteadOfPassingTheTunFdToXray() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val manager = readyCore(bridge, FakeRunningRouteProbe(success = true))

        manager.start(
            profile = sampleProfile(),
            tunFd = 42,
            datDir = "/tmp",
            protectSocket = { true },
            engine = ActiveVpnEngine.Hev
        )

        assertEquals(TunnelCoreState.Connected, manager.state.value)
        assertEquals(0, bridge.tunFd)
        assertEquals(19090, manager.activeSocksPort())
        assertTrue(bridge.configJson.contains("\"tag\": \"tun-socks\""))
        assertFalse(bridge.configJson.contains("\"protocol\": \"tun\""))
        assertFalse(JSONObject(bridge.configJson).has("env"))
    }

    @Test
    fun verifyRunningRoute_doesNotClaimSuccessWhenTheLiveProxyProbeFails() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val routeProbe = FakeRunningRouteProbe(success = false, error = "upstream timeout")
        val manager = readyCore(bridge, routeProbe)

        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }
        val result = manager.verifyRunningRoute()

        assertFalse(result.success)
        assertEquals("upstream timeout", result.error)
    }

    @Test
    fun resetRouteProbeConnectionsDelegatesToTheLiveProbe() {
        val bridge = FakeXrayBridge(available = true)
        val routeProbe = FakeRunningRouteProbe(success = true)
        val manager = readyCore(bridge, routeProbe)

        manager.resetRouteProbeConnections()

        assertEquals(1, routeProbe.resetCount)
    }

    @Test
    fun start_missingBridge_entersErrorStateAndDoesNotRun() = runTest {
        val bridge = FakeXrayBridge(available = false)
        val manager = readyCore(bridge)

        assertFalse(manager.isAvailable())

        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }

        assertEquals(TunnelCoreState.Error, manager.state.value)
        assertTrue(manager.lastError.value?.contains("libXray.aar") == true)
        assertFalse(bridge.runCalled)
    }

    @Test
    fun startupFailure_configRejectedByCoreIsNotRetryable() = runTest {
        val error = "infra/conf/serial: failed to decode config > json: cannot unmarshal string into Go struct field XmuxConfig.maxConnections of type int32"
        val bridge = FakeXrayBridge(available = true).apply {
            runResults += XrayCallResult(false, error)
        }
        val manager = readyCore(bridge)

        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }

        assertEquals(TunnelCoreState.Error, manager.state.value)
        assertEquals(error, manager.startupFailureResult().error)
        assertFalse(manager.startupFailureResult().retryAllowed)
        assertEquals(RouteFailureScope.Configuration, manager.startupFailureResult().failureScope)
        assertTrue(bridge.stopCalled)
    }

    @Test
    fun startupFailure_missingCoreRequiresRebuildRatherThanNetworkRetry() = runTest {
        val manager = readyCore(FakeXrayBridge(available = false))

        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }

        assertFalse(manager.startupFailureResult().retryAllowed)
    }

    @Test
    fun startupFailure_malformedProfileExtraRequiresConfigurationChange() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val manager = readyCore(bridge)

        manager.start(sampleProfile().copy(xhttpExtraJson = "{"), tunFd = 42, datDir = "/tmp") { true }

        assertEquals(TunnelCoreState.Error, manager.state.value)
        assertFalse(bridge.runCalled)
        assertFalse(manager.startupFailureResult().retryAllowed)
    }

    @Test
    fun startupFailure_transientNativeErrorKeepsAutomaticRecoveryEnabled() = runTest {
        val bridge = FakeXrayBridge(available = true).apply {
            runResults += XrayCallResult(false, "socket: too many open files")
        }
        val manager = readyCore(bridge)

        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }

        assertTrue(manager.startupFailureResult().retryAllowed)
    }

    @Test
    fun startupFailure_addressCollisionsRemainRetryableAfterPortBudget() = runTest {
        val bridge = FakeXrayBridge(available = true).apply {
            repeat(3) { runResults += XrayCallResult(false, "bind: address already in use") }
        }
        val manager = readyCore(bridge)

        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }

        assertEquals(TunnelCoreState.Error, manager.state.value)
        assertTrue(manager.startupFailureResult().retryAllowed)
        assertTrue(bridge.runResults.isEmpty())
    }

    @Test
    fun startupFailure_configurationRejectionDoesNotPoisonLaterStartup() = runTest {
        val bridge = FakeXrayBridge(available = true).apply {
            runResults += XrayCallResult(false, "infra/conf: Failed to build transport config")
            runResults += XrayCallResult(true, null)
        }
        val manager = readyCore(bridge)
        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }
        assertFalse(manager.startupFailureResult().retryAllowed)

        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }

        assertEquals(TunnelCoreState.Connected, manager.state.value)
        assertEquals(null, manager.lastError.value)
        bridge.nativeRunning = false
        assertFalse(manager.refreshRunningState())
        assertTrue(manager.startupFailureResult().retryAllowed)
    }

    @Test
    fun start_realDelayFailure_doesNotBlockCoreStartup() = runTest {
        val bridge = FakeXrayBridge(available = true).apply {
            pingResult = XrayPingResult(success = false, delayMs = null, error = "timeout")
        }
        val manager = readyCore(bridge)

        manager.start(sampleProfile(), tunFd = 42, datDir = System.getProperty("java.io.tmpdir") ?: ".") { true }

        assertEquals(TunnelCoreState.Connected, manager.state.value)
        assertEquals(null, manager.lastError.value)
        assertTrue(bridge.runCalled)
        assertEquals(0, bridge.pingRequests.size)
    }

    @Test
    fun start_doesNotRunPreflightEndpointsBeforeCore() = runTest {
        val bridge = FakeXrayBridge(available = true).apply {
            pingResults += XrayPingResult(success = false, delayMs = null, error = "blocked")
            pingResults += XrayPingResult(success = true, delayMs = 222, error = null)
        }
        val manager = readyCore(bridge)

        manager.start(sampleProfile(), tunFd = 42, datDir = System.getProperty("java.io.tmpdir") ?: ".") { true }

        assertEquals(TunnelCoreState.Connected, manager.state.value)
        assertTrue(bridge.runCalled)
        assertEquals(0, bridge.pingRequests.size)
    }

    @Test
    fun refreshRunningState_stoppedNativeCoreMovesToError() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val manager = readyCore(bridge)
        manager.start(sampleProfile(), tunFd = 42, datDir = System.getProperty("java.io.tmpdir") ?: ".") { true }

        bridge.nativeRunning = false
        val running = manager.refreshRunningState()

        assertFalse(running)
        assertEquals(TunnelCoreState.Error, manager.state.value)
        assertTrue(manager.lastError.value?.contains("stopped unexpectedly") == true)
    }

    @Test
    fun stop_runningBridgeStopsCoreAndDisconnects() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val manager = readyCore(bridge)
        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }

        manager.stop()

        assertTrue(bridge.stopCalled)
        assertEquals(TunnelCoreState.Disconnected, manager.state.value)
    }

    @Test
    fun stop_coreThatStaysRunningDoesNotClaimDisconnected() = runTest {
        val bridge = FakeXrayBridge(available = true).apply {
            stopLeavesCoreRunning = true
        }
        val manager = XrayCoreManager(
            bridge = bridge,
            runningRouteProbe = FakeRunningRouteProbe(success = true),
            portAllocator = { 19090 },
            localSocksListenerProbe = LocalSocksListenerProbe { true },
            stopWaitTimeoutMs = 1L,
            stopPollIntervalMs = 1L
        )
        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }

        manager.stop()

        assertTrue(bridge.stopCalled)
        assertEquals(TunnelCoreState.Error, manager.state.value)
        assertTrue(manager.lastError.value?.contains("did not stop") == true)
    }

    @Test
    fun start_retriesAnAddressInUseFailureWithANewEphemeralPort() = runTest {
        val bridge = FakeXrayBridge(available = true).apply {
            runResults += XrayCallResult(success = false, error = "bind: address already in use")
            runResults += XrayCallResult(success = true, error = null)
        }
        val ports = ArrayDeque(listOf(19090, 19091))
        val manager = XrayCoreManager(
            bridge = bridge,
            runningRouteProbe = FakeRunningRouteProbe(success = true),
            portAllocator = { ports.removeFirst() },
            localSocksListenerProbe = LocalSocksListenerProbe { true }
        )

        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }

        assertEquals(TunnelCoreState.Connected, manager.state.value)
        assertEquals(19091, manager.activeSocksPort())
        assertTrue(bridge.runCalled)
    }

    @Test
    fun refreshRunningState_toleratesOneTransientStateQueryFailure() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val manager = readyCore(bridge)
        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }
        bridge.stateQueryFailures = 2

        assertTrue(manager.refreshRunningState())
        assertEquals(TunnelCoreState.Connected, manager.state.value)
        assertFalse(manager.refreshRunningState())
        assertEquals(TunnelCoreState.Error, manager.state.value)
    }

    @Test
    fun start_listenerNeverBinds_stopsCoreAndNeverClaimsConnected() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val manager = XrayCoreManager(
            bridge = bridge,
            runningRouteProbe = FakeRunningRouteProbe(success = true),
            portAllocator = { 19090 },
            localSocksListenerProbe = LocalSocksListenerProbe { false }
        )

        manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }

        assertEquals(TunnelCoreState.Error, manager.state.value)
        assertEquals(null, manager.activeSocksPort())
        assertTrue(bridge.stopCalled)
        assertTrue(manager.lastError.value?.contains("Local SOCKS listener") == true)
    }

    @Test
    fun start_cancelledWhileListenerIsBinding_stopsCoreAndPropagatesCancellation() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val listenerProbeEntered = CompletableDeferred<Unit>()
        val manager = XrayCoreManager(
            bridge = bridge,
            runningRouteProbe = FakeRunningRouteProbe(success = true),
            portAllocator = { 19090 },
            localSocksListenerProbe = LocalSocksListenerProbe {
                listenerProbeEntered.complete(Unit)
                awaitCancellation()
            }
        )

        val startJob = launch {
            manager.start(sampleProfile(), tunFd = 42, datDir = "/tmp") { true }
        }
        listenerProbeEntered.await()
        startJob.cancelAndJoin()

        assertTrue(startJob.isCancelled)
        assertTrue(bridge.stopCalled)
        assertEquals(null, manager.activeSocksPort())
        assertEquals(TunnelCoreState.Disconnected, manager.state.value)
    }

    @Test
    fun tcpLocalSocksListenerProbe_requiresAnActualLoopbackListener() = runTest {
        val openListenerProbe =
            TcpLocalSocksListenerProbe(timeoutMs = 1_000L, retryDelayMs = 10L, connectTimeoutMs = 50)
        val closedPort = ServerSocket().use { socket ->
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            assertTrue(openListenerProbe.awaitReady(socket.localPort))
            socket.localPort
        }

        val closedListenerProbe =
            TcpLocalSocksListenerProbe(timeoutMs = 0L, retryDelayMs = 10L, connectTimeoutMs = 50)
        assertFalse(closedListenerProbe.awaitReady(closedPort))
    }

    @Test
    fun measureDelay_availableBridgeBuildsTemporaryConfigAndReturnsDelay() {
        val bridge = FakeXrayBridge(available = true)
        val manager = XrayCoreManager(bridge)

        val result = manager.measureDelay(
            profile = sampleProfile(),
            datDir = System.getProperty("java.io.tmpdir") ?: ".",
            timeoutSeconds = 5,
            testUrl = "https://example.com/generate_204"
        )

        assertTrue(result.success)
        assertEquals(123L, result.delayMs)
        val requestJson = String(android.util.Base64.decode(bridge.pingRequest, android.util.Base64.DEFAULT))
        val request = JSONObject(requestJson)
        assertEquals("https://example.com/generate_204", request.getString("url"))
        assertEquals(5, request.getInt("timeout"))
        assertTrue(request.getString("proxy").startsWith("socks5://127.0.0.1:"))
        assertTrue(bridge.pingConfigJson.contains("\"protocol\": \"socks\""))
        assertTrue(bridge.pingConfigJson.contains("\"protocol\": \"vless\""))
    }

    @Test
    fun measureDelay_retriesOneAddressInUseFailureWithANewPort() {
        val bridge = FakeXrayBridge(available = true).apply {
            pingResults += XrayPingResult(success = false, delayMs = null, error = "bind: address already in use")
            pingResults += XrayPingResult(success = true, delayMs = 131, error = null)
        }
        val ports = ArrayDeque(listOf(19090, 19091))
        val manager = XrayCoreManager(
            bridge = bridge,
            runningRouteProbe = FakeRunningRouteProbe(success = true),
            portAllocator = { ports.removeFirst() },
            localSocksListenerProbe = LocalSocksListenerProbe { true }
        )

        val result = manager.measureDelay(
            profile = sampleProfile(),
            datDir = System.getProperty("java.io.tmpdir") ?: ".",
            timeoutSeconds = 5,
            testUrl = "https://example.com/generate_204"
        )

        assertTrue(result.success)
        assertEquals(131L, result.delayMs)
        assertEquals(2, bridge.pingRequests.size)
        val firstRequest = JSONObject(
            String(android.util.Base64.decode(bridge.pingRequests[0], android.util.Base64.DEFAULT))
        )
        val secondRequest = JSONObject(
            String(android.util.Base64.decode(bridge.pingRequests[1], android.util.Base64.DEFAULT))
        )
        assertEquals("socks5://127.0.0.1:19090", firstRequest.getString("proxy"))
        assertEquals("socks5://127.0.0.1:19091", secondRequest.getString("proxy"))
    }

    @Test
    fun measureDelayAny_usesAtMostTwoBoundedPreflightEndpoints() {
        val bridge = FakeXrayBridge(available = true).apply {
            pingResults += XrayPingResult(success = false, delayMs = null, error = "timeout")
            pingResults += XrayPingResult(success = false, delayMs = null, error = "timeout")
            pingResults += XrayPingResult(success = true, delayMs = 123, error = null)
        }
        val manager = XrayCoreManager(bridge)

        val result = manager.measureDelayAny(
            profile = sampleProfile(),
            datDir = System.getProperty("java.io.tmpdir") ?: ".",
            timeoutSeconds = 5,
            testUrls = listOf("https://one.example", "https://two.example", "https://three.example")
        )

        assertFalse(result.success)
        assertEquals(2, bridge.pingRequests.size)
    }

    @Test
    fun summarizeDelayValues_usesMedianP95AndJitterInsteadOfFirstSample() {
        val summary = XrayCoreManager.summarizeDelayValues(listOf(100, 120, 220), totalSamples = 3)

        assertTrue(summary.success)
        assertEquals(120L, summary.medianMs)
        assertEquals(220L, summary.p95Ms)
        assertEquals(40L, summary.jitterMs)
        assertEquals(3, summary.successfulSamples)
        assertEquals(3, summary.totalSamples)
    }

    @Test
    fun measureDelaySummary_collectsBoundedSamplesBeforeReportingSuccess() {
        val bridge = FakeXrayBridge(available = true).apply {
            pingResults += XrayPingResult(success = true, delayMs = 100, error = null)
            pingResults += XrayPingResult(success = true, delayMs = 300, error = null)
            pingResults += XrayPingResult(success = true, delayMs = 200, error = null)
        }
        val manager = XrayCoreManager(bridge)

        val summary = manager.measureDelaySummary(
            profile = sampleProfile(),
            datDir = System.getProperty("java.io.tmpdir") ?: ".",
            testUrls = listOf("https://one.example", "https://two.example", "https://three.example"),
            sampleCount = 3
        )

        assertTrue(summary.success)
        assertEquals(200L, summary.medianMs)
        assertEquals(300L, summary.p95Ms)
        assertEquals(3, summary.successfulSamples)
        assertEquals(3, summary.totalSamples)
        assertEquals(3, bridge.pingRequests.size)
    }

    @Test
    fun measureDelayQuick_usesOneBoundedSampleForBatchRefresh() {
        val bridge = FakeXrayBridge(available = true).apply {
            pingResults += XrayPingResult(success = true, delayMs = 144, error = null)
            pingResults += XrayPingResult(success = true, delayMs = 999, error = null)
        }
        val manager = XrayCoreManager(bridge)

        val summary = manager.measureDelayQuick(
            profile = sampleProfile(),
            datDir = System.getProperty("java.io.tmpdir") ?: ".",
            timeoutSeconds = 3,
            testUrl = "https://one.example"
        )

        assertTrue(summary.success)
        assertEquals(144L, summary.medianMs)
        assertEquals(1, summary.successfulSamples)
        assertEquals(1, summary.totalSamples)
        assertEquals(1, bridge.pingRequests.size)
        val requestJson = String(android.util.Base64.decode(bridge.pingRequest, android.util.Base64.DEFAULT))
        assertEquals(3, JSONObject(requestJson).getInt("timeout"))
    }

    @Test
    fun delayConfirmationResamplesSuccessfulRoutesInsteadOfSelectingOneLuckyResponse() {
        val bridge = FakeXrayBridge(available = true).apply {
            invokeAvailable = true
            listOf(listOf(40L, 170L), listOf(900L, 180L), listOf(800L, 175L)).forEach { round ->
                batchResults += XrayPingBatchResult(true, round.map { XrayPingResult(true, it, null) }, null)
            }
        }
        val summaries = XrayCoreManager(bridge).measureDelaySummaryBatch(
            listOf(sampleProfile(), sampleProfile().copy(address = "203.0.113.2")),
            System.getProperty("java.io.tmpdir") ?: "."
        )
        assertEquals(800L, summaries[0].medianMs)
        assertEquals(900L, summaries[0].p95Ms)
        assertEquals(175L, summaries[1].medianMs)
        assertTrue(summaries[0].jitterMs!! > summaries[1].jitterMs!!)
        assertTrue(summaries.all { it.totalSamples == 3 && it.successfulSamples == 3 })
        assertEquals(SocksRunningRouteProbe.HEALTH_URLS, bridge.batchRequests.map { it.second })
        assertTrue(bridge.batchRequests.all { it.first.size == 2 })
        assertTrue(bridge.batchRequests.flatMap { it.first }.all { !java.io.File(it.configPath).exists() })
        assertTrue(bridge.pingRequests.isEmpty())
    }

    @Test
    fun delayConfirmationCountsFailuresWithoutRetryingUntilTheyLookReliable() {
        val bridge = FakeXrayBridge(available = true).apply {
            invokeAvailable = true
            batchResults += XrayPingBatchResult(true, listOf(
                XrayPingResult(true, 40L, null), XrayPingResult(true, 170L, null)), null)
            batchResults += XrayPingBatchResult(true, listOf(
                XrayPingResult(false, null, "timeout"), XrayPingResult(true, 180L, null)), null)
            batchResults += XrayPingBatchResult(true, listOf(
                XrayPingResult(false, null, "timeout"), XrayPingResult(false, null, "timeout")), null)
        }
        val results = XrayCoreManager(bridge).measureDelaySummaryBatch(
            listOf(sampleProfile(), sampleProfile()), System.getProperty("java.io.tmpdir") ?: "."
        )
        assertEquals(1, results[0].successfulSamples)
        assertEquals(2, results[1].successfulSamples)
        assertTrue(results.all { it.totalSamples == 3 && it.error?.contains("timeout") == true })
        assertEquals(3, bridge.batchRequests.size)
        assertTrue(bridge.pingRequests.isEmpty())
    }

    @Test
    fun delayConfirmationSupportsOlderAarsWithTheSameSamplesAndCleanup() {
        val bridge = FakeXrayBridge(available = true).apply {
            listOf(100L, 200L, 300L, 210L, 150L, 220L).forEach {
                pingResults += XrayPingResult(true, it, null)
            }
        }
        val results = XrayCoreManager(bridge).measureDelaySummaryBatch(
            listOf(sampleProfile(), sampleProfile()), System.getProperty("java.io.tmpdir") ?: "."
        )
        assertEquals(150L, results[0].medianMs)
        assertEquals(210L, results[1].medianMs)
        assertTrue(bridge.batchRequests.isEmpty())
        val requests = bridge.pingRequests.map {
            JSONObject(String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)))
        }
        assertEquals(SocksRunningRouteProbe.HEALTH_URLS.flatMap { listOf(it, it) }, requests.map { it.getString("url") })
        assertTrue(requests.all { !java.io.File(it.getString("configPath")).exists() })
    }

    @Test
    fun malformedConfirmationBatchFallsBackOnceWithoutRepeatingNativeBatchFailures() {
        val bridge = FakeXrayBridge(available = true).apply {
            invokeAvailable = true
            batchResult = XrayPingBatchResult(true, emptyList(), null)
            pingResult = XrayPingResult(true, 120L, null)
        }
        val results = XrayCoreManager(bridge).measureDelaySummaryBatch(
            listOf(sampleProfile(), sampleProfile()), System.getProperty("java.io.tmpdir") ?: "."
        )
        assertTrue(results.all { it.successfulSamples == 3 && it.totalSamples == 3 })
        assertEquals(1, bridge.batchRequests.size)
        assertEquals(6, bridge.pingRequests.size)
        assertTrue(bridge.batchConfigs.all { !java.io.File(it.configPath).exists() })
    }

    @Test
    fun confirmationUsesThreeProvidersAndToleratesOneUnreachableProvider() {
        val bridge = FakeXrayBridge(available = true).apply {
            invokeAvailable = true
            listOf(100L, null, 150L).forEach { delay ->
                batchResults += XrayPingBatchResult(true,
                    listOf(XrayPingResult(delay != null, delay, if (delay == null) "Endpoint unreachable" else null)), null)
            }
        }
        val summary = XrayCoreManager(bridge).measureDelaySummaryBatch(
            listOf(sampleProfile()), System.getProperty("java.io.tmpdir") ?: ".", testUrls = emptyList()
        ).single()
        assertEquals(2, summary.successfulSamples)
        assertEquals(3, summary.totalSamples)
        assertEquals(listOf("cp.cloudflare.com", "www.gstatic.com", "www.apple.com"),
            bridge.batchRequests.map { java.net.URI(it.second).host })
    }

    @Test
    fun cancelledConfirmationCannotStartAnotherRoundOrFallback() {
        val bridge = FakeXrayBridge(available = true).apply {
            invokeAvailable = true
            batchResult = XrayPingBatchResult(true, listOf(XrayPingResult(true, 80L, null)), null)
        }
        var cancelled = false
        bridge.onBatch = { cancelled = true }
        val manager = XrayCoreManager(bridge)
        val error = runCatching {
            manager.measureDelaySummaryBatch(listOf(sampleProfile()), System.getProperty("java.io.tmpdir") ?: ".",
                checkActive = { if (cancelled) throw CancellationException("Start VPN superseded confirmation") })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, bridge.batchRequests.size)
        assertTrue(bridge.pingRequests.isEmpty())
        assertTrue(bridge.batchConfigs.all { !java.io.File(it.configPath).exists() })
        assertTrue(manager.measureDelaySummaryBatch(listOf(sampleProfile()),
            System.getProperty("java.io.tmpdir") ?: ".").single().success)
    }

    @Test
    fun confirmationRejectsNetworkChangesBeforePublishingOrFallingBack() {
        val bridge = FakeXrayBridge(available = true).apply {
            invokeAvailable = true
            batchResult = XrayPingBatchResult(true, listOf(XrayPingResult(true, 80L, null)), null)
        }
        var obsolete = false
        bridge.onBatch = { obsolete = true }
        val error = runCatching {
            XrayCoreManager(bridge).measureDelaySummaryBatch(listOf(sampleProfile()),
                System.getProperty("java.io.tmpdir") ?: ".",
                checkActive = { if (obsolete) throw java.io.IOException("Physical network changed") })
        }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertEquals(1, bridge.batchRequests.size)
        assertTrue(bridge.pingRequests.isEmpty())
        assertTrue(bridge.batchConfigs.all { !java.io.File(it.configPath).exists() })
    }

    @Test
    fun confirmationRejectsOversizedWorkAndDoesNotProbeAnEmptyList() {
        val bridge = FakeXrayBridge(available = true).apply { invokeAvailable = true }
        val manager = XrayCoreManager(bridge)
        assertTrue(manager.measureDelaySummaryBatch(emptyList(), ".").isEmpty())
        assertTrue(runCatching { manager.measureDelaySummaryBatch(List(6) { sampleProfile() }, ".") }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(bridge.batchRequests.isEmpty())
        assertTrue(bridge.pingRequests.isEmpty())
    }

    @Test
    fun measureDelayQuickBatch_usesLibXrayBatchAndCleansTempFiles() {
        val bridge = FakeXrayBridge(available = true).apply {
            invokeAvailable = true
            batchResult = XrayPingBatchResult(
                success = true,
                results = listOf(
                    XrayPingResult(success = true, delayMs = 144, error = null),
                    XrayPingResult(success = true, delayMs = 155, error = null)
                ),
                error = null
            )
        }
        val manager = XrayCoreManager(bridge)

        val summaries = manager.measureDelayQuickBatch(
            profiles = listOf(sampleProfile(), sampleProfile().copy(name = "second")),
            datDir = System.getProperty("java.io.tmpdir") ?: ".",
            timeoutSeconds = 3,
            testUrl = "https://one.example"
        )

        assertTrue(summaries != null)
        assertEquals(2, summaries!!.size)
        assertEquals(144L, summaries[0].medianMs)
        assertEquals(155L, summaries[1].medianMs)
        assertEquals(2, bridge.batchConfigs.size)
        assertEquals(1, bridge.batchRequests.size)
        assertTrue(bridge.batchConfigs.all { !java.io.File(it.configPath).exists() })
    }

    @Test
    fun measureDelayQuickBatch_retriesOnlyFailedProfilesOnAnIndependentEndpoint() {
        val bridge = FakeXrayBridge(available = true).apply {
            invokeAvailable = true
            batchResults += XrayPingBatchResult(
                success = true,
                results = listOf(
                    XrayPingResult(success = true, delayMs = 144, error = null),
                    XrayPingResult(success = false, delayMs = null, error = "first endpoint timed out")
                ),
                error = null
            )
            batchResults += XrayPingBatchResult(
                success = true,
                results = listOf(
                    XrayPingResult(success = true, delayMs = 222, error = null)
                ),
                error = null
            )
        }
        val manager = XrayCoreManager(bridge)

        val summaries = manager.measureDelayQuickBatch(
            profiles = listOf(sampleProfile(), sampleProfile().copy(name = "second")),
            datDir = System.getProperty("java.io.tmpdir") ?: ".",
            timeoutSeconds = 3,
            testUrl = "https://one.example",
            retryTestUrl = "https://two.example"
        )

        assertTrue(summaries != null)
        assertEquals(2, summaries!!.size)
        assertEquals(144L, summaries[0].medianMs)
        assertEquals(1, summaries[0].totalSamples)
        assertEquals(222L, summaries[1].medianMs)
        assertEquals(1, summaries[1].successfulSamples)
        assertEquals(2, summaries[1].totalSamples)
        assertEquals(2, bridge.batchRequests.size)
        assertEquals("https://one.example", bridge.batchRequests[0].second)
        assertEquals(2, bridge.batchRequests[0].first.size)
        assertEquals("https://two.example", bridge.batchRequests[1].second)
        assertEquals(1, bridge.batchRequests[1].first.size)
        assertTrue(
            bridge.batchRequests
                .flatMap { it.first }
                .all { !java.io.File(it.configPath).exists() }
        )
    }

    @Test
    fun measureDelayQuickBatch_usesThirdEndpointWithoutSequentialPerProfileFallback() {
        val bridge = FakeXrayBridge(available = true).apply {
            invokeAvailable = true
            batchResults += XrayPingBatchResult(
                success = true,
                results = listOf(XrayPingResult(false, null, "first endpoint timed out")),
                error = null
            )
            batchResults += XrayPingBatchResult(
                success = true,
                results = listOf(XrayPingResult(false, null, "second endpoint timed out")),
                error = null
            )
            batchResults += XrayPingBatchResult(
                success = true,
                results = listOf(XrayPingResult(true, 188, null)),
                error = null
            )
        }

        val summaries = XrayCoreManager(bridge).measureDelayQuickBatch(
            profiles = listOf(sampleProfile()),
            datDir = System.getProperty("java.io.tmpdir") ?: ".",
            timeoutSeconds = 3,
            testUrl = "https://one.example",
            retryTestUrl = "https://two.example",
            finalTestUrl = "https://three.example"
        )

        assertTrue(summaries != null)
        assertEquals(188L, summaries!!.single().medianMs)
        assertEquals(3, summaries.single().totalSamples)
        assertEquals(
            listOf("https://one.example", "https://two.example", "https://three.example"),
            bridge.batchRequests.map { it.second }
        )
    }

    @Test
    fun measureDelayQuickBatch_yieldsBeforeRetriesWhenVpnStartIsWaiting() = runTest {
        val bridge = FakeXrayBridge(available = true).apply {
            invokeAvailable = true
            batchResult = XrayPingBatchResult(true, listOf(XrayPingResult(false, null, "timeout")), null)
        }
        val manager = XrayCoreManager(bridge)
        val runtime = XrayCoreManager::class.java.getDeclaredMethod("getNativeRuntime")
            .apply { isAccessible = true }.invoke(manager) as NativeRuntimeCoordinator
        var waiting: kotlinx.coroutines.Deferred<NativeRuntimeCoordinator.Lease>? = null
        bridge.onBatch = {
            if (waiting == null) {
                waiting = async(start = CoroutineStart.UNDISPATCHED) { runtime.acquireVpn() }
            }
        }
        try {
            val failure = runCatching {
                manager.measureDelayQuickBatch(
                    listOf(sampleProfile()), System.getProperty("java.io.tmpdir") ?: ".",
                    testUrl = "https://one.example", retryTestUrl = "https://two.example",
                    finalTestUrl = "https://three.example"
                )
            }.exceptionOrNull()
            assertTrue("A pending VPN startup must supersede optional native retries", failure is CancellationException)
            assertEquals(1, bridge.batchRequests.size)
            assertTrue(bridge.batchConfigs.all { !java.io.File(it.configPath).exists() })
        } finally {
            waiting?.await()?.close()
        }
        assertTrue("Cancelled probing leaked the native runtime lease", runtime.tryAcquireProbe()?.also { it.close() } != null)
    }

    @Test
    fun singleDelayPortRetryYieldsToAWaitingVpnAndRemovesItsConfig() = runTest {
        val bridge = FakeXrayBridge(available = true).apply {
            pingResults += XrayPingResult(false, null, "address already in use")
            pingResults += XrayPingResult(true, 100L, null)
        }
        val manager = XrayCoreManager(bridge)
        val runtime = XrayCoreManager::class.java.getDeclaredMethod("getNativeRuntime")
            .apply { isAccessible = true }.invoke(manager) as NativeRuntimeCoordinator
        var waiting: kotlinx.coroutines.Deferred<NativeRuntimeCoordinator.Lease>? = null
        bridge.onPing = {
            if (waiting == null) waiting = async(start = CoroutineStart.UNDISPATCHED) { runtime.acquireVpn() }
        }
        try {
            val failure = runCatching {
                manager.measureDelay(sampleProfile(), System.getProperty("java.io.tmpdir") ?: ".")
            }.exceptionOrNull()
            assertTrue("Single-profile retries must yield the native lease to VPN startup", failure is CancellationException)
            assertEquals(1, bridge.pingRequests.size)
            val request = JSONObject(String(android.util.Base64.decode(bridge.pingRequest, android.util.Base64.DEFAULT)))
            assertFalse(java.io.File(request.getString("configPath")).exists())
        } finally {
            waiting?.await()?.close()
        }
        assertTrue(runtime.tryAcquireProbe()?.also { it.close() } != null)
    }

    @Test
    fun detailedDelayDoesNotRetryAPortOnAnObsoleteNetwork() {
        val bridge = FakeXrayBridge(available = true).apply {
            pingResults += XrayPingResult(false, null, "address already in use")
            pingResults += XrayPingResult(true, 100L, null)
        }
        var networkChanged = false
        bridge.onPing = { networkChanged = true }
        val manager = XrayCoreManager(bridge)
        val failure = runCatching {
            manager.measureDelaySummary(
                sampleProfile(), System.getProperty("java.io.tmpdir") ?: ".",
                checkActive = { if (networkChanged) throw java.io.IOException("Fixture handoff") }
            )
        }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertEquals("A completed native call must be followed by a context check before retry", 1, bridge.pingRequests.size)
        val request = JSONObject(String(android.util.Base64.decode(bridge.pingRequest, android.util.Base64.DEFAULT)))
        assertFalse(java.io.File(request.getString("configPath")).exists())
    }

    @Test
    fun detailedDelayStopsBetweenNativeSamplesWhenItsMeasurementContextChanges() {
        val bridge = FakeXrayBridge(available = true).apply {
            pingResult = XrayPingResult(true, 100L, null)
        }
        val manager = XrayCoreManager(bridge)
        var networkChanged = false
        bridge.onPing = { networkChanged = true }
        val failure = runCatching {
            manager.measureDelaySummary(
                sampleProfile(), System.getProperty("java.io.tmpdir") ?: ".", sampleCount = 3,
                checkActive = {
                    if (networkChanged) throw java.io.IOException("Physical network changed")
                }
            )
        }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertEquals(1, bridge.pingRequests.size)
        val request = JSONObject(String(android.util.Base64.decode(bridge.pingRequest, android.util.Base64.DEFAULT)))
        assertFalse(java.io.File(request.getString("configPath")).exists())
        assertTrue(manager.measureDelayQuick(sampleProfile(), System.getProperty("java.io.tmpdir") ?: ".").success)
    }

    @Test
    fun measureDelayQuickBatch_cancellationAfterNativeCallSkipsEveryRetryAndCleansFiles() {
        val bridge = FakeXrayBridge(available = true).apply {
            invokeAvailable = true
            batchResult = XrayPingBatchResult(true, listOf(XrayPingResult(false, null, "timeout")), null)
        }
        var cancelled = false
        bridge.onBatch = { cancelled = true }
        val manager = XrayCoreManager(bridge)
        val failure = runCatching {
            manager.measureDelayQuickBatch(
                listOf(sampleProfile()), System.getProperty("java.io.tmpdir") ?: ".",
                checkActive = { if (cancelled) throw CancellationException("Scan cancelled for Start VPN") }
            )
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, bridge.batchRequests.size)
        assertTrue(bridge.batchConfigs.all { !java.io.File(it.configPath).exists() })
        assertTrue(manager.measureDelayQuickBatch(
            listOf(sampleProfile()), System.getProperty("java.io.tmpdir") ?: ".",
            retryTestUrl = null, finalTestUrl = null
        ) != null)
    }

    @Test
    fun measureDelayQuickBatch_alreadyCancelledNeverInvokesNativeCode() {
        val bridge = FakeXrayBridge(available = true).apply { invokeAvailable = true }
        val failure = runCatching {
            XrayCoreManager(bridge).measureDelayQuickBatch(
                listOf(sampleProfile()), System.getProperty("java.io.tmpdir") ?: ".",
                checkActive = { throw CancellationException("Scan already cancelled") }
            )
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(bridge.batchRequests.isEmpty())
    }

    @Test
    fun measureRoutePerformance_usesTemporaryProxyAndAlwaysStopsIt() = runTest {
        val bridge = FakeXrayBridge(available = true)
        val performanceProbe = FakeRoutePerformanceProbe(
            RoutePerformanceSummary(true, downloadBps = 2_000_000L, uploadBps = 500_000L)
        )
        val manager = XrayCoreManager(
            bridge = bridge,
            runningRouteProbe = FakeRunningRouteProbe(success = true),
            portAllocator = { 19090 },
            localSocksListenerProbe = LocalSocksListenerProbe { true },
            routePerformanceProbe = performanceProbe
        )

        val result = manager.measureRoutePerformance(
            profile = sampleProfile(),
            datDir = System.getProperty("java.io.tmpdir") ?: "."
        )

        assertTrue(result.success)
        assertEquals(2_000_000L, result.downloadBps)
        assertEquals(19090, performanceProbe.lastPort)
        assertTrue(bridge.configJson.contains("\"tag\": \"proxy\""))
        assertTrue(bridge.stopCalled)
    }

    @Test(expected = CancellationException::class)
    fun measureDelay_propagatesCancellationFromLibXray() {
        val bridge = FakeXrayBridge(available = true).apply {
            pingFailure = CancellationException("cancel delay scan")
        }

        XrayCoreManager(bridge).measureDelay(
            profile = sampleProfile(),
            datDir = System.getProperty("java.io.tmpdir") ?: "."
        )
    }

    @Test
    fun measureRoutePerformance_doesNotRetryOrReportSuccessIfItsRuntimeCannotStop() = runTest {
        val bridge = FakeXrayBridge(available = true).apply { stopLeavesCoreRunning = true }
        var allocatedPorts = 0
        val manager = XrayCoreManager(
            bridge = bridge,
            runningRouteProbe = FakeRunningRouteProbe(success = true),
            portAllocator = { allocatedPorts++; 19090 },
            localSocksListenerProbe = LocalSocksListenerProbe { true },
            stopWaitTimeoutMs = 50L,
            routePerformanceProbe = FakeRoutePerformanceProbe(
                RoutePerformanceSummary(false, error = "address already in use")
            )
        )
        val result = manager.measureRoutePerformance(sampleProfile(), "/tmp")
        assertFalse(result.success)
        assertTrue(result.error?.contains("has not stopped") == true)
        assertEquals(1, allocatedPorts)
        assertEquals(TunnelCoreState.Error, manager.state.value)
        val another = manager.measureRoutePerformance(sampleProfile(), "/tmp")
        assertFalse(another.success)
        assertEquals(1, allocatedPorts)
    }

    @Test(expected = CancellationException::class)
    fun measureDelayQuickBatch_propagatesCancellationFromLibXray() {
        val bridge = FakeXrayBridge(available = true).apply {
            invokeAvailable = true
            batchFailure = CancellationException("cancel batch scan")
        }

        XrayCoreManager(bridge).measureDelayQuickBatch(
            profiles = listOf(sampleProfile()),
            datDir = System.getProperty("java.io.tmpdir") ?: "."
        )
    }

    private fun sampleProfile() = G2RayProfile(
        rawUri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2#test",
        uuid = "00000000-0000-0000-0000-000000000000",
        address = "example.com",
        port = 443,
        security = "tls",
        sni = "example.com",
        transport = Transport.XHTTP,
        hostHeader = "example.com",
        alpn = listOf("h2"),
        path = "/",
        fingerprint = "chrome",
        mode = "packet-up",
        allowInsecure = false,
        name = "test"
    )

    private fun readyCore(
        bridge: XrayBridge,
        routeProbe: RunningRouteProbe = FakeRunningRouteProbe(success = true)
    ): XrayCoreManager {
        return XrayCoreManager(
            bridge = bridge,
            runningRouteProbe = routeProbe,
            portAllocator = { 19090 },
            localSocksListenerProbe = LocalSocksListenerProbe { true }
        )
    }

    private class FakeXrayBridge(private val available: Boolean) : XrayBridge {
        var tunFd: Int? = null
        var datDir: String? = null
        var configJson: String = ""
        var runCalled = false
        var stopCalled = false
        var pingRequest = ""
        var pingRequests = mutableListOf<String>()
        var pingConfigJson = ""
        var pingResult = XrayPingResult(success = true, delayMs = 123, error = null)
        var pingResults = mutableListOf<XrayPingResult>()
        var pingFailure: RuntimeException? = null
        var onPing: (() -> Unit)? = null
        var invokeAvailable = false
        var batchConfigs = emptyList<XrayPingBatchConfig>()
        var batchResult = XrayPingBatchResult(false, emptyList(), "batch unavailable")
        var batchResults = mutableListOf<XrayPingBatchResult>()
        var batchRequests = mutableListOf<Pair<List<XrayPingBatchConfig>, String>>()
        var batchFailure: RuntimeException? = null
        var onBatch: (() -> Unit)? = null
        var runResults = mutableListOf<XrayCallResult>()
        var nativeRunning = true
        var stopLeavesCoreRunning = false
        var stateQueryFailures = 0
        var dialerProtected = false
        var listenerProtected = false

        override fun isAvailable(): Boolean = available

        override fun capabilities(): XrayRuntimeCapabilities = XrayRuntimeCapabilities(
            classFound = available,
            registerDialerControllerFound = available,
            registerListenerControllerFound = available,
            setTunFdFound = available,
            newRunRequestFound = available,
            runFromJsonFound = available,
            stopFound = available,
            getStateFound = available,
            pingFound = available,
            invokeFound = invokeAvailable
        )

        override fun registerDialerController(protectFd: (Int) -> Boolean) {
            dialerProtected = protectFd(7)
        }

        override fun registerListenerController(protectFd: (Int) -> Boolean) {
            listenerProtected = protectFd(7)
        }

        override fun setTunFd(fd: Int) {
            tunFd = fd
        }

        override fun newRunFromJsonRequest(datDir: String, configJson: String): String {
            this.datDir = datDir
            this.configJson = configJson
            return "request"
        }

        override fun runFromJson(base64Request: String): XrayCallResult {
            runCalled = true
            val result = if (runResults.isNotEmpty()) {
                runResults.removeAt(0)
            } else {
                XrayCallResult(success = true, error = null)
            }
            nativeRunning = result.success
            if (result.success) {
                stopCalled = false
            }
            return result
        }

        override fun ping(base64Request: String): XrayPingResult {
            pingFailure?.let { throw it }
            pingRequest = base64Request
            pingRequests += base64Request
            val requestJson = String(android.util.Base64.decode(base64Request, android.util.Base64.DEFAULT))
            val request = JSONObject(requestJson)
            pingConfigJson = java.io.File(request.getString("configPath")).readText()
            onPing?.invoke()
            return if (pingResults.isNotEmpty()) {
                pingResults.removeAt(0)
            } else {
                pingResult
            }
        }

        override fun pingBatch(
            configs: List<XrayPingBatchConfig>,
            timeoutSeconds: Int,
            testUrl: String
        ): XrayPingBatchResult {
            batchFailure?.let { throw it }
            batchConfigs = configs
            batchRequests += configs to testUrl
            onBatch?.invoke()
            return if (batchResults.isNotEmpty()) {
                batchResults.removeAt(0)
            } else {
                batchResult
            }
        }

        override fun stop(): XrayCallResult {
            stopCalled = true
            if (!stopLeavesCoreRunning) {
                nativeRunning = false
            }
            return XrayCallResult(success = true, error = null)
        }

        override fun getState(): Boolean {
            if (stateQueryFailures > 0) {
                stateQueryFailures--
                throw IllegalStateException("temporary state query failure")
            }
            return runCalled && nativeRunning && (!stopCalled || stopLeavesCoreRunning)
        }
    }

    private class FakeRunningRouteProbe(
        private val success: Boolean,
        private val error: String? = null
    ) : RunningRouteProbe {
        var lastPort: Int? = null
        var resetCount: Int = 0

        override suspend fun probe(socksPort: Int): RunningRouteResult {
            lastPort = socksPort
            return RunningRouteResult(success = success, error = error)
        }

        override fun reset() {
            resetCount++
        }
    }

    private class FakeRoutePerformanceProbe(
        private val result: RoutePerformanceSummary
    ) : RoutePerformanceProbe {
        var lastPort: Int? = null

        override suspend fun probe(socksPort: Int): RoutePerformanceSummary {
            lastPort = socksPort
            return result
        }
    }
}
