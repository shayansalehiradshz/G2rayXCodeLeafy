package com.g2ray.client.vpn

import android.net.Network
import android.os.Build
import android.os.ParcelFileDescriptor
import com.g2ray.client.model.AppConnectionState
import com.g2ray.client.model.RouteState
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.VpnStateRepository
import com.g2ray.client.parser.VlessUriParser
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.N, Build.VERSION_CODES.S])
class G2RayVpnServiceReverificationTest {
    private lateinit var originalState: AppConnectionState

    @Before
    fun setUp() {
        originalState = VpnStateRepository.state.value
        Dispatchers.setMain(StandardTestDispatcher())
        VpnStateRepository.state.value = AppConnectionState(
            RouteState.Settling, TunnelState.Starting, "Fixture waiting for verification",
            routeReady = false, tunnelVerified = false
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        VpnStateRepository.state.value = originalState
    }

    @Test
    fun promptFailureFromAnObsoleteNetworkDoesNotConfirmOrRestartTheTunnel() = runTest {
        val probe = CountingProbe()
        withConnectedService(probe) { service, bridge ->
            val before = VpnStateRepository.state.value
            val generation = field(service, "underlyingNetworkGeneration") as AtomicLong
            probe.onProbe = {
                generation.incrementAndGet()
                RunningRouteResult(false, "Fixture old-network failure")
            }

            assertTrue(requestReverification(service))
            runCurrent()
            advanceTimeBy(1_500L)
            runCurrent()

            assertEquals("An obsolete failure must not start a confirmation probe", 1, probe.calls)
            assertEquals(0, bridge.stops)
            assertFalse(field(service, "routeRecoveryInProgress") as Boolean)
            assertEquals(before, VpnStateRepository.state.value)
            assertFalse((field(service, "lifecycleOperationMutex") as Mutex).isLocked)
        }
    }

    @Test
    fun promptSuccessFromAnObsoleteNetworkCannotMarkTheNewNetworkVerified() = runTest {
        val probe = CountingProbe()
        withConnectedService(probe) { service, bridge ->
            val before = VpnStateRepository.state.value
            val generation = field(service, "underlyingNetworkGeneration") as AtomicLong
            probe.onProbe = {
                generation.incrementAndGet()
                RunningRouteResult(true)
            }

            assertTrue(requestReverification(service))
            runCurrent()

            assertEquals(1, probe.calls)
            assertEquals(0, bridge.stops)
            assertEquals(before, VpnStateRepository.state.value)
        }
    }

    @Test
    fun physicalHandoffCancelsAnOutstandingPromptProbeAndReleasesItsMutex() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val probe = CountingProbe().apply {
            onProbe = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        withConnectedService(probe) { service, bridge ->
            assertTrue(requestReverification(service))
            val promptJob = field(service, "routeReverifyJob") as Job
            runCurrent()
            assertTrue(entered.isCompleted)
            assertTrue((field(service, "lifecycleOperationMutex") as Mutex).isLocked)

            applyNetwork(service, ShadowNetwork.newInstance(101))
            runCurrent()

            assertTrue("The handoff must not wait for an obsolete probe deadline", promptJob.isCancelled)
            assertTrue(cancelled.isCompleted)
            assertFalse((field(service, "lifecycleOperationMutex") as Mutex).isLocked)
            assertEquals(0, bridge.stops)

            probe.onProbe = { RunningRouteResult(true) }
            advanceTimeBy(2_500L)
            runCurrent()
            assertEquals("The replacement network must still get a fresh check", 2, probe.calls)
            assertEquals(0, bridge.stops)
        }
    }

    @Test
    fun lossOfAllPhysicalNetworksCancelsThePromptWithoutStartingRecovery() = runTest {
        val probe = CountingProbe().apply { onProbe = { awaitCancellation() } }
        withConnectedService(probe) { service, bridge ->
            assertTrue(requestReverification(service))
            val job = field(service, "routeReverifyJob") as Job
            runCurrent()

            applyNetwork(service, null)
            runCurrent()

            assertTrue(job.isCancelled)
            assertTrue(field(service, "waitingForUnderlyingNetwork") as Boolean)
            assertFalse((field(service, "lifecycleOperationMutex") as Mutex).isLocked)
            assertFalse(field(service, "routeRecoveryInProgress") as Boolean)
            assertEquals(0, bridge.stops)
        }
    }

    @Test
    fun networkChangedDuringConfirmationDelayDoesNotLaunchAnotherProbe() = runTest {
        val probe = CountingProbe().apply { onProbe = { RunningRouteResult(false, "Fixture failure") } }
        withConnectedService(probe) { service, bridge ->
            val before = VpnStateRepository.state.value
            assertTrue(requestReverification(service))
            runCurrent()
            assertEquals(1, probe.calls)

            (field(service, "underlyingNetworkGeneration") as AtomicLong).incrementAndGet()
            advanceTimeBy(1_500L)
            runCurrent()

            assertEquals(1, probe.calls)
            assertEquals(0, bridge.stops)
            assertEquals(before, VpnStateRepository.state.value)
        }
    }

    @Test
    fun networkChangedWhilePublicationIsQueuedCannotBeMarkedVerified() = runTest {
        val probe = CountingProbe()
        withConnectedService(probe) { service, bridge ->
            val before = VpnStateRepository.state.value
            val generation = field(service, "underlyingNetworkGeneration") as AtomicLong
            probe.onProbe = {
                backgroundScope.launch(Dispatchers.Main) { generation.incrementAndGet() }
                RunningRouteResult(true)
            }

            assertTrue(requestReverification(service))
            runCurrent()

            assertEquals(1, probe.calls)
            assertEquals(0, bridge.stops)
            assertEquals(before, VpnStateRepository.state.value)
        }
    }

    @Test
    fun stableNetworkSuccessStillVerifiesTheTunnelWithoutRestarting() = runTest {
        val probe = CountingProbe()
        withConnectedService(probe) { service, bridge ->
            assertTrue(requestReverification(service))
            runCurrent()

            assertEquals(1, probe.calls)
            assertTrue(VpnStateRepository.state.value.tunnelVerified)
            assertEquals(TunnelState.Connected, VpnStateRepository.state.value.tunnelState)
            assertEquals(0, bridge.stops)
        }
    }

    @Test
    fun stableNetworkFailureStillConfirmsAndSchedulesRecovery() = runTest {
        val probe = CountingProbe().apply { onProbe = { RunningRouteResult(false, "Fixture route unavailable") } }
        withConnectedService(probe) { service, bridge ->
            assertTrue(requestReverification(service))
            runCurrent()
            assertEquals(1, probe.calls)

            advanceTimeBy(1_500L)
            runCurrent()

            assertEquals(2, probe.calls)
            assertTrue(field(service, "routeRecoveryInProgress") as Boolean)
            assertEquals(1, bridge.stops)
            assertFalse(VpnStateRepository.state.value.tunnelVerified)
        }
    }

    @Test
    fun noPhysicalNetworkRejectsPromptVerificationWithoutTakingTheMutex() = runTest {
        val probe = CountingProbe()
        withConnectedService(probe) { service, bridge ->
            setField(service, "waitingForUnderlyingNetwork", true)

            assertFalse(requestReverification(service))
            runCurrent()

            assertEquals(0, probe.calls)
            assertEquals(0, bridge.stops)
            assertFalse((field(service, "lifecycleOperationMutex") as Mutex).isLocked)
        }
    }

    @Test
    fun handoffFailureFromAReplacedNetworkDoesNotRestartTheNewRoute() = runTest {
        assertReplacedHandoffDoesNotRecover(oldProbeSuccess = false)
    }

    @Test
    fun handoffSuccessFromAReplacedNetworkDoesNotRestartTheNewRoute() = runTest {
        assertReplacedHandoffDoesNotRecover(oldProbeSuccess = true)
    }

    @Test
    fun networkLossDuringHandoffVerificationDoesNotScheduleStaleRecovery() = runTest {
        val probe = CountingProbe()
        withConnectedService(probe) { service, bridge ->
            probe.onProbe = {
                applyNetwork(service, null)
                RunningRouteResult(true)
            }
            applyNetwork(service, ShadowNetwork.newInstance(101))
            val handoff = field(service, "networkHandoffJob") as Job
            runCurrent()
            advanceTimeBy(2_500L)
            runCurrent()

            assertTrue(handoff.isCancelled)
            assertEquals(1, probe.calls)
            assertTrue(field(service, "waitingForUnderlyingNetwork") as Boolean)
            assertFalse("An obsolete handoff must not own recovery", field(service, "routeRecoveryInProgress") as Boolean)
            assertEquals(0, bridge.stops)
            assertFalse((field(service, "lifecycleOperationMutex") as Mutex).isLocked)
            assertFalse(VpnStateRepository.state.value.tunnelVerified)
        }
    }

    @Test
    fun stableHandoffSuccessAfterNetworkReturnVerifiesWithoutRestarting() = runTest {
        val probe = CountingProbe()
        withConnectedService(probe) { service, bridge ->
            applyNetwork(service, null)
            runCurrent()
            applyNetwork(service, ShadowNetwork.newInstance(101))
            runCurrent()
            advanceTimeBy(2_500L)
            runCurrent()

            assertEquals(1, probe.calls)
            assertTrue(VpnStateRepository.state.value.tunnelVerified)
            assertEquals(TunnelState.Connected, VpnStateRepository.state.value.tunnelState)
            assertFalse(field(service, "routeRecoveryInProgress") as Boolean)
            assertEquals(0, bridge.stops)
        }
    }

    @Test
    fun stableHandoffFailureStillConfirmsAndSchedulesRecovery() = runTest {
        val probe = CountingProbe().apply { onProbe = { RunningRouteResult(false, "Fixture route failed") } }
        withConnectedService(probe) { service, bridge ->
            applyNetwork(service, ShadowNetwork.newInstance(101))
            runCurrent()
            advanceTimeBy(2_500L)
            runCurrent()
            assertEquals(1, probe.calls)
            assertFalse(field(service, "routeRecoveryInProgress") as Boolean)

            advanceTimeBy(1_500L)
            runCurrent()
            assertEquals(2, probe.calls)
            assertEquals(1, bridge.stops)
            assertTrue(field(service, "routeRecoveryInProgress") as Boolean)
            assertFalse(VpnStateRepository.state.value.tunnelVerified)
        }
    }

    @Test
    fun handoffReplacedWhileWaitingForLifecycleMutexDoesNotProbeTheOldNetwork() = runTest {
        val probe = CountingProbe()
        withConnectedService(probe) { service, bridge ->
            val mutex = field(service, "lifecycleOperationMutex") as Mutex
            mutex.lock()
            try {
                applyNetwork(service, ShadowNetwork.newInstance(101))
                val oldHandoff = field(service, "networkHandoffJob") as Job
                runCurrent()
                advanceTimeBy(2_500L)
                runCurrent()
                applyNetwork(service, ShadowNetwork.newInstance(102))
                runCurrent()
                assertTrue(oldHandoff.isCancelled)
                assertEquals(0, probe.calls)
            } finally {
                mutex.unlock()
            }

            advanceTimeBy(2_500L)
            runCurrent()
            assertEquals(1, probe.calls)
            assertEquals(0, bridge.stops)
            assertFalse((field(service, "lifecycleOperationMutex") as Mutex).isLocked)
        }
    }

    @Test
    fun delayedNetworkLossCannotUnverifyAReverifiedReplacementNetwork() = runTest {
        val probe = CountingProbe()
        withConnectedService(probe) { service, bridge ->
            val dispatcher = HoldFirstDispatch(StandardTestDispatcher(testScheduler))
            setField(service, "scope", CoroutineScope(backgroundScope.coroutineContext + dispatcher))
            applyNetwork(service, null)
            assertTrue(dispatcher.hasHeldTask)
            applyNetwork(service, ShadowNetwork.newInstance(101))
            runCurrent()
            advanceTimeBy(2_500L)
            runCurrent()

            assertEquals(1, probe.calls)
            val ready = VpnStateRepository.state.value
            assertTrue(ready.tunnelVerified)
            dispatcher.releaseHeldTask()
            runCurrent()

            assertEquals("An old loss must not overwrite the current verified route", ready, VpnStateRepository.state.value)
            assertEquals(0, bridge.stops)
        }
    }

    @Test
    fun delayedNetworkLossCannotOverwriteAnInProgressStop() = runTest {
        val probe = CountingProbe()
        withConnectedService(probe) { service, bridge ->
            val dispatcher = HoldFirstDispatch(StandardTestDispatcher(testScheduler))
            setField(service, "scope", CoroutineScope(backgroundScope.coroutineContext + dispatcher))
            val mutex = field(service, "lifecycleOperationMutex") as Mutex
            mutex.lock()
            try {
                applyNetwork(service, null)
                assertTrue(dispatcher.hasHeldTask)
                G2RayVpnService::class.java.getDeclaredMethod("stopTunnel")
                    .apply { isAccessible = true }.invoke(service)
                val stopping = VpnStateRepository.state.value
                assertEquals(TunnelState.Stopping, stopping.tunnelState)
                dispatcher.releaseHeldTask()
                runCurrent()

                assertEquals("An old loss must not turn Stop back into startup", stopping, VpnStateRepository.state.value)
                assertEquals(0, probe.calls)
                assertEquals(0, bridge.stops)
            } finally {
                mutex.unlock()
            }
        }
    }

    @Test
    fun currentNetworkLossStillMarksTheTunnelUnverified() = runTest {
        val probe = CountingProbe()
        withConnectedService(probe) { service, bridge ->
            assertTrue(requestReverification(service))
            runCurrent()
            assertTrue(VpnStateRepository.state.value.tunnelVerified)
            val dispatcher = HoldFirstDispatch(StandardTestDispatcher(testScheduler))
            setField(service, "scope", CoroutineScope(backgroundScope.coroutineContext + dispatcher))
            applyNetwork(service, null)
            assertTrue(dispatcher.hasHeldTask)
            dispatcher.releaseHeldTask()
            runCurrent()

            assertEquals(TunnelState.Starting, VpnStateRepository.state.value.tunnelState)
            assertFalse(VpnStateRepository.state.value.tunnelVerified)
            assertTrue(field(service, "waitingForUnderlyingNetwork") as Boolean)
            assertEquals(0, bridge.stops)
        }
    }

    private suspend fun TestScope.assertReplacedHandoffDoesNotRecover(oldProbeSuccess: Boolean) {
        val probe = CountingProbe()
        withConnectedService(probe) { service, bridge ->
            probe.onProbe = {
                applyNetwork(service, ShadowNetwork.newInstance(102))
                RunningRouteResult(oldProbeSuccess, "Fixture old-network result")
            }
            applyNetwork(service, ShadowNetwork.newInstance(101))
            val handoff = field(service, "networkHandoffJob") as Job
            runCurrent()
            advanceTimeBy(2_500L)
            runCurrent()

            assertTrue(handoff.isCancelled)
            assertEquals(1, probe.calls)
            assertEquals("A replacement network must not be restarted by an old result", 0, bridge.stops)
            assertFalse("An obsolete handoff must not own recovery", field(service, "routeRecoveryInProgress") as Boolean)
            assertFalse((field(service, "lifecycleOperationMutex") as Mutex).isLocked)
            assertFalse(VpnStateRepository.state.value.tunnelVerified)

            probe.onProbe = { RunningRouteResult(true) }
            advanceTimeBy(2_500L)
            runCurrent()
            assertEquals("The newest network must still receive a fresh check", 2, probe.calls)
            assertEquals(0, bridge.stops)
        }
    }

    private suspend fun TestScope.withConnectedService(
        probe: VpnDataPathProbe, block: suspend (G2RayVpnService, CountingBridge) -> Unit
    ) {
        val service = Robolectric.buildService(G2RayVpnService::class.java).create().get()
        val bridge = CountingBridge()
        val core = XrayCoreManager(bridge, RunningRouteProbe {
            RunningRouteResult(false, "Fixture SOCKS route unavailable")
        })
        @Suppress("UNCHECKED_CAST")
        val state = XrayCoreManager::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }.get(core) as MutableStateFlow<TunnelCoreState>
        state.value = TunnelCoreState.Connected
        val pipe = ParcelFileDescriptor.createPipe()
        setField(service, "scope", backgroundScope)
        setField(service, "core", core)
        setField(service, "fullVpnDataPathProbe", probe)
        setField(service, "vpnInterface", pipe[0])
        setField(service, "underlyingNetwork", ShadowNetwork.newInstance(100))
        setField(service, "activeProfile", VlessUriParser.parse(
            "vless://00000000-0000-4000-8000-000000000001@203.0.113.1:443" +
                "?encryption=none&security=tls&type=ws&sni=fixture.example&host=fixture.example&path=%2Fws"
        ).getOrThrow())
        try {
            block(service, bridge)
        } finally {
            setField(service, "vpnInterface", null)
            service.onDestroy()
            pipe.forEach { it.close() }
        }
    }

    private fun requestReverification(service: G2RayVpnService): Boolean = G2RayVpnService::class.java
            .getDeclaredMethod("requestRouteReverification", RouteReverifyReason::class.java)
            .apply { isAccessible = true }.invoke(service, RouteReverifyReason.DeviceWake) as Boolean

    private fun applyNetwork(service: G2RayVpnService, network: Network?) {
        G2RayVpnService::class.java
            .getDeclaredMethod("applyUnderlyingNetwork", Network::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, network, true)
    }

    private fun field(service: G2RayVpnService, name: String): Any? =
        G2RayVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setField(service: G2RayVpnService, name: String, value: Any?) {
        G2RayVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private class CountingProbe : VpnDataPathProbe {
        var calls = 0
        var onProbe: suspend () -> RunningRouteResult = { RunningRouteResult(true) }
        override suspend fun probe(): RunningRouteResult {
            calls++
            return onProbe()
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private class HoldFirstDispatch(private val delegate: TestDispatcher) : CoroutineDispatcher(), Delay by delegate {
        private var heldTask: Pair<CoroutineContext, Runnable>? = null
        private var firstDispatch = true
        val hasHeldTask: Boolean get() = heldTask != null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (firstDispatch) {
                firstDispatch = false
                heldTask = context to block
            } else {
                delegate.dispatch(context, block)
            }
        }

        fun releaseHeldTask() {
            val (context, block) = checkNotNull(heldTask)
            heldTask = null
            delegate.dispatch(context, block)
        }
    }

    private class CountingBridge : XrayBridge {
        var stops = 0
        override fun isAvailable() = true
        override fun getState() = true
        override fun capabilities(): XrayRuntimeCapabilities = error("Unexpected core startup")
        override fun registerDialerController(protectFd: (Int) -> Boolean) = Unit
        override fun registerListenerController(protectFd: (Int) -> Boolean) = Unit
        override fun setTunFd(fd: Int) = Unit
        override fun newRunFromJsonRequest(datDir: String, configJson: String): String = error("Unexpected startup")
        override fun runFromJson(base64Request: String): XrayCallResult = error("Unexpected startup")
        override fun ping(base64Request: String): XrayPingResult = error("Unexpected ping")
        override fun stop(): XrayCallResult {
            stops++
            return XrayCallResult(true, null)
        }
    }
}
