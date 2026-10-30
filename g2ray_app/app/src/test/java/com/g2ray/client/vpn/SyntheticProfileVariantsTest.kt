package com.g2ray.client.vpn

import android.net.Uri
import com.g2ray.client.parser.VlessUriParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyntheticProfileVariantsTest {
    private fun source(type: String = "xhttp", extra: Boolean = true) = VlessUriParser.parse(
        "vless://00000000-0000-0000-0000-000000000000@example.com:443" +
            "?encryption=none&security=tls&sni=example.com&host=example.com&type=$type" +
            "&path=${Uri.encode("/path?a=b+c")}&mode=packet-up&alpn=h2&fp=chrome" +
            "&unknown=one&unknown=two" +
            (if (extra && type == "xhttp") "&extra=${Uri.encode("{\"xmux\":{\"maxConcurrency\":8}}")}" else "") +
            "#test%20name"
    ).getOrThrow()

    @Test fun cloneRemovesOnlyExtraWithoutChangingSourceOrUnknownParameters() {
        val original = source()
        val clone = SyntheticProfileVariants.withoutXhttpExtra(original)
        assertNotNull(original.xhttpExtraJson)
        assertNull(clone.xhttpExtraJson)
        assertNull(Uri.parse(clone.rawUri).getQueryParameter("extra"))
        assertEquals(original, clone.copy(rawUri = original.rawUri, xhttpExtraJson = original.xhttpExtraJson))
        assertEquals(listOf("one", "two"), Uri.parse(clone.rawUri).getQueryParameters("unknown"))
        assertEquals(clone, VlessUriParser.parse(clone.rawUri).getOrThrow())
    }

    @Test fun cloneWithoutExistingExtraIsSemanticallyUnchanged() {
        val original = source(extra = false)
        assertEquals(original, SyntheticProfileVariants.withoutXhttpExtra(original).copy(rawUri = original.rawUri))
    }

    @Test fun websocketCannotBeSilentlyComparedAsXhttp() {
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticProfileVariants.withoutXhttpExtra(source(type = "ws", extra = false))
        }
    }

    @Test fun eightControlledVariantsChangeOnlyTheThreeFactorsAndPreserveNonemptyMux() {
        val original = source()
        for (post in listOf(60_000, 1_000_000)) for (interval in listOf(10, 30)) for (keep in listOf(0, 20)) {
            val clone = SyntheticProfileVariants.controlledXhttp(original, post, interval, keep)
            assertEquals(original, clone.copy(rawUri = original.rawUri, xhttpExtraJson = original.xhttpExtraJson))
            val extra = JSONObject(clone.xhttpExtraJson!!)
            assertEquals(post, extra.getInt("scMaxEachPostBytes"))
            assertEquals(interval, extra.getInt("scMinPostsIntervalMs"))
            assertEquals(keep, extra.getJSONObject("xmux").getInt("hKeepAlivePeriod"))
            assertEquals(8, extra.getJSONObject("xmux").getInt("maxConcurrency"))
            assertFalse(extra.getJSONObject("xmux").has("maxConnections"))
            assertEquals(listOf("one", "two"), Uri.parse(clone.rawUri).getQueryParameters("unknown"))
        }
    }

    @Test fun controlledVariantMaterializesEmptyMuxDefaultsBeforeChangingKeepalive() {
        val extra = JSONObject(SyntheticProfileVariants.controlledXhttp(source(extra = false), 60_000, 10, 20).xhttpExtraJson!!)
        val mux = extra.getJSONObject("xmux")
        assertEquals(3, mux.getInt("maxConnections"))
        assertEquals("600-900", mux.getString("hMaxRequestTimes"))
        assertEquals("1800-3000", mux.getString("hMaxReusableSecs"))
        assertEquals(20, mux.getInt("hKeepAlivePeriod"))
    }

    @Test fun controlledVariantRejectsUnboundedOrUnknownFactorValues() {
        assertThrows(IllegalArgumentException::class.java) { SyntheticProfileVariants.controlledXhttp(source(), 0, 10, 20) }
        assertThrows(IllegalArgumentException::class.java) { SyntheticProfileVariants.controlledXhttp(source(), 60_000, -1, 20) }
        assertThrows(IllegalArgumentException::class.java) { SyntheticProfileVariants.controlledXhttp(source(), 60_000, 10, 3600) }
    }
}
