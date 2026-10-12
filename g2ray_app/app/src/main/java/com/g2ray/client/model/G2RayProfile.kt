package com.g2ray.client.model

enum class Transport {
    XHTTP,
    WS
}

data class G2RayProfile(
    val rawUri: String,
    val name: String,
    val uuid: String,
    val address: String,
    val port: Int,
    val security: String,
    val sni: String?,
    val hostHeader: String?,
    val alpn: List<String>,
    val fingerprint: String?,
    val transport: Transport,
    val path: String,
    val mode: String?,
    val allowInsecure: Boolean,
    val finalMaskJson: String? = null,
    val cipherSuites: String? = null,
    val xhttpExtraJson: String? = null
)
