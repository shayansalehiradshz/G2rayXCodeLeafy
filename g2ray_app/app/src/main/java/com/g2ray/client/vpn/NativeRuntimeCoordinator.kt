package com.g2ray.client.vpn

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Owns libXray's process-global controllers and runtime, not just an invoke call. */
internal class NativeRuntimeCoordinator {
    private val gate = Mutex()
    private val waitingVpnStarts = AtomicInteger()

    fun hasWaitingVpnStart(): Boolean = waitingVpnStarts.get() > 0

    class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    fun tryAcquireProbe(): Lease? {
        if (waitingVpnStarts.get() > 0) return null
        val owner = Any()
        if (!gate.tryLock(owner)) return null
        if (waitingVpnStarts.get() > 0) {
            gate.unlock(owner)
            return null
        }
        return Lease { gate.unlock(owner) }
    }

    suspend fun acquireVpn(): Lease {
        waitingVpnStarts.incrementAndGet()
        try {
            val owner = Any()
            gate.lock(owner)
            return Lease { gate.unlock(owner) }
        } finally {
            waitingVpnStarts.decrementAndGet()
        }
    }

    companion object {
        val process = NativeRuntimeCoordinator()
    }
}
