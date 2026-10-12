package com.g2ray.client.vpn

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HevTunControllerTest {
    @Test
    fun buildConfig_keepsTheTunnelPrivateAndTargetsLoopbackSocks() {
        val config = HevTunController.buildConfig(socksPort = 19090, mtu = 1500)

        assertTrue(config.contains("mtu: 1500"))
        assertTrue(config.contains("ipv4: 198.18.0.1"))
        assertTrue(config.contains("ipv6: 'fc00::1'"))
        assertTrue(config.contains("port: 19090"))
        assertTrue(config.contains("address: 127.0.0.1"))
        assertTrue(config.contains("udp: 'udp'"))
        assertTrue(config.contains("tcp-read-write-timeout: 300000"))
    }

    @Test
    fun buildConfig_ipv4OnlyOmitsIpv6FromHev() {
        val config = HevTunController.buildConfig(
            socksPort = 19090,
            mtu = 1500,
            ipv4Only = true
        )

        assertTrue(config.contains("ipv4: 198.18.0.1"))
        assertFalse(config.contains("ipv6:"))
    }
}
