package com.g2ray.client.vpn

import com.g2ray.client.model.G2RayProfile
import com.g2ray.client.model.ActiveVpnEngine
import com.g2ray.client.model.Transport
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {
    const val VPN_TUN_MTU = 1500
    private const val DNS_INBOUND_TAG = "dns-inbound"
    private const val DNS_OUTBOUND_TAG = "dns-out"
    private const val CLIENT_BUFFER_SIZE_KIB = 512
    // Reduce exposure to short phone-to-edge NAT idle windows. These probes
    // run only while the socket is otherwise idle.
    private const val OUTER_TCP_KEEPALIVE_IDLE_SECONDS = 25
    private const val OUTER_TCP_KEEPALIVE_INTERVAL_SECONDS = 15
    private const val PROXY_OUTBOUND_PREFIX = "proxy-"
    private const val PROXY_BALANCER_TAG = "proxy-balancer"
    private const val OBSERVATORY_PROBE_INTERVAL = "20s"
    private const val MAX_LIVE_PROXY_OUTBOUNDS = 3
    private const val FAKE_DNS_IPV4_POOL = "240.0.0.0/12"
    private const val FAKE_DNS_POOL_SIZE = 65_535
    private const val DPI_COMPATIBILITY_FINAL_MASK = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":["5","94","1"],"delays":["0"],"maxSplit":"0"}},{"type":"fragment","settings":{"packets":"1-1","lengths":["109","1"],"delays":["1"],"maxSplit":"355"}}]}"""

    fun buildConfig(profile: G2RayProfile, socksPort: Int = 10808): String {
        val root = JSONObject()

        val log = JSONObject().apply {
            put("loglevel", "warning")
        }
        root.put("log", log)

        root.put("dns", buildProxyDnsObject())
        root.put("policy", buildClientPolicy())

        val inbounds = JSONArray()
        val localSocks = JSONObject().apply {
            put("port", socksPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("udp", true)
            })
        }
        inbounds.put(localSocks)
        root.put("inbounds", inbounds)

        val outbounds = JSONArray()
        outbounds.put(buildVlessOutbound(profile).apply { put("tag", "proxy") })
        root.put("outbounds", outbounds)

        return root.toString(2)
    }

    fun buildDelayTestConfig(
        profile: G2RayProfile,
        socksPort: Int,
        dpiCompatibilityEnabled: Boolean = false
    ): String {
        val root = JSONObject(buildConfig(profile, socksPort))
        if (dpiCompatibilityEnabled) {
            val streamSettings = root.getJSONArray("outbounds")
                .getJSONObject(0)
                .getJSONObject("streamSettings")
            streamSettings.getJSONObject("tlsSettings").put("fingerprint", "unsafe")
            if (!streamSettings.has("finalmask")) {
                streamSettings.put("finalmask", JSONObject(DPI_COMPATIBILITY_FINAL_MASK))
            }
        }
        return root.toString(2)
    }

    fun buildVpnConfig(
        profile: G2RayProfile,
        healthSocksPort: Int = 10808,
        engine: ActiveVpnEngine = ActiveVpnEngine.XrayNative,
        fakeDnsEnabled: Boolean = false,
        ipv4Only: Boolean = false,
        dpiCompatibilityEnabled: Boolean = false,
        tunFd: Int? = null,
        fallbackProfiles: List<G2RayProfile> = emptyList()
    ): String {
        require(healthSocksPort in 1..65535) { "Health SOCKS port must be valid" }
        val root = JSONObject()
        val routeProfiles = compatibleRouteProfiles(profile, fallbackProfiles)
        val balancedRoutes = routeProfiles.size > 1

        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        root.put("dns", buildProxyDnsObject(fakeDnsEnabled))
        // Xray deserializes the root env object as map[string]string. Keep the
        // descriptor textual here; a JSON number makes native-TUN startup fail
        // before the Android fd can be opened.
        tunFd?.takeIf { it >= 0 }?.let { fd ->
            root.put("env", JSONObject().put("xray.tun.fd", fd.toString()))
        }
        if (fakeDnsEnabled) {
            // Xray's root schema uses camel-case `fakeDns`; the DNS server and
            // sniffing protocol tokens below intentionally remain `fakedns`.
            root.put("fakeDns", JSONObject().apply {
                put("ipPool", FAKE_DNS_IPV4_POOL)
                put("poolSize", FAKE_DNS_POOL_SIZE)
            })
        }
        root.put("policy", buildClientPolicy())

        val localInboundTag = if (engine == ActiveVpnEngine.Hev) "tun-socks" else "health-socks"
        val dnsIngressTags = JSONArray().apply {
            if (engine == ActiveVpnEngine.XrayNative) {
                put("tun-in")
            }
            put(localInboundTag)
        }
        root.put("inbounds", JSONArray().apply {
            if (engine == ActiveVpnEngine.XrayNative) {
                put(buildTunInbound(fakeDnsEnabled, ipv4Only))
            }
            put(buildSocksInbound(
                port = healthSocksPort,
                tag = localInboundTag,
                udp = engine == ActiveVpnEngine.Hev,
                sniffTraffic = engine == ActiveVpnEngine.Hev,
                fakeDnsEnabled = fakeDnsEnabled
            ))
        })

        root.put("outbounds", JSONArray().apply {
            routeProfiles.forEachIndexed { index, routeProfile ->
                put(buildVlessOutbound(routeProfile, dpiCompatibilityEnabled).apply {
                    put("tag", if (balancedRoutes) "$PROXY_OUTBOUND_PREFIX$index" else "proxy")
                    // XHTTP already manages its own multiplexing. Keep classic Mux
                    // disabled to avoid stream contention and configuration drift.
                    put("mux", JSONObject().apply {
                        put("enabled", false)
                        put("concurrency", -1)
                    })
                })
            }
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
            })
            put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
            })
            // The DNS outbound hands intercepted DNS packets to Xray's DNS
            // module. Its DoH upstream requests are routed back to proxy.
            put(JSONObject().apply {
                put("tag", DNS_OUTBOUND_TAG)
                put("protocol", "dns")
            })
        })

        if (balancedRoutes) {
            root.put("observatory", JSONObject().apply {
                put("subjectSelector", JSONArray().put(PROXY_OUTBOUND_PREFIX))
                put("probeURL", "https://cp.cloudflare.com/generate_204")
                put("probeInterval", OBSERVATORY_PROBE_INTERVAL)
                put("enableConcurrency", true)
            })
        }

        root.put("routing", JSONObject().apply {
            // Every VPN ingress path has an explicit outbound rule. Keep
            // routing domain-transparent and let the proxied DNS path handle
            // resolution instead of triggering an unnecessary fallback lookup.
            put("domainStrategy", "AsIs")
            if (balancedRoutes) {
                put("balancers", JSONArray().put(JSONObject().apply {
                    put("tag", PROXY_BALANCER_TAG)
                    put("selector", JSONArray().put(PROXY_OUTBOUND_PREFIX))
                    put("strategy", JSONObject().put("type", "leastPing"))
                    // Observatory starts asynchronously. Keep the user's selected
                    // route usable until the first concurrent probe set completes.
                    put("fallbackTag", "${PROXY_OUTBOUND_PREFIX}0")
                }))
            }
            put("rules", JSONArray().apply {
                // Android sends resolver traffic to the TUN just like normal
                // traffic. Capture it explicitly so browsers cannot depend on
                // a local-network DNS path while the tunnel is active.
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", dnsIngressTags)
                    put("port", "53")
                    put("outboundTag", DNS_OUTBOUND_TAG)
                })
                // Non-local DoH requests emitted by the built-in DNS module
                // must use the VLESS route, not the physical network.
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().put(DNS_INBOUND_TAG))
                    putProxyTarget(balancedRoutes)
                })
                // Codespaces XHTTP is a TCP-oriented path. Force browser QUIC
                // to fall back to the same TCP transport used by the server.
                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "udp")
                    put("port", "443")
                    put("outboundTag", "block")
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().put(localInboundTag))
                    putProxyTarget(balancedRoutes)
                })
                if (engine == ActiveVpnEngine.XrayNative) {
                    put(JSONObject().apply {
                        put("type", "field")
                        put("inboundTag", JSONArray().put("tun-in"))
                        putProxyTarget(balancedRoutes)
                    })
                }
            })
        })

        return root.toString(2)
    }

    private fun compatibleRouteProfiles(
        selected: G2RayProfile,
        fallbacks: List<G2RayProfile>
    ): List<G2RayProfile> {
        val result = ArrayList<G2RayProfile>(MAX_LIVE_PROXY_OUTBOUNDS)
        val seenEndpoints = HashSet<String>()
        (sequenceOf(selected) + fallbacks.asSequence()).forEach { candidate ->
            if (result.size >= MAX_LIVE_PROXY_OUTBOUNDS) return@forEach
            if (!sameTransportTemplate(selected, candidate)) return@forEach
            val endpoint = "${candidate.address.lowercase()}:${candidate.port}"
            if (seenEndpoints.add(endpoint)) result += candidate
        }
        return result.ifEmpty { listOf(selected) }
    }

    private fun sameTransportTemplate(first: G2RayProfile, second: G2RayProfile): Boolean {
        return first.uuid.equals(second.uuid, ignoreCase = true) &&
            first.port == second.port &&
            first.security.equals(second.security, ignoreCase = true) &&
            first.sni.equals(second.sni, ignoreCase = true) &&
            first.hostHeader.equals(second.hostHeader, ignoreCase = true) &&
            first.transport == second.transport &&
            first.path == second.path &&
            first.mode.equals(second.mode, ignoreCase = true) &&
            first.alpn.map(String::lowercase) == second.alpn.map(String::lowercase) &&
            first.fingerprint.equals(second.fingerprint, ignoreCase = true) &&
            first.finalMaskJson == second.finalMaskJson &&
            first.cipherSuites == second.cipherSuites &&
            first.xhttpExtraJson == second.xhttpExtraJson
    }

    private fun JSONObject.putProxyTarget(balancedRoutes: Boolean) {
        if (balancedRoutes) {
            put("balancerTag", PROXY_BALANCER_TAG)
        } else {
            put("outboundTag", "proxy")
        }
    }

    private fun buildClientPolicy(): JSONObject {
        return JSONObject().apply {
            put("levels", JSONObject().apply {
                put("0", JSONObject().apply {
                    // ARM64 defaults to a 4 KiB per-request pipe. Use Xray's
                    // desktop default to avoid needless backpressure on
                    // high-latency links. Keep idle sessions aligned with the
                    // server so background-app reuse does not hit Xray's
                    // five-minute client default first.
                    put("bufferSize", CLIENT_BUFFER_SIZE_KIB)
                    put("connIdle", 900)
                })
            })
        }
    }

    private fun buildProxyDnsObject(fakeDnsEnabled: Boolean = false): JSONObject {
        return JSONObject().apply {
            put("servers", JSONArray().apply {
                if (fakeDnsEnabled) {
                    put("fakedns")
                }
                listOf(
                    "https://1.1.1.1/dns-query",
                    "https://8.8.8.8/dns-query"
                ).forEach { address ->
                    put(JSONObject().apply {
                        put("address", address)
                        put("queryStrategy", "UseIPv4")
                    })
                }
            })
            put("queryStrategy", "UseIPv4")
            put("enableParallelQuery", true)
            // Keep an expired answer briefly while Xray refreshes it in the
            // background. This prevents a short DoH outage from hanging every
            // browser lookup, while bounding stale data to ten minutes.
            put("serveStale", true)
            put("serveExpiredTTL", 600)
            put("tag", DNS_INBOUND_TAG)
        }
    }

    private fun buildTunInbound(fakeDnsEnabled: Boolean, ipv4Only: Boolean): JSONObject {
        return JSONObject().apply {
            put("tag", "tun-in")
            put("port", 0)
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "g2ray0")
                put("MTU", VPN_TUN_MTU)
                put("gateway", JSONArray().apply {
                    put("198.18.0.1/15")
                    if (!ipv4Only) {
                        put("fc00::1/64")
                    }
                })
                put("dns", JSONArray().apply {
                    put("1.1.1.1")
                    put("8.8.8.8")
                })
            })
            put("sniffing", buildSniffing(fakeDnsEnabled))
        }
    }

    private fun buildSocksInbound(
        port: Int,
        tag: String,
        udp: Boolean,
        sniffTraffic: Boolean = false,
        fakeDnsEnabled: Boolean = false
    ): JSONObject {
        return JSONObject().apply {
            put("tag", tag)
            put("port", port)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("udp", udp)
            })
            if (sniffTraffic) {
                put("sniffing", buildSniffing(fakeDnsEnabled))
            }
        }
    }

    private fun buildSniffing(fakeDnsEnabled: Boolean): JSONObject {
        return JSONObject().apply {
            put("enabled", true)
            put("destOverride", JSONArray().apply {
                if (fakeDnsEnabled) {
                    put("fakedns")
                }
                put("http")
                put("tls")
            })
            // The recovered hostname must replace the fake or bare destination
            // IP so routing and the remote resolver see the original domain.
            put("routeOnly", false)
            put("metadataOnly", false)
        }
    }

    private fun buildVlessOutbound(
        profile: G2RayProfile,
        dpiCompatibilityEnabled: Boolean = false
    ): JSONObject {
        return JSONObject().apply {
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(JSONObject().apply {
                    put("address", profile.address)
                    put("port", profile.port)
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", profile.uuid)
                        put("encryption", "none")
                    }))
                }))
            })

            val streamSettings = JSONObject().apply {
                put("network", if (profile.transport == Transport.XHTTP) "xhttp" else "ws")
                put("security", "tls")
                // Preserve the mobile-to-edge NAT mapping across short carrier
                // idle windows. tcpUserTimeout still bounds a
                // genuinely black-holed path; this is not a bandwidth boost.
                put("sockopt", JSONObject().apply {
                    put("tcpKeepAliveIdle", OUTER_TCP_KEEPALIVE_IDLE_SECONDS)
                    put("tcpKeepAliveInterval", OUTER_TCP_KEEPALIVE_INTERVAL_SECONDS)
                    put("tcpUserTimeout", 30_000)
                })

                val tlsSettings = JSONObject().apply {
                    put("serverName", profile.sni)
                    put("allowInsecure", false)
                    val fingerprint = if (dpiCompatibilityEnabled) {
                        "unsafe"
                    } else {
                        profile.fingerprint?.takeIf { it.isNotEmpty() } ?: "chrome"
                    }
                    put("fingerprint", fingerprint)
                    profile.cipherSuites
                        ?.takeIf { it.isNotBlank() && fingerprint == "unsafe" }
                        ?.let { put("cipherSuites", it) }
                    val alpnValues = if (profile.transport == Transport.XHTTP) {
                        listOf("h2")
                    } else {
                        profile.alpn
                    }
                    if (alpnValues.isNotEmpty()) {
                        put("alpn", JSONArray().apply {
                            alpnValues.forEach { put(it) }
                        })
                    }
                }
                put("tlsSettings", tlsSettings)

                val finalMask = profile.finalMaskJson
                    ?.takeIf { it.isNotBlank() }
                    ?: DPI_COMPATIBILITY_FINAL_MASK.takeIf { dpiCompatibilityEnabled }
                finalMask?.let { put("finalmask", JSONObject(it)) }

                if (profile.transport == Transport.XHTTP) {
                    put("xhttpSettings", JSONObject().apply {
                        put("host", profile.hostHeader)
                        put("path", profile.path)
                        put("mode", profile.mode?.takeIf { it.isNotBlank() } ?: "packet-up")
                        profile.xhttpExtraJson?.let { put("extra", JSONObject(it)) }
                    })
                } else if (profile.transport == Transport.WS) {
                    put("wsSettings", JSONObject().apply {
                        put("path", profile.path)
                        put("headers", JSONObject().apply {
                            put("Host", profile.hostHeader)
                        })
                    })
                }
            }
            put("streamSettings", streamSettings)
        }
    }
}
