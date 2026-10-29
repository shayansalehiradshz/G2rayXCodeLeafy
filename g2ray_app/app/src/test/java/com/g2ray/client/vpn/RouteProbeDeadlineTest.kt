package com.g2ray.client.vpn

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real loopback HTTP body reads, without an Internet endpoint or native core. */
@RunWith(RobolectricTestRunner::class)
class RouteProbeDeadlineTest {
    @Test
    fun stalledSuccessBodiesRespectReadDeadlinesOnBothPaths() = runBlocking {
        for (fullVpn in listOf(false, true)) {
            StalledBodyServer().use { server ->
                val client = clientFor(server).readTimeout(300, TimeUnit.MILLISECONDS).build()
                val startedAt = System.nanoTime()
                val result = probe(fullVpn, client)
                assertFalse("Success headers with a stalled body verified fullVpn=$fullVpn", result.success)
                assertTrue("The fixture must actually deliver headers", server.headersSent.get() >= 2)
                assertTrue("The body read must honor the client timeout: ${result.error}",
                    result.error?.let { it.contains("timeout", true) || it.contains("timed out", true) } == true)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 5_000L)
            }
        }
    }

    @Test
    fun cancellationInterruptsBodyReadsOnBothPaths() = runBlocking {
        for (fullVpn in listOf(false, true)) {
            StalledBodyServer().use { server ->
                val client = clientFor(server).readTimeout(10, TimeUnit.SECONDS).build()
                val check = async(start = CoroutineStart.UNDISPATCHED) { probe(fullVpn, client) }
                try {
                    assertTrue(withContext(Dispatchers.IO) { server.stalledBodySent.await(3, TimeUnit.SECONDS) })
                    assertTrue("Headers alone must not complete the health check", check.isActive)
                    check.cancelAndJoin()
                    assertTrue(check.isCancelled)
                } finally {
                    check.cancelAndJoin()
                }
            }
        }
    }

    private fun clientFor(server: StalledBodyServer): OkHttpClient.Builder = OkHttpClient.Builder()
        .callTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val original = chain.request()
            val path = when (original.url.host) {
                "www.apple.com" -> "/success"
                "www.gstatic.com" -> "/unavailable"
                else -> "/generate_204"
            }
            // Redirect only the fixture's socket to loopback; preserve the
            // logical request identity, like a DNS/socket-level test adapter.
            chain.proceed(original.newBuilder().url("http://127.0.0.1:${server.port}$path").build())
                .newBuilder().request(original).build()
        }

    private suspend fun probe(fullVpn: Boolean, client: OkHttpClient): RunningRouteResult {
        if (fullVpn) {
            val probe = OkHttpVpnDataPathProbe { true }
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

    private class StalledBodyServer : Closeable {
        private val listener = ServerSocket(0, 3, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newFixedThreadPool(4)
        private val released = CountDownLatch(1)
        val headersSent = AtomicInteger()
        val stalledBodySent = CountDownLatch(1)
        val port: Int get() = listener.localPort

        private val acceptTask = workers.submit {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                workers.execute {
                    socket.use {
                        try {
                            it.soTimeout = 1_000
                            val request = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val requestLine = request.readLine()
                            while (!request.readLine().isNullOrEmpty()) Unit
                            val stalled = requestLine?.contains(" /success ") == true
                            val response = when {
                                stalled -> "HTTP/1.1 200 OK\r\nContent-Length: 512\r\nConnection: close\r\n\r\npartial"
                                requestLine?.contains(" /unavailable ") == true ->
                                    "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                else -> "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                            }
                            it.getOutputStream().apply {
                                write(response.toByteArray(Charsets.US_ASCII))
                                flush()
                            }
                            headersSent.incrementAndGet()
                            if (stalled) {
                                stalledBodySent.countDown()
                                released.await(8, TimeUnit.SECONDS)
                            }
                        } catch (_: IOException) {
                            // Cancellation closes the client's owned connection.
                        }
                    }
                }
            }
        }

        override fun close() {
            listener.close()
            released.countDown()
            try {
                // Finish accepting before retiring the executor: an already
                // accepted socket must still be dispatched and closed.
                acceptTask.get(5, TimeUnit.SECONDS)
            } finally {
                workers.shutdown()
                check(workers.awaitTermination(5, TimeUnit.SECONDS)) { "Loopback fixture did not stop" }
            }
        }
    }
}
