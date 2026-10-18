package com.g2ray.client.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.g2ray.client.data.QuotaEstimate
import com.g2ray.client.data.quotaCycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuotaSettingsScreen(vm: G2RayViewModel, accountId: String, onBack: () -> Unit) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    var usedHours by rememberSaveable { mutableStateOf("") }
    var usedMinutes by rememberSaveable { mutableStateOf("") }
    var budgetHours by rememberSaveable { mutableStateOf("60") }
    var running by rememberSaveable { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var loaded by rememberSaveable(accountId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(accountId) {
        if (loaded) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { runCatching { vm.quotaEstimates.read(accountId) } }
        result.onFailure { status = "Could not read the saved estimate. No usage was changed." }.getOrNull()?.let {
            val used = it.projectedUsedSeconds(System.currentTimeMillis())
            if (used != null) {
                usedHours = (used / 3600).toString()
                usedMinutes = (used % 3600 / 60).toString()
            } else status = "The recorded estimate expired. Enter the current panel time."
            budgetHours = (it.budgetSeconds / 3600).toString()
            running = it.assumedRunning
        }
        loaded = true
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Usage: ${account?.displayName.orEmpty()}") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureInfoButton(FeatureHelp.Quota)
            Text("Copy the current monthly used time from the Codespace panel, or import its all-config QR. This is a local estimate, not GitHub billing. Other Codespaces billed to the same account also consume its quota.")
            OutlinedTextField(usedHours, { usedHours = it }, enabled = loaded && !saving, label = { Text("Used hours this month") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(usedMinutes, { usedMinutes = it }, enabled = loaded && !saving, label = { Text("Additional minutes (0-59)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(budgetHours, { budgetHours = it }, enabled = loaded && !saving, label = { Text("Local hour budget (2-core)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            SettingSwitch("Assume host keeps running", running, !saving, FeatureHelp.Quota) { running = it }
            Text("If the host stops, this projection overestimates usage. Update it after a restart. An expired month becomes unknown instead of silently inventing fresh billing usage.")
            if (status.isNotBlank()) Text(status)
            Button(onClick = {
                val hours = usedHours.toLongOrNull()
                val minutes = usedMinutes.ifBlank { "0" }.toLongOrNull()
                val budget = budgetHours.toLongOrNull()
                if (hours == null || hours !in 0..744 || minutes == null || minutes !in 0..59 ||
                    hours * 3600 + minutes * 60 > 744 * 3600 || budget == null || budget !in 1..744) {
                    status = "Enter valid hours (0-744), minutes (0-59), and budget (1-744)."
                } else {
                    saving = true
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val result = withContext(Dispatchers.IO) { runCatching {
                            vm.quotaEstimates.save(accountId, QuotaEstimate(hours * 3600 + minutes * 60, budget * 3600, now, quotaCycle(now), running))
                        } }
                        saving = false
                        status = if (result.isSuccess) "Estimate updated." else "Could not save estimate."
                    }
                }
            }, enabled = loaded && !saving && account != null) { Text(if (saving) "Saving..." else "Update estimate") }
        }
    }
}
