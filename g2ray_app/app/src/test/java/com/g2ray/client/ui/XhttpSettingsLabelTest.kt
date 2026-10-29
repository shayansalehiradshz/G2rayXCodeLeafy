package com.g2ray.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XhttpSettingsLabelTest {
    private val link = "vless://11111111-1111-4111-8111-111111111111@edge.example:443" +
        "?encryption=none&security=tls&sni=edge.example&host=edge.example&path=%2F" +
        "&type=xhttp&mode=packet-up&alpn=h2"

    @Test fun distinguishesImportedCustomSettingsWithoutDisplayingCredentials() {
        assertEquals("XHTTP: packet-up / core defaults", xhttpSettingsLabel(link))
        val custom = xhttpSettingsLabel(link + "&extra=%7B%22xmux%22%3A%7B%22maxConcurrency%22%3A8%7D%7D")!!
        assertEquals("XHTTP: packet-up / custom extra", custom)
        assertFalse(custom.contains("11111111"))
        assertFalse(custom.contains("edge.example"))
        assertFalse(custom.contains("xmux"))
    }

    @Test fun invalidAndOtherTransportsDoNotGetMisleadingXhttpLabels() {
        assertNull(xhttpSettingsLabel("invalid"))
        assertNull(xhttpSettingsLabel(link.replace("type=xhttp", "type=ws")))
    }
}
