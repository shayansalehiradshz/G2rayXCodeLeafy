package com.g2ray.client.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.g2ray.client.R
import com.g2ray.client.model.*
import com.g2ray.client.vpn.DiagnosticEvents
import com.g2ray.client.vpn.VpnTileService

@Composable
internal fun SettingSwitch(title: String, checked: Boolean, enabled: Boolean, help: FeatureHelp, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        FeatureInfoButton(help)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionSettingsScreen(vm: G2RayViewModel, onBack: () -> Unit) {
    val mode by vm.vpnEngineMode.collectAsStateWithLifecycle()
    val fastDns by vm.fastDnsEnabled.collectAsStateWithLifecycle()
    val ipv4 by vm.ipv4OnlyEnabled.collectAsStateWithLifecycle()
    val dpi by vm.dpiCompatibilityEnabled.collectAsStateWithLifecycle()
    val optimize by vm.autoRouteOptimizationEnabled.collectAsStateWithLifecycle()
    val failover by vm.seamlessFailoverEnabled.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnosticsEnabled.collectAsStateWithLifecycle()
    val vpn by VpnStateRepository.state.collectAsStateWithLifecycle()
    val enabled = vpn.tunnelState in setOf(TunnelState.Disabled, TunnelState.Disconnected, TunnelState.Error)
    val context = LocalContext.current
    var tileMessage by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Connection settings") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!enabled) Text("Disconnect VPN before changing its packet engine or routing behavior.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Engine", Modifier.weight(1f)); FeatureInfoButton(FeatureHelp.Engine)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                VpnEngineMode.entries.forEachIndexed { index, engine -> SegmentedButton(
                    selected = mode == engine, enabled = enabled, onClick = { vm.setVpnEngineMode(engine) },
                    shape = SegmentedButtonDefaults.itemShape(index, VpnEngineMode.entries.size)
                ) { Text(engine.label) } }
            }
            SettingSwitch("Fast DNS", fastDns, enabled, FeatureHelp.FastDns, vm::setFastDnsEnabled)
            SettingSwitch("IPv4-only compatibility", ipv4, enabled, FeatureHelp.Ipv4, vm::setIpv4OnlyEnabled)
            SettingSwitch("DPI compatibility", dpi, enabled, FeatureHelp.Dpi, vm::setDpiCompatibilityEnabled)
            SettingSwitch("Auto-optimize routes", optimize, enabled, FeatureHelp.Optimize, vm::setAutoRouteOptimizationEnabled)
            SettingSwitch("Same-Codespace failover", failover, enabled, FeatureHelp.Failover, vm::setSeamlessFailoverEnabled)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quick Settings tile", Modifier.weight(1f))
                FeatureInfoButton(FeatureHelp.Tile)
                TextButton(onClick = {
                    try { if (Build.VERSION.SDK_INT >= 33) {
                        val manager = context.getSystemService(StatusBarManager::class.java)
                            ?: throw IllegalStateException("Quick Settings service unavailable")
                        manager.requestAddTileService(ComponentName(context, VpnTileService::class.java), "G2Ray VPN",
                            Icon.createWithResource(context, R.drawable.ic_vpn_notification), context.mainExecutor) { result ->
                            tileMessage = when (result) {
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "VPN tile added."
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "VPN tile is already added."
                                else -> "Open Quick Settings, tap Edit, and add G2Ray VPN."
                            }
                        }
                    } else tileMessage = "Pull down Quick Settings, tap Edit, then drag G2Ray VPN into your tiles."
                    } catch (_: RuntimeException) {
                        tileMessage = "Open Quick Settings, tap Edit, and add G2Ray VPN."
                    }
                }) { Text("Add tile") }
            }
            tileMessage?.let { Text(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (!openVpnProtectionSettings(context)) Toast.makeText(context, "Android VPN settings unavailable", Toast.LENGTH_SHORT).show() }) { Text("Android VPN protection") }
                FeatureInfoButton(FeatureHelp.Protection)
            }
            HorizontalDivider()
            SettingSwitch("Connection diagnostics", diagnostics, true, FeatureHelp.Diagnostics, vm::setDiagnosticsEnabled)
            OutlinedButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, DiagnosticEvents.export())
                }, "Share connection diagnostics"))
            }, enabled = diagnostics) { Text("Share diagnostics") }
        }
    }
}
