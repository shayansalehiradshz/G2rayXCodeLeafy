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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    connectRequest: Int = 0,
    onConnectRequestHandled: () -> Unit = {},
    onNavigateToSettings: () -> Unit
) {
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val activeApiKey by viewModel.activeApiKey.collectAsStateWithLifecycle()
    val activeGitHubToken by viewModel.activeGitHubToken.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val codespaceProfiles by viewModel.activeCodespaceProfiles.collectAsStateWithLifecycle()
    val delayStates by viewModel.profileDelayStates.collectAsStateWithLifecycle()
    val measurementNetworkKey by viewModel.measurementNetworkKey.collectAsStateWithLifecycle()
    val controlPlane by viewModel.workerControlPlane.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val isWakingUp by viewModel.isWakingUp.collectAsStateWithLifecycle()
    val isVpnCoreAvailable by viewModel.isVpnCoreAvailable.collectAsStateWithLifecycle()
    val vpnEngineMode by viewModel.vpnEngineMode.collectAsStateWithLifecycle()
    val autoRouteOptimizationEnabled by viewModel.autoRouteOptimizationEnabled.collectAsStateWithLifecycle()
    val isRouteOptimizing by viewModel.isRouteOptimizing.collectAsStateWithLifecycle()
    val savedDataReady by viewModel.savedDataReady.collectAsStateWithLifecycle()
    val savedDataError by viewModel.repositoryLoadError.collectAsStateWithLifecycle()
    val isReloadingSavedData by viewModel.isReloadingSavedData.collectAsStateWithLifecycle()
    val originalLegacyConfig by viewModel.legacyProfileForRecovery.collectAsStateWithLifecycle()
    val savedSettingsUsable = savedDataReady && !isReloadingSavedData
    var showCodespacePicker by remember { mutableStateOf(false) }

    val vpnUiState by com.g2ray.client.model.VpnStateRepository.state.collectAsStateWithLifecycle()
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
        } else viewModel.reportVpnStartBlocked("VPN permission was not granted. Tap Start VPN to try again.")
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
    LaunchedEffect(connectRequest, savedSettingsUsable) {
        if (connectRequest > 0 && savedSettingsUsable) {
            onConnectRequestHandled()
            if (!isVpnRunning) startVpnWithPermissions()
        }
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
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("VPN", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    FeatureInfoButton(FeatureHelp.Protection)
                }
                Text(if (isVpnCoreAvailable) vpnUiState.message else XrayCoreManager.MISSING_CORE_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium)
                if (vpnUiState.tunnelState == TunnelState.Starting || isWakingUp) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text("Route: ${vpnUiState.routeState.name}  |  Engine: ${vpnUiState.activeVpnEngine?.label ?: vpnEngineMode.label}",
                    style = MaterialTheme.typography.bodySmall)
                if (vpnUiState.tunnelState == TunnelState.Blocked) {
                    Text("Traffic is blocked until VPN recovery succeeds.", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { startVpnWithPermissions() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        enabled = savedSettingsUsable && activeProfile != null && isVpnCoreAvailable) { Text("Retry VPN") }
                }
                Button(
                    onClick = {
                        if (isVpnRunning || vpnUiState.tunnelState == TunnelState.Blocked) {
                            if (vpnUiState.alwaysOnVpn == true) openVpnProtectionSettings(context)
                            else viewModel.stopVpn(context)
                        } else startVpnWithPermissions()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    enabled = isVpnRunning || vpnUiState.tunnelState == TunnelState.Blocked ||
                        (savedSettingsUsable && activeProfile != null && isVpnCoreAvailable)
                ) {
                    Text(when {
                        (isVpnRunning || vpnUiState.tunnelState == TunnelState.Blocked) && vpnUiState.alwaysOnVpn == true -> "Manage always-on VPN"
                        isVpnRunning || vpnUiState.tunnelState == TunnelState.Blocked -> "Disconnect VPN"
                        else -> "Start VPN"
                    })
                }
                if (vpnUiState.tunnelState == TunnelState.Connected && vpnUiState.tunnelVerified) {
                    if (vpnUiState.tunnelTrafficSource == TunnelTrafficSource.HevTun) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Down  ${SpeedFormatter.formatBps(vpnUiState.downloadBps)}", style = MaterialTheme.typography.titleMedium)
                            Text("Up  ${SpeedFormatter.formatBps(vpnUiState.uploadBps)}", style = MaterialTheme.typography.titleMedium)
                        }
                    } else Text("Route verified. Live byte counters are available with HEV.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Android outside-VPN blocking: ${vpnBlockingStatusLabel(vpnUiState.blockConnectionsWithoutVpn)}",
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
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
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { showCodespacePicker = true }, enabled = savedSettingsUsable) {
                            Text("Switch")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (activeAccount != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { QuotaStatus(viewModel, activeAccount!!.id) }
                            FeatureInfoButton(FeatureHelp.Quota)
                        }
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
                        text = "Last action: $statusMessage",
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
                            enabled = savedSettingsUsable
                        ) {
                            Text("Wake Up Codespace")
                        }
                        if (activeAccount?.let(viewModel::wakeControlConfigured) != true) {
                            Text("Wake needs a GitHub token or shared Worker in Settings. Working imported configs can still connect.",
                                style = MaterialTheme.typography.bodySmall)
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
                        FeatureInfoButton(FeatureHelp.Optimize)
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
                                    ConfigShareButton(profile.rawUri)
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
                                if (account.id != activeAccount?.id) {
                                    if (isVpnRunning || vpnUiState.tunnelState == TunnelState.Blocked) viewModel.connectToAccount(account.id)
                                    else viewModel.selectAccount(account.id)
                                }
                                showCodespacePicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(account.displayName, fontWeight = FontWeight.Bold)
                                QuotaStatus(viewModel, account.id)
                                Text(
                                    account.lastWorkerMessage ?: account.codespaceName.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (accounts.none { it.enabled }) TextButton(onClick = {
                        showCodespacePicker = false; onNavigateToSettings()
                    }) { Text("Add or enable a Codespace in Settings") }
                    if (isVpnRunning) Text("Choosing another Codespace disconnects and reconnects VPN; existing streams may need to resume.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showCodespacePicker = false }) { Text("Close") } }
        )
    }
}
