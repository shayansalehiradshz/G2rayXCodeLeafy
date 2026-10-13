package com.g2ray.client.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import com.g2ray.client.vpn.SocketProtector
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Scopes route samples to an Android physical network, never to its VPN overlay. */
class RouteMeasurementContext(context: Context) {
    data class Snapshot(val networkKey: String, val generation: Long, val underlyingGeneration: Long)

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val generation = AtomicLong()
    private val observers = AtomicInteger()
    private val epoch = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrNull()?.takeIf { it >= 0 }?.let { "boot:$it" } ?: "process:${UUID.randomUUID()}"

    fun currentKey(): String? = keyFor(
        if (SocketProtector.vpnService != null) SocketProtector.underlyingNetworks.value
        else runCatching { connectivity?.activeNetwork }.getOrNull()
    )

    fun capture(): Snapshot? {
        if (observers.get() <= 0) return null
        val before = generation.get()
        val underlyingBefore = SocketProtector.underlyingNetworkGeneration()
        val key = currentKey() ?: return null
        if (observers.get() <= 0 || before != generation.get() ||
            underlyingBefore != SocketProtector.underlyingNetworkGeneration()
        ) return null
        return Snapshot(key, before, underlyingBefore)
    }

    fun isCurrent(snapshot: Snapshot): Boolean = capture() == snapshot

    fun keyFor(network: Network?): String? {
        if (network == null) return null
        return runCatching {
            val capabilities = connectivity?.getNetworkCapabilities(network) ?: return@runCatching null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) return@runCatching null
            network.networkHandle.takeIf { it != 0L }?.let { "v1:$epoch:$it" }
        }.getOrNull()
    }

    fun changes(): Flow<String?> {
        val defaultNetworkChanges = callbackFlow {
            var lastDefault = runCatching { connectivity?.activeNetwork }.getOrNull()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (lastDefault != network) generation.incrementAndGet()
                    lastDefault = network
                    trySend(true)
                }
                override fun onLost(network: Network) {
                    if (lastDefault == network) {
                        lastDefault = null
                        generation.incrementAndGet()
                    }
                    trySend(true)
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(true) }
            }
            val registered = runCatching {
                connectivity?.registerDefaultNetworkCallback(callback)
                connectivity != null
            }.getOrDefault(false)
            if (registered) observers.incrementAndGet()
            trySend(registered)
            if (!registered) {
                close()
                return@callbackFlow
            }
            awaitClose {
                if (observers.decrementAndGet() == 0) generation.incrementAndGet()
                runCatching { connectivity?.unregisterNetworkCallback(callback) }
            }
        }
        return combine(defaultNetworkChanges, SocketProtector.underlyingNetworks) { observing, _ ->
            if (observing) currentKey() else null
        }.distinctUntilChanged()
    }

    companion object {
        fun matches(measuredKey: String?, currentKey: String?): Boolean =
            !currentKey.isNullOrBlank() && measuredKey == currentKey
    }
}
