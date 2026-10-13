package com.g2ray.client.vpn

import android.os.Build
import android.net.Network
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.g2ray.client.data.AccountRepository
import com.g2ray.client.data.AccountRepositoryTest.InMemorySecureStore
import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.ApiKeyKind
import com.g2ray.client.model.CodespaceWakeMode
import com.g2ray.client.model.G2RayProfile
import com.g2ray.client.model.VpnEngineMode
import com.g2ray.client.network.WakeRetryCooldown
import com.g2ray.client.network.CodespaceWakeCoordinator
import com.g2ray.client.parser.VlessUriParser
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], shadows = [G2RayVpnServiceStartupTest.EndpointNetworkShadow::class])
class G2RayVpnServiceStartupTest {
    @Test
    fun lifecycleChangedDuringEndpointDnsNeverStartsTheCore() = runTest {
        withRejectedConfigService { service, tun, bridge ->
            SocketProtector.vpnService = service
            SocketProtector.updateUnderlyingNetwork(Shadow.newInstanceOf(Network::class.java))
            endpointLookupAction = { setField(service, "vpnInterface", null) }
            try {
                val result = invokeRoute(
                    service, "startEngineAndVerify", profile("front.example"), tun,
                    ActiveVpnEngine.XrayNative, 0L
                )
                assertFalse(result.success)
                assertEquals("A stale DNS completion started an obsolete core", 0, bridge.starts)
            } finally {
                endpointLookupAction = null
                SocketProtector.vpnService = null
                SocketProtector.updateUnderlyingNetwork(null)
            }
        }
    }

    @Test
    fun networkChangedDuringEndpointDnsNeverStartsTheOldNetworkCore() = runTest {
        withRejectedConfigService { service, tun, bridge ->
            SocketProtector.vpnService = service
            SocketProtector.updateUnderlyingNetwork(Shadow.newInstanceOf(Network::class.java))
            val generation = G2RayVpnService::class.java.getDeclaredField("underlyingNetworkGeneration")
                .apply { isAccessible = true }.get(service) as java.util.concurrent.atomic.AtomicLong
            endpointLookupAction = { generation.incrementAndGet() }
            try {
                val result = invokeRoute(
                    service, "startEngineAndVerify", profile("front.example"), tun,
                    ActiveVpnEngine.XrayNative, 0L
                )
                assertFalse(result.success)
                assertEquals(RouteFailureScope.DialerBinding, result.failureScope)
                assertEquals(0, bridge.starts)
            } finally {
                endpointLookupAction = null
                SocketProtector.vpnService = null
                SocketProtector.updateUnderlyingNetwork(null)
            }
        }
    }

    @Test
    fun endpointDnsNetworkChangeDoesNotTriggerAutoPacketEngineFallback() = runTest {
        withRejectedConfigService { service, tun, bridge ->
            SocketProtector.vpnService = service
            SocketProtector.updateUnderlyingNetwork(Shadow.newInstanceOf(Network::class.java))
            setField(service, "requestedEngineMode", VpnEngineMode.Auto)
            val generation = G2RayVpnService::class.java.getDeclaredField("underlyingNetworkGeneration")
                .apply { isAccessible = true }.get(service) as java.util.concurrent.atomic.AtomicLong
            var lookups = 0
            endpointLookupAction = {
                lookups++
                generation.incrementAndGet()
            }
            try {
                val result = invokeRoute(
                    service, "startBestEngineAndVerify", profile("front.example"), tun,
                    0L, ActiveVpnEngine.Hev
                )
                assertFalse(result.success)
                assertTrue("A network change must remain retryable", result.retryAllowed)
                assertEquals(RouteFailureScope.DialerBinding, result.failureScope)
                assertEquals("Physical-network changes are not packet-engine failures", 1, lookups)
                assertEquals(0, bridge.starts)
            } finally {
                endpointLookupAction = null
                SocketProtector.vpnService = null
                SocketProtector.updateUnderlyingNetwork(null)
            }
        }
    }

    @Test
    fun rejectedCoreConfigDoesNotTryTheOtherPacketEngine() = runTest {
        withRejectedConfigService { service, tun, bridge ->
            val result = invokeRoute(
                service, "startBestEngineAndVerify", profile(), tun, 0L, ActiveVpnEngine.Hev
            )

            assertFalse(result.retryAllowed)
            assertEquals(RouteFailureScope.Configuration, result.failureScope)
            assertEquals(1, bridge.starts)
        }
    }

    @Test
    fun configRejectionDuringRecoveryStopsBeforeWakeOrFurtherRestarts() = runTest {
        withRejectedConfigService { service, tun, bridge ->
            val repository = AccountRepository(InMemorySecureStore())
            val account = repository.addAccount(
                "Fixture", "", "fixture-space", wakeMode = CodespaceWakeMode.GITHUB_DIRECT
            )
            repository.addApiKey(
                account.id, "Fixture", secret = "fixture-only-token", kind = ApiKeyKind.GITHUB_TOKEN
            )
            setField(service, "accountRepository\$delegate", lazyOf(repository))
            setField(service, "activeAccountId", account.id)
            setField(service, "codespaceWakeCoordinator\$delegate", lazyOf(CodespaceWakeCoordinator(
                workerWake = { _, _ -> error("Rejected config must not call Worker") },
                githubWake = { _, _ -> error("Rejected config must not call GitHub") }
            )))
            val result = invokeRoute(
                service, "recoverRouteWithWorkerAttempt", profile(), tun, 0L,
                "Initial route timed out", RouteFailureScope.XrayRoute,
                WakeRetryCooldown(SystemClock::elapsedRealtime)
            )

            assertFalse(result.retryAllowed)
            assertEquals(RouteFailureScope.Configuration, result.failureScope)
            assertEquals(1, bridge.starts)
        }
    }

    @Test
    fun malformedBackupDoesNotPreventTryingTheNextProfile() = runTest {
        withRejectedConfigService { service, tun, bridge ->
            val repository = backupRepository(service, withWorkingBackup = true)
            bridge.rejectedAddresses = setOf("203.0.113.2")
            setField(service, "requestedEngineMode", VpnEngineMode.XrayNative)
            setField(service, "fullVpnDataPathProbe", object : VpnDataPathProbe {
                override suspend fun probe() = RunningRouteResult(true)
            })

            val result = invokeOptionalRoute(service, "tryAlternativeStoredProfiles", profile(), tun, 0L, 6)

            assertTrue(result?.success == true)
            assertEquals(listOf("203.0.113.2", "203.0.113.3"), bridge.addresses)
            assertEquals("203.0.113.3", repository.profiles.value.single {
                it.id == repository.activeSelection.value.profileId
            }.address)
        }
    }

    @Test
    fun allMalformedBackupsDoNotOverrideTheOriginalRoutesRetryPolicy() = runTest {
        withRejectedConfigService { service, tun, bridge ->
            backupRepository(service, withWorkingBackup = false)

            val result = invokeOptionalRoute(service, "tryAlternativeStoredProfiles", profile(), tun, 0L, 6)

            assertNull(result)
            assertEquals(1, bridge.starts)
        }
    }

    private fun backupRepository(service: G2RayVpnService, withWorkingBackup: Boolean): AccountRepository {
        val repository = AccountRepository(InMemorySecureStore())
        val account = repository.addAccount("Fixture", "https://worker.example", "fixture-space")
        val malformed = profile("203.0.113.2", extra = "{\"xmux\":{\"maxConnections\":\"invalid\"}}")
        repository.importProfile(account.id, "Bad backup", malformed.rawUri, malformed)
        if (withWorkingBackup) {
            val working = profile("203.0.113.3")
            repository.importProfile(account.id, "Good backup", working.rawUri, working)
        }
        setField(service, "accountRepository\$delegate", lazyOf(repository))
        setField(service, "activeAccountId", account.id)
        return repository
    }

    private suspend fun TestScope.withRejectedConfigService(
        block: suspend (G2RayVpnService, ParcelFileDescriptor, RejectingBridge) -> Unit
    ) {
        val service = Robolectric.buildService(G2RayVpnService::class.java).create().get()
        val pipe = ParcelFileDescriptor.createPipe()
        val bridge = RejectingBridge()
        val core = XrayCoreManager(
            bridge, RunningRouteProbe { error("A rejected config must not probe the network") },
            portAllocator = { 19090 },
            localSocksListenerProbe = LocalSocksListenerProbe { true }
        )
        setField(service, "scope", backgroundScope)
        setField(service, "core", core)
        setField(service, "vpnInterface", pipe[0])
        setField(service, "seamlessFailoverEnabled", false)
        try {
            block(service, pipe[0], bridge)
        } finally {
            setField(service, "vpnInterface", null)
            core.stop()
            service.onDestroy()
            pipe.forEach { it.close() }
        }
    }

    private suspend fun invokeRoute(
        service: G2RayVpnService, name: String, vararg arguments: Any
    ): RunningRouteResult = invokeOptionalRoute(service, name, *arguments)!!

    private suspend fun invokeOptionalRoute(
        service: G2RayVpnService, name: String, vararg arguments: Any
    ): RunningRouteResult? = suspendCoroutine { continuation ->
        val method = G2RayVpnService::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == arguments.size + 1 &&
                it.parameterTypes.last() == Continuation::class.java
        }.apply { isAccessible = true }
        try {
            val result = method.invoke(service, *arguments, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(result as RunningRouteResult?)
        } catch (error: InvocationTargetException) {
            continuation.resumeWithException(error.targetException)
        }
    }

    private fun setField(service: G2RayVpnService, name: String, value: Any?) {
        G2RayVpnService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(service, value)
    }

    private fun profile(address: String = "203.0.113.1", extra: String? = null): G2RayProfile = VlessUriParser.parse(
        "vless://00000000-0000-4000-8000-000000000001@$address:443" +
            "?encryption=none&security=tls&sni=validation.example&host=validation.example" +
            "&alpn=h2&type=xhttp&path=%2F&mode=packet-up" +
            (extra?.let { "&extra=${android.net.Uri.encode(it)}" } ?: "")
    ).getOrThrow()

    private class RejectingBridge : XrayBridge {
        var starts = 0
        val addresses = mutableListOf<String>()
        var rejectedAddresses: Set<String>? = null
        var running = false
        override fun isAvailable() = true
        override fun capabilities(): XrayRuntimeCapabilities = error("Unexpected capability check")
        override fun registerDialerController(protectFd: (Int) -> Boolean) = Unit
        override fun registerListenerController(protectFd: (Int) -> Boolean) = Unit
        override fun setTunFd(fd: Int) = Unit
        override fun newRunFromJsonRequest(datDir: String, configJson: String) = configJson
        override fun runFromJson(base64Request: String): XrayCallResult {
            starts++
            val address = JSONObject(base64Request).getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address")
            addresses += address
            running = rejectedAddresses?.contains(address) == false
            return XrayCallResult(running, if (running) null else "infra/conf: Failed to build transport config")
        }
        override fun ping(base64Request: String): XrayPingResult = error("Unexpected ping")
        override fun stop(): XrayCallResult {
            running = false
            return XrayCallResult(true, null)
        }
        override fun getState() = running
    }

    @Implements(Network::class)
    class EndpointNetworkShadow {
        @Implementation
        fun getAllByName(host: String): Array<InetAddress> {
            endpointLookupAction?.invoke()
            return arrayOf(InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 1)))
        }
    }

    private companion object {
        var endpointLookupAction: (() -> Unit)? = null
    }
}
