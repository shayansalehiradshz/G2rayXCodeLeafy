package com.g2ray.client.vpn

import android.net.Network
import android.os.ParcelFileDescriptor
import com.g2ray.client.network.ControlPlaneConnectionPools
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference

object SocketProtector {
    @Volatile
    var vpnService: G2RayVpnService? = null

    @Volatile
    private var underlyingNetwork: Network? = null

    private val lastDialerFailure = AtomicReference<String?>(null)

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
        if (!service.protect(fd)) {
            return recordDialerFailure("Android rejected VPN protection for an Xray dialer socket")
        }
        val network = underlyingNetwork
            ?: return recordDialerFailure("No physical network was selected for an Xray dialer socket")
        return runCatching {
            ParcelFileDescriptor.fromFd(fd).use { duplicate ->
                network.bindSocket(duplicate.fileDescriptor)
            }
            true
        }.getOrElse { error ->
            recordDialerFailure(
                "Could not bind an Xray dialer socket to the selected network: " +
                    (error.localizedMessage ?: error.javaClass.simpleName)
            )
        }
    }

    @Synchronized
    fun updateUnderlyingNetwork(network: Network?) {
        val changed = underlyingNetwork != network
        underlyingNetwork = network
        if (changed) {
            ControlPlaneConnectionPools.evictAll()
        }
    }

    fun clearDialerFailure() {
        lastDialerFailure.set(null)
    }

    fun consumeDialerFailure(): String? = lastDialerFailure.getAndSet(null)

    private fun recordDialerFailure(message: String): Boolean {
        lastDialerFailure.compareAndSet(null, message)
        return false
    }

    /**
     * Control-plane calls are direct when no VPN exists. While the VPN is
     * active, protect and bind them to the selected physical network so a dead
     * tunnel cannot block its own Worker/GitHub recovery request.
     */
    fun protectControlPlane(socket: Socket): Boolean {
        val service = vpnService ?: return true
        if (!service.protect(socket)) {
            return false
        }
        val network = underlyingNetwork ?: return false
        return runCatching {
            network.bindSocket(socket)
            true
        }.getOrDefault(false)
    }

    /**
     * OkHttp resolves a hostname before creating the socket. Resolve on the
     * same non-VPN network used by [protectControlPlane], otherwise recovery
     * DNS can disappear into an already-broken tunnel.
     */
    fun lookupControlPlane(hostname: String): List<InetAddress> {
        val network = underlyingNetwork
        val addresses = when {
            network != null -> network.getAllByName(hostname).toList()
            vpnService == null -> InetAddress.getAllByName(hostname).toList()
            else -> throw UnknownHostException("No non-VPN network is available for $hostname")
        }
        if (addresses.isEmpty()) {
            throw UnknownHostException("No addresses found for $hostname")
        }
        return addresses
    }

    /**
     * Resolve a domain-based VLESS endpoint before Xray starts. This avoids a
     * circular lookup through the new TUN while preserving the original
     * SNI/Host fields for Codespaces routing.
     */
    fun resolveTunnelEndpoint(address: String): String {
        if (isNumericAddress(address)) {
            return address
        }
        val addresses = lookupControlPlane(address)
        return (addresses.firstOrNull { it is Inet4Address } ?: addresses.first()).hostAddress
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
