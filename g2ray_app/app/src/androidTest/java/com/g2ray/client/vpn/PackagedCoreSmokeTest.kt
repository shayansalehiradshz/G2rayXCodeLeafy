package com.g2ray.client.vpn

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.G2RayProfile
import com.g2ray.client.parser.VlessUriParser
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackagedCoreSmokeTest {
    @Test
    fun packagedNativeCoresLoadOnDeviceAbi() {
        assertTrue("libXray is missing for this device ABI", XrayCoreManager().isAvailable())
        assertTrue("HEV is missing for this device ABI: ${HevTunController.loadError()}", HevTunController.isAvailable())
    }

    @Test
    fun fullDeviceConfigKeepsDnsAndIpv6InsideTheTunnel() {
        val profile = smokeProfile("20.85.77.48", "primary")
        val config = JSONObject(XrayConfigBuilder.buildVpnConfig(profile, 10808, ActiveVpnEngine.XrayNative))

        assertEquals("UseIPv4", config.getJSONObject("dns").getString("queryStrategy"))
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun", tun.getString("protocol"))
        assertEquals("fc00::1/64", tun.getJSONObject("settings").getJSONArray("gateway").getString(1))
    }

    @Test
    fun packagedCoreStartsAndStopsMultiRouteConfig() = runBlocking {
        val manager = XrayCoreManager()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val selected = smokeProfile("20.85.77.48", "primary")
        val fallbacks = listOf(
            smokeProfile("20.120.56.11", "fallback-1"),
            smokeProfile("20.125.70.28", "fallback-2")
        )

        try {
            manager.start(
                profile = selected,
                fallbackProfiles = fallbacks,
                tunFd = 0,
                datDir = context.filesDir.absolutePath,
                protectSocket = { true },
                engine = ActiveVpnEngine.Hev
            )
            assertEquals(TunnelCoreState.Connected, manager.state.value)
            assertTrue(manager.activeSocksPort() != null)
        } finally {
            manager.stop()
        }
    }

    private fun smokeProfile(address: String, name: String): G2RayProfile {
        return VlessUriParser.parse(
            "vless://00000000-0000-0000-0000-000000000000@20.85.77.48:443" +
                "?encryption=none&security=tls&sni=test-443.app.github.dev&fp=chrome&alpn=h2" +
                "&type=xhttp&host=test-443.app.github.dev&path=%2F&mode=packet-up#$name"
        ).getOrThrow().copy(address = address, name = name)
    }
}
