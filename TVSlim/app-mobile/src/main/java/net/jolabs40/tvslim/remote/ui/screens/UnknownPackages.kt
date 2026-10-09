package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.PackageOrigin
import net.jolabs40.tvslim.device.UnknownPackage
import net.jolabs40.tvslim.remote.R

/** Android robot green, readable in both themes. */
private val ANDROID_GREEN = Color(0xFF3DDC84)

/** Package origin: robot for Android, factory for the device (or chip) maker, grid for anything else. */
@Composable
fun OriginIcon(origin: PackageOrigin, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Icon(
        imageVector = when (origin) {
            PackageOrigin.ANDROID -> Icons.Filled.Android
            PackageOrigin.MAKER -> Icons.Filled.Factory
            PackageOrigin.OTHER -> Icons.Filled.Apps
        },
        contentDescription = originLabel(origin),
        modifier = modifier.size(size),
        tint = when (origin) {
            PackageOrigin.ANDROID -> ANDROID_GREEN
            PackageOrigin.MAKER -> MaterialTheme.colorScheme.tertiary
            PackageOrigin.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun originLabel(origin: PackageOrigin): String = stringResource(
    when (origin) {
        PackageOrigin.ANDROID -> R.string.origin_android
        PackageOrigin.MAKER -> R.string.origin_maker
        PackageOrigin.OTHER -> R.string.origin_other
    },
)

/**
 * Preinstalled packages the catalogue does not describe, grouped by publisher (`org.droidtv`) with a guessed origin.
 *
 * Read-only, since an unknown system package may drive the tuner or the remote. The list can be exported, and
 * proposed through the GitHub form when it holds a maker package.
 */
fun LazyListScope.unknownsSection(
    shown: List<UnknownPackage>,
    total: Int,
    onExport: () -> Unit,
    /** Null when the catalogue misses no maker package, so there is nothing worth proposing. */
    onSuggest: (() -> Unit)?,
) {
    item(key = "inconnus-entete") {
        UnknownsHeader(shown = shown.size, total = total, onExport = onExport, onSuggest = onSuggest)
    }
    shown.groupBy { it.family }.forEach { (family, members) ->
        item(key = "inconnus-famille-$family") {
            Text(
                text = "$family (${members.size})",
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(members, key = { "inconnu-${it.packageName}" }) { unknown -> UnknownPackageRow(unknown) }
    }
}

@Composable
private fun UnknownsHeader(shown: Int, total: Int, onExport: () -> Unit, onSuggest: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.unknown_title, shown),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.unknown_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PackageOrigin.ORDER.forEach { origin ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OriginIcon(origin, size = 16.dp)
                    Text(
                        text = originLabel(origin),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Two buttons share the phone's width; alone, the export button keeps its own.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sharedWidth = if (onSuggest != null) Modifier.weight(1f) else Modifier
            OutlinedButton(
                onClick = onExport,
                enabled = total > 0,
                modifier = sharedWidth,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.unknown_export), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            onSuggest?.let { suggest ->
                Button(onClick = suggest, modifier = sharedWidth, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.unknown_propose), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun UnknownPackageRow(unknown: UnknownPackage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OriginIcon(unknown.origin)
        Text(
            text = unknown.packageName,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (unknown.state == PackageState.DISABLED) {
            Text(
                text = stringResource(R.string.home_disabled_badge),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
