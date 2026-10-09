package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.UnknownPackage
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.state_disabled
import net.jolabs40.tvslim.windows.resources.unknown_export
import net.jolabs40.tvslim.windows.resources.unknown_hint
import net.jolabs40.tvslim.windows.resources.unknown_propose
import net.jolabs40.tvslim.windows.resources.unknown_title
import net.jolabs40.tvslim.windows.ui.components.OriginIcon
import net.jolabs40.tvslim.windows.ui.components.OriginLegend
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import org.jetbrains.compose.resources.stringResource

/** Width of the checkbox column in the catalogue list, so unknown packages line up with it. */
private val CHECKBOX_COLUMN = 120.dp

/**
 * Below the catalogue, preinstalled packages it does not describe, grouped by vendor ("org.droidtv"), each
 * with its guessed origin. Read-only, since an unknown system package may run the tuner or the remote. The
 * list can be exported to extend the catalogue, and submitted through the GitHub form when it contains a
 * maker package.
 */
fun LazyListScope.unknownsSection(
    shown: List<UnknownPackage>,
    total: Int,
    onExport: () -> Unit,
    /** Null when no maker package is missing from the catalogue: nothing worth submitting. */
    onSuggest: (() -> Unit)?,
) {
    item(key = "unknowns-header") {
        UnknownsHeader(shown = shown.size, total = total, onExport = onExport, onSuggest = onSuggest)
    }
    shown.groupBy { it.family }.forEach { (family, members) ->
        item(key = "unknowns-family-$family") {
            Text(
                text = "$family (${members.size})",
                modifier = Modifier.padding(start = CHECKBOX_COLUMN, top = 10.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(members, key = { "unknown-${it.packageName}" }) { unknown -> UnknownPackageRow(unknown) }
    }
}

@Composable
private fun UnknownsHeader(shown: Int, total: Int, onExport: () -> Unit, onSuggest: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.unknown_title, shown),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(onClick = onExport, enabled = total > 0) {
                Text(stringResource(Res.string.unknown_export))
            }
            onSuggest?.let { suggest ->
                Button(onClick = suggest) { Text(stringResource(Res.string.unknown_propose)) }
            }
        }
        SecondaryText(stringResource(Res.string.unknown_hint), small = true)
        OriginLegend()
    }
}

@Composable
private fun UnknownPackageRow(unknown: UnknownPackage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 16.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The checkbox column stays empty: nothing to select here.
        Spacer(Modifier.width(CHECKBOX_COLUMN))
        OriginIcon(unknown.origin)
        Text(
            text = unknown.packageName,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (unknown.state == PackageState.DISABLED) {
            SecondaryText(stringResource(Res.string.state_disabled), small = true)
        }
    }
}
