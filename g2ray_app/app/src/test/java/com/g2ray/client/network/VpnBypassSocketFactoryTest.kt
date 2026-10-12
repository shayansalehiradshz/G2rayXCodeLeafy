package com.g2ray.client.network

import java.io.IOException
import java.net.Socket
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnBypassSocketFactoryTest {
    @Test
    fun createSocket_protectsSocketBeforeReturningIt() {
        var protectedSocket: Socket? = null
        val factory = VpnBypassSocketFactory(
            protect = { socket ->
                protectedSocket = socket
                true
            }
        )

        val socket = factory.createSocket()
        try {
            assertSame(socket, protectedSocket)
            assertTrue(!socket.isConnected)
        } finally {
            socket.close()
        }
    }

    @Test
    fun createSocket_closesAndRejectsUnprotectedSocket() {
        var rejectedSocket: Socket? = null
        val factory = VpnBypassSocketFactory(
            protect = { socket ->
                rejectedSocket = socket
                false
            }
        )

        assertThrows(IOException::class.java) { factory.createSocket() }
        assertTrue(rejectedSocket?.isClosed == true)
    }
}
