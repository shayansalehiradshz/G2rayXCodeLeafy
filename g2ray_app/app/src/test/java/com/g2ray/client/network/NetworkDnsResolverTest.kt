package com.g2ray.client.network

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM], shadows = [
    NetworkDnsResolverTest.ResolverShadow::class,
    NetworkDnsResolverTest.NetworkShadow::class
])
class NetworkDnsResolverTest {
    @Before
    fun setUp() {
        queries.clear()
        answers = listOf(InetAddress.getByName("203.0.113.1"), InetAddress.getByName("2001:db8::1"))
        responseCode = 0
        pending = false
        platformError = false
        legacyCalls.set(0)
        legacyRelease = CountDownLatch(0)
    }

    @After
    fun tearDown() {
        legacyRelease.countDown()
        Thread.interrupted()
    }

    @Test
    @Config(sdk = [29, 34, 35])
    fun modernQueryUsesTheExactPhysicalNetworkAndKeepsBothAddressFamilies() {
        val network = Shadow.newInstanceOf(Network::class.java)
        assertEquals(answers, NetworkDnsResolver().lookup(network, "front.example"))
        val query = queries.remove()
        assertSame(network, query.network)
        assertEquals("front.example", query.hostname)
        assertEquals(DnsResolver.FLAG_EMPTY, query.flags)
        assertFalse(query.cancellation.isCanceled)
        assertEquals(0, legacyCalls.get())
    }

    @Test
    fun nullNetworkIsPreservedForCallersWithoutAnActiveVpn() {
        assertEquals(answers, NetworkDnsResolver().lookup(null, "front.example"))
        assertEquals(null, queries.remove().network)
    }

    @Test
    fun emptyAnswersAndDnsFailureCodesAreNotSuccessfulLookups() {
        for ((values, code) in listOf(emptyList<InetAddress>() to 0, answers to 3)) {
            answers = values
            responseCode = code
            assertThrows(UnknownHostException::class.java) { NetworkDnsResolver().lookup(null, "front.example") }
        }
    }

    @Test
    fun platformErrorsRemainRetryableUnknownHostFailuresWithTheirCause() {
        platformError = true
        val failure = assertThrows(UnknownHostException::class.java) {
            NetworkDnsResolver().lookup(null, "front.example")
        }
        assertTrue(failure.cause is DnsResolver.DnsException)
    }

    @Test
    fun modernDeadlineCancelsTheNativeQueryAndIgnoresLateAnswers() {
        pending = true
        val failure = assertThrows(UnknownHostException::class.java) {
            NetworkDnsResolver(timeoutMs = 50).lookup(null, "stalled.example")
        }
        assertTrue(failure.cause is TimeoutException)
        val query = queries.remove()
        assertTrue(query.cancellation.isCanceled)
        query.executor.execute { query.callback.onAnswer(answers, 0) }
    }

    @Test
    fun coroutineCancellationCancelsTheNativeQueryAndDoesNotLeakThreadInterruption() = runBlocking {
        pending = true
        val resolver = NetworkDnsResolver(timeoutMs = 10_000)
        val interruptedAfterLookup = AtomicBoolean(true)
        val job = launch(Dispatchers.IO) {
            try {
                interruptibleDnsLookup { resolver.lookup(null, "stalled.example") }
            } finally {
                interruptedAfterLookup.set(Thread.currentThread().isInterrupted)
            }
        }
        try {
            val query = queries.poll(5, TimeUnit.SECONDS) ?: error("Native query was not started")
            job.cancel()
            withTimeout(1_500) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(query.cancellation.isCanceled)
            query.executor.execute { query.callback.onAnswer(answers, 0) }
        } finally {
            withTimeout(5_000) { job.cancelAndJoin() }
        }
        assertFalse(interruptedAfterLookup.get())
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun alreadyInterruptedCallDoesNotLaunchAnotherNativeQuery() {
        Thread.currentThread().interrupt()
        try {
            val failure = assertThrows(UnknownHostException::class.java) {
                NetworkDnsResolver().lookup(null, "front.example")
            }
            assertTrue(failure.cause is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(queries.isEmpty())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun aLookupThatReturnsAfterCancellationCannotResumeVpnStartup() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val usedResult = AtomicBoolean(false)
        val job = launch(Dispatchers.IO) {
            interruptibleDnsLookup {
                entered.countDown()
                try { release.await() } catch (_: InterruptedException) { }
                "late numeric backup"
            }
            usedResult.set(true)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            withTimeout(1_500) { job.join() }
            assertFalse(usedResult.get())
        } finally {
            release.countDown()
            withTimeout(5_000) { job.cancelAndJoin() }
        }
    }

    @Test
    @Config(sdk = [24, 28])
    fun legacyLookupPreservesThePhysicalNetworkAndAddressFamilies() {
        val executor = NetworkDnsResolver.createLegacyExecutor(1)
        try {
            val network = Shadow.newInstanceOf(Network::class.java)
            assertEquals(answers, NetworkDnsResolver(legacyExecutor = executor).lookup(network, "front.example"))
            assertEquals(1, legacyCalls.get())
            assertTrue(queries.isEmpty())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    @Config(sdk = [24, 28])
    fun stuckLegacyLookupCannotGrowTheWorkerPoolOrQueueMoreLookups() {
        val executor = NetworkDnsResolver.createLegacyExecutor(1)
        legacyRelease = CountDownLatch(1)
        try {
            val network = Shadow.newInstanceOf(Network::class.java)
            val resolver = NetworkDnsResolver(timeoutMs = 100, legacyExecutor = executor)
            val timeout = assertThrows(UnknownHostException::class.java) { resolver.lookup(network, "stalled.example") }
            assertTrue(timeout.cause is TimeoutException)
            val busy = assertThrows(UnknownHostException::class.java) { resolver.lookup(network, "other.example") }
            assertTrue(busy.cause is RejectedExecutionException)
            assertEquals(1, executor.largestPoolSize)
            assertEquals(0, executor.queue.size)
            assertEquals(1, legacyCalls.get())
        } finally {
            legacyRelease.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun defaultLegacyWorkerPoolHasAFixedCeilingAndNoBacklog() {
        val executor = NetworkDnsResolver.createLegacyExecutor()
        try {
            assertEquals(4, executor.maximumPoolSize)
            assertEquals(0, executor.queue.remainingCapacity())
            assertEquals(0, executor.poolSize)
        } finally {
            executor.shutdownNow()
        }
    }

    data class Query(val network: Network?, val hostname: String, val flags: Int, val executor: Executor,
                     val cancellation: CancellationSignal, val callback: DnsResolver.Callback<List<InetAddress>>)

    @Implements(DnsResolver::class, minSdk = 29)
    class ResolverShadow {
        companion object {
            @JvmStatic
            @Implementation
            fun getInstance(): DnsResolver = Shadow.newInstanceOf(DnsResolver::class.java)
        }

        @Implementation
        fun query(network: Network?, hostname: String, flags: Int, executor: Executor,
                  cancellation: CancellationSignal, callback: DnsResolver.Callback<List<InetAddress>>) {
            queries += Query(network, hostname, flags, executor, cancellation, callback)
            if (pending) return
            executor.execute {
                if (platformError) callback.onError(DnsResolver.DnsException(DnsResolver.ERROR_SYSTEM, IOException("Resolver failed")))
                else callback.onAnswer(answers, responseCode)
            }
        }
    }

    @Implements(Network::class)
    class NetworkShadow {
        @Implementation
        fun getAllByName(hostname: String): Array<InetAddress> {
            legacyCalls.incrementAndGet()
            // Model old native resolvers that cannot be interrupted.
            while (legacyRelease.count > 0) {
                try { legacyRelease.await(5, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
            }
            return answers.toTypedArray()
        }
    }

    private companion object {
        val queries = LinkedBlockingQueue<Query>()
        val legacyCalls = AtomicInteger()
        var answers = emptyList<InetAddress>()
        var responseCode = 0
        var pending = false
        var platformError = false
        var legacyRelease = CountDownLatch(0)
    }
}
