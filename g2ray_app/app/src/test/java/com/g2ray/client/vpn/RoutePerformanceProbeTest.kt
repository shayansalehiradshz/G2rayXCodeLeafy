package com.g2ray.client.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePerformanceProbeTest {
    private fun probeBuilder(payloadBytes: Int, cancelPath: String? = null) = {
        OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath == cancelPath) throw CancellationException("Optimization cancelled")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ByteArray(if (chain.request().url.encodedPath == "/__down") payloadBytes else 0).toResponseBody())
                .build()
        }
    }

    @Test
    fun truncatedDownloadIsRejectedInsteadOfUsedToSelectARoute() = runBlocking {
        val result = SocksRoutePerformanceProbe(128, 64, probeBuilder(64)).probe(10808)
        assertFalse(result.success)
        assertEquals(null, result.downloadBps)
        assertTrue(result.error.orEmpty().contains("ended early"))
    }

    @Test
    fun completePayloadProducesMeasuredDownloadAndUploadSamples() = runBlocking {
        val result = SocksRoutePerformanceProbe(128, 64, probeBuilder(128)).probe(10808)
        assertTrue(result.success)
        assertTrue(result.downloadBps!! > 0)
        assertTrue(result.uploadBps!! > 0)
    }

    @Test(expected = CancellationException::class)
    fun downloadCancellationPropagatesToTheOptimizer() {
        runBlocking { SocksRoutePerformanceProbe(128, 64, probeBuilder(128, "/__down")).probe(10808) }
    }

    @Test(expected = CancellationException::class)
    fun uploadCancellationDoesNotTurnIntoASuccessfulDownloadOnlySample() {
        runBlocking { SocksRoutePerformanceProbe(128, 64, probeBuilder(128, "/__up")).probe(10808) }
    }

    @Test
    fun bytesPerSecond_usesActualBytesAndNanosecondsWithoutOverflow() {
        assertEquals(
            2_000_000L,
            SocksRoutePerformanceProbe.bytesPerSecond(
                bytes = 1_000_000L,
                elapsedNanos = 500_000_000L
            )
        )
    }

    @Test
    fun bytesPerSecond_rejectsEmptyOrZeroDurationSamples() {
        assertEquals(0L, SocksRoutePerformanceProbe.bytesPerSecond(0L, 1_000L))
        assertEquals(0L, SocksRoutePerformanceProbe.bytesPerSecond(1_000L, 0L))
    }
}
