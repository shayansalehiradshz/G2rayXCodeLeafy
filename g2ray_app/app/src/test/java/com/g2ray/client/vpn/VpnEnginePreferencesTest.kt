package com.g2ray.client.vpn

import com.g2ray.client.data.SecureKeyValueStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnEnginePreferencesTest {
    @Test
    fun compatibilityFeatures_defaultOffAndPersistExplicitChoices() {
        val preferences = VpnEnginePreferences(InMemoryStore())

        assertFalse(preferences.isFastDnsEnabled())
        assertFalse(preferences.isIpv4OnlyEnabled())
        assertFalse(preferences.isDpiCompatibilityEnabled())
        assertTrue(preferences.isAutoRouteOptimizationEnabled())
        assertTrue(preferences.isSeamlessFailoverEnabled())

        preferences.setFastDnsEnabled(true)
        preferences.setIpv4OnlyEnabled(true)
        preferences.setDpiCompatibilityEnabled(true)
        preferences.setAutoRouteOptimizationEnabled(false)
        preferences.setSeamlessFailoverEnabled(false)

        assertTrue(preferences.isFastDnsEnabled())
        assertTrue(preferences.isIpv4OnlyEnabled())
        assertTrue(preferences.isDpiCompatibilityEnabled())
        assertFalse(preferences.isAutoRouteOptimizationEnabled())
        assertFalse(preferences.isSeamlessFailoverEnabled())
    }

    @Test
    fun routeOptimizationAttempt_isStoredPerCodespaceAccount() {
        val preferences = VpnEnginePreferences(InMemoryStore())

        assertEquals(0L, preferences.getLastRouteOptimizationAttemptAt("account-a"))
        preferences.setLastRouteOptimizationAttemptAt("account-a", 1234L)

        assertEquals(1234L, preferences.getLastRouteOptimizationAttemptAt("account-a"))
        assertEquals(0L, preferences.getLastRouteOptimizationAttemptAt("account-b"))
    }

    @Test
    fun routeOptimizationCooldownDoesNotCarryAcrossPhysicalNetworks() {
        val store = InMemoryStore()
        val preferences = VpnEnginePreferences(store)
        preferences.setLastRouteOptimizationAttemptAt("account-a", 1234L, "network-a")
        val reloaded = VpnEnginePreferences(store)
        assertEquals(1234L, reloaded.getLastRouteOptimizationAttemptAt("account-a", "network-a"))
        assertEquals(0L, reloaded.getLastRouteOptimizationAttemptAt("account-a", "network-b"))
        assertEquals(0L, reloaded.getLastRouteOptimizationAttemptAt("account-b", "network-a"))
        reloaded.setLastRouteOptimizationAttemptAt("account-a", 2345L, "network-b")
        assertEquals(2345L, reloaded.getLastRouteOptimizationAttemptAt("account-a", "network-b"))
        assertEquals(0L, reloaded.getLastRouteOptimizationAttemptAt("account-a", "network-a"))
    }

    @Test
    fun legacyOptimizationTimestampDoesNotSuppressTheFirstScopedScan() {
        val preferences = VpnEnginePreferences(InMemoryStore())
        preferences.setLastRouteOptimizationAttemptAt("account-a", 1234L)
        assertEquals(0L, preferences.getLastRouteOptimizationAttemptAt("account-a", "network-a"))
    }

    private class InMemoryStore : SecureKeyValueStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }

        override fun putStrings(values: Map<String, String?>) {
            values.forEach(::putString)
        }

        override fun remove(vararg keys: String) {
            keys.forEach(values::remove)
        }
    }
}
