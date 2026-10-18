package com.slop4hexagon.app.ui.library

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.slop4hexagon.app.ui.appViewModel
import com.slop4hexagon.app.ui.common.BannerTone
import com.slop4hexagon.app.ui.common.FormatBadge
import com.slop4hexagon.app.ui.common.InfoBanner
import com.slop4hexagon.app.ui.common.KeyValueRow
import com.slop4hexagon.app.ui.common.ProgressRow
import com.slop4hexagon.app.ui.common.SectionCard
import com.slop4hexagon.core.model.ModelDescriptor
import com.slop4hexagon.core.model.ModelFormat
import com.slop4hexagon.core.model.ModelNames

@Composable
fun ModelLibraryScreen(onOpenDetails: (String) -> Unit) {
    val vm: ModelLibraryViewModel = appViewModel(::ModelLibraryViewModel)
    val state by vm.state.collectAsState()
    var pendingDeletion by remember { mutableStateOf<String?>(null) }

    // Accept model files by their real MIME types where the provider reports
    // them, plus */* as a fallback because most providers do not classify
    // .safetensors / .dlc at all.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "Import a model file",
                subtitle = "Files are streamed into app storage with a SHA-256 computed on the fly. " +
                    "Your original file is never modified or deleted.",
            ) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Text("  choose model file")
                }
                state.progress?.let { p ->
                    ProgressRow(
                        p.fraction().toFloat(),
                        "${p.phase.name.lowercase()} · ${ModelNames.humanSize(p.copiedBytes)}" +
                            if (p.totalBytes > 0) " of ${ModelNames.humanSize(p.totalBytes)}" else "",
                    )
                }
            }
        }

        items(state.messages.withIndex().toList(), key = { "msg-${it.index}" }) { indexed ->
            val message = indexed.value
            InfoBanner(
                message.text,
                when (message.tone) {
                    ModelLibraryViewModel.UiMessage.Tone.INFO -> BannerTone.OK
                    ModelLibraryViewModel.UiMessage.Tone.WARN -> BannerTone.WARN
                    ModelLibraryViewModel.UiMessage.Tone.ERROR -> BannerTone.ERROR
                },
            )
        }

        item {
            HorizontalDivider()
            Text(
                "Library (${state.models.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (state.models.isEmpty()) {
            item {
                InfoBanner(
                    "No models imported. Accepts SafeTensors, ONNX, TFLite, GGUF, QNN context " +
                        "binaries and .dlc containers; anything unrecognised is refused rather than " +
                        "stored as a model.\n\nImporting a file only records and inspects it — see the " +
                        "compatibility table in each entry for whether an engine can execute it.",
                    BannerTone.NEUTRAL,
                )
            }
        }

        items(state.models, key = { it.id }) { model ->
            ModelRow(
                model = model,
                onDetails = { onOpenDetails(model.id) },
                onDelete = { pendingDeletion = model.id },
            )
        }
    }

    pendingDeletion?.let { id ->
        val model = state.models.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Remove from app storage?") },
            text = {
                Text(
                    "This deletes only the copy this app imported" +
                        (model?.let { " (${ModelNames.humanSize(it.sizeBytes)})" } ?: "") +
                        ".\n\nYour original file stays where it is; nothing outside the app's private " +
                        "directory is touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.remove(id); pendingDeletion = null }) { Text("Remove app copy") }
            },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModelRow(model: ModelDescriptor, onDetails: () -> Unit, onDelete: () -> Unit) {
    val inspection = model.inspection
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(model.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                FormatBadge(inspection.format)
            }
            KeyValueRow("File", model.originalFileName)
            KeyValueRow("Size", ModelNames.humanSize(model.sizeBytes))
            KeyValueRow("Weights detected", ModelNames.humanSize(inspection.weightBytes))
            if (inspection.tensorCount > 0) {
                KeyValueRow("Tensors", inspection.tensorCount.toString())
            }
            inspection.estimatedMemoryBytes?.let { KeyValueRow("Est. resident memory", ModelNames.humanSize(it)) }
            KeyValueRow("Executable here?", executionSummary(model))
            if (inspection.isSinglePipelineFile) {
                InfoBanner(
                    "This file contains UNet + VAE + text encoder tensors, i.e. a complete pipeline " +
                        "weights set. No pipeline engine ships in this build, so it still cannot run.",
                    BannerTone.NEUTRAL,
                )
            }
            (inspection.formatWarnings + inspection.inspectionWarnings).firstOrNull()?.let {
                InfoBanner(it, BannerTone.WARN)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onDetails) { Icon(Icons.Default.Info, "details") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "remove") }
            }
        }
    }
}

private fun executionSummary(model: ModelDescriptor): String {
    val cpu = model.compatibilityFor(com.slop4hexagon.core.backend.BackendId.CPU)
    return when {
        model.inspection.format == ModelFormat.UNKNOWN -> "refused as unknown format"
        cpu?.status?.name == "LIKELY_SUPPORTED" -> "reported likely supported on CPU"
        else -> "no — ${cpu?.status?.name?.replace('_', ' ').lowercase() ?: 'unknown'}"
    }
}
