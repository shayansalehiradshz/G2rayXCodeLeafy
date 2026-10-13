package com.g2ray.client.vpn

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeRuntimeCoordinatorTest {
    @Test
    fun vpnStartWaitsForActiveProbeAndBlocksEveryFurtherProbeUntilStopped() = runTest {
        val runtime = NativeRuntimeCoordinator()
        val probe = runtime.tryAcquireProbe()!!
        val waiting = async { runtime.acquireVpn() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertTrue(runtime.hasWaitingVpnStart())
        assertNull(runtime.tryAcquireProbe())
        probe.close()
        val vpn = waiting.await()
        assertFalse(runtime.hasWaitingVpnStart())
        assertNull(runtime.tryAcquireProbe())
        vpn.close()
        vpn.close()
        assertNotNull(runtime.tryAcquireProbe()?.also { it.close() })
    }

    @Test
    fun cancelledVpnWaitDoesNotLeakOwnershipOrPriority() = runTest {
        val runtime = NativeRuntimeCoordinator()
        val probe = runtime.tryAcquireProbe()!!
        val waiting = async { runtime.acquireVpn() }
        runCurrent()
        waiting.cancel()
        runCurrent()
        assertFalse(runtime.hasWaitingVpnStart())
        probe.close()
        assertNotNull(runtime.tryAcquireProbe()?.also { it.close() })
    }

    @Test
    fun persistentReconnectBackoffIsFiniteAndCapped() {
        assertEquals(5_000L, G2RayVpnService.reconnectBackoffMs(0))
        assertEquals(10_000L, G2RayVpnService.reconnectBackoffMs(1))
        assertEquals(60_000L, G2RayVpnService.reconnectBackoffMs(100))
        assertEquals(5_000L, G2RayVpnService.reconnectBackoffMs(-1))
    }
}
