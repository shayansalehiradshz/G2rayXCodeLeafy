package com.g2ray.client.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnEngineSelectorTest {
    @Test
    fun auto_prefersNativeForTheLowerOverheadDataPath() {
        assertEquals(
            ActiveVpnEngine.XrayNative,
            VpnEngineSelector.resolve(VpnEngineMode.Auto, hevAvailable = true)
        )
    }

    @Test
    fun auto_usesNativeWhenHevIsNotBundled() {
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
    fun autoFallsBackToHevWhenNativePacketEngineDoesNotBecomeHealthy() {
        assertEquals(
            ActiveVpnEngine.Hev,
            VpnEngineSelector.fallbackEngine(
                mode = VpnEngineMode.Auto,
                active = ActiveVpnEngine.XrayNative,
                hevAvailable = true,
                activePacketEngineReady = false
            )
        )
        assertEquals(
            null,
            VpnEngineSelector.fallbackEngine(
                mode = VpnEngineMode.Auto,
                active = ActiveVpnEngine.Hev,
                hevAvailable = true,
                activePacketEngineReady = true
            )
        )
        assertEquals(
            null,
            VpnEngineSelector.fallbackEngine(
                mode = VpnEngineMode.Auto,
                active = ActiveVpnEngine.XrayNative,
                hevAvailable = false,
                activePacketEngineReady = false
            )
        )
        assertEquals(
            null,
            VpnEngineSelector.fallbackEngine(
                mode = VpnEngineMode.Hev,
                active = ActiveVpnEngine.Hev,
                hevAvailable = true,
                activePacketEngineReady = false
            )
        )
        assertEquals(
            null,
            VpnEngineSelector.fallbackEngine(
                mode = VpnEngineMode.Auto,
                active = ActiveVpnEngine.XrayNative,
                hevAvailable = true,
                activePacketEngineReady = true
            )
        )
        assertEquals(
            ActiveVpnEngine.XrayNative,
            VpnEngineSelector.fallbackEngine(
                mode = VpnEngineMode.Auto,
                active = ActiveVpnEngine.Hev,
                hevAvailable = true,
                activePacketEngineReady = false
            )
        )
    }
}
