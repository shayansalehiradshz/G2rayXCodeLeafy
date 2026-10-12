package com.g2ray.client.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigClipboardTest {
    @Test
    fun copiesExactVlessUriToClipboard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=xhttp&path=%2F#profile"

        assertTrue(copyConfigLinkToClipboard(context, uri))

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(uri, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
