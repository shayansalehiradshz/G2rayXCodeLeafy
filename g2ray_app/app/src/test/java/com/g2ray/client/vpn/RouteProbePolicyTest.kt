package com.g2ray.client.vpn

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProbePolicyTest {
    private fun outcome(url: String, code: Int, body: String = "", finalUrl: String = url) =
        evaluateRouteProbeResponse(url, Response.Builder().request(Request.Builder().url(finalUrl).build())
            .protocol(Protocol.HTTP_2).code(code).message("Fixture").body(body.toResponseBody()).build())

    @Test fun known204EndpointsRejectWrongSuccessAndRedirectResponses() {
        for (url in SocksRunningRouteProbe.HEALTH_URLS.filter { it.endsWith("generate_204") }) {
            assertTrue(outcome(url, 204).success)
            assertFalse(outcome(url, 200, "login or error page").success)
            assertFalse(outcome(url, 201).success)
            assertFalse(outcome(url, 302).success)
            assertFalse(outcome(url, 503).success)
            assertFalse(outcome(url, 204, finalUrl = "https://login.example/generate_204").success)
        }
    }

    @Test fun appleRequiresItsCompleteExpectedSuccessContent() {
        val url = "https://www.apple.com/library/test/success.html"
        assertTrue(outcome(url, 200, "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>").success)
        assertTrue(outcome(url, 200, "<html><body> Success </body></html>").success)
        assertFalse(outcome(url, 200, "<html><body>Sign in</body></html>").success)
        assertFalse(outcome(url, 204).success)
    }

    @Test fun sharedHealthQuorumCannotBeSatisfiedByTwoErrorOrRedirectPages() = runBlocking {
        val urls = SocksRunningRouteProbe.HEALTH_URLS
        for (redirected in listOf(false, true)) {
            val result = SocksRunningRouteProbe.probeUntilDecided(urls) { url ->
                if (url.endsWith("generate_204")) {
                    if (redirected) outcome(url, 204, finalUrl = "https://login.example/generate_204")
                    else outcome(url, 200, "login or error page")
                } else outcome(url, 200, "<html><body>Success</body></html>")
            }
            assertFalse(result.success)
        }
        val healthy = SocksRunningRouteProbe.probeUntilDecided(urls) { url ->
            when {
                url.contains("gstatic") -> outcome(url, 503)
                url.endsWith("generate_204") -> outcome(url, 204)
                else -> outcome(url, 200, "<html><body>Success</body></html>")
            }
        }
        assertTrue(healthy.success)
    }

    @Test fun bothProductionHealthClientsRejectRedirects() = runBlocking {
        val socks = SocksRunningRouteProbe()
        val full = OkHttpVpnDataPathProbe()
        try {
            val socksClient = SocksRunningRouteProbe::class.java.getDeclaredMethod("clientFor", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(socks, 10808) as OkHttpClient
            val fullClient = OkHttpVpnDataPathProbe::class.java.getDeclaredMethod("client")
                .apply { isAccessible = true }.invoke(full) as OkHttpClient
            for (client in listOf(socksClient, fullClient)) {
                assertFalse(client.followRedirects)
                assertFalse(client.followSslRedirects)
            }
        } finally {
            socks.reset()
            full.reset()
        }
    }
}
