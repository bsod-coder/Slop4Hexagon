package com.slop4hexagon.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.slop4hexagon.core.backend.BackendStatus
import com.slop4hexagon.core.model.ModelFormat
import com.slop4hexagon.core.model.ModelNames

/**
 * Colour + icon coding of the six backend states. The distinction between
 * "probed/available", "initialized" and "verified execution" is the whole point
 * of this app's diagnostics, so it is visible at a glance everywhere.
 */
data class StatusVisuals(val label: String, val color: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun statusVisuals(status: BackendStatus): StatusVisuals {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        BackendStatus.UNSUPPORTED -> StatusVisuals("Unsupported", scheme.outline, Icons.Default.TrendingFlat)
        BackendStatus.UNAVAILABLE -> StatusVisuals("Unavailable", scheme.error, Icons.Default.Error)
        BackendStatus.AVAILABLE -> StatusVisuals("Available", scheme.tertiary, Icons.Default.HourglassTop)
        BackendStatus.INITIALIZED -> StatusVisuals("Initialized", scheme.secondary, Icons.Default.HourglassTop)
        BackendStatus.VERIFIED_EXECUTION -> StatusVisuals("Verified execution", scheme.primary, Icons.Default.CheckCircle)
        BackendStatus.FAILED -> StatusVisuals("Failed", scheme.error, Icons.Default.Error)
        BackendStatus.FALLBACK_OCCURRED -> StatusVisuals("Fallback in use", scheme.tertiary, Icons.Default.Error)
    }
}

@Composable
fun StatusChip(status: BackendStatus, modifier: Modifier = Modifier) {
    val visuals = statusVisuals(status)
    Surface(
        color = visuals.color.copy(alpha = 0.14f),
        contentColor = visuals.color,
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            androidx.compose.material3.Icon(visuals.icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(visuals.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            content()
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(0.58f),
        )
    }
}

@Composable
fun InfoBanner(text: String, tone: BannerTone = BannerTone.NEUTRAL, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val bg = when (tone) {
        BannerTone.NEUTRAL -> scheme.surfaceVariant
        BannerTone.WARN -> scheme.tertiaryContainer
        BannerTone.ERROR -> scheme.errorContainer
        BannerTone.OK -> scheme.primaryContainer
    }
    val fg = when (tone) {
        BannerTone.NEUTRAL -> scheme.onSurfaceVariant
        BannerTone.WARN -> scheme.onTertiaryContainer
        BannerTone.ERROR -> scheme.onErrorContainer
        BannerTone.OK -> scheme.onPrimaryContainer
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

enum class BannerTone { NEUTRAL, WARN, ERROR, OK }

@Composable
fun ProgressRow(fraction: Float?, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (fraction != null) {
                Text("%.0f%%".format(fraction * 100f), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = ProgressIndicatorDefaults.linearColor,
                trackColor = ProgressIndicatorDefaults.linearColor.copy(alpha = 0.15f),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = 12) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(12.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = maxLines,
            )
        }
    }
}

@Composable
fun FormatBadge(format: ModelFormat, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    ) {
        Text(
            ModelNames.shortFormatName(format),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun SmallLinkButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, style = MaterialTheme.typography.labelLarge) }
}
