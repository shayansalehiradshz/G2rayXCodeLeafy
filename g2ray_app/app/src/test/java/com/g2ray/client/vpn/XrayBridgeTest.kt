package com.g2ray.client.vpn

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XrayBridgeTest {
    @Test
    fun invokePingResponse_parsesStructuredDelayObject() {
        val result = XrayPingResult.fromJson(
            """{"success":true,"data":{"delay":321},"error":""}"""
        )

        assertTrue(result.success)
        assertEquals(321L, result.delayMs)
        assertEquals(null, result.error)
    }

    @Test
    fun invokeErrorResponse_preservesErrorText() {
        val result = XrayCallResult.fromJson(
            """{"success":false,"data":null,"error":"invalid config"}"""
        )

        assertFalse(result.success)
        assertEquals("invalid config", result.error)
    }

    @Test
    fun pingBatchResponse_preservesOrderAndPerConfigFailures() {
        val result = XrayPingBatchResult.fromJson(
            """{"success":true,"data":{"results":[{"success":true,"delay":123},{"success":false,"error":"timeout"}]},"error":""}"""
        )

        assertTrue(result.success)
        assertEquals(2, result.results.size)
        assertEquals(123L, result.results[0].delayMs)
        assertTrue(result.results[0].success)
        assertFalse(result.results[1].success)
        assertEquals("timeout", result.results[1].error)
    }

    @Test
    fun modernCapabilities_doNotRequireRemovedSetTunFdMethod() {
        val capabilities = XrayRuntimeCapabilities(
            classFound = true,
            registerDialerControllerFound = true,
            registerListenerControllerFound = true,
            setTunFdFound = false,
            newRunRequestFound = true,
            runFromJsonFound = true,
            stopFound = true,
            getStateFound = true,
            pingFound = true,
            invokeFound = true
        )

        assertTrue(capabilities.canStart)
    }

    @Test
    fun reflectionBridge_translatesLegacyCallsToModernInvokeApi() {
        ModernLibXray.lastRequest = null
        val bridge = ReflectionXrayBridge(ModernLibXray::class.java)

        assertTrue(bridge.isAvailable())
        bridge.registerDialerController { true }
        bridge.registerListenerController { true }

        val runRequest = bridge.newRunFromJsonRequest(
            datDir = "/data",
            configJson = "{\"log\":{\"loglevel\":\"warning\"}}"
        )
        val runResult = bridge.runFromJson(runRequest)
        assertTrue(runResult.success)
        val runJson = org.json.JSONObject(ModernLibXray.lastRequest!!)
        assertEquals("runXrayFromJson", runJson.getString("method"))
        assertEquals(
            "{\"log\":{\"loglevel\":\"warning\"}}",
            runJson.getJSONObject("payload").getString("configJSON")
        )

        val legacyPing = Base64.encodeToString(
            """{"datDir":"/data","configPath":"/data/config.json","timeout":4,"url":"https://example.com/generate_204","proxy":"socks5://127.0.0.1:19090"}"""
                .toByteArray(),
            Base64.NO_WRAP
        )
        val pingResult = bridge.ping(legacyPing)
        assertTrue(pingResult.success)
        assertEquals(123L, pingResult.delayMs)
        val pingJson = org.json.JSONObject(ModernLibXray.lastRequest!!)
        assertEquals("ping", pingJson.getString("method"))
        assertEquals(4, pingJson.getJSONObject("payload").getInt("timeout"))

        val batchResult = bridge.pingBatch(
            configs = listOf(
                XrayPingBatchConfig("/data/one.json", "proxy"),
                XrayPingBatchConfig("/data/two.json", "proxy")
            ),
            timeoutSeconds = 3,
            testUrl = "https://example.com/generate_204"
        )
        assertTrue(batchResult.success)
        assertEquals(2, batchResult.results.size)
        val batchJson = org.json.JSONObject(ModernLibXray.lastRequest!!)
        assertEquals("pingBatch", batchJson.getString("method"))
        assertEquals(2, batchJson.getJSONObject("payload").getJSONArray("configs").length())
        assertEquals(3, batchJson.getJSONObject("payload").getInt("timeout"))

        assertTrue(bridge.getState())
        assertTrue(bridge.stop().success)
    }

    private interface ModernController {
        fun protectFd(fd: Long): Boolean
    }

    private object ModernLibXray {
        var lastRequest: String? = null

        @JvmStatic
        fun invoke(request: String): String {
            lastRequest = request
            val method = org.json.JSONObject(request).getString("method")
            return when (method) {
                "ping" -> """{"success":true,"data":{"delay":123},"error":""}"""
                "pingBatch" -> """{"success":true,"data":{"results":[{"success":true,"delay":123},{"success":false,"error":"timeout"}]},"error":""}"""
                "getXrayState" -> """{"success":true,"data":{"running":true},"error":""}"""
                else -> """{"success":true,"data":{},"error":""}"""
            }
        }

        @JvmStatic
        fun registerDialerController(controller: ModernController) = Unit

        @JvmStatic
        fun registerListenerController(controller: ModernController) = Unit
    }

    @Test
    fun pingResultFromBase64_successParsesDelay() {
        val encoded = Base64.encodeToString(
            """{"success":true,"data":321}""".toByteArray(),
            Base64.NO_WRAP
        )

        val result = XrayPingResult.fromBase64(encoded)

        assertTrue(result.success)
        assertEquals(321L, result.delayMs)
        assertEquals(null, result.error)
    }

    @Test
    fun pingResultFromBase64_errorParsesErrorAndDelayCode() {
        val encoded = Base64.encodeToString(
            """{"success":false,"data":10000,"error":"network unreachable"}""".toByteArray(),
            Base64.NO_WRAP
        )

        val result = XrayPingResult.fromBase64(encoded)

        assertFalse(result.success)
        assertEquals(10000L, result.delayMs)
        assertEquals("network unreachable", result.error)
    }
}
