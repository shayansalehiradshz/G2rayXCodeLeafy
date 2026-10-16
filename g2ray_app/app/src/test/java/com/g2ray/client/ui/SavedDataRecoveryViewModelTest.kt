package com.g2ray.client.ui

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.lifecycle.ViewModelStore
import com.g2ray.client.G2RayApplication
import com.g2ray.client.data.AccountRepository
import com.g2ray.client.data.AccountRepositoryTest.InMemorySecureStore
import com.g2ray.client.data.SecureKeyValueStore
import com.g2ray.client.data.EncryptedPrefsStore
import com.g2ray.client.model.AppConnectionState
import com.g2ray.client.model.RouteState
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.VpnStateRepository
import com.g2ray.client.model.VpnEngineMode
import com.g2ray.client.network.CodespaceWakeCoordinator
import com.g2ray.client.network.WorkerRouteOutcome
import com.g2ray.client.network.WorkerWakeResponse
import kotlinx.coroutines.flow.MutableStateFlow
import com.g2ray.client.parser.VlessUriParser
import com.g2ray.client.vpn.G2RayVpnService
import com.g2ray.client.vpn.VpnEnginePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = G2RayApplication::class, sdk = [31])
class SavedDataRecoveryViewModelTest {
    @Test
    fun duplicateStartCannotReplaceAConnectedStateWhenUiCoreAvailabilityIsStale() {
        withViewModel(repositoryWithProfile(), InMemorySecureStore()) { viewModel, application ->
            @Suppress("UNCHECKED_CAST")
            val availability = G2RayViewModel::class.java.getDeclaredField("_isVpnCoreAvailable")
                .apply { isAccessible = true }.get(viewModel) as MutableStateFlow<Boolean>
            availability.value = false
            VpnStateRepository.state.value = VpnStateRepository.state.value.copy(
                tunnelState = TunnelState.Connected, tunnelVerified = true, routeReady = true)
            val context = RecordingServiceContext(application)
            viewModel.startVpn(context)
            assertEquals(0, context.starts)
            assertEquals(TunnelState.Connected, VpnStateRepository.state.value.tunnelState)
            assertTrue(VpnStateRepository.state.value.tunnelVerified)
        }
    }

    @Test
    fun startCancelsUiWakeAndOptionalProbesBeforeServiceRecovery() {
        withViewModel(repositoryWithProfile(), InMemorySecureStore()) { viewModel, application ->
            @Suppress("UNCHECKED_CAST")
            val availability = G2RayViewModel::class.java.getDeclaredField("_isVpnCoreAvailable")
                .apply { isAccessible = true }.get(viewModel) as MutableStateFlow<Boolean>
            availability.value = true
            val jobs = listOf("currentWakeJob", "currentDelayProbeJob", "routeOptimizationJob").map { name ->
                kotlinx.coroutines.Job().also { job ->
                    G2RayViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(viewModel, job)
                }
            }
            val context = RecordingServiceContext(application)
            viewModel.startVpn(context)
            assertEquals(1, context.starts)
            assertTrue(jobs.all { it.isCancelled })
        }
    }

    @Test
    fun repeatedStartDoesNotLaunchAnotherServiceOrProbe() {
        withViewModel(repositoryWithProfile(), InMemorySecureStore()) { viewModel, application ->
            @Suppress("UNCHECKED_CAST")
            val availability = G2RayViewModel::class.java.getDeclaredField("_isVpnCoreAvailable")
                .apply { isAccessible = true }.get(viewModel) as MutableStateFlow<Boolean>
            availability.value = true
            val context = RecordingServiceContext(application)
            viewModel.startVpn(context)
            viewModel.startVpn(context)
            assertEquals(1, context.starts)
            assertEquals(TunnelState.Starting, VpnStateRepository.state.value.tunnelState)
        }
    }

    @Test
    fun failedServiceLaunchPreservesBlockedState() {
        withViewModel(repositoryWithProfile(), InMemorySecureStore()) { viewModel, application ->
            @Suppress("UNCHECKED_CAST")
            val availability = G2RayViewModel::class.java.getDeclaredField("_isVpnCoreAvailable")
                .apply { isAccessible = true }.get(viewModel) as MutableStateFlow<Boolean>
            availability.value = true
            VpnStateRepository.state.value = VpnStateRepository.state.value.copy(tunnelState = TunnelState.Blocked)
            viewModel.startVpn(RecordingServiceContext(application, rejectStart = true))
            assertEquals(TunnelState.Blocked, VpnStateRepository.state.value.tunnelState)
            assertFalse(VpnStateRepository.state.value.tunnelVerified)
            assertTrue(viewModel.statusMessage.value.startsWith("Could not start VPN:"))
        }
    }

    @Test
    fun stopInAlwaysOnModeLeavesDisconnectControlToAndroid() {
        withViewModel(repositoryWithProfile(), InMemorySecureStore()) { viewModel, application ->
            val context = RecordingServiceContext(application)
            VpnStateRepository.state.value = VpnStateRepository.state.value.copy(alwaysOnVpn = true)
            viewModel.stopVpn(context)
            assertEquals(0, context.starts)
            assertTrue(viewModel.statusMessage.value.contains("Android VPN settings"))
        }
    }

    @Test
    fun startWithWakeConfiguredHandsOffToTheServiceBeforeAnyNetworkPreflight() {
        val repository = repositoryWithProfile()
        val accountId = repository.activeSelection.value.accountId!!
        repository.addApiKey(accountId, "Fixture", "fixture-not-a-real-token",
            kind = com.g2ray.client.model.ApiKeyKind.GITHUB_TOKEN)
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        withViewModel(repository, InMemorySecureStore(), dispatcher) { viewModel, application ->
            @Suppress("UNCHECKED_CAST")
            val availability = G2RayViewModel::class.java.getDeclaredField("_isVpnCoreAvailable")
                .apply { isAccessible = true }.get(viewModel) as MutableStateFlow<Boolean>
            availability.value = true
            val context = RecordingServiceContext(application)

            viewModel.startVpn(context)

            // The scheduler is deliberately paused: no DNS, JNI ping, or
            // Worker/GitHub request is allowed before the capture service.
            assertEquals(G2RayVpnService.ACTION_START, context.startedIntent?.action)
            assertEquals(accountId, context.startedIntent?.getStringExtra(G2RayVpnService.EXTRA_ACCOUNT_ID))
        }
    }

    @Test
    fun startUsesStoredSelectionWithoutUiCollectors() {
        exerciseStartFromStoredSelection(staleUi = false)
    }

    @Test
    fun startUsesTheSelectedCodespaceInsteadOfAStaleUiAccountAndProfile() {
        exerciseStartFromStoredSelection(staleUi = true)
    }

    @Test
    fun wakeUsesTheStoredAccountWithoutAUiCollector() {
        withViewModel(repositoryWithProfile(), InMemorySecureStore()) { viewModel, _ ->
            assertNull(viewModel.activeAccount.value)

            viewModel.wakeUpCodespace()

            assertEquals("Configure the shared Worker or add a GitHub token for this Codespace.", viewModel.statusMessage.value)
            assertFalse(viewModel.isWakingUp.value)
        }
    }

    @Test
    fun manualWakeReportsReadyWithoutClaimingVpnStartup() {
        val repository = repositoryWithProfile()
        repository.setWorkerControlPlane("https://worker.example/", null, "fixture-not-a-real-key")
        val preferences = InMemorySecureStore()
        VpnEnginePreferences(preferences).setAutoRouteOptimizationEnabled(false)
        withViewModel(repository, preferences) { viewModel, _ ->
            val wake = CodespaceWakeCoordinator(
                workerWake = { _, _ -> WorkerRouteOutcome.Ready("https://worker.example/",
                    WorkerWakeResponse(ok = true, codespace = "fixture-space", route_ready = true)) },
                githubWake = { _, _ -> error("Unexpected direct GitHub request") }
            )
            G2RayViewModel::class.java.getDeclaredField("codespaceWakeCoordinator\$delegate")
                .apply { isAccessible = true }.set(viewModel, lazyOf(wake))

            viewModel.wakeUpCodespace()

            runBlocking {
                withTimeout(10_000L) { viewModel.statusMessage.first { it == "Codespace route is ready." } }
            }
            assertEquals(TunnelState.Disconnected, VpnStateRepository.state.value.tunnelState)
            assertFalse(VpnStateRepository.state.value.tunnelVerified)
        }
    }

    @Test
    fun manualCommandsWaitForAnInFlightAccountSelection() {
        val backing = InMemorySecureStore()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockWrites = AtomicBoolean(false)
        val store = object : SecureKeyValueStore by backing {
            override fun putStrings(values: Map<String, String?>) {
                if (blockWrites.get()) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Fixture account selection did not release" }
                }
                backing.putStrings(values)
            }
        }
        val repository = repositoryWithProfile(store)
        val profileId = repository.activeSelection.value.profileId!!
        val second = repository.addAccount("Second", "", "second-space")
        withViewModel(repository, InMemorySecureStore()) { viewModel, _ ->
            blockWrites.set(true)
            try {
                viewModel.selectAccount(second.id)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val commands = listOf<() -> Unit>(
                    { viewModel.measureProfileDelay(profileId) },
                    viewModel::refreshActiveCodespaceDelays,
                    viewModel::optimizeRoutes,
                    viewModel::wakeUpCodespace
                )
                commands.forEach { command ->
                    command()
                    assertTrue(viewModel.statusMessage.value.contains("settings change"))
                    assertTrue(viewModel.profileDelayStates.value.isEmpty())
                }
            } finally {
                release.countDown()
                runBlocking {
                    withTimeout(10_000L) { repository.activeSelection.first { it.accountId == second.id } }
                }
            }
        }
    }

    @Test
    fun unreadableVpnPreferencesDoNotCrashTheViewModelOrWriteDefaults() {
        val application = RuntimeEnvironment.getApplication() as G2RayApplication
        val repository = AccountRepository(InMemorySecureStore())
        G2RayApplication::class.java.getDeclaredField("accountRepository\$delegate")
            .apply { isAccessible = true }.set(application, lazyOf(repository))
        val store = InMemorySecureStore()
        store.putString("vpn_engine_mode", "xray_native")
        store.putString("vpn_fast_dns_enabled", "true")
        val failingStore = object : SecureKeyValueStore by store {
            override fun getString(key: String, defaultValue: String?): String? {
                throw IOException("Fixture preference read failure")
            }
        }
        val viewModelStore = ViewModelStore()
        val originalConnection = VpnStateRepository.state.value
        Dispatchers.setMain(UnconfinedTestDispatcher())
        VpnStateRepository.state.value = AppConnectionState(RouteState.Unknown, TunnelState.Disconnected, "Fixture")
        try {
            val viewModel = G2RayViewModel(application, { error("No native probe is expected") }, VpnEnginePreferences(failingStore))
            viewModelStore.put("fixture", viewModel)

            assertNotNull(viewModel.repositoryLoadError.value)
            assertEquals("xray_native", store.getString("vpn_engine_mode"))
            assertEquals("true", store.getString("vpn_fast_dns_enabled"))
            assertEquals(0, store.atomicWriteCount)
        } finally {
            viewModelStore.clear()
            Dispatchers.resetMain()
            VpnStateRepository.state.value = originalConnection
        }
    }

    @Test
    fun unreadableSettingsBlockStartupProbesWakeAndPreferenceWrites() {
        val store = InMemorySecureStore()
        store.putString("vpn_engine_mode", "xray_native")
        val failing = unreadableStore(store, AtomicBoolean(true))
        withViewModel(AccountRepository(InMemorySecureStore()), failing) { viewModel, application ->
            assertFalse(viewModel.savedDataReady.value)
            viewModel.startVpn(application)
            viewModel.refreshActiveCodespaceDelays()
            viewModel.measureProfileDelay("unused")
            viewModel.optimizeRoutes()
            viewModel.wakeUpCodespace()
            viewModel.setVpnEngineMode(VpnEngineMode.Hev)

            assertTrue(viewModel.statusMessage.value.contains("settings", ignoreCase = true))
            assertEquals("xray_native", store.getString("vpn_engine_mode"))
            assertEquals(0, store.atomicWriteCount)
            assertTrue(viewModel.profileDelayStates.value.isEmpty())
        }
    }

    @Test
    fun reloadRecoversAllPreferencesAndProfileIdsWithoutReleasingBlockedState() {
        val preferencesStore = InMemorySecureStore()
        val preferences = VpnEnginePreferences(preferencesStore)
        preferences.setMode(VpnEngineMode.XrayNative)
        preferences.setFastDnsEnabled(true)
        preferences.setIpv4OnlyEnabled(true)
        preferences.setDpiCompatibilityEnabled(true)
        preferences.setAutoRouteOptimizationEnabled(false)
        preferences.setSeamlessFailoverEnabled(false)
        val failReads = AtomicBoolean(true)
        val repository = repositoryWithProfile()
        val selection = repository.activeSelection.value
        val profiles = repository.profiles.value
        withViewModel(repository, unreadableStore(preferencesStore, failReads)) { viewModel, _ ->
            assertFalse(viewModel.savedDataReady.value)
            VpnStateRepository.state.value = VpnStateRepository.state.value.copy(tunnelState = TunnelState.Blocked)
            failReads.set(false)
            viewModel.reloadSavedData()
            awaitReload(viewModel)
            assertEquals(TunnelState.Blocked, VpnStateRepository.state.value.tunnelState)

            assertTrue(viewModel.savedDataReady.value)
            assertNull(viewModel.repositoryLoadError.value)
            assertEquals(VpnEngineMode.XrayNative, viewModel.vpnEngineMode.value)
            assertTrue(viewModel.fastDnsEnabled.value)
            assertTrue(viewModel.ipv4OnlyEnabled.value)
            assertTrue(viewModel.dpiCompatibilityEnabled.value)
            assertFalse(viewModel.autoRouteOptimizationEnabled.value)
            assertFalse(viewModel.seamlessFailoverEnabled.value)
            assertEquals(selection, repository.activeSelection.value)
            assertEquals(profiles, repository.profiles.value)
            assertEquals(0, preferencesStore.atomicWriteCount)
        }
    }

    @Test
    fun settingsFailureDoesNotInvalidateAVerifiedTunnelAndStopStillDispatches() {
        withViewModel(repositoryWithProfile(), unreadableStore(InMemorySecureStore(), AtomicBoolean(true))) { viewModel, application ->
            val connected = AppConnectionState(
                RouteState.Ready, TunnelState.Connected, "Fixture connected",
                routeReady = true, tunnelVerified = true
            )
            VpnStateRepository.state.value = connected
            viewModel.startVpn(application)
            viewModel.refreshActiveCodespaceDelays()
            viewModel.reloadSavedData()
            assertEquals(connected, VpnStateRepository.state.value)
            assertFalse(viewModel.isReloadingSavedData.value)

            val context = object : ContextWrapper(application) {
                var stopAction: String? = null
                override fun startService(service: Intent): ComponentName? {
                    stopAction = service.action
                    return ComponentName(application, G2RayVpnService::class.java)
                }
            }
            viewModel.stopVpn(context)
            assertEquals(G2RayVpnService.ACTION_STOP, context.stopAction)
        }
    }

    @Test
    fun retainedBlockedCaptureDoesNotRunPingAllOrChangeConnectionState() {
        withViewModel(repositoryWithProfile(), InMemorySecureStore()) { viewModel, _ ->
            val blocked = AppConnectionState(RouteState.Error, TunnelState.Blocked, "Fixture blocked")
            VpnStateRepository.state.value = blocked

            viewModel.refreshActiveCodespaceDelays()

            assertEquals("Stop VPN before refreshing real delays.", viewModel.statusMessage.value)
            assertTrue(viewModel.profileDelayStates.value.isEmpty())
            assertEquals(blocked, VpnStateRepository.state.value)
        }
    }

    @Test
    fun retainedBlockedCaptureDoesNotRunIndividualNativePing() {
        val repository = repositoryWithProfile()
        val profileId = repository.activeSelection.value.profileId!!
        withViewModel(repository, InMemorySecureStore()) { viewModel, _ ->
            val blocked = AppConnectionState(RouteState.Error, TunnelState.Blocked, "Fixture blocked")
            VpnStateRepository.state.value = blocked

            viewModel.measureProfileDelay(profileId)

            assertEquals("Stop VPN before running real-delay test.", viewModel.profileDelayStates.value[profileId]?.error)
            assertFalse(viewModel.profileDelayStates.value[profileId]?.testing == true)
            assertEquals(blocked, VpnStateRepository.state.value)
        }
    }

    @Test
    fun repeatedReloadWhileBusyDoesNotCreateASpuriousPreferenceFailure() {
        val backing = InMemorySecureStore()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockReads = AtomicBoolean(false)
        val store = object : SecureKeyValueStore by backing {
            override fun getString(key: String, defaultValue: String?): String? {
                if (blockReads.get() && key == "vpn_engine_mode") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Fixture reload did not release" }
                }
                return backing.getString(key, defaultValue)
            }
        }
        withViewModel(repositoryWithProfile(), store) { viewModel, _ ->
            blockReads.set(true)
            try {
                viewModel.reloadSavedData()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                viewModel.reloadSavedData()
                assertTrue(viewModel.isReloadingSavedData.value)
                assertNull(viewModel.repositoryLoadError.value)
            } finally {
                release.countDown()
            }
            awaitReload(viewModel)
            assertTrue(viewModel.savedDataReady.value)
            assertNull(viewModel.repositoryLoadError.value)
        }
    }

    @Test
    fun defaultApplicationAndViewModelRecoverADeferredProviderFailureWithOneStore() {
        val application = RuntimeEnvironment.getApplication() as G2RayApplication
        val backing = application.getSharedPreferences("default-provider-fixture", Context.MODE_PRIVATE)
        assertTrue(backing.edit().putString("vpn_engine_mode", "xray_native").commit())
        val failOpen = AtomicBoolean(true)
        var opens = 0
        val encryptedStore = EncryptedPrefsStore {
            opens += 1
            if (failOpen.get()) throw GeneralSecurityException("Fixture provider unavailable")
            backing
        }
        G2RayApplication::class.java.getDeclaredField("secureStore\$delegate")
            .apply { isAccessible = true }.set(application, lazyOf(encryptedStore))
        val originalConnection = VpnStateRepository.state.value
        VpnStateRepository.state.value = AppConnectionState(RouteState.Unknown, TunnelState.Disconnected, "Fixture")
        val viewModelStore = ViewModelStore()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val viewModel = G2RayViewModel(application)
            viewModelStore.put("fixture", viewModel)
            assertFalse(viewModel.savedDataReady.value)
            assertNotNull(viewModel.repositoryLoadError.value)
            assertEquals(2, opens)
            failOpen.set(false)
            viewModel.reloadSavedData()
            awaitReload(viewModel)
            assertTrue(viewModel.savedDataReady.value)
            assertNull(viewModel.repositoryLoadError.value)
            assertEquals(VpnEngineMode.XrayNative, viewModel.vpnEngineMode.value)
            assertEquals(3, opens)
            viewModel.reloadSavedData()
            awaitReload(viewModel)
            assertEquals(3, opens)
        } finally {
            viewModelStore.clear()
            Dispatchers.resetMain()
            VpnStateRepository.state.value = originalConnection
        }
    }

    private fun exerciseStartFromStoredSelection(staleUi: Boolean) {
        val repository = repositoryWithProfile()
        val first = repository.activeSelection.value
        val dispatcher = UnconfinedTestDispatcher()
        withViewModel(repository, InMemorySecureStore(), dispatcher) { viewModel, application ->
            if (staleUi) {
                runBlocking {
                    withTimeout(10_000L) { viewModel.activeAccount.first { it?.id == first.accountId } }
                    withTimeout(10_000L) { viewModel.activeProfile.first { it?.id == first.profileId } }
                }
                dispatcher.scheduler.advanceTimeBy(5_001L)
                dispatcher.scheduler.runCurrent()
                val second = repository.addAccount("Second", "", "second-space")
                val profile = VlessUriParser.parseFirstProfile(
                    "vless://00000000-0000-4000-8000-000000000001@203.0.113.2:443" +
                        "?encryption=none&security=tls&type=ws&sni=second.example&host=second.example&path=%2Fws"
                ).getOrThrow()
                repository.importProfile(second.id, profile.name, profile.rawUri, profile)
                repository.selectAccount(second.id)
                assertEquals(first.accountId, viewModel.activeAccount.value?.id)
                assertEquals(first.profileId, viewModel.activeProfile.value?.id)
            } else {
                assertNull(viewModel.activeAccount.value)
                assertNull(viewModel.activeProfile.value)
            }
            val selected = repository.activeSelection.value
            val expected = repository.profiles.value.single { it.id == selected.profileId }
            @Suppress("UNCHECKED_CAST")
            val availability = G2RayViewModel::class.java.getDeclaredField("_isVpnCoreAvailable")
                .apply { isAccessible = true }.get(viewModel) as MutableStateFlow<Boolean>
            availability.value = true
            val context = RecordingServiceContext(application)

            viewModel.startVpn(context)

            assertEquals(G2RayVpnService.ACTION_START, context.startedIntent?.action)
            assertEquals(selected.accountId, context.startedIntent?.getStringExtra(G2RayVpnService.EXTRA_ACCOUNT_ID))
            assertEquals(expected.rawUri, context.startedIntent?.getStringExtra(G2RayVpnService.EXTRA_RAW_PROFILE_URI))
            assertEquals(selected, repository.activeSelection.value)
        }
    }

    private class RecordingServiceContext(base: Context, private val rejectStart: Boolean = false) : ContextWrapper(base) {
        var startedIntent: Intent? = null
        var starts = 0
        override fun getApplicationContext(): Context = this
        override fun startService(service: Intent): ComponentName {
            if (rejectStart) throw SecurityException("Fixture service start rejected")
            starts++
            startedIntent = service
            return ComponentName(this, G2RayVpnService::class.java)
        }
        override fun startForegroundService(service: Intent): ComponentName = startService(service)
    }

    private fun repositoryWithProfile(store: SecureKeyValueStore = InMemorySecureStore()): AccountRepository {
        val repository = AccountRepository(store)
        val account = repository.addAccount("Fixture", "", "fixture-space")
        val profile = VlessUriParser.parseFirstProfile(
            "vless://00000000-0000-4000-8000-000000000001@fixture.example:443" +
                "?encryption=none&security=tls&type=ws&sni=fixture.example&host=fixture.example&path=%2Fws"
        ).getOrThrow()
        repository.importProfile(account.id, profile.name, profile.rawUri, profile)
        return repository
    }

    private fun unreadableStore(backing: SecureKeyValueStore, failReads: AtomicBoolean): SecureKeyValueStore =
        object : SecureKeyValueStore by backing {
            override fun getString(key: String, defaultValue: String?): String? {
                if (failReads.get()) throw IOException("Fixture preference read failure")
                return backing.getString(key, defaultValue)
            }
        }

    private fun awaitReload(viewModel: G2RayViewModel) = runBlocking {
        withTimeout(10_000L) {
            combine(viewModel.isReloadingSavedData, viewModel.savedDataReady, viewModel.repositoryLoadError) { busy, ready, error ->
                !busy && ready && error == null
            }.first { it }
        }
    }

    private fun withViewModel(
        repository: AccountRepository,
        preferenceStore: SecureKeyValueStore,
        dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
        body: (G2RayViewModel, G2RayApplication) -> Unit
    ) {
        val application = RuntimeEnvironment.getApplication() as G2RayApplication
        G2RayApplication::class.java.getDeclaredField("accountRepository\$delegate")
            .apply { isAccessible = true }.set(application, lazyOf(repository))
        val originalConnection = VpnStateRepository.state.value
        VpnStateRepository.state.value = AppConnectionState(RouteState.Unknown, TunnelState.Disconnected, "Fixture")
        val viewModelStore = ViewModelStore()
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = G2RayViewModel(application, { error("Unexpected native route probe") }, VpnEnginePreferences(preferenceStore))
            viewModelStore.put("fixture", viewModel)
            body(viewModel, application)
        } finally {
            viewModelStore.clear()
            Dispatchers.resetMain()
            VpnStateRepository.state.value = originalConnection
        }
    }
}
