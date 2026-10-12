package com.g2ray.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsScreenTest {
    @Test
    fun wakeKeyDisplayText_showsFullLocalSecret() {
        val secret = "TEST_WAKE_SECRET_VISIBLE_PLACEHOLDER_REDACT_ME"

        val display = wakeKeyDisplayTextVisibleForTesting(secret)

        assertEquals(secret, display)
        assertFalse(display.contains("•"))
    }
}
