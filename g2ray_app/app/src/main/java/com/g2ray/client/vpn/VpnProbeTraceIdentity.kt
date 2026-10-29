package com.g2ray.client.vpn

import okhttp3.Connection
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Short-lived diagnostic identity, never a network address or Xray outbound tag. */
internal class VpnProbeTraceIdentity {
    val runId: String = UUID.randomUUID().toString()
    private val requests = AtomicLong()
    private val connections = IdentityHashMap<Connection, Int>()
    private var released = false
    @Volatile var connectionsCapped = false
        private set

    fun nextRequestId(): Long? {
        while (true) {
            val current = requests.get()
            if (current == Long.MAX_VALUE) return null
            if (requests.compareAndSet(current, current + 1)) return current
        }
    }

    @Synchronized fun connectionId(connection: Connection): Int? {
        if (released) return null
        connections[connection]?.let { return it }
        if (connections.size >= MAX_CONNECTIONS) {
            connectionsCapped = true
            return null
        }
        return (connections.size + 1).also { connections[connection] = it }
    }

    @Synchronized fun releaseConnections() {
        released = true
        connections.clear()
    }

    private companion object { const val MAX_CONNECTIONS = 128 }
}
