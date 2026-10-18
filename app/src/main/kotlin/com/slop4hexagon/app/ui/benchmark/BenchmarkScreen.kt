package com.slop4hexagon.app.ui.benchmark

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.slop4hexagon.app.ui.appViewModel
import com.slop4hexagon.app.ui.common.BannerTone
import com.slop4hexagon.app.ui.common.InfoBanner
import com.slop4hexagon.app.ui.common.KeyValueRow
import com.slop4hexagon.app.ui.common.MonoBlock
import com.slop4hexagon.app.ui.common.ProgressRow
import com.slop4hexagon.app.ui.common.SectionCard
import com.slop4hexagon.app.ui.common.StatusChip
import com.slop4hexagon.core.backend.BackendPreference
import com.slop4hexagon.core.backend.OpKind
import com.slop4hexagon.core.benchmark.BenchmarkMode
import com.slop4hexagon.core.benchmark.BenchmarkReport
import com.slop4hexagon.core.model.ModelDescriptor
import com.slop4hexagon.core.model.ModelNames

@Composable
fun BenchmarkScreen() {
    val vm: BenchmarkViewModel = appViewModel(::BenchmarkViewModel)
    val state by vm.state.collectAsState()

    var exportTarget by remember { mutableStateOf<BenchmarkReport?>(null) }
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> exportTarget?.let { vm.export(it, uri) }; exportTarget = null }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                "Benchmark mode",
                subtitle = "Micro-op mode times one fixed-shape operation. It is NOT full image " +
                    "generation, and every report labels the run as a micro-op benchmark.",
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BenchmarkMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = { vm.setMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, BenchmarkMode.entries.size),
                        ) { Text(if (mode == BenchmarkMode.MICRO_OP) "Backend micro-op" else "Full pipeline") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendPreference.entries.forEach { pref ->
                        FilterChip(
                            selected = state.backendPreference == pref,
                            onClick = { vm.setBackendPreference(pref) },
                            label = { Text(pref.label()) },
                        )
                    }
                }
                if (state.backendStatuses.isNotEmpty()) {
                    Text("Live backend states", style = MaterialTheme.typography.labelMedium)
                    state.backendStatuses.forEach { info ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(info.displayName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            StatusChip(info.status)
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Configuration") {
                if (state.mode == BenchmarkMode.MICRO_OP) {
                    OpDropdown(state, vm)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntField("rows / filters", state.rows) { v -> vm.setShape(v, state.inner, state.cols) }
                        IntField("inner / in-ch", state.inner) { v -> vm.setShape(state.rows, v, state.cols) }
                        IntField("cols / side", state.cols) { v -> vm.setShape(state.rows, state.inner, v) }
                    }
                    state.planOrNull()?.let { plan ->
                        KeyValueRow(
                            "Input buffers",
                            "${ModelNames.humanSize(plan.inputAElements * 4L)} + ${ModelNames.humanSize(plan.inputBElements * 4L)}",
                        )
                        KeyValueRow("Output shape", plan.outputShape.joinToString("x"))
                        plan.validate(CPU_ELEMENT_BUDGET).takeIf { it.isNotEmpty() }?.let {
                            InfoBanner(it.joinToString("\n- ", "- "), BannerTone.ERROR)
                        }
                    }
                } else {
                    Text(
                        "Full-pipeline mode times an end-to-end generation for an installed model, " +
                            "including initialization of the pipeline engine.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ModelChooser(state.models, state.modelId, vm)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("warm-up", state.settings.warmupIterations) { v ->
                        vm.setIterations(v, state.settings.measuredIterations)
                    }
                    IntField("measured", state.settings.measuredIterations) { v ->
                        vm.setIterations(state.settings.warmupIterations, v)
                    }
                }
                InfoBanner(vm.capabilityExplanation(), BannerTone.NEUTRAL)
            }
        }

        item {
            SectionCard("Run") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::run, enabled = !state.running) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text(if (state.running) "  running" else "  start benchmark")
                    }
                    OutlinedButton(onClick = vm::cancel, enabled = state.running) {
                        Icon(Icons.Default.Stop, null)
                        Text("  stop")
                    }
                }
                state.progress?.let { p ->
                    ProgressRow(
                        if (p.totalIterations > 0) p.iteration.toFloat() / p.totalIterations else null,
                        buildString {
                            append(p.phase.name.lowercase())
                            if (p.totalIterations > 0) append(" ${p.iteration}/${p.totalIterations}")
                            p.lastLatencyMillis?.let { append(" · %.3f ms".format(it)) }
                        },
                    )
                }
                state.message?.let { InfoBanner(it, BannerTone.WARN) }
                state.lastReport?.let { report ->
                    ReportCard(
                        report = report,
                        onJson = { vm.previewJson(report) },
                        onExport = {
                            exportTarget = report
                            savePicker.launch("slop4hexagon-${report.id}.json")
                        },
                    )
                }
            }
        }

        state.exportedText?.let { json ->
            item {
                SectionCard(
                    "Exported JSON",
                    subtitle = "The exact document the export writes to disk",
                ) {
                    MonoBlock(json, maxLines = 200)
                }
            }
        }

        item {
            SectionCard(
                "Saved runs (${state.history.size})",
                subtitle = "Newest first. Pick two to compare; export writes the stored report verbatim.",
            ) {
                if (state.history.isEmpty()) {
                    Text("Nothing recorded yet.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        items(state.history, key = { it.id }) { report ->
            HistoryRow(
                report = report,
                selected = state.comparison?.let { it.first == report.id || it.second == report.id } ?: false,
                onSelect = { vm.pickForComparison(report.id, state.history.map { it.id }) },
                onJson = { vm.previewJson(report) },
                onExport = {
                    exportTarget = report
                    savePicker.launch("slop4hexagon-${report.id}.json")
                },
                onDelete = { vm.deleteReport(report.id) },
            )
        }

        state.comparisonSummary?.let { summary ->
            item {
                SectionCard("Comparison") {
                    summary.rows.forEach { row ->
                        KeyValueRow(
                            row.metric,
                            buildString {
                                append(row.left)
                                append("  →  ")
                                append(row.right)
                                row.deltaPercent?.let { append("  (%.1f%%)".format(it)) }
                            },
                        )
                    }
                    InfoBanner(summary.verdict, BannerTone.NEUTRAL)
                }
            }
        }
    }
}

private const val CPU_ELEMENT_BUDGET = 1L shl 24

private fun BackendPreference.label(): String = when (this) {
    BackendPreference.AUTO -> "Auto"
    BackendPreference.CPU -> "CPU"
    BackendPreference.GPU -> "GPU"
    BackendPreference.HTP -> "HTP"
}

@Composable
private fun OpDropdown(state: BenchmarkViewModel.UiState, vm: BenchmarkViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val implemented = state.supportedOps
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = state.op.displayName + if (state.op in implemented) "" else "   (no backend implements this)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Operation") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OpKind.entries.forEach { op ->
                DropdownMenuItem(
                    text = {
                        Text(
                            op.displayName + if (op in implemented) "" else "   — not implemented by any backend here",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    onClick = { vm.setOp(op); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ModelChooser(models: List<ModelDescriptor>, selectedId: String?, vm: BenchmarkViewModel) {
    if (models.isEmpty()) {
        InfoBanner(
            "Prerequisite not met: no model is installed. Import one in the Model Library first. " +
                "This app will not report invented numbers for a run that cannot happen.",
            BannerTone.ERROR,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedId?.let { id -> models.firstOrNull { it.id == id }?.displayName } ?: "no model selected",
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${model.displayName} · ${model.inspection.format.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    onClick = { vm.setModel(model.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun IntField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.weight(1f),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun ReportCard(report: BenchmarkReport, onJson: () -> Unit, onExport: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(report.id, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (report.executionVerified) "verified execution" else "NOT verified",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (report.executionVerified) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            KeyValueRow("Backend", "${report.backendDisplayName} (${report.backendId})")
            KeyValueRow("Status before → after", "${report.backendStatusAtStart} → ${report.backendStatusAtEnd}")
            KeyValueRow("Mode", report.mode.label())
            report.phases.coldStartNanos?.let { KeyValueRow("Cold start (first touch)", "%.3f ms".format(it / 1e6)) }
            report.phases.initNanos?.let { KeyValueRow("Initialization", "%.3f ms".format(it / 1e6)) }
            if (report.phases.warmupNanos.isNotEmpty()) {
                KeyValueRow(
                    "Warm-up (excluded from stats)",
                    report.phases.warmupNanos.joinToString(", ") { "%.2f ms".format(it / 1e6) },
                )
            }
            val stats = report.stats
            if (stats == null) {
                InfoBanner(
                    "No statistics recorded: this run completed no measured iteration." +
                        report.failures.firstOrNull()?.let { " First failure: $it" }.orEmpty(),
                    BannerTone.ERROR,
                )
            } else {
                KeyValueRow("Measured runs", stats.count.toString())
                KeyValueRow("Median", "%.3f ms".format(stats.medianNanos / 1e6))
                KeyValueRow("Min / max", "%.3f / %.3f ms".format(stats.minNanos / 1e6, stats.maxNanos / 1e6))
                KeyValueRow("Mean", "%.3f ms".format(stats.meanNanos / 1e6))
                KeyValueRow("p95", "%.3f ms".format(stats.p95Nanos / 1e6))
            }
            report.memory?.let { m ->
                m.peakRssBytes?.let { KeyValueRow("Peak RSS", ModelNames.humanSize(it)) }
                m.javaHeapUsedBytes?.let { KeyValueRow("Java heap (now)", ModelNames.humanSize(it)) }
                m.nativeHeapUsedBytes?.let { KeyValueRow("Native heap (now)", ModelNames.humanSize(it)) }
                KeyValueRow("Memory note", m.note)
            }
            report.thermal?.let { KeyValueRow("Thermal", "${it.thermalStatusName ?: "n/a"} — ${it.note}") }
            KeyValueRow(
                "Per-iteration samples",
                if (report.phases.inferenceNanos.isEmpty()) {
                    "none"
                } else {
                    report.phases.inferenceNanos.joinToString(", ") { "%.2f ms".format(it / 1e6) }
                },
            )
            report.failures.forEach { InfoBanner(it, BannerTone.ERROR) }
            report.notes.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onJson) { Text("json") }
                TextButton(onClick = onExport) {
                    Icon(Icons.Default.Share, null)
                    Text(" export")
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    report: BenchmarkReport,
    selected: Boolean,
    onSelect: () -> Unit,
    onJson: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(report.id, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    report.stats?.let { "%.3f ms median".format(it.medianNanos / 1e6) } ?: "no data",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            KeyValueRow("Backend", report.backendDisplayName)
            KeyValueRow("Mode", report.mode.label())
            KeyValueRow("Verified execution", if (report.executionVerified) "yes" else "no")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onSelect) { Text(if (selected) "selected" else "compare") }
                OutlinedButton(onClick = onJson) { Text("json") }
                OutlinedButton(onClick = onExport) { Text("export") }
                OutlinedButton(onClick = onDelete) { Text("delete") }
            }
        }
    }
}

private fun BenchmarkMode.label(): String = when (this) {
    BenchmarkMode.MICRO_OP -> "micro-op (single fixed-shape operation)"
    BenchmarkMode.FULL_PIPELINE -> "full pipeline (end-to-end generation)"
}
