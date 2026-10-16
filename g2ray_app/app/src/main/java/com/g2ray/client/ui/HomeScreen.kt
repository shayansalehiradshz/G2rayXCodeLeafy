package com.g2ray.client.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.g2ray.client.model.TunnelTrafficSource
import com.g2ray.client.model.TunnelState
import com.g2ray.client.model.CodespaceWakeMode
import com.g2ray.client.model.VpnEngineMode
import com.g2ray.client.vpn.SpeedFormatter
import com.g2ray.client.vpn.XrayCoreManager

internal fun shouldRequestNotificationPermission(sdkInt: Int, permissionGranted: Boolean): Boolean {
    return sdkInt >= Build.VERSION_CODES.TIRAMISU && !permissionGranted
}

internal fun shouldContinueAfterNotificationPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean): Boolean {
    return true
}

internal fun copyConfigLinkToClipboard(context: Context, rawUri: String): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("VLESS config", rawUri))
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: G2RayViewModel,
    onNavigateToSettings: () -> Unit
) {
    val activeAccount by viewModel.activeAccount.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val activeApiKey by viewModel.activeApiKey.collectAsState()
    val activeGitHubToken by viewModel.activeGitHubToken.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val codespaceProfiles by viewModel.activeCodespaceProfiles.collectAsState()
    val delayStates by viewModel.profileDelayStates.collectAsState()
    val measurementNetworkKey by viewModel.measurementNetworkKey.collectAsState()
    val controlPlane by viewModel.workerControlPlane.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isWakingUp by viewModel.isWakingUp.collectAsState()
    val isVpnCoreAvailable by viewModel.isVpnCoreAvailable.collectAsState()
    val vpnEngineMode by viewModel.vpnEngineMode.collectAsState()
    val fastDnsEnabled by viewModel.fastDnsEnabled.collectAsState()
    val ipv4OnlyEnabled by viewModel.ipv4OnlyEnabled.collectAsState()
    val dpiCompatibilityEnabled by viewModel.dpiCompatibilityEnabled.collectAsState()
    val autoRouteOptimizationEnabled by viewModel.autoRouteOptimizationEnabled.collectAsState()
    val seamlessFailoverEnabled by viewModel.seamlessFailoverEnabled.collectAsState()
    val isRouteOptimizing by viewModel.isRouteOptimizing.collectAsState()
    val savedDataReady by viewModel.savedDataReady.collectAsState()
    val savedDataError by viewModel.repositoryLoadError.collectAsState()
    val isReloadingSavedData by viewModel.isReloadingSavedData.collectAsState()
    val originalLegacyConfig by viewModel.legacyProfileForRecovery.collectAsState()
    val savedSettingsUsable = savedDataReady && !isReloadingSavedData
    var showCodespacePicker by remember { mutableStateOf(false) }

    val vpnUiState by com.g2ray.client.model.VpnStateRepository.state.collectAsState()
    val isVpnRunning = vpnUiState.tunnelState == TunnelState.Starting ||
        vpnUiState.tunnelState == TunnelState.Connected ||
        vpnUiState.tunnelState == TunnelState.Stopping
    val unavailableProfiles = codespaceProfiles.filter {
        ProfileHealth.isUnavailableForCleanup(it, networkKey = measurementNetworkKey) && delayStates[it.id]?.testing != true
    }
    val isAnyDelayTesting = codespaceProfiles.any { delayStates[it.id]?.testing == true }
    var pendingDeleteProfileId by remember { mutableStateOf<String?>(null) }
    var showDeleteUnavailableConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpn(context)
        }
    }

    fun continueWithVpnPermission() {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.startVpn(context)
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (shouldContinueAfterNotificationPermissionResult(granted)) {
            continueWithVpnPermission()
        }
    }

    fun startVpnWithPermissions() {
        if (!isVpnCoreAvailable) {
            viewModel.reportVpnStartBlocked(XrayCoreManager.MISSING_CORE_MESSAGE)
            return
        }

        if (
            shouldRequestNotificationPermission(
                Build.VERSION.SDK_INT,
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        continueWithVpnPermission()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("G2Ray Client") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val loadMessage = savedDataError ?: if (isReloadingSavedData) "Reloading saved settings..." else null
            if (loadMessage != null) {
                SavedDataNotice(
                    message = loadMessage,
                    reloading = isReloadingSavedData,
                    retryEnabled = !isVpnRunning && !isWakingUp && !isRouteOptimizing && !isAnyDelayTesting,
                    onRetry = viewModel::reloadSavedData,
                    onCopyOriginal = originalLegacyConfig?.let { raw ->
                        {
                            val copied = copyConfigLinkToClipboard(context, raw)
                            Toast.makeText(context, if (copied) "Original config copied" else "Clipboard unavailable", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            // Section 1: Active Account & Wake Control
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Codespace Route Manager",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { showCodespacePicker = true }, enabled = savedSettingsUsable) {
                            Text("Switch")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (activeAccount != null) {
                        Text(
                            text = "Codespace: ${activeAccount?.displayName}",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        if (controlPlane != null && activeAccount?.wakeMode != CodespaceWakeMode.GITHUB_DIRECT) {
                            Text(
                                text = "Worker URL: ${controlPlane?.workerBaseUrl}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (!controlPlane?.fallbackWorkerBaseUrl.isNullOrEmpty()) {
                                Text(
                                    text = "Backup Worker: ${controlPlane?.fallbackWorkerBaseUrl}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        if (!activeAccount?.codespaceName.isNullOrEmpty()) {
                            Text(
                                text = "Codespace: ${activeAccount?.codespaceName}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = "Wake method: ${when (activeAccount?.wakeMode) {
                                CodespaceWakeMode.WORKER -> "Worker"
                                CodespaceWakeMode.GITHUB_DIRECT -> "Direct GitHub"
                                else -> "Auto"
                            }}",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when (activeAccount?.wakeMode) {
                                    CodespaceWakeMode.GITHUB_DIRECT -> "GitHub token: ${activeGitHubToken?.label ?: "None"}"
                                    else -> "Wake key: ${if (controlPlane != null) "Shared" else activeApiKey?.label ?: "None"}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        if (activeAccount?.wakeMode != CodespaceWakeMode.GITHUB_DIRECT && (controlPlane != null || activeApiKey != null)) {
                            SelectionContainer {
                                Text(
                                    text = "Wake Key: ${controlPlane?.encryptedWakeSecret ?: activeApiKey?.encryptedValue}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "No active Codespace. Set one up in Settings.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Status: $statusMessage",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isWakingUp) {
                        Button(
                            onClick = { viewModel.cancelWakeUp() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onError,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cancel Wake Up")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.wakeUpCodespace() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = savedSettingsUsable && activeAccount?.let(viewModel::wakeControlConfigured) == true
                        ) {
                            Text("Wake Up Codespace")
                        }
                    }
                }
            }

            // Section 2: Active Profile (VLESS)
            Text(
                "Active VLESS Config",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (activeProfile != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "VL",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeProfile?.displayName ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${activeProfile?.address}:${activeProfile?.port}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Transport: ${activeProfile?.transport?.name} (${activeProfile?.path})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No active VLESS config selected.\nManage configs in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (activeAccount != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Configs for ${activeAccount?.displayName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { showDeleteUnavailableConfirm = true },
                            enabled = savedSettingsUsable && unavailableProfiles.isNotEmpty() && !isVpnRunning && !isRouteOptimizing
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete failed configs")
                        }
                        TextButton(
                            onClick = viewModel::optimizeRoutes,
                            enabled = savedSettingsUsable && codespaceProfiles.isNotEmpty() && !isVpnRunning &&
                                !isRouteOptimizing && !isAnyDelayTesting
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isRouteOptimizing) "Optimizing" else "Optimize")
                        }
                        TextButton(
                            onClick = { viewModel.refreshActiveCodespaceDelays() },
                            enabled = savedSettingsUsable && codespaceProfiles.isNotEmpty() &&
                                !isRouteOptimizing && !isAnyDelayTesting
                        ) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ping all")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Auto-optimize routes",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = autoRouteOptimizationEnabled,
                            onCheckedChange = viewModel::setAutoRouteOptimizationEnabled,
                            enabled = savedSettingsUsable && !isVpnRunning && !isRouteOptimizing && !isAnyDelayTesting
                        )
                    }
                }
                codespaceProfiles.forEach { profile ->
                    val state = delayStates[profile.id]
                    val delayText = ProfileHealth.delayLabel(ProfileHealth.displayDelay(profile, state, networkKey = measurementNetworkKey))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (profile.id == activeProfile?.id) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        onClick = { viewModel.selectProfile(profile.id) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                                Text(
                                    "${profile.transport.name}  ${profile.address}:${profile.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                profile.lastDownloadBps?.takeIf { it > 0L }?.let { downloadBps ->
                                    val sampleText = if (ProfileHealth.isThroughputFresh(profile, networkKey = measurementNetworkKey)) {
                                        val uploadText = profile.lastUploadBps
                                            ?.takeIf { it > 0L }
                                            ?.let { "  UL ${SpeedFormatter.formatBps(it)}" }
                                            .orEmpty()
                                        "Route sample: DL ${SpeedFormatter.formatBps(downloadBps)}$uploadText"
                                    } else {
                                        "Route sample: stale; run Optimize"
                                    }
                                    Text(
                                        sampleText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    delayText,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row {
                                    IconButton(
                                        onClick = { viewModel.measureProfileDelay(profile.id) },
                                        enabled = savedSettingsUsable && !isVpnRunning && !isRouteOptimizing && !isAnyDelayTesting
                                    ) {
                                        Icon(Icons.Default.NetworkCheck, contentDescription = "Run accurate three-sample delay test")
                                    }
                                    IconButton(onClick = {
                                        val copied = copyConfigLinkToClipboard(context, profile.rawUri)
                                        Toast.makeText(
                                            context,
                                            if (copied) "Config link copied" else "Clipboard is unavailable",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy config link")
                                    }
                                    IconButton(
                                        onClick = { pendingDeleteProfileId = profile.id },
                                        enabled = savedSettingsUsable && !isVpnRunning && !isRouteOptimizing &&
                                            !isAnyDelayTesting && state?.testing != true
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete config")
                                    }
                                }
                            }
                        }
                    }
                }

                val pendingDeleteProfile = codespaceProfiles.firstOrNull { it.id == pendingDeleteProfileId }
                if (pendingDeleteProfile != null) {
                    AlertDialog(
                        onDismissRequest = { pendingDeleteProfileId = null },
                        title = { Text("Delete config?") },
                        text = { Text("Delete ${pendingDeleteProfile.displayName} from this device?") },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.deleteProfile(pendingDeleteProfile.id)
                                pendingDeleteProfileId = null
                            }) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDeleteProfileId = null }) { Text("Cancel") }
                        }
                    )
                }

                if (showDeleteUnavailableConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteUnavailableConfirm = false },
                        title = { Text("Delete failed configs?") },
                        text = {
                            Text(
                                "Delete ${unavailableProfiles.size} configs that failed at least " +
                                    "${ProfileHealth.UNAVAILABLE_DELETE_FAILURES} consecutive delay checks?"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.deleteUnavailableProfiles()
                                showDeleteUnavailableConfirm = false
                            }) { Text("Delete failed") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteUnavailableConfirm = false }) { Text("Cancel") }
                        }
                    )
                }
            }

            // Section 3: Full-device VPN control
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.NetworkCheck,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Full-device VPN",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isVpnCoreAvailable) {
                            vpnUiState.message
                        } else {
                            XrayCoreManager.MISSING_CORE_MESSAGE
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Engine: ${vpnUiState.activeVpnEngine?.label ?: vpnEngineMode.label}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Outside-VPN blocking: ${vpnBlockingStatusLabel(if (isVpnRunning) vpnUiState.blockConnectionsWithoutVpn else null)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    TextButton(onClick = {
                        if (!openVpnProtectionSettings(context)) {
                            Toast.makeText(context, "Android VPN settings are unavailable", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("VPN protection settings")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        VpnEngineMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = vpnEngineMode == mode,
                                onClick = { viewModel.setVpnEngineMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = VpnEngineMode.entries.size
                                ),
                                enabled = savedSettingsUsable && !isVpnRunning
                            ) {
                                Text(mode.label)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Fast DNS (experimental)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = fastDnsEnabled,
                            onCheckedChange = viewModel::setFastDnsEnabled,
                            enabled = savedSettingsUsable && !isVpnRunning
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "IPv4-only compatibility",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = ipv4OnlyEnabled,
                            onCheckedChange = viewModel::setIpv4OnlyEnabled,
                            enabled = savedSettingsUsable && !isVpnRunning
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "DPI compatibility (experimental)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = dpiCompatibilityEnabled,
                            onCheckedChange = viewModel::setDpiCompatibilityEnabled,
                            enabled = savedSettingsUsable && !isVpnRunning
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Seamless route failover",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = seamlessFailoverEnabled,
                            onCheckedChange = viewModel::setSeamlessFailoverEnabled,
                            enabled = savedSettingsUsable && !isVpnRunning
                        )
                    }
                    if (vpnUiState.tunnelState == TunnelState.Connected && vpnUiState.tunnelVerified) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (vpnUiState.tunnelTrafficSource == TunnelTrafficSource.HevTun) {
                            Text(
                                text = SpeedFormatter.formatNotification(
                                    com.g2ray.client.vpn.TrafficSpeed(
                                        rxBps = vpnUiState.downloadBps,
                                        txBps = vpnUiState.uploadBps,
                                        totalRxBytes = vpnUiState.totalRxBytes,
                                        totalTxBytes = vpnUiState.totalTxBytes
                                    )
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Live HEV TUN counters for this VPN session.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Text(
                                text = "Route verified. Exact live speed is unavailable in Native mode; select HEV before connecting to show real tunnel counters.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    } else if (vpnUiState.tunnelState == TunnelState.Starting ||
                        (vpnUiState.tunnelState == TunnelState.Connected && !vpnUiState.tunnelVerified)
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Verifying the live tunnel route before reporting traffic...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (vpnUiState.tunnelState == TunnelState.Blocked) {
                        Button(
                            onClick = { startVpnWithPermissions() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = savedSettingsUsable && activeProfile != null && isVpnCoreAvailable
                        ) { Text("Retry VPN") }
                        TextButton(
                            onClick = {
                                if (vpnUiState.alwaysOnVpn == true) {
                                    if (!openVpnProtectionSettings(context)) {
                                        Toast.makeText(context, "Android VPN settings are unavailable", Toast.LENGTH_SHORT).show()
                                    }
                                } else viewModel.stopVpn(context)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (vpnUiState.alwaysOnVpn == true) "Manage always-on VPN" else "Disconnect VPN")
                        }
                    } else if (isVpnRunning) {
                        Button(
                            onClick = {
                                if (vpnUiState.alwaysOnVpn == true) {
                                    if (!openVpnProtectionSettings(context)) {
                                        Toast.makeText(context, "Android VPN settings are unavailable", Toast.LENGTH_SHORT).show()
                                    }
                                } else viewModel.stopVpn(context)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(if (vpnUiState.alwaysOnVpn == true) "Manage always-on VPN" else "Stop VPN")
                        }
                    } else {
                        Button(
                            onClick = { startVpnWithPermissions() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = savedSettingsUsable && activeProfile != null && isVpnCoreAvailable
                        ) {
                            Text("Start VPN")
                        }
                    }
                }
            }
        }
    }

    if (showCodespacePicker) {
        AlertDialog(
            onDismissRequest = { showCodespacePicker = false },
            title = { Text("Choose Codespace") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    accounts.filter { it.enabled }.sortedBy { it.priority }.forEach { account ->
                        TextButton(
                            onClick = {
                                viewModel.selectAccount(account.id)
                                showCodespacePicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(account.displayName, fontWeight = FontWeight.Bold)
                                Text(
                                    account.lastWorkerMessage ?: account.codespaceName.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCodespacePicker = false }) { Text("Close") } }
        )
    }
}
