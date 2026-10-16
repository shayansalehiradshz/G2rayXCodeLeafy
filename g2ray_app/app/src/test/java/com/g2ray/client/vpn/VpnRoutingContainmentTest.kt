package com.g2ray.client.vpn

import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.Transport
import com.g2ray.client.parser.VlessUriParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Config-contract checks, not a packet capture or OS kill-switch acceptance. */
@RunWith(RobolectricTestRunner::class)
class VpnRoutingContainmentTest {
    @Test
    fun everyApplicationIngressUsesTheProxyOrADeliberateBlockNeverDirect() {
        configs().forEach { root ->
            val proxyTags = root.getJSONArray("outbounds").let { list ->
                (0 until list.length()).map { list.getJSONObject(it) }
                    .filter { it.getString("protocol") == "vless" }.map { it.getString("tag") }.toSet()
            }
            val inbounds = root.getJSONArray("inbounds")
            for (index in 0 until inbounds.length()) {
                val inbound = inbounds.getJSONObject(index)
                val tag = inbound.getString("tag")
                if (inbound.getString("protocol") == "socks") {
                    assertEquals("127.0.0.1", inbound.getString("listen"))
                }
                for (network in listOf("tcp", "udp")) {
                    for (port in listOf(53, 80, 443, 853, 3478, 5349, 8443)) {
                        val target = target(root, tag, network, port)
                        when {
                            port == 53 -> assertEquals("dns-out", target)
                            network == "udp" && port == 443 -> assertEquals("block", target)
                            else -> assertTrue("$tag $network/$port must not bypass: $target",
                                target in proxyTags || target == "balancer:proxy-balancer")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun applicationDnsUsesOnlyProxiedDohOrLocalFakeDnsNeverSystemFallback() {
        configs().forEach { root ->
            val dns = root.getJSONObject("dns")
            val servers = dns.getJSONArray("servers")
            for (index in 0 until servers.length()) {
                val entry = servers.get(index)
                if (entry is String) assertEquals("fakedns", entry)
                else assertTrue((entry as JSONObject).getString("address").startsWith("https://"))
            }
            assertEquals("AsIs", root.getJSONObject("routing").getString("domainStrategy"))
            val target = target(root, dns.getString("tag"), "tcp", 443)
            assertTrue(target == "proxy" || target == "balancer:proxy-balancer")
            assertEquals("vless", root.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
            val rules = root.getJSONObject("routing").getJSONArray("rules")
            for (index in 0 until rules.length()) {
                assertFalse(rules.getJSONObject(index).optString("outboundTag") == "direct")
            }
        }
    }

    private fun target(root: JSONObject, inboundTag: String, network: String, port: Int): String {
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        for (index in 0 until rules.length()) {
            val rule = rules.getJSONObject(index)
            val tags = rule.optJSONArray("inboundTag")
            if (tags != null && (0 until tags.length()).none { tags.getString(it) == inboundTag }) continue
            if (rule.has("network") && rule.getString("network") != network) continue
            if (rule.has("port") && rule.getString("port") != port.toString()) continue
            return if (rule.has("balancerTag")) "balancer:${rule.getString("balancerTag")}" else rule.getString("outboundTag")
        }
        return root.getJSONArray("outbounds").getJSONObject(0).getString("tag")
    }

    private fun configs(): List<JSONObject> = buildList {
        val selected = VlessUriParser.parse(
            "vless://00000000-0000-4000-8000-000000000001@203.0.113.1:443" +
                "?encryption=none&security=tls&sni=fixture.example&host=fixture.example&type=xhttp&path=%2F&mode=packet-up&alpn=h2"
        ).getOrThrow()
        for (engine in ActiveVpnEngine.entries) for (transport in Transport.entries) {
            for (fakeDns in listOf(false, true)) for (ipv4Only in listOf(false, true)) {
                for (balanced in listOf(false, true)) {
                    val profile = selected.copy(transport = transport,
                        path = if (transport == Transport.WS) "/ws" else "/")
                    add(JSONObject(XrayConfigBuilder.buildVpnConfig(profile, engine = engine,
                        fakeDnsEnabled = fakeDns, ipv4Only = ipv4Only,
                        fallbackProfiles = if (balanced) listOf(profile.copy(address = "203.0.113.2")) else emptyList())))
                }
            }
        }
    }
}
