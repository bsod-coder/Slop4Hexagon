package com.slop4hexagon.ui.screens.benchmark

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.slop4hexagon.SlopApp
import com.slop4hexagon.domain.benchmark.BenchmarkAggregator
import com.slop4hexagon.domain.benchmark.BenchmarkMode
import com.slop4hexagon.domain.benchmark.BenchmarkResult
import com.slop4hexagon.domain.model.BackendId

@Composable
fun BenchmarkScreen(
    vm: BenchmarkViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            val app = LocalContext.current.applicationContext as SlopApp
            @Suppress("UNCHECKED_CAST")
            return BenchmarkViewModel(app) as T
        }
    })
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Benchmark Lab", style = MaterialTheme.typography.headlineSmall)
        Text("Real measurements only. Uses SystemClock.elapsedRealtimeNanos(). Warmup separate from steady-state. Exportable JSON.", style = MaterialTheme.typography.bodySmall)

        // Model picker
        ModelPicker(state, vm)

        // Backend picker
        Text("Backend", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackendId.values().forEach { id ->
                AssistChip(onClick = { vm.selectBackend(id) }, label = { Text(id.name) })
            }
        }

        // Mode
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { vm.selectMode(BenchmarkMode.MICRO) }, label = { Text("Micro (matmul)") })
            AssistChip(onClick = { vm.selectMode(BenchmarkMode.FULL_PIPELINE) }, label = { Text("Full Pipeline") })
        }
        Text(
            if (state.mode == BenchmarkMode.MICRO) "Micro: 512x512 float matmul, real CPU work. No model needed."
            else "Full pipeline: validates prerequisites, reports honest error if unsupported. Do not confuse matmul ms with diffusion ms.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = state.warmup, onValueChange = vm::setWarmup, label = { Text("Warmup") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = state.measured, onValueChange = vm::setMeasured, label = { Text("Measured (5)") }, modifier = Modifier.weight(1f))
        }

        if (state.isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(state.progress ?: "Running…")
            OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
        } else {
            Button(onClick = { vm.run(ctx) }) { Text("Run Benchmark") }
        }

        state.error?.let { Card { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) } }

        state.lastResult?.let { r ->
            BenchmarkResultCard(r, vm)
        }

        Text("Saved runs", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.benchmarks) { e ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${e.backendId} • ${e.mode} • ${java.util.Date(e.timestampMillis)}", style = MaterialTheme.typography.labelMedium)
                        val result = try { kotlinx.serialization.json.Json.decodeFromString<BenchmarkResult>(e.json) } catch (_: Exception) { null }
                        if (result != null) {
                            Text("Median: ${BenchmarkAggregator.toDisplayMs(result.medianNanos)} min ${BenchmarkAggregator.toDisplayMs(result.minNanos)} max ${BenchmarkAggregator.toDisplayMs(result.maxNanos)}")
                            if (result.error != null) Text("Error: ${result.error}", color = MaterialTheme.colorScheme.error)
                            Row {
                                TextButton(onClick = {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_TEXT, vm.exportJson(result))
                                    }
                                    ctx.startActivity(Intent.createChooser(intent, "Export JSON"))
                                }) { Text("Share JSON") }
                            }
                        } else Text(e.json.take(200))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(state: BenchmarkUiState, vm: BenchmarkViewModel) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            readOnly = true,
            value = state.models.find { it.id == state.selectedModelId }?.displayName ?: "No model (micro works without)",
            onValueChange = {},
            label = { Text("Model (optional for micro)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { vm.selectModel(null); expanded = false })
            state.models.forEach { m ->
                DropdownMenuItem(text = { Text("${m.displayName} [${m.format}]") }, onClick = { vm.selectModel(m.id); expanded = false })
            }
        }
    }
    if (state.mode == BenchmarkMode.FULL_PIPELINE && state.selectedModelId == null) {
        Text("Full pipeline requires a compatible model. Import one first.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BenchmarkResultCard(r: BenchmarkResult, vm: BenchmarkViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Result ${r.id.take(8)} • ${r.config.backendId} • ${r.config.mode}", style = MaterialTheme.typography.titleSmall)
            Text("Cold start: ${BenchmarkAggregator.toDisplayMs(r.coldStartNanos)} init: ${BenchmarkAggregator.toDisplayMs(r.initNanos)}")
            Text("Warmup: ${r.warmupNanos.map { BenchmarkAggregator.toDisplayMs(it) }}")
            Text("Inference: ${r.inferenceNanos.map { BenchmarkAggregator.toDisplayMs(it) }}")
            Text("Median ${BenchmarkAggregator.toDisplayMs(r.medianNanos)} min ${BenchmarkAggregator.toDisplayMs(r.minNanos)} max ${BenchmarkAggregator.toDisplayMs(r.maxNanos)}")
            Text("Backend status: ${r.backendStatus} verified=${r.backendVerified}")
            r.error?.let { Text("Note: $it", color = MaterialTheme.colorScheme.error) }
            Text("Export: share JSON button in saved list. Compare by viewing multiple saved runs side-by-side.")
        }
    }
}
