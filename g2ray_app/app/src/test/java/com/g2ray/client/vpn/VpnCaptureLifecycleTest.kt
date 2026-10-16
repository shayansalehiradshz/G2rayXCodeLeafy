package com.g2ray.client.vpn

import android.app.Service
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.g2ray.client.G2RayApplication
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.VpnStateRepository
import com.g2ray.client.data.EncryptedPrefsStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowNetwork

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.N, Build.VERSION_CODES.S], application = G2RayApplication::class,
    shadows = [VpnCaptureLifecycleTest.CaptureBuilderShadow::class, VpnCaptureLifecycleTest.PolicyShadow::class])
class VpnCaptureLifecycleTest {
    private val originalState = VpnStateRepository.state.value

    @Before fun before() {
        Dispatchers.setMain(StandardTestDispatcher())
        created.clear()
        configs.clear()
        alwaysOn = false
        lockdown = false
        refuseInterface = false
        onEstablish = null
    }
    @After fun after() {
        Dispatchers.resetMain()
        VpnStateRepository.state.value = originalState
        created.forEach { runCatching { it.close() } }
    }

    @Test fun repeatedInvalidStartReusesTheIdleCaptureGuard() = runTest {
        withService { service ->
            val request = Intent(G2RayVpnService.ACTION_START)
                .putExtra(G2RayVpnService.EXTRA_RAW_PROFILE_URI, "invalid-profile")
            service.onStartCommand(request, 0, 1)
            finishCleanup(service)
            val guard = field(service, "vpnInterface") as ParcelFileDescriptor
            service.onStartCommand(request, 0, 2)
            finishCleanup(service)
            assertSame(guard, field(service, "vpnInterface"))
            assertTrue(guard.fileDescriptor.valid())
            assertEquals(1, configs.size)
            assertEquals(TunnelState.Blocked, VpnStateRepository.state.value.tunnelState)
        }
    }

    @Test fun terminalFailureKeepsThePhysicalNetworkForManualWake() = runTest {
        withService { service ->
            invoke(service, "installTunInterface", 0L)
            invoke(service, "registerUnderlyingNetworkMonitor")
            val callback = field(service, "underlyingNetworkCallback")
            assertNotNull(callback)
            val physical = ShadowNetwork.newInstance(101)
            invoke(service, "applyUnderlyingNetwork", physical, false)
            invoke(service, "failClosed", "Fixture configuration failure")
            finishCleanup(service)

            assertSame(callback, field(service, "underlyingNetworkCallback"))
            assertEquals(physical, SocketProtector.underlyingNetworks.value)
            assertEquals("203.0.113.1", SocketProtector.lookupControlPlane("203.0.113.1").single().hostAddress)
            assertEquals(TunnelState.Blocked, VpnStateRepository.state.value.tunnelState)

            service.onStartCommand(Intent(G2RayVpnService.ACTION_STOP), 0, 2)
            finishCleanup(service)
            assertNull(field(service, "underlyingNetworkCallback"))
            assertNull(SocketProtector.underlyingNetworks.value)
        }
    }

    @Test fun invalidStartupStillRegistersThePhysicalNetworkForWake() = runTest {
        withService { service ->
            service.onStartCommand(Intent(G2RayVpnService.ACTION_START)
                .putExtra(G2RayVpnService.EXTRA_RAW_PROFILE_URI, "invalid-profile"), 0, 1)
            finishCleanup(service)
            assertEquals(TunnelState.Blocked, VpnStateRepository.state.value.tunnelState)
            assertNotNull("Capture without a working profile must still allow manual wake",
                field(service, "underlyingNetworkCallback"))
        }
    }

    @Test fun blockedNetworkChangesKeepCaptureWithoutStartingRecovery() = runTest {
        withService { service ->
            val guard = invoke(service, "installTunInterface", 0L) as ParcelFileDescriptor
            invoke(service, "failClosed", "Fixture terminal failure")
            finishCleanup(service)
            val blocked = VpnStateRepository.state.value
            val first = ShadowNetwork.newInstance(101)
            val replacement = ShadowNetwork.newInstance(102)
            for (network in listOf(first, null, replacement)) {
                invoke(service, "applyUnderlyingNetwork", network, true)
                runCurrent()
                assertEquals(network, SocketProtector.underlyingNetworks.value)
                assertNull("An idle error guard must not retry implicitly", field(service, "networkHandoffJob"))
                assertEquals(blocked, VpnStateRepository.state.value)
                assertSame(guard, field(service, "vpnInterface"))
                assertTrue(guard.fileDescriptor.valid())
            }
        }
    }

    @Test fun retiredCleanupCannotOverwriteAReplacementService() = runTest {
        withService { retired ->
            invoke(retired, "installTunInterface", 0L)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            setField(retired, "fullVpnDataPathProbe", object : VpnDataPathProbe {
                override suspend fun probe() = RunningRouteResult(false)
                override suspend fun reset() { entered.complete(Unit); release.await() }
            })
            retired.onDestroy()
            runCurrent()
            assertTrue(entered.isCompleted)

            withService { replacement ->
                invoke(replacement, "installTunInterface", 0L)
                val network = ShadowNetwork.newInstance(102)
                invoke(replacement, "applyUnderlyingNetwork", network, false)
                VpnStateRepository.state.value = VpnStateRepository.state.value.copy(
                    tunnelState = TunnelState.Starting, message = "Replacement is starting")
                invoke(replacement, "createNotificationChannel")
                invoke(replacement, "updateNotification", "Replacement is starting")
                val notifications = replacement.getSystemService(NotificationManager::class.java)
                assertTrue(notifications.activeNotifications.any { it.id == G2RayVpnService.NOTIFICATION_ID })
                val starting = VpnStateRepository.state.value
                release.complete(Unit)
                finishCleanup(retired)

                assertSame(replacement, SocketProtector.vpnService)
                assertEquals(network, SocketProtector.underlyingNetworks.value)
                assertEquals("Retired cleanup must not publish Disconnected over a newer start",
                    starting, VpnStateRepository.state.value)
                assertTrue("Retired cleanup must not cancel the new service notification",
                    notifications.activeNotifications.any { it.id == G2RayVpnService.NOTIFICATION_ID })
                assertTrue((field(replacement, "vpnInterface") as ParcelFileDescriptor).fileDescriptor.valid())
            }
        }
    }

    @Test fun destroyedServiceCannotQueueAnotherStartOrPublishStalePolicy() = runTest {
        withService { service ->
            alwaysOn = true
            lockdown = true
            service.onDestroy()
            assertEquals(Service.START_NOT_STICKY,
                service.onStartCommand(Intent(G2RayVpnService.ACTION_START), 0, 1))
            assertNull(field(service, "startJob"))
            assertNull(VpnStateRepository.state.value.alwaysOnVpn)
            assertNull(VpnStateRepository.state.value.blockConnectionsWithoutVpn)
        }
    }

    @Test fun systemAlwaysOnStartIsHandled() = runTest {
        withService { service ->
            assertEquals(Service.START_STICKY,
                service.onStartCommand(Intent(VpnService.SERVICE_INTERFACE), 0, 1))
            assertNotNull("System startup must actually schedule a VPN connection", field(service, "startJob"))
        }
    }

    @Test fun terminalFailureDoesNotReleaseTheCapturedTraffic() = runTest {
        withService { service ->
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                setField(service, "vpnInterface", pipe[0])
                setField(service, "desiredConnected", true)
                invoke(service, "failClosed", "Fixture rejected configuration")
                runCurrent()
                assertSame("A terminal error must keep the capture interface", pipe[0], field(service, "vpnInterface"))
                assertTrue("The capture descriptor must remain open", pipe[0].fileDescriptor.valid())
                assertFalse(VpnStateRepository.state.value.tunnelVerified)
                assertEquals("Blocked", VpnStateRepository.state.value.tunnelState.name)
            } finally {
                setField(service, "vpnInterface", null)
                pipe.forEach { it.close() }
            }
        }
    }

    @Test fun malformedProfileIsRejectedAfterTrafficCapture() = runTest {
        withService { service ->
            service.onStartCommand(Intent(G2RayVpnService.ACTION_START)
                .putExtra(G2RayVpnService.EXTRA_RAW_PROFILE_URI, "invalid-profile"), 0, 1)
            finishCleanup(service)
            assertEquals(1, configs.size)
            val guard = field(service, "vpnInterface") as ParcelFileDescriptor
            assertTrue(guard.fileDescriptor.valid())
            assertEquals("Blocked", VpnStateRepository.state.value.tunnelState.name)
        }
    }

    @Test fun newSystemStartOverridesAnEarlierUserStop() = runTest {
        withService { service ->
            val store = fixtureStore(service).apply { putString("vpn_desired_connected", "false") }
            setField(service, "secureStore\$delegate", lazyOf(store))
            service.onStartCommand(Intent(VpnService.SERVICE_INTERFACE), 0, 1)
            finishCleanup(service)
            assertNotNull("A new system start must not be suppressed by the old Stop", field(service, "vpnInterface"))
            assertEquals("Blocked", VpnStateRepository.state.value.tunnelState.name)
            assertEquals("true", store.getString("vpn_desired_connected", null))
        }
    }

    @Test fun stickyRestartAfterExplicitStopDoesNotReconnect() = runTest {
        withService { service ->
            val store = fixtureStore(service).apply { putString("vpn_desired_connected", "false") }
            setField(service, "secureStore\$delegate", lazyOf(store))
            service.onStartCommand(null, 0, 1)
            finishCleanup(service)
            assertNull(field(service, "vpnInterface"))
            assertEquals(TunnelState.Disconnected, VpnStateRepository.state.value.tunnelState)
        }
    }

    @Test fun unreadablePreferencesCannotRemoveTheCaptureGuard() = runTest {
        withService { service ->
            val store = EncryptedPrefsStore { throw IOException("Fixture unavailable preferences") }
            setField(service, "secureStore\$delegate", lazyOf(store))
            service.onStartCommand(Intent(G2RayVpnService.ACTION_START), 0, 1)
            finishCleanup(service)
            assertEquals("Blocked", VpnStateRepository.state.value.tunnelState.name)
            assertNotNull(field(service, "vpnInterface"))
        }
    }

    @Test fun refusedInterfaceNeverReportsProtectionOrConnection() = runTest {
        withService { service ->
            refuseInterface = true
            service.onStartCommand(Intent(G2RayVpnService.ACTION_START), 0, 1)
            finishCleanup(service)
            assertNull(field(service, "vpnInterface"))
            assertEquals(TunnelState.Error, VpnStateRepository.state.value.tunnelState)
            assertFalse(VpnStateRepository.state.value.tunnelVerified)
            assertTrue(VpnStateRepository.state.value.message.contains("No capture interface"))
        }
    }

    @Test fun explicitStopOverridesInFlightErrorCleanup() = runTest {
        withService { service ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            setField(service, "fullVpnDataPathProbe", object : VpnDataPathProbe {
                override suspend fun probe() = RunningRouteResult(false)
                override suspend fun reset() { entered.complete(Unit); release.await() }
            })
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                setField(service, "vpnInterface", pipe[0])
                setField(service, "desiredConnected", true)
                invoke(service, "failClosed", "Fixture configuration failure")
                runCurrent()
                assertTrue(entered.isCompleted)
                service.onStartCommand(Intent(G2RayVpnService.ACTION_STOP), 0, 2)
                release.complete(Unit)
                finishCleanup(service)
                assertNull(field(service, "vpnInterface"))
                assertFalse(pipe[0].fileDescriptor.valid())
                assertEquals(TunnelState.Disconnected, VpnStateRepository.state.value.tunnelState)
                assertFalse(field(service, "desiredConnected") as Boolean)
            } finally { release.complete(Unit); pipe.forEach { runCatching { it.close() } } }
        }
    }

    @Test fun destructionReleasesGuardAndCancelsQueuedRepair() = runTest {
        withService { service ->
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                setField(service, "vpnInterface", pipe[0])
                setField(service, "desiredConnected", true)
                invoke(service, "restartTunnelAfterAppResume")
                service.onDestroy()
                finishCleanup(service)
                assertNull(field(service, "vpnInterface"))
                assertFalse(pipe[0].fileDescriptor.valid())
                assertFalse(field(service, "pendingStartAfterCleanup") as Boolean)
                assertNull(field(service, "startJob"))
            } finally { pipe.forEach { runCatching { it.close() } } }
        }
    }

    @Test fun revocationReleasesAnErrorGuard() = runTest {
        withService { service ->
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                setField(service, "vpnInterface", pipe[0])
                invoke(service, "failClosed", "Fixture terminal failure")
                finishCleanup(service)
                service.onRevoke()
                finishCleanup(service)
                assertNull(field(service, "vpnInterface"))
                assertFalse(pipe[0].fileDescriptor.valid())
            } finally { pipe.forEach { runCatching { it.close() } } }
        }
    }

    @Test fun captureHasDefaultRoutesWithoutBypassOrAppExemptions() = runTest {
        withService { service ->
            for (ipv4Only in listOf(false, true)) {
                setField(service, "ipv4Only", ipv4Only)
                invoke(service, "establishTunInterface")
                val config = configs.last()
                val routes: List<android.net.RouteInfo> = ReflectionHelpers.getField(config, "routes")
                assertTrue(routes.any { it.destination.toString() == "0.0.0.0/0" })
                assertEquals(!ipv4Only, routes.any { it.destination.prefixLength == 0 && it.destination.address is java.net.Inet6Address })
                assertFalse(ReflectionHelpers.getField<Boolean>(config, "allowBypass"))
                assertEquals(!ipv4Only, ReflectionHelpers.getField<Boolean>(config, "allowIPv6"))
                assertNull(ReflectionHelpers.getField<Any?>(config, "allowedApplications"))
                assertNull(ReflectionHelpers.getField<Any?>(config, "disallowedApplications"))
                assertEquals(listOf("1.1.1.1", "8.8.8.8"), ReflectionHelpers.getField<List<String>>(config, "dnsServers"))
            }
        }
    }

    @Test fun replacementIsEstablishedBeforeOldGuardIsClosed() = runTest {
        withService { service ->
            val old = invoke(service, "installTunInterface", 0L) as ParcelFileDescriptor
            onEstablish = { assertTrue("Hold the old guard during replacement", old.fileDescriptor.valid()) }
            setField(service, "ipv4Only", true)
            val replacement = invoke(service, "installTunInterface", 0L) as ParcelFileDescriptor
            assertFalse(old.fileDescriptor.valid())
            assertTrue(replacement.fileDescriptor.valid())
            assertSame(replacement, field(service, "vpnInterface"))
            assertEquals(true, field(service, "vpnInterfaceIpv4Only"))
        }
    }

    @Test fun failedReplacementKeepsOldGuard() = runTest {
        withService { service ->
            val old = invoke(service, "installTunInterface", 0L) as ParcelFileDescriptor
            refuseInterface = true
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                invoke(service, "installTunInterface", 0L)
            }
            assertTrue(old.fileDescriptor.valid())
            assertSame(old, field(service, "vpnInterface"))
        }
    }

    @Test fun stopDuringEstablishRejectsAndClosesTheObsoleteDescriptor() = runTest {
        withService { service ->
            onEstablish = { invoke(service, "stopTunnel") }
            val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                invoke(service, "installTunInterface", 0L)
            }
            assertTrue(error.cause is kotlinx.coroutines.CancellationException)
            assertNull(field(service, "vpnInterface"))
            assertFalse(created[0].fileDescriptor.valid())
            finishCleanup(service)
        }
    }

    @Test fun anIdleErrorGuardAllowsRetryWithoutARecursiveCleanup() = runTest {
        withService { service ->
            invoke(service, "installTunInterface", 0L)
            assertEquals(false, invoke(service, "handleExistingTunnelStart", null))
        }
    }

    @Test @Config(sdk = [Build.VERSION_CODES.S])
    fun alwaysOnStopIsManagedByAndroidAndPolicyIsReported() = runTest {
        withService { service ->
            alwaysOn = true
            lockdown = true
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                setField(service, "vpnInterface", pipe[0])
                setField(service, "desiredConnected", true)
                assertEquals(Service.START_STICKY, service.onStartCommand(Intent(G2RayVpnService.ACTION_STOP), 0, 1))
                assertSame(pipe[0], field(service, "vpnInterface"))
                assertTrue(VpnStateRepository.state.value.alwaysOnVpn == true)
                assertTrue(VpnStateRepository.state.value.blockConnectionsWithoutVpn == true)
            } finally { setField(service, "vpnInterface", null); pipe.forEach { it.close() } }
        }
    }

    @Test fun repairDoesNotReleaseTheInterfaceBeforeRestartCanRun() = runTest {
        withService { service ->
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                setField(service, "vpnInterface", pipe[0])
                setField(service, "desiredConnected", true)
                invoke(service, "restartTunnelAfterAppResume")
                // Before cleanup/restart coroutines run, the guard must still
                // be owned by the service, including a concurrent Stop.
                assertSame(pipe[0], field(service, "vpnInterface"))
                assertTrue(pipe[0].fileDescriptor.valid())
            } finally {
                setField(service, "pendingStartAfterCleanup", false)
                setField(service, "vpnInterface", null)
                pipe.forEach { it.close() }
            }
        }
    }

    private suspend fun TestScope.withService(block: suspend (G2RayVpnService) -> Unit) {
        val service = Robolectric.buildService(G2RayVpnService::class.java).create().get()
        setField(service, "scope", CoroutineScope(backgroundScope.coroutineContext +
            SupervisorJob(backgroundScope.coroutineContext[Job])))
        setField(service, "secureStore\$delegate", lazyOf(fixtureStore(service)))
        setField(service, "fullVpnDataPathProbe", object : VpnDataPathProbe {
            override suspend fun probe() = RunningRouteResult(false, "Unexpected health request")
        })
        try { block(service) } finally {
            setField(service, "pendingStartAfterCleanup", false)
            service.onDestroy()
            finishCleanup(service)
        }
    }

    private fun fixtureStore(service: G2RayVpnService): EncryptedPrefsStore = EncryptedPrefsStore {
        service.getSharedPreferences("vpn-privacy-fixture", android.content.Context.MODE_PRIVATE)
    }

    private suspend fun TestScope.finishCleanup(service: G2RayVpnService) {
        runCurrent()
        (field(service, "cleanupJob") as? Job)?.join()
        runCurrent()
    }

    companion object {
        private val created = mutableListOf<ParcelFileDescriptor>()
        private val configs = mutableListOf<Any>()
        private var alwaysOn = false
        private var lockdown = false
        private var refuseInterface = false
        private var onEstablish: (() -> Unit)? = null
    }

    @Implements(VpnService.Builder::class)
    class CaptureBuilderShadow {
        @RealObject private lateinit var builder: VpnService.Builder
        // Android's String overloads call a libcore-only InetAddress method
        // absent on the desktop JVM. Exercise the real Builder's InetAddress
        // overloads, preserving its actual route/family/application config.
        @Implementation fun addAddress(address: String, prefix: Int): VpnService.Builder =
            builder.addAddress(java.net.InetAddress.getByName(address), prefix)
        @Implementation fun addRoute(address: String, prefix: Int): VpnService.Builder {
            val parsed = java.net.InetAddress.getByName(address)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return builder.addRoute(parsed, prefix)
            // Old RouteInfo constructors refer to Android-only Inet4Address.ANY.
            // Supply the equivalent explicit gateway in this JVM fixture.
            val routes: MutableList<android.net.RouteInfo> = ReflectionHelpers.getField(builder, "mRoutes")
            routes += android.net.RouteInfo::class.java.getDeclaredConstructor(
                android.net.IpPrefix::class.java, java.net.InetAddress::class.java, String::class.java
            ).apply { isAccessible = true }.newInstance(android.net.IpPrefix(parsed, prefix),
                java.net.InetAddress.getByAddress(ByteArray(parsed.address.size)), null)
            return builder
        }
        @Implementation fun addDnsServer(address: String): VpnService.Builder =
            builder.addDnsServer(java.net.InetAddress.getByName(address))
        @Implementation fun establish(): ParcelFileDescriptor? {
            onEstablish?.invoke()
            val config = ReflectionHelpers.getField<Any>(builder, "mConfig")
            // These two assignments are normally performed by establish()
            // immediately before crossing the Android system-service boundary.
            ReflectionHelpers.setField(config, "routes", ReflectionHelpers.getField<Any>(builder, "mRoutes"))
            ReflectionHelpers.setField(config, "addresses", ReflectionHelpers.getField<Any>(builder, "mAddresses"))
            configs += config
            if (refuseInterface) return null
            val pipe = ParcelFileDescriptor.createPipe()
            created.addAll(pipe)
            return pipe[0]
        }
    }

    @Implements(VpnService::class)
    class PolicyShadow : org.robolectric.shadows.ShadowVpnService() {
        @Implementation(minSdk = 29) fun isAlwaysOn(): Boolean = alwaysOn
        @Implementation(minSdk = 29) fun isLockdownEnabled(): Boolean = lockdown
    }

    private fun field(service: G2RayVpnService, name: String): Any? =
        G2RayVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setField(service: G2RayVpnService, name: String, value: Any?) {
        G2RayVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun invoke(service: G2RayVpnService, name: String, vararg args: Any?): Any? =
        G2RayVpnService::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(service, *args)
}
