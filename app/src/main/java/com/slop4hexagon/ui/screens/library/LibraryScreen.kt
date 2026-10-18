package com.slop4hexagon.ui.screens.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.slop4hexagon.SlopApp
import com.slop4hexagon.data.db.ModelEntity
import com.slop4hexagon.domain.model.ModelFormat

@Composable
fun LibraryScreen(
    vm: LibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            val app = LocalContext.current.applicationContext as SlopApp
            @Suppress("UNCHECKED_CAST")
            return LibraryViewModel(app) as T
        }
    })
) {
    val state by vm.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.import(uri)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Model Library", style = MaterialTheme.typography.headlineSmall)
        Text("Import via Storage Access Framework. Large files streamed without heap load. Original files never deleted.", style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launcher.launch(arrayOf("*/*")) }) { Text("Import Model") }
            Text("Supports .onnx, .tflite, .dlc, .bin, .safetensors, .ckpt, .gguf (unknown = unsupported)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }

        if (state.importing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(); Text(state.importProgress ?: "Importing…") }
        }
        state.error?.let { Card { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) } }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.models) { m ->
                ModelCard(m, vm) { vm.showDetails(m.id) }
            }
            if (state.models.isEmpty()) {
                item { Card { Text("No models yet. Import a model file to see compatibility.", modifier = Modifier.padding(12.dp)) } }
            }
        }
    }

    state.selectedId?.let { id ->
        val entity = state.models.find { it.id == id }
        if (entity != null) {
            ModelDetailsDialog(entity, vm, onDismiss = vm::dismissDetails)
        }
    }
}

@Composable
private fun ModelCard(entity: ModelEntity, vm: LibraryViewModel, onClick: () -> Unit) {
    val format = try { ModelFormat.valueOf(entity.format) } catch (_: Exception) { ModelFormat.UNKNOWN }
    val compat = vm.compatibilityFor(entity)
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entity.displayName, style = MaterialTheme.typography.titleMedium)
            Text("${entity.originalFileName} • ${format.displayName} • ${entity.fileSizeBytes / 1024 / 1024} MB", style = MaterialTheme.typography.bodySmall)
            Text("Est. memory: ${entity.estimatedMemoryBytes?.let { "${it / 1024 / 1024} MB" } ?: "—"} • ${entity.sha256?.take(8) ?: "no hash"}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                compat.forEach { (backend, status) -> Text("$backend: $status", style = MaterialTheme.typography.labelSmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onClick() }) { Text("Details") }
                TextButton(onClick = { vm.delete(entity.id) }) { Text("Remove") }
            }
            if (format == ModelFormat.SAFETENSORS || format == ModelFormat.CKPT) {
                Text("Note: SD checkpoints contain UNet/VAE/text encoder. Import ≠ executable. Needs pipeline + conversion.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            if (format == ModelFormat.UNKNOWN) {
                Text("Unknown format: cannot execute. Shown as incompatible.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ModelDetailsDialog(entity: ModelEntity, vm: LibraryViewModel, onDismiss: () -> Unit) {
    val format = try { ModelFormat.valueOf(entity.format) } catch (_: Exception) { ModelFormat.UNKNOWN }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entity.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("File: ${entity.originalFileName}")
                Text("Format: ${format.displayName} (${entity.format})")
                Text("Size: ${entity.fileSizeBytes} bytes (${entity.fileSizeBytes / 1024 / 1024} MB)")
                Text("Stored: ${entity.storedPath}")
                Text("SHA256: ${entity.sha256}")
                Text("Imported: ${java.util.Date(entity.importTimestampMillis)}")
                Text("Est. memory: ${entity.estimatedMemoryBytes ?: "unknown"}")
                Text("Supported backends:")
                vm.compatibilityFor(entity).forEach { (k, v) -> Text("  $k: $v") }
                Text("Warnings:")
                if (entity.fileSizeBytes > 2_000_000_000L) Text("- File >2GB may exceed 8GB device memory")
                if (format == ModelFormat.GGUF) Text("- GGUF is for LLMs, not image pipelines")
                if (format == ModelFormat.UNKNOWN) Text("- Unknown format unsupported")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
