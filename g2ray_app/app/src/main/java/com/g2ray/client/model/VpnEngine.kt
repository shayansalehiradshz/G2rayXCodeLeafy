package com.g2ray.client.model

/**
 * The requested packet-forwarding implementation for Android's VPN file
 * descriptor. Auto is the normal choice: it tries Xray's native TUN path
 * first because it avoids the extra HEV userspace forwarding hop, then falls
 * back to HEV when the native packet path cannot become healthy.
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
        VpnEngineMode.Auto -> ActiveVpnEngine.XrayNative
        VpnEngineMode.Hev -> if (hevAvailable) ActiveVpnEngine.Hev else ActiveVpnEngine.XrayNative
        VpnEngineMode.XrayNative -> ActiveVpnEngine.XrayNative
    }

    fun fallbackEngine(
        mode: VpnEngineMode,
        active: ActiveVpnEngine,
        hevAvailable: Boolean,
        activePacketEngineReady: Boolean
    ): ActiveVpnEngine? {
        if (mode != VpnEngineMode.Auto || activePacketEngineReady) return null
        return when (active) {
            ActiveVpnEngine.XrayNative -> ActiveVpnEngine.Hev.takeIf { hevAvailable }
            ActiveVpnEngine.Hev -> ActiveVpnEngine.XrayNative
        }
    }
}
