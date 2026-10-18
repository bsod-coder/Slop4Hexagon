package com.slop4hexagon.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slop4hexagon.domain.model.BackendStatus

@Composable
fun BackendStatusChip(status: BackendStatus, backendId: String) {
    val label = when (status) {
        BackendStatus.AVAILABLE -> "$backendId • Available"
        BackendStatus.INITIALIZED -> "$backendId • Initialized"
        BackendStatus.VERIFIED_EXECUTION -> "$backendId • Verified"
        BackendStatus.UNSUPPORTED -> "$backendId • Unsupported"
        BackendStatus.FAILED -> "$backendId • Failed"
        BackendStatus.FALLBACK_OCCURRED -> "$backendId • Fallback"
    }
    val color = when (status) {
        BackendStatus.VERIFIED_EXECUTION -> MaterialTheme.colorScheme.primary
        BackendStatus.FAILED, BackendStatus.UNSUPPORTED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(labelColor = color),
        modifier = Modifier.padding(2.dp)
    )
}

@Composable
fun BackendRow(statuses: Map<String, String>) {
    Row(modifier = Modifier.padding(4.dp)) {
        statuses.forEach { (id, st) ->
            val status = try { BackendStatus.valueOf(st) } catch (_: Exception) { BackendStatus.UNSUPPORTED }
            BackendStatusChip(status, id)
        }
    }
}
