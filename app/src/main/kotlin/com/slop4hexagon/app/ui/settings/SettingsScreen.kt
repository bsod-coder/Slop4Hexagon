package com.slop4hexagon.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.slop4hexagon.app.ui.appViewModel
import com.slop4hexagon.app.ui.common.BannerTone
import com.slop4hexagon.app.ui.common.InfoBanner
import com.slop4hexagon.app.ui.common.KeyValueRow
import com.slop4hexagon.app.ui.common.MonoBlock
import com.slop4hexagon.app.ui.common.SectionCard
import com.slop4hexagon.app.ui.common.StatusChip
import com.slop4hexagon.core.backend.BackendPreference
import com.slop4hexagon.core.settings.ThemeMode

@Composable
fun SettingsScreen() {
    val vm: SettingsViewModel = appViewModel(::SettingsViewModel)
    val state by vm.state.collectAsState()
    val container = com.slop4hexagon.app.ui.rememberContainer()
    var exportMessage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            exportMessage = runCatching {
                com.slop4hexagon.app.platform.ImageStore.writeBytes(container.appContext, uri, vm.diagnosticJson().toByteArray())
                "diagnostics written to the chosen document"
            }.getOrElse { t -> "export failed: ${t::class.java.simpleName}: ${t.message}" }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                "Preferred backend",
                subtitle = "Selection falls back with an explicit reason; a fallback is never reported as success.",
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendPreference.entries.forEach { pref ->
                        FilterChip(
                            selected = state.settings.backendPreference == pref,
                            onClick = { vm.setBackendPreference(pref) },
                            label = { Text(pref.label()) },
                        )
                    }
                }
                if (state.fallbackOccurred) {
                    InfoBanner(
                        "A fallback occurred: the preferred backend could not be used and another one was " +
                            "selected instead. The reasons are listed below.",
                        BannerTone.WARN,
                    )
                }
                state.selectionReasons.forEach { reason ->
                    Text(reason, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(onClick = vm::reprobeBackends, modifier = Modifier.fillMaxWidth()) {
                    Text("re-probe backends")
                }
            }
        }

        item {
            SectionCard(
                "Backend states",
                subtitle = "Available ≠ Initialized ≠ Verified execution. Only a real executed inference " +
                    "moves a backend to Verified execution.",
            ) {
                if (state.statuses.isEmpty()) {
                    Text("No probe results yet.", style = MaterialTheme.typography.bodySmall)
                }
                state.statuses.forEach { info ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(info.displayName, style = MaterialTheme.typography.bodyMedium)
                            StatusChip(info.status)
                        }
                        Text(info.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        info.capabilities?.let { caps ->
                            Text(
                                "ops: " + (if (caps.supportedOps.isEmpty()) "none" else caps.supportedOps.joinToString { it.name }) +
                                    " · micro: ${caps.supportsMicroBenchmarks} · pipeline: ${caps.supportsImagePipeline}" +
                                    " · max elements: ${caps.maxTensorElements}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            caps.notes.forEach { note ->
                                Text("• $note", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Benchmark defaults") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("warm-up ${state.settings.warmupIterations} · measured ${state.settings.measuredIterations}", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1, 2, 3).forEach { w ->
                        FilterChip(
                            selected = state.settings.warmupIterations == w,
                            onClick = { vm.setIterations(w, state.settings.measuredIterations) },
                            label = { Text("warm-up $w") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5, 10).forEach { m ->
                        FilterChip(
                            selected = state.settings.measuredIterations == m,
                            onClick = { vm.setIterations(state.settings.warmupIterations, m) },
                            label = { Text("measured $m") },
                        )
                    }
                }
                InfoBanner(
                    "Benchmarks never start by themselves: a run is only executed when you press Start in " +
                        "the Benchmark tab. Warm-up runs are recorded separately and excluded from the " +
                        "statistics.",
                    BannerTone.NEUTRAL,
                )
            }
        }

        item {
            SectionCard("Appearance") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.settings.themeMode == mode,
                            onClick = { vm.setTheme(mode) },
                            label = { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Dynamic color", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Android 12+ wallpaper palette; ignored on older releases",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = state.settings.dynamicColor, onCheckedChange = vm::setDynamicColor)
                }
            }
        }

        item {
            SectionCard(
                "Device & runtime diagnostics",
                subtitle = "Public Android APIs only. Missing or restricted values are reported as null, " +
                    "and no Hexagon version is claimed unless inference really ran there.",
            ) {
                Button(onClick = vm::collectDiagnostics, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.busy) "collecting…" else "collect diagnostics snapshot")
                }
                OutlinedButton(onClick = { exportPicker.launch("slop4hexagon-diagnostics.json") }, modifier = Modifier.fillMaxWidth()) {
                    Text("export diagnostics json")
                }
                state.snapshot?.let { snap ->
                    KeyValueRow("SoC model", snap.socModel ?: "null (unavailable on this Android version)")
                    KeyValueRow("Build.HARDWARE", snap.hardware ?: "null")
                    KeyValueRow("ABIs", snap.supportedAbis.joinToString())
                    KeyValueRow("Android", "${snap.androidRelease} (API ${snap.sdkInt})")
                    KeyValueRow("Device", "${snap.manufacturer ?: "?"} ${snap.brand ?: ""} ${snap.model ?: ""}".trim())
                    snap.totalMemBytes?.let { KeyValueRow("Total memory", com.slop4hexagon.core.model.ModelNames.humanSize(it)) }
                    snap.availMemBytes?.let { KeyValueRow("Available memory", com.slop4hexagon.core.model.ModelNames.humanSize(it)) }
                    KeyValueRow("Hexagon verified", if (snap.hexagonVerified) "yes" else "no — never inferred from Build fields")
                    MonoBlock(
                        snap.notes.joinToString("\n\n", transform = { "• $it" }),
                        maxLines = 20,
                    )
                }
                state.message?.let { InfoBanner(it, BannerTone.NEUTRAL) }
                exportMessage?.let { InfoBanner(it, BannerTone.OK) }
                if (state.storeErrors.isNotEmpty()) {
                    InfoBanner(
                        "Storage/index notes:\n" + state.storeErrors.joinToString("\n- ", "- "),
                        BannerTone.WARN,
                    )
                }
            }
        }

        item {
            SectionCard("App") {
                KeyValueRow("Version", container.appVersion)
                KeyValueRow("Model storage", "app-private filesDir/models (imported copies only)")
                KeyValueRow("Network", "no INTERNET permission: local inference only")
                Text(
                    "QNN/HTP integration prerequisites are documented in docs/QNN_HTP_SETUP.md " +
                        "inside the repository.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun BackendPreference.label(): String = when (this) {
    BackendPreference.AUTO -> "Auto"
    BackendPreference.CPU -> "CPU"
    BackendPreference.GPU -> "GPU"
    BackendPreference.HTP -> "HTP"
}
