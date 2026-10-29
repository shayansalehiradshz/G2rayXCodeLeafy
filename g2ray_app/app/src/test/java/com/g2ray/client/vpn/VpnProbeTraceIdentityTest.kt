package com.g2ray.client.vpn

import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import okhttp3.Connection
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class VpnProbeTraceIdentityTest {
    private fun connection(): Connection = java.lang.reflect.Proxy.newProxyInstance(
        Connection::class.java.classLoader, arrayOf(Connection::class.java)
    ) { _, method, _ -> when (method.name) {
        "protocol" -> Protocol.HTTP_1_1
        "equals" -> true
        "hashCode" -> 1
        else -> null
    } } as Connection

    @Test fun separateTracesShareRunAndNeverReuseAnAssignedRequestId() {
        val identity = VpnProbeTraceIdentity()
        val records = (0..2).map {
            val trace = VpnProbeTimingTrace({ 0L }, identity)
            val call = OkHttpClient().newCall(Request.Builder().url("https://example.com/").build())
            trace.listenerFor("example.com").callStart(call)
            trace.summaries().single()
        }
        records.forEachIndexed { id, record ->
            assertTrue(record.contains("run_id=${identity.runId} request_id=$id "))
        }
        assertNotEquals(identity.runId, VpnProbeTraceIdentity().runId)
    }

    @Test fun concurrentAssignmentsAndOverflowCannotAliasCalls() {
        val identity = VpnProbeTraceIdentity()
        val ids = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val workers = List(8) { thread { repeat(100) { ids.add(requireNotNull(identity.nextRequestId())) } } }
        workers.forEach { it.join() }
        assertEquals(800, ids.toSet().size)
        val counter = VpnProbeTraceIdentity::class.java.getDeclaredField("requests").apply { isAccessible = true }
            .get(identity) as AtomicLong
        counter.set(Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE - 1, identity.nextRequestId())
        assertNull(identity.nextRequestId())
        assertNull(identity.nextRequestId())
    }

    @Test fun connectionIdentityDoesNotDependOnHashOrEqualsAndIsReleased() {
        val identity = VpnProbeTraceIdentity()
        val first = connection()
        val second = connection()
        assertEquals(first, second)
        assertNotEquals(identity.connectionId(first), identity.connectionId(second))
        assertEquals(1, identity.connectionId(first))
        repeat(126) { assertNotNull(identity.connectionId(connection())) }
        assertNull(identity.connectionId(connection()))
        assertTrue(identity.connectionsCapped)
        assertEquals(1, identity.connectionId(first))
        identity.releaseConnections()
        assertNull(identity.connectionId(first))
    }
}
