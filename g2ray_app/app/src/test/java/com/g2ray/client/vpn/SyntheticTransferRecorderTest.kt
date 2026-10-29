package com.g2ray.client.vpn

import java.io.IOException
import java.util.concurrent.CancellationException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class SyntheticTransferRecorderTest {
    @Test fun successfulTransferRunsAndReportsCompletionExactlyOnce() {
        val events = mutableListOf<String>()
        var now = 100L
        var executions = 0
        val recorder = SyntheticTransferRecorder({ now }, events::add)
        val result = recorder.record(SyntheticTransferRecorder.Kind.Download) {
            executions++
            now = 200L
            42
        }
        assertEquals(42, result)
        assertEquals(1, executions)
        assertEquals(2, events.size)
        assertEquals("event=bulk_end kind=download outcome=completed error_type=none request_start_ms=100 request_end_ms=200", events.last())
    }

    @Test fun failedAndCancelledTransfersRetainOriginalExceptionAndTerminalInterval() {
        for (failure in listOf(IOException("private-token"), CancellationException("private-path"), AssertionError("HTTP 503 private-body"))) {
            val events = mutableListOf<String>()
            var now = 10L
            val recorder = SyntheticTransferRecorder({ now }, events::add)
            val actual = runCatching {
                recorder.record(SyntheticTransferRecorder.Kind.Upload) { now = 30L; throw failure }
            }.exceptionOrNull()
            assertSame(failure, actual)
            assertEquals(2, events.size)
            assertTrue(events.last().contains("outcome=failed error_type=${failure.javaClass.simpleName}"))
            assertTrue(events.last().contains("request_start_ms=10 request_end_ms=30"))
            assertFalse(events.joinToString().contains("private-"))
            assertFalse(events.joinToString().contains("503"))
        }
    }

    @Test fun diagnosticFailuresCannotMaskTransferSuccessOrFailure() {
        val recorder = SyntheticTransferRecorder({ error("clock") }, { error("report") })
        assertEquals(9, recorder.record(SyntheticTransferRecorder.Kind.Download) { 9 })
        val failure = IOException("original")
        assertSame(failure, runCatching {
            recorder.record(SyntheticTransferRecorder.Kind.Download) { throw failure }
        }.exceptionOrNull())
    }

    @Test fun unavailableClockDoesNotInventAnInterval() {
        val events = mutableListOf<String>()
        val recorder = SyntheticTransferRecorder({ error("clock") }, events::add)
        recorder.record(SyntheticTransferRecorder.Kind.Download) { Unit }
        assertTrue(events.last().contains("request_start_ms=unknown request_end_ms=unknown"))
    }

    @Test fun realHttpErrorAndBodyTimeoutAreTerminalFailuresNotSuccessfulTransfers() {
        for (stallBody in listOf(false, true)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 3_000
                val responder = thread(isDaemon = true) {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().apply {
                            write((if (stallBody) "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n" else
                                "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\n\r\n").toByteArray())
                            flush()
                        }
                        if (stallBody) Thread.sleep(1_000)
                    }
                }
                val events = mutableListOf<String>()
                var creditedSuccess = 0
                val recorder = SyntheticTransferRecorder({ System.nanoTime() / 1_000_000L }, events::add)
                val client = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS)
                    .callTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
                try {
                    val failure = runCatching {
                        recorder.record(SyntheticTransferRecorder.Kind.Download) {
                            client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
                                .execute().use { response ->
                                    check(response.isSuccessful) { "HTTP ${response.code}" }
                                    response.body!!.bytes()
                                }
                        }
                        creditedSuccess++
                    }.exceptionOrNull()
                    assertNotNull(failure)
                    if (stallBody) {
                        assertTrue("Fixture did not exercise a body read timeout", failure is java.net.SocketTimeoutException)
                    } else {
                        assertTrue("Fixture did not exercise an HTTP rejection", failure is IllegalStateException)
                        assertEquals("HTTP 503", failure!!.message)
                    }
                    assertEquals(0, creditedSuccess)
                    assertTrue(events.last().contains("outcome=failed"))
                    assertEquals(1, events.count { it.contains("event=bulk_end") })
                    assertFalse(events.joinToString().contains("HTTP"))
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                    responder.join(3_000)
                    assertFalse(responder.isAlive)
                }
            }
        }
    }
}
