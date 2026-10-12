package com.g2ray.client.network

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class RouteCandidateDiscoveryTest {
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
            .addInterceptor { throw CancellationException("Scan canceled") }
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
