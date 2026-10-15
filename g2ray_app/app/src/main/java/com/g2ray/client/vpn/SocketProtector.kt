package com.g2ray.client.vpn

import android.net.Network
import android.os.ParcelFileDescriptor
import com.g2ray.client.network.ControlPlaneConnectionPools
import com.g2ray.client.network.NetworkDnsResolver
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object SocketProtector {
    @Volatile
    var vpnService: G2RayVpnService? = null

    @Volatile
    private var underlyingNetwork: Network? = null
    private val _underlyingNetworks = MutableStateFlow<Network?>(null)
    val underlyingNetworks = _underlyingNetworks.asStateFlow()
    private val networkGeneration = AtomicLong()

    fun underlyingNetworkGeneration(): Long = networkGeneration.get()

    private data class DialerFailure(val generation: Long, val message: String)
    private val lastDialerFailure = AtomicReference<DialerFailure?>(null)
    private val dnsResolver = NetworkDnsResolver()

    @JvmStatic
    fun protect(fd: Int): Boolean {
        val service = vpnService ?: return true
        return service.protect(fd)
    }

    /**
     * Xray invokes this before connect(). Protect the outbound socket from the
     * VPN loop, then bind it to the physical network selected by the service.
     * The duplicated descriptor refers to the same socket and can be closed
     * immediately after Network.bindSocket applies the network mark.
     */
    @JvmStatic
    fun protectAndBindDialer(fd: Int): Boolean {
        val service = vpnService ?: return true
        val (network, generation) = networkSnapshot()
        if (!service.protect(fd)) {
            return recordDialerFailure("Android rejected VPN protection for an Xray dialer socket", generation)
        }
        if (network == null) {
            return recordDialerFailure("No physical network was selected for an Xray dialer socket", generation)
        }
        return runCatching {
            ParcelFileDescriptor.fromFd(fd).use { duplicate ->
                network.bindSocket(duplicate.fileDescriptor)
            }
            generation == networkGeneration.get()
        }.getOrElse { error ->
            recordDialerFailure(
                "Could not bind an Xray dialer socket to the selected network: " +
                    (error.localizedMessage ?: error.javaClass.simpleName), generation
            )
        }
    }

    @Synchronized
    fun updateUnderlyingNetwork(network: Network?) {
        val changed = underlyingNetwork != network
        if (changed) networkGeneration.incrementAndGet()
        underlyingNetwork = network
        _underlyingNetworks.value = network
        if (changed) {
            ControlPlaneConnectionPools.evictAll()
        }
    }

    fun clearDialerFailure() {
        lastDialerFailure.set(null)
    }

    fun consumeDialerFailure(): String? = lastDialerFailure.getAndSet(null)
        ?.takeIf { it.generation == networkGeneration.get() }?.message

    @Synchronized
    private fun networkSnapshot(): Pair<Network?, Long> = underlyingNetwork to networkGeneration.get()

    @Synchronized
    private fun recordDialerFailure(message: String, generation: Long): Boolean {
        // A late bind/protect callback from a retired network must neither
        // restart its replacement nor mask a real failure on that replacement.
        if (generation == networkGeneration.get()) {
            val previous = lastDialerFailure.get()
            if (previous == null || previous.generation != generation) {
                lastDialerFailure.set(DialerFailure(generation, message))
            }
        }
        return false
    }

    /**
     * Control-plane calls are direct when no VPN exists. While the VPN is
     * active, protect and bind them to the selected physical network so a dead
     * tunnel cannot block its own Worker/GitHub recovery request.
     */
    fun protectControlPlane(socket: Socket): Boolean {
        val service = vpnService ?: return true
        val (network, generation) = networkSnapshot()
        if (network == null) return false
        return runCatching {
            // Android creates Socket's native FD lazily. Read an option before
            // protect(), without binding or connecting through the dead VPN.
            socket.soLinger
            if (!service.protect(socket)) return@runCatching false
            network.bindSocket(socket)
            generation == networkGeneration.get()
        }.getOrDefault(false)
    }

    /**
     * OkHttp resolves a hostname before creating the socket. Resolve on the
     * same non-VPN network used by [protectControlPlane], otherwise recovery
     * DNS can disappear into an already-broken tunnel.
     */
    fun lookupControlPlane(hostname: String): List<InetAddress> {
        val network = underlyingNetwork
        if (network == null && vpnService != null) {
            throw UnknownHostException("No non-VPN network is available for $hostname")
        }
        if (isNumericAddress(hostname)) return listOf(InetAddress.getByName(hostname))
        return dnsResolver.lookup(network, hostname)
    }

    /**
     * Resolve a domain-based VLESS endpoint before Xray starts. This avoids a
     * circular lookup through the new TUN while preserving the original
     * SNI/Host fields for Codespaces routing.
     */
    fun resolveTunnelEndpoints(address: String, maxCandidates: Int): List<String> {
        if (isNumericAddress(address)) {
            return listOf(address)
        }
        val addresses = lookupControlPlane(address)
        val preferred = addresses.filterIsInstance<Inet4Address>().ifEmpty { addresses }
        return preferred.mapNotNull { it.hostAddress?.takeIf(String::isNotBlank) }
            .distinct()
            .take(maxCandidates.coerceIn(1, 3))
            .takeIf { it.isNotEmpty() }
            ?: throw UnknownHostException("No usable address found for $address")
    }

    internal fun isNumericAddress(address: String): Boolean {
        if (address.contains(':')) {
            return true
        }
        val parts = address.split('.')
        return parts.size == 4 && parts.all { part ->
                part.isNotEmpty() &&
                part.length <= 3 &&
                part.all(Char::isDigit) &&
                (part.toIntOrNull() ?: -1) in 0..255
        }
    }
}
