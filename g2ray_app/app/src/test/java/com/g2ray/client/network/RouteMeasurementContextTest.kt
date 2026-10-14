package com.g2ray.client.network

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.g2ray.client.vpn.G2RayVpnService
import com.g2ray.client.vpn.SocketProtector
import com.g2ray.client.G2RayApplication
import com.g2ray.client.data.AccountRepository
import com.g2ray.client.data.AccountRepositoryTest.InMemorySecureStore
import com.g2ray.client.data.SecureKeyValueStore
import com.g2ray.client.parser.VlessUriParser
import com.g2ray.client.ui.G2RayViewModel
import com.g2ray.client.vpn.XrayBridge
import com.g2ray.client.vpn.XrayCallResult
import com.g2ray.client.vpn.XrayCoreManager
import com.g2ray.client.vpn.XrayPingBatchConfig
import com.g2ray.client.vpn.XrayPingBatchResult
import com.g2ray.client.vpn.XrayPingResult
import com.g2ray.client.vpn.XrayRuntimeCapabilities
import com.g2ray.client.vpn.VpnEnginePreferences
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.N, Build.VERSION_CODES.S], application = G2RayApplication::class, shadows = [RouteMeasurementContextTest.ConnectivityShadow::class])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RouteMeasurementContextTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(application.contentResolver, Settings.Global.BOOT_COUNT, 10)
        defaultNetwork = null
        capabilities.clear()
        callbacks.clear()
        failRegistration = false
        SocketProtector.vpnService = null
        SocketProtector.updateUnderlyingNetwork(null)
    }

    @After
    fun tearDown() {
        SocketProtector.vpnService = null
        SocketProtector.updateUnderlyingNetwork(null)
    }

    private fun physical(id: Int): Network = ShadowNetwork.newInstance(id).also {
        capabilities[it] = NetworkCapabilities().also { caps ->
            val shadow = Shadow.extract<ShadowNetworkCapabilities>(caps)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    @Test
    fun keysSurviveProcessContextRecreationButChangeOnBootOrNetworkReplacement() {
        val first = physical(100)
        defaultNetwork = first
        val context = RouteMeasurementContext(application)
        val key = context.currentKey()
        assertNotNull(key)
        assertEquals(key, RouteMeasurementContext(application).currentKey())
        defaultNetwork = physical(101)
        assertNotEquals(key, context.currentKey())
        defaultNetwork = first
        Settings.Global.putInt(application.contentResolver, Settings.Global.BOOT_COUNT, 11)
        assertNotEquals(key, RouteMeasurementContext(application).currentKey())
    }

    @Test
    fun vpnOverlayNeverBecomesTheMeasurementNetwork() {
        val physical = physical(100)
        defaultNetwork = physical(200)
        capabilities[defaultNetwork] = NetworkCapabilities().also {
            val shadow = Shadow.extract<ShadowNetworkCapabilities>(it)
            shadow.addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val context = RouteMeasurementContext(application)
        assertNull(context.currentKey())
        SocketProtector.vpnService = G2RayVpnService()
        SocketProtector.updateUnderlyingNetwork(physical)
        assertEquals(context.keyFor(physical), context.currentKey())
        SocketProtector.updateUnderlyingNetwork(null)
        assertNull(context.currentKey())
    }

    @Test
    fun unavailableNetworksAndUnknownKeysNeverCertifyHistoricalSamples() {
        val context = RouteMeasurementContext(application)
        assertNull(context.currentKey())
        val offline = physical(100)
        capabilities[offline] = NetworkCapabilities()
        assertNull(context.keyFor(offline))
        assertFalse(RouteMeasurementContext.matches(null, null))
        assertFalse(RouteMeasurementContext.matches("", ""))
        assertFalse(RouteMeasurementContext.matches("network-a", "network-b"))
        assertTrue(RouteMeasurementContext.matches("network-a", "network-a"))
    }

    @Test
    fun unavailableBootCountUsesOnlyAProcessContextEpoch() {
        Settings.Global.putInt(application.contentResolver, Settings.Global.BOOT_COUNT, -1)
        defaultNetwork = physical(100)
        val context = RouteMeasurementContext(application)
        assertNotNull(context.currentKey())
        assertEquals(context.currentKey(), context.currentKey())
        assertNotEquals(context.currentKey(), RouteMeasurementContext(application).currentKey())
    }

    @Test
    fun observationTracksDefaultAndSelectedPhysicalNetworksAndUnregistersOnClose() = runTest {
        defaultNetwork = physical(100)
        val context = RouteMeasurementContext(application)
        val observed = mutableListOf<String?>()
        val job = backgroundScope.launch { context.changes().toList(observed) }
        runCurrent()
        assertEquals(context.currentKey(), observed.last())
        assertEquals(1, callbacks.size)
        val before = observed.last()
        defaultNetwork = physical(101)
        callbacks.single().onAvailable(defaultNetwork!!)
        runCurrent()
        assertNotEquals(before, observed.last())
        SocketProtector.vpnService = G2RayVpnService()
        val selected = physical(102)
        SocketProtector.updateUnderlyingNetwork(selected)
        runCurrent()
        assertEquals(context.keyFor(selected), observed.last())
        SocketProtector.updateUnderlyingNetwork(null)
        runCurrent()
        assertNull(observed.last())
        job.cancelAndJoin()
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun failedObservationRegistrationIsFailClosedAndDoesNotCrashTheUi() = runTest {
        defaultNetwork = physical(100)
        failRegistration = true
        val observed = mutableListOf<String?>()
        val context = RouteMeasurementContext(application)
        val job = backgroundScope.launch { context.changes().toList(observed) }
        runCurrent()
        assertEquals(listOf<String?>(null), observed)
        assertTrue(callbacks.isEmpty())
        assertNull(context.capture())
        job.cancelAndJoin()
    }

    @Test
    fun switchAwayAndBackInvalidatesAnInFlightProbeWithoutErasingNetworkHistory() = runTest {
        val first = physical(100)
        defaultNetwork = first
        val context = RouteMeasurementContext(application)
        val job = backgroundScope.launch { context.changes().toList(mutableListOf()) }
        runCurrent()
        val before = context.capture()!!
        defaultNetwork = physical(101)
        callbacks.single().onAvailable(defaultNetwork!!)
        defaultNetwork = first
        callbacks.single().onAvailable(first)
        assertEquals(before.networkKey, context.currentKey())
        assertFalse(context.isCurrent(before))
        job.cancelAndJoin()
    }

    @Test
    fun conflatedUnderlyingNetworkChangesStillInvalidateTheProbe() = runTest {
        defaultNetwork = physical(100)
        val context = RouteMeasurementContext(application)
        val job = backgroundScope.launch { context.changes().toList(mutableListOf()) }
        runCurrent()
        val before = context.capture()!!
        SocketProtector.updateUnderlyingNetwork(physical(101))
        SocketProtector.updateUnderlyingNetwork(null)
        assertEquals(before.networkKey, context.currentKey())
        assertFalse(context.isCurrent(before))
        job.cancelAndJoin()
    }

    @Test
    fun unobservedNetworkCannotCertifyAnUninterruptedMeasurement() {
        defaultNetwork = physical(100)
        val context = RouteMeasurementContext(application)
        assertNotNull(context.currentKey())
        assertNull(context.capture())
    }

    @Test
    fun pingAllPersistsScopedResultsOnAStableNetwork() = runBlocking {
        exercisePingAll(changeNetwork = false)
    }

    @Test
    fun pingAllDiscardsMidScanHandoffResultsWithoutChangingSavedProfilesOrSelection() = runBlocking {
        exercisePingAll(changeNetwork = true)
    }

    @Test
    fun pingAllUsesStoredProfilesWithoutAUiListCollector() = runBlocking {
        exercisePingAll(changeNetwork = false, observeProfiles = false)
    }

    @Test
    fun pingAllUsesTheSelectedAccountWhenTheUiListIsStale() = runBlocking {
        exercisePingAll(changeNetwork = false, switchAccount = true)
    }

    @Test
    fun unavailableCleanupUsesTheSelectedAccountWhenTheUiListIsStale() = runBlocking {
        defaultNetwork = physical(100)
        val repository = AccountRepository(InMemorySecureStore())
        val first = repository.addAccount("First", "", "first-space")
        val second = repository.addAccount("Second", "", "second-space")
        val key = (application as G2RayApplication).routeMeasurementContext.currentKey()!!
        listOf(first, second).forEachIndexed { index, account ->
            val parsed = VlessUriParser.parse(
                "vless://00000000-0000-4000-8000-000000000001@203.0.113.${index + 1}:443" +
                    "?encryption=none&security=tls&sni=fixture.example&type=ws&host=fixture.example&path=%2Fws"
            ).getOrThrow()
            val stored = repository.importProfile(account.id, account.displayName, parsed.rawUri, parsed)
            repeat(2) {
                repository.recordProfileDelay(stored.id, false, null, "Fixture unavailable", networkKey = key)
            }
        }
        repository.selectAccount(first.id)
        G2RayApplication::class.java.getDeclaredField("accountRepository\$delegate")
            .apply { isAccessible = true }.set(application, lazyOf(repository))
        val dispatcher = UnconfinedTestDispatcher()
        val store = ViewModelStore()
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = G2RayViewModel(application, { error("Unexpected route probe") }, VpnEnginePreferences(InMemorySecureStore()))
            store.put("fixture", viewModel)
            withTimeout(10_000L) { viewModel.measurementNetworkKey.first { it != null } }
            withTimeout(10_000L) {
                viewModel.activeCodespaceProfiles.first { it.singleOrNull()?.accountId == first.id }
            }
            dispatcher.scheduler.advanceTimeBy(5_001L)
            dispatcher.scheduler.runCurrent()
            repository.selectAccount(second.id)
            assertEquals(first.id, viewModel.activeCodespaceProfiles.value.single().accountId)

            viewModel.deleteUnavailableProfiles()

            withTimeout(10_000L) { viewModel.statusMessage.first { it.startsWith("Deleted ") } }
            assertEquals(second.id, repository.activeSelection.value.accountId)
            assertEquals(listOf(first.id), repository.profiles.value.map { it.accountId })
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun unreadableScanHistoryPausesAutomaticScanningWithoutBlockingSavedSettings() {
        defaultNetwork = physical(100)
        val repository = AccountRepository(InMemorySecureStore())
        repository.addAccount("Fixture", "", "fixture-space")
        G2RayApplication::class.java.getDeclaredField("accountRepository\$delegate")
            .apply { isAccessible = true }.set(application, lazyOf(repository))
        val backing = InMemorySecureStore()
        val preferencesStore = object : SecureKeyValueStore by backing {
            override fun getString(key: String, defaultValue: String?): String? {
                if (key.startsWith("vpn_route_optimization_attempt_")) {
                    throw java.io.IOException("Fixture scan-history read failure")
                }
                return backing.getString(key, defaultValue)
            }
        }
        val store = ViewModelStore()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val viewModel = G2RayViewModel(application, { error("Unexpected route probe") }, VpnEnginePreferences(preferencesStore))
            store.put("fixture", viewModel)
            assertNotNull((application as G2RayApplication).routeMeasurementContext.currentKey())

            viewModel.autoOptimizeRoutesIfNeeded()

            assertTrue(viewModel.statusMessage.value.contains("scan history"))
            assertTrue(viewModel.savedDataReady.value)
            assertNull(viewModel.repositoryLoadError.value)
            assertFalse(viewModel.isRouteOptimizing.value)
            assertEquals(0, backing.atomicWriteCount)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private suspend fun exercisePingAll(
        changeNetwork: Boolean, observeProfiles: Boolean = true, switchAccount: Boolean = false
    ) {
        defaultNetwork = physical(100)
        val repository = AccountRepository(InMemorySecureStore())
        val account = repository.addAccount("Fixture", "", "fixture-space")
        listOf("203.0.113.1", "203.0.113.2").forEach { address ->
            val parsed = VlessUriParser.parse(
                "vless://00000000-0000-4000-8000-000000000001@$address:443" +
                    "?encryption=none&security=tls&sni=fixture.example&type=ws&host=fixture.example&path=%2Fws"
            ).getOrThrow()
            repository.importProfile(account.id, address, parsed.rawUri, parsed)
        }
        G2RayApplication::class.java.getDeclaredField("accountRepository\$delegate")
            .apply { isAccessible = true }.set(application, lazyOf(repository))
        val bridge = DelayBridge {
            if (changeNetwork) {
                defaultNetwork = physical(101)
                callbacks.toList().forEach { it.onAvailable(defaultNetwork!!) }
            }
        }
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val viewModel = G2RayViewModel(application, { XrayCoreManager(bridge) }, VpnEnginePreferences(InMemorySecureStore()))
            store.put("fixture", viewModel)
            withTimeout(10_000L) { viewModel.measurementNetworkKey.first { it != null } }
            if (observeProfiles) {
                withTimeout(10_000L) { viewModel.activeCodespaceProfiles.first { it.size == 2 } }
            } else {
                assertTrue(viewModel.activeCodespaceProfiles.value.isEmpty())
            }
            if (switchAccount) {
                dispatcher.scheduler.advanceTimeBy(5_001L)
                dispatcher.scheduler.runCurrent()
                val second = repository.addAccount("Second", "", "second-space")
                listOf("203.0.113.3", "203.0.113.4").forEach { address ->
                    val parsed = VlessUriParser.parse(
                        "vless://00000000-0000-4000-8000-000000000001@$address:443" +
                            "?encryption=none&security=tls&sni=fixture.example&type=ws&host=fixture.example&path=%2Fws"
                    ).getOrThrow()
                    repository.importProfile(second.id, address, parsed.rawUri, parsed)
                }
                repository.selectAccount(second.id)
                assertTrue(viewModel.activeCodespaceProfiles.value.all { it.accountId == account.id })
            }
            val selection = repository.activeSelection.value
            val before = repository.profiles.value.filter { it.accountId == selection.accountId }
            val untested = repository.profiles.value.filter { it.accountId != selection.accountId }
            viewModel.refreshActiveCodespaceDelays()
            val states = try {
                withTimeout(10_000L) {
                    viewModel.profileDelayStates.first { results ->
                        before.all { results[it.id]?.testing == false }
                    }
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Ping completion timed out: status=${viewModel.statusMessage.value}; " +
                    "optimizing=${viewModel.isRouteOptimizing.value}; batchCalls=${bridge.batchCalls}; " +
                    "vpn=${com.g2ray.client.model.VpnStateRepository.state.value.tunnelState}; " +
                    "states=${viewModel.profileDelayStates.value}", error)
            }
            assertEquals(1, bridge.batchCalls)
            assertEquals(selection, repository.activeSelection.value)
            assertEquals(untested, repository.profiles.value.filter { it.accountId != selection.accountId })
            if (changeNetwork) {
                assertEquals(before, repository.profiles.value)
                assertTrue(states.values.all { it.delayMs == null && it.error != null })
            } else {
                val key = (application as G2RayApplication).routeMeasurementContext.currentKey()
                assertNotNull(key)
                assertTrue(repository.profiles.value.filter { it.accountId == selection.accountId }
                    .all { it.lastDelayMs == 100L && it.lastDelayNetworkKey == key })
                assertTrue(states.values.all { it.delayMs == 100L && it.networkKey == key })
            }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private class DelayBridge(private val duringBatch: () -> Unit) : XrayBridge {
        var batchCalls = 0
        override fun isAvailable() = true
        override fun capabilities() = XrayRuntimeCapabilities(
            true, false, false, false, false, false, false, false, pingFound = true, invokeFound = true
        )
        override fun pingBatch(configs: List<XrayPingBatchConfig>, timeoutSeconds: Int, testUrl: String): XrayPingBatchResult {
            batchCalls++
            duringBatch()
            return XrayPingBatchResult(true, configs.map { XrayPingResult(true, 100L, null) }, null)
        }
        override fun ping(base64Request: String): XrayPingResult = error("Unexpected sequential native fallback")
        override fun registerDialerController(protectFd: (Int) -> Boolean) = error("Unexpected VPN startup")
        override fun registerListenerController(protectFd: (Int) -> Boolean) = error("Unexpected VPN startup")
        override fun setTunFd(fd: Int) = error("Unexpected VPN startup")
        override fun newRunFromJsonRequest(datDir: String, configJson: String): String = error("Unexpected VPN startup")
        override fun runFromJson(base64Request: String): XrayCallResult = error("Unexpected VPN startup")
        override fun stop(): XrayCallResult = error("Unexpected VPN stop")
        override fun getState() = false
    }

    @Implements(ConnectivityManager::class)
    class ConnectivityShadow {
        @Implementation
        fun getActiveNetwork(): Network? = defaultNetwork

        @Implementation
        fun getNetworkCapabilities(network: Network): NetworkCapabilities? = capabilities[network]

        @Implementation
        fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            if (failRegistration) throw IllegalStateException("Network callback quota reached")
            callbacks += callback
        }

        @Implementation
        fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            check(callbacks.remove(callback))
        }
    }

    companion object {
        private var defaultNetwork: Network? = null
        private val capabilities = mutableMapOf<Network?, NetworkCapabilities>()
        private val callbacks = mutableSetOf<ConnectivityManager.NetworkCallback>()
        private var failRegistration = false
    }
}
