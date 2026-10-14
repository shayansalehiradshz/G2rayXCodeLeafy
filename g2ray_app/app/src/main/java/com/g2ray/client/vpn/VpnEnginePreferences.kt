package com.g2ray.client.vpn

import android.content.Context
import com.g2ray.client.data.EncryptedPrefsStore
import com.g2ray.client.data.SecureKeyValueStore
import com.g2ray.client.model.VpnEngineMode

internal data class VpnSettingsSnapshot(
    val mode: VpnEngineMode = VpnEngineMode.Auto,
    val fastDnsEnabled: Boolean = false,
    val ipv4OnlyEnabled: Boolean = false,
    val dpiCompatibilityEnabled: Boolean = false,
    val autoRouteOptimizationEnabled: Boolean = true,
    val seamlessFailoverEnabled: Boolean = true
)

/** Persists the user's engine preference independently of a VPN service restart. */
class VpnEnginePreferences internal constructor(
    private val store: SecureKeyValueStore
) {
    constructor(context: Context) : this(EncryptedPrefsStore(context.applicationContext))

    internal fun readSettings(): VpnSettingsSnapshot = VpnSettingsSnapshot(
        mode = getMode(),
        fastDnsEnabled = isFastDnsEnabled(),
        ipv4OnlyEnabled = isIpv4OnlyEnabled(),
        dpiCompatibilityEnabled = isDpiCompatibilityEnabled(),
        autoRouteOptimizationEnabled = isAutoRouteOptimizationEnabled(),
        seamlessFailoverEnabled = isSeamlessFailoverEnabled()
    )

    fun getMode(): VpnEngineMode = VpnEngineMode.fromStorage(store.getString(PREF_VPN_ENGINE_MODE, null))

    fun setMode(mode: VpnEngineMode) {
        store.putString(PREF_VPN_ENGINE_MODE, mode.storageValue)
    }

    fun isFastDnsEnabled(): Boolean = readBoolean(PREF_FAST_DNS_ENABLED)

    fun setFastDnsEnabled(enabled: Boolean) {
        store.putString(PREF_FAST_DNS_ENABLED, enabled.toString())
    }

    fun isIpv4OnlyEnabled(): Boolean = readBoolean(PREF_IPV4_ONLY_ENABLED)

    fun setIpv4OnlyEnabled(enabled: Boolean) {
        store.putString(PREF_IPV4_ONLY_ENABLED, enabled.toString())
    }

    fun isDpiCompatibilityEnabled(): Boolean = readBoolean(PREF_DPI_COMPATIBILITY_ENABLED)

    fun setDpiCompatibilityEnabled(enabled: Boolean) {
        store.putString(PREF_DPI_COMPATIBILITY_ENABLED, enabled.toString())
    }

    fun isAutoRouteOptimizationEnabled(): Boolean {
        return readBoolean(PREF_AUTO_ROUTE_OPTIMIZATION_ENABLED, defaultValue = true)
    }

    fun setAutoRouteOptimizationEnabled(enabled: Boolean) {
        store.putString(PREF_AUTO_ROUTE_OPTIMIZATION_ENABLED, enabled.toString())
    }

    fun isSeamlessFailoverEnabled(): Boolean {
        return readBoolean(PREF_SEAMLESS_FAILOVER_ENABLED, defaultValue = true)
    }

    fun setSeamlessFailoverEnabled(enabled: Boolean) {
        store.putString(PREF_SEAMLESS_FAILOVER_ENABLED, enabled.toString())
    }

    fun getLastRouteOptimizationAttemptAt(accountId: String, networkKey: String? = null): Long {
        if (!networkKey.isNullOrBlank() &&
            store.getString("${routeOptimizationAttemptKey(accountId)}_network", null) != networkKey
        ) return 0L
        return store.getString(routeOptimizationAttemptKey(accountId), null)
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L
    }

    fun setLastRouteOptimizationAttemptAt(accountId: String, timestampMs: Long, networkKey: String? = null) {
        val key = routeOptimizationAttemptKey(accountId)
        store.putStrings(mapOf(key to timestampMs.coerceAtLeast(0L).toString(), "${key}_network" to networkKey))
    }

    private fun readBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return store.getString(key, null)?.toBooleanStrictOrNull() ?: defaultValue
    }

    private fun routeOptimizationAttemptKey(accountId: String): String {
        require(accountId.isNotBlank()) { "Account ID is empty" }
        return "$PREF_ROUTE_OPTIMIZATION_ATTEMPT_PREFIX$accountId"
    }

    companion object {
        private const val PREF_VPN_ENGINE_MODE = "vpn_engine_mode"
        private const val PREF_FAST_DNS_ENABLED = "vpn_fast_dns_enabled"
        private const val PREF_IPV4_ONLY_ENABLED = "vpn_ipv4_only_enabled"
        private const val PREF_DPI_COMPATIBILITY_ENABLED = "vpn_dpi_compatibility_enabled"
        private const val PREF_AUTO_ROUTE_OPTIMIZATION_ENABLED = "vpn_auto_route_optimization_enabled"
        private const val PREF_SEAMLESS_FAILOVER_ENABLED = "vpn_seamless_failover_enabled"
        private const val PREF_ROUTE_OPTIMIZATION_ATTEMPT_PREFIX = "vpn_route_optimization_attempt_"
    }
}
