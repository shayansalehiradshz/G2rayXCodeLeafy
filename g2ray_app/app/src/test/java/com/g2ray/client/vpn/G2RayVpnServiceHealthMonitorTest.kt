package com.g2ray.client.vpn

import android.os.Build
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class G2RayVpnServiceHealthMonitorTest {
    @Test
    fun periodicHealthCheckDoesNotProbeWhileLifecycleTransitionOwnsClients() = runTest {
        val probe = CountingProbe()
        val service = connectedService(probe)
        val mutex = field(service, "lifecycleOperationMutex") as Mutex
        mutex.lock()
        try {
            startHealthMonitor(service)
            tick(30_000L)
            assertEquals(0, probe.calls)

            mutex.unlock()
            tick(5_000L)
            assertEquals(1, probe.calls)
            assertFalse(mutex.isLocked)
        } finally {
            if (mutex.isLocked) mutex.unlock()
            service.onDestroy()
        }
    }

    @Test
    fun periodicHealthCheckDiscardsFailureFromAnObsoletePhysicalNetwork() = runTest {
        val probe = CountingProbe()
        val service = connectedService(probe)
        val generation = field(service, "underlyingNetworkGeneration") as AtomicLong
        probe.onProbe = {
            generation.incrementAndGet()
            RunningRouteResult(false, "Full VPN data path: ECONNREFUSED")
        }
        try {
            startHealthMonitor(service)
            tick(30_000L)

            assertEquals(1, probe.calls)
            assertFalse(field(service, "routeRecoveryInProgress") as Boolean)
            assertEquals(0, probe.resets)
            assertFalse((field(service, "lifecycleOperationMutex") as Mutex).isLocked)

            probe.onProbe = { RunningRouteResult(true) }
            tick(30_000L)
            assertEquals(2, probe.calls)
        } finally {
            service.onDestroy()
        }
    }

    @Test
    fun cancellingPeriodicProbeReleasesLifecycleMutexForRecovery() = runTest {
        val entered = CompletableDeferred<Unit>()
        val probe = CountingProbe().apply {
            onProbe = {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val service = connectedService(probe)
        val mutex = field(service, "lifecycleOperationMutex") as Mutex
        try {
            startHealthMonitor(service)
            tick(30_000L)
            assertTrue(entered.isCompleted)
            assertTrue(mutex.isLocked)

            (field(service, "healthJob") as Job).cancel()
            runCurrent()
            assertFalse(mutex.isLocked)
        } finally {
            service.onDestroy()
        }
    }

    private fun TestScope.connectedService(probe: VpnDataPathProbe): G2RayVpnService {
        val service = Robolectric.buildService(G2RayVpnService::class.java).create().get()
        val core = XrayCoreManager(AlwaysRunningBridge(), RunningRouteProbe {
            RunningRouteResult(false, "SOCKS route unavailable")
        })
        @Suppress("UNCHECKED_CAST")
        val state = XrayCoreManager::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }.get(core) as MutableStateFlow<TunnelCoreState>
        state.value = TunnelCoreState.Connected
        setField(service, "scope", backgroundScope)
        setField(service, "core", core)
        setField(service, "fullVpnDataPathProbe", probe)
        return service
    }

    private fun TestScope.startHealthMonitor(service: G2RayVpnService) {
        G2RayVpnService::class.java.getDeclaredMethod("startCoreHealthMonitor")
            .apply { isAccessible = true }.invoke(service)
        runCurrent()
    }

    private fun TestScope.tick(milliseconds: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(milliseconds))
        advanceTimeBy(milliseconds)
        runCurrent()
    }

    private fun field(service: G2RayVpnService, name: String): Any? {
        return G2RayVpnService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(service)
    }

    private fun setField(service: G2RayVpnService, name: String, value: Any) {
        G2RayVpnService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(service, value)
    }

    private class CountingProbe : VpnDataPathProbe {
        var calls = 0
        var resets = 0
        var onProbe: suspend () -> RunningRouteResult = { RunningRouteResult(true) }

        override suspend fun probe(): RunningRouteResult {
            calls++
            return onProbe()
        }

        override suspend fun reset() {
            resets++
        }
    }

    private class AlwaysRunningBridge : XrayBridge {
        override fun isAvailable() = true
        override fun getState() = true
        override fun capabilities(): XrayRuntimeCapabilities = error("Unexpected core startup")
        override fun registerDialerController(protectFd: (Int) -> Boolean) = Unit
        override fun registerListenerController(protectFd: (Int) -> Boolean) = Unit
        override fun setTunFd(fd: Int) = Unit
        override fun newRunFromJsonRequest(datDir: String, configJson: String): String = error("Unexpected startup")
        override fun runFromJson(base64Request: String): XrayCallResult = error("Unexpected startup")
        override fun ping(base64Request: String): XrayPingResult = error("Unexpected ping")
        override fun stop() = XrayCallResult(true, null)
    }
}
