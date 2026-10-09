package net.jolabs40.tvslim.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jolabs40.tvslim.R
import net.jolabs40.tvslim.catalog.Risk
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.ui.UiState
import net.jolabs40.tvslim.ui.TvPackageRow
import net.jolabs40.tvslim.ui.components.ScrollBar
import net.jolabs40.tvslim.ui.components.InfoBlock
import net.jolabs40.tvslim.ui.components.ListCounter
import net.jolabs40.tvslim.ui.components.Header
import net.jolabs40.tvslim.ui.components.FocusableRow
import net.jolabs40.tvslim.ui.components.RiskDot

/**
 * Catalogue entries and their state on this TV, read-only. Enabling and disabling is done from the
 * companion, since ticking dozens of entries with a remote is what the app avoids.
 *
 * Two-pane TV layout: compact list on the left, details of the focused entry on the right, with no
 * scrolling text.
 */
@Composable
fun PackagesScreen(state: UiState) {
    val lines = state.presentPackages
    val listState = rememberLazyListState()
    var preview by remember { mutableStateOf<TvPackageRow?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Header(
            title = stringResource(R.string.packages_title),
            subtitle = stringResource(R.string.packages_subtitle),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(
                    R.string.packages_counts,
                    lines.size,
                    state.disabledPackages,
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            ListCounter(
                state = listState,
                total = lines.size,
                currentIndex = lines.indexOfFirst { it.entry.packageName == preview?.entry?.packageName },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1.3f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(lines, key = { it.entry.packageName }) { line ->
                    FocusableRow(
                        onClick = { preview = line },
                        modifier = Modifier.onFocusChanged { focus ->
                            if (focus.isFocused) preview = line
                        },
                    ) {
                        CompactRow(line)
                    }
                }
            }

            ScrollBar(state = listState, modifier = Modifier.width(3.dp))

            InfoBlock(modifier = Modifier.weight(1f)) {
                PackagePreview(line = preview)
            }
        }
    }
}

/** List row: just enough to recognize the entry and its state. */
@Composable
private fun CompactRow(line: TvPackageRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RiskDot(line.entry.risk, Modifier.padding(end = 10.dp))
        Text(
            text = line.entry.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(
                if (line.state == PackageState.DISABLED) {
                    R.string.state_disabled
                } else {
                    R.string.state_active
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (line.state == PackageState.DISABLED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

/** Right pane: everything the catalogue knows about the focused entry. */
@Composable
private fun PackagePreview(line: TvPackageRow?) {
    if (line == null) {
        Text(
            text = stringResource(R.string.packages_browse_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        text = line.entry.name,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = line.entry.packageName,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = line.entry.description,
        style = MaterialTheme.typography.bodyMedium,
    )
    line.entry.sideEffect?.let { effect ->
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.side_effect_prefix, effect),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(14.dp))
    Detail(
        stringResource(R.string.packages_state),
        stringResource(
            if (line.state == PackageState.DISABLED) {
                R.string.state_disabled
            } else {
                R.string.state_active
            },
        ),
    )
    Detail(stringResource(R.string.packages_risk), riskLabel(line.entry.risk))
    line.entry.sizeMb?.let { size ->
        Detail(stringResource(R.string.packages_size), stringResource(R.string.size_mb, size))
    }
    if (line.entry.brand.isNotBlank()) {
        Detail(stringResource(R.string.packages_origin), line.entry.brand)
    }
}

@Composable
private fun Detail(label: String, rawValue: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = rawValue, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun riskLabel(risk: Risk): String = stringResource(
    when (risk) {
        Risk.NONE -> R.string.risk_none
        Risk.LOW -> R.string.risk_low
        Risk.MEDIUM -> R.string.risk_medium
        Risk.HIGH -> R.string.risk_high
    },
)

