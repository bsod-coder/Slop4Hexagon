package com.slop4hexagon.app.ui.generate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.slop4hexagon.app.generate.GenerationController
import com.slop4hexagon.app.ui.appViewModel
import com.slop4hexagon.app.ui.common.ActionRow
import com.slop4hexagon.app.ui.common.BannerTone
import com.slop4hexagon.app.ui.common.InfoBanner
import com.slop4hexagon.app.ui.common.KeyValueRow
import com.slop4hexagon.app.ui.common.ProgressRow
import com.slop4hexagon.app.ui.common.SectionCard
import com.slop4hexagon.app.ui.common.StatusChip
import com.slop4hexagon.core.backend.InferenceRequest
import com.slop4hexagon.core.model.ModelNames
import com.slop4hexagon.core.pipeline.PipelineRequestValidator
import com.slop4hexagon.core.pipeline.SettingSupport

@Composable
fun GenerateScreen(onOpenLibrary: () -> Unit) {
    val vm: GenerateViewModel = appViewModel(::GenerateViewModel)
    val state by vm.state.collectAsState()

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            vm.onSourceImagePicked(null, null)
        } else {
            val name = uri.lastPathSegment?.substringAfterLast('/')
            vm.onSourceImagePicked(uri, name)
        }
    }
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri -> vm.saveToUri(uri) }

    val executable = state.validation?.executable == true

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "Execution target",
                subtitle = "Everything below is reported against the backend actually selected at run time.",
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(state.backendLabel, style = MaterialTheme.typography.bodyMedium)
                    StatusChip(state.backendStatus)
                }
                SmallModelPicker(state, vm)
                if (state.models.isEmpty()) {
                    OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
                        Text("Import a model first (Model Library)")
                    }
                }
            }
        }

        item {
            SectionCard(title = "Prompt") {
                OutlinedTextField(
                    value = state.settings.prompt,
                    onValueChange = { v -> vm.updateSettings { it.copy(prompt = v) } },
                    label = { Text("Positive prompt") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Honoured(
                    supported = state.supportOf(PipelineRequestValidator.FIELD_NEGATIVE_PROMPT),
                    blockedNote = "The selected backend has no text encoder path, so a negative prompt " +
                        "would be silently ignored — not shown as an active control.",
                ) {
                    OutlinedTextField(
                        value = state.settings.negativePrompt,
                        onValueChange = { v -> vm.updateSettings { it.copy(negativePrompt = v) } },
                        label = { Text("Negative prompt") },
                        minLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            SectionCard(title = "Mode") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    InferenceRequest.ImageGenMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.settings.mode == mode,
                            onClick = { vm.updateSettings { it.copy(mode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, InferenceRequest.ImageGenMode.entries.size),
                        ) { Text(if (mode == InferenceRequest.ImageGenMode.TEXT_TO_IMAGE) "Text → image" else "Image → image") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { sourcePicker.launch(arrayOf("image/*")) }) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.height(18.dp))
                        Text("  source image")
                    }
                    Text(
                        state.sourceImageName ?: "none selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Honoured(
                    supported = state.supportOf(PipelineRequestValidator.FIELD_DENOISING),
                    blockedNote = "Denoising strength is a sampler parameter; no sampler exists in this build, " +
                        "so the value is not applied to anything.",
                ) {
                    Column {
                        Text("Denoising strength: %.2f".format(state.settings.denoisingStrength), style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = state.settings.denoisingStrength.toFloat(),
                            onValueChange = { v -> vm.updateSettings { it.copy(denoisingStrength = v.toDouble()) } },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Output & sampling") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Width", state.settings.width) { v -> vm.updateSettings { it.copy(width = v) } }
                    NumberField("Height", state.settings.height) { v -> vm.updateSettings { it.copy(height = v) } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Steps", state.settings.steps) { v -> vm.updateSettings { it.copy(steps = v) } }
                    NumberField("Seed", state.settings.seed.toInt()) { v -> vm.updateSettings { it.copy(seed = v.toLong()) } }
                }
                val geomBlocked = state.validation?.errors?.any { it.contains("multiples of 8") } == true
                if (geomBlocked) {
                    InfoBanner(
                        "Latent-space pipelines require multiples of ${PipelineRequestValidator.VAE_DOWNSCALE}; " +
                            "the field stays editable because a future engine can honour it.",
                        BannerTone.WARN,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Run") {
                ActionRow {
                    Button(onClick = vm::run, enabled = !state.running) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text(if (state.running) "  running…" else "  generate")
                    }
                    OutlinedButton(onClick = vm::cancel, enabled = state.running) {
                        Icon(Icons.Default.Stop, null)
                        Text("  cancel")
                    }
                }
                state.progress?.let { p ->
                    ProgressRow(p.fraction, p.label)
                }
                if (!executable) {
                    state.validation?.let { v ->
                        if (v.errors.isNotEmpty()) {
                            InfoBanner("Invalid input:\n" + v.errors.joinToString("\n— ", "— "), BannerTone.ERROR)
                        }
                        if (v.blockers.isNotEmpty()) {
                            InfoBanner(
                                "Cannot execute in this build:\n" + v.blockers.joinToString("\n— ", "— ") +
                                    "\n\nSettings marked as not honoured are listed for transparency; " +
                                    "no placeholder image is produced in their place.",
                                BannerTone.WARN,
                            )
                        }
                    }
                }
                when (val outcome = state.outcome) {
                    is GenerationController.Outcome.Failed ->
                        InfoBanner(
                            "Backend reported failure after " + "%.1f".format(outcome.latencyMillis) +
                                " ms on ${outcome.backendLabel}:\n" + outcome.message,
                            BannerTone.ERROR,
                        )
                    is GenerationController.Outcome.Executed -> ExecutedPreview(outcome, vm, savePicker)
                    else -> Unit
                }
                state.savedMessage?.let { InfoBanner(it, BannerTone.OK) }
            }
        }
    }
}

@Composable
private fun ExecutedPreview(
    outcome: GenerationController.Outcome.Executed,
    vm: GenerateViewModel,
    savePicker: androidx.activity.result.ActivityResultLauncher<String>,
) {
    SectionCard(title = "Result") {
        Image(
            bitmap = outcome.bitmap.asImageBitmap(),
            contentDescription = "generated image",
            modifier = Modifier.fillMaxWidth().height(280.dp),
            contentScale = ContentScale.Fit,
        )
        KeyValueRow("Backend used", outcome.backendLabel)
        KeyValueRow("Model used", outcome.modelLabel)
        KeyValueRow("Output", "${outcome.width}x${outcome.height}")
        KeyValueRow("Latency", "%.1f ms".format(outcome.latencyMillis))
        ActionRow {
            Button(onClick = vm::saveToPictures) { Text("Save to Pictures") }
            OutlinedButton(onClick = { savePicker.launch("slop4hexagon-%d.png".format(System.currentTimeMillis())) }) {
                Text("Save to folder…")
            }
        }
    }
}

/**
 * Shows [content] only when the selected backend can honour the setting;
 * otherwise renders an explicit explanation. This is how the app satisfies
 * "do not display settings the engine cannot honour" without hiding the reason.
 */
@Composable
private fun Honoured(
    supported: SettingSupport,
    blockedNote: String,
    content: @Composable () -> Unit,
) {
    when (supported) {
        SettingSupport.HONOURED -> content()
        SettingSupport.NOT_SUPPORTED -> InfoBanner("Not honoured here: $blockedNote", BannerTone.NEUTRAL)
        SettingSupport.MISSING_MODEL_PART ->
            InfoBanner("Needs a model part this file does not contain: $blockedNote", BannerTone.WARN)
    }
}

@Composable
private fun NumberField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.weight(1f),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun SmallModelPicker(state: GenerateViewModel.UiState, vm: GenerateViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.settings.modelId?.let { id -> state.models.firstOrNull { it.id == id } }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let { "${it.displayName} · ${ModelNames.humanSize(it.sizeBytes)}" }
                ?: "no model selected",
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${model.displayName}\n${model.inspection.format.displayName} · " +
                                ModelNames.humanSize(model.sizeBytes) +
                                if (model.inspection.isSinglePipelineFile) " · full pipeline file" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    onClick = {
                        vm.selectModel(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
