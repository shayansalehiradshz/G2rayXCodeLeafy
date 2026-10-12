package com.g2ray.client.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.concurrent.thread

class ControlPlaneConnectionPoolsTest {
    @Test
    fun networkChangeEvictsKeepAliveSocketsOffTheCallingThread() {
        val closed = CountDownLatch(1)
        val closedOn = AtomicReference<Thread>()
        val factory = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun close() {
                    closedOn.compareAndSet(null, Thread.currentThread())
                    try {
                        super.close()
                    } finally {
                        closed.countDown()
                    }
                }
            }
            override fun createSocket(host: String, port: Int): Socket = Socket(host, port)
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
                Socket(host, port, local, localPort)
            override fun createSocket(host: InetAddress, port: Int): Socket = Socket(host, port)
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
                Socket(host, port, local, localPort)
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val responder = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                        flush()
                    }
                    input.read()
                }
            }
            val pool = ControlPlaneConnectionPools.create()
            val client = OkHttpClient.Builder().socketFactory(factory).connectionPool(pool)
                .callTimeout(3, TimeUnit.SECONDS).build()
            try {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
                    .execute().use { assertTrue(it.isSuccessful) }
                assertTrue("The test must exercise an idle reusable connection", pool.idleConnectionCount() == 1)
                val caller = Thread.currentThread()

                ControlPlaneConnectionPools.evictAll()

                assertTrue("The retired socket must be closed", closed.await(5, TimeUnit.SECONDS))
                assertNotEquals("Network callbacks must not perform socket I/O", caller, closedOn.get())
            } finally {
                pool.evictAll()
                client.dispatcher.executorService.shutdown()
                responder.join(5_000)
            }
        }
    }
}
