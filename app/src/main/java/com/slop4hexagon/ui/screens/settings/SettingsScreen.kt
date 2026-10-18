package com.slop4hexagon.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.slop4hexagon.SlopApp
import com.slop4hexagon.domain.model.BackendId
import com.slop4hexagon.domain.model.BackendStatus
import com.slop4hexagon.ui.components.BackendStatusChip

@Composable
fun SettingsScreen(
    vm: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            val ctx = LocalContext.current
            val app = ctx.applicationContext as SlopApp
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(app, ctx) as T
        }
    })
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings & Diagnostics", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Preferred Backend", style = MaterialTheme.typography.titleSmall)
                Text("Auto chooses best verified backend. Current choice influences Generate & Benchmark.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendId.values().forEach { id ->
                        AssistChip(onClick = { vm.setPreferredBackend(id) }, label = { Text(id.name + if (state.preferredBackend == id) " ✓" else "") })
                    }
                }
                Text("Selected: ${state.preferredBackend.name}", style = MaterialTheme.typography.labelMedium)
                Text("Memory/performance settings: HTP tuning requires QNN SDK. No toggle fakes success - status below shows real init.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Backend Status", style = MaterialTheme.typography.titleSmall)
                Text("Available = probe ok, Initialized = init succeeded, Verified = ran inference, Unsupported = missing SDK/driver, Failed = error, Fallback = used alternate.", style = MaterialTheme.typography.bodySmall)
                state.backendStatuses.forEach { (id, st) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BackendStatusChip(st, id.name)
                        Text(when (st) {
                            BackendStatus.AVAILABLE -> "Driver present but not proven to execute."
                            BackendStatus.INITIALIZED -> "Init ok, not yet verified on this run."
                            BackendStatus.VERIFIED_EXECUTION -> "Measured inference ran on this backend."
                            BackendStatus.UNSUPPORTED -> "Unavailable - see docs."
                            BackendStatus.FAILED -> "Init failed, check logs."
                            BackendStatus.FALLBACK_OCCURRED -> "Requested backend fell back to alternative."
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = vm::refreshBackends) { Text("Re-initialize") }
                if (state.backendStatuses[BackendId.HTP] == BackendStatus.UNSUPPORTED) {
                    Text("HTP unavailable: QNN SDK not bundled. See docs/HTP_INTEGRATION.md for required artifacts (libQnnHtp.so, DLC toolchain). App remains buildable without it.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Device & Runtime", style = MaterialTheme.typography.titleSmall)
                val d = state.deviceInfo
                if (d != null) {
                    Text("SOC_MODEL: ${d.socModel ?: "unknown (Build.SOC_MODEL API31+)"}")
                    Text("HARDWARE: ${d.hardware}")
                    Text("ABIs: ${d.supportedAbis.joinToString()}")
                    Text("Android ${d.androidVersion} API ${d.apiLevel}")
                    Text("Memory total ${d.totalMemMb}MB avail ${d.availMemMb}MB low=${d.lowMemory}")
                    Text("Thermal status: ${d.thermalStatus ?: "n/a"} (PowerManager.currentThermalStatus API29+)")
                    Text("App: ${d.appVersion}")
                } else Text("Collecting…")
                OutlinedButton(onClick = vm::refreshDiagnostics) { Text("Refresh") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Model Storage", style = MaterialTheme.typography.titleSmall)
                Text("Models stored in app private filesDir/models. SAF used for import/pick. Manage in Library.", style = MaterialTheme.typography.bodySmall)
                Text("Logs & diagnostics export", style = MaterialTheme.typography.titleSmall)
                Button(onClick = {
                    val text = vm.exportDiagnostics()
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        putExtra(Intent.EXTRA_SUBJECT, "Slop4Hexagon diagnostics")
                    }
                    ctx.startActivity(Intent.createChooser(intent, "Export diagnostics"))
                }) { Text("Share diagnostics") }
            }
        }

        Text("App version and runtime info above. Hexagon architecture not claimed unless verified via QNN runtime.", style = MaterialTheme.typography.bodySmall)
    }
}
