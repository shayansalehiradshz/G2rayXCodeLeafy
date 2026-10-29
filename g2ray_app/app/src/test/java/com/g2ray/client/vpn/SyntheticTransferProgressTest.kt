package com.g2ray.client.vpn

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Test

class SyntheticTransferProgressTest {
    @Test fun retainsFirstLastAndExactByteCountWithBoundedSamples() {
        var now = 100L
        val progress = SyntheticTransferProgress { now }
        repeat(1_000) { progress.advance(1L); now += 250L }
        val result = progress.summary()
        assertTrue(result.contains("observed_bytes=1000 first_progress_ms=100 last_progress_ms=249850"))
        assertTrue(result.contains("progress_capped=true"))
        assertEquals(16, result.substringAfter("progress_samples=").split(',').size)
    }

    @Test fun noBytesOrUnavailableClockNeverInventsProgressTime() {
        val empty = SyntheticTransferProgress { 0L }
        empty.advance(0)
        assertTrue(empty.summary().contains("observed_bytes=0 first_progress_ms=unknown last_progress_ms=unknown"))
        val noClock = SyntheticTransferProgress { error("private-token") }
        noClock.advance(3)
        assertTrue(noClock.summary().contains("observed_bytes=3 first_progress_ms=unknown"))
        assertFalse(noClock.summary().contains("private-token"))
    }

    @Test fun writerPreservesPayloadContentLengthAndDoesNotReplay() {
        val source = ByteArray(100_000) { (it and 255).toByte() }
        val progress = SyntheticTransferProgress { 0L }
        val body = SyntheticProgressRequestBody(source.toRequestBody("application/octet-stream".toMediaType()), progress)
        val destination = Buffer()
        body.writeTo(destination)
        assertArrayEquals(source, destination.readByteArray())
        assertEquals(source.size.toLong(), body.contentLength())
        assertEquals("application/octet-stream", body.contentType().toString())
        assertTrue(progress.summary().contains("observed_bytes=100000"))
    }

    @Test fun originalWriterFailureAndOneShotContractSurviveTheWrapper() {
        val error = IOException("private-content")
        var attempts = 0
        val source = object : RequestBody() {
            override fun contentType() = null
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { attempts++; throw error }
        }
        val progress = SyntheticTransferProgress { 0L }
        val body = SyntheticProgressRequestBody(source, progress)
        assertTrue(body.isOneShot())
        assertSame(error, runCatching { body.writeTo(Buffer()) }.exceptionOrNull())
        assertEquals(1, attempts)
        assertTrue(progress.summary().contains("observed_bytes=0"))
    }
}
