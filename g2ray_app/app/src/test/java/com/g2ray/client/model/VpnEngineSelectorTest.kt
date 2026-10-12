package com.g2ray.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnEngineSelectorTest {
    @Test
    fun auto_prefersHevWhenTheRuntimeProvidesIt() {
        assertEquals(
            ActiveVpnEngine.Hev,
            VpnEngineSelector.resolve(VpnEngineMode.Auto, hevAvailable = true)
        )
    }

    @Test
    fun auto_fallsBackToNativeWhenHevIsNotBundled() {
        assertEquals(
            ActiveVpnEngine.XrayNative,
            VpnEngineSelector.resolve(VpnEngineMode.Auto, hevAvailable = false)
        )
    }

    @Test
    fun explicitHevDoesNotLeaveTheDeviceWithoutAWorkingEngine() {
        assertEquals(
            ActiveVpnEngine.XrayNative,
            VpnEngineSelector.resolve(VpnEngineMode.Hev, hevAvailable = false)
        )
    }

    @Test
    fun autoFallsBackOnlyWhenTheHevPacketEngineDidNotBecomeHealthy() {
        assertTrue(
            VpnEngineSelector.shouldFallbackToNative(
                mode = VpnEngineMode.Auto,
                active = ActiveVpnEngine.Hev,
                activePacketEngineReady = false
            )
        )
        assertFalse(
            VpnEngineSelector.shouldFallbackToNative(
                mode = VpnEngineMode.Auto,
                active = ActiveVpnEngine.Hev,
                activePacketEngineReady = true
            )
        )
        assertFalse(
            VpnEngineSelector.shouldFallbackToNative(
                mode = VpnEngineMode.Hev,
                active = ActiveVpnEngine.Hev,
                activePacketEngineReady = false
            )
        )
        assertFalse(
            VpnEngineSelector.shouldFallbackToNative(
                mode = VpnEngineMode.Auto,
                active = ActiveVpnEngine.XrayNative,
                activePacketEngineReady = false
            )
        )
    }
}
