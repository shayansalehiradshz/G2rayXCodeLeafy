package com.g2ray.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.g2ray.client.model.*
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
    val backstack = remember { mutableStateListOf<SettingsDest>(SettingsDest.Accounts) }
    val currentDest = backstack.last()

    fun handleBack() {
        if (backstack.size > 1) {
            backstack.removeAt(backstack.lastIndex)
        } else {
            onNavigateBack()
        }
    }

    when (currentDest) {
        is SettingsDest.Accounts -> {
            AccountsScreen(
                viewModel = viewModel,
                onNavigateBack = { handleBack() },
                onAddAccount = { backstack.add(SettingsDest.AccountEditor(null)); Unit },
                onEditAccount = { backstack.add(SettingsDest.AccountEditor(it)); Unit },
                onManageSharedWorker = { backstack.add(SettingsDest.WorkerControlPlane); Unit },
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
                delayStates = viewModel.profileDelayStates.collectAsState().value,
                onNavigateBack = { handleBack() },
                onImportProfile = { backstack.add(SettingsDest.ProfileImport(currentDest.accountId)); Unit }
            )
        }
        is SettingsDest.ProfileImport -> {
            ProfileImportScreen(
                viewModel = viewModel,
                accountId = currentDest.accountId,
                onNavigateBack = { handleBack() }
            )
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
    onManageProfiles: (String) -> Unit
) {
    val accounts by viewModel.accounts.collectAsState()
    val activeSelection by viewModel.activeSelection.collectAsState()
    val keys by viewModel.apiKeys.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val controlPlane by viewModel.workerControlPlane.collectAsState()
    var accountPendingDelete by remember { mutableStateOf<G2RayAccount?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Codespaces") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
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
    val accounts by viewModel.accounts.collectAsState()
    val apiKeys by viewModel.apiKeys.collectAsState()
    val controlPlane by viewModel.workerControlPlane.collectAsState()
    val editingAccount = remember(accountId, accounts) { accounts.find { it.id == accountId } }
    val directToken = remember(editingAccount?.id, apiKeys) {
        apiKeys.firstOrNull {
            it.accountId == editingAccount?.id && it.kind == ApiKeyKind.GITHUB_TOKEN && it.enabled
        }
    }

    var displayName by remember(editingAccount?.id) { mutableStateOf(editingAccount?.displayName ?: "") }
    var codespaceName by remember(editingAccount?.id) { mutableStateOf(editingAccount?.codespaceName ?: "") }
    var wakeMode by remember(editingAccount?.id) {
        mutableStateOf(editingAccount?.wakeMode ?: CodespaceWakeMode.AUTO)
    }
    var githubToken by remember(editingAccount?.id, directToken?.updatedAt) {
        mutableStateOf(directToken?.encryptedValue.orEmpty())
    }
    var statusMessage by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (accountId == null) "Add Codespace" else "Edit Codespace") },
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

            Text("Wake Method", style = MaterialTheme.typography.titleSmall)
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Codespace")
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
    val controlPlane by viewModel.workerControlPlane.collectAsState()
    var workerUrl by remember(controlPlane?.updatedAt) { mutableStateOf(controlPlane?.workerBaseUrl.orEmpty()) }
    var fallbackUrl by remember(controlPlane?.updatedAt) { mutableStateOf(controlPlane?.fallbackWorkerBaseUrl.orEmpty()) }
    var wakeSecret by remember(controlPlane?.updatedAt) { mutableStateOf(controlPlane?.encryptedWakeSecret.orEmpty()) }
    var statusMessage by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shared Worker") },
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Shared Worker")
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
    val accounts by viewModel.accounts.collectAsState()
    val account = remember(accountId) { accounts.find { it.id == accountId } }
    val apiKeys by viewModel.apiKeys.collectAsState()
    val activeSelection by viewModel.activeSelection.collectAsState()
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
    val apiKeys by viewModel.apiKeys.collectAsState()
    val editingKey = remember(keyId, apiKeys) { apiKeys.find { it.id == keyId } }

    var label by remember(editingKey?.id) { mutableStateOf(editingKey?.label ?: "") }
    var secret by remember(editingKey?.id) { mutableStateOf(editingKey?.encryptedValue ?: "") }
    var enabled by remember(editingKey?.id) { mutableStateOf(editingKey?.enabled ?: true) }
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Key")
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
    val accounts by viewModel.accounts.collectAsState()
    val account = remember(accountId) { accounts.find { it.id == accountId } }
    val profiles by viewModel.profiles.collectAsState()
    val activeSelection by viewModel.activeSelection.collectAsState()
    val now = System.currentTimeMillis()
    val measurementNetworkKey by viewModel.measurementNetworkKey.collectAsState()
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
                                    enabled = delayState?.testing != true
                                ) {
                                    Text(if (delayState?.testing == true) "..." else "Ping")
                                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileImportScreen(
    viewModel: G2RayViewModel,
    accountId: String,
    onNavigateBack: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var parseResults by remember { mutableStateOf<List<Result<G2RayProfile>>>(emptyList()) }
    var selectedToImport by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    var statusMessage by remember { mutableStateOf("") }
    var errorLogs by remember { mutableStateOf<List<String>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Profiles") },
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
            if (parseResults.isEmpty()) {
                Text(
                    "Paste a raw VLESS URI, messy text that contains VLESS links, a multi-line list, or a Base64-encoded subscription.",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("Subscription Text / VLESS URI") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6
                )

                if (statusMessage.isNotEmpty()) {
                    Text(statusMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Button(
                    onClick = {
                        val parsed = VlessUriParser.parseAllProfiles(inputText)
                        if (parsed.isEmpty()) {
                            statusMessage = "No supported vless:// profiles found in the pasted text or subscription."
                        } else {
                            parseResults = parsed
                            selectedToImport = parsed.mapIndexed { index, res -> index to res.isSuccess }.toMap()
                            errorLogs = parsed.filter { it.isFailure }.map { it.exceptionOrNull()?.message ?: "Unknown error" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Parse Configs")
                }
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
                                .clickable { selectedToImport = selectedToImport + (index to !isChecked) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { selectedToImport = selectedToImport + (index to it) }
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

                Button(
                    onClick = {
                        val selectedUris = parseResults.mapIndexedNotNull { index, res ->
                            if (res.isSuccess && (selectedToImport[index] == true)) {
                                res.getOrThrow().rawUri
                            } else {
                                null
                            }
                        }
                        viewModel.importProfilesText(accountId, selectedUris.joinToString("\n")) { result ->
                            if (result.isSuccess) {
                                onNavigateBack()
                            } else {
                                statusMessage = result.exceptionOrNull()?.message ?: "Import failed"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedToImport.values.any { it }
                ) {
                    Text("Import Selected (${selectedToImport.values.count { it }})")
                }

                OutlinedButton(
                    onClick = {
                        parseResults = emptyList()
                        selectedToImport = emptyMap()
                        errorLogs = emptyList()
                        statusMessage = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Over")
                }
            }
        }
    }
}
