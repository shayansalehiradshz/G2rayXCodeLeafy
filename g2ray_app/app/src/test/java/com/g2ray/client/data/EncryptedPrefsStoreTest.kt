package com.g2ray.client.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.GeneralSecurityException

@RunWith(RobolectricTestRunner::class)
class EncryptedPrefsStoreTest {
    private fun backingPreferences(): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("store-failure-fixture", Context.MODE_PRIVATE)

    @Test
    fun openingFailureIsDeferredAndRetriedWithoutClearingStoredData() {
        val backing = backingPreferences()
        assertTrue(backing.edit().putString("saved", "original").commit())
        var calls = 0
        val store = EncryptedPrefsStore {
            calls += 1
            if (calls == 1) throw GeneralSecurityException("Fixture opening failure")
            backing
        }

        assertEquals(0, calls)
        assertThrows(GeneralSecurityException::class.java) { store.getString("saved") }
        assertEquals("original", backing.getString("saved", null))
        assertEquals("original", store.getString("saved"))
        assertEquals(2, calls)
        assertEquals("original", store.getString("saved"))
        assertEquals(2, calls)
    }

    @Test
    fun failedCommitDoesNotLeaveUnacknowledgedValuesInMemory() {
        val backing = backingPreferences()
        assertTrue(backing.edit().putString("profile", "original").putString("untouched", "keep").commit())
        val failing = FailureReportingPreferences(backing, failures = 1)
        val store = EncryptedPrefsStore { failing }

        assertThrows(IllegalStateException::class.java) {
            store.putStrings(mapOf("profile" to "unacknowledged", "new_key" to "new"))
        }

        assertEquals("original", store.getString("profile"))
        assertNull(store.getString("new_key"))
        assertEquals("keep", store.getString("untouched"))
    }

    @Test
    fun failedRemoveDoesNotEraseOriginalSettings() {
        val backing = backingPreferences()
        assertTrue(backing.edit().putString("legacy", "original").commit())
        val store = EncryptedPrefsStore { FailureReportingPreferences(backing, failures = 1) }

        assertThrows(IllegalStateException::class.java) { store.remove("legacy") }

        assertEquals("original", store.getString("legacy"))
    }

    @Test
    fun pendingRollbackBlocksReadsAndNewWritesUntilAnAcknowledgedRestore() {
        val backing = backingPreferences()
        assertTrue(backing.edit().putString("profile", "original").commit())
        val failing = FailureReportingPreferences(backing, failures = 4)
        val store = EncryptedPrefsStore { failing }

        assertThrows(IllegalStateException::class.java) { store.putString("profile", "unacknowledged") }
        assertEquals(2, failing.commits)
        assertThrows(IllegalStateException::class.java) { store.getString("profile") }
        assertThrows(IllegalStateException::class.java) { store.putString("new_key", "must-not-write") }
        assertEquals(4, failing.commits)
        assertNull(backing.getString("new_key", null))

        assertEquals("original", store.getString("profile"))
        assertEquals(5, failing.commits)
        assertEquals("original", store.getString("profile"))
        assertEquals(5, failing.commits)
        store.putString("new_key", "confirmed")
        assertEquals("confirmed", store.getString("new_key"))
    }

    @Test
    fun throwingCommitAndRollbackRemainRecoverableWithoutAcceptingNewValues() {
        val backing = backingPreferences()
        assertTrue(backing.edit().putString("profile", "original").commit())
        val failing = FailureReportingPreferences(backing, failures = 3, throwOnFailure = true)
        val store = EncryptedPrefsStore { failing }

        assertThrows(IllegalStateException::class.java) { store.putString("profile", "unacknowledged") }
        assertThrows(java.io.IOException::class.java) { store.getString("profile") }
        assertEquals("original", store.getString("profile"))
        assertEquals(4, failing.commits)
    }

    @Test
    fun pendingScanHistoryRollbackDoesNotBlockUnrelatedVpnSettings() {
        val backing = backingPreferences()
        assertTrue(backing.edit().putString("vpn_engine_mode", "xray_native").commit())
        val failing = FailureReportingPreferences(backing, failures = 4)
        val store = EncryptedPrefsStore { failing }
        val historyKey = "vpn_route_optimization_attempt_fixture"

        assertThrows(IllegalStateException::class.java) { store.putString(historyKey, "unacknowledged") }
        assertEquals(2, failing.commits)

        assertEquals("xray_native", store.getString("vpn_engine_mode"))
        assertEquals(2, failing.commits)
        assertThrows(IllegalStateException::class.java) { store.getString(historyKey) }
        assertEquals(3, failing.commits)
    }

    private class FailureReportingPreferences(
        private val backing: SharedPreferences,
        private var failures: Int,
        private val throwOnFailure: Boolean = false
    ) : SharedPreferences by backing {
        var commits = 0
            private set
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    // AOSP changes the in-memory map before reporting disk
                    // success. This fixture models a changed value with a
                    // failed acknowledgement, not a real full-device disk.
                    val committed = editor.commit()
                    commits += 1
                    if (failures <= 0) return committed
                    failures -= 1
                    if (throwOnFailure) throw java.io.IOException("Fixture commit acknowledgement failure")
                    return false
                }
            }
        }
    }
}
