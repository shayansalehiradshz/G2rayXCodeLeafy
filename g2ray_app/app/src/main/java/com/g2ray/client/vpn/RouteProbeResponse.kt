package com.g2ray.client.vpn

import java.io.IOException
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Consume a complete small response within the existing OkHttp call deadline. */
internal fun evaluateRouteProbeResponse(
    url: String,
    response: Response
): SocksRunningRouteProbe.RouteProbeOutcome = try {
    response.use {
        val target = url.toHttpUrl()
        val appleSuccess = target.host == "www.apple.com" && target.encodedPath == "/library/test/success.html"
        val validStatus = when {
            target.encodedPath.endsWith("/generate_204") -> it.code == 204
            appleSuccess -> it.code == 200
            else -> it.isSuccessful
        }
        if (it.request.url != target || it.priorResponse != null) {
            SocksRunningRouteProbe.RouteProbeOutcome(error = "$url: Health redirects are not allowed")
        } else if (!validStatus) {
            SocksRunningRouteProbe.RouteProbeOutcome(error = "$url: HTTP ${it.code}")
        } else {
            val body = it.body ?: throw IOException("Health response has no body")
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_HEALTH_RESPONSE_BYTES) throw IOException("Health response is too large")
            val source = body.source()
            // request() returns only after EOF or this byte limit. Headers
            // and a partial body cannot verify a stalled tunnel.
            source.request(MAX_HEALTH_RESPONSE_BYTES + 1)
            val receivedLength = source.buffer.size
            if (receivedLength > MAX_HEALTH_RESPONSE_BYTES) throw IOException("Health response is too large")
            if (declaredLength >= 0L && receivedLength != declaredLength) {
                throw IOException("Health response is incomplete")
            }
            if (appleSuccess && !APPLE_SUCCESS_BODY.containsMatchIn(source.buffer.readUtf8())) {
                throw IOException("Health response has unexpected content")
            }
            SocksRunningRouteProbe.RouteProbeOutcome(success = true)
        }
    }
} catch (error: Exception) {
    SocksRunningRouteProbe.RouteProbeOutcome(error = "$url: ${error.localizedMessage ?: error.javaClass.simpleName}")
}

private const val MAX_HEALTH_RESPONSE_BYTES = 65_536L
private val APPLE_SUCCESS_BODY = Regex("<body[^>]*>\\s*Success\\s*</body>", RegexOption.IGNORE_CASE)
