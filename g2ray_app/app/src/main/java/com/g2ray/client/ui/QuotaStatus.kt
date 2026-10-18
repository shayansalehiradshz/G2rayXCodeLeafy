package com.g2ray.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun QuotaStatus(vm: G2RayViewModel, accountId: String) {
    val owner = LocalLifecycleOwner.current
    var label by remember(accountId) { mutableStateOf("Usage: no estimate; update Usage in Settings") }
    var warning by remember(accountId) { mutableStateOf(false) }
    LaunchedEffect(accountId, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val now = System.currentTimeMillis()
                val data = withContext(Dispatchers.IO) { runCatching {
                    vm.quotaEstimates.read(accountId) to vm.quotaEstimates.isConfirmedBlocked(accountId, now)
                }.getOrNull() }
                val estimate = data?.first
                val blocked = data?.second == true
                val used = estimate?.projectedUsedSeconds(now)
                warning = blocked || (used != null && estimate.budgetSeconds - used <= 3600)
                label = when {
                    blocked -> "GitHub reported a quota/billing block this month. Try another saved Codespace or check billing."
                    estimate != null -> estimate.label(now)
                    else -> "Usage: no estimate; update Usage in Settings"
                }
                delay(60_000)
            }
        }
    }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
