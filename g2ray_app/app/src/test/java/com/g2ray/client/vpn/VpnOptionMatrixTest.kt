package com.g2ray.client.vpn

import android.net.Uri
import com.g2ray.client.data.SecureKeyValueStore
import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.Transport
import com.g2ray.client.model.VpnEngineMode
import com.g2ray.client.parser.VlessUriParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class VpnOptionMatrixTest {
    @Test fun everyStoredSwitchEngineAndRoutingCombinationRoundTrips() {
        val values = mutableMapOf("unrelated-account-data" to "preserve")
        val store = object : SecureKeyValueStore {
            override fun getString(key: String, defaultValue: String?) = values[key] ?: defaultValue
            override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
            override fun putStrings(values: Map<String, String?>) { values.forEach { (k, v) -> putString(k, v) } }
            override fun remove(vararg keys: String) { keys.forEach(values::remove) }
        }
        val p = VpnEnginePreferences(store)
        var cases = 0
        for (mode in VpnEngineMode.entries) for (mask in 0..63) for (routing in 0..7) {
            val expected = VpnSettingsSnapshot(mode, bit(mask, 0), bit(mask, 1), bit(mask, 2),
                bit(mask, 3), bit(mask, 4), policy(routing), bit(mask, 5))
            p.setMode(mode)
            p.setFastDnsEnabled(expected.fastDnsEnabled)
            p.setIpv4OnlyEnabled(expected.ipv4OnlyEnabled)
            p.setDpiCompatibilityEnabled(expected.dpiCompatibilityEnabled)
            p.setAutoRouteOptimizationEnabled(expected.autoRouteOptimizationEnabled)
            p.setSeamlessFailoverEnabled(expected.seamlessFailoverEnabled)
            p.setRoutingPolicy(expected.routingPolicy)
            p.setDiagnosticsEnabled(expected.diagnosticsEnabled)
            assertEquals("mode=$mode mask=$mask routing=$routing", expected, VpnEnginePreferences(store).readSettings())
            assertEquals("preserve", values["unrelated-account-data"])
            cases++
        }
        assertEquals(1536, cases)
    }

    @Test fun generatedTransportModeTuningAndDataPathCombinationsKeepTheirContracts() {
        var cases = 0
        val fixtureDir = System.getProperty("g2ray.configFixtureDir")?.let { File(it, "options").apply { mkdirs() } }
        val variants = buildList {
            for (mode in listOf("packet-up", "stream-up", "stream-one", "auto")) {
                for (extra in listOf(false, true)) add(Triple(Transport.XHTTP, mode, if (extra) "extra" else "default"))
            }
            for (alpn in listOf("h2", "http/1.1", "")) add(Triple(Transport.WS, "", alpn))
        }
        for (engine in ActiveVpnEngine.entries) for (mask in 0..15) for (routing in 0..3) for (variant in variants) {
            val (transport, mode, tuningOrAlpn) = variant
            val extra = if (transport == Transport.XHTTP && tuningOrAlpn == "extra") "{\"scMaxEachPostBytes\":1000000}" else null
            val alpn = if (transport == Transport.XHTTP) "h2" else tuningOrAlpn
            val raw = "vless://00000000-0000-0000-0000-000000000001@203.0.113.1:443" +
                "?encryption=none&security=tls&sni=fixture.example&host=fixture.example&fp=chrome" +
                "&type=${transport.name.lowercase()}&path=%2F&mode=$mode&alpn=${Uri.encode(alpn)}" +
                (extra?.let { "&extra=${Uri.encode(it)}" } ?: "")
            val profile = VlessUriParser.parse(raw).getOrThrow()
            val fakeDns = bit(mask, 0)
            val ipv4 = bit(mask, 1)
            val dpi = bit(mask, 2)
            val balanced = bit(mask, 3)
            val policy = policy(routing)
            val config = JSONObject(XrayConfigBuilder.buildVpnConfig(profile, engine = engine,
                fakeDnsEnabled = fakeDns, ipv4Only = ipv4, dpiCompatibilityEnabled = dpi,
                fallbackProfiles = if (balanced) listOf(profile.copy(address = "203.0.113.2")) else emptyList(),
                routingPolicy = policy))
            val label = "engine=$engine flags=$mask routing=$routing variant=$variant"
            assertEquals(label, fakeDns, config.has("fakeDns"))
            assertEquals(label, balanced, config.has("observatory"))
            val outbounds = config.getJSONArray("outbounds")
            val proxies = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.filter { it.getString("protocol") == "vless" }
            assertEquals(label, if (balanced) 2 else 1, proxies.size)
            for (proxy in proxies) {
                assertFalse(label, proxy.getJSONObject("mux").getBoolean("enabled"))
                val stream = proxy.getJSONObject("streamSettings")
                val tls = stream.getJSONObject("tlsSettings")
                assertFalse(label, tls.getBoolean("allowInsecure"))
                assertEquals(label, if (dpi) "unsafe" else "chrome", tls.getString("fingerprint"))
                assertEquals(label, dpi, stream.has("finalmask"))
                if (transport == Transport.XHTTP) {
                    val xhttp = stream.getJSONObject("xhttpSettings")
                    assertEquals(label, mode, xhttp.getString("mode"))
                    assertEquals(label, extra != null, xhttp.has("extra"))
                }
            }
            assertEquals(label, policy.hasDomainBypass, (0 until outbounds.length()).any {
                outbounds.getJSONObject(it).optString("tag") == "bypass"
            })
            val inbounds = config.getJSONArray("inbounds")
            val tun = (0 until inbounds.length()).map { inbounds.getJSONObject(it) }.firstOrNull { it.optString("protocol") == "tun" }
            assertEquals(label, engine == ActiveVpnEngine.XrayNative, tun != null)
            tun?.let { assertEquals(label, if (ipv4) 1 else 2, it.getJSONObject("settings").getJSONArray("gateway").length()) }
            fixtureDir?.let { File(it, "variant-${cases.toString().padStart(4, '0')}.json").writeText(config.toString()) }
            cases++
        }
        assertEquals(1408, cases)
    }

    private fun bit(mask: Int, bit: Int) = mask and (1 shl bit) != 0
    private fun policy(mask: Int) = RoutingPolicy(bit(mask, 0),
        if (bit(mask, 1)) listOf("example.ir", "fixture.example") else emptyList(),
        if (bit(mask, 2)) setOf("com.example.testclient") else emptySet())
}
