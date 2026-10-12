package com.g2ray.client.network

import com.g2ray.client.parser.VlessUriParser
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class DiscoveredRouteCandidate(
    val address: String,
    val sources: Set<String>
)

/**
 * Discovers only DNS-advertised addresses for the exact Codespaces hostname.
 * Compatibility is established later by a real libXray request from the phone;
 * DNS presence by itself never makes a candidate eligible for persistence.
 */
class RouteCandidateDiscovery(
    private val httpClient: OkHttpClient,
    private val systemLookup: (String) -> List<InetAddress>
) {
    suspend fun discover(
        hostnames: Collection<String>,
        existingAddresses: Collection<String> = emptyList(),
        maxCandidates: Int = MAX_CANDIDATES
    ): List<DiscoveredRouteCandidate> = coroutineScope {
        val normalizedHosts = hostnames.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !VlessUriParser.isIpv4Literal(it) }
            .distinctBy(String::lowercase)
            .take(MAX_HOSTNAMES)
            .toList()

        val observations = mutableListOf<Pair<String, String>>()

        val jobs = normalizedHosts.flatMap { hostname ->
            listOf(
                async(Dispatchers.IO) {
                    queryOrEmpty {
                        systemLookup(hostname)
                            .filterIsInstance<Inet4Address>()
                            .mapNotNull(InetAddress::getHostAddress)
                            .map { it to SOURCE_SYSTEM }
                    }
                },
                async(Dispatchers.IO) {
                    queryOrEmpty { queryDoh(CLOUDFLARE_DOH, hostname).map { it to SOURCE_CLOUDFLARE } }
                },
                async(Dispatchers.IO) {
                    queryOrEmpty { queryDoh(GOOGLE_DOH, hostname).map { it to SOURCE_GOOGLE } }
                }
            )
        }.toMutableList()
        // A few curated ECS views can expose legitimate anycast answers that
        // the phone's default resolver does not return. Keep this to the first
        // hostname and a small fixed list so discovery stays fast and bounded.
        normalizedHosts.firstOrNull()?.let { hostname ->
            GOOGLE_ECS_SUBNETS.forEach { subnet ->
                jobs += async(Dispatchers.IO) {
                    queryOrEmpty {
                        queryDoh(GOOGLE_DOH, hostname, subnet)
                            .map { it to "google_ecs_$subnet" }
                    }
                }
            }
        }
        jobs.awaitAll().forEach(observations::addAll)
        // Fresh phone-network answers lead the bounded list. Saved endpoints
        // remain as a fallback and collect an additional source label when a
        // resolver returns the same address.
        existingAddresses.forEach { observations += it to SOURCE_EXISTING }

        val sourcesByAddress = linkedMapOf<String, LinkedHashSet<String>>()
        observations.forEach { (address, source) ->
            val normalized = address.trim()
            if (VlessUriParser.isUsablePublicIpv4(normalized)) {
                sourcesByAddress.getOrPut(normalized) { linkedSetOf() } += source
            }
        }
        sourcesByAddress.entries.take(maxCandidates.coerceIn(1, MAX_CANDIDATES)).map { (address, sources) ->
            DiscoveredRouteCandidate(address, sources)
        }
    }

    private suspend fun queryOrEmpty(query: suspend () -> List<Pair<String, String>>): List<Pair<String, String>> {
        currentCoroutineContext().ensureActive()
        return try {
            query().also { currentCoroutineContext().ensureActive() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            emptyList()
        }
    }

    private suspend fun queryDoh(
        endpoint: String,
        hostname: String,
        ednsClientSubnet: String? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("name", hostname)
            .addQueryParameter("type", "A")
            .apply {
                ednsClientSubnet?.let { addQueryParameter("edns_client_subnet", it) }
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("DoH returned HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("DoH returned an empty response")
            extractIpv4Answers(body)
        }
    }

    companion object {
        private const val MAX_HOSTNAMES = 4
        const val MAX_CANDIDATES = 24
        private const val CLOUDFLARE_DOH = "https://cloudflare-dns.com/dns-query"
        private const val GOOGLE_DOH = "https://dns.google/resolve"
        private const val SOURCE_EXISTING = "saved"
        private const val SOURCE_SYSTEM = "system_dns"
        private const val SOURCE_CLOUDFLARE = "cloudflare_doh"
        private const val SOURCE_GOOGLE = "google_doh"
        private val GOOGLE_ECS_SUBNETS = listOf(
            "1.1.1.0/24",
            "8.8.8.0/24",
            "9.9.9.0/24",
            "94.140.14.0/24",
            "185.228.168.0/24"
        )

        internal fun extractIpv4Answers(json: String): List<String> {
            val answers = JSONObject(json).optJSONArray("Answer") ?: return emptyList()
            return buildList {
                for (index in 0 until answers.length()) {
                    val answer = answers.optJSONObject(index) ?: continue
                    if (answer.optInt("type", -1) != 1) continue
                    answer.optString("data")
                        .trim()
                        .takeIf(VlessUriParser::isUsablePublicIpv4)
                        ?.let(::add)
                }
            }.distinct()
        }
    }
}
