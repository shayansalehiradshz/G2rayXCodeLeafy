package com.g2ray.client.vpn

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Owned response fixtures; no DNS, Internet traffic, or native core is used. */
@RunWith(RobolectricTestRunner::class)
class RouteProbeBodyTest {
    @Test
    fun physicalDefaultNetworkCannotVerifyTheVpnOrSendHealthRequests() = runBlocking {
        val requests = AtomicInteger()
        val result = probe(true, defaultNetworkIsVpn = { false }) {
            requests.incrementAndGet()
            "Complete fixture response".toResponseBody()
        }
        assertFalse("Direct physical-network replies cannot prove the VPN", result.success)
        org.junit.Assert.assertEquals(0, requests.get())
    }

    @Test
    fun unknownDefaultNetworkCannotVerifyTheVpn() = runBlocking {
        val requests = AtomicInteger()
        val result = probe(true, defaultNetworkIsVpn = { null }) {
            requests.incrementAndGet()
            "Complete fixture response".toResponseBody()
        }
        assertFalse(result.success)
        org.junit.Assert.assertEquals(0, requests.get())
    }

    @Test
    fun startupWaitsForVpnDefaultRoutingBeforeSendingItsHealthRequests() = runBlocking {
        val vpnDefault = AtomicBoolean(false)
        val sentBeforeVpn = AtomicBoolean(false)
        val ready = launch { delay(150L); vpnDefault.set(true) }
        val result = probe(true, defaultNetworkIsVpn = vpnDefault::get) {
            if (!vpnDefault.get()) sentBeforeVpn.set(true)
            "Complete fixture response".toResponseBody()
        }
        ready.join()
        assertTrue(result.success)
        assertFalse("Startup must not probe on the physical default route", sentBeforeVpn.get())
    }

    @Test
    fun losingVpnDefaultRoutingDuringAReplyCannotVerifyTheTunnel() = runBlocking {
        val vpnDefault = AtomicBoolean(true)
        val result = probe(true, defaultNetworkIsVpn = vpnDefault::get) {
            vpnDefault.set(false)
            "Complete fixture response".toResponseBody()
        }
        assertFalse(result.success)
    }

    @Test
    fun successfulHeadersWithTruncatedBodiesCannotVerifyEitherPath() = runBlocking {
        for (fullVpn in listOf(false, true)) {
            val result = probe(fullVpn) {
                object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = 512L
                    private val data: BufferedSource = object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long =
                            throw IOException("Fixture body ended before Content-Length")
                        override fun timeout() = Timeout.NONE
                        override fun close() = Unit
                    }.buffer()
                    override fun source(): BufferedSource = data
                }
            }
            assertFalse("HTTP headers alone verified fullVpn=$fullVpn", result.success)
            assertTrue(result.error?.contains("Fixture body ended") == true)
        }
    }

    @Test
    fun oversizedSuccessBodiesCannotVerifyEitherPath() = runBlocking {
        for (fullVpn in listOf(false, true)) {
            val result = probe(fullVpn) { ByteArray(65_537).toResponseBody() }
            assertFalse("Unbounded health response accepted for fullVpn=$fullVpn", result.success)
            assertTrue(result.error?.contains("too large") == true)
        }
    }

    @Test
    fun unknownLengthBodiesRemainBoundedOnBothPaths() = runBlocking {
        for (fullVpn in listOf(false, true)) {
            val result = probe(fullVpn) {
                object : ResponseBody() {
                    private val data = Buffer().write(ByteArray(65_537))
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = -1L
                    override fun source(): BufferedSource = data
                }
            }
            assertFalse(result.success)
            assertTrue(result.error?.contains("too large") == true)
        }
    }

    @Test
    fun earlyEofBeforeDeclaredLengthCannotVerifyEitherPath() = runBlocking {
        for (fullVpn in listOf(false, true)) {
            val result = probe(fullVpn) {
                object : ResponseBody() {
                    private val data = Buffer().writeUtf8("partial")
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = 512L
                    override fun source(): BufferedSource = data
                }
            }
            assertFalse(result.success)
            assertTrue(result.error?.contains("incomplete") == true)
        }
    }

    @Test
    fun completeSmallSuccessBodiesVerifyBothPaths() = runBlocking {
        for (fullVpn in listOf(false, true)) {
            assertTrue(probe(fullVpn) { "Complete fixture response".toResponseBody() }.success)
        }
    }

    @Test
    fun empty204ResponsesStillVerifyBothPaths() = runBlocking {
        for (fullVpn in listOf(false, true)) {
            assertTrue(probe(fullVpn, code = 204) { ByteArray(0).toResponseBody() }.success)
        }
    }

    @Test
    fun completeBodiesAtTheExactLimitStillVerifyBothPaths() = runBlocking {
        for (fullVpn in listOf(false, true)) {
            for (declaredLength in listOf(-1L, 65_536L)) {
                assertTrue(probe(fullVpn) {
                    object : ResponseBody() {
                        private val data = Buffer().write(ByteArray(65_536))
                        override fun contentType(): MediaType? = null
                        override fun contentLength() = declaredLength
                        override fun source(): BufferedSource = data
                    }
                }.success)
            }
        }
    }

    @Test
    fun completeUnknownLengthEmptyBodiesStillVerifyBothPaths() = runBlocking {
        for (fullVpn in listOf(false, true)) {
            assertTrue(probe(fullVpn) {
                object : ResponseBody() {
                    private val data = Buffer()
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = -1L
                    override fun source(): BufferedSource = data
                }
            }.success)
        }
    }

    private suspend fun probe(
        fullVpn: Boolean,
        code: Int? = null,
        defaultNetworkIsVpn: () -> Boolean? = { true },
        body: () -> ResponseBody
    ): RunningRouteResult {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_2)
                .code(code ?: if (chain.request().url.encodedPath.endsWith("/generate_204")) 204 else 200)
                .message("Fixture").body(body()).build()
        }.build()
        if (fullVpn) {
            val probe = OkHttpVpnDataPathProbe(defaultNetworkIsVpn)
            OkHttpVpnDataPathProbe::class.java.getDeclaredField("cachedClient").apply {
                isAccessible = true
                set(probe, client)
            }
            return try { probe.probe() } finally { probe.reset() }
        }
        val probe = SocksRunningRouteProbe()
        SocksRunningRouteProbe::class.java.getDeclaredField("cachedClient").apply {
            isAccessible = true
            set(probe, client)
        }
        SocksRunningRouteProbe::class.java.getDeclaredField("cachedSocksPort").apply {
            isAccessible = true
            setInt(probe, 19090)
        }
        return try { probe.probe(19090) } finally { probe.reset() }
    }
}
