package com.g2ray.client.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VpnProtectionSettingsTest {
    @Test
    fun settingsOpensTheUserControlledVpnPanelNotAnUnverifiedAppToggle() {
        val context = RecordingContext()
        assertTrue(openVpnProtectionSettings(context))
        assertEquals(listOf(Settings.ACTION_VPN_SETTINGS), context.intents.map { it.action })
        assertTrue(context.intents.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun unavailableOemVpnPanelFallsBackToGeneralSettings() {
        val context = RecordingContext { intent ->
            if (intent.action == Settings.ACTION_VPN_SETTINGS) throw ActivityNotFoundException()
        }
        assertTrue(openVpnProtectionSettings(context))
        assertEquals(listOf(Settings.ACTION_VPN_SETTINGS, Settings.ACTION_SETTINGS), context.intents.map { it.action })
    }

    @Test
    fun managedDeviceRestrictionsAreReportedWithoutCrashing() {
        val context = RecordingContext { throw SecurityException("Fixture restricted settings") }
        assertFalse(openVpnProtectionSettings(context))
        assertEquals(2, context.intents.size)
    }

    @Test
    fun unknownOsBlockingStateDoesNotClaimLeakProtection() {
        assertEquals("Enabled", vpnBlockingStatusLabel(true))
        assertEquals("Not enabled", vpnBlockingStatusLabel(false))
        assertEquals("Check Android settings", vpnBlockingStatusLabel(null))
    }

    private class RecordingContext(private val onLaunch: (Intent) -> Unit = {}) :
        ContextWrapper(RuntimeEnvironment.getApplication()) {
        val intents = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            intents += intent
            onLaunch(intent)
        }
    }
}
