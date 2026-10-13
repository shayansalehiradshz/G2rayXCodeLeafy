package com.g2ray.client.vpn

import android.net.Network
import android.net.VpnService
import android.os.Build
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    SocketProtectorTest.BindingShadow::class
])
class SocketProtectorTest {
    @Before
    fun setUp() {
        events.clear()
        protectionAllowed = true
        bindingFails = false
        SocketProtector.vpnService = G2RayVpnService()
        SocketProtector.updateUnderlyingNetwork(Shadow.newInstanceOf(Network::class.java))
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
        fun bindSocket(socket: Socket) {
            events += "bind"
            check((socket as TrackingSocket).initialized)
            if (bindingFails) throw IOException("Network was lost")
        }
    }

    private companion object {
        val events = mutableListOf<String>()
        var protectionAllowed = true
        var bindingFails = false
    }
}
