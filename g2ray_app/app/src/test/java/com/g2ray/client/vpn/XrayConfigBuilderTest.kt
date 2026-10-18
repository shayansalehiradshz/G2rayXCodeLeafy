package com.g2ray.client.vpn

import com.g2ray.client.model.G2RayProfile
import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.Transport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XrayConfigBuilderTest {
    @Test
    fun explicitDomainBypassIsScopedAndKeepsDnsProxiedAcrossEngines() {
        for (engine in ActiveVpnEngine.entries) for (fakeDns in listOf(false, true)) for (balanced in listOf(false, true)) {
            val profile = testXhttpProfile().copy(address = "203.0.113.1")
            val root = JSONObject(XrayConfigBuilder.buildVpnConfig(profile, engine = engine, fakeDnsEnabled = fakeDns,
                fallbackProfiles = if (balanced) listOf(profile.copy(address = "203.0.113.2")) else emptyList(),
                routingPolicy = RoutingPolicy(true, listOf("example.ir"))))
            val rules = root.getJSONObject("routing").getJSONArray("rules")
            assertEquals("dns-out", rules.getJSONObject(0).getString("outboundTag"))
            assertFalse(rules.getJSONObject(1).optString("outboundTag") == "bypass")
            val bypass = rules.getJSONObject(2)
            assertEquals("bypass", bypass.getString("outboundTag"))
            assertEquals(listOf("domain:ir", "domain:example.ir"), (0 until bypass.getJSONArray("domain").length()).map { bypass.getJSONArray("domain").getString(it) })
            assertTrue(bypass.has("inboundTag"))
            assertEquals("block", rules.getJSONObject(3).getString("outboundTag"))
            val inbounds = root.getJSONArray("inbounds")
            for (i in 0 until inbounds.length()) {
                val inbound = inbounds.getJSONObject(i)
                if (inbound.getString("tag") == "health-socks") continue
                val sniffing = inbound.getJSONObject("sniffing")
                assertTrue(sniffing.getBoolean("enabled"))
                assertTrue(sniffing.getBoolean("routeOnly"))
                assertFalse(sniffing.getBoolean("metadataOnly"))
                assertTrue(sniffing.getJSONArray("destOverride").toString().contains("tls"))
            }
            System.getProperty("g2ray.configFixtureDir")?.let { directory ->
                java.io.File(directory, "bypass-$engine-fake$fakeDns-balanced$balanced.json").apply {
                    parentFile?.mkdirs(); writeText(root.toString(2))
                }
            }
        }
    }

    @Test
    fun vpnIngressAndDnsHaveNoDirectOutboundAcrossEngineAndRoutingModes() {
        val selected = testXhttpProfile().copy(address = "203.0.113.1")
        for (transport in Transport.entries) for (engine in ActiveVpnEngine.entries) for (fakeDns in listOf(false, true))
            for (ipv4Only in listOf(false, true)) for (balanced in listOf(false, true)) {
                val profile = selected.copy(transport = transport,
                    mode = if (transport == Transport.XHTTP) "packet-up" else null,
                    alpn = if (transport == Transport.XHTTP) listOf("h2") else listOf("http/1.1"))
                val root = JSONObject(XrayConfigBuilder.buildVpnConfig(profile, engine = engine,
                    fakeDnsEnabled = fakeDns, ipv4Only = ipv4Only,
                    fallbackProfiles = if (balanced) listOf(profile.copy(address = "203.0.113.2")) else emptyList()))
                val outbounds = root.getJSONArray("outbounds")
                for (i in 0 until outbounds.length()) {
                    assertTrue(outbounds.getJSONObject(i).getString("protocol") in setOf("vless", "dns", "blackhole"))
                }
                val rules = root.getJSONObject("routing").getJSONArray("rules")
                for (i in 0 until rules.length()) assertFalse(rules.getJSONObject(i).optString("outboundTag") == "direct")
                assertEquals("https://1.1.1.1/dns-query", root.getJSONObject("dns").getJSONArray("servers")
                    .getJSONObject(if (fakeDns) 1 else 0).getString("address"))
                assertEquals("proxy", outbounds.getJSONObject(0).getString("tag").substringBefore('-'))
                // Optional handoff to the real pinned-core config validator;
                // never exports user configuration or performs network IO.
                System.getProperty("g2ray.configFixtureDir")?.let { directory ->
                    val file = java.io.File(directory,
                        "$transport-$engine-fake$fakeDns-v4$ipv4Only-balanced$balanced.json")
                    file.parentFile?.mkdirs()
                    file.writeText(root.toString(2))
                }
            }
    }


    @Test
    fun buildConfigs_wsEarlyData_preservesSupportedPathSyntaxAcrossEngines() {
        val path = "/ws?ed=2560&token=a%2Bb"
        val profile = testXhttpProfile().copy(
            transport = Transport.WS, path = path, mode = null, alpn = listOf("http/1.1")
        )
        val configs = listOf(
            XrayConfigBuilder.buildConfig(profile),
            XrayConfigBuilder.buildDelayTestConfig(profile, socksPort = 19090),
            XrayConfigBuilder.buildVpnConfig(profile, engine = ActiveVpnEngine.Hev),
            XrayConfigBuilder.buildVpnConfig(profile, engine = ActiveVpnEngine.XrayNative)
        )
        configs.forEach { config ->
            val stream = JSONObject(config).getJSONArray("outbounds")
                .getJSONObject(0).getJSONObject("streamSettings")
            val ws = stream.getJSONObject("wsSettings")
            assertEquals(path, ws.getString("path"))
            assertEquals(profile.hostHeader, ws.getJSONObject("headers").getString("Host"))
            assertEquals(profile.sni, stream.getJSONObject("tlsSettings").getString("serverName"))
            assertFalse(stream.getJSONObject("tlsSettings").getBoolean("allowInsecure"))
            assertFalse(ws.has("maxEarlyData"))
            assertFalse(ws.has("earlyDataHeaderName"))
        }
    }

    @Test
    fun buildConfig_xhttpProfile_correctConfig() {
        val profile = G2RayProfile(
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

        val jsonString = XrayConfigBuilder.buildConfig(profile, socksPort = 19090)
        val root = JSONObject(jsonString)

        val policy = root.getJSONObject("policy").getJSONObject("levels").getJSONObject("0")
        assertEquals(512, policy.getInt("bufferSize"))
        assertEquals(900, policy.getInt("connIdle"))
        assertFalse(policy.has("handshake"))

        val outbounds = root.getJSONArray("outbounds")
        val inbounds = root.getJSONArray("inbounds")
        assertEquals(19090, inbounds.getJSONObject(0).getInt("port"))
        assertEquals("proxy", outbounds.getJSONObject(0).getString("tag"))
        val streamSettings = outbounds.getJSONObject(0).getJSONObject("streamSettings")

        assertEquals("xhttp", streamSettings.getString("network"))
        assertEquals("tls", streamSettings.getString("security"))

        val tlsSettings = streamSettings.getJSONObject("tlsSettings")
        assertEquals("example.com", tlsSettings.getString("serverName"))
        assertFalse(tlsSettings.getBoolean("allowInsecure"))
        assertEquals("h2", tlsSettings.getJSONArray("alpn").getString(0))

        val xhttpSettings = streamSettings.getJSONObject("xhttpSettings")
        assertFalse(streamSettings.has("wsSettings"))
        assertFalse(xhttpSettings.has("heartbeatPeriod"))
        assertEquals("packet-up", xhttpSettings.getString("mode"))
        assertEquals("example.com", xhttpSettings.getString("host"))
        assertEquals("/", xhttpSettings.getString("path"))

        val users = outbounds.getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users")
        assertEquals("00000000-0000-0000-0000-000000000000", users.getJSONObject(0).getString("id"))
        val dns = root.getJSONObject("dns")
        assertEquals("UseIPv4", dns.getString("queryStrategy"))
        assertEquals("https://1.1.1.1/dns-query", dns.getJSONArray("servers").getJSONObject(0).getString("address"))
        assertTrue(dns.getBoolean("enableParallelQuery"))
        assertTrue(dns.getBoolean("serveStale"))
        assertEquals(600, dns.getInt("serveExpiredTTL"))
    }

    @Test
    fun buildConfig_xhttpProfile_preservesConfiguredMode() {
        val profile = G2RayProfile(
            rawUri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=stream-up&alpn=h2#test",
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
            mode = "stream-up",
            allowInsecure = false,
            name = "test"
        )

        val jsonString = XrayConfigBuilder.buildConfig(profile, socksPort = 19090)
        val root = JSONObject(jsonString)
        val xhttpSettings = root.getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("xhttpSettings")

        assertEquals("stream-up", xhttpSettings.getString("mode"))
    }

    @Test
    fun buildConfig_wsProfile_correctConfig() {
        val profile = G2RayProfile(
            rawUri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#test",
            uuid = "00000000-0000-0000-0000-000000000000",
            address = "example.com",
            port = 443,
            security = "tls",
            sni = "example.com",
            transport = Transport.WS,
            hostHeader = "example.com",
            alpn = emptyList(),
            path = "/",
            fingerprint = null,
            mode = null,
            allowInsecure = false,
            name = "test"
        )

        val jsonString = XrayConfigBuilder.buildConfig(profile)
        val root = JSONObject(jsonString)

        val outbounds = root.getJSONArray("outbounds")
        val streamSettings = outbounds.getJSONObject(0).getJSONObject("streamSettings")

        assertEquals("ws", streamSettings.getString("network"))

        val wsSettings = streamSettings.getJSONObject("wsSettings")
        assertFalse(wsSettings.has("heartbeatPeriod"))
        assertEquals("/", wsSettings.getString("path"))
        assertEquals("example.com", wsSettings.getJSONObject("headers").getString("Host"))

        val tlsSettings = streamSettings.getJSONObject("tlsSettings")
        assertEquals("chrome", tlsSettings.getString("fingerprint"))
        assertFalse(tlsSettings.getBoolean("allowInsecure"))
    }

    @Test
    fun buildDelayTestConfig_wsProfile_preservesForcedAlpn() {
        val profile = G2RayProfile(
            rawUri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2Fws&alpn=http%2F1.1#test",
            uuid = "00000000-0000-0000-0000-000000000000",
            address = "example.com",
            port = 443,
            security = "tls",
            sni = "example.com",
            transport = Transport.WS,
            hostHeader = "example.com",
            alpn = listOf("http/1.1"),
            path = "/ws",
            fingerprint = "chrome",
            mode = null,
            allowInsecure = false,
            name = "test"
        )

        val jsonString = XrayConfigBuilder.buildDelayTestConfig(profile, socksPort = 19090)
        val root = JSONObject(jsonString)
        val streamSettings = root.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        val tlsSettings = streamSettings.getJSONObject("tlsSettings")

        assertEquals("ws", streamSettings.getString("network"))
        assertEquals("http/1.1", tlsSettings.getJSONArray("alpn").getString(0))
        assertEquals("/ws", streamSettings.getJSONObject("wsSettings").getString("path"))
    }

    @Test
    fun buildVpnConfig_xhttpProfile_usesTunInboundAndVlessOutbound() {
        val profile = G2RayProfile(
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

        val jsonString = XrayConfigBuilder.buildVpnConfig(profile, healthSocksPort = 19090)
        val root = JSONObject(jsonString)

        val policy = root.getJSONObject("policy").getJSONObject("levels").getJSONObject("0")
        assertEquals(512, policy.getInt("bufferSize"))
        assertEquals(900, policy.getInt("connIdle"))
        assertFalse(policy.has("handshake"))

        val inbounds = root.getJSONArray("inbounds")
        assertEquals(2, inbounds.length())
        val tunInbound = inbounds.getJSONObject(0)
        assertEquals("tun-in", tunInbound.getString("tag"))
        assertEquals("tun", tunInbound.getString("protocol"))
        assertEquals(0, tunInbound.getInt("port"))

        val tunSettings = tunInbound.getJSONObject("settings")
        assertEquals("g2ray0", tunSettings.getString("name"))
        assertEquals(1500, tunSettings.getInt("MTU"))
        assertEquals("fc00::1/64", tunSettings.getJSONArray("gateway").getString(1))
        assertEquals(2, tunSettings.getJSONArray("dns").length())

        val dns = root.getJSONObject("dns")
        val dnsServers = dns.getJSONArray("servers")
        assertEquals(2, dnsServers.length())
        assertEquals("https://1.1.1.1/dns-query", dnsServers.getJSONObject(0).getString("address"))
        assertEquals("https://8.8.8.8/dns-query", dnsServers.getJSONObject(1).getString("address"))
        assertEquals("UseIPv4", dns.getString("queryStrategy"))
        assertTrue(dns.getBoolean("enableParallelQuery"))
        assertTrue(dns.getBoolean("serveStale"))
        assertEquals(600, dns.getInt("serveExpiredTTL"))
        assertEquals("dns-inbound", dns.getString("tag"))

        val routing = root.getJSONObject("routing")
        assertEquals("AsIs", routing.getString("domainStrategy"))
        val rules = routing.getJSONArray("rules")
        assertEquals("tun-in", rules.getJSONObject(0).getJSONArray("inboundTag").getString(0))
        assertEquals("health-socks", rules.getJSONObject(0).getJSONArray("inboundTag").getString(1))
        assertEquals("53", rules.getJSONObject(0).getString("port"))
        assertEquals("dns-out", rules.getJSONObject(0).getString("outboundTag"))
        assertEquals("dns-inbound", rules.getJSONObject(1).getJSONArray("inboundTag").getString(0))
        assertEquals("proxy", rules.getJSONObject(1).getString("outboundTag"))
        assertEquals("udp", rules.getJSONObject(2).getString("network"))
        assertEquals("443", rules.getJSONObject(2).getString("port"))
        assertEquals("block", rules.getJSONObject(2).getString("outboundTag"))
        assertEquals("health-socks", rules.getJSONObject(3).getJSONArray("inboundTag").getString(0))
        assertEquals("proxy", rules.getJSONObject(3).getString("outboundTag"))
        assertEquals("tun-in", rules.getJSONObject(4).getJSONArray("inboundTag").getString(0))
        assertEquals("proxy", rules.getJSONObject(4).getString("outboundTag"))

        val sniffingOverrides = tunInbound.getJSONObject("sniffing").getJSONArray("destOverride")
        assertEquals(0, sniffingOverrides.length())
        assertFalse(tunInbound.getJSONObject("sniffing").getBoolean("enabled"))

        val healthInbound = inbounds.getJSONObject(1)
        assertEquals("health-socks", healthInbound.getString("tag"))
        assertEquals("socks", healthInbound.getString("protocol"))
        assertEquals("127.0.0.1", healthInbound.getString("listen"))
        assertEquals(19090, healthInbound.getInt("port"))

        val outbounds = root.getJSONArray("outbounds")
        assertEquals("proxy", outbounds.getJSONObject(0).getString("tag"))
        assertEquals("vless", outbounds.getJSONObject(0).getString("protocol"))
        assertEquals(3, outbounds.length())
        assertEquals("block", outbounds.getJSONObject(1).getString("tag"))
        assertEquals("blackhole", outbounds.getJSONObject(1).getString("protocol"))
        assertEquals("dns-out", outbounds.getJSONObject(2).getString("tag"))
        assertEquals("dns", outbounds.getJSONObject(2).getString("protocol"))

        val socketSettings = outbounds.getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("sockopt")
        assertEquals(25, socketSettings.getInt("tcpKeepAliveIdle"))
        assertEquals(15, socketSettings.getInt("tcpKeepAliveInterval"))
        assertEquals(30_000, socketSettings.getInt("tcpUserTimeout"))
    }

    @Test
    fun buildVpnConfig_hevProfileUsesLoopbackSocksInsteadOfNativeTun() {
        val profile = G2RayProfile(
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

        val root = JSONObject(
            XrayConfigBuilder.buildVpnConfig(
                profile = profile,
                healthSocksPort = 19090,
                engine = ActiveVpnEngine.Hev
            )
        )

        val inbounds = root.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        val socks = inbounds.getJSONObject(0)
        assertEquals("tun-socks", socks.getString("tag"))
        assertEquals("socks", socks.getString("protocol"))
        assertEquals("127.0.0.1", socks.getString("listen"))
        assertEquals(19090, socks.getInt("port"))
        assertTrue(socks.getJSONObject("settings").getBoolean("udp"))
        val sniffingOverrides = socks.getJSONObject("sniffing").getJSONArray("destOverride")
        assertEquals(0, sniffingOverrides.length())
        assertFalse(socks.getJSONObject("sniffing").getBoolean("enabled"))

        val rules = root.getJSONObject("routing").getJSONArray("rules")
        assertEquals("tun-socks", rules.getJSONObject(0).getJSONArray("inboundTag").getString(0))
        assertEquals("53", rules.getJSONObject(0).getString("port"))
        assertEquals("dns-out", rules.getJSONObject(0).getString("outboundTag"))
        assertEquals("tun-socks", rules.getJSONObject(3).getJSONArray("inboundTag").getString(0))
        assertEquals("proxy", rules.getJSONObject(3).getString("outboundTag"))
    }

    @Test
    fun buildVpnConfig_fastDnsAddsBoundedPoolAndSniffingToBothEngines() {
        listOf(ActiveVpnEngine.XrayNative, ActiveVpnEngine.Hev).forEach { engine ->
            val root = JSONObject(
                XrayConfigBuilder.buildVpnConfig(
                    profile = testXhttpProfile(),
                    healthSocksPort = 19090,
                    engine = engine,
                    fakeDnsEnabled = true
                )
            )

            val fakeDns = root.getJSONObject("fakeDns")
            assertEquals("240.0.0.0/12", fakeDns.getString("ipPool"))
            assertEquals(65_535, fakeDns.getInt("poolSize"))
            assertFalse(root.has("fakedns"))
            assertEquals("fakedns", root.getJSONObject("dns").getJSONArray("servers").getString(0))

            val dataInbound = root.getJSONArray("inbounds").getJSONObject(0)
            val overrides = dataInbound.getJSONObject("sniffing").getJSONArray("destOverride")
            assertEquals("fakedns", overrides.getString(0))
            assertEquals(1, overrides.length())
            assertTrue(dataInbound.getJSONObject("sniffing").getBoolean("routeOnly"))
            assertTrue(dataInbound.getJSONObject("sniffing").getBoolean("metadataOnly"))
        }
    }

    @Test
    fun buildVpnConfig_defaultDnsDoesNotCreateFakeAddresses() {
        val root = JSONObject(
            XrayConfigBuilder.buildVpnConfig(
                profile = testXhttpProfile(),
                healthSocksPort = 19090,
                engine = ActiveVpnEngine.Hev
            )
        )

        assertFalse(root.has("fakeDns"))
        assertFalse(root.has("fakedns"))
        assertEquals(
            "https://1.1.1.1/dns-query",
            root.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("address")
        )
        val overrides = root.getJSONArray("inbounds")
            .getJSONObject(0)
            .getJSONObject("sniffing")
            .getJSONArray("destOverride")
        assertEquals(0, overrides.length())
    }

    @Test
    fun buildVpnConfig_ipv4OnlyRemovesNativeTunIpv6Gateway() {
        val root = JSONObject(
            XrayConfigBuilder.buildVpnConfig(
                profile = testXhttpProfile(),
                healthSocksPort = 19090,
                engine = ActiveVpnEngine.XrayNative,
                ipv4Only = true
            )
        )

        val gateway = root.getJSONArray("inbounds")
            .getJSONObject(0)
            .getJSONObject("settings")
            .getJSONArray("gateway")
        assertEquals(1, gateway.length())
        assertEquals("198.18.0.1/15", gateway.getString(0))
    }

    @Test
    fun buildVpnConfig_placesNativeTunFdInRootEnvironmentOnlyWhenProvided() {
        val native = JSONObject(
            XrayConfigBuilder.buildVpnConfig(
                profile = testXhttpProfile(),
                healthSocksPort = 19090,
                engine = ActiveVpnEngine.XrayNative,
                tunFd = 42
            )
        )
        val tunFd = native.getJSONObject("env").get("xray.tun.fd")
        assertTrue(tunFd is String)
        assertEquals("42", tunFd)

        val hev = JSONObject(
            XrayConfigBuilder.buildVpnConfig(
                profile = testXhttpProfile(),
                healthSocksPort = 19090,
                engine = ActiveVpnEngine.Hev,
                tunFd = null
            )
        )
        assertFalse(hev.has("env"))
    }

    @Test
    fun buildVpnConfig_dpiCompatibilityAddsFragmentMaskAndNativeGoTlsOnlyWhenEnabled() {
        val normal = JSONObject(
            XrayConfigBuilder.buildVpnConfig(
                profile = testXhttpProfile(),
                healthSocksPort = 19090
            )
        ).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertFalse(normal.has("finalmask"))
        assertEquals("chrome", normal.getJSONObject("tlsSettings").getString("fingerprint"))

        val compatibility = JSONObject(
            XrayConfigBuilder.buildVpnConfig(
                profile = testXhttpProfile(),
                healthSocksPort = 19090,
                dpiCompatibilityEnabled = true
            )
        ).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("unsafe", compatibility.getJSONObject("tlsSettings").getString("fingerprint"))
        val masks = compatibility.getJSONObject("finalmask").getJSONArray("tcp")
        assertEquals(2, masks.length())
        assertEquals("tlshello", masks.getJSONObject(0).getJSONObject("settings").getString("packets"))
        assertEquals("1-1", masks.getJSONObject(1).getJSONObject("settings").getString("packets"))
        assertEquals("1", masks.getJSONObject(1).getJSONObject("settings").getJSONArray("delays").getString(0))
    }

    @Test
    fun buildDelayTestConfig_dpiCompatibilityUsesTheSameTlsAndFragmentPath() {
        val streamSettings = JSONObject(
            XrayConfigBuilder.buildDelayTestConfig(
                profile = testXhttpProfile(),
                socksPort = 19090,
                dpiCompatibilityEnabled = true
            )
        ).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")

        assertEquals("unsafe", streamSettings.getJSONObject("tlsSettings").getString("fingerprint"))
        val masks = streamSettings.getJSONObject("finalmask").getJSONArray("tcp")
        assertEquals(2, masks.length())
        assertEquals("tlshello", masks.getJSONObject(0).getJSONObject("settings").getString("packets"))
        assertEquals("1-1", masks.getJSONObject(1).getJSONObject("settings").getString("packets"))
    }

    @Test
    fun buildConfig_importedFinalMaskAndUnsafeCipherSuitesArePreserved() {
        val finalMask = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":["20"],"delays":["0"],"maxSplit":"2"}}]}"""
        val cipherSuites = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
        val profile = testXhttpProfile().copy(
            fingerprint = "unsafe",
            finalMaskJson = finalMask,
            cipherSuites = cipherSuites
        )

        val streamSettings = JSONObject(XrayConfigBuilder.buildConfig(profile))
            .getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("streamSettings")

        assertEquals(finalMask, streamSettings.getJSONObject("finalmask").toString())
        assertEquals(cipherSuites, streamSettings.getJSONObject("tlsSettings").getString("cipherSuites"))
    }

    @Test
    fun buildConfig_importedXhttpExtraIsUsedByDelayAndBothVpnEngines() {
        val extra = """{"scMaxEachPostBytes":60000,"xmux":{"maxConnections":3,"hKeepAlivePeriod":20}}"""
        val profile = testXhttpProfile().copy(xhttpExtraJson = extra)
        val configs = listOf(
            XrayConfigBuilder.buildDelayTestConfig(profile, socksPort = 19090),
            XrayConfigBuilder.buildVpnConfig(profile, engine = ActiveVpnEngine.Hev),
            XrayConfigBuilder.buildVpnConfig(profile, engine = ActiveVpnEngine.XrayNative)
        )

        configs.forEach { config ->
            val settings = JSONObject(config).getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("streamSettings").getJSONObject("xhttpSettings")
            assertEquals(extra, settings.getJSONObject("extra").toString())
        }
    }

    @Test
    fun buildVpnConfig_untunedProfileDoesNotGainImplicitXhttpTuning() {
        val settings = JSONObject(XrayConfigBuilder.buildVpnConfig(testXhttpProfile()))
            .getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("xhttpSettings")

        assertFalse(settings.has("extra"))
        assertFalse(settings.has("xmux"))
    }

    @Test
    fun buildVpnConfig_differentXhttpTuningCannotEnterTheSameBalancer() {
        val selected = testXhttpProfile().copy(
            address = "20.0.0.1",
            xhttpExtraJson = """{"xmux":{"maxConnections":3}}"""
        )
        val incompatible = selected.copy(address = "20.0.0.2", xhttpExtraJson = null)
        val compatible = selected.copy(address = "20.0.0.3")
        val root = JSONObject(XrayConfigBuilder.buildVpnConfig(
            selected,
            fallbackProfiles = listOf(incompatible, compatible)
        ))
        val outbounds = root.getJSONArray("outbounds")
        val proxyOutbounds = (0 until outbounds.length()).map(outbounds::getJSONObject)
            .filter { it.getString("protocol") == "vless" }

        assertEquals(2, proxyOutbounds.size)
        assertEquals(listOf("20.0.0.1", "20.0.0.3"), proxyOutbounds.map {
            it.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address")
        })
        proxyOutbounds.forEach {
            assertEquals(selected.xhttpExtraJson,
                it.getJSONObject("streamSettings").getJSONObject("xhttpSettings")
                    .getJSONObject("extra").toString())
        }
    }

    @Test
    fun buildVpnConfig_compatibleFallbacksUseLeastPingWithSelectedRouteAsFallback() {
        val selected = testXhttpProfile().copy(address = "20.0.0.1", name = "selected")
        val fallbackA = selected.copy(address = "20.0.0.2", name = "fallback-a")
        val fallbackB = selected.copy(address = "20.0.0.3", name = "fallback-b")

        val root = JSONObject(
            XrayConfigBuilder.buildVpnConfig(
                profile = selected,
                fallbackProfiles = listOf(fallbackA, fallbackB),
                healthSocksPort = 19090
            )
        )

        val outbounds = root.getJSONArray("outbounds")
        assertEquals(listOf("proxy-0", "proxy-1", "proxy-2"), (0..2).map {
            outbounds.getJSONObject(it).getString("tag")
        })
        assertEquals("20.0.0.1", outbounds.getJSONObject(0).getJSONObject("settings")
            .getJSONArray("vnext").getJSONObject(0).getString("address"))

        val observatory = root.getJSONObject("observatory")
        assertEquals("proxy-", observatory.getJSONArray("subjectSelector").getString(0))
        assertEquals("20s", observatory.getString("probeInterval"))
        assertTrue(observatory.getBoolean("enableConcurrency"))

        val routing = root.getJSONObject("routing")
        val balancer = routing.getJSONArray("balancers").getJSONObject(0)
        assertEquals("proxy-balancer", balancer.getString("tag"))
        assertEquals("proxy-", balancer.getJSONArray("selector").getString(0))
        assertEquals("leastPing", balancer.getJSONObject("strategy").getString("type"))
        assertEquals("proxy-0", balancer.getString("fallbackTag"))

        val rules = routing.getJSONArray("rules")
        listOf(1, 3, 4).forEach { index ->
            assertEquals("proxy-balancer", rules.getJSONObject(index).getString("balancerTag"))
            assertFalse(rules.getJSONObject(index).has("outboundTag"))
        }
    }

    @Test
    fun buildVpnConfig_singleRouteKeepsSimpleOutboundWithoutProbeTraffic() {
        val root = JSONObject(
            XrayConfigBuilder.buildVpnConfig(
                profile = testXhttpProfile(),
                fallbackProfiles = emptyList(),
                healthSocksPort = 19090
            )
        )

        assertFalse(root.has("observatory"))
        assertFalse(root.getJSONObject("routing").has("balancers"))
        assertEquals("proxy", root.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
        assertEquals(
            "proxy",
            root.getJSONObject("routing").getJSONArray("rules").getJSONObject(1).getString("outboundTag")
        )
    }

    private fun testXhttpProfile(): G2RayProfile {
        return G2RayProfile(
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
    }
}
