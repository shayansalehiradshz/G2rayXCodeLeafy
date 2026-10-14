package com.g2ray.client.network

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal suspend fun <T> interruptibleDnsLookup(block: () -> T): T {
    val result = try {
        runInterruptible(Dispatchers.IO, block)
    } catch (error: Exception) {
        // OkHttp requires IOException, so the synchronous API wraps interrupted
        // waits. Restore coroutine cancellation before recovery can retry them.
        currentCoroutineContext().ensureActive()
        throw error
    }
    currentCoroutineContext().ensureActive()
    return result
}

/** Bounded physical-network DNS for synchronous OkHttp and interruptible coroutine callers. */
internal class NetworkDnsResolver(
    private val timeoutMs: Long = 10_000L,
    private val legacyExecutor: ExecutorService = sharedLegacyExecutor
) {
    init {
        require(timeoutMs > 0L) { "DNS deadline must be positive" }
    }

    fun lookup(network: Network?, hostname: String): List<InetAddress> {
        if (Thread.currentThread().isInterrupted) {
            throw failure(hostname, "interrupted", InterruptedException("DNS caller was interrupted"))
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) lookupModern(network, hostname)
        else lookupLegacy(network, hostname)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun lookupModern(network: Network?, hostname: String): List<InetAddress> {
        val result = CompletableFuture<List<InetAddress>>()
        val cancellation = CancellationSignal()
        try {
            DnsResolver.getInstance().query(network, hostname, DnsResolver.FLAG_EMPTY,
                callbackExecutor, cancellation, object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (rcode == 0 && answer.isNotEmpty()) result.complete(answer)
                        else result.completeExceptionally(failure(hostname, "returned no usable answer (rcode=$rcode)"))
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        result.completeExceptionally(failure(hostname, "failed", error))
                    }
                })
        } catch (error: Exception) {
            runCatching { cancellation.cancel() }
            result.completeExceptionally(error)
        }
        return await(hostname, result) { cancellation.cancel() }
    }

    private fun lookupLegacy(network: Network?, hostname: String): List<InetAddress> {
        val result = try {
            legacyExecutor.submit<List<InetAddress>> {
                (network?.getAllByName(hostname) ?: InetAddress.getAllByName(hostname)).toList()
            }
        } catch (busy: RejectedExecutionException) {
            throw failure(hostname, "is busy; retry when the physical network is usable", busy)
        }
        return await(hostname, result) { result.cancel(true) }
    }

    private fun await(hostname: String, result: Future<List<InetAddress>>, cancel: () -> Unit): List<InetAddress> {
        try {
            val addresses = result.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (addresses.isEmpty()) throw failure(hostname, "returned no addresses")
            return addresses
        } catch (timeout: TimeoutException) {
            runCatching { cancel() }
            result.cancel(false)
            throw failure(hostname, "timed out", timeout)
        } catch (interrupted: InterruptedException) {
            runCatching { cancel() }
            result.cancel(false)
            Thread.currentThread().interrupt()
            throw failure(hostname, "was interrupted", interrupted)
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            if (cause is Error) throw cause
            if (cause is UnknownHostException) throw cause
            throw failure(hostname, "failed", cause)
        }
    }

    private fun failure(hostname: String, reason: String, cause: Throwable? = null): UnknownHostException =
        UnknownHostException("DNS for $hostname $reason").apply { if (cause != null) initCause(cause) }

    internal companion object {
        private val callbackExecutor = Executor { it.run() }
        private val sharedLegacyExecutor by lazy { createLegacyExecutor() }

        fun createLegacyExecutor(maxWorkers: Int = 4): ThreadPoolExecutor {
            require(maxWorkers in 1..4)
            val ids = AtomicInteger()
            // Native getaddrinfo may ignore interruption on older Android.
            // No queue and a fixed ceiling prevent stale lookups accumulating.
            return ThreadPoolExecutor(0, maxWorkers, 30, TimeUnit.SECONDS, SynchronousQueue(),
                { task -> Thread(task, "g2ray-dns-${ids.incrementAndGet()}").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy())
        }
    }
}
