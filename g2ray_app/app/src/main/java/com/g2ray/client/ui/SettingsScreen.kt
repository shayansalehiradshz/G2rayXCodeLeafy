package com.g2ray.client.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.g2ray.client.model.*
import com.g2ray.client.parser.CodespaceProfileIdentity
import com.g2ray.client.parser.VlessUriParser
import com.g2ray.client.vpn.SpeedFormatter

sealed interface SettingsDest {
    object Accounts : SettingsDest
    data class AccountEditor(val accountId: String?) : SettingsDest
    object WorkerControlPlane : SettingsDest
    data class ApiKeys(val accountId: String) : SettingsDest
    data class ApiKeyEditor(val accountId: String, val keyId: String?) : SettingsDest
    data class Profiles(val accountId: String) : SettingsDest
    data class ProfileImport(val accountId: String) : SettingsDest
    object Connection : SettingsDest
    object Routing : SettingsDest
    data class Quota(val accountId: String) : SettingsDest
}

internal fun encodeSettingsDestination(destination: SettingsDest): String = when (destination) {
    SettingsDest.Accounts -> "accounts"
    SettingsDest.WorkerControlPlane -> "worker"
    SettingsDest.Connection -> "connection"
    SettingsDest.Routing -> "routing"
    is SettingsDest.AccountEditor -> "account|${destination.accountId.orEmpty()}"
    is SettingsDest.ApiKeys -> "keys|${destination.accountId}"
    is SettingsDest.ApiKeyEditor -> "key|${destination.accountId}|${destination.keyId.orEmpty()}"
    is SettingsDest.Profiles -> "profiles|${destination.accountId}"
    is SettingsDest.ProfileImport -> "import|${destination.accountId}"
    is SettingsDest.Quota -> "quota|${destination.accountId}"
}

internal fun decodeSettingsDestination(value: String): SettingsDest? {
    val parts = value.split('|')
    return when (parts.firstOrNull()) {
        "accounts" -> SettingsDest.Accounts
        "worker" -> SettingsDest.WorkerControlPlane
        "connection" -> SettingsDest.Connection
        "routing" -> SettingsDest.Routing
        "account" -> SettingsDest.AccountEditor(parts.getOrNull(1)?.ifBlank { null })
        "keys" -> parts.getOrNull(1)?.takeIf(String::isNotBlank)?.let(SettingsDest::ApiKeys)
        "key" -> parts.getOrNull(1)?.takeIf(String::isNotBlank)?.let { SettingsDest.ApiKeyEditor(it, parts.getOrNull(2)?.ifBlank { null }) }
        "profiles" -> parts.getOrNull(1)?.takeIf(String::isNotBlank)?.let(SettingsDest::Profiles)
        "import" -> parts.getOrNull(1)?.takeIf(String::isNotBlank)?.let(SettingsDest::ProfileImport)
        "quota" -> parts.getOrNull(1)?.takeIf(String::isNotBlank)?.let(SettingsDest::Quota)
        else -> null
    }
}

internal fun wakeKeyDisplayTextVisibleForTesting(secret: String): String = wakeKeyDisplayText(secret)

private fun wakeKeyDisplayText(secret: String): String {
    return secret.ifBlank { "(empty wake key)" }
}

private fun wakeModeLabel(mode: CodespaceWakeMode): String = when (mode) {
    CodespaceWakeMode.AUTO -> "Auto"
    CodespaceWakeMode.WORKER -> "Worker"
    CodespaceWakeMode.GITHUB_DIRECT -> "GitHub"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: G2RayViewModel,
    onNavigateBack: () -> Unit
) {
    val savedDataReady by viewModel.savedDataReady.collectAsStateWithLifecycle()
    val savedDataError by viewModel.repositoryLoadError.collectAsStateWithLifecycle()
    val isReloading by viewModel.isReloadingSavedData.collectAsStateWithLifecycle()
    val originalConfig by viewModel.legacyProfileForRecovery.collectAsStateWithLifecycle()
    val vpnState by VpnStateRepository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (!savedDataReady) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Saved Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
                SavedDataNotice(
                    message = savedDataError ?: "Reloading saved settings...",
                    reloading = isReloading,
                    retryEnabled = vpnState.tunnelState in setOf(TunnelState.Disabled, TunnelState.Disconnected, TunnelState.Error, TunnelState.Blocked),
                    onRetry = viewModel::reloadSavedData,
                    onCopyOriginal = originalConfig?.let { raw ->
                        {
                            val copied = copyConfigLinkToClipboard(context, raw)
                            Toast.makeText(context, if (copied) "Original config copied" else "Clipboard unavailable", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
        return
    }
    val backstack = rememberSaveable(saver = listSaver(
        save = { stack: androidx.compose.runtime.snapshots.SnapshotStateList<SettingsDest> -> stack.map(::encodeSettingsDestination) },
        restore = { saved -> saved.mapNotNull(::decodeSettingsDestination).ifEmpty { listOf(SettingsDest.Accounts) }.toMutableStateList() }
    )) { mutableStateListOf<SettingsDest>(SettingsDest.Accounts) }
    val currentDest = backstack.last()

    fun handleBack() {
        if (backstack.size > 1) {
            backstack.removeAt(backstack.lastIndex)
        } else {
            onNavigateBack()
        }
    }

    val savingSettings by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    BackHandler(enabled = backstack.size > 1) { if (!savingSettings) handleBack() }
    val feedback by viewModel.statusMessage.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().imePadding()) {
      Box(Modifier.weight(1f)) {
      when (currentDest) {
        is SettingsDest.Accounts -> {
            AccountsScreen(
                viewModel = viewModel,
                onNavigateBack = { handleBack() },
                onAddAccount = { backstack.add(SettingsDest.AccountEditor(null)); Unit },
                onEditAccount = { backstack.add(SettingsDest.AccountEditor(it)); Unit },
                onManageSharedWorker = { backstack.add(SettingsDest.WorkerControlPlane); Unit },
                onConnectionSettings = { backstack.add(SettingsDest.Connection); Unit },
                onRoutingSettings = { backstack.add(SettingsDest.Routing); Unit },
                onManageKeys = { backstack.add(SettingsDest.ApiKeys(it)); Unit },
                onManageQuota = { backstack.add(SettingsDest.Quota(it)); Unit },
                onManageProfiles = { backstack.add(SettingsDest.Profiles(it)); Unit }
            )
        }
        is SettingsDest.AccountEditor -> {
            AccountEditorScreen(
                viewModel = viewModel,
                accountId = currentDest.accountId,
                onNavigateBack = { handleBack() }
            )
        }
        is SettingsDest.WorkerControlPlane -> {
            WorkerControlPlaneScreen(viewModel = viewModel, onNavigateBack = { handleBack() })
        }
        is SettingsDest.ApiKeys -> {
            ApiKeysScreen(
                viewModel = viewModel,
                accountId = currentDest.accountId,
                onNavigateBack = { handleBack() },
                onAddKey = { backstack.add(SettingsDest.ApiKeyEditor(currentDest.accountId, null)); Unit },
                onEditKey = { backstack.add(SettingsDest.ApiKeyEditor(currentDest.accountId, it)); Unit }
            )
        }
        is SettingsDest.ApiKeyEditor -> {
            ApiKeyEditorScreen(
                viewModel = viewModel,
                accountId = currentDest.accountId,
                keyId = currentDest.keyId,
                onNavigateBack = { handleBack() }
            )
        }
        is SettingsDest.Profiles -> {
            ProfilesScreen(
                viewModel = viewModel,
                accountId = currentDest.accountId,
                delayStates = viewModel.profileDelayStates.collectAsStateWithLifecycle().value,
                onNavigateBack = { handleBack() },
                onImportProfile = { backstack.add(SettingsDest.ProfileImport(currentDest.accountId)); Unit }
            )
        }
        is SettingsDest.ProfileImport -> {
            ProfileImportScreen(
                viewModel = viewModel,
                accountId = currentDest.accountId,
                onNavigateBack = { handleBack() },
                onImported = { importedAccountId ->
                    handleBack()
                    if (backstack.lastOrNull() is SettingsDest.Profiles) {
                        backstack[backstack.lastIndex] = SettingsDest.Profiles(importedAccountId)
                    }
                }
            )
        }
        SettingsDest.Connection -> ConnectionSettingsScreen(viewModel, ::handleBack)
        SettingsDest.Routing -> RoutingSettingsScreen(viewModel, ::handleBack)
        is SettingsDest.Quota -> QuotaSettingsScreen(viewModel, currentDest.accountId, ::handleBack)
      }
      }
      if (feedback != "Idle") Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
          Text(feedback, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
              style = MaterialTheme.typography.bodySmall, maxLines = 3)
      }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    viewModel: G2RayViewModel,
    onNavigateBack: () -> Unit,
    onAddAccount: () -> Unit,
    onEditAccount: (String) -> Unit,
    onManageSharedWorker: () -> Unit,
    onConnectionSettings: () -> Unit,
    onRoutingSettings: () -> Unit,
    onManageKeys: (String) -> Unit,
    onManageQuota: (String) -> Unit,
    onManageProfiles: (String) -> Unit
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val activeSelection by viewModel.activeSelection.collectAsStateWithLifecycle()
    val keys by viewModel.apiKeys.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val controlPlane by viewModel.workerControlPlane.collectAsStateWithLifecycle()
    var accountPendingDelete by remember { mutableStateOf<G2RayAccount?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onConnectionSettings) { Icon(Icons.Default.Tune, "Connection settings") }
                    IconButton(onClick = onRoutingSettings) { Icon(Icons.AutoMirrored.Filled.AltRoute, "Bypass routing") }
                    IconButton(onClick = onManageSharedWorker) {
                        Icon(Icons.Default.SettingsEthernet, contentDescription = "Shared Worker control plane")
                    }
                    IconButton(onClick = onAddAccount) {
                        Icon(Icons.Default.Add, contentDescription = "Add Codespace")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (accounts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No Codespaces configured yet.", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onAddAccount) {
                        Text("Add First Codespace")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (controlPlane == null) {
                        "Shared Worker is not configured. Codespaces with a direct GitHub token can still be woken."
                    } else {
                        "Shared Worker: ${controlPlane?.workerBaseUrl}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (controlPlane == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                accounts.forEach { account ->
                    val isActive = account.id == activeSelection.accountId
                    val accountProfs = profiles.filter { it.accountId == account.id }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectAccount(account.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isActive) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Active",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
                                    Text(
                                        text = account.displayName,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                Row {
                                    IconButton(onClick = { onEditAccount(account.id) }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                                    }
                                    IconButton(onClick = { accountPendingDelete = account }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            }

                            if (!account.codespaceName.isNullOrEmpty()) {
                                Text("Codespace: ${account.codespaceName}", style = MaterialTheme.typography.bodyMedium)
                            }
                            val directTokenConfigured = keys.any {
                                it.accountId == account.id && it.kind == ApiKeyKind.GITHUB_TOKEN &&
                                    it.enabled && it.encryptedValue.isNotBlank()
                            }
                            Text(
                                "Wake: ${wakeModeLabel(account.wakeMode)}" +
                                    if (directTokenConfigured) " + direct token" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            account.lastWorkerMessage?.let { message ->
                                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onManageProfiles(account.id) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    )
                                ) {
                                    Icon(Icons.Default.NetworkPing, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Configs (${accountProfs.size})", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Row {
                                TextButton(onClick = { onManageKeys(account.id) }) { Text("Keys") }
                                TextButton(onClick = { onManageQuota(account.id) }) { Text("Usage") }
                            }
                        }
                    }
                }
            }
        }
    }

    accountPendingDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { accountPendingDelete = null },
            title = { Text("Delete Codespace") },
            text = { Text("Delete ${account.displayName}? This removes this Codespace, its direct GitHub token, and its local configs. The shared Worker remains available for other Codespaces.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAccount(account.id)
                        accountPendingDelete = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { accountPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditorScreen(
    viewModel: G2RayViewModel,
    accountId: String?,
    onNavigateBack: () -> Unit
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val apiKeys by viewModel.apiKeys.collectAsStateWithLifecycle()
    val controlPlane by viewModel.workerControlPlane.collectAsStateWithLifecycle()
    val editingAccount = remember(accountId, accounts) { accounts.find { it.id == accountId } }
    val directToken = remember(editingAccount?.id, apiKeys) {
        apiKeys.firstOrNull {
            it.accountId == editingAccount?.id && it.kind == ApiKeyKind.GITHUB_TOKEN && it.enabled
        }
    }

    val drafts: FormDraftViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val saving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    var displayName by rememberSaveable(editingAccount?.id) { mutableStateOf(editingAccount?.displayName ?: "") }
    var codespaceName by rememberSaveable(editingAccount?.id) { mutableStateOf(editingAccount?.codespaceName ?: "") }
    var wakeMode by rememberSaveable(editingAccount?.id) {
        mutableStateOf(editingAccount?.wakeMode ?: CodespaceWakeMode.AUTO)
    }
    var githubToken by drafts.text("account:${accountId}:${directToken?.updatedAt}:token", directToken?.encryptedValue.orEmpty())
    var statusMessage by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (accountId == null) "Add Codespace" else "Edit Codespace") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display Name") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = codespaceName,
                onValueChange = { codespaceName = it },
                label = { Text("GitHub Codespace Name") },
                supportingText = { Text("The exact Codespace name shown by GitHub.") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wake Method", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                FeatureInfoButton(FeatureHelp.Wake)
            }
            val wakeModes = CodespaceWakeMode.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                wakeModes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = wakeMode == mode,
                        onClick = { wakeMode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index, wakeModes.size)
                    ) {
                        Text(wakeModeLabel(mode))
                    }
                }
            }

            if (wakeMode != CodespaceWakeMode.WORKER) {
                OutlinedTextField(
                    value = githubToken,
                    onValueChange = { githubToken = it },
                    label = { Text("GitHub Token${if (wakeMode == CodespaceWakeMode.AUTO) " (optional)" else ""}") },
                    supportingText = {
                        Text("Fine-grained PAT: Codespaces read + Codespaces lifecycle admin write.")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = when {
                    wakeMode == CodespaceWakeMode.GITHUB_DIRECT -> "The app will contact GitHub directly; no Cloudflare Worker is required."
                    controlPlane == null -> "No shared Worker is configured. Auto mode will use the direct token when present."
                    wakeMode == CodespaceWakeMode.WORKER -> "Shared Worker: ${controlPlane?.workerBaseUrl}"
                    else -> "Auto uses the shared Worker first, then direct GitHub only if the Worker is unreachable."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (statusMessage.isNotEmpty()) {
                Text(statusMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = {
                    viewModel.saveAccount(
                        id = accountId,
                        displayName = displayName,
                        workerUrl = controlPlane?.workerBaseUrl.orEmpty(),
                        fallbackWorkerUrl = controlPlane?.fallbackWorkerBaseUrl,
                        codespaceName = codespaceName,
                        workerCodespaceId = editingAccount?.workerCodespaceId
                            ?.takeIf { it != editingAccount.codespaceName }
                            ?: codespaceName,
                        wakeMode = wakeMode,
                        githubToken = githubToken
                    ) { result ->
                        if (result.isSuccess) {
                            onNavigateBack()
                        } else {
                            statusMessage = result.exceptionOrNull()?.message ?: "An error occurred"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving
            ) {
                Text(if (saving) "Saving..." else "Save Codespace")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerControlPlaneScreen(
    viewModel: G2RayViewModel,
    onNavigateBack: () -> Unit
) {
    val controlPlane by viewModel.workerControlPlane.collectAsStateWithLifecycle()
    val drafts: FormDraftViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val saving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    var workerUrl by rememberSaveable(controlPlane?.updatedAt) { mutableStateOf(controlPlane?.workerBaseUrl.orEmpty()) }
    var fallbackUrl by rememberSaveable(controlPlane?.updatedAt) { mutableStateOf(controlPlane?.fallbackWorkerBaseUrl.orEmpty()) }
    var wakeSecret by drafts.text("worker:${controlPlane?.updatedAt}:secret", controlPlane?.encryptedWakeSecret.orEmpty())
    var statusMessage by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shared Worker") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            Text(
                "One Worker URL and one wake key manage every saved Codespace. The app selects each one by its exact GitHub Codespace name.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = workerUrl,
                onValueChange = { workerUrl = it },
                label = { Text("Primary Worker URL") },
                placeholder = { Text("https://worker.example.workers.dev") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = fallbackUrl,
                onValueChange = { fallbackUrl = it },
                label = { Text("Backup Worker URL (optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = wakeSecret,
                onValueChange = { wakeSecret = it },
                label = { Text("Shared Wake Key") },
                supportingText = { Text("Visible on this device by design. Do not share it outside your own Worker setup.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
            if (statusMessage.isNotEmpty()) {
                Text(statusMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    viewModel.saveWorkerControlPlane(workerUrl, fallbackUrl, wakeSecret) { result ->
                        if (result.isSuccess) onNavigateBack()
                        else statusMessage = result.exceptionOrNull()?.message ?: "Could not save shared Worker"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving
            ) {
                Text(if (saving) "Saving..." else "Save Shared Worker")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(
    viewModel: G2RayViewModel,
    accountId: String,
    onNavigateBack: () -> Unit,
    onAddKey: () -> Unit,
    onEditKey: (String) -> Unit
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val account = remember(accountId, accounts) { accounts.find { it.id == accountId } }
    val apiKeys by viewModel.apiKeys.collectAsStateWithLifecycle()
    val activeSelection by viewModel.activeSelection.collectAsStateWithLifecycle()
    val accountKeys = apiKeys.filter { it.accountId == accountId }
    var keyPendingDelete by remember { mutableStateOf<G2RayApiKey?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keys: ${account?.displayName ?: ""}") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onAddKey) {
                        Icon(Icons.Default.Add, contentDescription = "Add Key")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (accountKeys.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No Wake Keys configured for this account.", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onAddKey) {
                        Text("Add Wake Key")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                accountKeys.forEach { key ->
                    val isActive = key.id == activeSelection.apiKeyId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (key.enabled) viewModel.selectApiKey(key.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isActive) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
                                    Text(
                                        key.label,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                SelectionContainer {
                                    Text(wakeKeyDisplayText(key.encryptedValue), style = MaterialTheme.typography.bodyMedium)
                                }
                                if (key.lastUsedAt != null) {
                                    Text(
                                        "Last used: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(key.lastUsedAt))}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = key.enabled,
                                    onCheckedChange = { viewModel.saveApiKey(key.id, accountId, key.label, "", it) }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(onClick = { onEditKey(key.id) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                                }
                                IconButton(onClick = { keyPendingDelete = key }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    keyPendingDelete?.let { key ->
        AlertDialog(
            onDismissRequest = { keyPendingDelete = null },
            title = { Text("Delete Wake Key") },
            text = { Text("Delete ${key.label}? You will need to add this wake secret again if you remove it.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteApiKey(key.id)
                        keyPendingDelete = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { keyPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyEditorScreen(
    viewModel: G2RayViewModel,
    accountId: String,
    keyId: String?,
    onNavigateBack: () -> Unit
) {
    val apiKeys by viewModel.apiKeys.collectAsStateWithLifecycle()
    val editingKey = remember(keyId, apiKeys) { apiKeys.find { it.id == keyId } }

    val drafts: FormDraftViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val saving by viewModel.isSavingSettings.collectAsStateWithLifecycle()
    var label by rememberSaveable(editingKey?.id) { mutableStateOf(editingKey?.label ?: "") }
    var secret by drafts.text("key:${accountId}:${keyId}:${editingKey?.updatedAt}", editingKey?.encryptedValue.orEmpty())
    var enabled by rememberSaveable(editingKey?.id) { mutableStateOf(editingKey?.enabled ?: true) }
    var statusMessage by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (keyId == null) "Add Wake Key" else "Edit Wake Key") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Key Label") },
                placeholder = { Text("E.g. Personal Key, Backup Key") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("Wake Secret") },
                supportingText = { Text("Visible on this device. Keep it private and only use your own Worker.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enabled")
                FeatureInfoButton(FeatureHelp.Key)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            if (statusMessage.isNotEmpty()) {
                Text(statusMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = {
                    viewModel.saveApiKey(
                        id = keyId,
                        accountId = accountId,
                        label = label,
                        secret = secret,
                        enabled = enabled
                    ) { result ->
                        if (result.isSuccess) {
                            onNavigateBack()
                        } else {
                            statusMessage = result.exceptionOrNull()?.message ?: "An error occurred"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving
            ) {
                Text(if (saving) "Saving..." else "Save Key")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: G2RayViewModel,
    accountId: String,
    delayStates: Map<String, ProfileDelayState>,
    onNavigateBack: () -> Unit,
    onImportProfile: () -> Unit
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val account = remember(accountId, accounts) { accounts.find { it.id == accountId } }
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeSelection by viewModel.activeSelection.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val measurementNetworkKey by viewModel.measurementNetworkKey.collectAsStateWithLifecycle()
    val accountProfiles = profiles
        .filter { it.accountId == accountId }
        .sortedWith(
            compareBy<StoredG2RayProfile> { ProfileHealth.rank(it, now, measurementNetworkKey) }
                .thenBy { ProfileHealth.delaySortValue(it, now, measurementNetworkKey) }
                .thenByDescending { it.lastDelayTestedAt ?: 0L }
                .thenBy { it.createdAt }
        )
    var profilePendingDelete by remember { mutableStateOf<StoredG2RayProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configs: ${account?.displayName ?: ""}") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onImportProfile) {
                        Icon(Icons.Default.Add, contentDescription = "Import Profile")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (accountProfiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No VLESS profiles configured.", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onImportProfile) {
                        Text("Import Profiles")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                accountProfiles.forEach { profile ->
                    val isActive = profile.id == activeSelection.profileId
                    val delayState = delayStates[profile.id]
                    val displayedDelay = ProfileHealth.displayDelay(profile, delayState, networkKey = measurementNetworkKey)
                    val effectiveDelayMs = displayedDelay.delayMs
                    val effectiveP95Ms = displayedDelay.p95Ms
                    val effectiveSuccessfulSamples = displayedDelay.successfulSamples
                    val effectiveSampleCount = displayedDelay.sampleCount
                    val effectiveError = displayedDelay.error

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectProfile(profile.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isActive) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
                                    Text(
                                        profile.displayName,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                Text("Address: ${profile.address}:${profile.port}", style = MaterialTheme.typography.bodySmall)
                                Text("Transport: ${profile.transport.name} (${profile.path})", style = MaterialTheme.typography.bodySmall)
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
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                val delayText = when {
                                    delayState?.testing == true -> "Real delay: testing..."
                                    effectiveDelayMs != null -> buildString {
                                        append("Real delay: median ${effectiveDelayMs} ms")
                                        effectiveP95Ms?.let { append(", p95 ${it} ms") }
                                        if (effectiveSampleCount > 0) {
                                            append(" (${effectiveSuccessfulSamples}/${effectiveSampleCount} samples)")
                                        }
                                    }
                                    effectiveError != null -> "Real delay: ${effectiveError}${failureSuffix(profile.delayFailureCount)}"
                                    else -> "Real delay: not tested"
                                }
                                Text(
                                    delayText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (effectiveError != null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.secondary
                                    }
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { viewModel.measureProfileDelay(profile.id) },
                                    enabled = delayStates.values.none { it.testing }
                                ) {
                                    Text(if (delayState?.testing == true) "..." else "Ping")
                                }
                                ConfigShareButton(profile.rawUri)
                                IconButton(onClick = { profilePendingDelete = profile }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    profilePendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { profilePendingDelete = null },
            title = { Text("Delete Config") },
            text = { Text("Delete ${profile.displayName}? This removes the local imported config from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile.id)
                        profilePendingDelete = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { profilePendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun failureSuffix(count: Int): String {
    if (count <= 0) return ""
    return if (count == 1) " (1 fail)" else " ($count fails)"
}

private data class PendingProfileImport(val accountId: String, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileImportScreen(
    viewModel: G2RayViewModel,
    accountId: String,
    onNavigateBack: () -> Unit,
    onImported: (String) -> Unit
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val drafts: FormDraftViewModel = viewModel()
    val draft = remember(accountId) { drafts.imports.getOrPut(accountId) { ProfileImportDraft(accountId) } }
    var targetAccountId by draft.targetAccountId
    val targetAccount = accounts.firstOrNull { it.id == targetAccountId }
    var showTargetPicker by remember { mutableStateOf(false) }
    var importing by draft.importing
    val parsing by draft.parsing
    var pendingImport by remember { mutableStateOf<PendingProfileImport?>(null) }
    var inputText by draft.inputText
    var parseResults by draft.results
    var selectedToImport by draft.selection
    var statusMessage by draft.status
    var errorLogs by draft.errors
    val importedAccountId by draft.importedAccountId
    LaunchedEffect(importedAccountId) {
        importedAccountId?.let { imported ->
            draft.importedAccountId.value = null
            drafts.imports.remove(accountId)
            onImported(imported)
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            if (scanned.length <= 1_000_000) {
                inputText = scanned
                parseResults = emptyList()
                drafts.parse(draft)
            } else statusMessage = "Scanned config is too large."
        }
    }
    BackHandler(enabled = importing) { statusMessage = "Import is finishing. Please wait." }

    fun submitImport(request: PendingProfileImport, allowUnidentifiedOrigins: Boolean = false) {
        if (importing) return
        importing = true
        statusMessage = ""
        viewModel.importProfilesText(request.accountId, request.text, allowUnidentifiedOrigins) { result ->
            importing = false
            if (result.isSuccess) {
                viewModel.saveQuotaFromBundle(request.accountId, draft.inputText.value)
                draft.importedAccountId.value = request.accountId
            } else {
                statusMessage = result.exceptionOrNull()?.message ?: "Import failed"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Profiles") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !importing && !parsing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showTargetPicker = true },
                    enabled = accounts.isNotEmpty() && !importing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(targetAccount?.displayName ?: "Choose Codespace")
                        targetAccount?.codespaceName?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose import destination")
                }
                DropdownMenu(
                    expanded = showTargetPicker,
                    onDismissRequest = { showTargetPicker = false },
                    modifier = Modifier.heightIn(max = 320.dp).widthIn(max = 320.dp)
                ) {
                    accounts.sortedBy { it.priority }.forEach { account ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(account.displayName)
                                    account.codespaceName?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            },
                            onClick = {
                                targetAccountId = account.id
                                showTargetPicker = false
                                statusMessage = ""
                            }
                        )
                    }
                }
            }
            if (targetAccount == null) {
                Text("Choose a saved Codespace before importing.", color = MaterialTheme.colorScheme.error)
            }
            if (statusMessage.isNotEmpty() && parseResults.isEmpty()) {
                Text(statusMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (parseResults.isEmpty()) {
                Text(
                    "Paste a raw VLESS URI, messy text that contains VLESS links, a multi-line list, or a Base64-encoded subscription.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { drafts.parse(draft) }, enabled = inputText.isNotBlank() && !parsing && !importing) {
                        Text(if (parsing) "Parsing..." else "Parse Configs")
                    }
                    OutlinedButton(onClick = {
                        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan a config or all-config QR").setBeepEnabled(false).setOrientationLocked(false))
                    }, enabled = !parsing && !importing) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Scan QR")
                    }
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = {
                        if (it.length <= 1_000_000) {
                            inputText = it
                            statusMessage = ""
                        } else statusMessage = "Import text is too large; use a smaller bundle."
                    },
                    label = { Text("Subscription Text / VLESS URI") },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    minLines = 4,
                    maxLines = 8,
                    enabled = !parsing && !importing
                )
            } else {
                Text("Select Profiles to Import", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))

                val successes = parseResults.mapIndexed { index, res -> index to res }.filter { it.second.isSuccess }
                if (successes.isEmpty()) {
                    Text("No profiles could be parsed successfully.", color = MaterialTheme.colorScheme.error)
                } else {
                    successes.forEach { (index, res) ->
                        val p = res.getOrThrow()
                        val isChecked = selectedToImport[index] ?: false
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !importing) {
                                    selectedToImport = selectedToImport + (index to !isChecked)
                                    statusMessage = ""
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                enabled = !importing,
                                onCheckedChange = {
                                    selectedToImport = selectedToImport + (index to it)
                                    statusMessage = ""
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(p.name, style = MaterialTheme.typography.titleSmall)
                                Text("${p.address}:${p.port} (${p.transport.name})", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                if (errorLogs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Unsupported profile issues detected:", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
                    errorLogs.forEach { log ->
                        Text("• $log", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (statusMessage.isNotEmpty()) {
                    Text(statusMessage, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = {
                        val selectedProfiles = parseResults.mapIndexedNotNull { index, res ->
                            if (res.isSuccess && (selectedToImport[index] == true)) {
                                res.getOrThrow()
                            } else {
                                null
                            }
                        }
                        val request = PendingProfileImport(targetAccountId, selectedProfiles.joinToString("\n") { it.rawUri })
                        val name = targetAccount?.codespaceName?.trim().orEmpty()
                        if (name.isNotEmpty() &&
                            selectedProfiles.any { !CodespaceProfileIdentity.hasKnownOrigin(it) } &&
                            selectedProfiles.none { CodespaceProfileIdentity.conflictsWith(it, name) }) {
                            pendingImport = request
                        } else {
                            submitImport(request)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = targetAccount != null && !importing && selectedToImport.values.any { it }
                ) {
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Import Selected (${selectedToImport.values.count { it }})")
                }

                OutlinedButton(
                    onClick = {
                        parseResults = emptyList()
                        selectedToImport = emptyMap()
                        errorLogs = emptyList()
                        statusMessage = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !importing
                ) {
                    Text("Start Over")
                }
            }
        }
    }
    pendingImport?.let { request ->
        val destination = accounts.firstOrNull { it.id == request.accountId }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Confirm Codespace binding") },
            text = {
                Text("A custom or unrecognized hostname does not identify its Codespace. Import into ${destination?.displayName ?: "the removed Codespace"}?")
            },
            confirmButton = {
                TextButton(enabled = destination != null, onClick = {
                    pendingImport = null
                    submitImport(request, allowUnidentifiedOrigins = true)
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
            }
        )
    }
}
