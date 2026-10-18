package com.g2ray.client.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.VpnStateRepository
import com.g2ray.client.vpn.RoutingPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class BypassApp(val packageName: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutingSettingsScreen(vm: G2RayViewModel, onBack: () -> Unit) {
    val policy by vm.routingPolicy.collectAsStateWithLifecycle()
    val vpn by VpnStateRepository.state.collectAsStateWithLifecycle()
    val editable = vpn.tunnelState in setOf(TunnelState.Disabled, TunnelState.Disconnected, TunnelState.Error)
    val context = LocalContext.current
    var bypassIr by rememberSaveable { mutableStateOf(policy.bypassIranianDomains) }
    var domains by rememberSaveable { mutableStateOf(policy.bypassDomains.joinToString("\n")) }
    var excluded by rememberSaveable { mutableStateOf(ArrayList(policy.excludedPackages)) }
    var search by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val apps by produceState<Result<List<BypassApp>>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { info -> BypassApp(info.activityInfo.packageName, info.loadLabel(context.packageManager).toString()) }
                .filter { it.packageName != context.packageName }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        } }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Bypass routing") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }, bottomBar = {
        Surface {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (status.isNotBlank()) Text(status)
                Button(onClick = {
                    val parsed = runCatching { RoutingPolicy.parseDomains(domains) }
                    if (parsed.isFailure) status = parsed.exceptionOrNull()?.message ?: "Invalid domains"
                    else {
                        saving = true
                        vm.saveRoutingPolicy(RoutingPolicy(bypassIr, parsed.getOrThrow(), excluded.toSet())) { result ->
                            saving = false
                            status = if (result.isSuccess) "Saved for the next VPN connection." else result.exceptionOrNull()?.message ?: "Could not save routing."
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = editable && !saving) { Text(if (saving) "Saving..." else "Save routing") }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Bypassed apps and domains use your real local IP and country. Keep every bypass off for a consistent VPN exit.", color = MaterialTheme.colorScheme.error)
                FeatureInfoButton(FeatureHelp.Bypass)
                if (!editable) Text("Disconnect VPN to change routing.")
                if (vpn.blockConnectionsWithoutVpn == true) Text("Android blocks non-VPN connections: excluded apps may have no Internet.")
                SettingSwitch("Bypass .ir domains", bypassIr, editable, FeatureHelp.Bypass) { bypassIr = it }
                OutlinedTextField(domains, { domains = it }, enabled = editable, label = { Text("Additional bypass domains") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                Text("Per-app bypass", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(search, { search = it }, label = { Text("Find an app") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (apps == null) CircularProgressIndicator()
                if (apps?.isFailure == true) Text("Could not load installed apps. Saved exclusions were not changed.")
            }
            val filtered = apps?.getOrNull().orEmpty().filter { search.isBlank() || it.label.contains(search, true) || it.packageName.contains(search, true) }
            items(filtered, key = { it.packageName }) { app ->
                val checked = app.packageName in excluded
                Row(Modifier.fillMaxWidth().clickable(enabled = editable) {
                    excluded = ArrayList(if (checked) excluded - app.packageName else excluded + app.packageName)
                }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(app.label); Text(app.packageName, style = MaterialTheme.typography.bodySmall) }
                    Checkbox(checked, enabled = editable, onCheckedChange = { selected ->
                        excluded = ArrayList(if (selected) excluded + app.packageName else excluded - app.packageName)
                    })
                }
            }
            if (apps?.isSuccess == true && filtered.isEmpty()) item { Text("No matching launchable apps.") }
        }
    }
}
