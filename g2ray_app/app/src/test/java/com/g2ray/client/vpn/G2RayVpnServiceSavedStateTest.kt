package com.g2ray.client.vpn

import android.content.Context
import android.os.Build
import com.g2ray.client.G2RayApplication
import com.g2ray.client.data.EncryptedPrefsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], application = G2RayApplication::class)
class G2RayVpnServiceSavedStateTest {
    @Test
    fun serviceRestoresProfileContextFromTheApplicationStore() = withService { service, store ->
        store.putStrings(mapOf("last_raw_profile_uri" to "fixture-profile", "last_account_id" to "fixture-account"))

        assertEquals("fixture-profile", invoke(service, "restoreLastRawProfile"))
        assertEquals("fixture-account", invoke(service, "restoreLastAccountId"))
    }

    @Test
    fun serviceRemembersProfileAndIntentInTheApplicationStore() = withService { service, store ->
        invoke(service, "rememberLastProfileContext", "fixture-profile", "fixture-account")

        assertEquals("fixture-profile", store.getString("last_raw_profile_uri"))
        assertEquals("fixture-account", store.getString("last_account_id"))
        assertEquals("true", store.getString("vpn_desired_connected"))
    }

    @Test
    fun clearingProfileContextUsesTheSameStoreWithoutChangingDesiredIntent() = withService { service, store ->
        store.putStrings(mapOf(
            "last_raw_profile_uri" to "fixture-profile",
            "last_account_id" to "fixture-account",
            "vpn_desired_connected" to "false"
        ))

        invoke(service, "clearLastRawProfile")

        assertNull(store.getString("last_raw_profile_uri"))
        assertNull(store.getString("last_account_id"))
        assertEquals("false", store.getString("vpn_desired_connected"))
    }

    private fun withService(body: (G2RayVpnService, EncryptedPrefsStore) -> Unit) {
        val application = RuntimeEnvironment.getApplication() as G2RayApplication
        val backing = application.getSharedPreferences("service-saved-state-fixture", Context.MODE_PRIVATE)
        val store = EncryptedPrefsStore { backing }
        G2RayApplication::class.java.getDeclaredField("secureStore\$delegate")
            .apply { isAccessible = true }.set(application, lazyOf(store))
        val controller = Robolectric.buildService(G2RayVpnService::class.java).create()
        try {
            body(controller.get(), store)
        } finally {
            controller.destroy()
        }
    }

    private fun invoke(service: G2RayVpnService, name: String, vararg args: String): Any? =
        G2RayVpnService::class.java.getDeclaredMethod(name, *Array(args.size) { String::class.java })
            .apply { isAccessible = true }.invoke(service, *args)
}
