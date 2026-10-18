package com.slop4hexagon.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slop4hexagon.app.ui.appViewModel
import com.slop4hexagon.app.ui.common.BannerTone
import com.slop4hexagon.app.ui.common.InfoBanner
import com.slop4hexagon.app.ui.common.KeyValueRow
import com.slop4hexagon.app.ui.common.MonoBlock
import com.slop4hexagon.app.ui.common.SectionCard
import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.model.CompatibilityStatus
import com.slop4hexagon.core.model.ModelNames

@Composable
fun ModelDetailsScreen(modelId: String?, onBack: () -> Unit) {
    val vm: ModelLibraryViewModel = appViewModel(::ModelLibraryViewModel)
    val state by vm.state.collectAsState()
    val model = state.models.firstOrNull { it.id == modelId }

    if (model == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This model is no longer in the library.", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.TextButton(onClick = onBack) { Text("back to library") }
        }
        return
    }

    val inspection = model.inspection

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(model.displayName, subtitle = model.originalFileName) {
                KeyValueRow("Format", inspection.format.displayName)
                KeyValueRow("Magic confirmed", if (inspection.magicConfirmed) "yes" else "no (name-based)")
                KeyValueRow("File size", ModelNames.humanSize(model.sizeBytes))
                KeyValueRow("Tensor payload", ModelNames.humanSize(inspection.weightBytes))
                inspection.estimatedMemoryBytes?.let {
                    KeyValueRow(
                        "Estimated resident",
                        ModelNames.humanSize(it) + " (weights × 1.15; activations not included)",
                    )
                }
                KeyValueRow("SHA-256", model.sha256?.take(16)?.plus("…") ?: "not computed")
                KeyValueRow("Imported", java.text.DateFormat
                    .getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(model.importedAtEpochMs)))
                model.note?.let { KeyValueRow("Note", it) }
            }
        }

        item {
            SectionCard(
                "Compatibility per backend",
                subtitle = "Derived from the format registry and the engines that ship in this build. " +
                    "Being importable is not the same as being executable.",
            ) {
                BackendId.entries.forEach { backend ->
                    val compat = model.compatibilityFor(backend)
                    if (compat == null) {
                        KeyValueRow(backend.displayName, "no entry")
                    } else {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(backend.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    compat.status.humanLabel(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = when (compat.status) {
                                        CompatibilityStatus.LIKELY_SUPPORTED -> MaterialTheme.colorScheme.primary
                                        CompatibilityStatus.UNKNOWN,
                                        CompatibilityStatus.REQUIRES_ENGINE,
                                        CompatibilityStatus.REQUIRES_CONVERSION,
                                        -> MaterialTheme.colorScheme.tertiary
                                        CompatibilityStatus.UNSUPPORTED -> MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                            Text(
                                compat.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (inspection.components.isNotEmpty()) {
            item {
                SectionCard("Detected pipeline components", subtitle = "From tensor names inside the file") {
                    inspection.components.forEach { c ->
                        KeyValueRow(
                            c.component.displayName,
                            "${c.tensorCount} tensor(s) · ${ModelNames.humanSize(c.bytes)}",
                        )
                    }
                    if (!inspection.isSinglePipelineFile) {
                        InfoBanner(
                            "UNet + VAE + text encoder are not all present, so a full diffusion pipeline " +
                                "would need additional component files even with a pipeline engine.",
                            BannerTone.WARN,
                        )
                    }
                }
            }
        }

        if (inspection.tensors.isNotEmpty()) {
            item {
                SectionCard(
                    "Tensors (${inspection.tensors.size})",
                    subtitle = "First 40 entries; the header was parsed without loading tensor data",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        inspection.tensors.take(40).forEach { t ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(t.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text(
                                    "${t.dtype} ${t.shape.joinToString("x")} · ${ModelNames.humanSize(t.dataBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        (inspection.formatWarnings + inspection.inspectionWarnings).forEach { warning ->
            item { InfoBanner(warning, BannerTone.WARN) }
        }

        item {
            SectionCard("Stored record", subtitle = "Exactly what the library index keeps on disk") {
                MonoBlock(
                    com.slop4hexagon.core.model.ModelDescriptorJson.encode(model).toPrettyString()
                        .lines().take(60).joinToString("\n"),
                    maxLines = 60,
                )
            }
        }

        item { androidx.compose.material3.TextButton(onClick = onBack) { Text("back to library") } }
    }
}

private fun CompatibilityStatus.humanLabel(): String = when (this) {
    CompatibilityStatus.LIKELY_SUPPORTED -> "likely supported"
    CompatibilityStatus.REQUIRES_ENGINE -> "needs an engine"
    CompatibilityStatus.REQUIRES_CONVERSION -> "needs conversion"
    CompatibilityStatus.UNSUPPORTED -> "unsupported"
    CompatibilityStatus.UNKNOWN -> "unknown"
}
