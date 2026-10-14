package com.g2ray.client.parser

import android.net.Uri
import com.g2ray.client.vpn.XrayConfigBuilder
import org.json.JSONObject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VlessUriParserTest {

    @Test
    fun parse_wsEarlyData_preservesNestedQueryAndDiscoveredRouteIdentity() {
        val path = "/ws?ed=2560&token=a%2Bb"
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443" +
            "?encryption=none&security=tls&sni=example.com&type=ws&host=example.com" +
            "&alpn=http%2F1.1&path=${Uri.encode(path)}#early-data"
        val source = VlessUriParser.parse(uri).getOrThrow()
        val discovered = VlessUriParser.withEndpointAddress(source, "20.85.77.48").getOrThrow()

        assertEquals(path, source.path)
        assertEquals(path, discovered.path)
        assertEquals(source.sni, discovered.sni)
        assertEquals(source.hostHeader, discovered.hostHeader)
        assertEquals(source.alpn, discovered.alpn)
        assertEquals(uri.substringAfter('?').substringBefore('#'),
            discovered.rawUri.substringAfter('?').substringBefore('#'))
        assertEquals(VlessUriParser.transportTemplateKey(source),
            VlessUriParser.transportTemplateKey(discovered))
        assertFalse(VlessUriParser.transportTemplateKey(source) ==
            VlessUriParser.transportTemplateKey(source.copy(path = "/ws")))
    }

    @Test
    fun parse_rejectsPortsOutsideTcpRange() {
        listOf("0", "-1", "65536", "2147483647", "", "not-a-port").forEach { port ->
            val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:$port?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F"
            assertTrue("Must reject endpoint port '$port'", VlessUriParser.parse(uri).isFailure)
        }
    }

    @Test
    fun parse_preservesValidBoundaryPorts() {
        listOf(1, 443, 65535).forEach { port ->
            val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:$port?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F"
            assertEquals(port, VlessUriParser.parse(uri).getOrThrow().port)
        }
    }

    @Test
    fun parse_rejectsEmptyEndpointHost() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F"
        val result = VlessUriParser.parse(uri)
        assertTrue("An empty dial endpoint must fail before DNS or core startup", result.isFailure)
        assertEquals("Missing host", result.exceptionOrNull()?.message)
    }

    @Test
    fun parse_finalMaskRejectsNonJsonAndTrailingData() {
        listOf("{} trailing", "{}{}", "{'tcp':[]}", "{tcp:[]}", "{\"tcp\":}", "/* comment */ {}",
            "{\"tcp\":NaN}", "{\"tcp\":Infinity}", "[]", "null").forEach { mask ->
            val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2&fm=${Uri.encode(mask)}"
            val result = VlessUriParser.parse(uri)
            assertTrue("Must reject FinalMask '$mask'", result.isFailure)
            assertEquals("Invalid FinalMask JSON", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun parse_finalMaskPreservesValidObjectAndEncodedStrings() {
        val mask = " \n{\"tcp\":[],\"note\":\"brace } and quote \\\" and encoded % text\"}\n "
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2&fm=${Uri.encode(mask)}"
        assertEquals(mask.trim(), VlessUriParser.parse(uri).getOrThrow().finalMaskJson)
    }

    @Test
    fun parseAllProfiles_invalidEndpointCannotBecomeAnImportedProfile() {
        val valid = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F"
        val invalid = valid.replace(":443?", ":65536?")
        val results = VlessUriParser.parseAllProfiles("$invalid\n$valid")
        assertEquals(2, results.size)
        assertTrue(results[0].isFailure)
        assertEquals(443, results[1].getOrThrow().port)
        assertEquals(443, VlessUriParser.parseFirstProfile("$invalid\n$valid").getOrThrow().port)
    }

    @Test
    fun parse_validVlessUri_success() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#test"
        val result = VlessUriParser.parse(uri)
        assertTrue(result.isSuccess)
        val profile = result.getOrNull()!!
        assertEquals("00000000-0000-0000-0000-000000000000", profile.uuid)
        assertEquals("example.com", profile.address)
        assertEquals(443, profile.port)
        assertEquals("none", profile.rawUri.substringAfter("encryption=").substringBefore("&"))
        assertEquals("tls", profile.security)
        assertEquals("example.com", profile.sni)
        assertEquals("test", profile.name)
    }

    @Test
    fun parse_truthyInsecure_success() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F&allowInsecure=True#test"
        val result = VlessUriParser.parse(uri)
        assertTrue(result.isFailure)
        assertEquals("allowInsecure=1 is not accepted by default", result.exceptionOrNull()?.message)
    }

    @Test
    fun parseFirstProfile_multiLine_success() {
        val input = """
            some text
            vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#test
            another line
        """.trimIndent()
        val result = VlessUriParser.parseFirstProfile(input)
        assertTrue(result.isSuccess)
    }

    @Test
    fun parseFirstProfile_messyInlineText_success() {
        val input = "Here is the config you asked for: vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#inline, paste it in the app."

        val result = VlessUriParser.parseFirstProfile(input)

        assertTrue(result.isSuccess)
        assertEquals("inline", result.getOrNull()?.name)
    }

    @Test
    fun parseAllProfiles_messyTextWithMultipleConfigs_success() {
        val input = """
            first config is vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#one.
            second config: vless://11111111-1111-1111-1111-111111111111@example.org:443?encryption=none&security=tls&sni=example.org&type=ws&host=example.org&path=%2F#two
        """.trimIndent()

        val results = VlessUriParser.parseAllProfiles(input)

        assertEquals(2, results.size)
        assertTrue(results.all { it.isSuccess })
        assertEquals("one", results[0].getOrNull()?.name)
        assertEquals("two", results[1].getOrNull()?.name)
    }

    @Test
    fun parse_validXhttp_success() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=None&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2,http/1.1#test"
        val result = VlessUriParser.parse(uri)
        assertTrue(result.isSuccess)
    }

    @Test
    fun parse_supportedXhttpModes_success() {
        val modes = listOf("packet-up", "stream-up", "stream-one", "auto")
        for (mode in modes) {
            val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=$mode&alpn=h2#$mode"
            val result = VlessUriParser.parse(uri)
            assertTrue("mode=$mode should parse", result.isSuccess)
            assertEquals(mode, result.getOrNull()?.mode)
        }
    }

    @Test
    fun parse_xhttpExtra_preservesServerExportTuningInCoreConfig() {
        val extra = """{"scMaxEachPostBytes":60000,"scMinPostsIntervalMs":10,"xmux":{"maxConnections":3,"hMaxRequestTimes":"600-900","hMaxReusableSecs":"1800-3000","hKeepAlivePeriod":20}}"""
        val profile = VlessUriParser.parse(xhttpUri(extra)).getOrThrow()

        val settings = JSONObject(XrayConfigBuilder.buildConfig(profile))
            .getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("xhttpSettings")

        assertEquals(extra, settings.getJSONObject("extra").toString())
        assertEquals("example.com", settings.getString("host"))
        assertEquals("packet-up", settings.getString("mode"))
        assertEquals("/", settings.getString("path"))
    }

    @Test
    fun parse_xhttpExtra_rejectsMalformedNonObjectAndTrailingJson() {
        listOf("not-json", "[]", "null", "{\"xmux\":}", "{} trailing", "{}{}", "{'xmux':{}}",
            "{\"xmux\":{\"maxConnections\":NaN}}", "{\"values\":[Infinity,-Infinity]}")
            .forEach { extra ->
                val result = VlessUriParser.parse(xhttpUri(extra))
                assertTrue("Must reject $extra", result.isFailure)
                assertEquals("Invalid XHTTP extra JSON object", result.exceptionOrNull()?.message)
            }
    }

    @Test
    fun parse_xhttpExtra_rejectsOversizedObject() {
        val result = VlessUriParser.parse(xhttpUri("{\"padding\":\"${"a".repeat(8_200)}\"}"))

        assertTrue(result.isFailure)
        assertEquals("XHTTP extra JSON is too large", result.exceptionOrNull()?.message)
    }

    @Test
    fun parse_xhttpExtra_distinguishesTuningTemplatesAndPreservesDiscoveredRoutes() {
        val first = VlessUriParser.parse(xhttpUri("{\"xmux\":{\"maxConnections\":3}}"))
            .getOrThrow()
        val different = VlessUriParser.parse(xhttpUri("{\"xmux\":{\"maxConnections\":6}}"))
            .getOrThrow()
        val generated = VlessUriParser.withEndpointAddress(first, "20.85.77.48").getOrThrow()

        assertFalse(VlessUriParser.semanticRouteKey(first) == VlessUriParser.semanticRouteKey(different))
        assertFalse(VlessUriParser.transportTemplateKey(first) == VlessUriParser.transportTemplateKey(different))
        assertEquals(VlessUriParser.transportTemplateKey(first), VlessUriParser.transportTemplateKey(generated))
        assertEquals(first.rawUri.substringAfter('?').substringBefore('#'),
            generated.rawUri.substringAfter('?').substringBefore('#'))
    }

    @Test
    fun parse_wsExtra_failsInsteadOfSilentlyDroppingXhttpSettings() {
        val result = VlessUriParser.parse(xhttpUri("{}").replace("type=xhttp", "type=ws"))

        assertTrue(result.isFailure)
        assertEquals("XHTTP extra requires type=xhttp", result.exceptionOrNull()?.message)
    }

    @Test
    fun parse_finalMaskAndCipherSuites_preservesAdvancedTransportSettings() {
        val finalMask = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":["5","94","1"],"delays":["0"],"maxSplit":"0"}}]}"""
        val cipherSuites = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2&fp=unsafe&fm=${Uri.encode(finalMask)}&cs=${Uri.encode(cipherSuites)}#test"

        val profile = VlessUriParser.parse(uri).getOrThrow()

        assertEquals(finalMask, profile.finalMaskJson)
        assertEquals(cipherSuites, profile.cipherSuites)
        assertEquals("unsafe", profile.fingerprint)
    }

    @Test
    fun parse_malformedFinalMask_failsBeforeCoreStartup() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2&fm=${Uri.encode("not-json")}#test"

        val result = VlessUriParser.parse(uri)

        assertTrue(result.isFailure)
        assertEquals("Invalid FinalMask JSON", result.exceptionOrNull()?.message)
    }

    @Test
    fun parse_cipherSuitesWithBrowserFingerprint_failsInsteadOfSilentlyIgnoringThem() {
        val cipherSuites = Uri.encode("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=h2&fp=chrome&cs=$cipherSuites#test"

        val result = VlessUriParser.parse(uri)

        assertTrue(result.isFailure)
        assertEquals(
            "Cipher suites require fp=unsafe because browser fingerprints override them",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun parse_missingSni_fails() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&type=ws&host=example.com&path=%2F"
        val result = VlessUriParser.parse(uri)
        assertTrue(result.isFailure)
        assertEquals("Missing SNI", result.exceptionOrNull()?.message)
    }

    @Test
    fun parse_missingHost_fails() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&path=%2F"
        val result = VlessUriParser.parse(uri)
        assertTrue(result.isFailure)
        assertEquals("Missing Host header", result.exceptionOrNull()?.message)
    }

    @Test
    fun parse_xhttpMissingH2_fails() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com&path=%2F&mode=packet-up&alpn=http/1.1"
        val result = VlessUriParser.parse(uri)
        assertTrue(result.isFailure)
        assertEquals("XHTTP Codespaces profiles require alpn=h2", result.exceptionOrNull()?.message)
    }

    @Test
    fun parse_insecureTrue_fails() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F&insecure=TRUE"
        val result = VlessUriParser.parse(uri)
        assertTrue(result.isFailure)
        assertEquals("insecure=1 is not accepted by default", result.exceptionOrNull()?.message)
    }

    @Test
    fun parseFirstProfile_base64_success() {
        // vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#test
        val base64 = "dmxlc3M6Ly8wMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDBAZXhhbXBsZS5jb206NDQzP2VuY3J5cHRpb249bm9uZSZzZWN1cml0eT10bHMmc25pPWV4YW1wbGUuY29tJnR5cGU9d3MmaG9zdD1leGFtcGxlLmNvbSZwYXRoPSUyRiN0ZXN0"
        val result = VlessUriParser.parseFirstProfile(base64)
        assertTrue(result.isSuccess)
    }

    @Test
    fun parseFirstProfile_unsupportedFirst_supportedSecond_success() {
        val input = """
            vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=tcp&host=example.com&path=%2F#tcp
            vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#ws
        """.trimIndent()
        val result = VlessUriParser.parseFirstProfile(input)
        assertTrue(result.isSuccess)
        assertEquals("ws", result.getOrNull()?.name)
    }

    @Test
    fun parseFirstProfile_base64UrlSafeWithoutPadding_success() {
        val base64UrlSafe = "dmxlc3M6Ly8wMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDBAZXhhbXBsZS5jb206NDQzP2VuY3J5cHRpb249bm9uZSZzZWN1cml0eT10bHMmc25pPWV4YW1wbGUuY29tJnR5cGU9d3MmaG9zdD1leGFtcGxlLmNvbSZwYXRoPSUyRiN0ZXN0"
        val result = VlessUriParser.parseFirstProfile(base64UrlSafe.dropLast(1)) // drop padding to test padBase64
        assertTrue(result.isSuccess)
    }

    @Test
    fun parse_missingPath_fails() {
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com"
        val result = VlessUriParser.parse(uri)
        assertTrue(result.isFailure)
        assertEquals("Missing path", result.exceptionOrNull()?.message)
    }

    @Test
    fun parse_oversizedLink_failsBeforeUriProcessing() {
        val result = VlessUriParser.parse("vless://" + "a".repeat(16_500))

        assertTrue(result.isFailure)
        assertEquals("Config link is too large", result.exceptionOrNull()?.message)
    }

    @Test
    fun parseAllProfiles_oversizedPasteIsRejectedWithoutScanningIt() {
        val results = VlessUriParser.parseAllProfiles("x".repeat(1_000_001))

        assertEquals(1, results.size)
        assertTrue(results.single().isFailure)
        assertEquals("Config import text is too large", results.single().exceptionOrNull()?.message)
    }

    @Test
    fun parseAllProfiles_capsOneImportAtFiveHundredConfigs() {
        val link = "vless://00000000-0000-0000-0000-000000000000@example.com:443?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#test"
        val results = VlessUriParser.parseAllProfiles(List(501) { link }.joinToString("\n"))

        assertEquals(1, results.size)
        assertTrue(results.single().isFailure)
        assertEquals(
            "A single import can contain at most 500 configs",
            results.single().exceptionOrNull()?.message
        )
    }

    @Test
    fun withEndpointAddress_changesOnlyDialAddressAndDeterministicName() {
        val finalMask = Uri.encode("""{"tcp":[{"type":"fragment"}]}""")
        val raw = "vless://00000000-0000-0000-0000-000000000000@space-443.app.github.dev:443" +
            "?encryption=none&security=tls&sni=space-443.app.github.dev&type=xhttp" +
            "&host=space-443.app.github.dev&path=%2F&mode=packet-up&alpn=h2&fp=unsafe&fm=$finalMask#base"
        val source = VlessUriParser.parse(raw).getOrThrow()

        val generated = VlessUriParser.withEndpointAddress(source, "20.85.77.48").getOrThrow()

        assertEquals("20.85.77.48", generated.address)
        assertEquals(source.uuid, generated.uuid)
        assertEquals(source.sni, generated.sni)
        assertEquals(source.hostHeader, generated.hostHeader)
        assertEquals(source.path, generated.path)
        assertEquals(source.mode, generated.mode)
        assertEquals(source.alpn, generated.alpn)
        assertEquals(source.finalMaskJson, generated.finalMaskJson)
        assertEquals("base-auto-20-85-77-48", generated.name)
        assertTrue(generated.rawUri.contains("@20.85.77.48:443?"))
    }

    @Test
    fun withEndpointAddress_replacesExistingGeneratedNameSuffix() {
        val source = VlessUriParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@20.85.77.48:443" +
                "?encryption=none&security=tls&sni=space-443.app.github.dev" +
                "&type=xhttp&host=space-443.app.github.dev&path=%2F&mode=packet-up&alpn=h2" +
                "#Route-auto-20-85-77-48"
        ).getOrThrow()

        val generated = VlessUriParser.withEndpointAddress(source, "20.120.56.11").getOrThrow()

        assertEquals("Route-auto-20-120-56-11", generated.name)
        assertFalse(generated.name.contains("48-auto"))
    }

    @Test
    fun routeCandidateValidation_acceptsPublicAndRejectsSpecialRanges() {
        assertTrue(VlessUriParser.isUsablePublicIpv4("20.85.77.48"))
        assertFalse(VlessUriParser.isUsablePublicIpv4("10.0.0.1"))
        assertFalse(VlessUriParser.isUsablePublicIpv4("100.64.0.1"))
        assertFalse(VlessUriParser.isUsablePublicIpv4("127.0.0.1"))
        assertFalse(VlessUriParser.isUsablePublicIpv4("169.254.1.1"))
        assertFalse(VlessUriParser.isUsablePublicIpv4("192.0.2.1"))
        assertFalse(VlessUriParser.isUsablePublicIpv4("198.18.0.1"))
        assertFalse(VlessUriParser.isUsablePublicIpv4("203.0.113.1"))
        assertFalse(VlessUriParser.isUsablePublicIpv4("not-an-ip"))
    }

    private fun xhttpUri(extra: String): String {
        return "vless://00000000-0000-0000-0000-000000000000@example.com:443" +
            "?encryption=none&security=tls&sni=example.com&type=xhttp&host=example.com" +
            "&path=%2F&mode=packet-up&alpn=h2&extra=${Uri.encode(extra)}#test"
    }
}
