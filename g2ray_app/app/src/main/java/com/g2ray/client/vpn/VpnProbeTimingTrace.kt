package com.g2ray.client.vpn

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Captures bounded timing events; HTTP callbacks never perform diagnostic I/O. */
internal class VpnProbeTimingTrace(private val nowMs: () -> Long) {
    private val requests = ConcurrentLinkedQueue<RequestTiming>()
    private val requestCount = AtomicInteger(0)

    fun listenerFor(endpoint: String): EventListener {
        val id = requestCount.getAndIncrement()
        if (id >= MAX_REQUESTS) return EventListener.NONE
        val label = endpoint.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,253}")) } ?: "redacted"
        return RequestTiming(label, id).also(requests::add)
    }

    fun summaries(): List<String> = requests.map { it.summary() }

    private inner class RequestTiming(private val endpoint: String, private val id: Int) : EventListener() {
        private val events = ConcurrentLinkedQueue<String>()
        private val eventCount = AtomicInteger(0)
        @Volatile private var startedAt: Long? = null
        @Volatile private var finishedAt: Long? = null
        @Volatile private var outcome = "pending"
        @Volatile private var terminalCallCancelled: Boolean? = null
        @Volatile private var dnsLastIpv4Count: Int? = null
        @Volatile private var dnsLastIpv6Count: Int? = null
        @Volatile private var connectLastFamily = "unknown"
        @Volatile private var connectAttempted = false
        @Volatile private var connectionId: Int? = null
        @Volatile private var connectionProtocol: Protocol? = null
        @Volatile private var acquiredWithoutConnect: Boolean? = null
        @Volatile private var responseCode: Int? = null
        @Volatile private var responseBytes: Long? = null

        override fun callStart(call: Call) = record("call_start")
        override fun dnsStart(call: Call, domainName: String) = record("dns_start")
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsLastIpv4Count = inetAddressList.count { it is Inet4Address }
            dnsLastIpv6Count = inetAddressList.count { it is Inet6Address }
            record("dns_end")
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectAttempted = true
            connectLastFamily = when (inetSocketAddress.address) {
                is Inet4Address -> "ipv4"
                is Inet6Address -> "ipv6"
                else -> "unknown"
            }
            record("connect_start")
        }
        override fun secureConnectStart(call: Call) = record("tls_start")
        override fun secureConnectEnd(call: Call, handshake: Handshake?) = record("tls_end")
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
            record("connect_end")

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException
        ) = record("connect_failed_${ioe.javaClass.simpleName}")

        override fun connectionAcquired(call: Call, connection: Connection) {
            // This identifies an OkHttp connection, never an Xray outbound.
            connectionId = System.identityHashCode(connection)
            connectionProtocol = connection.protocol()
            acquiredWithoutConnect = !connectAttempted
            record("connection_acquired")
        }

        override fun requestHeadersEnd(call: Call, request: okhttp3.Request) = record("request_sent")
        override fun requestBodyEnd(call: Call, byteCount: Long) = record("request_body_end")
        override fun responseHeadersStart(call: Call) = record("response_headers")
        override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
            responseCode = response.code
            record("response_headers_end")
        }
        override fun responseBodyEnd(call: Call, byteCount: Long) {
            responseBytes = byteCount
            record("response_body_end")
        }
        override fun callEnd(call: Call) {
            terminalCallCancelled = call.isCanceled()
            finish("completed")
        }

        override fun callFailed(call: Call, ioe: IOException) {
            terminalCallCancelled = call.isCanceled()
            finish("failed_${ioe.javaClass.simpleName}")
        }

        private fun timeOrNull(): Long? = try { nowMs() } catch (_: Exception) { null }

        private fun record(event: String) {
            val now = timeOrNull() ?: return
            if (startedAt == null) startedAt = now
            if (eventCount.getAndIncrement() < MAX_EVENTS_PER_REQUEST) {
                events.add("$event:${(now - startedAt!!).coerceAtLeast(0L)}")
            }
        }

        private fun finish(result: String) {
            record(result)
            finishedAt = timeOrNull()
            outcome = result
        }

        fun summary(): String {
            val start = startedAt
            val end = finishedAt ?: timeOrNull()
            val elapsed = if (start == null || end == null) "unknown" else (end - start).coerceAtLeast(0L).toString()
            val stages = events.joinToString(",")
            return "endpoint=$endpoint outcome=$outcome elapsed_ms=$elapsed " +
                "request_id=$id request_start_ms=${start ?: "unknown"} request_end_ms=${finishedAt ?: "unknown"} " +
                "connection_id=${connectionId ?: "unknown"} protocol=${connectionProtocol ?: "unknown"} " +
                "acquired_without_connect=${acquiredWithoutConnect ?: "unknown"} " +
                "http=${responseCode ?: "unknown"} response_bytes=${responseBytes ?: "unknown"} " +
                "call_cancelled=${terminalCallCancelled ?: "unknown"} " +
                "dns_last_ipv4_count=${dnsLastIpv4Count ?: "unknown"} " +
                "dns_last_ipv6_count=${dnsLastIpv6Count ?: "unknown"} connect_last_family=$connectLastFamily " +
                "requests_dropped=${(requestCount.get() - MAX_REQUESTS).coerceAtLeast(0)} " +
                "events_truncated=${eventCount.get() > MAX_EVENTS_PER_REQUEST} stages=$stages"
        }
    }

    private companion object {
        const val MAX_EVENTS_PER_REQUEST = 64
        const val MAX_REQUESTS = 64
    }
}
