package com.g2ray.client.vpn

import android.net.Network
import android.net.DnsResolver
import android.net.VpnService
import android.os.Build
import android.os.CancellationSignal
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], shadows = [
    SocketProtectorTest.ProtectionShadow::class,
    SocketProtectorTest.BindingShadow::class,
    SocketProtectorTest.ResolverShadow::class
])
class SocketProtectorTest {
    private lateinit var selectedNetwork: Network

    @Before
    fun setUp() {
        events.clear()
        protectionAllowed = true
        bindingFails = false
        resolvedAddresses = emptyArray()
        lookupNetwork = null
        SocketProtector.vpnService = G2RayVpnService()
        selectedNetwork = Shadow.newInstanceOf(Network::class.java)
        SocketProtector.updateUnderlyingNetwork(selectedNetwork)
    }

    @After
    fun tearDown() {
        SocketProtector.vpnService = null
        SocketProtector.updateUnderlyingNetwork(null)
    }

    @Test
    fun initializesTheNativeDescriptorBeforeProtectionAndNetworkBinding() {
        TrackingSocket().use { socket ->
            assertTrue(SocketProtector.protectControlPlane(socket))
            assertEquals(listOf("materialize", "protect", "bind"), events)
            assertFalse(socket.isBound)
            assertFalse(socket.isConnected)
        }
    }

    @Test
    fun rejectedProtectionNeverBindsOrReturnsAnUnprotectedSocket() {
        protectionAllowed = false
        TrackingSocket().use { socket ->
            assertFalse(SocketProtector.protectControlPlane(socket))
            assertEquals(listOf("materialize", "protect"), events)
        }
    }

    @Test
    fun missingPhysicalNetworkDoesNotAttemptProtectionOrConnect() {
        SocketProtector.updateUnderlyingNetwork(null)
        TrackingSocket().use { socket ->
            assertFalse(SocketProtector.protectControlPlane(socket))
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun descriptorAndNetworkBindingErrorsAreReportedAsProtectionFailure() {
        TrackingSocket().use { socket ->
            socket.close()
            assertFalse(SocketProtector.protectControlPlane(socket))
            assertEquals(listOf("materialize"), events)
        }
        events.clear()
        bindingFails = true
        TrackingSocket().use { socket ->
            assertFalse(SocketProtector.protectControlPlane(socket))
            assertEquals(listOf("materialize", "protect", "bind"), events)
        }
    }

    @Test
    fun noVpnNeedsNoProtectionOrSocketInitialization() {
        SocketProtector.vpnService = null
        TrackingSocket().use { socket ->
            assertTrue(SocketProtector.protectControlPlane(socket))
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun bootstrapRetainsBoundedDistinctIpv4AnswersOnTheSelectedNetwork() {
        resolvedAddresses = arrayOf(
            InetAddress.getByName("2001:db8::1"),
            InetAddress.getByName("203.0.113.1"),
            InetAddress.getByName("203.0.113.1"),
            InetAddress.getByName("203.0.113.2"),
            InetAddress.getByName("203.0.113.3"),
            InetAddress.getByName("203.0.113.4")
        )
        assertEquals(listOf("203.0.113.1", "203.0.113.2", "203.0.113.3"),
            SocketProtector.resolveTunnelEndpoints("front.example", 100))
        assertEquals(listOf("lookup"), events)
        assertSame(selectedNetwork, lookupNetwork)
        assertEquals(listOf("203.0.113.1"), SocketProtector.resolveTunnelEndpoints("front.example", 1))
    }

    @Test
    fun ipv6OnlyBootstrapKeepsTheExistingAddressFamilyFallback() {
        resolvedAddresses = arrayOf(InetAddress.getByName("2001:db8::1"), InetAddress.getByName("2001:db8::2"))
        assertEquals(resolvedAddresses.map { it.hostAddress },
            SocketProtector.resolveTunnelEndpoints("front.example", 3))
    }

    @Test
    fun numericEndpointsNeverInvokeBootstrapDns() {
        assertEquals(listOf("203.0.113.1"), SocketProtector.resolveTunnelEndpoints("203.0.113.1", 3))
        assertEquals(listOf("2001:db8::1"), SocketProtector.resolveTunnelEndpoints("2001:db8::1", 3))
        assertTrue(events.isEmpty())
    }

    @Test
    fun controlPlaneDnsCannotFallIntoVpnWhenPhysicalNetworkIsMissing() {
        SocketProtector.updateUnderlyingNetwork(null)
        for (host in listOf("front.example", "203.0.113.1")) {
            assertThrows(UnknownHostException::class.java) { SocketProtector.lookupControlPlane(host) }
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun numericControlPlaneHostDoesNotUseAResolver() {
        assertEquals("203.0.113.1", SocketProtector.lookupControlPlane("203.0.113.1").single().hostAddress)
        assertTrue(events.isEmpty())
    }

    @Test(expected = UnknownHostException::class)
    fun emptyBootstrapResponseFailsInsteadOfCreatingAnEmptyDialAddress() {
        SocketProtector.resolveTunnelEndpoints("front.example", 3)
    }

    private class TrackingSocket : Socket() {
        var initialized = false

        override fun getSoLinger(): Int {
            events += "materialize"
            if (isClosed) throw SocketException("Socket is closed")
            initialized = true
            return -1
        }
    }

    @Implements(VpnService::class)
    class ProtectionShadow {
        @Implementation
        fun protect(socket: Socket): Boolean {
            events += "protect"
            return protectionAllowed && (socket as TrackingSocket).initialized
        }
    }

    @Implements(Network::class)
    class BindingShadow {
        @Implementation
        fun getAllByName(host: String): Array<InetAddress> {
            events += "lookup"
            return resolvedAddresses
        }

        @Implementation
        fun bindSocket(socket: Socket) {
            events += "bind"
            check((socket as TrackingSocket).initialized)
            if (bindingFails) throw IOException("Network was lost")
        }
    }

    @Implements(DnsResolver::class, minSdk = 29)
    class ResolverShadow {
        companion object {
            @JvmStatic
            @Implementation
            fun getInstance(): DnsResolver = Shadow.newInstanceOf(DnsResolver::class.java)
        }

        @Implementation
        fun query(network: Network?, host: String, flags: Int, executor: Executor,
                  cancellation: CancellationSignal?, callback: DnsResolver.Callback<List<InetAddress>>) {
            events += "lookup"
            lookupNetwork = network
            executor.execute { callback.onAnswer(resolvedAddresses.toList(), 0) }
        }
    }

    private companion object {
        val events = mutableListOf<String>()
        var protectionAllowed = true
        var bindingFails = false
        var resolvedAddresses = emptyArray<InetAddress>()
        var lookupNetwork: Network? = null
    }
}
