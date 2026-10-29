package com.g2ray.client.vpn

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class VpnProbeTimingTraceTest {
    private val call = OkHttpClient().newCall(Request.Builder().url("https://www.gstatic.com/generate_204").build())
    private val address = InetSocketAddress.createUnresolved("192.0.2.1", 443)

    @Test
    fun distinguishesDnsTcpTlsAndResponseTiming() {
        var now = 100L
        val trace = VpnProbeTimingTrace { now }
        val listener = trace.listenerFor(call.request().url.host)
        listener.callStart(call)
        now = 110L
        listener.dnsStart(call, "www.gstatic.com")
        now = 160L
        listener.dnsEnd(call, "www.gstatic.com", emptyList())
        listener.connectStart(call, address, Proxy.NO_PROXY)
        now = 190L
        listener.secureConnectStart(call)
        now = 290L
        listener.secureConnectEnd(call, null)
        listener.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_2)
        now = 300L
        listener.requestHeadersEnd(call, call.request())
        now = 490L
        listener.responseHeadersStart(call)
        now = 500L
        listener.callEnd(call)
        now = 2_000L

        val result = trace.summaries().single()
        assertTrue(result.contains("outcome=completed elapsed_ms=400"))
        assertTrue(result.contains("dns_start:10,dns_end:60,connect_start:60,tls_start:90,tls_end:190"))
        assertTrue(result.contains("request_sent:200,response_headers:390,completed:400"))
    }

    @Test
    fun labelsExplicitCancellationWithoutInferringAnEndpointFailure() {
        val trace = VpnProbeTimingTrace { 0L }
        val listener = trace.listenerFor("www.gstatic.com")
        listener.callStart(call)
        call.cancel()
        listener.callFailed(call, IOException("Canceled"))

        val result = trace.summaries().single()
        assertTrue(result.contains("call_cancelled=true"))
        assertTrue(result.contains("outcome=failed_IOException"))
    }

    @Test
    fun recordsAddressFamilyCountsWithoutLoggingAddresses() {
        val ipv4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
        val ipv6 = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })
        val trace = VpnProbeTimingTrace { 0L }
        val listener = trace.listenerFor("www.gstatic.com")
        listener.callStart(call)
        listener.dnsEnd(call, "www.gstatic.com", listOf(ipv4, ipv6))
        listener.connectStart(call, InetSocketAddress(ipv6, 443), Proxy.NO_PROXY)

        val result = trace.summaries().single()
        assertTrue(result.contains("dns_last_ipv4_count=1 dns_last_ipv6_count=1 connect_last_family=ipv6"))
        assertFalse(result.contains(ipv4.hostAddress!!))
        assertFalse(result.contains(ipv6.hostAddress!!))
    }

    @Test
    fun pendingRequestsAreNotReportedAsCompletedOrZeroLatency() {
        var now = 0L
        val trace = VpnProbeTimingTrace { now }
        val listener = trace.listenerFor("www.gstatic.com")
        listener.callStart(call)
        listener.dnsStart(call, "www.gstatic.com")
        now = 4_500L

        val result = trace.summaries().single()
        assertTrue(result.contains("outcome=pending elapsed_ms=4500"))
        assertFalse(result.contains("completed"))
        assertFalse(result.contains("dns_end"))
    }

    @Test
    fun neverRecordsAddressesCredentialsPathsOrExceptionMessages() {
        val trace = VpnProbeTimingTrace { 0L }
        val listener = trace.listenerFor("www.gstatic.com")
        val privateCall = OkHttpClient().newCall(Request.Builder()
            .url("https://test-user:test-secret@www.gstatic.com/private-path?token=test-token")
            .header("Authorization", "Bearer test-secret")
            .build())
        listener.callStart(privateCall)
        listener.connectStart(privateCall, address, Proxy.NO_PROXY)
        listener.requestHeadersEnd(privateCall, privateCall.request())
        listener.callFailed(privateCall, IOException("private-path test-secret test-token ${address.hostString}"))

        val result = trace.summaries().single()
        assertTrue(result.contains("outcome=failed_IOException"))
        listOf("test-user", "test-secret", "private-path", "test-token", address.hostString, "Bearer").forEach {
            assertFalse("Trace leaked $it", result.contains(it))
        }
    }

    @Test
    fun concurrentProbeCallsHaveIndependentRecords() {
        val trace = VpnProbeTimingTrace { 100L }
        val first = trace.listenerFor("first.example")
        val second = trace.listenerFor("second.example")
        first.callStart(call)
        first.dnsStart(call, "first.example")
        second.callStart(call)
        second.callEnd(call)

        val results = trace.summaries()
        assertEquals(2, results.size)
        assertTrue(results[0].contains("endpoint=first.example outcome=pending"))
        assertFalse(results[0].contains("completed"))
        assertTrue(results[1].contains("endpoint=second.example outcome=completed"))
        assertFalse(results[1].contains("dns_start"))
    }

    @Test
    fun capsEventsWithoutMisreportingTerminalOutcome() {
        val trace = VpnProbeTimingTrace { 100L }
        val listener = trace.listenerFor("www.gstatic.com")
        listener.callStart(call)
        repeat(1_000) { listener.dnsStart(call, "www.gstatic.com") }
        listener.callEnd(call)

        val result = trace.summaries().single()
        assertTrue(result.contains("outcome=completed"))
        assertTrue(result.contains("events_truncated=true"))
        assertEquals(64, result.substringAfter("stages=").split(',').size)
    }

    @Test
    fun unavailableDiagnosticClockDoesNotThrowFromHttpCallbacks() {
        val trace = VpnProbeTimingTrace { throw IllegalStateException("Clock unavailable") }
        val listener = trace.listenerFor("www.gstatic.com")
        listener.callStart(call)
        listener.dnsStart(call, "www.gstatic.com")
        listener.callFailed(call, IOException("Network unavailable"))

        assertTrue(trace.summaries().single().contains("outcome=failed_IOException elapsed_ms=unknown"))
    }

    @Test
    fun capsRequestsAsWellAsEventsAndRedactsUntrustedLabels() {
        val trace = VpnProbeTimingTrace { 0L }
        repeat(1_000) {
            trace.listenerFor("https://user:secret@example.com/private?token=secret").callStart(call)
        }
        val results = trace.summaries()
        assertEquals(64, results.size)
        assertTrue(results.all { it.contains("endpoint=redacted") && it.contains("requests_dropped=936") })
        assertFalse(results.joinToString().contains("secret"))
    }

    @Test
    fun responseBodyCompletionIsSeparateFromHeadersAndHttpValidity() {
        var now = 100L
        val trace = VpnProbeTimingTrace { now }
        val listener = trace.listenerFor("synthetic")
        listener.callStart(call)
        now = 120L
        listener.requestHeadersEnd(call, call.request())
        now = 150L
        listener.responseHeadersStart(call)
        listener.responseHeadersEnd(call, okhttp3.Response.Builder().request(call.request())
            .protocol(Protocol.HTTP_2).code(503).message("private error").build())
        now = 500L
        listener.responseBodyEnd(call, 123L)
        listener.callEnd(call)
        val result = trace.summaries().single()
        assertTrue(result.contains("http=503 response_bytes=123"))
        assertTrue(result.contains("request_start_ms=100 request_end_ms=500"))
        assertTrue(result.contains("response_headers:50,response_headers_end:50,response_body_end:400"))
        assertFalse(result.contains("private error"))
    }

    @Test
    fun realPooledConnectionIsDistinguishedFromFreshConnectWithoutClaimingXrayRoute() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val failure = AtomicReference<Throwable>()
            val responder = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader()
                        repeat(2) {
                            while (!input.readLine().isNullOrEmpty()) Unit
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: keep-alive\r\n\r\nx".toByteArray())
                                flush()
                            }
                        }
                    }
                }.onFailure(failure::set)
            }
            val trace = VpnProbeTimingTrace { System.nanoTime() / 1_000_000L }
            val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS)
                .eventListenerFactory { trace.listenerFor("fixture") }.build()
            try {
                repeat(2) {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/private-token").build()).execute()
                        .use { response -> assertTrue(response.body!!.string().isNotBlank()) }
                }
                val results = trace.summaries()
                assertEquals(2, results.size)
                assertTrue(results[0].contains("acquired_without_connect=false"))
                assertTrue(results[1].contains("acquired_without_connect=true"))
                fun connectionId(result: String) = result.substringAfter("connection_id=").substringBefore(' ')
                assertEquals(connectionId(results[0]), connectionId(results[1]))
                assertFalse(results.joinToString().contains("private-token"))
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
                responder.join(5_000)
                assertFalse("Local fixture did not terminate", responder.isAlive)
                failure.get()?.let { throw AssertionError("Local fixture failed", it) }
            }
        }
    }
}
