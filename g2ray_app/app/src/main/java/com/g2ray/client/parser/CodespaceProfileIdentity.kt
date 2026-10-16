package com.g2ray.client.parser

import com.g2ray.client.model.G2RayProfile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object CodespaceProfileIdentity {
    private val originHost = Regex("^([a-z0-9][a-z0-9-]*)-([0-9]{1,5})\\.app\\.github\\.dev$")

    fun matches(profile: G2RayProfile, codespaceName: String): Boolean {
        val names = knownNames(profile)
        return names.isNotEmpty() && names.all { it.equals(codespaceName.trim(), ignoreCase = true) }
    }

    fun conflictsWith(profile: G2RayProfile, codespaceName: String): Boolean =
        knownNames(profile).any { !it.equals(codespaceName.trim(), ignoreCase = true) }

    fun hasKnownOrigin(profile: G2RayProfile): Boolean = knownNames(profile).isNotEmpty()

    private fun knownNames(profile: G2RayProfile): Set<String> =
        listOfNotNull(profile.sni, profile.hostHeader).mapNotNull(::nameFromAuthority).toSet()

    private fun nameFromAuthority(value: String): String? {
        val authority = value.trim()
        if (authority.isEmpty() || authority.any { it.isWhitespace() || it in "/\\?#@" }) return null
        val url = "https://$authority".toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != "/" || url.query != null || url.fragment != null) return null
        val match = originHost.matchEntire(url.host.removeSuffix(".")) ?: return null
        val forwardedPort = match.groupValues[2].toIntOrNull() ?: return null
        return match.groupValues[1].takeIf { forwardedPort in 1..65535 }
    }
}
