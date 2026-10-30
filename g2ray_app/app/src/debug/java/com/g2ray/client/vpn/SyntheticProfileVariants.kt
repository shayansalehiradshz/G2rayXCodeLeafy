package com.g2ray.client.vpn

import android.net.Uri
import com.g2ray.client.model.G2RayProfile
import com.g2ray.client.model.Transport
import com.g2ray.client.parser.VlessUriParser
import org.json.JSONObject

/** A runtime-only diagnostic clone. Never writes the saved profile repository. */
internal object SyntheticProfileVariants {
    fun controlledXhttp(profile: G2RayProfile, postBytes: Int, intervalMs: Int, keepAliveSeconds: Int): G2RayProfile {
        require(profile.transport == Transport.XHTTP)
        require(postBytes in setOf(60_000, 1_000_000) && intervalMs in setOf(10, 30) && keepAliveSeconds in setOf(0, 20))
        val extra = profile.xhttpExtraJson?.let(::JSONObject) ?: JSONObject()
        val mux = extra.optJSONObject("xmux") ?: JSONObject()
        // An absent/empty xmux gets these defaults in the pinned native parser.
        // A nonempty user object must keep all of its other effective values.
        if (mux.length() == 0) {
            mux.put("maxConnections", 3).put("hMaxRequestTimes", "600-900").put("hMaxReusableSecs", "1800-3000")
        }
        mux.put("hKeepAlivePeriod", keepAliveSeconds)
        extra.put("xmux", mux).put("scMaxEachPostBytes", postBytes).put("scMinPostsIntervalMs", intervalMs)
        return replaceExtra(profile, extra.toString())
    }

    fun withoutXhttpExtra(profile: G2RayProfile): G2RayProfile {
        require(profile.transport == Transport.XHTTP) { "Core-default comparison requires XHTTP" }
        return replaceExtra(profile, null)
    }

    private fun replaceExtra(profile: G2RayProfile, extra: String?): G2RayProfile {
        val uri = Uri.parse(profile.rawUri)
        val builder = uri.buildUpon().clearQuery()
        for (key in uri.queryParameterNames) {
            if (key == "extra") continue
            for (value in uri.getQueryParameters(key)) builder.appendQueryParameter(key, value)
        }
        if (extra != null) builder.appendQueryParameter("extra", extra)
        val clone = VlessUriParser.parse(builder.build().toString()).getOrThrow()
        check(clone.copy(rawUri = profile.rawUri, xhttpExtraJson = profile.xhttpExtraJson) == profile) {
            "Diagnostic clone changed fields other than XHTTP extra"
        }
        check((extra == null) == (clone.xhttpExtraJson == null))
        return clone
    }
}
