package com.g2ray.client.vpn

import android.content.Intent
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.g2ray.client.G2RayApplication
import com.g2ray.client.data.AccountRepository
import com.g2ray.client.data.EncryptedPrefsStore
import com.g2ray.client.model.VpnStateRepository
import com.g2ray.client.parser.VlessUriParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 31], application = G2RayApplication::class,
    shadows = [G2RayVpnServiceAlwaysOnTest.ProtectionModeShadow::class])
class G2RayVpnServiceAlwaysOnTest {
    @Test
    fun androidSystemStartQueuesTheVpnInsteadOfIgnoringItsAction() = runTest {
        withService { service, _ ->
            val result = service.onStartCommand(Intent(VpnService.SERVICE_INTERFACE), 0, 1)
            assertEquals(VpnService.START_STICKY, result)
            assertTrue(field(service, "desiredConnected") as Boolean)
            assertNotNull("Android's always-on start must reach startup", field(service, "startJob"))
        }
    }

    @Test
    fun duplicateSystemStartDoesNotReplaceAnAlreadyQueuedStartup() = runTest {
        withService { service, _ ->
            service.onStartCommand(Intent(VpnService.SERVICE_INTERFACE), 0, 1)
            val first = field(service, "startJob")
            assertNotNull(first)
            service.onStartCommand(Intent(VpnService.SERVICE_INTERFACE), 0, 2)
            assertSame(first, field(service, "startJob"))
        }
    }

    @Test
    @Config(sdk = [31])
    fun appStopCannotReleaseAnAlwaysOnTunnelBehindAndroidsBack() = runTest {
        withService { service, _ ->
            alwaysOn = true
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                setField(service, "vpnInterface", pipe[0])
                setField(service, "desiredConnected", true)
                assertEquals(VpnService.START_STICKY,
                    service.onStartCommand(Intent(G2RayVpnService.ACTION_STOP), 0, 1))
                assertSame(pipe[0], field(service, "vpnInterface"))
                assertTrue(field(service, "desiredConnected") as Boolean)
            } finally {
                setField(service, "vpnInterface", null)
                pipe.forEach { it.close() }
            }
        }
    }

    @Test
    fun manualStopWithoutAlwaysOnStillStops() = runTest {
        withService { service, _ ->
            setField(service, "desiredConnected", true)
            assertEquals(VpnService.START_NOT_STICKY,
                service.onStartCommand(Intent(G2RayVpnService.ACTION_STOP), 0, 1))
            assertFalse(field(service, "desiredConnected") as Boolean)
        }
    }

    @Test
    fun systemRestorationUsesTheSavedSelectionEvenWhenTheSessionCacheWasCleared() = runTest {
        withService { service, store ->
            store.putString("vpn_desired_connected", "false")
            val application = RuntimeEnvironment.getApplication() as G2RayApplication
            val repository = application.accountRepository
            val account = repository.addAccount("Fixture", "", "fixture-space")
            val parsed = VlessUriParser.parse(
                "vless://00000000-0000-4000-8000-000000000001@203.0.113.1:443" +
                    "?encryption=none&security=tls&sni=fixture.example&host=fixture.example&type=xhttp&path=%2F&mode=packet-up&alpn=h2"
            ).getOrThrow()
            val selected = repository.importProfile(account.id, "Fixture", parsed.rawUri, parsed)
            repository.selectProfile(selected.id)
            assertEquals(null, store.getString("last_raw_profile_uri"))
            val restored = G2RayVpnService::class.java.getDeclaredMethod("selectedProfileForSystemStart")
                .apply { isAccessible = true }.invoke(service)
            assertEquals(selected, restored)
        }
    }

    @Test
    fun protectionStatusIsUnknownOnOldAndroidAndOsReportedOnNewAndroid() = runTest {
        withService { service, _ ->
            lockdown = true
            alwaysOn = true
            service.onStartCommand(Intent(G2RayVpnService.ACTION_START), 0, 1)
            val state = VpnStateRepository.state.value
            if (Build.VERSION.SDK_INT >= 29) {
                assertEquals(true, state.alwaysOnVpn)
                assertEquals(true, state.blockConnectionsWithoutVpn)
            } else {
                assertEquals(null, state.alwaysOnVpn)
                assertEquals(null, state.blockConnectionsWithoutVpn)
            }
        }
    }

    @Test
    fun systemRestorationCannotUseAnOldSessionWhenTheSelectedAccountHasNoProfile() = runTest {
        withService { service, store ->
            val repository = (RuntimeEnvironment.getApplication() as G2RayApplication).accountRepository
            repository.addAccount("New Codespace", "", "new-fixture-space")
            store.putString("last_raw_profile_uri", "old-session-config")
            val failure = runCatching {
                G2RayVpnService::class.java.getDeclaredMethod("selectedProfileForSystemStart")
                    .apply { isAccessible = true }.invoke(service)
            }.exceptionOrNull()
            assertTrue(failure?.cause is IllegalStateException)
            assertTrue(failure?.cause?.message.orEmpty().contains("Import and select"))
        }
    }

    private suspend fun TestScope.withService(
        block: suspend (G2RayVpnService, EncryptedPrefsStore) -> Unit
    ) {
        alwaysOn = false
        lockdown = false
        val previousState = VpnStateRepository.state.value
        val application = RuntimeEnvironment.getApplication() as G2RayApplication
        val backing = application.getSharedPreferences("always-on-fixture", Context.MODE_PRIVATE)
        backing.edit().clear().commit()
        val store = EncryptedPrefsStore { backing }
        G2RayApplication::class.java.getDeclaredField("secureStore\$delegate")
            .apply { isAccessible = true }.set(application, lazyOf(store))
        G2RayApplication::class.java.getDeclaredField("accountRepository\$delegate")
            .apply { isAccessible = true }.set(application, lazyOf(AccountRepository(store)))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val service = Robolectric.buildService(G2RayVpnService::class.java).create().get()
        setField(service, "scope", backgroundScope)
        try {
            block(service, store)
        } finally {
            (field(service, "startJob") as? Job)?.cancel()
            service.onDestroy()
            runCurrent()
            Dispatchers.resetMain()
            VpnStateRepository.state.value = previousState
            alwaysOn = false
            lockdown = false
        }
    }

    private fun field(service: G2RayVpnService, name: String): Any? =
        G2RayVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setField(service: G2RayVpnService, name: String, value: Any?) {
        G2RayVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    @Implements(VpnService::class)
    class ProtectionModeShadow : org.robolectric.shadows.ShadowService() {
        @Implementation(minSdk = 29)
        fun isAlwaysOn() = alwaysOn

        @Implementation(minSdk = 29)
        fun isLockdownEnabled() = lockdown
    }

    companion object {
        var alwaysOn = false
        var lockdown = false
    }
}
