package com.g2ray.client.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

internal enum class FeatureHelp(val title: String, val explanation: String) {
    Engine("VPN engine", "Auto is recommended: it tries the lower-overhead Native Xray TUN path first and falls back to HEV if that packet path cannot become healthy. HEV and Native remain manual choices for troubleshooting. Change engines only while disconnected; the best path can still vary by phone and Android build."),
    FastDns("Fast DNS", "Default: off. Answers supported DNS queries locally with synthetic addresses and lets the Codespace resolve names. It can shorten first visits to new domains. Turn it off if an app cannot connect or retains old synthetic addresses. Restart affected apps after changing it."),
    Ipv4("IPv4-only compatibility", "Default: off. Disables IPv6 in the VPN; Android blocks it instead of allowing it to bypass the tunnel. Try it only if your phone or exit has broken IPv6. It can prevent access to IPv6-only services; it does not lower every ping."),
    Dpi("DPI compatibility", "Default: off. Experimental TLS fragmentation and fingerprinting for networks that interfere with the normal handshake. This is not a speed setting: some carriers or edges reject fragmented handshakes entirely. Enable only to test a blocked ordinary config. If connecting hangs or becomes slower, turn this off before retrying."),
    Optimize("Automatic route optimization", "Default: on. While disconnected, periodically discovers compatible edge addresses and tests them from this phone. Saves successful configs and may select a better route. Measurements belong to this network, are bounded, and do not run a large scanner during your browsing session. Turn off to keep manual route selection."),
    Failover("Same-Codespace failover", "Default: on. Keeps a small set of compatible routes inside the running core and chooses a healthy route for new connections. It cannot move an established download or AI stream to a new server without interruption. Turn off for a controlled single-config comparison."),
    Ping("Ping and Optimize", "Ping measures a small HTTPS request through a config, not ICMP or maximum speed. While connected it checks the running tunnel without replacing it. Optimize is available while disconnected and compares routes using several measurements. Low delay does not guarantee the highest sustained video speed."),
    Wake("Wake method", "Start VPN first tries the saved working route. If it fails, recovery can start the Codespace through your shared Worker or a GitHub token belonging to that account. Auto prefers Worker, with direct GitHub as a fallback for an unreachable Worker. A stopped Codespace needs time to boot; no setting can eliminate GitHub startup time."),
    Quota("Monthly usage estimate", "GitHub Free includes 120 core-hours: about 60 active hours on a 2-core machine, shared by all Codespaces billed to the account. The panel tracks only this project's local estimate. An imported snapshot is a projection, not live billing. Only an explicit GitHub quota/billing response confirms a block. Keep old Codespaces for reuse after reset; storage also has limits."),
    Bypass("Bypass routing", "Default: everything uses the VPN. Excluded apps and matching domains use your real local connection and can show your local country/IP. App exclusions may lose Internet access with Android's Block connections without VPN enabled. Domain rules require a known hostname; literal IPs and apps with encrypted DNS may not match. Disable bypass to keep a consistent exit."),
    Diagnostics("Diagnostics", "Optional bounded event history is off by default. When enabled, it records connection phases and error categories, not browsing destinations, tokens, or config links. It keeps only recent events and does no packet capture. Enable to reproduce a problem, share the diagnostic text, then turn it off."),
    Protection("Android VPN protection", "Always-on restarts the VPN after system events. Block connections without VPN prevents normal apps from using the local connection when the VPN is unavailable. Android manages these switches. Intentional app bypass can conflict with blocking. Turning the app's VPN off is not the same as turning these Android settings off."),
    Tile("Quick Settings tile", "Adds a G2Ray VPN control beside Android's Wi-Fi and Bluetooth tiles. Tap to connect using the selected Codespace, or to disconnect. The first start may need Android's VPN permission. With always-on enabled, the tile opens the app instead of repeatedly fighting Android's reconnect policy."),
    Key("Wake key enabled", "An enabled key can be selected for this Codespace's Worker requests. Disable an old key to keep it saved without using it. This is not a VPN on/off switch, and it does not change the Worker's secret. Direct GitHub wake uses the account's GitHub token instead."),
    Share("Share configs", "Copy or show an offline QR code containing the original config. Anyone with the link can use its server credentials. A QR code works with compatible clients; one compact all-config QR from this project's panel is supported by this app. Sharing does not copy your GitHub token or wake key.")
}

@Composable
internal fun FeatureInfoButton(feature: FeatureHelp) {
    var showing by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { showing = true }) {
        Icon(Icons.Outlined.Info, contentDescription = "About ${feature.title}")
    }
    if (showing) AlertDialog(
        onDismissRequest = { showing = false },
        title = { Text(feature.title) },
        text = { Text(feature.explanation) },
        confirmButton = { TextButton(onClick = { showing = false }) { Text("Got it") } }
    )
}
