package com.g2ray.client.vpn

import java.net.IDN

data class RoutingPolicy(
    val bypassIranianDomains: Boolean = false,
    val bypassDomains: List<String> = emptyList(),
    val excludedPackages: Set<String> = emptySet()
) {
    val hasDomainBypass: Boolean get() = bypassIranianDomains || bypassDomains.isNotEmpty()

    companion object {
        fun parseDomains(text: String): List<String> {
            val values = text.split(Regex("[\\s,;]+" )).filter(String::isNotBlank)
            require(values.size <= 100) { "Use at most 100 bypass domains" }
            return values.map { raw ->
                val domain = IDN.toASCII(raw.trim().removePrefix("*.").removePrefix(".").trimEnd('.')).lowercase()
                require(domain.length in 1..253 && domain.contains('.') &&
                    domain.split('.').all { it.length in 1..63 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) } &&
                    !domain.matches(Regex("[0-9.]+"))) {
                    "Enter domain names only, such as example.ir; not URLs, IPs or paths"
                }
                domain
            }.distinct()
        }
    }
}
