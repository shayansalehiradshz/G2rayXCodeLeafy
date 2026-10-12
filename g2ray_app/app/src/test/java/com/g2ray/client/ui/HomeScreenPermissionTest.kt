package com.g2ray.client.ui

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenPermissionTest {
    @Test
    fun shouldRequestNotificationPermission_onlyOnAndroid13PlusWhenMissing() {
        assertTrue(shouldRequestNotificationPermission(Build.VERSION_CODES.TIRAMISU, false))
        assertFalse(shouldRequestNotificationPermission(Build.VERSION_CODES.TIRAMISU, true))
        assertFalse(shouldRequestNotificationPermission(Build.VERSION_CODES.S_V2, false))
    }

    @Test
    fun notificationPermissionResult_neverBlocksVpnPermissionFlow() {
        assertTrue(shouldContinueAfterNotificationPermissionResult(true))
        assertTrue(shouldContinueAfterNotificationPermissionResult(false))
    }
}
