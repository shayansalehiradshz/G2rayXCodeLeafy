package com.g2ray.client.model

/**
 * The requested packet-forwarding implementation for Android's VPN file
 * descriptor. Auto is the normal choice: it selects HEV when it is bundled
 * and falls back to Xray's native TUN implementation when it is not.
 */
enum class VpnEngineMode(val storageValue: String, val label: String) {
    Auto("auto", "Auto"),
    Hev("hev", "HEV"),
    XrayNative("xray_native", "Native");

    companion object {
        fun fromStorage(value: String?): VpnEngineMode =
            entries.firstOrNull { it.storageValue == value } ?: Auto
    }
}

enum class ActiveVpnEngine(val label: String) {
    Hev("HEV TUN"),
    XrayNative("Xray TUN")
}

enum class TunnelTrafficSource {
    Unavailable,
    HevTun
}

object VpnEngineSelector {
    fun resolve(mode: VpnEngineMode, hevAvailable: Boolean): ActiveVpnEngine = when (mode) {
        VpnEngineMode.Auto -> if (hevAvailable) ActiveVpnEngine.Hev else ActiveVpnEngine.XrayNative
        VpnEngineMode.Hev -> if (hevAvailable) ActiveVpnEngine.Hev else ActiveVpnEngine.XrayNative
        VpnEngineMode.XrayNative -> ActiveVpnEngine.XrayNative
    }

    fun shouldFallbackToNative(
        mode: VpnEngineMode,
        active: ActiveVpnEngine,
        activePacketEngineReady: Boolean
    ): Boolean {
        return mode == VpnEngineMode.Auto &&
            active == ActiveVpnEngine.Hev &&
            !activePacketEngineReady
    }
}
