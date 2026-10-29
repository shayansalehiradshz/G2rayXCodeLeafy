package com.g2ray.client.vpn

import android.net.Uri
import com.g2ray.client.model.G2RayProfile
import com.g2ray.client.model.Transport
import com.g2ray.client.parser.VlessUriParser

/** A runtime-only diagnostic clone. Never writes the saved profile repository. */
internal object SyntheticProfileVariants {
    fun withoutXhttpExtra(profile: G2RayProfile): G2RayProfile {
        require(profile.transport == Transport.XHTTP) { "Core-default comparison requires XHTTP" }
        val uri = Uri.parse(profile.rawUri)
        val builder = uri.buildUpon().clearQuery()
        for (key in uri.queryParameterNames) {
            if (key == "extra") continue
            for (value in uri.getQueryParameters(key)) builder.appendQueryParameter(key, value)
        }
        val clone = VlessUriParser.parse(builder.build().toString()).getOrThrow()
        check(clone.copy(rawUri = profile.rawUri, xhttpExtraJson = profile.xhttpExtraJson) == profile) {
            "Diagnostic clone changed fields other than XHTTP extra"
        }
        return clone
    }
}
