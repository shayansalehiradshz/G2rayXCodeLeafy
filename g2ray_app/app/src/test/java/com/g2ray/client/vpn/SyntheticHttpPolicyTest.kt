package com.g2ray.client.vpn

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class SyntheticHttpPolicyTest {
    @Test fun strictPolicyRetainsNoHiddenReplayOrRedirectDefaults() {
        val client = OkHttpClient.Builder().strictSyntheticRequests().build()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test fun redirectToAValid204IsNotAValidOriginalResponse() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 1_000
            val destinations = AtomicInteger()
            val failure = AtomicReference<Throwable>()
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 2_000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${server.localPort}/valid\r\n" +
                                "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                            flush()
                        }
                    }
                    try {
                        server.accept().use { destinations.incrementAndGet() }
                    } catch (_: java.net.SocketTimeoutException) { /* No redirect request is expected. */ }
                } catch (error: Throwable) { failure.set(error) }
            }
            val client = OkHttpClient.Builder().strictSyntheticRequests().callTimeout(3, TimeUnit.SECONDS).build()
            try {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/original").build())
                    .execute().use { assertEquals(302, it.code) }
                worker.join(4_000)
                assertFalse(worker.isAlive)
                failure.get()?.let { throw AssertionError(it) }
                assertEquals(0, destinations.get())
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun aClosedSocketAfterUploadDoesNotReplayThePayload() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 1_000
            val requests = AtomicInteger()
            val failure = AtomicReference<Throwable>()
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 2_000
                        val input = socket.getInputStream()
                        var window = 0
                        while (window != 0x0d0a0d0a) {
                            val next = input.read()
                            check(next >= 0)
                            window = (window shl 8) or next
                        }
                        assertEquals(120, input.read())
                        requests.incrementAndGet()
                    }
                    try { server.accept().use { requests.incrementAndGet() } }
                    catch (_: java.net.SocketTimeoutException) { /* No retry is expected. */ }
                } catch (error: Throwable) { failure.set(error) }
            }
            val client = OkHttpClient.Builder().strictSyntheticRequests().callTimeout(3, TimeUnit.SECONDS).build()
            try {
                val result = runCatching {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/upload")
                        .post(byteArrayOf(120).toRequestBody()).build()).execute().close()
                }
                assertTrue(result.isFailure)
                worker.join(4_000)
                assertFalse(worker.isAlive)
                failure.get()?.let { throw AssertionError(it) }
                assertEquals(1, requests.get())
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }
}
