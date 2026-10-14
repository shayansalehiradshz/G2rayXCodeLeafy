package com.g2ray.client.vpn

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Captures bounded timing events; HTTP callbacks never perform diagnostic I/O. */
internal class VpnProbeTimingTrace(private val nowMs: () -> Long) {
    private val requests = ConcurrentLinkedQueue<RequestTiming>()

    fun listenerFor(endpoint: String): EventListener = RequestTiming(endpoint).also(requests::add)

    fun summaries(): List<String> = requests.map { it.summary() }

    private inner class RequestTiming(private val endpoint: String) : EventListener() {
        private val events = ConcurrentLinkedQueue<String>()
        private val eventCount = AtomicInteger(0)
        @Volatile private var startedAt: Long? = null
        @Volatile private var finishedAt: Long? = null
        @Volatile private var outcome = "pending"

        override fun callStart(call: Call) = record("call_start")
        override fun dnsStart(call: Call, domainName: String) = record("dns_start")
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = record("dns_end")
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = record("connect_start")
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

        override fun requestHeadersEnd(call: Call, request: okhttp3.Request) = record("request_sent")
        override fun responseHeadersStart(call: Call) = record("response_headers")
        override fun callEnd(call: Call) = finish("completed")
        override fun callFailed(call: Call, ioe: IOException) = finish("failed_${ioe.javaClass.simpleName}")

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
                "events_truncated=${eventCount.get() > MAX_EVENTS_PER_REQUEST} stages=$stages"
        }
    }

    private companion object {
        const val MAX_EVENTS_PER_REQUEST = 64
    }
}
