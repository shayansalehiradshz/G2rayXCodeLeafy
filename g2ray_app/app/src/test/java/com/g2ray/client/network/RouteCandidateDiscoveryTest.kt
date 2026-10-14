package com.g2ray.client.network

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody
import okhttp3.MediaType
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
class RouteCandidateDiscoveryTest {
    @Test
    fun discover_cancellationInterruptsAnOutstandingSystemLookup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body("""{"Answer":[]}""".toResponseBody("application/dns-json".toMediaType()))
                    .build()
            }
            .build()
        val discovery = RouteCandidateDiscovery(client) {
            started.complete(Unit)
            release.await()
            emptyList()
        }
        val job = launch { discovery.discover(listOf("space-443.app.github.dev")) }
        try {
            withTimeout(5_000) { started.await() }
            job.cancel()
            val finishedPromptly = withTimeoutOrNull(1_500) { job.join(); true } ?: false
            assertTrue("Cancellation must interrupt the system-lookup wait", finishedPromptly)
        } finally {
            release.countDown()
            withTimeout(5_000) { job.cancelAndJoin() }
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun discover_deadlineAlsoCancelsRequestsStillQueuedInOkHttp() = runBlocking {
        val release = AtomicBoolean(false)
        val calls = CopyOnWriteArrayList<Call>()
        val dispatcher = Dispatcher().apply {
            maxRequests = 1
            maxRequestsPerHost = 1
        }
        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls += call }
            })
            .addInterceptor { chain ->
                while (!chain.call().isCanceled() && !release.get()) Thread.sleep(5)
                throw IOException("Request ended")
            }
            .build()
        val discovery = RouteCandidateDiscovery(client) { emptyList() }
        val job = async { discovery.discover(listOf("space-443.app.github.dev"), listOf("20.85.77.48")) }
        try {
            val result = withTimeout(7_000) { job.await() }
            assertEquals(listOf("20.85.77.48"), result.map { it.address })
            assertEquals(setOf("saved"), result.single().sources)
            assertEquals(7, calls.size)
            assertTrue("Deadlines must cancel both running and queued requests", calls.all { it.isCanceled() })
        } finally {
            release.set(true)
            client.dispatcher.cancelAll()
            withTimeout(5_000) { job.cancelAndJoin() }
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun discover_collectsValidAnswersAndPreservesTheirSources() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val address = if (chain.request().url.host == "cloudflare-dns.com") "20.85.77.48" else "20.120.56.11"
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body("""{"Answer":[{"type":1,"data":"$address"}]}""".toResponseBody("application/dns-json".toMediaType()))
                    .build()
            }
            .build()
        try {
            val discovery = RouteCandidateDiscovery(client) { listOf(InetAddress.getByName("20.125.70.28")) }
            val result = discovery.discover(listOf("space-443.app.github.dev"), listOf("20.85.77.48"))
            assertEquals(listOf("20.125.70.28", "20.85.77.48", "20.120.56.11"), result.map { it.address })
            assertEquals(setOf("system_dns"), result[0].sources)
            assertEquals(setOf("cloudflare_doh", "saved"), result[1].sources)
            assertTrue(result[2].sources.contains("google_doh"))
            assertEquals(6, result[2].sources.size)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun discover_cancellationCancelsInflightDohCallsWithoutWaitingForTheirTimeouts() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = AtomicBoolean(false)
        val calls = CopyOnWriteArrayList<Call>()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls += call }
            })
            .addInterceptor { chain ->
                started.complete(Unit)
                while (!chain.call().isCanceled() && !release.get()) Thread.sleep(5)
                throw IOException("Request ended")
            }
            .build()
        val discovery = RouteCandidateDiscovery(client) { emptyList() }
        val job = launch { discovery.discover(listOf("space-443.app.github.dev")) }
        try {
            withTimeout(5_000) { started.await() }
            job.cancel()
            val finishedPromptly = withTimeoutOrNull(1_500) { job.join(); true } ?: false
            assertTrue("Cancel must reach the in-flight requests, not wait for their timeout", finishedPromptly)
            assertTrue(calls.isNotEmpty())
            assertTrue("Canceled discovery must not leave running or queued HTTP calls", calls.all { it.isCanceled() })
        } finally {
            release.set(true)
            client.dispatcher.cancelAll()
            withTimeout(5_000) { job.cancelAndJoin() }
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun discover_oversizedDohIsRejectedWithoutLosingSystemOrSavedCandidates() = runTest {
        for (unknownLength in listOf(false, true)) {
            val payload = """{"Answer":[{"type":1,"data":"20.85.77.48"}],"padding":"${"x".repeat(65_536)}"}"""
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val body = if (unknownLength) object : ResponseBody() {
                        private val buffer = Buffer().writeUtf8(payload)
                        override fun contentType(): MediaType = "application/dns-json".toMediaType()
                        override fun contentLength(): Long = -1
                        override fun source(): BufferedSource = buffer
                    } else payload.toResponseBody("application/dns-json".toMediaType())
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(body).build()
                }
                .build()
            try {
                val discovery = RouteCandidateDiscovery(client) { listOf(InetAddress.getByName("20.120.56.11")) }
                val result = discovery.discover(listOf("space-443.app.github.dev"), listOf("20.69.79.91"))
                assertEquals("unknownLength=$unknownLength", listOf("20.120.56.11", "20.69.79.91"), result.map { it.address })
                assertEquals(setOf("system_dns"), result[0].sources)
                assertEquals(setOf("saved"), result[1].sources)
            } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun discover_unavailableResolversKeepValidSavedRoutes() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("Resolver unavailable") }
            .build()
        val discovery = RouteCandidateDiscovery(client) { throw IOException("Resolver unavailable") }

        val result = discovery.discover(listOf("space-443.app.github.dev"), listOf("20.85.77.48"))

        assertEquals(listOf("20.85.77.48"), result.map { it.address })
        assertEquals(setOf("saved"), result.single().sources)
    }

    @Test(expected = CancellationException::class)
    fun discover_propagatesSystemResolverCancellation() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"Answer\":[]}".toResponseBody("application/dns-json".toMediaType()))
                    .build()
            }
            .build()
        val discovery = RouteCandidateDiscovery(client) { throw CancellationException("Scan canceled") }

        discovery.discover(listOf("space-443.app.github.dev"), listOf("20.85.77.48"))
    }

    @Test(expected = CancellationException::class)
    fun discover_propagatesDohCancellation() = runTest {
        val client = OkHttpClient.Builder()
            // Async OkHttp reports interceptor failures through IOException.
            .addInterceptor { throw IOException("Scan canceled", CancellationException("Scan canceled")) }
            .build()
        val discovery = RouteCandidateDiscovery(client) { emptyList() }

        discovery.discover(listOf("space-443.app.github.dev"), listOf("20.85.77.48"))
    }

    @Test
    fun extractIpv4Answers_keepsOnlyPublicARecordsAndDeduplicates() {
        val json = """
            {
              "Answer": [
                {"type": 1, "data": "20.85.77.48"},
                {"type": 28, "data": "2606:4700::1"},
                {"type": 1, "data": "10.0.0.1"},
                {"type": 1, "data": "20.85.77.48"},
                {"type": 1, "data": "20.120.56.11"}
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf("20.85.77.48", "20.120.56.11"),
            RouteCandidateDiscovery.extractIpv4Answers(json)
        )
    }

    @Test
    fun discover_withoutHostnames_keepsBoundedValidSavedCandidates() = runTest {
        val discovery = RouteCandidateDiscovery(OkHttpClient()) { emptyList() }

        val candidates = discovery.discover(
            hostnames = emptyList(),
            existingAddresses = listOf("20.85.77.48", "10.0.0.1", "20.85.77.48", "20.120.56.11"),
            maxCandidates = 1
        )

        assertEquals(listOf("20.85.77.48"), candidates.map { it.address })
        assertEquals(setOf("saved"), candidates.single().sources)
    }

    @Test
    fun discover_queriesOnlyABoundedCuratedSetOfGoogleEcsViews() = runTest {
        val urls = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                urls += chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"Answer\":[]}".toResponseBody("application/dns-json".toMediaType()))
                    .build()
            }
            .build()
        val discovery = RouteCandidateDiscovery(client) { emptyList() }

        discovery.discover(listOf("space-443.app.github.dev", "space-8443.app.github.dev"))

        val ecsQueries = urls.map { it.toHttpUrl() }
            .count { it.queryParameter("edns_client_subnet") != null }
        assertEquals(5, ecsQueries)
    }
}
