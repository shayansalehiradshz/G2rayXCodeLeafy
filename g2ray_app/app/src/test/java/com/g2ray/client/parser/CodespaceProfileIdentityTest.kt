package com.g2ray.client.parser

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CodespaceProfileIdentityTest {
    private val profile = VlessUriParser.parse(
        "vless://00000000-0000-4000-8000-000000000001@203.0.113.1:443" +
            "?encryption=none&security=tls&type=xhttp&alpn=h2&mode=packet-up&path=%2F" +
            "&sni=long-space-name-443.app.github.dev&host=long-space-name-8443.app.github.dev"
    ).getOrThrow()

    @Test
    fun fullCodespaceNameNotOwnerOrPrefixIsUsed() {
        assertTrue(CodespaceProfileIdentity.matches(profile, "long-space-name"))
        assertFalse(CodespaceProfileIdentity.matches(profile, "long-space"))
        assertFalse(CodespaceProfileIdentity.matches(profile, "GitHubOwner"))
        assertTrue(CodespaceProfileIdentity.conflictsWith(profile, "long-space"))
    }

    @Test
    fun canonicalCasePortAndDnsRootDotAreAccepted() {
        val canonical = profile.copy(sni = "LONG-SPACE-NAME-443.APP.GITHUB.DEV.",
            hostHeader = "long-space-name-8443.app.github.dev:443")
        assertTrue(CodespaceProfileIdentity.matches(canonical, "LONG-SPACE-NAME"))
    }

    @Test
    fun malformedAuthoritiesCannotBeNormalizedIntoMatchingHosts() {
        for (authority in listOf("@long-space-name-443.app.github.dev",
            "long-space-name-443.app.github.dev/", "long-space-name-443.app.github.dev?query=1",
            "long-space-name-443.app.github.dev#fragment", "long-space-name-443.app.github.dev:65536",
            "long-space-name-443.app.github.dev.evil.example", "long-space-name-0.app.github.dev",
            "long-space-name-65536.app.github.dev", "long-space-name-443.app.github.dev\tbad")) {
            assertFalse(authority, CodespaceProfileIdentity.matches(profile.copy(sni = authority,
                hostHeader = authority), "long-space-name"))
        }
    }

    @Test
    fun conflictingOriginsDoNotPassByMatchingOnlyOneField() {
        val conflicting = profile.copy(hostHeader = "other-space-8443.app.github.dev")
        assertFalse(CodespaceProfileIdentity.matches(conflicting, "long-space-name"))
        assertTrue(CodespaceProfileIdentity.conflictsWith(conflicting, "long-space-name"))
    }

    @Test
    fun customFrontDoesNotInventAGithubIdentity() {
        val custom = profile.copy(sni = "front.example", hostHeader = "front.example")
        assertFalse(CodespaceProfileIdentity.matches(custom, "long-space-name"))
        assertFalse(CodespaceProfileIdentity.conflictsWith(custom, "long-space-name"))
    }
}
