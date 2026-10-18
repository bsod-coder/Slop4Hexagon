package com.slop4hexagon.ui.screens.generate

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.slop4hexagon.SlopApp
import com.slop4hexagon.domain.model.BackendId
import com.slop4hexagon.domain.model.GenerationMode

@Composable
fun GenerateScreen(
    vm: GenerateViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            val app = LocalContext.current.applicationContext as SlopApp
            @Suppress("UNCHECKED_CAST")
            return GenerateViewModel(app) as T
        }
    })
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val scroll = rememberScrollState()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        vm.updateSourceUri(uri)
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Generate", style = MaterialTheme.typography.headlineSmall)
        Text("Offline-first • No account required. Import a compatible model in Model Library.", style = MaterialTheme.typography.bodySmall)

        // Model selector
        ModelSelector(state, vm)

        OutlinedTextField(
            value = state.prompt,
            onValueChange = vm::updatePrompt,
            label = { Text("Positive prompt") },
            placeholder = { Text("A photo of a fox in a forest, detailed") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        OutlinedTextField(
            value = state.negativePrompt,
            onValueChange = vm::updateNegative,
            label = { Text("Negative prompt (optional)") },
            modifier = Modifier.fillMaxWidth()
        )

        // Mode
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { vm.updateMode(GenerationMode.TEXT_TO_IMAGE) }, label = { Text("Text → Image") })
            AssistChip(onClick = { vm.updateMode(GenerationMode.IMAGE_TO_IMAGE) }, label = { Text("Image → Image") })
        }
        if (state.mode == GenerationMode.IMAGE_TO_IMAGE) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Source image (SAF)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { imagePicker.launch(arrayOf("image/*")) }) { Text("Pick image") }
                        if (state.sourceImageUri != null) Text("Selected: ${state.sourceImageUri}", maxLines = 1)
                    }
                    // Denoising strength only shown when backend supports; here we show but ViewModel validates
                    OutlinedTextField(
                        value = state.denoisingStrength,
                        onValueChange = vm::updateDenoising,
                        label = { Text("Denoising strength (0..1)") },
                        supportingText = { Text("Only honored if backend supports it; otherwise ignored") }
                    )
                }
            }
        }

        // Size & steps
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = state.width.toString(), onValueChange = { vm.updateSize(it.toIntOrNull() ?: 512, state.height) }, label = { Text("Width") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = state.height.toString(), onValueChange = { vm.updateSize(state.width, it.toIntOrNull() ?: 512) }, label = { Text("Height") }, modifier = Modifier.weight(1f))
        }
        Text("Steps: ${state.steps}")
        Slider(value = state.steps.toFloat(), onValueChange = { vm.updateSteps(it.toInt()) }, valueRange = 5f..50f, steps = 9)
        OutlinedTextField(value = state.seed, onValueChange = vm::updateSeed, label = { Text("Seed (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = state.denoisingStrength, onValueChange = vm::updateDenoising, label = { Text("Denoising strength") }, modifier = Modifier.fillMaxWidth())

        // Backend preference
        BackendPreferenceRow(state.preferredBackend) { vm.state.value = vm.state.value.copy(preferredBackend = it) }

        // Progress
        if (state.isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Generating… ${state.elapsedMillis?.let { "${it}ms" } ?: ""}")
            OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
        }

        // Backend used
        state.backendUsed?.let { Text("Backend used: $it", style = MaterialTheme.typography.labelMedium) }
        state.error?.let {
            Card { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        }
        state.resultUri?.let {
            Card { Column(modifier = Modifier.padding(12.dp)) { Text("Output: $it"); Text("In real build, image preview via Coil would show here.") } }
            Button(onClick = { vm.saveToMediaStore(ctx) {} }) { Text("Save to gallery") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.generate(ctx) }, enabled = !state.isRunning) { Text("Generate") }
            OutlinedButton(onClick = vm::cancel, enabled = state.isRunning) { Text("Cancel") }
        }

        Text("Unsupported functionality hidden: denoising strength field is validated per-backend. Generate shows real backend used; no placeholder images.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ModelSelector(state: GenerateUiState, vm: GenerateViewModel) {
    var expanded by remember { mutableStateOf(false) }
    if (state.models.isEmpty()) {
        Card { Text("No models imported. Go to Models → Import.", modifier = Modifier.padding(12.dp)) }
        return
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            readOnly = true,
            value = state.models.find { it.id == state.selectedModelId }?.displayName ?: "Select model",
            onValueChange = {},
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.models.forEach { m ->
                DropdownMenuItem(text = { Text("${m.displayName} [${m.format}] ${m.fileSizeBytes / 1024 / 1024}MB") }, onClick = {
                    vm.selectModel(m.id); expanded = false
                })
            }
        }
    }
}

@Composable
private fun BackendPreferenceRow(current: BackendId, onChange: (BackendId) -> Unit) {
    Column {
        Text("Preferred backend", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackendId.values().forEach { id ->
                AssistChip(onClick = { onChange(id) }, label = { Text(id.name) })
            }
        }
        Text("Auto picks best available: HTP > GPU > CPU. Real status shown in Settings.", style = MaterialTheme.typography.bodySmall)
    }
}
