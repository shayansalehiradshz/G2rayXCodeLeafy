package com.g2ray.client.vpn

import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class VpnDataPathProbeTest {
    @Test
    fun debugClientAttachesTheRequestTimingTagWithoutSharingItWithOtherCalls() = runBlocking {
        val probe = OkHttpVpnDataPathProbe()
        try {
            val method = OkHttpVpnDataPathProbe::class.java.getDeclaredMethod("client").apply { isAccessible = true }
            val client = method.invoke(probe) as OkHttpClient
            val trace = VpnProbeTimingTrace { 0L }
            client.newCall(Request.Builder().url("https://www.gstatic.com/generate_204")
                .tag(VpnProbeTimingTrace::class.java, trace).build())
            client.newCall(Request.Builder().url("https://example.com/").build())

            val records = trace.summaries()
            assertEquals(1, records.size)
            assertEquals(true, records.single().contains("endpoint=www.gstatic.com outcome=pending"))
        } finally {
            probe.reset()
        }
    }

    @Test
    fun resetClosesCachedClientOffCallingThreadAndOnlyOnce() = runBlocking {
        val caller = Thread.currentThread()
        var closedOn: Thread? = null
        var closeCount = 0
        val executor = object : ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, LinkedBlockingQueue()) {
            override fun shutdown() {
                closedOn = Thread.currentThread()
                closeCount++
                super.shutdown()
            }
        }
        val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).build()
        val probe = OkHttpVpnDataPathProbe()
        val field = OkHttpVpnDataPathProbe::class.java.getDeclaredField("cachedClient").apply {
            isAccessible = true
            set(probe, client)
        }

        probe.reset()
        probe.reset()

        assertEquals(1, closeCount)
        assertNotEquals("TLS socket cleanup must not run on the service/UI caller", caller, closedOn)
        assertNull(field.get(probe))
    }
}
